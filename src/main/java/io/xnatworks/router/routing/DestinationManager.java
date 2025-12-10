/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.routing;

import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.dicom.DicomClient;
import io.xnatworks.router.xnat.XnatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages all destination types (XNAT, DICOM AE, File System).
 * Provides unified interface for health checking and forwarding.
 */
public class DestinationManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DestinationManager.class);

    private final AppConfig config;

    // Clients by destination name
    private final Map<String, XnatClient> xnatClients = new ConcurrentHashMap<>();
    private final Map<String, DicomClient> dicomClients = new ConcurrentHashMap<>();

    // Health status by destination name
    private final Map<String, DestinationHealth> healthStatus = new ConcurrentHashMap<>();

    // Health check scheduler
    private final ScheduledExecutorService healthChecker;

    public DestinationManager(AppConfig config) {
        this.config = config;
        this.healthChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "destination-health-checker");
            t.setDaemon(true);
            return t;
        });

        initializeDestinations();
    }

    /**
     * Initialize all configured destinations.
     */
    private void initializeDestinations() {
        for (Map.Entry<String, AppConfig.Destination> entry : config.getDestinations().entrySet()) {
            String name = entry.getKey();
            AppConfig.Destination dest = entry.getValue();

            if (!dest.isEnabled()) {
                log.info("Skipping disabled destination: {}", name);
                continue;
            }

            try {
                initializeDestination(name, dest);
            } catch (Exception e) {
                log.error("Failed to initialize destination '{}': {}", name, e.getMessage(), e);
            }
        }

        log.info("Initialized {} destinations ({} XNAT, {} DICOM, {} file)",
                healthStatus.size(), xnatClients.size(), dicomClients.size(),
                healthStatus.size() - xnatClients.size() - dicomClients.size());
    }

    /**
     * Initialize a single destination.
     */
    private void initializeDestination(String name, AppConfig.Destination dest) {
        DestinationHealth health = new DestinationHealth();
        health.setName(name);
        health.setType(dest.getType());
        health.setDescription(dest.getDescription());

        if (dest instanceof AppConfig.XnatDestination) {
            AppConfig.XnatDestination xnatDest = (AppConfig.XnatDestination) dest;
            health.setUrl(xnatDest.getUrl());

            // Create XNAT client
            AppConfig.XnatEndpointLegacy endpoint = new AppConfig.XnatEndpointLegacy();
            endpoint.setUrl(xnatDest.getUrl());
            endpoint.setUsername(xnatDest.getUsername());
            endpoint.setPassword(xnatDest.getPassword());

            XnatClient client = new XnatClient(name,
                    createXnatEndpoint(xnatDest.getUrl(), xnatDest.getUsername(), xnatDest.getPassword()));
            xnatClients.put(name, client);

            log.info("Initialized XNAT destination '{}': {}", name, xnatDest.getUrl());

        } else if (dest instanceof AppConfig.DicomAeDestination) {
            AppConfig.DicomAeDestination dicomDest = (AppConfig.DicomAeDestination) dest;
            health.setUrl(dicomDest.getHost() + ":" + dicomDest.getPort());
            health.setAeTitle(dicomDest.getAeTitle());

            DicomClient client = new DicomClient(name, dicomDest);
            dicomClients.put(name, client);

            log.info("Initialized DICOM destination '{}': {}@{}:{}",
                    name, dicomDest.getAeTitle(), dicomDest.getHost(), dicomDest.getPort());

        } else if (dest instanceof AppConfig.FileDestination) {
            AppConfig.FileDestination fileDest = (AppConfig.FileDestination) dest;
            health.setUrl(fileDest.getPath());

            // Ensure directory exists
            try {
                Files.createDirectories(Paths.get(fileDest.getPath()));
            } catch (IOException e) {
                log.warn("Failed to create file destination directory: {}", fileDest.getPath());
            }

            log.info("Initialized File destination '{}': {}", name, fileDest.getPath());
        }

        healthStatus.put(name, health);
    }

    private AppConfig.XnatEndpoint createXnatEndpoint(String url, String username, String password) {
        AppConfig.XnatEndpoint endpoint = new AppConfig.XnatEndpoint();
        endpoint.setUrl(url);
        endpoint.setUsername(username);
        endpoint.setPassword(password);
        return endpoint;
    }

    /**
     * Start periodic health checking.
     */
    public void startHealthChecks() {
        int interval = config.getResilience().getHealthCheckInterval();
        log.info("Starting health checks every {} seconds", interval);

        healthChecker.scheduleAtFixedRate(
                this::checkAllDestinations,
                0, interval, TimeUnit.SECONDS
        );
    }

    /**
     * Stop health checking.
     */
    public void stopHealthChecks() {
        healthChecker.shutdown();
        try {
            healthChecker.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Check health of all destinations.
     */
    public void checkAllDestinations() {
        for (String name : healthStatus.keySet()) {
            try {
                checkDestination(name);
            } catch (Exception e) {
                log.debug("Error checking destination '{}': {}", name, e.getMessage());
            }
        }
    }

    /**
     * Check health of a specific destination.
     */
    public boolean checkDestination(String name) {
        DestinationHealth health = healthStatus.get(name);
        if (health == null) {
            return false;
        }

        boolean wasAvailable = health.isAvailable();
        boolean isAvailable = false;

        AppConfig.Destination dest = config.getDestination(name);

        if (dest instanceof AppConfig.XnatDestination) {
            XnatClient client = xnatClients.get(name);
            if (client != null) {
                isAvailable = client.isAvailable();
            }
        } else if (dest instanceof AppConfig.DicomAeDestination) {
            DicomClient client = dicomClients.get(name);
            if (client != null) {
                isAvailable = client.echo();
            }
        } else if (dest instanceof AppConfig.FileDestination) {
            AppConfig.FileDestination fileDest = (AppConfig.FileDestination) dest;
            Path path = Paths.get(fileDest.getPath());
            isAvailable = Files.exists(path) && Files.isWritable(path);
        }

        // Update health status
        health.updateStatus(isAvailable);

        // Log state changes
        if (wasAvailable && !isAvailable) {
            log.warn("Destination '{}' is now UNAVAILABLE", name);
        } else if (!wasAvailable && isAvailable) {
            log.info("Destination '{}' is now AVAILABLE", name);
        }

        return isAvailable;
    }

    /**
     * Get XNAT client for a destination.
     */
    public XnatClient getXnatClient(String name) {
        return xnatClients.get(name);
    }

    /**
     * Get DICOM client for a destination.
     */
    public DicomClient getDicomClient(String name) {
        return dicomClients.get(name);
    }

    /**
     * Get health status for a destination.
     */
    public DestinationHealth getHealth(String name) {
        return healthStatus.get(name);
    }

    /**
     * Get all health statuses.
     */
    public Map<String, DestinationHealth> getAllHealth() {
        return new HashMap<>(healthStatus);
    }

    /**
     * Get list of available destinations.
     */
    public List<String> getAvailableDestinations() {
        List<String> available = new ArrayList<>();
        for (Map.Entry<String, DestinationHealth> entry : healthStatus.entrySet()) {
            if (entry.getValue().isAvailable()) {
                available.add(entry.getKey());
            }
        }
        return available;
    }

    /**
     * Check if a destination is available.
     */
    public boolean isAvailable(String name) {
        DestinationHealth health = healthStatus.get(name);
        return health != null && health.isAvailable();
    }

    /**
     * Add a new destination at runtime.
     */
    public void addDestination(String name, AppConfig.Destination dest) {
        if (healthStatus.containsKey(name)) {
            throw new IllegalArgumentException("Destination '" + name + "' already exists");
        }
        initializeDestination(name, dest);
        log.info("Added destination '{}' of type {}", name, dest.getType());
    }

    /**
     * Update an existing destination at runtime.
     */
    public void updateDestination(String name, AppConfig.Destination dest) {
        // Remove the old destination first
        removeDestination(name);
        // Then add it back with new config
        initializeDestination(name, dest);
        log.info("Updated destination '{}' of type {}", name, dest.getType());
    }

    /**
     * Remove a destination at runtime.
     */
    public void removeDestination(String name) {
        // Close any existing clients
        XnatClient xnatClient = xnatClients.remove(name);
        if (xnatClient != null) {
            try {
                xnatClient.close();
            } catch (Exception e) {
                log.debug("Error closing XNAT client for '{}': {}", name, e.getMessage());
            }
        }

        DicomClient dicomClient = dicomClients.remove(name);
        if (dicomClient != null) {
            try {
                dicomClient.close();
            } catch (Exception e) {
                log.debug("Error closing DICOM client for '{}': {}", name, e.getMessage());
            }
        }

        healthStatus.remove(name);
        log.info("Removed destination '{}'", name);
    }

    /**
     * Forward files to a file destination.
     */
    public ForwardResult forwardToFile(String destinationName, List<File> files,
                                        String subDir) throws IOException {
        AppConfig.Destination dest = config.getDestination(destinationName);
        if (!(dest instanceof AppConfig.FileDestination)) {
            throw new IllegalArgumentException("Destination '" + destinationName + "' is not a file destination");
        }

        AppConfig.FileDestination fileDest = (AppConfig.FileDestination) dest;
        Path targetDir = Paths.get(fileDest.getPath());
        if (subDir != null && !subDir.isEmpty()) {
            targetDir = targetDir.resolve(subDir);
        }

        Files.createDirectories(targetDir);

        ForwardResult result = new ForwardResult();
        result.setDestination(destinationName);
        result.setTotalFiles(files.size());

        long startTime = System.currentTimeMillis();

        for (File file : files) {
            try {
                Path target = targetDir.resolve(file.getName());
                Files.copy(file.toPath(), target);
                result.incrementSuccess();
            } catch (IOException e) {
                log.error("Failed to copy file {}: {}", file.getName(), e.getMessage());
                result.incrementFailed();
            }
        }

        result.setDurationMs(System.currentTimeMillis() - startTime);
        return result;
    }

    @Override
    public void close() {
        stopHealthChecks();

        // Close XNAT clients
        for (XnatClient client : xnatClients.values()) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Error closing XNAT client: {}", e.getMessage());
            }
        }
        xnatClients.clear();

        // Close DICOM clients
        for (DicomClient client : dicomClients.values()) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Error closing DICOM client: {}", e.getMessage());
            }
        }
        dicomClients.clear();
    }

    /**
     * Health information for a destination.
     */
    public static class DestinationHealth {
        private String name;
        private String type;
        private String description;
        private String url;
        private String aeTitle;
        private boolean available = true;
        private LocalDateTime lastCheckTime;
        private LocalDateTime lastAvailableTime;
        private LocalDateTime unavailableSince;
        private int consecutiveFailures;
        private long totalChecks;
        private long successfulChecks;

        public void updateStatus(boolean isAvailable) {
            LocalDateTime now = LocalDateTime.now();
            this.lastCheckTime = now;
            this.totalChecks++;

            if (isAvailable) {
                this.available = true;
                this.lastAvailableTime = now;
                this.unavailableSince = null;
                this.consecutiveFailures = 0;
                this.successfulChecks++;
            } else {
                if (this.available) {
                    this.unavailableSince = now;
                }
                this.available = false;
                this.consecutiveFailures++;
            }
        }

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public boolean isAvailable() { return available; }

        public LocalDateTime getLastCheckTime() { return lastCheckTime; }
        public LocalDateTime getLastAvailableTime() { return lastAvailableTime; }
        public LocalDateTime getUnavailableSince() { return unavailableSince; }
        public int getConsecutiveFailures() { return consecutiveFailures; }
        public long getTotalChecks() { return totalChecks; }
        public long getSuccessfulChecks() { return successfulChecks; }

        public double getAvailabilityPercent() {
            if (totalChecks == 0) return 100.0;
            return (successfulChecks * 100.0) / totalChecks;
        }

        public long getDowntimeSeconds() {
            if (available || unavailableSince == null) return 0;
            return java.time.Duration.between(unavailableSince, LocalDateTime.now()).getSeconds();
        }
    }

    /**
     * Result of a forward operation.
     */
    public static class ForwardResult {
        private String destination;
        private int totalFiles;
        private int successCount;
        private int failedCount;
        private long durationMs;
        private String errorMessage;

        public String getDestination() { return destination; }
        public void setDestination(String destination) { this.destination = destination; }

        public int getTotalFiles() { return totalFiles; }
        public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }

        public int getSuccessCount() { return successCount; }
        public void incrementSuccess() { this.successCount++; }

        public int getFailedCount() { return failedCount; }
        public void incrementFailed() { this.failedCount++; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

        public boolean isSuccess() { return failedCount == 0; }
        public boolean isPartial() { return successCount > 0 && failedCount > 0; }
    }
}
