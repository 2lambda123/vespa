// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.yahoo.vdslib.distribution.ConfiguredNode;
import com.yahoo.vdslib.distribution.Distribution;
import com.yahoo.vdslib.state.Node;
import com.yahoo.vespa.clustercontroller.core.listeners.NodeListener;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Detailed information about the current state of all the distributor and storage nodes of the cluster.
 *
 * @author hakonhall
 * @author bratseth
 */
public class ClusterInfo {

    /** The configured nodes of this cluster, indexed by node index */
    private final Map<Integer, ConfiguredNode> nodes = new HashMap<>();

    /** Information about the current state of distributors */
    private final Map<Integer, DistributorNodeInfo> distributorNodeInfo = new TreeMap<>();
    /** Information about the current state of storage nodes */
    private final Map<Integer, StorageNodeInfo> storageNodeInfo = new TreeMap<>();
    /** Information about the current state of all nodes - always consists of both sets of nodes in the two maps above */
    private final Map<Node, NodeInfo> allNodeInfo = new TreeMap<>(); // TODO: Remove

    /** Returns non-null iff index is a configured nodes (except perhaps in tests). */
    DistributorNodeInfo getDistributorNodeInfo(int index) { return distributorNodeInfo.get(index); }

    /** Returns non-null iff index is a configured nodes (except perhaps in tests). */
    StorageNodeInfo getStorageNodeInfo(int index) { return storageNodeInfo.get(index); }

    /** Returns information about the given node id, or null if this node does not exist */
    public NodeInfo getNodeInfo(Node node) { return allNodeInfo.get(node); }

    Collection<DistributorNodeInfo> getDistributorNodeInfos() { return Collections.unmodifiableCollection(distributorNodeInfo.values()); }

    Collection<StorageNodeInfo> getStorageNodeInfos() { return Collections.unmodifiableCollection(storageNodeInfo.values()); }

    Collection<NodeInfo> getAllNodeInfos() { return Collections.unmodifiableCollection(allNodeInfo.values()); }

    /** Returns the configured nodes of this as a read-only map indexed on node index (distribution key) */
    Map<Integer, ConfiguredNode> getConfiguredNodes() { return Collections.unmodifiableMap(nodes); }

    boolean hasConfiguredNode(int index) { return nodes.containsKey(index); }

    /** Sets the nodes which belongs to this cluster */
    void setNodes(Collection<ConfiguredNode> newNodes, ContentCluster owner,
                  Distribution distribution, NodeListener nodeListener) {
        // Remove info for removed nodes
        Set<ConfiguredNode> newNodesSet = new HashSet<>(newNodes);
        for (ConfiguredNode existingNode : this.nodes.values()) {
            if ( ! newNodesSet.contains(existingNode)) {
                {
                    Node existingStorageNode = storageNodeInfo.remove(existingNode.index()).getNode();
                    allNodeInfo.remove(existingStorageNode);
                    nodeListener.handleRemovedNode(existingStorageNode);
                }

                {
                    Node existingDistributorNode = distributorNodeInfo.remove(existingNode.index()).getNode();
                    allNodeInfo.remove(existingDistributorNode);
                    nodeListener.handleRemovedNode(existingDistributorNode);
                }
            }
        }

        // Add and update new nodes info
        for (ConfiguredNode node : newNodes) {
            if ( ! nodes.containsKey(node.index())) { // add new node info
                addNodeInfo(new DistributorNodeInfo(owner, node.index(), null, distribution));
                addNodeInfo(new StorageNodeInfo(owner, node.index(), node.retired(), null, distribution));
            }
            else {
                getStorageNodeInfo(node.index()).setConfiguredRetired(node.retired());
            }
        }

        // Update node set
        nodes.clear();
        for (ConfiguredNode node : newNodes) {
            this.nodes.put(node.index(), node);
        }
    }

    private void addNodeInfo(NodeInfo nodeInfo) {
        if (nodeInfo instanceof DistributorNodeInfo) {
            distributorNodeInfo.put(nodeInfo.getNodeIndex(), (DistributorNodeInfo) nodeInfo);
        } else {
            storageNodeInfo.put(nodeInfo.getNodeIndex(), (StorageNodeInfo) nodeInfo);
        }
        allNodeInfo.put(nodeInfo.getNode(), nodeInfo);
        nodeInfo.setReportedState(nodeInfo.getReportedState().setDescription("Node not seen in slobrok."), 0);
    }

    /** Returns true if no nodes are down or unknown */
    boolean allStatesReported() {
        if (nodes.isEmpty()) return false;
        for (ConfiguredNode node : nodes.values()) {
            if (getDistributorNodeInfo(node.index()).getReportedState().getState().oneOf("d-")) return false;
            if (getStorageNodeInfo(node.index()).getReportedState().getState().oneOf("d-")) return false;
        }
        return true;
    }

    /**
     * Sets the rpc address of a node. If the node does not exist this does nothing.
     *
     * @return the info to which an rpc address is set, or null if none
     */
    public NodeInfo setRpcAddress(Node node, String rpcAddress) {
        NodeInfo nodeInfo = getInfo(node);
        if (nodeInfo != null) {
            nodeInfo.setRpcAddress(rpcAddress);
        }
        return nodeInfo;
    }
    // TODO: Do all mutation of node info through setters in this

    /** Returns the node info object for a given node identifier */
    private NodeInfo getInfo(Node node) {
        return switch (node.getType()) {
            case DISTRIBUTOR -> getDistributorNodeInfo(node.getIndex());
            case STORAGE -> getStorageNodeInfo(node.getIndex());
        };
    }

}
