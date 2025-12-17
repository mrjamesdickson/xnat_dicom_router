/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.index.DicomIndexer;
import io.xnatworks.router.store.RouterStore;
import io.xnatworks.router.store.RouterStore.*;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for DICOM index search and custom field management.
 */
@Path("/search")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SearchResource {

    private final AppConfig config;
    private final RouterStore store;
    private final DicomIndexer indexer;

    @Inject
    public SearchResource(AppConfig config, RouterStore store, DicomIndexer indexer) {
        this.config = config;
        this.store = store;
        this.indexer = indexer;
    }

    // ========================================================================
    // Search Endpoints
    // ========================================================================

    /**
     * Search for studies.
     */
    @GET
    @Path("/studies")
    public Response searchStudies(
            @QueryParam("patientId") String patientId,
            @QueryParam("patientName") String patientName,
            @QueryParam("studyDateFrom") String studyDateFrom,
            @QueryParam("studyDateTo") String studyDateTo,
            @QueryParam("modality") String modality,
            @QueryParam("accessionNumber") String accessionNumber,
            @QueryParam("institutionName") String institutionName,
            @QueryParam("studyDescription") String studyDescription,
            @QueryParam("sourceRoute") String sourceRoute,
            @QueryParam("limit") @DefaultValue("100") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset) {

        SearchCriteria criteria = new SearchCriteria();
        criteria.patientId = patientId;
        criteria.patientName = patientName;
        criteria.studyDateFrom = studyDateFrom;
        criteria.studyDateTo = studyDateTo;
        criteria.modality = modality;
        criteria.accessionNumber = accessionNumber;
        criteria.institutionName = institutionName;
        criteria.studyDescription = studyDescription;
        criteria.sourceRoute = sourceRoute;
        criteria.limit = limit;
        criteria.offset = offset;

        List<IndexedStudy> studies = store.searchStudies(criteria);

        // Populate custom field values for each study
        List<CustomField> displayFields = store.getEnabledCustomFields().stream()
                .filter(f -> f.displayInList && "study".equals(f.level))
                .toList();

        for (IndexedStudy study : studies) {
            if (!displayFields.isEmpty()) {
                study.customFields = store.getCustomFieldValues(study.studyUid);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("studies", studies);
        result.put("count", studies.size());
        result.put("offset", offset);
        result.put("limit", limit);

        return Response.ok(result).build();
    }

    /**
     * Get study details by UID.
     */
    @GET
    @Path("/studies/{studyUid}")
    public Response getStudy(@PathParam("studyUid") String studyUid) {
        IndexedStudy study = store.getStudy(studyUid);
        if (study == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Study not found: " + studyUid))
                    .build();
        }

        // Get custom field values
        study.customFields = store.getCustomFieldValues(studyUid);

        // Get series
        List<IndexedSeries> series = store.getSeriesForStudy(studyUid);
        for (IndexedSeries s : series) {
            s.customFields = store.getCustomFieldValues(s.seriesUid);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("study", study);
        result.put("series", series);

        return Response.ok(result).build();
    }

    /**
     * Get series details by UID.
     */
    @GET
    @Path("/series/{seriesUid}")
    public Response getSeries(@PathParam("seriesUid") String seriesUid) {
        List<IndexedInstance> instances = store.getInstancesForSeries(seriesUid);
        if (instances.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Series not found: " + seriesUid))
                    .build();
        }

        // Get custom field values for instances
        for (IndexedInstance instance : instances) {
            instance.customFields = store.getCustomFieldValues(instance.sopInstanceUid);
        }

        return Response.ok(Map.of("instances", instances, "count", instances.size())).build();
    }

    /**
     * Get index statistics.
     */
    @GET
    @Path("/stats")
    public Response getStats() {
        IndexStats stats = store.getIndexStats();
        return Response.ok(stats).build();
    }

    /**
     * Get study counts grouped by study date.
     * Useful for charting index coverage over time.
     *
     * @param fromDate optional start date filter (YYYYMMDD format)
     * @param toDate optional end date filter (YYYYMMDD format)
     */
    @GET
    @Path("/stats/by-date")
    public Response getStudiesByDate(
            @QueryParam("fromDate") String fromDate,
            @QueryParam("toDate") String toDate) {

        // Validate date formats if provided
        if (fromDate != null && !fromDate.isEmpty() && !isValidDicomDate(fromDate)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid fromDate format. Use YYYYMMDD"))
                    .build();
        }
        if (toDate != null && !toDate.isEmpty() && !isValidDicomDate(toDate)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid toDate format. Use YYYYMMDD"))
                    .build();
        }

        List<DateCount> dateCounts = store.getStudiesByDate(fromDate, toDate);

        Map<String, Object> result = new HashMap<>();
        result.put("dateCounts", dateCounts);
        result.put("totalDates", dateCounts.size());
        result.put("totalStudies", dateCounts.stream().mapToInt(dc -> dc.count).sum());

        return Response.ok(result).build();
    }

    // ========================================================================
    // Custom Field Endpoints
    // ========================================================================

    /**
     * Get all custom fields.
     */
    @GET
    @Path("/fields")
    public Response getCustomFields() {
        List<CustomField> fields = store.getAllCustomFields();
        return Response.ok(Map.of("fields", fields)).build();
    }

    /**
     * Get available DICOM tags that can be indexed.
     */
    @GET
    @Path("/fields/available-tags")
    public Response getAvailableTags() {
        List<DicomIndexer.TagInfo> tags = DicomIndexer.getAvailableTags();
        return Response.ok(Map.of("tags", tags)).build();
    }

    /**
     * Add a custom field.
     */
    @POST
    @Path("/fields")
    public Response addCustomField(CustomField field) {
        if (field.fieldName == null || field.fieldName.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Field name is required"))
                    .build();
        }
        if (field.dicomTag == null || field.dicomTag.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "DICOM tag is required"))
                    .build();
        }

        // Validate tag
        int tag = DicomIndexer.parseTag(field.dicomTag);
        if (tag == -1) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid DICOM tag: " + field.dicomTag))
                    .build();
        }

        // Sanitize field name
        field.fieldName = field.fieldName.toLowerCase().replaceAll("[^a-z0-9_]", "_");

        if (field.displayName == null || field.displayName.isEmpty()) {
            field.displayName = field.fieldName;
        }

        CustomField created = store.addCustomField(field);
        if (created == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to create custom field"))
                    .build();
        }

        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    /**
     * Update a custom field.
     */
    @PUT
    @Path("/fields/{id}")
    public Response updateCustomField(@PathParam("id") long id, CustomField field) {
        CustomField existing = store.getCustomField(id);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Field not found: " + id))
                    .build();
        }

        field.id = id;
        field.fieldName = existing.fieldName;  // Can't change field name

        if (field.dicomTag != null && !field.dicomTag.isEmpty()) {
            int tag = DicomIndexer.parseTag(field.dicomTag);
            if (tag == -1) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid DICOM tag: " + field.dicomTag))
                        .build();
            }
        }

        boolean success = store.updateCustomField(field);
        if (!success) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to update custom field"))
                    .build();
        }

        return Response.ok(store.getCustomField(id)).build();
    }

    /**
     * Delete a custom field.
     */
    @DELETE
    @Path("/fields/{id}")
    public Response deleteCustomField(@PathParam("id") long id) {
        boolean success = store.deleteCustomField(id);
        if (!success) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Field not found: " + id))
                    .build();
        }
        return Response.noContent().build();
    }

    // ========================================================================
    // Reindex Endpoints
    // ========================================================================

    /**
     * Start a full reindex from base_dir.
     */
    @POST
    @Path("/reindex")
    public Response startReindex() {
        String baseDir = config.getReceiver().getBaseDir();
        ReindexJob job = indexer.startReindex(baseDir);

        if (job == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to start reindex"))
                    .build();
        }

        return Response.accepted(job).build();
    }

    /**
     * Start indexing from a specific destination.
     *
     * For DICOM AE destinations, supports date range filtering and chunking:
     * - studyDateFrom/studyDateTo: YYYYMMDD format date range filter
     * - chunkSize: HOURLY, DAILY, WEEKLY, MONTHLY, YEARLY, NONE (default: NONE)
     */
    @POST
    @Path("/reindex/destination/{destinationName}")
    public Response startReindexFromDestination(
            @PathParam("destinationName") String destinationName,
            @QueryParam("clearExisting") @DefaultValue("false") boolean clearExisting,
            @QueryParam("studyDateFrom") String studyDateFrom,
            @QueryParam("studyDateTo") String studyDateTo,
            @QueryParam("chunkSize") @DefaultValue("NONE") String chunkSizeStr) {

        // Look up the destination
        AppConfig.Destination dest = config.getDestinations().get(destinationName);
        if (dest == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Destination not found: " + destinationName))
                    .build();
        }

        ReindexJob job;

        if (dest instanceof AppConfig.FileDestination) {
            // File-based destination - scan the path (date filtering not applicable)
            AppConfig.FileDestination fileDest = (AppConfig.FileDestination) dest;
            String path = fileDest.getPath();
            if (path == null || path.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "File destination has no path configured"))
                        .build();
            }
            job = indexer.startIndexFromFileDestination(destinationName, path, clearExisting);

        } else if (dest instanceof AppConfig.DicomAeDestination) {
            // DICOM AE destination - use C-FIND with date range and chunking
            AppConfig.DicomAeDestination dicomDest = (AppConfig.DicomAeDestination) dest;
            String callingAeTitle = config.getRoutes().isEmpty() ? "INDEXER" :
                    config.getRoutes().get(0).getAeTitle();

            // Parse chunk size
            DicomIndexer.ChunkSize chunkSize;
            try {
                chunkSize = DicomIndexer.ChunkSize.valueOf(chunkSizeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid chunkSize: " + chunkSizeStr +
                                ". Valid values: HOURLY, DAILY, WEEKLY, MONTHLY, YEARLY, NONE"))
                        .build();
            }

            // Validate date format if provided
            if (studyDateFrom != null && !isValidDicomDate(studyDateFrom)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid studyDateFrom format. Use YYYYMMDD"))
                        .build();
            }
            if (studyDateTo != null && !isValidDicomDate(studyDateTo)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid studyDateTo format. Use YYYYMMDD"))
                        .build();
            }

            job = indexer.startIndexFromDicomDestination(
                    destinationName,
                    dicomDest.getHost(),
                    dicomDest.getPort(),
                    dicomDest.getAeTitle(),
                    callingAeTitle,
                    clearExisting,
                    studyDateFrom,
                    studyDateTo,
                    chunkSize);

        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Destination type not supported for indexing: " + dest.getType()))
                    .build();
        }

        if (job == null) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to start reindex"))
                    .build();
        }

        return Response.accepted(job).build();
    }

    /**
     * Validate DICOM date format (YYYYMMDD).
     */
    private boolean isValidDicomDate(String date) {
        if (date == null || date.length() != 8) {
            return false;
        }
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            int month = Integer.parseInt(date.substring(4, 6));
            int day = Integer.parseInt(date.substring(6, 8));
            return year >= 1900 && year <= 2100 && month >= 1 && month <= 12 && day >= 1 && day <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Get list of destinations that can be indexed.
     */
    @GET
    @Path("/destinations")
    public Response getIndexableDestinations() {
        List<Map<String, Object>> destinations = new ArrayList<>();

        for (Map.Entry<String, AppConfig.Destination> entry : config.getDestinations().entrySet()) {
            AppConfig.Destination dest = entry.getValue();
            Map<String, Object> destInfo = new HashMap<>();
            destInfo.put("name", entry.getKey());
            destInfo.put("type", dest.getType());
            destInfo.put("description", dest.getDescription());
            destInfo.put("enabled", dest.isEnabled());

            if (dest instanceof AppConfig.FileDestination) {
                destInfo.put("indexable", true);
                destInfo.put("path", ((AppConfig.FileDestination) dest).getPath());
            } else if (dest instanceof AppConfig.DicomAeDestination) {
                AppConfig.DicomAeDestination dicomDest = (AppConfig.DicomAeDestination) dest;
                destInfo.put("indexable", true);
                destInfo.put("host", dicomDest.getHost());
                destInfo.put("port", dicomDest.getPort());
                destInfo.put("aeTitle", dicomDest.getAeTitle());
            } else {
                destInfo.put("indexable", false);
            }

            destinations.add(destInfo);
        }

        return Response.ok(Map.of("destinations", destinations)).build();
    }

    /**
     * Get reindex job status.
     */
    @GET
    @Path("/reindex/status")
    public Response getReindexStatus() {
        ReindexJob job = indexer.getCurrentJob();
        if (job == null) {
            return Response.ok(Map.of("status", "idle", "message", "No reindex job running")).build();
        }
        return Response.ok(job).build();
    }

    /**
     * Cancel the current reindex job.
     */
    @POST
    @Path("/reindex/cancel")
    public Response cancelReindex() {
        boolean cancelled = indexer.cancelJob();
        if (cancelled) {
            return Response.ok(Map.of("message", "Cancel requested", "success", true)).build();
        } else {
            return Response.ok(Map.of("message", "No running job to cancel", "success", false)).build();
        }
    }

    /**
     * Get reindex job by ID.
     */
    @GET
    @Path("/reindex/{jobId}")
    public Response getReindexJob(@PathParam("jobId") long jobId) {
        ReindexJob job = store.getReindexJob(jobId);
        if (job == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Job not found: " + jobId))
                    .build();
        }
        return Response.ok(job).build();
    }

    /**
     * Clear the index (requires confirmation).
     */
    @DELETE
    @Path("/index")
    public Response clearIndex(@QueryParam("confirm") @DefaultValue("false") boolean confirm) {
        if (!confirm) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Please confirm with ?confirm=true"))
                    .build();
        }

        store.clearIndex();
        return Response.ok(Map.of("message", "Index cleared")).build();
    }
}
