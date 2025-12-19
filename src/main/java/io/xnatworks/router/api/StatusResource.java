/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.routing.DestinationManager;
import io.xnatworks.router.tracking.TransferTracker;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDateTime;
import java.util.*;

/**
 * REST API for system status.
 */
@Path("/status")
@Produces(MediaType.APPLICATION_JSON)
public class StatusResource {

    private final AppConfig config;
    private final DestinationManager destinationManager;
    private final TransferTracker transferTracker;
    private final LocalDateTime startTime = LocalDateTime.now();

    public StatusResource(AppConfig config, DestinationManager destinationManager,
                          TransferTracker transferTracker) {
        this.config = config;
        this.destinationManager = destinationManager;
        this.transferTracker = transferTracker;
    }

    @GET
    public Response getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        // Basic info
        status.put("version", "2.1.0");
        status.put("startTime", startTime.toString());
        status.put("uptime", getUptime());

        // Routes summary
        List<Map<String, Object>> routesSummary = new ArrayList<>();
        for (AppConfig.RouteConfig route : config.getRoutes()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("aeTitle", route.getAeTitle());
            r.put("port", route.getPort());
            r.put("enabled", route.isEnabled());
            r.put("destinations", route.getDestinations().size());
            routesSummary.add(r);
        }
        status.put("routes", routesSummary);

        // Destinations summary
        List<Map<String, Object>> destsSummary = new ArrayList<>();
        for (Map.Entry<String, DestinationManager.DestinationHealth> entry :
                destinationManager.getAllHealth().entrySet()) {
            DestinationManager.DestinationHealth health = entry.getValue();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", entry.getKey());
            d.put("type", health.getType());
            d.put("available", health.isAvailable());
            d.put("availabilityPercent", health.getAvailabilityPercent());
            destsSummary.add(d);
        }
        status.put("destinations", destsSummary);

        // Transfer statistics
        TransferTracker.GlobalStatistics stats = transferTracker.getGlobalStatistics();
        Map<String, Object> transfers = new LinkedHashMap<>();
        transfers.put("total", stats.getTotalTransfers());
        transfers.put("successful", stats.getSuccessfulTransfers());
        transfers.put("failed", stats.getFailedTransfers());
        transfers.put("active", stats.getActiveTransfers());
        transfers.put("successRate", stats.getSuccessRate());
        status.put("transfers", transfers);

        return Response.ok(status).build();
    }

    @GET
    @Path("/health")
    public Response getHealth() {
        // Simple health check endpoint
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("timestamp", LocalDateTime.now().toString());

        // Check destinations
        int totalDests = destinationManager.getAllHealth().size();
        int availableDests = destinationManager.getAvailableDestinations().size();
        health.put("destinationsAvailable", availableDests + "/" + totalDests);

        if (availableDests == 0 && totalDests > 0) {
            health.put("status", "DEGRADED");
        }

        return Response.ok(health).build();
    }

    private String getUptime() {
        java.time.Duration duration = java.time.Duration.between(startTime, LocalDateTime.now());
        long days = duration.toDays();
        long hours = duration.toHoursPart();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else {
            return String.format("%dm %ds", minutes, seconds);
        }
    }
}
