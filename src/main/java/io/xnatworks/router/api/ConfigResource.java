/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * REST API for configuration viewing and editing.
 */
@Path("/config")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigResource {
    private static final Logger log = LoggerFactory.getLogger(ConfigResource.class);

    private final AppConfig config;

    public ConfigResource(AppConfig config) {
        this.config = config;
    }

    @GET
    public Response getConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Basic settings (safe to expose)
        result.put("adminPort", config.getAdminPort());
        result.put("adminHost", config.getAdminHost());
        result.put("dataDirectory", config.getDataDirectory());
        result.put("scriptsDirectory", config.getScriptsDirectory());
        result.put("logLevel", config.getLogLevel());
        result.put("configFile", config.getConfigFile() != null ? config.getConfigFile().getAbsolutePath() : null);

        // Route count
        result.put("routeCount", config.getRoutes().size());

        // Destination count by type
        Map<String, Integer> destCounts = new LinkedHashMap<>();
        int xnatCount = 0, dicomCount = 0, fileCount = 0;
        for (AppConfig.Destination dest : config.getDestinations().values()) {
            if (dest instanceof AppConfig.XnatDestination) xnatCount++;
            else if (dest instanceof AppConfig.DicomAeDestination) dicomCount++;
            else if (dest instanceof AppConfig.FileDestination) fileCount++;
        }
        destCounts.put("xnat", xnatCount);
        destCounts.put("dicom", dicomCount);
        destCounts.put("file", fileCount);
        result.put("destinations", destCounts);

        // Receiver config
        Map<String, Object> receiverMap = new LinkedHashMap<>();
        receiverMap.put("baseDir", config.getReceiver().getBaseDir());
        receiverMap.put("storageDir", config.getReceiver().getStorageDir());
        result.put("receiver", receiverMap);

        // Resilience config
        Map<String, Object> resilienceMap = new LinkedHashMap<>();
        resilienceMap.put("healthCheckInterval", config.getResilience().getHealthCheckInterval());
        resilienceMap.put("cacheDir", config.getResilience().getCacheDir());
        resilienceMap.put("maxRetries", config.getResilience().getMaxRetries());
        resilienceMap.put("retryDelay", config.getResilience().getRetryDelay());
        resilienceMap.put("retentionDays", config.getResilience().getRetentionDays());
        result.put("resilience", resilienceMap);

        // Notifications config (mask password)
        Map<String, Object> notifMap = new LinkedHashMap<>();
        notifMap.put("enabled", config.getNotifications().isEnabled());
        notifMap.put("smtpServer", config.getNotifications().getSmtpServer());
        notifMap.put("smtpPort", config.getNotifications().getSmtpPort());
        notifMap.put("smtpUseTls", config.getNotifications().isSmtpUseTls());
        notifMap.put("smtpUsername", config.getNotifications().getSmtpUsername());
        notifMap.put("fromAddress", config.getNotifications().getFromAddress());
        notifMap.put("adminEmail", config.getNotifications().getAdminEmail());
        notifMap.put("notifyOnDestinationDown", config.getNotifications().isNotifyOnDestinationDown());
        notifMap.put("notifyOnDestinationRecovered", config.getNotifications().isNotifyOnDestinationRecovered());
        notifMap.put("notifyOnForwardFailure", config.getNotifications().isNotifyOnForwardFailure());
        notifMap.put("notifyOnDailySummary", config.getNotifications().isNotifyOnDailySummary());
        result.put("notifications", notifMap);

        // Features config
        Map<String, Object> featuresMap = new LinkedHashMap<>();
        featuresMap.put("enableIndexing", config.getFeatures().isEnableIndexing());
        featuresMap.put("enableReview", config.getFeatures().isEnableReview());
        featuresMap.put("enableOcr", config.getFeatures().isEnableOcr());
        featuresMap.put("enableQueryRetrieve", config.getFeatures().isEnableQueryRetrieve());
        result.put("features", featuresMap);

        return Response.ok(result).build();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateConfig(Map<String, Object> updates) {
        try {
            // Update basic settings
            if (updates.containsKey("dataDirectory")) {
                config.setDataDirectory((String) updates.get("dataDirectory"));
            }
            if (updates.containsKey("scriptsDirectory")) {
                config.setScriptsDirectory((String) updates.get("scriptsDirectory"));
            }
            if (updates.containsKey("logLevel")) {
                config.setLogLevel((String) updates.get("logLevel"));
            }

            // Update receiver config
            if (updates.containsKey("receiver") && updates.get("receiver") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> receiverUpdates = (Map<String, Object>) updates.get("receiver");
                if (receiverUpdates.containsKey("baseDir")) {
                    config.getReceiver().setBaseDir((String) receiverUpdates.get("baseDir"));
                }
            }

            // Update resilience config
            if (updates.containsKey("resilience") && updates.get("resilience") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> resilienceUpdates = (Map<String, Object>) updates.get("resilience");
                if (resilienceUpdates.containsKey("healthCheckInterval")) {
                    config.getResilience().setHealthCheckInterval(((Number) resilienceUpdates.get("healthCheckInterval")).intValue());
                }
                if (resilienceUpdates.containsKey("maxRetries")) {
                    config.getResilience().setMaxRetries(((Number) resilienceUpdates.get("maxRetries")).intValue());
                }
                if (resilienceUpdates.containsKey("retryDelay")) {
                    config.getResilience().setRetryDelay(((Number) resilienceUpdates.get("retryDelay")).intValue());
                }
                if (resilienceUpdates.containsKey("retentionDays")) {
                    config.getResilience().setRetentionDays(((Number) resilienceUpdates.get("retentionDays")).intValue());
                }
            }

            // Update notifications config
            if (updates.containsKey("notifications") && updates.get("notifications") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> notifUpdates = (Map<String, Object>) updates.get("notifications");
                if (notifUpdates.containsKey("enabled")) {
                    config.getNotifications().setEnabled((Boolean) notifUpdates.get("enabled"));
                }
                if (notifUpdates.containsKey("smtpServer")) {
                    config.getNotifications().setSmtpServer((String) notifUpdates.get("smtpServer"));
                }
                if (notifUpdates.containsKey("smtpPort")) {
                    config.getNotifications().setSmtpPort(((Number) notifUpdates.get("smtpPort")).intValue());
                }
                if (notifUpdates.containsKey("smtpUseTls")) {
                    config.getNotifications().setSmtpUseTls((Boolean) notifUpdates.get("smtpUseTls"));
                }
                if (notifUpdates.containsKey("smtpUsername")) {
                    config.getNotifications().setSmtpUsername((String) notifUpdates.get("smtpUsername"));
                }
                if (notifUpdates.containsKey("smtpPassword") && notifUpdates.get("smtpPassword") != null) {
                    String pwd = (String) notifUpdates.get("smtpPassword");
                    if (!pwd.isEmpty()) {
                        config.getNotifications().setSmtpPassword(pwd);
                    }
                }
                if (notifUpdates.containsKey("fromAddress")) {
                    config.getNotifications().setFromAddress((String) notifUpdates.get("fromAddress"));
                }
                if (notifUpdates.containsKey("adminEmail")) {
                    config.getNotifications().setAdminEmail((String) notifUpdates.get("adminEmail"));
                }
                if (notifUpdates.containsKey("notifyOnDestinationDown")) {
                    config.getNotifications().setNotifyOnDestinationDown((Boolean) notifUpdates.get("notifyOnDestinationDown"));
                }
                if (notifUpdates.containsKey("notifyOnDestinationRecovered")) {
                    config.getNotifications().setNotifyOnDestinationRecovered((Boolean) notifUpdates.get("notifyOnDestinationRecovered"));
                }
                if (notifUpdates.containsKey("notifyOnForwardFailure")) {
                    config.getNotifications().setNotifyOnForwardFailure((Boolean) notifUpdates.get("notifyOnForwardFailure"));
                }
                if (notifUpdates.containsKey("notifyOnDailySummary")) {
                    config.getNotifications().setNotifyOnDailySummary((Boolean) notifUpdates.get("notifyOnDailySummary"));
                }
            }

            // Save to file
            config.save();
            log.info("Configuration updated and saved");

            return Response.ok(Map.of("status", "saved", "message", "Configuration updated successfully")).build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to update configuration: {}", e.getMessage(), e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid configuration: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/routes/summary")
    public Response getRoutesSummary() {
        List<Map<String, Object>> routes = new ArrayList<>();

        for (AppConfig.RouteConfig route : config.getRoutes()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("aeTitle", route.getAeTitle());
            r.put("port", route.getPort());
            r.put("enabled", route.isEnabled());
            r.put("description", route.getDescription());
            r.put("destinationCount", route.getDestinations().size());
            r.put("routingRuleCount", route.getRoutingRules().size());
            r.put("validationRuleCount", route.getValidationRules().size());
            r.put("filterCount", route.getFilters().size());
            routes.add(r);
        }

        return Response.ok(routes).build();
    }

    @GET
    @Path("/destinations/summary")
    public Response getDestinationsSummary() {
        List<Map<String, Object>> destinations = new ArrayList<>();

        for (Map.Entry<String, AppConfig.Destination> entry : config.getDestinations().entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", entry.getKey());

            AppConfig.Destination dest = entry.getValue();
            if (dest instanceof AppConfig.XnatDestination) {
                AppConfig.XnatDestination xnat = (AppConfig.XnatDestination) dest;
                d.put("type", "xnat");
                d.put("url", maskUrl(xnat.getUrl()));
                d.put("enabled", xnat.isEnabled());
            } else if (dest instanceof AppConfig.DicomAeDestination) {
                AppConfig.DicomAeDestination dicom = (AppConfig.DicomAeDestination) dest;
                d.put("type", "dicom");
                d.put("aeTitle", dicom.getAeTitle());
                d.put("host", dicom.getHost());
                d.put("port", dicom.getPort());
                d.put("enabled", dicom.isEnabled());
            } else if (dest instanceof AppConfig.FileDestination) {
                AppConfig.FileDestination file = (AppConfig.FileDestination) dest;
                d.put("type", "file");
                d.put("path", file.getPath());
                d.put("enabled", file.isEnabled());
            }

            destinations.add(d);
        }

        return Response.ok(destinations).build();
    }

    @GET
    @Path("/scripts/summary")
    public Response getScriptsSummary() {
        // Return info about script configuration
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scriptsDirectory", config.getScriptsDirectory());
        return Response.ok(result).build();
    }

    @GET
    @Path("/ae-titles")
    public Response getAeTitles() {
        List<Map<String, Object>> aeTitles = new ArrayList<>();

        for (AppConfig.RouteConfig route : config.getRoutes()) {
            Map<String, Object> ae = new LinkedHashMap<>();
            ae.put("aeTitle", route.getAeTitle());
            ae.put("port", route.getPort());
            ae.put("enabled", route.isEnabled());
            aeTitles.add(ae);
        }

        return Response.ok(aeTitles).build();
    }

    @GET
    @Path("/ports")
    public Response getUsedPorts() {
        Set<Integer> ports = new TreeSet<>();

        for (AppConfig.RouteConfig route : config.getRoutes()) {
            ports.add(route.getPort());
        }

        ports.add(config.getAdminPort());

        return Response.ok(Map.of(
                "adminPort", config.getAdminPort(),
                "dicomPorts", new ArrayList<>(ports)
        )).build();
    }

    /**
     * Mask sensitive parts of URLs for display.
     */
    private String maskUrl(String url) {
        if (url == null) return null;
        // Replace password if present in URL
        return url.replaceAll("://[^:]+:[^@]+@", "://***:***@");
    }
}
