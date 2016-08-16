package com.yahoo.search.dispatch;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.yahoo.search.cluster.ClusterMonitor;
import com.yahoo.search.cluster.NodeManager;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.vespa.config.search.DispatchConfig;

// Only needed until query requests are moved to rpc
import com.yahoo.prelude.Ping;
import com.yahoo.prelude.fastsearch.FastSearcher;
import com.yahoo.yolean.Exceptions;
import com.yahoo.prelude.Pong;
import com.yahoo.prelude.fastsearch.FS4ResourcePool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A model of a search cluster we might want to dispatch queries to.
 * 
 * @author bratseth
 */
@Beta
public class SearchCluster implements NodeManager<SearchCluster.Node> {

    private static final Logger log = Logger.getLogger(SearchCluster.class.getName());
    
    private final int size;
    private final ImmutableMap<Integer, Group> groups;
    private final ImmutableMultimap<String, Node> nodesByHost;
    private final ClusterMonitor<Node> clusterMonitor;

    // Only needed until query requests are moved to rpc
    private final FS4ResourcePool fs4ResourcePool;

    public SearchCluster(DispatchConfig dispatchConfig, FS4ResourcePool fs4ResourcePool) {
        this(toNodes(dispatchConfig), fs4ResourcePool);
    }
    
    public SearchCluster(List<Node> nodes, FS4ResourcePool fs4ResourcePool) {
        size = nodes.size();
        this.fs4ResourcePool = fs4ResourcePool;
        
        // Create groups
        ImmutableMap.Builder<Integer, Group> groupsBuilder = new ImmutableMap.Builder<>();
        for (Map.Entry<Integer, List<Node>> group : nodes.stream().collect(Collectors.groupingBy(Node::group)).entrySet())
            groupsBuilder.put(group.getKey(), new Group(group.getKey(), group.getValue()));
        groups = groupsBuilder.build();
        
        // Index nodes by host
        ImmutableMultimap.Builder<String, Node> nodesByHostBuilder = new ImmutableMultimap.Builder<>();
        for (Node node : nodes)
            nodesByHostBuilder.put(node.hostname(), node);
        nodesByHost = nodesByHostBuilder.build();
        
        // Set up monitoring of the fs4 interface of the nodes
        // We can switch to monitoring the rpc interface instead when we move the query phase to rpc
        clusterMonitor = new ClusterMonitor<>(this);
        for (Node node : nodes)
            clusterMonitor.add(node, true);
    }
    
    private static ImmutableList<Node> toNodes(DispatchConfig dispatchConfig) {
        ImmutableList.Builder<Node> nodesBuilder = new ImmutableList.Builder<>();
        for (DispatchConfig.Node node : dispatchConfig.node())
            nodesBuilder.add(new Node(node.host(), node.port(), node.group()));
        return nodesBuilder.build();
    }
    
    /** Returns the number of nodes in this cluster (across all groups) */
    public int size() { return size; }
    
    /** Returns the groups of this cluster as an immutable map indexed by group id */
    public ImmutableMap<Integer, Group> groups() { return groups; }

    /** 
     * Returns the nodes of this cluster as an immutable map indexed by host.
     * One host may contain multiple nodes (on different ports), so this is a multi-map.
     */
    public ImmutableMultimap<String, Node> nodesByHost() { return nodesByHost; }

    /** Used by the cluster monitor to manage node status */
    @Override
    public void working(Node node) { node.setWorking(true); }

    /** Used by the cluster monitor to manage node status */
    @Override
    public void failed(Node node) { node.setWorking(false); }

    /** Used by the cluster monitor to manage node status */
    @Override
    public void ping(Node node, Executor executor) {
        Pinger pinger = new Pinger(node);
        FutureTask<Pong> future = new FutureTask<>(pinger);

        executor.execute(future);
        Pong pong;
        try {
            pong = future.get(clusterMonitor.getConfiguration().getFailLimit(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            pong = new Pong();
            pong.addError(ErrorMessage.createUnspecifiedError("Ping was interrupted: " + node));
            log.log(Level.WARNING, "Exception pinging " + node, e);
        } catch (ExecutionException e) {
            pong = new Pong();
            pong.addError(ErrorMessage.createUnspecifiedError("Execution was interrupted: " + node));
            log.log(Level.WARNING, "Exception pinging " + node, e);
        } catch (TimeoutException e) {
            pong = new Pong();
            pong.addError(ErrorMessage.createNoAnswerWhenPingingNode("Ping thread timed out"));
        }
        future.cancel(true);

        if (pong.badResponse())
            clusterMonitor.failed(node, pong.getError(0));
        else
            clusterMonitor.responded(node);
    }

    private class Pinger implements Callable<Pong> {

        private final Node node;

        public Pinger(Node node) {
            this.node = node;
        }

        public Pong call() {
            Pong pong;
            try {
                pong = FastSearcher.ping(new Ping(clusterMonitor.getConfiguration().getRequestTimeout()), 
                                         fs4ResourcePool.getBackend(node.hostname(), node.port()), node.toString());
            } catch (RuntimeException e) {
                pong = new Pong();
                pong.addError(ErrorMessage.createBackendCommunicationError("Exception when pinging " + node + ": "
                              + Exceptions.toMessageString(e)));
            }
            return pong;
        }

    }

    public static class Group {
        
        private final int id;
        private final ImmutableList<Node> nodes;
        
        public Group(int id, List<Node> nodes) {
            this.id = id;
            this.nodes = ImmutableList.copyOf(nodes);
        }

        /** Returns the id of this group */
        public int id() { return id; }
        
        /** Returns the nodes in this group as an immutable list */
        public ImmutableList<Node> nodes() { return nodes; }

        @Override
        public String toString() { return "search group " + id; }
        
    }
    
    public static class Node {
        
        private final String hostname;
        private final int port;
        private final int group;
        
        private final AtomicBoolean working = new AtomicBoolean(true);
        
        public Node(String hostname, int port, int group) {
            this.hostname = hostname;
            this.port = port;
            this.group = group;
        }
        
        public String hostname() { return hostname; }
        public int port() { return port; }

        /** Returns the id of this group this node belongs to */
        public int group() { return group; }
        
        private void setWorking(boolean working) {
            this.working.lazySet(working);
        }
        
        /** Returns whether this node is currently responding to requests */
        public boolean isWorking() { return working.get(); }
        
        @Override
        public int hashCode() { return Objects.hash(hostname, port); }
        
        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if ( ! (o instanceof Node)) return false;
            Node other = (Node)o;
            if ( ! Objects.equals(this.hostname, other.hostname)) return false;
            if ( ! Objects.equals(this.port, other.port)) return false;
            return true;
        }
        
        @Override
        public String toString() { return "search node " + hostname; }
        
    }

}
