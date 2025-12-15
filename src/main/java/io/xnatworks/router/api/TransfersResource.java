/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.tracking.TransferTracker;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.*;

/**
 * REST API for transfer history and status.
 */
@Path("/transfers")
@Produces(MediaType.APPLICATION_JSON)
public class TransfersResource {

    private final TransferTracker transferTracker;

    public TransfersResource(TransferTracker transferTracker) {
        this.transferTracker = transferTracker;
    }

    @GET
    public Response listTransfers(
            @QueryParam("aeTitle") String aeTitle,
            @QueryParam("status") String status,
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {

        List<TransferTracker.TransferRecord> records;

        if (aeTitle != null && !aeTitle.isEmpty()) {
            records = transferTracker.getTransferHistory(aeTitle, limit + offset);
        } else {
            records = transferTracker.getAllTransferHistory(limit + offset);
        }

        // Filter by status if specified
        if (status != null && !status.isEmpty()) {
            TransferTracker.TransferStatus filterStatus;
            try {
                filterStatus = TransferTracker.TransferStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid status: " + status))
                        .build();
            }

            List<TransferTracker.TransferRecord> filtered = new ArrayList<>();
            for (TransferTracker.TransferRecord record : records) {
                if (record.getStatus() == filterStatus) {
                    filtered.add(record);
                }
            }
            records = filtered;
        }

        // Apply pagination
        int start = Math.min(offset, records.size());
        int end = Math.min(offset + limit, records.size());
        List<TransferTracker.TransferRecord> page = records.subList(start, end);

        List<Map<String, Object>> transfers = new ArrayList<>();
        for (TransferTracker.TransferRecord record : page) {
            transfers.add(recordToMap(record));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transfers", transfers);
        result.put("total", records.size());
        result.put("offset", offset);
        result.put("limit", limit);

        return Response.ok(result).build();
    }

    @GET
    @Path("/active")
    public Response getActiveTransfers() {
        List<TransferTracker.TransferRecord> active = transferTracker.getActiveTransfers();

        List<Map<String, Object>> transfers = new ArrayList<>();
        for (TransferTracker.TransferRecord record : active) {
            transfers.add(recordToMap(record));
        }

        return Response.ok(Map.of("transfers", transfers, "count", transfers.size())).build();
    }

    @GET
    @Path("/{transferId}")
    public Response getTransfer(@PathParam("transferId") String transferId) {
        TransferTracker.TransferRecord record = transferTracker.getTransfer(transferId);
        if (record == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Transfer not found: " + transferId))
                    .build();
        }

        return Response.ok(recordToMap(record)).build();
    }

    @GET
    @Path("/statistics")
    public Response getGlobalStatistics() {
        TransferTracker.GlobalStatistics stats = transferTracker.getGlobalStatistics();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTransfers", stats.getTotalTransfers());
        result.put("successfulTransfers", stats.getSuccessfulTransfers());
        result.put("failedTransfers", stats.getFailedTransfers());
        result.put("activeTransfers", stats.getActiveTransfers());
        result.put("successRate", stats.getSuccessRate());
        result.put("totalBytes", stats.getTotalBytes());
        result.put("averageTransferTime", stats.getAverageTransferTime());

        return Response.ok(result).build();
    }

    @GET
    @Path("/statistics/{aeTitle}")
    public Response getAeTitleStatistics(@PathParam("aeTitle") String aeTitle) {
        TransferTracker.AeTitleStatistics stats = transferTracker.getAeTitleStatistics(aeTitle);
        if (stats == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No statistics for AE Title: " + aeTitle))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aeTitle", aeTitle);
        result.put("totalTransfers", stats.getTotalTransfers());
        result.put("successfulTransfers", stats.getSuccessfulTransfers());
        result.put("failedTransfers", stats.getFailedTransfers());
        result.put("activeTransfers", stats.getActiveTransfers());
        result.put("successRate", stats.getSuccessRate());
        result.put("totalBytes", stats.getTotalBytes());
        result.put("averageTransferTime", stats.getAverageTransferTime());

        // Per-destination stats
        Map<String, Object> destStats = new LinkedHashMap<>();
        for (Map.Entry<String, TransferTracker.DestinationStatistics> entry :
                stats.getDestinationStatistics().entrySet()) {
            TransferTracker.DestinationStatistics ds = entry.getValue();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("successful", ds.getSuccessful());
            d.put("failed", ds.getFailed());
            d.put("successRate", ds.getSuccessRate());
            destStats.put(entry.getKey(), d);
        }
        result.put("destinations", destStats);

        return Response.ok(result).build();
    }

    @GET
    @Path("/by-study/{studyUid}")
    public Response getTransfersByStudy(@PathParam("studyUid") String studyUid) {
        List<TransferTracker.TransferRecord> records = transferTracker.getTransfersByStudyUid(studyUid);

        List<Map<String, Object>> transfers = new ArrayList<>();
        for (TransferTracker.TransferRecord record : records) {
            transfers.add(recordToMap(record));
        }

        return Response.ok(Map.of("studyUid", studyUid, "transfers", transfers)).build();
    }

    @GET
    @Path("/failed")
    public Response getFailedTransfers(
            @QueryParam("aeTitle") String aeTitle,
            @QueryParam("limit") @DefaultValue("50") int limit) {

        List<TransferTracker.TransferRecord> failed = transferTracker.getFailedTransfers(aeTitle, limit);

        List<Map<String, Object>> transfers = new ArrayList<>();
        for (TransferTracker.TransferRecord record : failed) {
            transfers.add(recordToMap(record));
        }

        return Response.ok(Map.of("transfers", transfers, "count", transfers.size())).build();
    }

    @POST
    @Path("/{transferId}/retry")
    public Response retryTransfer(@PathParam("transferId") String transferId) {
        TransferTracker.TransferRecord record = transferTracker.getTransfer(transferId);
        if (record == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Transfer not found: " + transferId))
                    .build();
        }

        if (record.getStatus() != TransferTracker.TransferStatus.FAILED) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Can only retry failed transfers"))
                    .build();
        }

        // Queue for retry
        boolean queued = transferTracker.queueForRetry(transferId);
        if (!queued) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to queue transfer for retry"))
                    .build();
        }

        return Response.ok(Map.of("message", "Transfer queued for retry", "transferId", transferId)).build();
    }

    private Map<String, Object> recordToMap(TransferTracker.TransferRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("transferId", record.getId());
        map.put("aeTitle", record.getAeTitle());
        map.put("studyUid", record.getStudyUid());
        map.put("callingAeTitle", record.getCallingAeTitle());
        map.put("fileCount", record.getFileCount());
        map.put("totalSize", record.getTotalSize());
        map.put("status", record.getStatus().name());
        map.put("receivedAt", record.getReceivedAt() != null ? record.getReceivedAt().toString() : null);
        map.put("processingStartedAt", record.getProcessingStartedAt() != null ? record.getProcessingStartedAt().toString() : null);
        map.put("forwardingStartedAt", record.getForwardingStartedAt() != null ? record.getForwardingStartedAt().toString() : null);
        map.put("completedAt", record.getCompletedAt() != null ? record.getCompletedAt().toString() : null);
        map.put("errorMessage", record.getErrorMessage());
        map.put("durationMs", record.getTotalDurationMs());

        // Progress tracking
        map.put("filesProcessed", record.getFilesProcessed());
        map.put("bytesProcessed", record.getBytesProcessed());
        map.put("progressPercent", record.getProgressPercent());

        // Destination results
        List<Map<String, Object>> destResults = new ArrayList<>();
        if (record.getDestinationResults() != null) {
            for (TransferTracker.DestinationResult dr : record.getDestinationResults()) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("destination", dr.getDestination());
                d.put("status", dr.getStatus().name());
                d.put("message", dr.getMessage());
                d.put("durationMs", dr.getDurationMs());
                d.put("filesTransferred", dr.getFilesTransferred());
                d.put("completedAt", dr.getCompletedAt() != null ? dr.getCompletedAt().toString() : null);
                destResults.add(d);
            }
        }
        map.put("destinations", destResults);

        return map;
    }
}
