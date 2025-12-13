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
 * REST API for routes management.
 */
@Path("/routes")
@Produces(MediaType.APPLICATION_JSON)
public class RoutesResource {
    private static final Logger log = LoggerFactory.getLogger(RoutesResource.class);

    private final AppConfig config;

    public RoutesResource(AppConfig config) {
        this.config = config;
    }

    @GET
    public Response listRoutes() {
        List<Map<String, Object>> routes = new ArrayList<>();

        for (AppConfig.RouteConfig route : config.getRoutes()) {
            routes.add(routeToMap(route));
        }

        return Response.ok(routes).build();
    }

    @GET
    @Path("/{aeTitle}")
    public Response getRoute(@PathParam("aeTitle") String aeTitle) {
        AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);
        if (route == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Route not found: " + aeTitle))
                    .build();
        }

        return Response.ok(routeToDetailedMap(route)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createRoute(Map<String, Object> routeData) {
        try {
            String aeTitle = (String) routeData.get("aeTitle");
            if (aeTitle == null || aeTitle.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "AE Title is required"))
                        .build();
            }

            // Check if route already exists
            if (config.findRouteByAeTitle(aeTitle) != null) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "Route already exists: " + aeTitle))
                        .build();
            }

            // Check if port is already in use
            int port = routeData.containsKey("port") ? ((Number) routeData.get("port")).intValue() : 11112;
            for (AppConfig.RouteConfig existing : config.getRoutes()) {
                if (existing.getPort() == port) {
                    return Response.status(Response.Status.CONFLICT)
                            .entity(Map.of("error", "Port already in use by route: " + existing.getAeTitle()))
                            .build();
                }
            }

            AppConfig.RouteConfig route = new AppConfig.RouteConfig();
            updateRouteFromMap(route, routeData);

            config.getRoutes().add(route);
            config.save();

            log.info("Created route: {}", aeTitle);
            return Response.status(Response.Status.CREATED)
                    .entity(routeToDetailedMap(route))
                    .build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to create route: {}", e.getMessage(), e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid route data: " + e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{aeTitle}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateRoute(@PathParam("aeTitle") String aeTitle, Map<String, Object> routeData) {
        try {
            AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);
            if (route == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Route not found: " + aeTitle))
                        .build();
            }

            // Check if port is being changed to one that's in use
            if (routeData.containsKey("port")) {
                int newPort = ((Number) routeData.get("port")).intValue();
                if (newPort != route.getPort()) {
                    for (AppConfig.RouteConfig existing : config.getRoutes()) {
                        if (existing.getPort() == newPort && !existing.getAeTitle().equals(aeTitle)) {
                            return Response.status(Response.Status.CONFLICT)
                                    .entity(Map.of("error", "Port already in use by route: " + existing.getAeTitle()))
                                    .build();
                        }
                    }
                }
            }

            updateRouteFromMap(route, routeData);
            config.save();

            log.info("Updated route: {}", aeTitle);
            return Response.ok(routeToDetailedMap(route)).build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        } catch (Exception e) {
            log.error("Failed to update route: {}", e.getMessage(), e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid route data: " + e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{aeTitle}")
    public Response deleteRoute(@PathParam("aeTitle") String aeTitle) {
        try {
            AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);
            if (route == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Route not found: " + aeTitle))
                        .build();
            }

            config.getRoutes().remove(route);
            config.save();

            log.info("Deleted route: {}", aeTitle);
            return Response.noContent().build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{aeTitle}/enabled")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response toggleRouteEnabled(@PathParam("aeTitle") String aeTitle, Map<String, Boolean> data) {
        try {
            AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);
            if (route == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Route not found: " + aeTitle))
                        .build();
            }

            Boolean enabled = data.get("enabled");
            if (enabled != null) {
                route.setEnabled(enabled);
                config.save();
                log.info("Route {} {}", aeTitle, enabled ? "enabled" : "disabled");
            }

            return Response.ok(Map.of("aeTitle", aeTitle, "enabled", route.isEnabled())).build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        }
    }

    // ========== Route Destinations ==========

    @GET
    @Path("/{aeTitle}/destinations")
    public Response getRouteDestinations(@PathParam("aeTitle") String aeTitle) {
        AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);
        if (route == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Route not found: " + aeTitle))
                    .build();
        }

        List<Map<String, Object>> destinations = new ArrayList<>();
        for (AppConfig.RouteDestination dest : route.getDestinations()) {
            destinations.add(routeDestinationToMap(dest));
        }

        return Response.ok(destinations).build();
    }

    @POST
    @Path("/{aeTitle}/destinations")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addRouteDestination(@PathParam("aeTitle") String aeTitle, Map<String, Object> destData) {
        try {
            AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);
            if (route == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Route not found: " + aeTitle))
                        .build();
            }

            String destName = (String) destData.get("destination");
            if (destName == null || destName.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Destination name is required"))
                        .build();
            }

            // Check destination exists in global destinations
            if (!config.getDestinations().containsKey(destName)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Destination does not exist: " + destName))
                        .build();
            }

            AppConfig.RouteDestination dest = new AppConfig.RouteDestination();
            updateRouteDestinationFromMap(dest, destData);

            route.getDestinations().add(dest);
            config.save();

            log.info("Added destination {} to route {}", destName, aeTitle);
            return Response.status(Response.Status.CREATED)
                    .entity(routeDestinationToMap(dest))
                    .build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        }
    }

    @PUT
    @Path("/{aeTitle}/destinations/{destName}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateRouteDestination(@PathParam("aeTitle") String aeTitle,
                                            @PathParam("destName") String destName,
                                            Map<String, Object> destData) {
        try {
            AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);
            if (route == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Route not found: " + aeTitle))
                        .build();
            }

            AppConfig.RouteDestination dest = null;
            for (AppConfig.RouteDestination d : route.getDestinations()) {
                if (d.getDestination().equals(destName)) {
                    dest = d;
                    break;
                }
            }

            if (dest == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Destination not found in route: " + destName))
                        .build();
            }

            updateRouteDestinationFromMap(dest, destData);
            config.save();

            log.info("Updated destination {} in route {}", destName, aeTitle);
            return Response.ok(routeDestinationToMap(dest)).build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{aeTitle}/destinations/{destName}")
    public Response removeRouteDestination(@PathParam("aeTitle") String aeTitle,
                                            @PathParam("destName") String destName) {
        try {
            AppConfig.RouteConfig route = config.findRouteByAeTitle(aeTitle);
            if (route == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Route not found: " + aeTitle))
                        .build();
            }

            boolean removed = route.getDestinations().removeIf(d -> d.getDestination().equals(destName));
            if (!removed) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Destination not found in route: " + destName))
                        .build();
            }

            config.save();

            log.info("Removed destination {} from route {}", destName, aeTitle);
            return Response.noContent().build();
        } catch (IOException e) {
            log.error("Failed to save configuration: {}", e.getMessage(), e);
            return Response.serverError()
                    .entity(Map.of("error", "Failed to save configuration: " + e.getMessage()))
                    .build();
        }
    }

    // ========== Helper methods ==========

    private void updateRouteFromMap(AppConfig.RouteConfig route, Map<String, Object> data) {
        if (data.containsKey("aeTitle")) {
            route.setAeTitle((String) data.get("aeTitle"));
        }
        if (data.containsKey("port")) {
            route.setPort(((Number) data.get("port")).intValue());
        }
        if (data.containsKey("description")) {
            route.setDescription((String) data.get("description"));
        }
        if (data.containsKey("enabled")) {
            route.setEnabled((Boolean) data.get("enabled"));
        }
        if (data.containsKey("workerThreads")) {
            route.setWorkerThreads(((Number) data.get("workerThreads")).intValue());
        }
        if (data.containsKey("maxConcurrentTransfers")) {
            route.setMaxConcurrentTransfers(((Number) data.get("maxConcurrentTransfers")).intValue());
        }
        if (data.containsKey("studyTimeoutSeconds")) {
            route.setStudyTimeoutSeconds(((Number) data.get("studyTimeoutSeconds")).intValue());
        }
        if (data.containsKey("rateLimitPerMinute")) {
            route.setRateLimitPerMinute(((Number) data.get("rateLimitPerMinute")).intValue());
        }
        if (data.containsKey("webhookUrl")) {
            route.setWebhookUrl((String) data.get("webhookUrl"));
        }
        if (data.containsKey("webhookEvents")) {
            @SuppressWarnings("unchecked")
            List<String> events = (List<String>) data.get("webhookEvents");
            route.setWebhookEvents(events);
        }
    }

    private void updateRouteDestinationFromMap(AppConfig.RouteDestination dest, Map<String, Object> data) {
        if (data.containsKey("destination")) {
            dest.setDestination((String) data.get("destination"));
        }
        if (data.containsKey("enabled")) {
            dest.setEnabled((Boolean) data.get("enabled"));
        }
        if (data.containsKey("anonymize")) {
            dest.setAnonymize((Boolean) data.get("anonymize"));
        }
        if (data.containsKey("anonScript")) {
            dest.setAnonScript((String) data.get("anonScript"));
        }
        if (data.containsKey("projectId")) {
            dest.setProjectId((String) data.get("projectId"));
        }
        if (data.containsKey("subjectPrefix")) {
            dest.setSubjectPrefix((String) data.get("subjectPrefix"));
        }
        if (data.containsKey("sessionPrefix")) {
            dest.setSessionPrefix((String) data.get("sessionPrefix"));
        }
        if (data.containsKey("priority")) {
            dest.setPriority(((Number) data.get("priority")).intValue());
        }
        if (data.containsKey("retryCount")) {
            dest.setRetryCount(((Number) data.get("retryCount")).intValue());
        }
        if (data.containsKey("retryDelaySeconds")) {
            dest.setRetryDelaySeconds(((Number) data.get("retryDelaySeconds")).intValue());
        }
        if (data.containsKey("useHonestBroker")) {
            dest.setUseHonestBroker((Boolean) data.get("useHonestBroker"));
        }
        if (data.containsKey("honestBroker")) {
            dest.setHonestBrokerName((String) data.get("honestBroker"));
        }
    }

    private Map<String, Object> routeToMap(AppConfig.RouteConfig route) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("aeTitle", route.getAeTitle());
        map.put("port", route.getPort());
        map.put("description", route.getDescription());
        map.put("enabled", route.isEnabled());
        map.put("workerThreads", route.getWorkerThreads());
        map.put("destinationCount", route.getDestinations().size());
        return map;
    }

    private Map<String, Object> routeToDetailedMap(AppConfig.RouteConfig route) {
        Map<String, Object> map = routeToMap(route);
        map.put("maxConcurrentTransfers", route.getMaxConcurrentTransfers());
        map.put("studyTimeoutSeconds", route.getStudyTimeoutSeconds());
        map.put("rateLimitPerMinute", route.getRateLimitPerMinute());
        map.put("webhookUrl", route.getWebhookUrl());
        map.put("webhookEvents", route.getWebhookEvents());

        // Routing rules
        List<Map<String, Object>> routingRules = new ArrayList<>();
        for (AppConfig.RoutingRule rule : route.getRoutingRules()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", rule.getName());
            r.put("description", rule.getDescription());
            r.put("tag", rule.getTag());
            r.put("operator", rule.getOperator());
            r.put("value", rule.getValue());
            r.put("values", rule.getValues());
            r.put("destinations", rule.getDestinations());
            routingRules.add(r);
        }
        map.put("routingRules", routingRules);

        // Validation rules
        List<Map<String, Object>> validationRules = new ArrayList<>();
        for (AppConfig.ValidationRule rule : route.getValidationRules()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", rule.getName());
            r.put("type", rule.getType());
            r.put("tag", rule.getTag());
            r.put("onFailure", rule.getOnFailure());
            validationRules.add(r);
        }
        map.put("validationRules", validationRules);

        // Filters
        List<Map<String, Object>> filters = new ArrayList<>();
        for (AppConfig.FilterRule filter : route.getFilters()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", filter.getName());
            f.put("tag", filter.getTag());
            f.put("operator", filter.getOperator());
            f.put("value", filter.getValue());
            f.put("action", filter.getAction());
            filters.add(f);
        }
        map.put("filters", filters);

        // Destinations
        List<Map<String, Object>> destinations = new ArrayList<>();
        for (AppConfig.RouteDestination dest : route.getDestinations()) {
            destinations.add(routeDestinationToMap(dest));
        }
        map.put("destinations", destinations);

        return map;
    }

    private Map<String, Object> routeDestinationToMap(AppConfig.RouteDestination dest) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("destination", dest.getDestination());
        d.put("enabled", dest.isEnabled());
        d.put("anonymize", dest.isAnonymize());
        d.put("anonScript", dest.getAnonScript());
        d.put("projectId", dest.getProjectId());
        d.put("subjectPrefix", dest.getSubjectPrefix());
        d.put("sessionPrefix", dest.getSessionPrefix());
        d.put("priority", dest.getPriority());
        d.put("retryCount", dest.getRetryCount());
        d.put("retryDelaySeconds", dest.getRetryDelaySeconds());
        d.put("useHonestBroker", dest.isUseHonestBroker());
        d.put("honestBroker", dest.getHonestBrokerName());
        return d;
    }
}
