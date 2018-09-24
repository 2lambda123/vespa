// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.prelude.fastsearch;

import com.yahoo.fs4.BasicPacket;
import com.yahoo.fs4.ChannelTimeoutException;
import com.yahoo.fs4.DocsumPacket;
import com.yahoo.fs4.GetDocSumsPacket;
import com.yahoo.fs4.Packet;
import com.yahoo.fs4.QueryPacket;
import com.yahoo.fs4.QueryResultPacket;
import com.yahoo.fs4.mplex.Backend;
import com.yahoo.fs4.mplex.FS4Channel;
import com.yahoo.fs4.mplex.InvalidChannelException;
import com.yahoo.prelude.fastsearch.VespaBackEndSearcher.FillHitsResult;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.dispatch.CloseableChannel;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.search.result.Hit;
import com.yahoo.search.result.HitGroup;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.yahoo.prelude.fastsearch.VespaBackEndSearcher.hitIterator;
import static java.util.Arrays.asList;

/**
 * {@link CloseableChannel} implementation for FS4 nodes and fdispatch
 *
 * @author ollivir
 */
public class FS4CloseableChannel extends CloseableChannel {
    private final VespaBackEndSearcher searcher;
    private FS4Channel channel;
    private final Optional<Integer> distributionKey;

    private ErrorMessage pendingSearchError = null;
    private Query query = null;
    private QueryPacket queryPacket = null;

    private int expectedFillResults = 0;
    private CacheKey summaryCacheKey = null;
    private DocsumPacketKey[] summaryPacketKeys = null;

    public FS4CloseableChannel(VespaBackEndSearcher searcher, Query query, FS4ResourcePool fs4ResourcePool, String hostname, int port,
            int distributionKey) {
        this.searcher = searcher;
        this.distributionKey = Optional.of(distributionKey);

        Backend backend = fs4ResourcePool.getBackend(hostname, port);
        this.channel = backend.openChannel();
        channel.setQuery(query);
    }

    // fdispatch code path
    public FS4CloseableChannel(VespaBackEndSearcher searcher, Query query, Backend backend) {
        this.searcher = searcher;
        this.distributionKey = Optional.empty();
        this.channel = backend.openChannel();
        channel.setQuery(query);
    }

    @Override
    protected void sendSearchRequest(Query query, QueryPacket queryPacket) throws IOException {
        if (isLoggingFine())
            getLogger().finest("sending query packet");

        if(queryPacket == null) {
            // query changed for subchannel
            queryPacket = searcher.createQueryPacket(query);
        }

        this.query = query;
        this.queryPacket = queryPacket;

        try {
            boolean couldSend = channel.sendPacket(queryPacket);
            if (!couldSend) {
                pendingSearchError = ErrorMessage.createBackendCommunicationError("Could not reach '" + getName() + "'");
            }
        } catch (InvalidChannelException e) {
            pendingSearchError = ErrorMessage.createBackendCommunicationError("Invalid channel " + getName());
        } catch (IllegalStateException e) {
            pendingSearchError = ErrorMessage.createBackendCommunicationError("Illegal state in FS4: " + e.getMessage());
        }
    }

    @Override
    protected List<Result> getSearchResults(CacheKey cacheKey) throws IOException {
        if(pendingSearchError != null) {
            return asList(new Result(query, pendingSearchError));
        }
        BasicPacket[] basicPackets;

        try {
            basicPackets = channel.receivePackets(query.getTimeLeft(), 1);
        } catch (ChannelTimeoutException e) {
            return asList(new Result(query, ErrorMessage.createTimeout("Timeout while waiting for " + getName())));
        } catch (InvalidChannelException e) {
            return asList(new Result(query, ErrorMessage.createBackendCommunicationError("Invalid channel for " + getName())));
        }

        if (basicPackets.length == 0) {
            return asList(new Result(query, ErrorMessage.createBackendCommunicationError(getName() + " got no packets back")));
        }

        if (isLoggingFine())
            getLogger().finest("got packets " + basicPackets.length + " packets");

        basicPackets[0].ensureInstanceOf(QueryResultPacket.class, getName());
        QueryResultPacket resultPacket = (QueryResultPacket) basicPackets[0];

        if (isLoggingFine())
            getLogger().finest("got query packet. " + "docsumClass=" + query.getPresentation().getSummary());

        if (query.getPresentation().getSummary() == null)
            query.getPresentation().setSummary(searcher.getDefaultDocsumClass());

        Result result = new Result(query);

        searcher.addMetaInfo(query, queryPacket.getQueryPacketData(), resultPacket, result);

        searcher.addUnfilledHits(result, resultPacket.getDocuments(), false, queryPacket.getQueryPacketData(), cacheKey, distributionKey);
        Packet[] packets;
        CacheControl cacheControl = searcher.getCacheControl();
        PacketWrapper packetWrapper = cacheControl.lookup(cacheKey, query);

        if (packetWrapper != null) {
            cacheControl.updateCacheEntry(cacheKey, query, resultPacket);
        } else {
            if (resultPacket.getCoverageFeature() && !resultPacket.getCoverageFull()) {
                // Don't add error here, it was done in first phase
                // No check if packetWrapper already exists, since incomplete
                // first phase data won't be cached anyway.
            } else {
                packets = new Packet[1];
                packets[0] = resultPacket;
                cacheControl.cache(cacheKey, query, new DocsumPacketKey[0], packets, distributionKey);
            }
        }
        return asList(result);
    }

    @Override
    protected void sendPartialFillRequest(Result result, String summaryClass) {
        summaryCacheKey = null;
        if (searcher.getCacheControl().useCache(channel.getQuery())) {
            summaryCacheKey = fetchCacheKeyFromHits(result.hits(), summaryClass);
            if (summaryCacheKey == null) {
                QueryPacket queryPacket = QueryPacket.create(channel.getQuery());
                summaryCacheKey = new CacheKey(queryPacket);
            }
            boolean cacheFound = cacheLookupTwoPhase(summaryCacheKey, result, summaryClass);
            if (!cacheFound) {
                summaryCacheKey = null;
            }
        }

        if (countFastHits(result) > 0) {
            summaryPacketKeys = getPacketKeys(result, summaryClass);
            if (summaryPacketKeys.length == 0) {
                expectedFillResults = 0;
            } else {
                try {
                    expectedFillResults = requestSummaries(result, summaryClass);
                } catch (InvalidChannelException e) {
                    result.hits()
                            .addError(ErrorMessage.createBackendCommunicationError("Invalid channel " + getName() + " (summary fetch)"));
                    return;
                } catch (IOException e) {
                    result.hits().addError(ErrorMessage.createBackendCommunicationError(
                            "IO error while talking on channel " + getName() + " (summary fetch): " + e.getMessage()));
                    return;
                }
            }
        } else {
            expectedFillResults = 0;
        }
    }


    @Override
    protected void getPartialFillResults(Result result, String summaryClass) {
        if (expectedFillResults == 0) {
            return;
        }

        Packet[] receivedPackets;
        try {
            receivedPackets = getSummaryResponses(result);
        } catch (InvalidChannelException e1) {
            result.hits().addError(ErrorMessage.createBackendCommunicationError("Invalid channel " + getName() + " (summary fetch)"));
            return;
        } catch (ChannelTimeoutException e1) {
            result.hits().addError(ErrorMessage.createTimeout("timeout waiting for summaries from " + getName()));
            return;
        }

        if (receivedPackets.length == 0) {
            result.hits().addError(ErrorMessage.createBackendCommunicationError(getName() + " got no packets back (summary fetch)"));
            return;
        }

        int skippedHits;
        try {
            FillHitsResult fillHitsResult = searcher.fillHits(result, receivedPackets, summaryClass);
            skippedHits = fillHitsResult.skippedHits;
            if (fillHitsResult.error != null) {
                result.hits().addError(ErrorMessage.createTimeout(fillHitsResult.error));
                return;
            }
        } catch (TimeoutException e) {
            result.hits().addError(ErrorMessage.createTimeout(e.getMessage()));
            return;
        } catch (IOException e) {
            result.hits().addError(ErrorMessage.createBackendCommunicationError(
                    "Error filling hits with summary fields, source: " + getName() + " Exception thrown: " + e.getMessage()));
            return;
        }
        if (skippedHits == 0 && summaryCacheKey != null) {
            searcher.getCacheControl().updateCacheEntry(summaryCacheKey, channel.getQuery(), summaryPacketKeys, receivedPackets);
        }

        if (skippedHits > 0)
            result.hits().addError(
                    ErrorMessage.createEmptyDocsums("Missing hit data for summary '" + summaryClass + "' for " + skippedHits + " hits"));
        result.analyzeHits();

        if (channel.getQuery().getTraceLevel() >= 3) {
            int hitNumber = 0;
            for (Iterator<com.yahoo.search.result.Hit> i = hitIterator(result); i.hasNext();) {
                com.yahoo.search.result.Hit hit = i.next();
                if (!(hit instanceof FastHit))
                    continue;
                FastHit fastHit = (FastHit) hit;

                String traceMsg = "Hit: " + (hitNumber++) + " from " + (fastHit.isCached() ? "cache" : "backend");
                if (!fastHit.isFilled(summaryClass))
                    traceMsg += ". Error, hit, not filled";
                channel.getQuery().trace(traceMsg, false, 3);
            }
        }
    }

    @Override
    public void closeChannel() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }

    private boolean cacheLookupTwoPhase(CacheKey cacheKey, Result result, String summaryClass) {
        Query query = result.getQuery();
        PacketWrapper packetWrapper = searcher.getCacheControl().lookup(cacheKey, query);

        if (packetWrapper == null) {
            return false;
        }
        if (packetWrapper.getNumPackets() != 0) {
            for (Iterator<Hit> i = hitIterator(result); i.hasNext();) {
                Hit hit = i.next();

                if (hit instanceof FastHit) {
                    FastHit fastHit = (FastHit) hit;
                    DocsumPacketKey key = new DocsumPacketKey(fastHit.getGlobalId(), fastHit.getPartId(), summaryClass);

                    if (searcher.fillHit(fastHit, (DocsumPacket) packetWrapper.getPacket(key), summaryClass).ok) {
                        fastHit.setCached(true);
                    }

                }
            }
            result.hits().setSorted(false);
            result.analyzeHits();
        }

        return true;
    }

    private CacheKey fetchCacheKeyFromHits(HitGroup hits, String summaryClass) {
        for (Iterator<Hit> i = hits.unorderedDeepIterator(); i.hasNext();) {
            Hit h = i.next();
            if (h instanceof FastHit) {
                FastHit hit = (FastHit) h;
                if (hit.isFilled(summaryClass)) {
                    continue;
                }
                if (hit.getCacheKey() != null) {
                    return hit.getCacheKey();
                }
            }
        }
        return null;
    }

    private int countFastHits(Result result) {
        int count = 0;
        for (Iterator<Hit> i = hitIterator(result); i.hasNext();) {
            if (i.next() instanceof FastHit)
                count++;
        }
        return count;
    }

    private int requestSummaries(Result result, String summaryClass) throws InvalidChannelException, ClassCastException, IOException {

        boolean summaryNeedsQuery = searcher.summaryNeedsQuery(result.getQuery());
        if (result.getQuery().getTraceLevel() >= 3)
            result.getQuery().trace((summaryNeedsQuery ? "Resending " : "Not resending ") + "query during document summary fetching", 3);

        GetDocSumsPacket docsumsPacket = GetDocSumsPacket.create(result, summaryClass, summaryNeedsQuery);
        int compressionLimit = result.getQuery().properties().getInteger(FastSearcher.PACKET_COMPRESSION_LIMIT, 0);
        docsumsPacket.setCompressionLimit(compressionLimit);
        if (compressionLimit != 0) {
            docsumsPacket.setCompressionType(result.getQuery().properties().getString(FastSearcher.PACKET_COMPRESSION_TYPE, "lz4"));
        }

        boolean couldSend = channel.sendPacket(docsumsPacket);
        if (!couldSend)
            throw new IOException("Could not successfully send GetDocSumsPacket.");

        return docsumsPacket.getNumDocsums() + 1;
    }

    private Packet[] getSummaryResponses(Result result) throws InvalidChannelException, ChannelTimeoutException {
        if(expectedFillResults == 0) {
            return new Packet[0];
        }
        BasicPacket[] receivedPackets = channel.receivePackets(result.getQuery().getTimeLeft(), expectedFillResults);

        return convertBasicPackets(receivedPackets);
    }

    /**
     * Returns an array of the hits contained in a result
     *
     * @return array of docids, empty array if no hits
     */
    private DocsumPacketKey[] getPacketKeys(Result result, String summaryClass) {
        DocsumPacketKey[] packetKeys = new DocsumPacketKey[result.getHitCount()];
        int x = 0;

        for (Iterator<com.yahoo.search.result.Hit> i = hitIterator(result); i.hasNext();) {
            com.yahoo.search.result.Hit hit = i.next();
            if (hit instanceof FastHit) {
                FastHit fastHit = (FastHit) hit;
                if (!fastHit.isFilled(summaryClass)) {
                    packetKeys[x] = new DocsumPacketKey(fastHit.getGlobalId(), fastHit.getPartId(), summaryClass);
                    x++;
                }
            }
        }
        if (x < packetKeys.length) {
            DocsumPacketKey[] tmp = new DocsumPacketKey[x];

            System.arraycopy(packetKeys, 0, tmp, 0, x);
            return tmp;
        } else {
            return packetKeys;
        }
    }

    private static Packet[] convertBasicPackets(BasicPacket[] basicPackets) throws ClassCastException {
        // trying to cast a BasicPacket[] to Packet[] will compile,
        // but lead to a runtime error. At least that's what I got
        // from testing and reading the specification. I'm just happy
        // if someone tells me what's the proper Java way of doing
        // this. -SK
        Packet[] packets = new Packet[basicPackets.length];

        for (int i = 0; i < basicPackets.length; i++) {
            packets[i] = (Packet) basicPackets[i];
        }
        return packets;
    }

    private String getName() {
        return searcher.getName();
    }

    private Logger getLogger() {
        return searcher.getLogger();
    }

    private boolean isLoggingFine() {
        return getLogger().isLoggable(Level.FINE);
    }
}
