/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.dicom.DicomClient;
import io.xnatworks.router.routing.DestinationManager;
import io.xnatworks.router.tracking.TransferTracker;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST API for DICOM Query/Retrieve operations.
 * Allows querying remote PACS servers and retrieving studies to forward to configured routes.
 */
@Path("/query-retrieve")
@Produces(MediaType.APPLICATION_JSON)
public class QueryRetrieveResource {
    private static final Logger log = LoggerFactory.getLogger(QueryRetrieveResource.class);

    private final AppConfig config;
    private final DestinationManager destinationManager;
    private final TransferTracker transferTracker;

    // Track active retrieve jobs
    private final Map<String, RetrieveJob> activeJobs = new ConcurrentHashMap<>();
    private final AtomicLong jobIdCounter = new AtomicLong(0);
    private final ExecutorService retrieveExecutor = Executors.newFixedThreadPool(4);

    public QueryRetrieveResource(AppConfig config, DestinationManager destinationManager,
                                  TransferTracker transferTracker) {
        this.config = config;
        this.destinationManager = destinationManager;
        this.transferTracker = transferTracker;
    }

    /**
     * List all configured DICOM sources (PACS servers that can be queried).
     */
    @GET
    @Path("/sources")
    public Response listSources() {
        List<Map<String, Object>> sources = new ArrayList<>();

        // Get all DICOM AE destinations that can be used as query sources
        for (Map.Entry<String, AppConfig.DicomAeDestination> entry : config.getDicomAeDestinations().entrySet()) {
            AppConfig.DicomAeDestination dest = entry.getValue();
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("name", entry.getKey());
            source.put("aeTitle", dest.getAeTitle());
            source.put("host", dest.getHost());
            source.put("port", dest.getPort());
            source.put("enabled", dest.isEnabled());
            source.put("description", dest.getDescription());

            // Test connectivity
            try (DicomClient client = new DicomClient(entry.getKey(), dest)) {
                source.put("available", client.echo());
            } catch (Exception e) {
                source.put("available", false);
            }

            sources.add(source);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sources", sources);
        result.put("count", sources.size());

        return Response.ok(result).build();
    }

    /**
     * Query a DICOM source for studies.
     * Supports searching by:
     * - PatientID
     * - PatientName
     * - AccessionNumber
     * - StudyInstanceUID (single or bulk)
     * - StudyDate (single date or range)
     * - Modality
     */
    @POST
    @Path("/query")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response queryStudies(Map<String, Object> queryParams) {
        String sourceName = (String) queryParams.get("source");
        if (sourceName == null || sourceName.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Source name is required"))
                    .build();
        }

        AppConfig.DicomAeDestination source = config.getDicomAeDestinations().get(sourceName);
        if (source == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Source not found: " + sourceName))
                    .build();
        }

        if (!source.isEnabled()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Source is disabled: " + sourceName))
                    .build();
        }

        try {
            Attributes queryKeys = buildQueryKeys(queryParams);

            try (DicomClient client = new DicomClient(sourceName, source)) {
                List<Attributes> results = client.findStudies(queryKeys);

                // Convert results to JSON-friendly format
                List<Map<String, Object>> studies = new ArrayList<>();
                for (Attributes attrs : results) {
                    Map<String, Object> study = new LinkedHashMap<>();
                    study.put("studyInstanceUID", attrs.getString(Tag.StudyInstanceUID, ""));
                    study.put("patientID", attrs.getString(Tag.PatientID, ""));
                    study.put("patientName", formatPatientName(attrs.getString(Tag.PatientName, "")));
                    study.put("accessionNumber", attrs.getString(Tag.AccessionNumber, ""));
                    study.put("studyDate", attrs.getString(Tag.StudyDate, ""));
                    study.put("studyTime", attrs.getString(Tag.StudyTime, ""));
                    study.put("studyDescription", attrs.getString(Tag.StudyDescription, ""));
                    study.put("modality", attrs.getString(Tag.ModalitiesInStudy, attrs.getString(Tag.Modality, "")));
                    study.put("numberOfSeries", attrs.getInt(Tag.NumberOfStudyRelatedSeries, 0));
                    study.put("numberOfInstances", attrs.getInt(Tag.NumberOfStudyRelatedInstances, 0));
                    study.put("referringPhysician", attrs.getString(Tag.ReferringPhysicianName, ""));
                    studies.add(study);
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("source", sourceName);
                response.put("studies", studies);
                response.put("count", studies.size());
                response.put("queryTime", System.currentTimeMillis());

                log.info("Query to '{}' returned {} studies", sourceName, studies.size());
                return Response.ok(response).build();
            }

        } catch (Exception e) {
            log.error("Query failed for source '{}': {}", sourceName, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Query failed: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Query series for a specific study.
     */
    @GET
    @Path("/study/{studyUID}/series")
    public Response querySeries(
            @PathParam("studyUID") String studyUID,
            @QueryParam("source") String sourceName) {

        if (sourceName == null || sourceName.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Source name is required"))
                    .build();
        }

        AppConfig.DicomAeDestination source = config.getDicomAeDestinations().get(sourceName);
        if (source == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Source not found: " + sourceName))
                    .build();
        }

        try (DicomClient client = new DicomClient(sourceName, source)) {
            Attributes queryKeys = new Attributes();
            queryKeys.setString(Tag.QueryRetrieveLevel, VR.CS, "SERIES");
            queryKeys.setString(Tag.StudyInstanceUID, VR.UI, studyUID);

            // Return keys for series
            queryKeys.setNull(Tag.SeriesInstanceUID, VR.UI);
            queryKeys.setNull(Tag.SeriesNumber, VR.IS);
            queryKeys.setNull(Tag.SeriesDescription, VR.LO);
            queryKeys.setNull(Tag.Modality, VR.CS);
            queryKeys.setNull(Tag.NumberOfSeriesRelatedInstances, VR.IS);
            queryKeys.setNull(Tag.SeriesDate, VR.DA);
            queryKeys.setNull(Tag.SeriesTime, VR.TM);
            queryKeys.setNull(Tag.BodyPartExamined, VR.CS);

            List<Attributes> results = client.findSeries(queryKeys);

            List<Map<String, Object>> seriesList = new ArrayList<>();
            for (Attributes attrs : results) {
                Map<String, Object> series = new LinkedHashMap<>();
                series.put("seriesInstanceUID", attrs.getString(Tag.SeriesInstanceUID, ""));
                series.put("seriesNumber", attrs.getInt(Tag.SeriesNumber, 0));
                series.put("seriesDescription", attrs.getString(Tag.SeriesDescription, ""));
                series.put("modality", attrs.getString(Tag.Modality, ""));
                series.put("numberOfInstances", attrs.getInt(Tag.NumberOfSeriesRelatedInstances, 0));
                series.put("seriesDate", attrs.getString(Tag.SeriesDate, ""));
                series.put("seriesTime", attrs.getString(Tag.SeriesTime, ""));
                series.put("bodyPartExamined", attrs.getString(Tag.BodyPartExamined, ""));
                seriesList.add(series);
            }

            // Sort by series number
            seriesList.sort((a, b) -> {
                int numA = (int) a.getOrDefault("seriesNumber", 0);
                int numB = (int) b.getOrDefault("seriesNumber", 0);
                return Integer.compare(numA, numB);
            });

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("studyInstanceUID", studyUID);
            response.put("source", sourceName);
            response.put("series", seriesList);
            response.put("count", seriesList.size());

            log.info("Series query for study {} from '{}': found {} series", studyUID, sourceName, seriesList.size());
            return Response.ok(response).build();

        } catch (Exception e) {
            log.error("Series query failed for study {} from '{}': {}", studyUID, sourceName, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Series query failed: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Query images for a specific series.
     */
    @GET
    @Path("/series/{seriesUID}/images")
    public Response queryImages(
            @PathParam("seriesUID") String seriesUID,
            @QueryParam("source") String sourceName,
            @QueryParam("studyUID") String studyUID) {

        if (sourceName == null || sourceName.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Source name is required"))
                    .build();
        }

        AppConfig.DicomAeDestination source = config.getDicomAeDestinations().get(sourceName);
        if (source == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Source not found: " + sourceName))
                    .build();
        }

        try (DicomClient client = new DicomClient(sourceName, source)) {
            Attributes queryKeys = new Attributes();
            queryKeys.setString(Tag.QueryRetrieveLevel, VR.CS, "IMAGE");
            if (studyUID != null && !studyUID.isEmpty()) {
                queryKeys.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
            }
            queryKeys.setString(Tag.SeriesInstanceUID, VR.UI, seriesUID);

            // Return keys for images
            queryKeys.setNull(Tag.SOPInstanceUID, VR.UI);
            queryKeys.setNull(Tag.SOPClassUID, VR.UI);
            queryKeys.setNull(Tag.InstanceNumber, VR.IS);
            queryKeys.setNull(Tag.Rows, VR.US);
            queryKeys.setNull(Tag.Columns, VR.US);
            queryKeys.setNull(Tag.ImageType, VR.CS);
            queryKeys.setNull(Tag.SliceLocation, VR.DS);
            queryKeys.setNull(Tag.SliceThickness, VR.DS);

            List<Attributes> results = client.find(UID.StudyRootQueryRetrieveInformationModelFind, queryKeys);

            List<Map<String, Object>> imageList = new ArrayList<>();
            for (Attributes attrs : results) {
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("sopInstanceUID", attrs.getString(Tag.SOPInstanceUID, ""));
                image.put("sopClassUID", attrs.getString(Tag.SOPClassUID, ""));
                image.put("instanceNumber", attrs.getInt(Tag.InstanceNumber, 0));
                image.put("rows", attrs.getInt(Tag.Rows, 0));
                image.put("columns", attrs.getInt(Tag.Columns, 0));
                image.put("imageType", attrs.getString(Tag.ImageType, ""));
                image.put("sliceLocation", attrs.getDouble(Tag.SliceLocation, 0.0));
                image.put("sliceThickness", attrs.getDouble(Tag.SliceThickness, 0.0));
                imageList.add(image);
            }

            // Sort by instance number
            imageList.sort((a, b) -> {
                int numA = (int) a.getOrDefault("instanceNumber", 0);
                int numB = (int) b.getOrDefault("instanceNumber", 0);
                return Integer.compare(numA, numB);
            });

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("seriesInstanceUID", seriesUID);
            response.put("source", sourceName);
            response.put("images", imageList);
            response.put("count", imageList.size());

            log.info("Image query for series {} from '{}': found {} images", seriesUID, sourceName, imageList.size());
            return Response.ok(response).build();

        } catch (Exception e) {
            log.error("Image query failed for series {} from '{}': {}", seriesUID, sourceName, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Image query failed: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get image preview from a PACS source.
     * Proxies to the PACS REST API (e.g., Orthanc) to get a preview image.
     */
    @GET
    @Path("/image/preview")
    @Produces("image/png")
    public Response getImagePreview(
            @QueryParam("source") String sourceName,
            @QueryParam("sopInstanceUID") String sopInstanceUID) {

        if (sourceName == null || sourceName.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Source name is required".getBytes(StandardCharsets.UTF_8))
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        if (sopInstanceUID == null || sopInstanceUID.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("SOP Instance UID is required".getBytes(StandardCharsets.UTF_8))
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        AppConfig.DicomAeDestination source = config.getDicomAeDestinations().get(sourceName);
        if (source == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Source not found".getBytes(StandardCharsets.UTF_8))
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        String restApiUrl = source.getRestApiUrl();
        if (restApiUrl == null || restApiUrl.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("REST API URL not configured for source".getBytes(StandardCharsets.UTF_8))
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }

        try {
            // Step 1: Look up the SOP Instance UID to get Orthanc's internal ID
            String orthancId = lookupOrthancId(restApiUrl, sopInstanceUID,
                    source.getRestApiUsername(), source.getRestApiPassword());

            if (orthancId == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Instance not found in PACS".getBytes(StandardCharsets.UTF_8))
                        .type(MediaType.TEXT_PLAIN)
                        .build();
            }

            // Step 2: Fetch the preview image
            byte[] imageData = fetchPreviewImage(restApiUrl, orthancId,
                    source.getRestApiUsername(), source.getRestApiPassword());

            return Response.ok(imageData).type("image/png").build();

        } catch (Exception e) {
            log.error("Failed to fetch image preview for {} from '{}': {}",
                    sopInstanceUID, sourceName, e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(("Failed to fetch preview: " + e.getMessage()).getBytes(StandardCharsets.UTF_8))
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
    }

    /**
     * Look up SOP Instance UID in Orthanc to get internal ID.
     */
    private String lookupOrthancId(String baseUrl, String sopInstanceUID, String username, String password)
            throws Exception {
        String lookupUrl = baseUrl + "/tools/lookup";

        HttpURLConnection conn = (HttpURLConnection) new URL(lookupUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "text/plain");
        conn.setDoOutput(true);

        if (username != null && !username.isEmpty() && password != null) {
            String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + auth);
        }

        conn.getOutputStream().write(sopInstanceUID.getBytes(StandardCharsets.UTF_8));

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            log.debug("Orthanc lookup returned status {}", responseCode);
            return null;
        }

        try (InputStream is = conn.getInputStream()) {
            String response = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Response is a JSON array: [{"Type":"Instance","ID":"xxx","Path":"/instances/xxx"}]
            if (response.contains("\"Type\":\"Instance\"") && response.contains("\"ID\":\"")) {
                int idStart = response.indexOf("\"ID\":\"") + 6;
                int idEnd = response.indexOf("\"", idStart);
                return response.substring(idStart, idEnd);
            }
        }
        return null;
    }

    /**
     * Fetch preview image from Orthanc.
     */
    private byte[] fetchPreviewImage(String baseUrl, String orthancId, String username, String password)
            throws Exception {
        String previewUrl = baseUrl + "/instances/" + orthancId + "/preview";

        HttpURLConnection conn = (HttpURLConnection) new URL(previewUrl).openConnection();
        conn.setRequestMethod("GET");

        if (username != null && !username.isEmpty() && password != null) {
            String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + auth);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("Failed to fetch preview: HTTP " + responseCode);
        }

        try (InputStream is = conn.getInputStream()) {
            return is.readAllBytes();
        }
    }

    /**
     * Bulk query - query multiple Study UIDs or Accession Numbers at once.
     */
    @POST
    @Path("/query/bulk")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response bulkQuery(Map<String, Object> queryParams) {
        String sourceName = (String) queryParams.get("source");
        if (sourceName == null || sourceName.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Source name is required"))
                    .build();
        }

        AppConfig.DicomAeDestination source = config.getDicomAeDestinations().get(sourceName);
        if (source == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Source not found: " + sourceName))
                    .build();
        }

        // Get the list of identifiers
        @SuppressWarnings("unchecked")
        List<String> identifiers = (List<String>) queryParams.get("identifiers");
        String identifierType = (String) queryParams.getOrDefault("identifierType", "studyInstanceUID");

        if (identifiers == null || identifiers.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Identifiers list is required"))
                    .build();
        }

        List<Map<String, Object>> allStudies = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (DicomClient client = new DicomClient(sourceName, source)) {
            for (String identifier : identifiers) {
                try {
                    Attributes queryKeys = new Attributes();
                    // Include return keys
                    addReturnKeys(queryKeys);

                    switch (identifierType.toLowerCase()) {
                        case "studyinstanceuid":
                            queryKeys.setString(Tag.StudyInstanceUID, VR.UI, identifier.trim());
                            break;
                        case "accessionnumber":
                            queryKeys.setString(Tag.AccessionNumber, VR.SH, identifier.trim());
                            break;
                        case "patientid":
                            queryKeys.setString(Tag.PatientID, VR.LO, identifier.trim());
                            break;
                        default:
                            errors.add("Invalid identifier type: " + identifierType);
                            continue;
                    }

                    List<Attributes> results = client.findStudies(queryKeys);
                    if (results.isEmpty()) {
                        notFound.add(identifier);
                    } else {
                        for (Attributes attrs : results) {
                            Map<String, Object> study = new LinkedHashMap<>();
                            study.put("studyInstanceUID", attrs.getString(Tag.StudyInstanceUID, ""));
                            study.put("patientID", attrs.getString(Tag.PatientID, ""));
                            study.put("patientName", formatPatientName(attrs.getString(Tag.PatientName, "")));
                            study.put("accessionNumber", attrs.getString(Tag.AccessionNumber, ""));
                            study.put("studyDate", attrs.getString(Tag.StudyDate, ""));
                            study.put("studyTime", attrs.getString(Tag.StudyTime, ""));
                            study.put("studyDescription", attrs.getString(Tag.StudyDescription, ""));
                            study.put("modality", attrs.getString(Tag.ModalitiesInStudy, attrs.getString(Tag.Modality, "")));
                            study.put("numberOfSeries", attrs.getInt(Tag.NumberOfStudyRelatedSeries, 0));
                            study.put("numberOfInstances", attrs.getInt(Tag.NumberOfStudyRelatedInstances, 0));
                            study.put("queryIdentifier", identifier);
                            allStudies.add(study);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Query failed for identifier '{}': {}", identifier, e.getMessage());
                    errors.add(identifier + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Bulk query failed: {}", e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Bulk query failed: " + e.getMessage()))
                    .build();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", sourceName);
        response.put("studies", allStudies);
        response.put("found", allStudies.size());
        response.put("notFound", notFound);
        response.put("errors", errors);
        response.put("totalQueried", identifiers.size());

        return Response.ok(response).build();
    }

    /**
     * Retrieve studies from a PACS and forward to a route.
     * Uses C-MOVE to retrieve studies to one of our configured routes.
     */
    @POST
    @Path("/retrieve")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response retrieveStudies(Map<String, Object> retrieveParams) {
        String sourceName = (String) retrieveParams.get("source");
        String targetRoute = (String) retrieveParams.get("targetRoute");

        @SuppressWarnings("unchecked")
        List<String> studyUIDs = (List<String>) retrieveParams.get("studyUIDs");

        if (sourceName == null || sourceName.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Source name is required"))
                    .build();
        }

        if (targetRoute == null || targetRoute.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Target route is required"))
                    .build();
        }

        if (studyUIDs == null || studyUIDs.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "At least one Study UID is required"))
                    .build();
        }

        // Verify source exists
        AppConfig.DicomAeDestination source = config.getDicomAeDestinations().get(sourceName);
        if (source == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Source not found: " + sourceName))
                    .build();
        }

        // Verify target route exists
        AppConfig.RouteConfig route = config.findRouteByAeTitle(targetRoute);
        if (route == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Target route not found: " + targetRoute))
                    .build();
        }

        // Create a retrieve job
        String jobId = String.format("QR-%d-%d", System.currentTimeMillis(), jobIdCounter.incrementAndGet());
        RetrieveJob job = new RetrieveJob(jobId, sourceName, targetRoute, studyUIDs);
        activeJobs.put(jobId, job);

        // Start the retrieve asynchronously
        retrieveExecutor.submit(() -> executeRetrieve(job, source, route));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobId);
        response.put("status", "STARTED");
        response.put("source", sourceName);
        response.put("targetRoute", targetRoute);
        response.put("studyCount", studyUIDs.size());
        response.put("message", "Retrieve job started");

        log.info("Started retrieve job {} from '{}' to route '{}' for {} studies",
                jobId, sourceName, targetRoute, studyUIDs.size());

        return Response.accepted(response).build();
    }

    /**
     * Get status of a retrieve job.
     */
    @GET
    @Path("/retrieve/{jobId}")
    public Response getRetrieveStatus(@PathParam("jobId") String jobId) {
        RetrieveJob job = activeJobs.get(jobId);
        if (job == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Job not found: " + jobId))
                    .build();
        }

        return Response.ok(job.toMap()).build();
    }

    /**
     * List all active retrieve jobs.
     */
    @GET
    @Path("/retrieve/jobs")
    public Response listRetrieveJobs() {
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (RetrieveJob job : activeJobs.values()) {
            jobs.add(job.toMap());
        }

        // Sort by start time descending
        jobs.sort((a, b) -> Long.compare(
                (Long) b.getOrDefault("startTime", 0L),
                (Long) a.getOrDefault("startTime", 0L)));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobs", jobs);
        response.put("count", jobs.size());

        return Response.ok(response).build();
    }

    /**
     * Cancel a retrieve job.
     */
    @DELETE
    @Path("/retrieve/{jobId}")
    public Response cancelRetrieveJob(@PathParam("jobId") String jobId) {
        RetrieveJob job = activeJobs.get(jobId);
        if (job == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Job not found: " + jobId))
                    .build();
        }

        job.cancel();
        log.info("Cancelled retrieve job: {}", jobId);

        return Response.ok(Map.of("status", "CANCELLED", "jobId", jobId)).build();
    }

    /**
     * Get available routes that can receive retrieved studies.
     */
    @GET
    @Path("/routes")
    public Response getAvailableRoutes() {
        List<Map<String, Object>> routes = new ArrayList<>();

        for (AppConfig.RouteConfig route : config.getRoutes()) {
            if (route.isEnabled()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("aeTitle", route.getAeTitle());
                r.put("port", route.getPort());
                r.put("description", route.getDescription());
                r.put("destinationCount", route.getDestinations().size());
                routes.add(r);
            }
        }

        return Response.ok(Map.of("routes", routes, "count", routes.size())).build();
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private Attributes buildQueryKeys(Map<String, Object> params) {
        Attributes keys = new Attributes();

        // Add return keys (what we want back)
        addReturnKeys(keys);

        // PatientID
        String patientId = (String) params.get("patientID");
        if (patientId != null && !patientId.isEmpty()) {
            keys.setString(Tag.PatientID, VR.LO, patientId);
        }

        // PatientName (with wildcard support)
        String patientName = (String) params.get("patientName");
        if (patientName != null && !patientName.isEmpty()) {
            keys.setString(Tag.PatientName, VR.PN, patientName);
        }

        // AccessionNumber
        String accessionNumber = (String) params.get("accessionNumber");
        if (accessionNumber != null && !accessionNumber.isEmpty()) {
            keys.setString(Tag.AccessionNumber, VR.SH, accessionNumber);
        }

        // StudyInstanceUID
        String studyUID = (String) params.get("studyInstanceUID");
        if (studyUID != null && !studyUID.isEmpty()) {
            keys.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
        }

        // Modality
        String modality = (String) params.get("modality");
        if (modality != null && !modality.isEmpty()) {
            keys.setString(Tag.ModalitiesInStudy, VR.CS, modality);
        }

        // Study Date / Date Range
        String studyDate = (String) params.get("studyDate");
        String studyDateFrom = (String) params.get("studyDateFrom");
        String studyDateTo = (String) params.get("studyDateTo");

        if (studyDate != null && !studyDate.isEmpty()) {
            // Single date
            keys.setString(Tag.StudyDate, VR.DA, formatDate(studyDate));
        } else if (studyDateFrom != null || studyDateTo != null) {
            // Date range
            String from = studyDateFrom != null ? formatDate(studyDateFrom) : "";
            String to = studyDateTo != null ? formatDate(studyDateTo) : "";
            keys.setString(Tag.StudyDate, VR.DA, from + "-" + to);
        }

        return keys;
    }

    private void addReturnKeys(Attributes keys) {
        // Request these fields to be returned
        keys.setNull(Tag.StudyInstanceUID, VR.UI);
        keys.setNull(Tag.PatientID, VR.LO);
        keys.setNull(Tag.PatientName, VR.PN);
        keys.setNull(Tag.AccessionNumber, VR.SH);
        keys.setNull(Tag.StudyDate, VR.DA);
        keys.setNull(Tag.StudyTime, VR.TM);
        keys.setNull(Tag.StudyDescription, VR.LO);
        keys.setNull(Tag.ModalitiesInStudy, VR.CS);
        keys.setNull(Tag.NumberOfStudyRelatedSeries, VR.IS);
        keys.setNull(Tag.NumberOfStudyRelatedInstances, VR.IS);
        keys.setNull(Tag.ReferringPhysicianName, VR.PN);

        // Set query retrieve level
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
    }

    private String formatDate(String dateStr) {
        // Accept various formats and convert to DICOM format (YYYYMMDD)
        if (dateStr == null || dateStr.isEmpty()) {
            return "";
        }

        // Already in DICOM format
        if (dateStr.matches("\\d{8}")) {
            return dateStr;
        }

        // ISO format (YYYY-MM-DD)
        if (dateStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return dateStr.replace("-", "");
        }

        // Try parsing various formats
        try {
            LocalDate date = LocalDate.parse(dateStr);
            return date.format(DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            log.warn("Could not parse date: {}", dateStr);
            return dateStr;
        }
    }

    private String formatPatientName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        // Convert DICOM format (Last^First^Middle) to readable format
        return name.replace("^", " ").trim();
    }

    private void executeRetrieve(RetrieveJob job, AppConfig.DicomAeDestination source,
                                  AppConfig.RouteConfig route) {
        job.setStatus("RUNNING");
        String moveDestination = route.getAeTitle();

        try (DicomClient client = new DicomClient(job.getSourceName(), source)) {
            for (String studyUID : job.getStudyUIDs()) {
                if (job.isCancelled()) {
                    job.setStatus("CANCELLED");
                    break;
                }

                try {
                    Attributes moveKeys = new Attributes();
                    moveKeys.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
                    moveKeys.setString(Tag.StudyInstanceUID, VR.UI, studyUID);

                    log.info("Retrieving study {} from '{}' to '{}'",
                            studyUID, job.getSourceName(), moveDestination);

                    DicomClient.MoveResult result = client.move(
                            UID.StudyRootQueryRetrieveInformationModelMove,
                            moveKeys,
                            moveDestination
                    );

                    if (result.isSuccess()) {
                        job.incrementCompleted();
                        job.addMessage("Study " + studyUID + ": " + result.getCompleted() + " instances retrieved");
                    } else {
                        job.incrementFailed();
                        job.addError("Study " + studyUID + " failed with status 0x" +
                                Integer.toHexString(result.getStatus()));
                    }

                } catch (Exception e) {
                    log.error("Failed to retrieve study {}: {}", studyUID, e.getMessage());
                    job.incrementFailed();
                    job.addError("Study " + studyUID + ": " + e.getMessage());
                }
            }

            if (!job.isCancelled()) {
                job.setStatus(job.getFailedCount() == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS");
            }

        } catch (Exception e) {
            log.error("Retrieve job {} failed: {}", job.getJobId(), e.getMessage(), e);
            job.setStatus("FAILED");
            job.addError("Job failed: " + e.getMessage());
        }

        job.setEndTime(System.currentTimeMillis());
        log.info("Retrieve job {} completed: {} succeeded, {} failed",
                job.getJobId(), job.getCompletedCount(), job.getFailedCount());
    }

    // ========================================================================
    // Retrieve Job tracking class
    // ========================================================================

    public static class RetrieveJob {
        private final String jobId;
        private final String sourceName;
        private final String targetRoute;
        private final List<String> studyUIDs;
        private final long startTime;
        private long endTime;
        private volatile String status = "PENDING";
        private volatile boolean cancelled = false;
        private volatile int completedCount = 0;
        private volatile int failedCount = 0;
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        public RetrieveJob(String jobId, String sourceName, String targetRoute, List<String> studyUIDs) {
            this.jobId = jobId;
            this.sourceName = sourceName;
            this.targetRoute = targetRoute;
            this.studyUIDs = new ArrayList<>(studyUIDs);
            this.startTime = System.currentTimeMillis();
        }

        public String getJobId() { return jobId; }
        public String getSourceName() { return sourceName; }
        public String getTargetRoute() { return targetRoute; }
        public List<String> getStudyUIDs() { return studyUIDs; }
        public long getStartTime() { return startTime; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public boolean isCancelled() { return cancelled; }
        public void cancel() { this.cancelled = true; }

        public int getCompletedCount() { return completedCount; }
        public synchronized void incrementCompleted() { this.completedCount++; }

        public int getFailedCount() { return failedCount; }
        public synchronized void incrementFailed() { this.failedCount++; }

        public void setEndTime(long endTime) { this.endTime = endTime; }

        public void addMessage(String message) { messages.add(message); }
        public void addError(String error) { errors.add(error); }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("jobId", jobId);
            map.put("source", sourceName);
            map.put("targetRoute", targetRoute);
            map.put("status", status);
            map.put("totalStudies", studyUIDs.size());
            map.put("completedCount", completedCount);
            map.put("failedCount", failedCount);
            map.put("progress", studyUIDs.isEmpty() ? 0 :
                    (int) ((completedCount + failedCount) * 100.0 / studyUIDs.size()));
            map.put("startTime", startTime);
            if (endTime > 0) {
                map.put("endTime", endTime);
                map.put("durationMs", endTime - startTime);
            }
            map.put("messages", messages);
            map.put("errors", errors);
            return map;
        }
    }
}
