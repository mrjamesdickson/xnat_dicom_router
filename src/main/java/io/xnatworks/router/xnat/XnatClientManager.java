/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.xnat;

import io.xnatworks.router.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple XnatClient instances for different XNAT endpoints.
 * Provides thread-safe access to clients and handles lifecycle management.
 */
public class XnatClientManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(XnatClientManager.class);

    private final AppConfig config;
    private final Map<String, XnatClient> clients = new ConcurrentHashMap<>();
    private final Map<String, EndpointStatus> endpointStatuses = new ConcurrentHashMap<>();

    public XnatClientManager(AppConfig config) {
        this.config = config;
        initializeClients();
    }

    /**
     * Initialize clients for all configured endpoints.
     */
    private void initializeClients() {
        // Initialize from legacy xnat_endpoints
        for (Map.Entry<String, AppConfig.XnatEndpointLegacy> entry : config.getXnatEndpoints().entrySet()) {
            String name = entry.getKey();
            AppConfig.XnatEndpointLegacy legacy = entry.getValue();

            if (!legacy.isEnabled()) {
                log.info("Skipping disabled endpoint: {}", name);
                continue;
            }

            try {
                AppConfig.XnatEndpoint endpoint = new AppConfig.XnatEndpoint();
                endpoint.setUrl(legacy.getUrl());
                endpoint.setUsername(legacy.getUsername());
                endpoint.setPassword(legacy.getPassword());
                endpoint.setDescription(legacy.getDescription());
                endpoint.setEnabled(legacy.isEnabled());

                XnatClient client = new XnatClient(name, endpoint);
                clients.put(name, client);
                endpointStatuses.put(name, new EndpointStatus(name, endpoint.getUrl()));
                log.info("Initialized XNAT client for endpoint '{}': {}", name, endpoint.getUrl());
            } catch (Exception e) {
                log.error("Failed to initialize client for endpoint '{}': {}", name, e.getMessage(), e);
            }
        }

        // Also initialize from destinations map
        for (Map.Entry<String, AppConfig.Destination> entry : config.getDestinations().entrySet()) {
            String name = entry.getKey();
            if (clients.containsKey(name)) continue;

            AppConfig.Destination dest = entry.getValue();
            if (!(dest instanceof AppConfig.XnatDestination)) continue;
            if (!dest.isEnabled()) continue;

            AppConfig.XnatDestination xnatDest = (AppConfig.XnatDestination) dest;
            try {
                AppConfig.XnatEndpoint endpoint = new AppConfig.XnatEndpoint();
                endpoint.setUrl(xnatDest.getUrl());
                endpoint.setUsername(xnatDest.getUsername());
                endpoint.setPassword(xnatDest.getPassword());
                endpoint.setDescription(xnatDest.getDescription());
                endpoint.setEnabled(xnatDest.isEnabled());

                XnatClient client = new XnatClient(name, endpoint);
                clients.put(name, client);
                endpointStatuses.put(name, new EndpointStatus(name, endpoint.getUrl()));
                log.info("Initialized XNAT client for destination '{}': {}", name, endpoint.getUrl());
            } catch (Exception e) {
                log.error("Failed to initialize client for destination '{}': {}", name, e.getMessage(), e);
            }
        }

        log.info("Initialized {} XNAT client(s)", clients.size());
    }

    /**
     * Get client for a specific endpoint name.
     */
    public XnatClient getClient(String endpointName) {
        XnatClient client = clients.get(endpointName);
        if (client == null) {
            log.warn("No client found for endpoint '{}', available: {}", endpointName, clients.keySet());
        }
        return client;
    }

    /**
     * Get client for a specific project (looks up project's endpoint).
     */
    public XnatClient getClientForProject(String projectId) {
        // Look up project mapping
        for (AppConfig.ProjectMapping project : config.getProjects()) {
            if (project.getProjectId() != null && project.getProjectId().equals(projectId)) {
                String endpointName = project.getXnatEndpoint();
                if (endpointName != null && clients.containsKey(endpointName)) {
                    return clients.get(endpointName);
                }
            }
        }

        log.warn("No endpoint configured for project '{}', using default", projectId);
        return getDefaultClient();
    }

    /**
     * Get the default client (first available or "default" named).
     */
    public XnatClient getDefaultClient() {
        if (clients.containsKey("default")) {
            return clients.get("default");
        }
        if (!clients.isEmpty()) {
            return clients.values().iterator().next();
        }
        return null;
    }

    /**
     * Check health of all endpoints.
     */
    public Map<String, EndpointStatus> checkAllEndpoints() {
        for (Map.Entry<String, XnatClient> entry : clients.entrySet()) {
            String name = entry.getKey();
            XnatClient client = entry.getValue();
            EndpointStatus status = endpointStatuses.get(name);

            boolean available = client.isAvailable();
            status.updateStatus(available);

            log.debug("Endpoint '{}' health check: {}", name, available ? "AVAILABLE" : "UNAVAILABLE");
        }
        return endpointStatuses;
    }

    /**
     * Check health of a specific endpoint.
     */
    public boolean checkEndpoint(String endpointName) {
        XnatClient client = clients.get(endpointName);
        if (client == null) {
            return false;
        }

        boolean available = client.isAvailable();
        EndpointStatus status = endpointStatuses.get(endpointName);
        if (status != null) {
            status.updateStatus(available);
        }

        return available;
    }

    /**
     * Get status of all endpoints.
     */
    public Map<String, EndpointStatus> getEndpointStatuses() {
        return endpointStatuses;
    }

    /**
     * Get all client names.
     */
    public java.util.Set<String> getClientNames() {
        return clients.keySet();
    }

    /**
     * Get number of configured endpoints.
     */
    public int getEndpointCount() {
        return clients.size();
    }

    @Override
    public void close() {
        log.info("Closing {} XNAT client(s)", clients.size());
        for (XnatClient client : clients.values()) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing client: {}", e.getMessage());
            }
        }
        clients.clear();
    }

    /**
     * Status information for an XNAT endpoint.
     */
    public static class EndpointStatus {
        private final String name;
        private final String url;
        private boolean available;
        private long lastCheckTime;
        private long lastAvailableTime;
        private long unavailableSince;
        private int consecutiveFailures;

        public EndpointStatus(String name, String url) {
            this.name = name;
            this.url = url;
            this.available = true;
            this.lastCheckTime = System.currentTimeMillis();
            this.lastAvailableTime = System.currentTimeMillis();
        }

        public void updateStatus(boolean isAvailable) {
            long now = System.currentTimeMillis();
            this.lastCheckTime = now;

            if (isAvailable) {
                if (!this.available) {
                    // Recovered
                    log.info("Endpoint '{}' recovered after {}ms downtime",
                            name, now - unavailableSince);
                }
                this.available = true;
                this.lastAvailableTime = now;
                this.unavailableSince = 0;
                this.consecutiveFailures = 0;
            } else {
                if (this.available) {
                    // Just went down
                    this.unavailableSince = now;
                }
                this.available = false;
                this.consecutiveFailures++;
            }
        }

        public String getName() { return name; }
        public String getUrl() { return url; }
        public boolean isAvailable() { return available; }
        public long getLastCheckTime() { return lastCheckTime; }
        public long getLastAvailableTime() { return lastAvailableTime; }
        public long getUnavailableSince() { return unavailableSince; }
        public int getConsecutiveFailures() { return consecutiveFailures; }

        public long getDowntimeMs() {
            if (available || unavailableSince == 0) return 0;
            return System.currentTimeMillis() - unavailableSince;
        }

        private static final Logger log = LoggerFactory.getLogger(EndpointStatus.class);
    }
}
