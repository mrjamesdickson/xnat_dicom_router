/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.broker.CrosswalkStore;
import io.xnatworks.router.broker.CrosswalkStore.BackupInfo;
import io.xnatworks.router.broker.HonestBrokerService;
import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.config.AppConfig.HonestBrokerConfig;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * REST API for managing Honest Broker configurations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /brokers - List all configured brokers</li>
 *   <li>GET /brokers/{name} - Get broker details</li>
 *   <li>POST /brokers - Create a new broker</li>
 *   <li>PUT /brokers/{name} - Update a broker</li>
 *   <li>DELETE /brokers/{name} - Delete a broker</li>
 *   <li>POST /brokers/{name}/test - Test broker connection</li>
 *   <li>POST /brokers/{name}/lookup - Perform a lookup</li>
 *   <li>POST /brokers/{name}/cache/clear - Clear broker cache</li>
 * </ul>
 * </p>
 */
@Path("/brokers")
@Produces(MediaType.APPLICATION_JSON)
public class HonestBrokersResource {
    private static final Logger log = LoggerFactory.getLogger(HonestBrokersResource.class);

    private final AppConfig config;
    private final HonestBrokerService brokerService;

    public HonestBrokersResource(AppConfig config, HonestBrokerService brokerService) {
        this.config = config;
        this.brokerService = brokerService;
    }

    /**
     * List all configured honest brokers.
     */
    @GET
    public Response listBrokers() {
        List<Map<String, Object>> brokers = new ArrayList<>();

        Map<String, HonestBrokerConfig> configuredBrokers = config.getHonestBrokers();
        if (configuredBrokers != null) {
            for (Map.Entry<String, HonestBrokerConfig> entry : configuredBrokers.entrySet()) {
                brokers.add(brokerToSummaryMap(entry.getKey(), entry.getValue()));
            }
        }

        return Response.ok(brokers).build();
    }

    /**
     * Get details for a specific broker.
     */
    @GET
    @Path("/{name}")
    public Response getBroker(@PathParam("name") String name) {
        HonestBrokerConfig broker = config.getHonestBroker(name);
        if (broker == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Broker not found: " + name))
                    .build();
        }

        return Response.ok(brokerToDetailedMap(name, broker)).build();
    }

    /**
     * Create a new broker configuration.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createBroker(Map<String, Object> brokerData) {
        try {
            String name = (String) brokerData.get("name");
            if (name == null || name.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Broker name is required"))
                        .build();
            }

            // Validate name format
            if (!name.matches("^[a-zA-Z0-9_-]+$")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Broker name must contain only letters, numbers, underscores, and hyphens"))
                        .build();
            }

            if (config.getHonestBrokers().containsKey(name)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "Broker already exists: " + name))
                        .build();
            }

            HonestBrokerConfig broker = createBrokerFromMap(brokerData);
            config.getHonestBrokers().put(name, broker);
            config.save();

            log.info("Created honest broker: {}", name);
            return Response.status(Response.Status.CREATED)
                    .entity(brokerToDetailedMap(name, broker))
                    .build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create broker: {}", e.getMessage(), e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid broker data: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Update an existing broker configuration.
     */
    @PUT
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateBroker(@PathParam("name") String name, Map<String, Object> brokerData) {
        try {
            HonestBrokerConfig broker = config.getHonestBroker(name);
            if (broker == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Broker not found: " + name))
                        .build();
            }

            updateBrokerFromMap(broker, brokerData);
            config.save();

            // Clear cache since config changed
            brokerService.clearCache(name);

            log.info("Updated honest broker: {}", name);
            return Response.ok(brokerToDetailedMap(name, broker)).build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to update broker: {}", e.getMessage(), e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid broker data: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Delete a broker configuration.
     */
    @DELETE
    @Path("/{name}")
    public Response deleteBroker(@PathParam("name") String name) {
        try {
            if (!config.getHonestBrokers().containsKey(name)) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Broker not found: " + name))
                        .build();
            }

            // Check if broker is in use by any route
            List<String> routesUsingBroker = findRoutesUsingBroker(name);
            if (!routesUsingBroker.isEmpty()) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of(
                                "error", "Broker is in use by routes: " + String.join(", ", routesUsingBroker),
                                "routes", routesUsingBroker
                        ))
                        .build();
            }

            config.getHonestBrokers().remove(name);
            config.save();

            // Clear cache
            brokerService.clearCache(name);

            log.info("Deleted honest broker: {}", name);
            return Response.ok(Map.of("success", true, "message", "Broker deleted: " + name)).build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Test connection to a broker.
     */
    @POST
    @Path("/{name}/test")
    public Response testBroker(@PathParam("name") String name) {
        HonestBrokerConfig broker = config.getHonestBroker(name);
        if (broker == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Broker not found: " + name))
                    .build();
        }

        boolean success = brokerService.testConnection(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("broker", name);
        result.put("success", success);
        result.put("message", success ? "Connection successful" : "Connection failed");

        return Response.ok(result).build();
    }

    /**
     * Perform a lookup using a broker.
     */
    @POST
    @Path("/{name}/lookup")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response lookup(@PathParam("name") String name, Map<String, Object> request) {
        HonestBrokerConfig broker = config.getHonestBroker(name);
        if (broker == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Broker not found: " + name))
                    .build();
        }

        String idIn = (String) request.get("idIn");
        if (idIn == null || idIn.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "idIn is required"))
                    .build();
        }

        String idOut = brokerService.lookup(name, idIn);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("broker", name);
        result.put("idIn", idIn);
        result.put("idOut", idOut);
        result.put("success", idOut != null);

        if (idOut == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(result)
                    .build();
        }

        return Response.ok(result).build();
    }

    /**
     * Clear cache for a broker.
     */
    @POST
    @Path("/{name}/cache/clear")
    public Response clearCache(@PathParam("name") String name) {
        HonestBrokerConfig broker = config.getHonestBroker(name);
        if (broker == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Broker not found: " + name))
                    .build();
        }

        brokerService.clearCache(name);
        return Response.ok(Map.of("success", true, "message", "Cache cleared for broker: " + name)).build();
    }

    /**
     * Clear all broker caches.
     */
    @POST
    @Path("/cache/clear")
    public Response clearAllCaches() {
        brokerService.clearAllCaches();
        return Response.ok(Map.of("success", true, "message", "All broker caches cleared")).build();
    }

    /**
     * Get crosswalk mappings for a broker.
     */
    @GET
    @Path("/{name}/crosswalk")
    public Response getCrosswalk(
            @PathParam("name") String name,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        HonestBrokerConfig broker = config.getHonestBroker(name);
        if (broker == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Broker not found: " + name))
                    .build();
        }

        // Only local brokers have crosswalk data
        if (!"local".equalsIgnoreCase(broker.getBrokerType())) {
            return Response.ok(Map.of(
                    "broker", name,
                    "type", broker.getBrokerType(),
                    "mappings", List.of(),
                    "message", "Crosswalk data only available for local brokers"
            )).build();
        }

        List<Map<String, Object>> mappings = new ArrayList<>();
        for (var entry : brokerService.getCrosswalkStore().getMappings(name)) {
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("id", entry.getId());
            mapping.put("idIn", entry.getIdIn());
            mapping.put("idOut", entry.getIdOut());
            mapping.put("idType", entry.getIdType());
            mapping.put("createdAt", entry.getCreatedAt());
            mapping.put("updatedAt", entry.getUpdatedAt());
            mappings.add(mapping);
            if (mappings.size() >= limit) break;
        }

        return Response.ok(Map.of(
                "broker", name,
                "totalMappings", brokerService.getCrosswalkStore().getMappingCount(name),
                "mappings", mappings
        )).build();
    }

    /**
     * GET version of lookup for easier testing.
     */
    @GET
    @Path("/{name}/lookup")
    public Response lookupGet(
            @PathParam("name") String name,
            @QueryParam("idIn") String idIn) {
        HonestBrokerConfig broker = config.getHonestBroker(name);
        if (broker == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Broker not found: " + name))
                    .build();
        }

        if (idIn == null || idIn.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "idIn query parameter is required"))
                    .build();
        }

        String idOut = brokerService.lookup(name, idIn);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("broker", name);
        result.put("idIn", idIn);
        result.put("idOut", idOut);
        result.put("success", idOut != null);

        if (idOut == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(result)
                    .build();
        }

        return Response.ok(result).build();
    }

    // ========================================================================
    // Backup and Restore Endpoints
    // ========================================================================

    /**
     * List all crosswalk database backups.
     */
    @GET
    @Path("/crosswalk/backups")
    public Response listBackups() {
        CrosswalkStore store = brokerService.getCrosswalkStore();
        List<BackupInfo> backups = store.listBackups();

        List<Map<String, Object>> result = new ArrayList<>();
        for (BackupInfo backup : backups) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("filename", backup.getFilename());
            info.put("timestamp", backup.getTimestamp());
            info.put("reason", backup.getReason());
            info.put("sizeBytes", backup.getSizeBytes());
            info.put("formattedSize", backup.getFormattedSize());
            info.put("mappingCount", backup.getMappingCount());
            info.put("logCount", backup.getLogCount());
            result.add(info);
        }

        return Response.ok(Map.of(
                "backups", result,
                "totalBackups", result.size(),
                "backupDirectory", store.getBackupDirectory()
        )).build();
    }

    /**
     * Create a new backup of the crosswalk database.
     */
    @POST
    @Path("/crosswalk/backups")
    public Response createBackup() {
        CrosswalkStore store = brokerService.getCrosswalkStore();
        BackupInfo backup = store.createBackup("manual");

        if (backup == null) {
            return Response.serverError()
                    .entity(Map.of("error", "Failed to create backup"))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Backup created successfully");
        result.put("filename", backup.getFilename());
        result.put("timestamp", backup.getTimestamp());
        result.put("sizeBytes", backup.getSizeBytes());
        result.put("formattedSize", backup.getFormattedSize());
        result.put("mappingCount", backup.getMappingCount());
        result.put("logCount", backup.getLogCount());

        log.info("Manual crosswalk backup created: {}", backup.getFilename());
        return Response.status(Response.Status.CREATED).entity(result).build();
    }

    /**
     * Restore the crosswalk database from a backup.
     */
    @POST
    @Path("/crosswalk/backups/{filename}/restore")
    public Response restoreBackup(@PathParam("filename") String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Backup filename is required"))
                    .build();
        }

        // Validate filename format
        if (!filename.matches("^crosswalk_\\d{8}_\\d{6}\\.db$")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid backup filename format"))
                    .build();
        }

        CrosswalkStore store = brokerService.getCrosswalkStore();
        boolean success = store.restoreFromBackup(filename);

        if (!success) {
            return Response.serverError()
                    .entity(Map.of("error", "Failed to restore from backup: " + filename))
                    .build();
        }

        log.info("Crosswalk database restored from backup: {}", filename);
        return Response.ok(Map.of(
                "success", true,
                "message", "Database restored from backup: " + filename,
                "restoredFrom", filename,
                "totalMappings", store.getTotalMappingCount(),
                "totalLogs", store.getTotalLogCount()
        )).build();
    }

    /**
     * Delete a backup file.
     */
    @DELETE
    @Path("/crosswalk/backups/{filename}")
    public Response deleteBackup(@PathParam("filename") String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Backup filename is required"))
                    .build();
        }

        CrosswalkStore store = brokerService.getCrosswalkStore();
        boolean success = store.deleteBackup(filename);

        if (!success) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Backup not found or could not be deleted: " + filename))
                    .build();
        }

        log.info("Deleted crosswalk backup: {}", filename);
        return Response.ok(Map.of(
                "success", true,
                "message", "Backup deleted: " + filename
        )).build();
    }

    /**
     * Get crosswalk database statistics.
     */
    @GET
    @Path("/crosswalk/stats")
    public Response getCrosswalkStats() {
        CrosswalkStore store = brokerService.getCrosswalkStore();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("databasePath", store.getDbPath());
        stats.put("backupDirectory", store.getBackupDirectory());
        stats.put("totalMappings", store.getTotalMappingCount());
        stats.put("totalLogs", store.getTotalLogCount());
        stats.put("backupCount", store.listBackups().size());

        // Per-broker stats
        List<Map<String, Object>> brokerStats = new ArrayList<>();
        Map<String, HonestBrokerConfig> configuredBrokers = config.getHonestBrokers();
        if (configuredBrokers != null) {
            for (String brokerName : configuredBrokers.keySet()) {
                HonestBrokerConfig broker = configuredBrokers.get(brokerName);
                if ("local".equalsIgnoreCase(broker.getBrokerType())) {
                    Map<String, Object> bs = new LinkedHashMap<>();
                    bs.put("name", brokerName);
                    bs.put("mappingCount", store.getMappingCount(brokerName));
                    brokerStats.add(bs);
                }
            }
        }
        stats.put("brokers", brokerStats);

        return Response.ok(stats).build();
    }

    /**
     * Export all crosswalk data as CSV.
     */
    @GET
    @Path("/crosswalk/export")
    @Produces("text/csv")
    public Response exportCrosswalkCsv() {
        CrosswalkStore store = brokerService.getCrosswalkStore();
        String csv = store.exportToCsv();

        return Response.ok(csv)
                .header("Content-Disposition", "attachment; filename=\"crosswalk_export.csv\"")
                .build();
    }

    /**
     * Trigger backup cleanup based on retention policy.
     */
    @POST
    @Path("/crosswalk/backups/cleanup")
    public Response cleanupBackups() {
        CrosswalkStore store = brokerService.getCrosswalkStore();
        int beforeCount = store.listBackups().size();
        store.cleanupOldBackups();
        int afterCount = store.listBackups().size();
        int deleted = beforeCount - afterCount;

        log.info("Backup cleanup complete: {} backups deleted", deleted);
        return Response.ok(Map.of(
                "success", true,
                "message", "Backup cleanup complete",
                "deletedCount", deleted,
                "remainingCount", afterCount
        )).build();
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private List<String> findRoutesUsingBroker(String brokerName) {
        List<String> routes = new ArrayList<>();
        List<AppConfig.RouteConfig> configuredRoutes = config.getRoutes();
        if (configuredRoutes != null) {
            for (AppConfig.RouteConfig route : configuredRoutes) {
                if (route.getDestinations() != null) {
                    for (AppConfig.RouteDestination dest : route.getDestinations()) {
                        if (dest.isUseHonestBroker() && brokerName.equals(dest.getHonestBrokerName())) {
                            routes.add(route.getAeTitle());
                            break;
                        }
                    }
                }
            }
        }
        return routes;
    }

    private Map<String, Object> brokerToSummaryMap(String name, HonestBrokerConfig broker) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("description", broker.getDescription());
        map.put("enabled", broker.isEnabled());
        map.put("type", broker.getBrokerType());
        map.put("namingScheme", broker.getNamingScheme());
        map.put("apiHost", broker.getApiHost());

        // Include crosswalk stats for local brokers in summary
        if ("local".equalsIgnoreCase(broker.getBrokerType())) {
            Map<String, Object> crosswalk = new LinkedHashMap<>();
            crosswalk.put("totalMappings", brokerService.getCrosswalkStore().getMappingCount(name));
            map.put("crosswalk", crosswalk);
        }

        return map;
    }

    private Map<String, Object> brokerToDetailedMap(String name, HonestBrokerConfig broker) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("description", broker.getDescription());
        map.put("enabled", broker.isEnabled());
        map.put("type", broker.getBrokerType());

        // Connection settings
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("stsHost", broker.getStsHost());
        connection.put("apiHost", broker.getApiHost());
        connection.put("timeout", broker.getTimeout());
        map.put("connection", connection);

        // Authentication (exclude sensitive data)
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("appName", broker.getAppName());
        auth.put("username", broker.getUsername());
        auth.put("hasAppKey", broker.getAppKey() != null && !broker.getAppKey().isEmpty());
        auth.put("hasPassword", broker.getPassword() != null && !broker.getPassword().isEmpty());
        map.put("auth", auth);

        // Cache settings
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("enabled", broker.isCacheEnabled());
        cache.put("ttlSeconds", broker.getCacheTtlSeconds());
        cache.put("maxSize", broker.getCacheMaxSize());
        map.put("cache", cache);

        // Behavior settings
        Map<String, Object> behavior = new LinkedHashMap<>();
        behavior.put("replacePatientId", broker.isReplacePatientId());
        behavior.put("replacePatientName", broker.isReplacePatientName());
        behavior.put("patientIdPrefix", broker.getPatientIdPrefix());
        behavior.put("patientNamePrefix", broker.getPatientNamePrefix());
        behavior.put("namingScheme", broker.getNamingScheme());
        behavior.put("lookupScript", broker.getLookupScript());
        behavior.put("cacheEnabled", broker.isCacheEnabled());
        behavior.put("cacheTtlSeconds", broker.getCacheTtlSeconds());
        behavior.put("cacheMaxSize", broker.getCacheMaxSize());
        map.put("behavior", behavior);

        // Date shifting settings
        Map<String, Object> dateShift = new LinkedHashMap<>();
        dateShift.put("enabled", broker.isDateShiftEnabled());
        dateShift.put("minDays", broker.getDateShiftMinDays());
        dateShift.put("maxDays", broker.getDateShiftMaxDays());
        map.put("dateShift", dateShift);

        // UID hashing settings
        map.put("hashUidsEnabled", broker.isHashUidsEnabled());

        // Crosswalk stats for local brokers
        if ("local".equalsIgnoreCase(broker.getBrokerType())) {
            Map<String, Object> crosswalk = new LinkedHashMap<>();
            crosswalk.put("totalMappings", brokerService.getCrosswalkStore().getMappingCount(name));
            map.put("crosswalk", crosswalk);
        }

        return map;
    }

    private HonestBrokerConfig createBrokerFromMap(Map<String, Object> data) {
        HonestBrokerConfig broker = new HonestBrokerConfig();
        updateBrokerFromMap(broker, data);
        return broker;
    }

    @SuppressWarnings("unchecked")
    private void updateBrokerFromMap(HonestBrokerConfig broker, Map<String, Object> data) {
        if (data.containsKey("description")) {
            broker.setDescription((String) data.get("description"));
        }
        if (data.containsKey("enabled")) {
            broker.setEnabled(toBoolean(data.get("enabled"), true));
        }
        if (data.containsKey("type")) {
            broker.setBrokerType((String) data.get("type"));
        }

        // Connection settings - can be flat or nested
        Map<String, Object> connection = (Map<String, Object>) data.get("connection");
        if (connection != null) {
            if (connection.containsKey("stsHost")) {
                broker.setStsHost((String) connection.get("stsHost"));
            }
            if (connection.containsKey("apiHost")) {
                broker.setApiHost((String) connection.get("apiHost"));
            }
            if (connection.containsKey("timeout")) {
                broker.setTimeout(toInt(connection.get("timeout"), 30));
            }
        } else {
            // Flat structure - support both camelCase and snake_case
            if (data.containsKey("stsHost") || data.containsKey("sts_host")) {
                broker.setStsHost((String) (data.containsKey("stsHost") ? data.get("stsHost") : data.get("sts_host")));
            }
            if (data.containsKey("apiHost") || data.containsKey("api_host")) {
                broker.setApiHost((String) (data.containsKey("apiHost") ? data.get("apiHost") : data.get("api_host")));
            }
            if (data.containsKey("timeout")) {
                broker.setTimeout(toInt(data.get("timeout"), 30));
            }
        }

        // Auth settings
        Map<String, Object> auth = (Map<String, Object>) data.get("auth");
        if (auth != null) {
            if (auth.containsKey("appName")) {
                broker.setAppName((String) auth.get("appName"));
            }
            // Only update sensitive fields if non-empty (don't overwrite with empty string)
            String authAppKey = (String) auth.get("appKey");
            if (authAppKey != null && !authAppKey.isEmpty()) {
                broker.setAppKey(authAppKey);
            }
            if (auth.containsKey("username")) {
                broker.setUsername((String) auth.get("username"));
            }
            String authPassword = (String) auth.get("password");
            if (authPassword != null && !authPassword.isEmpty()) {
                broker.setPassword(authPassword);
            }
        } else {
            // Flat structure
            if (data.containsKey("appName") || data.containsKey("app_name")) {
                broker.setAppName((String) (data.containsKey("appName") ? data.get("appName") : data.get("app_name")));
            }
            // Only update sensitive fields if non-empty (don't overwrite with empty string)
            String appKey = (String) (data.containsKey("appKey") ? data.get("appKey") : data.get("app_key"));
            if (appKey != null && !appKey.isEmpty()) {
                broker.setAppKey(appKey);
            }
            if (data.containsKey("username")) {
                broker.setUsername((String) data.get("username"));
            }
            String password = (String) data.get("password");
            if (password != null && !password.isEmpty()) {
                broker.setPassword(password);
            }
        }

        // Cache settings
        Map<String, Object> cache = (Map<String, Object>) data.get("cache");
        if (cache != null) {
            if (cache.containsKey("enabled")) {
                broker.setCacheEnabled(toBoolean(cache.get("enabled"), true));
            }
            if (cache.containsKey("ttlSeconds")) {
                broker.setCacheTtlSeconds(toInt(cache.get("ttlSeconds"), 3600));
            }
            if (cache.containsKey("maxSize")) {
                broker.setCacheMaxSize(toInt(cache.get("maxSize"), 10000));
            }
        } else {
            // Flat structure
            if (data.containsKey("cacheEnabled")) {
                broker.setCacheEnabled(toBoolean(data.get("cacheEnabled"), true));
            }
            if (data.containsKey("cacheTtlSeconds")) {
                broker.setCacheTtlSeconds(toInt(data.get("cacheTtlSeconds"), 3600));
            }
            if (data.containsKey("cacheMaxSize")) {
                broker.setCacheMaxSize(toInt(data.get("cacheMaxSize"), 10000));
            }
        }

        // Behavior settings
        Map<String, Object> behavior = (Map<String, Object>) data.get("behavior");
        if (behavior != null) {
            if (behavior.containsKey("replacePatientId")) {
                broker.setReplacePatientId(toBoolean(behavior.get("replacePatientId"), true));
            }
            if (behavior.containsKey("replacePatientName")) {
                broker.setReplacePatientName(toBoolean(behavior.get("replacePatientName"), true));
            }
            if (behavior.containsKey("patientIdPrefix")) {
                broker.setPatientIdPrefix((String) behavior.get("patientIdPrefix"));
            }
            if (behavior.containsKey("patientNamePrefix")) {
                broker.setPatientNamePrefix((String) behavior.get("patientNamePrefix"));
            }
            if (behavior.containsKey("namingScheme")) {
                broker.setNamingScheme((String) behavior.get("namingScheme"));
            }
            if (behavior.containsKey("lookupScript")) {
                broker.setLookupScript((String) behavior.get("lookupScript"));
            }
        } else {
            // Flat structure
            if (data.containsKey("replacePatientId") || data.containsKey("replace_patient_id")) {
                broker.setReplacePatientId(toBoolean(
                    data.containsKey("replacePatientId") ? data.get("replacePatientId") : data.get("replace_patient_id"),
                    true));
            }
            if (data.containsKey("replacePatientName") || data.containsKey("replace_patient_name")) {
                broker.setReplacePatientName(toBoolean(
                    data.containsKey("replacePatientName") ? data.get("replacePatientName") : data.get("replace_patient_name"),
                    true));
            }
            if (data.containsKey("patientIdPrefix") || data.containsKey("patient_id_prefix")) {
                broker.setPatientIdPrefix((String)
                    (data.containsKey("patientIdPrefix") ? data.get("patientIdPrefix") : data.get("patient_id_prefix")));
            }
            if (data.containsKey("patientNamePrefix") || data.containsKey("patient_name_prefix")) {
                broker.setPatientNamePrefix((String)
                    (data.containsKey("patientNamePrefix") ? data.get("patientNamePrefix") : data.get("patient_name_prefix")));
            }
            if (data.containsKey("namingScheme") || data.containsKey("naming_scheme")) {
                broker.setNamingScheme((String)
                    (data.containsKey("namingScheme") ? data.get("namingScheme") : data.get("naming_scheme")));
            }
            if (data.containsKey("lookupScript") || data.containsKey("lookup_script")) {
                broker.setLookupScript((String)
                    (data.containsKey("lookupScript") ? data.get("lookupScript") : data.get("lookup_script")));
            }
        }

        // Handle flat structure cache settings with snake_case
        if (data.containsKey("cache_enabled")) {
            broker.setCacheEnabled(toBoolean(data.get("cache_enabled"), true));
        }
        if (data.containsKey("cache_ttl_seconds")) {
            broker.setCacheTtlSeconds(toInt(data.get("cache_ttl_seconds"), 3600));
        }
        if (data.containsKey("cache_max_size")) {
            broker.setCacheMaxSize(toInt(data.get("cache_max_size"), 10000));
        }

        // Handle flat broker_type (snake_case from frontend)
        if (data.containsKey("broker_type")) {
            broker.setBrokerType((String) data.get("broker_type"));
        }

        // Date shifting settings - can be nested or flat
        @SuppressWarnings("unchecked")
        Map<String, Object> dateShift = (Map<String, Object>) data.get("dateShift");
        if (dateShift != null) {
            if (dateShift.containsKey("enabled")) {
                broker.setDateShiftEnabled(toBoolean(dateShift.get("enabled"), false));
            }
            if (dateShift.containsKey("minDays")) {
                broker.setDateShiftMinDays(toInt(dateShift.get("minDays"), -365));
            }
            if (dateShift.containsKey("maxDays")) {
                broker.setDateShiftMaxDays(toInt(dateShift.get("maxDays"), 365));
            }
        } else {
            // Flat structure
            if (data.containsKey("dateShiftEnabled") || data.containsKey("date_shift_enabled")) {
                broker.setDateShiftEnabled(toBoolean(
                    data.containsKey("dateShiftEnabled") ? data.get("dateShiftEnabled") : data.get("date_shift_enabled"),
                    false));
            }
            if (data.containsKey("dateShiftMinDays") || data.containsKey("date_shift_min_days")) {
                broker.setDateShiftMinDays(toInt(
                    data.containsKey("dateShiftMinDays") ? data.get("dateShiftMinDays") : data.get("date_shift_min_days"),
                    -365));
            }
            if (data.containsKey("dateShiftMaxDays") || data.containsKey("date_shift_max_days")) {
                broker.setDateShiftMaxDays(toInt(
                    data.containsKey("dateShiftMaxDays") ? data.get("dateShiftMaxDays") : data.get("date_shift_max_days"),
                    365));
            }
        }

        // UID hashing settings
        if (data.containsKey("hashUidsEnabled") || data.containsKey("hash_uids_enabled")) {
            broker.setHashUidsEnabled(toBoolean(
                data.containsKey("hashUidsEnabled") ? data.get("hashUidsEnabled") : data.get("hash_uids_enabled"),
                false));
        }
    }

    private boolean toBoolean(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return defaultValue;
    }

    private int toInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
