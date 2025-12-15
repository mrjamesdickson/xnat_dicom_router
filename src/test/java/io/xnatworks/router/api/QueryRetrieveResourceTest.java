/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.routing.DestinationManager;
import io.xnatworks.router.tracking.TransferTracker;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QueryRetrieveResource.
 */
@DisplayName("QueryRetrieveResource Tests")
class QueryRetrieveResourceTest {

    @TempDir
    Path tempDir;

    private AppConfig config;
    private DestinationManager destinationManager;
    private TransferTracker transferTracker;
    private QueryRetrieveResource queryRetrieveResource;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        config.getResilience().setHealthCheckInterval(60);

        // Add a test DICOM destination (source)
        AppConfig.DicomAeDestination dicomDest = new AppConfig.DicomAeDestination();
        dicomDest.setAeTitle("TEST_PACS");
        dicomDest.setHost("localhost");
        dicomDest.setPort(104);
        dicomDest.setEnabled(true);
        dicomDest.setDescription("Test PACS Server");
        config.getDestinations().put("test-pacs", dicomDest);

        // Add a test route
        AppConfig.RouteConfig route = new AppConfig.RouteConfig();
        route.setAeTitle("TEST_ROUTE");
        route.setPort(11112);
        route.setEnabled(true);
        route.setDescription("Test Route");
        AppConfig.RouteDestination routeDest = new AppConfig.RouteDestination();
        routeDest.setDestination("test-pacs");
        route.getDestinations().add(routeDest);
        config.getRoutes().add(route);

        destinationManager = new DestinationManager(config);
        transferTracker = new TransferTracker(tempDir);

        queryRetrieveResource = new QueryRetrieveResource(config, destinationManager, transferTracker);
    }

    @AfterEach
    void tearDown() {
        if (destinationManager != null) {
            destinationManager.close();
        }
    }

    @Nested
    @DisplayName("GET /query-retrieve/sources Tests")
    class ListSourcesTests {

        @Test
        @DisplayName("Should list DICOM sources")
        void shouldListDicomSources() {
            Response response = queryRetrieveResource.listSources();

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");

            assertEquals(1, sources.size());
            assertEquals(1, result.get("count"));

            Map<String, Object> source = sources.get(0);
            assertEquals("test-pacs", source.get("name"));
            assertEquals("TEST_PACS", source.get("aeTitle"));
            assertEquals("localhost", source.get("host"));
            assertEquals(104, source.get("port"));
            assertEquals(true, source.get("enabled"));
        }

        @Test
        @DisplayName("Should return empty list when no DICOM sources configured")
        void shouldReturnEmptyListWhenNoSources() {
            AppConfig emptyConfig = new AppConfig();
            emptyConfig.getResilience().setHealthCheckInterval(60);

            DestinationManager emptyManager = new DestinationManager(emptyConfig);
            QueryRetrieveResource resource = new QueryRetrieveResource(emptyConfig, emptyManager, transferTracker);

            Response response = resource.listSources();

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sources = (List<Map<String, Object>>) result.get("sources");

            assertTrue(sources.isEmpty());
            assertEquals(0, result.get("count"));

            emptyManager.close();
        }
    }

    @Nested
    @DisplayName("GET /query-retrieve/routes Tests")
    class GetAvailableRoutesTests {

        @Test
        @DisplayName("Should list available routes")
        void shouldListAvailableRoutes() {
            Response response = queryRetrieveResource.getAvailableRoutes();

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> routes = (List<Map<String, Object>>) result.get("routes");

            assertEquals(1, routes.size());
            assertEquals(1, result.get("count"));

            Map<String, Object> route = routes.get(0);
            assertEquals("TEST_ROUTE", route.get("aeTitle"));
            assertEquals(11112, route.get("port"));
            assertEquals("Test Route", route.get("description"));
        }

        @Test
        @DisplayName("Should exclude disabled routes")
        void shouldExcludeDisabledRoutes() {
            // Disable the route
            config.getRoutes().get(0).setEnabled(false);

            Response response = queryRetrieveResource.getAvailableRoutes();

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> routes = (List<Map<String, Object>>) result.get("routes");

            assertTrue(routes.isEmpty());
            assertEquals(0, result.get("count"));
        }
    }

    @Nested
    @DisplayName("POST /query-retrieve/query Tests")
    class QueryStudiesTests {

        @Test
        @DisplayName("Should return 400 when source is missing")
        void shouldReturn400WhenSourceMissing() {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("patientID", "12345");

            Response response = queryRetrieveResource.queryStudies(queryParams);

            assertEquals(400, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Source name is required"));
        }

        @Test
        @DisplayName("Should return 404 for unknown source")
        void shouldReturn404ForUnknownSource() {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("source", "unknown-pacs");
            queryParams.put("patientID", "12345");

            Response response = queryRetrieveResource.queryStudies(queryParams);

            assertEquals(404, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Source not found"));
        }

        @Test
        @DisplayName("Should return 400 for disabled source")
        void shouldReturn400ForDisabledSource() {
            // Disable the source
            ((AppConfig.DicomAeDestination) config.getDestinations().get("test-pacs")).setEnabled(false);

            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("source", "test-pacs");
            queryParams.put("patientID", "12345");

            Response response = queryRetrieveResource.queryStudies(queryParams);

            assertEquals(400, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Source is disabled"));
        }
    }

    @Nested
    @DisplayName("POST /query-retrieve/query/bulk Tests")
    class BulkQueryTests {

        @Test
        @DisplayName("Should return 400 when source is missing")
        void shouldReturn400WhenSourceMissing() {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("identifiers", Arrays.asList("1.2.3", "4.5.6"));

            Response response = queryRetrieveResource.bulkQuery(queryParams);

            assertEquals(400, response.getStatus());
        }

        @Test
        @DisplayName("Should return 400 when identifiers list is empty")
        void shouldReturn400WhenIdentifiersEmpty() {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("source", "test-pacs");
            queryParams.put("identifiers", Collections.emptyList());

            Response response = queryRetrieveResource.bulkQuery(queryParams);

            assertEquals(400, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Identifiers list is required"));
        }

        @Test
        @DisplayName("Should return 404 for unknown source")
        void shouldReturn404ForUnknownSource() {
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("source", "unknown-pacs");
            queryParams.put("identifiers", Arrays.asList("1.2.3", "4.5.6"));

            Response response = queryRetrieveResource.bulkQuery(queryParams);

            assertEquals(404, response.getStatus());
        }
    }

    @Nested
    @DisplayName("POST /query-retrieve/retrieve Tests")
    class RetrieveStudiesTests {

        @Test
        @DisplayName("Should return 400 when source is missing")
        void shouldReturn400WhenSourceMissing() {
            Map<String, Object> retrieveParams = new HashMap<>();
            retrieveParams.put("targetRoute", "TEST_ROUTE");
            retrieveParams.put("studyUIDs", Arrays.asList("1.2.3"));

            Response response = queryRetrieveResource.retrieveStudies(retrieveParams);

            assertEquals(400, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Source name is required"));
        }

        @Test
        @DisplayName("Should return 400 when target route is missing")
        void shouldReturn400WhenTargetRouteMissing() {
            Map<String, Object> retrieveParams = new HashMap<>();
            retrieveParams.put("source", "test-pacs");
            retrieveParams.put("studyUIDs", Arrays.asList("1.2.3"));

            Response response = queryRetrieveResource.retrieveStudies(retrieveParams);

            assertEquals(400, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Target route is required"));
        }

        @Test
        @DisplayName("Should return 400 when study UIDs are missing")
        void shouldReturn400WhenStudyUIDsMissing() {
            Map<String, Object> retrieveParams = new HashMap<>();
            retrieveParams.put("source", "test-pacs");
            retrieveParams.put("targetRoute", "TEST_ROUTE");
            retrieveParams.put("studyUIDs", Collections.emptyList());

            Response response = queryRetrieveResource.retrieveStudies(retrieveParams);

            assertEquals(400, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Study UID is required"));
        }

        @Test
        @DisplayName("Should return 404 for unknown source")
        void shouldReturn404ForUnknownSource() {
            Map<String, Object> retrieveParams = new HashMap<>();
            retrieveParams.put("source", "unknown-pacs");
            retrieveParams.put("targetRoute", "TEST_ROUTE");
            retrieveParams.put("studyUIDs", Arrays.asList("1.2.3"));

            Response response = queryRetrieveResource.retrieveStudies(retrieveParams);

            assertEquals(404, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Source not found"));
        }

        @Test
        @DisplayName("Should return 404 for unknown target route")
        void shouldReturn404ForUnknownTargetRoute() {
            Map<String, Object> retrieveParams = new HashMap<>();
            retrieveParams.put("source", "test-pacs");
            retrieveParams.put("targetRoute", "UNKNOWN_ROUTE");
            retrieveParams.put("studyUIDs", Arrays.asList("1.2.3"));

            Response response = queryRetrieveResource.retrieveStudies(retrieveParams);

            assertEquals(404, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Target route not found"));
        }
    }

    @Nested
    @DisplayName("GET /query-retrieve/retrieve/{jobId} Tests")
    class GetRetrieveStatusTests {

        @Test
        @DisplayName("Should return 404 for unknown job ID")
        void shouldReturn404ForUnknownJobId() {
            Response response = queryRetrieveResource.getRetrieveStatus("unknown-job-id");

            assertEquals(404, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Job not found"));
        }
    }

    @Nested
    @DisplayName("GET /query-retrieve/retrieve/jobs Tests")
    class ListRetrieveJobsTests {

        @Test
        @DisplayName("Should return empty list when no jobs")
        void shouldReturnEmptyListWhenNoJobs() {
            Response response = queryRetrieveResource.listRetrieveJobs();

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> jobs = (List<Map<String, Object>>) result.get("jobs");

            assertTrue(jobs.isEmpty());
            assertEquals(0, result.get("count"));
        }
    }

    @Nested
    @DisplayName("DELETE /query-retrieve/retrieve/{jobId} Tests")
    class CancelRetrieveJobTests {

        @Test
        @DisplayName("Should return 404 for unknown job ID")
        void shouldReturn404ForUnknownJobId() {
            Response response = queryRetrieveResource.cancelRetrieveJob("unknown-job-id");

            assertEquals(404, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Job not found"));
        }
    }

    @Nested
    @DisplayName("RetrieveJob Tests")
    class RetrieveJobTests {

        @Test
        @DisplayName("Should create retrieve job with correct initial state")
        void shouldCreateRetrieveJobWithCorrectState() {
            List<String> studyUIDs = Arrays.asList("1.2.3", "4.5.6", "7.8.9");
            QueryRetrieveResource.RetrieveJob job = new QueryRetrieveResource.RetrieveJob(
                    "JOB-001", "test-pacs", "TEST_ROUTE", studyUIDs);

            assertEquals("JOB-001", job.getJobId());
            assertEquals("test-pacs", job.getSourceName());
            assertEquals("TEST_ROUTE", job.getTargetRoute());
            assertEquals(3, job.getStudyUIDs().size());
            assertEquals("PENDING", job.getStatus());
            assertFalse(job.isCancelled());
            assertEquals(0, job.getCompletedCount());
            assertEquals(0, job.getFailedCount());
        }

        @Test
        @DisplayName("Should track progress correctly")
        void shouldTrackProgressCorrectly() {
            List<String> studyUIDs = Arrays.asList("1.2.3", "4.5.6", "7.8.9");
            QueryRetrieveResource.RetrieveJob job = new QueryRetrieveResource.RetrieveJob(
                    "JOB-001", "test-pacs", "TEST_ROUTE", studyUIDs);

            job.setStatus("RUNNING");
            job.incrementCompleted();
            job.incrementCompleted();
            job.incrementFailed();

            Map<String, Object> jobMap = job.toMap();
            assertEquals("RUNNING", jobMap.get("status"));
            assertEquals(2, jobMap.get("completedCount"));
            assertEquals(1, jobMap.get("failedCount"));
            assertEquals(3, jobMap.get("totalStudies"));
            assertEquals(100, jobMap.get("progress")); // (2+1) / 3 * 100 = 100%
        }

        @Test
        @DisplayName("Should cancel job")
        void shouldCancelJob() {
            List<String> studyUIDs = Arrays.asList("1.2.3");
            QueryRetrieveResource.RetrieveJob job = new QueryRetrieveResource.RetrieveJob(
                    "JOB-001", "test-pacs", "TEST_ROUTE", studyUIDs);

            assertFalse(job.isCancelled());
            job.cancel();
            assertTrue(job.isCancelled());
        }

        @Test
        @DisplayName("Should add messages and errors")
        void shouldAddMessagesAndErrors() {
            List<String> studyUIDs = Arrays.asList("1.2.3");
            QueryRetrieveResource.RetrieveJob job = new QueryRetrieveResource.RetrieveJob(
                    "JOB-001", "test-pacs", "TEST_ROUTE", studyUIDs);

            job.addMessage("Study 1.2.3 retrieved successfully");
            job.addError("Failed to retrieve study 4.5.6");

            Map<String, Object> jobMap = job.toMap();

            @SuppressWarnings("unchecked")
            List<String> messages = (List<String>) jobMap.get("messages");
            @SuppressWarnings("unchecked")
            List<String> errors = (List<String>) jobMap.get("errors");

            assertEquals(1, messages.size());
            assertEquals(1, errors.size());
            assertTrue(messages.get(0).contains("retrieved successfully"));
            assertTrue(errors.get(0).contains("Failed to retrieve"));
        }
    }
}
