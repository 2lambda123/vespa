// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.streamingvisitors;

import com.yahoo.document.select.OrderingSpecification;
import com.yahoo.document.select.parser.ParseException;
import com.yahoo.documentapi.AckToken;
import com.yahoo.documentapi.DocumentAccess;
import com.yahoo.documentapi.VisitorControlHandler;
import com.yahoo.documentapi.VisitorDataHandler;
import com.yahoo.documentapi.VisitorParameters;
import com.yahoo.documentapi.VisitorSession;
import com.yahoo.documentapi.messagebus.MessageBusDocumentAccess;
import com.yahoo.documentapi.messagebus.MessageBusParams;
import com.yahoo.documentapi.messagebus.loadtypes.LoadType;
import com.yahoo.documentapi.messagebus.loadtypes.LoadTypeSet;
import com.yahoo.documentapi.messagebus.protocol.DocumentProtocol;
import com.yahoo.documentapi.messagebus.protocol.DocumentSummaryMessage;
import com.yahoo.documentapi.messagebus.protocol.QueryResultMessage;
import com.yahoo.documentapi.messagebus.protocol.SearchResultMessage;
import com.yahoo.io.GrowableByteBuffer;
import com.yahoo.log.LogLevel;
import com.yahoo.messagebus.Message;
import com.yahoo.messagebus.routing.Route;
import com.yahoo.prelude.fastsearch.TimeoutException;
import com.yahoo.processing.request.CompoundName;
import com.yahoo.search.Query;
import com.yahoo.search.grouping.vespa.GroupingExecutor;
import com.yahoo.search.query.Model;
import com.yahoo.search.query.Ranking;
import com.yahoo.searchlib.aggregation.Grouping;
import com.yahoo.vdslib.DocumentSummary;
import com.yahoo.vdslib.SearchResult;
import com.yahoo.vdslib.VisitorStatistics;
import com.yahoo.vespa.objects.BufferSerializer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * A visitor data handler that performs a query in VDS with the
 * searchvisitor visitor plugin. It collects and merges hits (sorted
 * descending on rank), summaries (sorted on document id), and
 * groupings. The resulting data can be fetched when the query has
 * completed.
 *
 * @author Ulf Carlin
 */
class VdsVisitor extends VisitorDataHandler implements Visitor {

    private static final CompoundName streamingUserid=new CompoundName("streaming.userid");
    private static final CompoundName streamingGroupname=new CompoundName("streaming.groupname");
    private static final CompoundName streamingSelection=new CompoundName("streaming.selection");
    private static final CompoundName streamingHeadersonly=new CompoundName("streaming.headersonly");
    private static final CompoundName streamingFromtimestamp=new CompoundName("streaming.fromtimestamp");
    private static final CompoundName streamingTotimestamp=new CompoundName("streaming.totimestamp");
    private static final CompoundName streamingLoadtype=new CompoundName("streaming.loadtype");
    private static final CompoundName streamingPriority=new CompoundName("streaming.priority");
    private static final CompoundName streamingOrdering=new CompoundName("streaming.ordering");
    private static final CompoundName streamingMaxbucketspervisitor=new CompoundName("streaming.maxbucketspervisitor");

    private static final Logger log = Logger.getLogger(VdsVisitor.class.getName());
    private final VisitorParameters params = new VisitorParameters("");
    private List<SearchResult.Hit> hits = new ArrayList<>();
    private int totalHitCount = 0;

    private final Map<String, DocumentSummary.Summary> summaryMap = new HashMap<>();
    private final Map<Integer, Grouping> groupingMap = new ConcurrentHashMap<>();
    private Query query = null;
    private VisitorSessionFactory visitorSessionFactory;

    static int getOrdering(String ordering) {
        if (ordering.equals("+")) {
            return OrderingSpecification.ASCENDING;
        } else if (ordering.equals("-")) {
            return OrderingSpecification.DESCENDING;
        } else {
            throw new RuntimeException("Ordering must be on the format {+/-}");
        }
    }

    public interface VisitorSessionFactory {
        VisitorSession createVisitorSession(VisitorParameters params) throws ParseException;
        LoadTypeSet getLoadTypeSet();
    }

    private static class MessageBusVisitorSessionFactory implements VisitorSessionFactory {
        private static final LoadTypeSet loadTypes = new LoadTypeSet("client");
        private static final DocumentAccess access = new MessageBusDocumentAccess(new MessageBusParams(loadTypes));

        @Override
        public VisitorSession createVisitorSession(VisitorParameters params) throws ParseException {
            return access.createVisitorSession(params);
        }

        @Override
        public LoadTypeSet getLoadTypeSet() {
            return loadTypes;
        }
    }

    public VdsVisitor(Query query, String searchCluster, Route route) {
        this.query = query;
        visitorSessionFactory = new MessageBusVisitorSessionFactory();
        setVisitorParameters(searchCluster, route);
    }

    public VdsVisitor(Query query, String searchCluster, Route route, VisitorSessionFactory visitorSessionFactory) {
        this.query = query;
        this.visitorSessionFactory = visitorSessionFactory;
        setVisitorParameters(searchCluster, route);
    }

    private void setVisitorParameters(String searchCluster, Route route) {
        if (query.properties().getString(streamingUserid) != null) {
            params.setDocumentSelection("id.user==" + query.properties().getString(streamingUserid));
        } else if (query.properties().getString(streamingGroupname) != null) {
            params.setDocumentSelection("id.group==\"" + query.properties().getString(streamingGroupname) + "\"");
        } else if (query.properties().getString(streamingSelection) != null) {
            params.setDocumentSelection(query.properties().getString(streamingSelection));
        }
        params.setTimeoutMs(query.getTimeout());
        params.setVisitorLibrary("searchvisitor");
        params.setLocalDataHandler(this);
        params.setVisitHeadersOnly(query.properties().getBoolean(streamingHeadersonly));
        if (query.properties().getDouble(streamingFromtimestamp) != null) {
            params.setFromTimestamp(query.properties().getDouble(streamingFromtimestamp).longValue());
        }
        if (query.properties().getDouble(streamingTotimestamp) != null) {
            params.setToTimestamp(query.properties().getDouble(streamingTotimestamp).longValue());
        }
        params.visitInconsistentBuckets(true);
        params.setPriority(DocumentProtocol.Priority.VERY_HIGH);

        if (query.properties().getString(streamingLoadtype) != null) {
            LoadType loadType = visitorSessionFactory.getLoadTypeSet().getNameMap().get(query.properties().getString(streamingLoadtype));
            if (loadType != null) {
                params.setLoadType(loadType);
                params.setPriority(loadType.getPriority());
            }
        }

        if (query.properties().getString(streamingPriority) != null) {
            params.setPriority(DocumentProtocol.getPriorityByName(
                    query.properties().getString(streamingPriority)));
        }

        params.setMaxPending(Integer.MAX_VALUE);
        params.setMaxBucketsPerVisitor(Integer.MAX_VALUE);
        params.setTraceLevel(query.getTraceLevel());

        String ordering = query.properties().getString(streamingOrdering);
        if (ordering != null) {
            params.setVisitorOrdering(getOrdering(ordering));
            params.setMaxFirstPassHits(query.getOffset() + query.getHits());
            params.setMaxBucketsPerVisitor(1);
            params.setDynamicallyIncreaseMaxBucketsPerVisitor(true);
        }

        String maxbuckets = query.properties().getString(streamingMaxbucketspervisitor);
        if (maxbuckets != null) {
            params.setMaxBucketsPerVisitor(Integer.parseInt(maxbuckets));
        }

        EncodedData ed = new EncodedData();
        encodeQueryData(query, 0, ed);
        params.setLibraryParameter("query", ed.getEncodedData());
        params.setLibraryParameter("querystackcount", String.valueOf(ed.getReturned()));
        params.setLibraryParameter("searchcluster", searchCluster.getBytes());
        if (query.getPresentation().getSummary() != null) {
            params.setLibraryParameter("summaryclass", query.getPresentation().getSummary());
        } else {
            params.setLibraryParameter("summaryclass", "default");
        }
        params.setLibraryParameter("summarycount", String.valueOf(query.getOffset() + query.getHits()));
        params.setLibraryParameter("rankprofile", query.getRanking().getProfile());
        params.setLibraryParameter("queryflags", String.valueOf(getQueryFlags(query)));

        ByteBuffer buf = ByteBuffer.allocate(1024);

        if (query.getRanking().getLocation() != null) {
            buf.clear();
            query.getRanking().getLocation().encode(buf);
            buf.flip();
            byte[] af = new byte [buf.remaining()];
            buf.get(af);
            params.setLibraryParameter("location", af);
        }

        if (query.hasEncodableProperties()) {
            encodeQueryData(query, 1, ed);
            params.setLibraryParameter("rankproperties", ed.getEncodedData());
        }

        List<Grouping> groupingList = GroupingExecutor.getGroupingList(query);
        if (groupingList.size() > 0){
            BufferSerializer gbuf = new BufferSerializer(new GrowableByteBuffer());
            gbuf.putInt(null, groupingList.size());
            for(Grouping g: groupingList){
                g.serialize(gbuf);
            }
            gbuf.flip();
            byte [] blob = gbuf.getBytes(null, gbuf.getBuf().limit());
            params.setLibraryParameter("aggregation", blob);
        }

        if (query.getRanking().getSorting() != null) {
            encodeQueryData(query, 3, ed);
            params.setLibraryParameter("sort", ed.getEncodedData());
        }

        params.setRoute(route);
    }

    static int getQueryFlags(Query query) {
        int flags = 0;

        boolean requestCoverage=true; // Always request coverage information

        flags |= 0; // was collapse
        flags |= query.properties().getBoolean(Model.ESTIMATE) ? 0x00000080 : 0;
        flags |= (query.getRanking().getFreshness() != null) ? 0x00002000 : 0;
        flags |= requestCoverage ? 0x00008000 : 0;
        flags |= query.getNoCache() ? 0x00010000 : 0;
        flags |= 0x00020000;                         // was PARALLEL
        flags |= query.properties().getBoolean(Ranking.RANKFEATURES,false) ? 0x00040000 : 0;

        return flags;
    }

    private static class EncodedData {
        private Object returned;
        private byte[] encoded;

        public void setReturned(Object o){
            this.returned = o;
        }
        public Object getReturned(){
            return returned;
        }
        public void setEncodedData(byte[] data){
            encoded = data;
        }
        public byte[] getEncodedData(){
            return encoded;
        }
    }

    private static void encodeQueryData(Query query, int code, EncodedData ed){
        ByteBuffer buf = ByteBuffer.allocate(1024);
        while (true) {
            try {
                switch(code){
                    case 0:
                        ed.setReturned(query.getModel().getQueryTree().getRoot().encode(buf));
                        break;
                    case 1:
                        ed.setReturned(query.encodeAsProperties(buf, true));
                        break;
                    case 2:
                        throw new IllegalArgumentException("old aggregation no longer exists!");
                    case 3:
                        if (query.getRanking().getSorting() != null)
                            ed.setReturned(query.getRanking().getSorting().encode(buf));
                        else
                            ed.setReturned(0);
                        break;
                }
                buf.flip();
                break;
            } catch (java.nio.BufferOverflowException e) {
                int size = buf.limit();
                buf = ByteBuffer.allocate(size*2);
            }
        }
        byte [] bb = new byte [buf.remaining()];
        buf.get(bb);
        ed.setEncodedData(bb);
    }

    @Override
    public void doSearch() throws InterruptedException, ParseException, TimeoutException {
        VisitorSession session = visitorSessionFactory.createVisitorSession(params);
        try {
            if ( !session.waitUntilDone(query.getTimeout())) {
                log.log(LogLevel.DEBUG, "Visitor returned from waitUntilDone without being completed for " + query + " with selection " + params.getDocumentSelection());
                session.abort();
                throw new TimeoutException("Query timed out in " + VdsStreamingSearcher.class.getName());
            }
        } finally {
            session.destroy();
        }

        query.trace(session.getTrace().toString(), false, 9);

        if (params.getControlHandler().getResult().code == VisitorControlHandler.CompletionCode.SUCCESS) {
            if (log.isLoggable(LogLevel.DEBUG)) {
                log.log(LogLevel.DEBUG, "VdsVisitor completed successfully for " + query + " with selection " + params.getDocumentSelection());
            }
        } else {
            throw new IllegalArgumentException("Query failed: " // TODO: Is it necessary to use a runtime exception?
                    + params.getControlHandler().getResult().code + ": "
                    + params.getControlHandler().getResult().message);
        }
    }

    @Override
    public VisitorStatistics getStatistics() {
        return params.getControlHandler().getVisitorStatistics();
    }

    @Override
    public void onMessage(Message m, AckToken token) {
        if (m instanceof QueryResultMessage) {
            QueryResultMessage qm = (QueryResultMessage)m;
            onQueryResult(qm.getResult(), qm.getSummary());
        } else if (m instanceof SearchResultMessage) {
            onSearchResult(((SearchResultMessage) m).getResult());
        } else if (m instanceof DocumentSummaryMessage) {
            DocumentSummaryMessage dsm = (DocumentSummaryMessage)m;
            onDocumentSummary(dsm.getResult());
        } else {
            throw new UnsupportedOperationException("Received unsupported message " + m + ". VdsVisitor can only accept query result, search result, and documentsummary messages.");
        }
        ack(token);
    }

    public void onQueryResult(SearchResult sr, DocumentSummary summary) {
        handleSearchResult(sr);
        handleSummary(summary);
    }

    public void onSearchResult(SearchResult sr) {
        if (log.isLoggable(LogLevel.SPAM)) {
            log.log(LogLevel.SPAM, "Got SearchResult for query with selection " + params.getDocumentSelection());
        }
        handleSearchResult(sr);
    }

    private void handleSearchResult(SearchResult sr) {
        final int hitCountTotal = sr.getTotalHitCount();
        final int hitCount = sr.getHitCount();
        if (log.isLoggable(LogLevel.DEBUG)) {
            log.log(LogLevel.DEBUG, "Got SearchResult with " + hitCountTotal + " in total and " + hitCount + " hits in real for query with selection " + params.getDocumentSelection());
        }

        List<SearchResult.Hit> newHits = new ArrayList<>(hitCount);
        for (int i = 0; i < hitCount; i++) {
            SearchResult.Hit hit = sr.getHit(i);
            newHits.add(hit);
        }
        synchronized (this) {
            totalHitCount += hitCountTotal;
            hits = ListMerger.mergeIntoArrayList(hits, newHits, query.getOffset() + query.getHits());
        }

        Map<Integer, byte []> newGroupingMap = sr.getGroupingList();
        mergeGroupingMaps(newGroupingMap);
    }

    private void mergeGroupingMaps(Map<Integer, byte []> newGroupingMap) {
        if (log.isLoggable(LogLevel.SPAM)) {
            log.log(LogLevel.SPAM, "mergeGroupingMaps: newGroupingMap = " + newGroupingMap);
        }
        for(Integer key : newGroupingMap.keySet()) {
            byte [] value = newGroupingMap.get(key);

            Grouping newGrouping = new Grouping();
            if (log.isLoggable(LogLevel.SPAM)) {
                log.log(LogLevel.SPAM, "Received group with key " + key + " and size " + value.length);
            }
            BufferSerializer buf = new BufferSerializer( new GrowableByteBuffer(ByteBuffer.wrap(value)) );
            newGrouping.deserialize(buf);
            if (buf.getBuf().hasRemaining()) {
                throw new IllegalArgumentException("Failed deserializing grouping. There are still data left. Position = " + buf.position() + ", limit = " + buf.getBuf().limit());
            }

            synchronized (groupingMap) {
                if (groupingMap.containsKey(key)) {
                    Grouping grouping = groupingMap.get(key);
                    grouping.merge(newGrouping);
                } else {
                    groupingMap.put(key, newGrouping);
                }
            }
        }
    }

    public void onDocumentSummary(DocumentSummary ds) {
        if (log.isLoggable(LogLevel.SPAM)) {
            log.log(LogLevel.SPAM, "Got DocumentSummary for query with selection " + params.getDocumentSelection());
        }
        handleSummary(ds);
    }

    private void handleSummary(DocumentSummary ds) {
        int summaryCount = ds.getSummaryCount();
        if (log.isLoggable(LogLevel.DEBUG)) {
            log.log(LogLevel.DEBUG, "Got DocumentSummary with " + summaryCount + " summaries for query with selection " + params.getDocumentSelection());
        }
        synchronized (summaryMap) {
            for (int i = 0; i < summaryCount; i++) {
                DocumentSummary.Summary summary = ds.getSummary(i);
                summaryMap.put(summary.getDocId(), summary);
            }
        }
    }

    @Override
    final public List<SearchResult.Hit> getHits() {
        int fromIndex = Math.min(hits.size(), query.getOffset());
        int toIndex = Math.min(hits.size(), query.getOffset() + query.getHits());
        return hits.subList(fromIndex, toIndex);
    }

    @Override
    final public Map<String, DocumentSummary.Summary> getSummaryMap() { return summaryMap; }

    @Override
    final public int getTotalHitCount() { return totalHitCount; }

    @Override
    final public List<Grouping> getGroupings() {
        Collection<Grouping> groupings = groupingMap.values();
        for (Grouping g : groupings) {
            g.postMerge();
        }
        Grouping[] array = groupings.toArray(new Grouping[groupings.size()]);
        return Arrays.asList(array);
    }

}
