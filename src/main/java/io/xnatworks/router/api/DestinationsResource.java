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
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * REST API for destination management.
 */
@Path("/destinations")
@Produces(MediaType.APPLICATION_JSON)
public class DestinationsResource {
    private static final Logger log = LoggerFactory.getLogger(DestinationsResource.class);

    private final AppConfig config;
    private final DestinationManager destinationManager;

    public DestinationsResource(AppConfig config, DestinationManager destinationManager) {
        this.config = config;
        this.destinationManager = destinationManager;
    }

    @GET
    public Response listDestinations() {
        List<Map<String, Object>> destinations = new ArrayList<>();

        for (Map.Entry<String, AppConfig.Destination> entry : config.getDestinations().entrySet()) {
            destinations.add(destinationToMap(entry.getKey(), entry.getValue()));
        }

        return Response.ok(destinations).build();
    }

    @GET
    @Path("/{name}")
    public Response getDestination(@PathParam("name") String name) {
        AppConfig.Destination dest = config.getDestinations().get(name);
        if (dest == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Destination not found: " + name))
                    .build();
        }

        Map<String, Object> result = destinationToDetailedMap(name, dest);

        // Add health info
        DestinationManager.DestinationHealth health = destinationManager.getHealth(name);
        if (health != null) {
            Map<String, Object> healthInfo = new LinkedHashMap<>();
            healthInfo.put("available", health.isAvailable());
            healthInfo.put("availabilityPercent", health.getAvailabilityPercent());
            healthInfo.put("totalChecks", health.getTotalChecks());
            healthInfo.put("successfulChecks", health.getSuccessfulChecks());
            healthInfo.put("consecutiveFailures", health.getConsecutiveFailures());
            healthInfo.put("lastCheck", health.getLastCheckTime() != null ? health.getLastCheckTime().toString() : null);
            healthInfo.put("lastAvailable", health.getLastAvailableTime() != null ? health.getLastAvailableTime().toString() : null);
            healthInfo.put("unavailableSince", health.getUnavailableSince() != null ? health.getUnavailableSince().toString() : null);
            healthInfo.put("downtimeSeconds", health.getDowntimeSeconds());
            result.put("health", healthInfo);
        }

        return Response.ok(result).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createDestination(Map<String, Object> destData) {
        try {
            String name = (String) destData.get("name");
            if (name == null || name.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Destination name is required"))
                        .build();
            }

            if (config.getDestinations().containsKey(name)) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "Destination already exists: " + name))
                        .build();
            }

            String type = (String) destData.get("type");
            if (type == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Destination type is required (xnat, dicom, or file)"))
                        .build();
            }

            AppConfig.Destination dest = createDestinationFromMap(type, destData);
            if (dest == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid destination type: " + type))
                        .build();
            }

            config.getDestinations().put(name, dest);
            config.save();

            // Re-initialize destination manager for this destination
            destinationManager.addDestination(name, dest);

            log.info("Created destination: {} (type: {})", name, type);
            return Response.status(Response.Status.CREATED)
                    .entity(destinationToDetailedMap(name, dest))
                    .build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create destination: {}", e.getMessage(), e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid destination data: " + e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateDestination(@PathParam("name") String name, Map<String, Object> destData) {
        try {
            AppConfig.Destination dest = config.getDestinations().get(name);
            if (dest == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Destination not found: " + name))
                        .build();
            }

            updateDestinationFromMap(dest, destData);
            config.save();

            // Update destination manager
            destinationManager.updateDestination(name, dest);

            log.info("Updated destination: {}", name);
            return Response.ok(destinationToDetailedMap(name, dest)).build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to update destination: {}", e.getMessage(), e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid destination data: " + e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{name}")
    public Response deleteDestination(@PathParam("name") String name) {
        try {
            if (!config.getDestinations().containsKey(name)) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Destination not found: " + name))
                        .build();
            }

            // Check if destination is in use by any route
            for (AppConfig.RouteConfig route : config.getRoutes()) {
                for (AppConfig.RouteDestination rd : route.getDestinations()) {
                    if (rd.getDestination().equals(name)) {
                        return Response.status(Response.Status.CONFLICT)
                                .entity(Map.of("error", "Destination is in use by route: " + route.getAeTitle()))
                                .build();
                    }
                }
            }

            config.getDestinations().remove(name);
            config.save();

            // Remove from destination manager
            destinationManager.removeDestination(name);

            log.info("Deleted destination: {}", name);
            return Response.noContent().build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{name}/enabled")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response toggleDestinationEnabled(@PathParam("name") String name, Map<String, Boolean> data) {
        try {
            AppConfig.Destination dest = config.getDestinations().get(name);
            if (dest == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Destination not found: " + name))
                        .build();
            }

            Boolean enabled = data.get("enabled");
            if (enabled != null) {
                dest.setEnabled(enabled);
                config.save();
                log.info("Destination {} {}", name, enabled ? "enabled" : "disabled");
            }

            return Response.ok(Map.of("name", name, "enabled", dest.isEnabled())).build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/{name}/health")
    public Response getDestinationHealth(@PathParam("name") String name) {
        DestinationManager.DestinationHealth health = destinationManager.getHealth(name);
        if (health == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Destination not found: " + name))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("type", health.getType());
        result.put("available", health.isAvailable());
        result.put("availabilityPercent", health.getAvailabilityPercent());
        result.put("totalChecks", health.getTotalChecks());
        result.put("successfulChecks", health.getSuccessfulChecks());
        result.put("consecutiveFailures", health.getConsecutiveFailures());
        result.put("lastCheck", health.getLastCheckTime() != null ? health.getLastCheckTime().toString() : null);
        result.put("lastAvailable", health.getLastAvailableTime() != null ? health.getLastAvailableTime().toString() : null);
        result.put("downtimeSeconds", health.getDowntimeSeconds());

        return Response.ok(result).build();
    }

    @POST
    @Path("/{name}/check")
    public Response checkDestination(@PathParam("name") String name) {
        if (!config.getDestinations().containsKey(name)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Destination not found: " + name))
                    .build();
        }

        boolean available = destinationManager.checkDestination(name);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("available", available);
        result.put("checkedAt", java.time.LocalDateTime.now().toString());

        return Response.ok(result).build();
    }

    @GET
    @Path("/available")
    public Response getAvailableDestinations() {
        List<String> available = destinationManager.getAvailableDestinations();
        return Response.ok(Map.of("available", available, "count", available.size())).build();
    }

    @GET
    @Path("/types")
    public Response getDestinationTypes() {
        return Response.ok(List.of(
                Map.of("type", "xnat", "description", "XNAT instance (HTTP/HTTPS)", "requiredFields", List.of("url")),
                Map.of("type", "dicom", "description", "DICOM AE Title (C-STORE)", "requiredFields", List.of("aeTitle", "host", "port")),
                Map.of("type", "file", "description", "File system directory", "requiredFields", List.of("path"))
        )).build();
    }

    // ========== Helper methods ==========

    private AppConfig.Destination createDestinationFromMap(String type, Map<String, Object> data) {
        switch (type.toLowerCase()) {
            case "xnat": {
                AppConfig.XnatDestination dest = new AppConfig.XnatDestination();
                dest.setUrl((String) data.get("url"));
                dest.setUsername((String) data.get("username"));
                dest.setPassword((String) data.get("password"));
                if (data.containsKey("enabled")) dest.setEnabled((Boolean) data.get("enabled"));
                if (data.containsKey("timeout")) dest.setTimeout(((Number) data.get("timeout")).intValue());
                if (data.containsKey("maxRetries")) dest.setMaxRetries(((Number) data.get("maxRetries")).intValue());
                if (data.containsKey("connectionPoolSize")) dest.setConnectionPoolSize(((Number) data.get("connectionPoolSize")).intValue());
                return dest;
            }
            case "dicom": {
                AppConfig.DicomAeDestination dest = new AppConfig.DicomAeDestination();
                dest.setAeTitle((String) data.get("aeTitle"));
                dest.setHost((String) data.get("host"));
                if (data.containsKey("port")) dest.setPort(((Number) data.get("port")).intValue());
                if (data.containsKey("enabled")) dest.setEnabled((Boolean) data.get("enabled"));
                if (data.containsKey("timeout")) dest.setTimeout(((Number) data.get("timeout")).intValue());
                if (data.containsKey("maxRetries")) dest.setMaxRetries(((Number) data.get("maxRetries")).intValue());
                if (data.containsKey("tlsEnabled")) dest.setTlsEnabled((Boolean) data.get("tlsEnabled"));
                return dest;
            }
            case "file": {
                AppConfig.FileDestination dest = new AppConfig.FileDestination();
                dest.setPath((String) data.get("path"));
                if (data.containsKey("enabled")) dest.setEnabled((Boolean) data.get("enabled"));
                if (data.containsKey("createSubdirectories")) dest.setCreateSubdirectories((Boolean) data.get("createSubdirectories"));
                if (data.containsKey("namingPattern")) dest.setNamingPattern((String) data.get("namingPattern"));
                return dest;
            }
            default:
                return null;
        }
    }

    private void updateDestinationFromMap(AppConfig.Destination dest, Map<String, Object> data) {
        if (data.containsKey("enabled")) {
            dest.setEnabled((Boolean) data.get("enabled"));
        }

        if (dest instanceof AppConfig.XnatDestination) {
            AppConfig.XnatDestination xnat = (AppConfig.XnatDestination) dest;
            if (data.containsKey("url")) xnat.setUrl((String) data.get("url"));
            if (data.containsKey("username")) xnat.setUsername((String) data.get("username"));
            if (data.containsKey("password") && data.get("password") != null && !((String)data.get("password")).isEmpty()) {
                xnat.setPassword((String) data.get("password"));
            }
            if (data.containsKey("timeout")) xnat.setTimeout(((Number) data.get("timeout")).intValue());
            if (data.containsKey("maxRetries")) xnat.setMaxRetries(((Number) data.get("maxRetries")).intValue());
            if (data.containsKey("connectionPoolSize")) xnat.setConnectionPoolSize(((Number) data.get("connectionPoolSize")).intValue());
        } else if (dest instanceof AppConfig.DicomAeDestination) {
            AppConfig.DicomAeDestination dicom = (AppConfig.DicomAeDestination) dest;
            if (data.containsKey("aeTitle")) dicom.setAeTitle((String) data.get("aeTitle"));
            if (data.containsKey("host")) dicom.setHost((String) data.get("host"));
            if (data.containsKey("port")) dicom.setPort(((Number) data.get("port")).intValue());
            if (data.containsKey("timeout")) dicom.setTimeout(((Number) data.get("timeout")).intValue());
            if (data.containsKey("maxRetries")) dicom.setMaxRetries(((Number) data.get("maxRetries")).intValue());
            if (data.containsKey("tlsEnabled")) dicom.setTlsEnabled((Boolean) data.get("tlsEnabled"));
        } else if (dest instanceof AppConfig.FileDestination) {
            AppConfig.FileDestination file = (AppConfig.FileDestination) dest;
            if (data.containsKey("path")) file.setPath((String) data.get("path"));
            if (data.containsKey("createSubdirectories")) file.setCreateSubdirectories((Boolean) data.get("createSubdirectories"));
            if (data.containsKey("namingPattern")) file.setNamingPattern((String) data.get("namingPattern"));
        }
    }

    private Map<String, Object> destinationToMap(String name, AppConfig.Destination d) {
        Map<String, Object> dest = new LinkedHashMap<>();
        dest.put("name", name);

        if (d instanceof AppConfig.XnatDestination) {
            AppConfig.XnatDestination xnat = (AppConfig.XnatDestination) d;
            dest.put("type", "xnat");
            dest.put("url", xnat.getUrl());
            dest.put("enabled", xnat.isEnabled());
        } else if (d instanceof AppConfig.DicomAeDestination) {
            AppConfig.DicomAeDestination dicom = (AppConfig.DicomAeDestination) d;
            dest.put("type", "dicom");
            dest.put("aeTitle", dicom.getAeTitle());
            dest.put("host", dicom.getHost());
            dest.put("port", dicom.getPort());
            dest.put("enabled", dicom.isEnabled());
        } else if (d instanceof AppConfig.FileDestination) {
            AppConfig.FileDestination file = (AppConfig.FileDestination) d;
            dest.put("type", "file");
            dest.put("path", file.getPath());
            dest.put("enabled", file.isEnabled());
        }

        // Add health info
        DestinationManager.DestinationHealth health = destinationManager.getHealth(name);
        if (health != null) {
            dest.put("available", health.isAvailable());
            dest.put("availabilityPercent", health.getAvailabilityPercent());
            dest.put("lastCheck", health.getLastCheckTime() != null ? health.getLastCheckTime().toString() : null);
            dest.put("consecutiveFailures", health.getConsecutiveFailures());
        }

        return dest;
    }

    private Map<String, Object> destinationToDetailedMap(String name, AppConfig.Destination d) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);

        if (d instanceof AppConfig.XnatDestination) {
            AppConfig.XnatDestination xnat = (AppConfig.XnatDestination) d;
            result.put("type", "xnat");
            result.put("url", xnat.getUrl());
            result.put("username", xnat.getUsername());
            result.put("enabled", xnat.isEnabled());
            result.put("timeout", xnat.getTimeout());
            result.put("maxRetries", xnat.getMaxRetries());
            result.put("connectionPoolSize", xnat.getConnectionPoolSize());
        } else if (d instanceof AppConfig.DicomAeDestination) {
            AppConfig.DicomAeDestination dicom = (AppConfig.DicomAeDestination) d;
            result.put("type", "dicom");
            result.put("aeTitle", dicom.getAeTitle());
            result.put("host", dicom.getHost());
            result.put("port", dicom.getPort());
            result.put("enabled", dicom.isEnabled());
            result.put("timeout", dicom.getTimeout());
            result.put("maxRetries", dicom.getMaxRetries());
            result.put("tlsEnabled", dicom.isTlsEnabled());
        } else if (d instanceof AppConfig.FileDestination) {
            AppConfig.FileDestination file = (AppConfig.FileDestination) d;
            result.put("type", "file");
            result.put("path", file.getPath());
            result.put("enabled", file.isEnabled());
            result.put("createSubdirectories", file.isCreateSubdirectories());
            result.put("namingPattern", file.getNamingPattern());
        }

        return result;
    }
}
