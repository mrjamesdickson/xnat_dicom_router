/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.metrics.MetricsCollector;
import io.xnatworks.router.metrics.MetricsCollector.MetricPoint;
import io.xnatworks.router.metrics.MetricsCollector.RouteSummary;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.*;

/**
 * REST API for metrics and time-series data.
 */
@Path("/metrics")
@Produces(MediaType.APPLICATION_JSON)
public class MetricsResource {

    private final MetricsCollector metricsCollector;

    public MetricsResource(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Get metrics summary including current throughput.
     */
    @GET
    public Response getMetricsSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        summary.put("currentThroughput", metricsCollector.getCurrentThroughput());
        summary.put("currentBytesPerMinute", metricsCollector.getCurrentBytesPerMinute());
        summary.put("routes", metricsCollector.getRouteSummaries());

        return Response.ok(summary).build();
    }

    /**
     * Get time-series data for the last N minutes.
     */
    @GET
    @Path("/timeseries/minutes")
    public Response getMinuteMetrics(@QueryParam("count") @DefaultValue("60") int count) {
        if (count < 1 || count > 60) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Count must be between 1 and 60"))
                    .build();
        }

        List<MetricPoint> data = metricsCollector.getMinuteMetrics(count);
        return Response.ok(Map.of(
                "resolution", "minute",
                "count", data.size(),
                "data", pointsToMaps(data)
        )).build();
    }

    /**
     * Get time-series data for the last N hours.
     */
    @GET
    @Path("/timeseries/hours")
    public Response getHourlyMetrics(@QueryParam("count") @DefaultValue("24") int count) {
        if (count < 1 || count > 24) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Count must be between 1 and 24"))
                    .build();
        }

        List<MetricPoint> data = metricsCollector.getHourlyMetrics(count);
        return Response.ok(Map.of(
                "resolution", "hour",
                "count", data.size(),
                "data", pointsToMaps(data)
        )).build();
    }

    /**
     * Get time-series data for the last N days.
     */
    @GET
    @Path("/timeseries/days")
    public Response getDailyMetrics(@QueryParam("count") @DefaultValue("30") int count) {
        if (count < 1 || count > 30) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Count must be between 1 and 30"))
                    .build();
        }

        List<MetricPoint> data = metricsCollector.getDailyMetrics(count);
        return Response.ok(Map.of(
                "resolution", "day",
                "count", data.size(),
                "data", pointsToMaps(data)
        )).build();
    }

    /**
     * Get time-series data for a specific route.
     */
    @GET
    @Path("/routes/{aeTitle}")
    public Response getRouteMetrics(
            @PathParam("aeTitle") String aeTitle,
            @QueryParam("minutes") @DefaultValue("60") int minutes) {

        if (minutes < 1 || minutes > 60) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Minutes must be between 1 and 60"))
                    .build();
        }

        List<MetricPoint> data = metricsCollector.getRouteMetrics(aeTitle, minutes);

        // Also get summary
        Map<String, RouteSummary> summaries = metricsCollector.getRouteSummaries();
        RouteSummary summary = summaries.get(aeTitle);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aeTitle", aeTitle);
        result.put("resolution", "minute");
        result.put("count", data.size());
        result.put("data", pointsToMaps(data));

        if (summary != null) {
            Map<String, Object> summaryMap = new LinkedHashMap<>();
            summaryMap.put("totalTransfers", summary.totalTransfers);
            summaryMap.put("successfulTransfers", summary.successfulTransfers);
            summaryMap.put("failedTransfers", summary.failedTransfers);
            summaryMap.put("totalBytes", summary.totalBytes);
            summaryMap.put("totalFiles", summary.totalFiles);
            summaryMap.put("recentThroughput", summary.recentThroughput);
            result.put("summary", summaryMap);
        }

        return Response.ok(result).build();
    }

    /**
     * Get all route summaries.
     */
    @GET
    @Path("/routes")
    public Response getAllRouteSummaries() {
        Map<String, RouteSummary> summaries = metricsCollector.getRouteSummaries();

        List<Map<String, Object>> routeList = new ArrayList<>();
        for (RouteSummary summary : summaries.values()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("aeTitle", summary.aeTitle);
            map.put("totalTransfers", summary.totalTransfers);
            map.put("successfulTransfers", summary.successfulTransfers);
            map.put("failedTransfers", summary.failedTransfers);
            map.put("totalBytes", summary.totalBytes);
            map.put("totalFiles", summary.totalFiles);
            map.put("recentThroughput", summary.recentThroughput);
            routeList.add(map);
        }

        return Response.ok(Map.of("routes", routeList)).build();
    }

    /**
     * Convert MetricPoints to maps for JSON serialization.
     */
    private List<Map<String, Object>> pointsToMaps(List<MetricPoint> points) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MetricPoint point : points) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("timestamp", point.timestamp);
            map.put("timestampIso", point.getTimestampIso());
            map.put("transfers", point.transfers);
            map.put("successful", point.successful);
            map.put("failed", point.failed);
            map.put("bytes", point.bytes);
            map.put("files", point.files);
            result.add(map);
        }
        return result;
    }
}
