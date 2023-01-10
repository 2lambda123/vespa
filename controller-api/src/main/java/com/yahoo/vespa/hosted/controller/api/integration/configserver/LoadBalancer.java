// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.configserver;

import ai.vespa.http.DomainName;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.CloudAccount;
import com.yahoo.config.provision.ClusterSpec;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 *  Represents an exclusive load balancer, assigned to an application's cluster.
 *
 * @author mortent
 */
public class LoadBalancer {

    private final String id;
    private final ApplicationId application;
    private final ClusterSpec.Id cluster;
    private final Optional<DomainName> hostname;
    private final Optional<String> ipAddress;
    private final State state;
    private final Optional<String> dnsZone;
    private final Optional<CloudAccount> cloudAccount;
    private final Optional<PrivateServiceInfo> service;

    public LoadBalancer(String id, ApplicationId application, ClusterSpec.Id cluster, Optional<DomainName> hostname,
                        Optional<String> ipAddress, State state, Optional<String> dnsZone,
                        Optional<CloudAccount> cloudAccount, Optional<PrivateServiceInfo> service) {
        this.id = Objects.requireNonNull(id, "id must be non-null");
        this.application = Objects.requireNonNull(application, "application must be non-null");
        this.cluster = Objects.requireNonNull(cluster, "cluster must be non-null");
        this.hostname = Objects.requireNonNull(hostname, "hostname must be non-null");
        this.ipAddress = Objects.requireNonNull(ipAddress, "ipAddress must be non-null");
        this.state = Objects.requireNonNull(state, "state must be non-null");
        this.dnsZone = Objects.requireNonNull(dnsZone, "dnsZone must be non-null");
        this.cloudAccount = Objects.requireNonNull(cloudAccount, "cloudAccount must be non-null");
        this.service = Objects.requireNonNull(service, "service must be non-null");
    }

    public String id() {
        return id;
    }

    public ApplicationId application() {
        return application;
    }

    public ClusterSpec.Id cluster() {
        return cluster;
    }

    public Optional<DomainName> hostname() {
        return hostname;
    }

    public Optional<String> ipAddress() {
        return ipAddress;
    }

    public Optional<String> dnsZone() {
        return dnsZone;
    }

    public State state() {
        return state;
    }

    public Optional<CloudAccount> cloudAccount() {
        return cloudAccount;
    }

    public Optional<PrivateServiceInfo> service() {
        return service;
    }

    public enum State {
        active,
        inactive,
        reserved,
        unknown
    }

    public record PrivateServiceInfo(String id, List<String> allowedUrns) { }

}
