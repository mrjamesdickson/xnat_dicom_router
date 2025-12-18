/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.archive.ArchiveManager;
import io.xnatworks.router.retry.RetryManager;
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
    private final ArchiveManager archiveManager;
    private final RetryManager retryManager;

    public TransfersResource(TransferTracker transferTracker) {
        this(transferTracker, null, null);
    }

    public TransfersResource(TransferTracker transferTracker, ArchiveManager archiveManager, RetryManager retryManager) {
        this.transferTracker = transferTracker;
        this.archiveManager = archiveManager;
        this.retryManager = retryManager;
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

    // ========================================================================
    // Per-Destination Retry Endpoints
    // ========================================================================

    /**
     * Get per-destination status for a study from the archive.
     */
    @GET
    @Path("/{studyUid}/destinations")
    public Response getDestinationStatuses(
            @PathParam("studyUid") String studyUid,
            @QueryParam("aeTitle") String aeTitle) {

        if (archiveManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Archive manager not available"))
                    .build();
        }

        if (aeTitle == null || aeTitle.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "aeTitle query parameter is required"))
                    .build();
        }

        ArchiveManager.ArchivedStudy study = archiveManager.getArchivedStudy(aeTitle, studyUid);
        if (study == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Study not found in archive: " + studyUid))
                    .build();
        }

        List<Map<String, Object>> destinations = new ArrayList<>();
        Map<String, ArchiveManager.DestinationStatus> statuses = study.getDestinationStatuses();
        if (statuses != null) {
            for (Map.Entry<String, ArchiveManager.DestinationStatus> entry : statuses.entrySet()) {
                destinations.add(destinationStatusToMap(entry.getKey(), entry.getValue()));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studyUid", studyUid);
        result.put("aeTitle", aeTitle);
        result.put("destinations", destinations);
        result.put("hasOriginal", study.getOriginalPath() != null);
        result.put("hasAnonymized", study.getAnonymizedPath() != null);

        return Response.ok(result).build();
    }

    /**
     * Retry a specific destination for a study.
     */
    @POST
    @Path("/{studyUid}/destinations/{destination}/retry")
    public Response retryDestination(
            @PathParam("studyUid") String studyUid,
            @PathParam("destination") String destination,
            @QueryParam("aeTitle") String aeTitle) {

        if (retryManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Retry manager not available"))
                    .build();
        }

        if (aeTitle == null || aeTitle.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "aeTitle query parameter is required"))
                    .build();
        }

        boolean scheduled = retryManager.retryDestination(aeTitle, studyUid, destination);
        if (!scheduled) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Failed to schedule retry"))
                    .build();
        }

        return Response.ok(Map.of(
                "message", "Retry scheduled",
                "studyUid", studyUid,
                "destination", destination,
                "aeTitle", aeTitle
        )).build();
    }

    /**
     * Retry all failed destinations for a study.
     */
    @POST
    @Path("/{studyUid}/retry-all")
    public Response retryAllFailedDestinations(
            @PathParam("studyUid") String studyUid,
            @QueryParam("aeTitle") String aeTitle) {

        if (retryManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Retry manager not available"))
                    .build();
        }

        if (aeTitle == null || aeTitle.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "aeTitle query parameter is required"))
                    .build();
        }

        int scheduled = retryManager.retryAllFailedDestinations(aeTitle, studyUid);

        return Response.ok(Map.of(
                "message", "Retries scheduled",
                "studyUid", studyUid,
                "aeTitle", aeTitle,
                "scheduledCount", scheduled
        )).build();
    }

    /**
     * Get list of studies with partial success (some destinations failed).
     */
    @GET
    @Path("/partial")
    public Response getPartialTransfers(
            @QueryParam("aeTitle") String aeTitle,
            @QueryParam("limit") @DefaultValue("50") int limit) {

        if (retryManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Retry manager not available"))
                    .build();
        }

        if (aeTitle == null || aeTitle.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "aeTitle query parameter is required"))
                    .build();
        }

        List<RetryManager.FailedStudySummary> failed = retryManager.getFailedStudies(aeTitle, limit);

        List<Map<String, Object>> studies = new ArrayList<>();
        for (RetryManager.FailedStudySummary fs : failed) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("studyUid", fs.getStudyUid());
            map.put("aeTitle", fs.getAeTitle());
            map.put("archivedAt", fs.getArchivedAt() != null ? fs.getArchivedAt().toString() : null);
            map.put("totalDestinations", fs.getTotalDestinations());
            map.put("successfulDestinations", fs.getSuccessfulDestinations());
            map.put("failedDestinations", fs.getFailedDestinations());
            map.put("failedDestinationNames", fs.getFailedDestinationNames());
            studies.add(map);
        }

        return Response.ok(Map.of(
                "studies", studies,
                "count", studies.size(),
                "aeTitle", aeTitle
        )).build();
    }

    /**
     * Get pending retry queue.
     */
    @GET
    @Path("/retry-queue")
    public Response getRetryQueue() {
        if (retryManager == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "Retry manager not available"))
                    .build();
        }

        List<RetryManager.PendingRetry> pending = retryManager.getPendingRetries();

        List<Map<String, Object>> retries = new ArrayList<>();
        for (RetryManager.PendingRetry pr : pending) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("studyUid", pr.getStudyUid());
            map.put("destination", pr.getDestination());
            map.put("delayMs", pr.getDelayMs());
            retries.add(map);
        }

        return Response.ok(Map.of("pending", retries, "count", retries.size())).build();
    }

    private Map<String, Object> destinationStatusToMap(String destName, ArchiveManager.DestinationStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("destination", destName);
        map.put("status", status.getStatus() != null ? status.getStatus().name() : null);
        map.put("message", status.getMessage());
        map.put("attempts", status.getAttempts());
        map.put("lastAttemptAt", status.getLastAttemptAt() != null ? status.getLastAttemptAt().toString() : null);
        map.put("nextRetryAt", status.getNextRetryAt() != null ? status.getNextRetryAt().toString() : null);
        map.put("durationMs", status.getDurationMs());
        map.put("filesTransferred", status.getFilesTransferred());
        map.put("errorDetails", status.getErrorDetails());
        return map;
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
