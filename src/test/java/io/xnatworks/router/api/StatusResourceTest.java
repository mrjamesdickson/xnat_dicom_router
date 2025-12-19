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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StatusResource.
 */
@DisplayName("StatusResource Tests")
class StatusResourceTest {

    @TempDir
    Path tempDir;

    private AppConfig config;
    private DestinationManager destinationManager;
    private TransferTracker transferTracker;
    private StatusResource statusResource;

    @BeforeEach
    void setUp() {
        config = new AppConfig();
        config.getResilience().setHealthCheckInterval(60);

        // Add a test route
        AppConfig.RouteConfig route = new AppConfig.RouteConfig();
        route.setAeTitle("TEST_AE");
        route.setPort(11112);
        route.setEnabled(true);
        AppConfig.RouteDestination routeDest = new AppConfig.RouteDestination();
        routeDest.setDestination("test-dest");
        route.getDestinations().add(routeDest);
        config.getRoutes().add(route);

        // Add a test destination
        AppConfig.FileDestination fileDest = new AppConfig.FileDestination();
        fileDest.setPath(tempDir.toString());
        fileDest.setEnabled(true);
        config.getDestinations().put("test-dest", fileDest);

        destinationManager = new DestinationManager(config);
        transferTracker = new TransferTracker(tempDir);

        statusResource = new StatusResource(config, destinationManager, transferTracker);
    }

    @AfterEach
    void tearDown() {
        if (destinationManager != null) {
            destinationManager.close();
        }
    }

    @Nested
    @DisplayName("GET /status Tests")
    class GetStatusTests {

        @Test
        @DisplayName("Should return 200 OK with status data")
        void shouldReturnStatusData() {
            Response response = statusResource.getStatus();

            assertEquals(200, response.getStatus());
            assertNotNull(response.getEntity());

            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) response.getEntity();
            assertNotNull(status.get("version"));
            assertNotNull(status.get("startTime"));
            assertNotNull(status.get("uptime"));
            assertNotNull(status.get("routes"));
            assertNotNull(status.get("destinations"));
            assertNotNull(status.get("transfers"));
        }

        @Test
        @DisplayName("Should include version 2.1.0")
        void shouldIncludeVersion() {
            Response response = statusResource.getStatus();

            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) response.getEntity();
            assertEquals("2.1.0", status.get("version"));
        }

        @Test
        @DisplayName("Should include routes summary")
        void shouldIncludeRoutesSummary() {
            Response response = statusResource.getStatus();

            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> routes = (List<Map<String, Object>>) status.get("routes");

            assertEquals(1, routes.size());
            Map<String, Object> route = routes.get(0);
            assertEquals("TEST_AE", route.get("aeTitle"));
            assertEquals(11112, route.get("port"));
            assertEquals(true, route.get("enabled"));
            assertEquals(1, route.get("destinations"));
        }

        @Test
        @DisplayName("Should include destinations summary")
        void shouldIncludeDestinationsSummary() {
            Response response = statusResource.getStatus();

            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> destinations = (List<Map<String, Object>>) status.get("destinations");

            assertEquals(1, destinations.size());
            Map<String, Object> dest = destinations.get(0);
            assertEquals("test-dest", dest.get("name"));
            assertEquals("file", dest.get("type"));
            assertNotNull(dest.get("available"));
            assertNotNull(dest.get("availabilityPercent"));
        }

        @Test
        @DisplayName("Should include transfer statistics")
        void shouldIncludeTransferStatistics() {
            Response response = statusResource.getStatus();

            @SuppressWarnings("unchecked")
            Map<String, Object> status = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            Map<String, Object> transfers = (Map<String, Object>) status.get("transfers");

            assertNotNull(transfers.get("total"));
            assertNotNull(transfers.get("successful"));
            assertNotNull(transfers.get("failed"));
            assertNotNull(transfers.get("active"));
            assertNotNull(transfers.get("successRate"));
        }
    }

    @Nested
    @DisplayName("GET /status/health Tests")
    class GetHealthTests {

        @Test
        @DisplayName("Should return 200 OK with health data")
        void shouldReturnHealthData() {
            Response response = statusResource.getHealth();

            assertEquals(200, response.getStatus());
            assertNotNull(response.getEntity());

            @SuppressWarnings("unchecked")
            Map<String, Object> health = (Map<String, Object>) response.getEntity();
            assertNotNull(health.get("status"));
            assertNotNull(health.get("timestamp"));
            assertNotNull(health.get("destinationsAvailable"));
        }

        @Test
        @DisplayName("Should return UP status when destinations available")
        void shouldReturnUpStatusWhenDestinationsAvailable() {
            Response response = statusResource.getHealth();

            @SuppressWarnings("unchecked")
            Map<String, Object> health = (Map<String, Object>) response.getEntity();
            assertEquals("UP", health.get("status"));
        }

        @Test
        @DisplayName("Should include destinations available count")
        void shouldIncludeDestinationsAvailableCount() {
            Response response = statusResource.getHealth();

            @SuppressWarnings("unchecked")
            Map<String, Object> health = (Map<String, Object>) response.getEntity();
            String destAvailable = (String) health.get("destinationsAvailable");
            assertTrue(destAvailable.matches("\\d+/\\d+"));
        }
    }

    @Nested
    @DisplayName("Health Status Edge Cases")
    class HealthStatusEdgeCases {

        @Test
        @DisplayName("Should return DEGRADED when no destinations available")
        void shouldReturnDegradedWhenNoDestinationsAvailable() {
            // Create config with unavailable destination
            AppConfig badConfig = new AppConfig();
            badConfig.getResilience().setHealthCheckInterval(60);

            AppConfig.FileDestination badDest = new AppConfig.FileDestination();
            badDest.setPath("/nonexistent/path/that/does/not/exist");
            badDest.setEnabled(true);
            badConfig.getDestinations().put("bad-dest", badDest);

            DestinationManager badManager = new DestinationManager(badConfig);
            // Trigger health check to update availability status
            badManager.checkAllDestinations();

            TransferTracker tracker = new TransferTracker(tempDir);

            StatusResource resource = new StatusResource(badConfig, badManager, tracker);

            Response response = resource.getHealth();

            @SuppressWarnings("unchecked")
            Map<String, Object> health = (Map<String, Object>) response.getEntity();
            assertEquals("DEGRADED", health.get("status"));
            assertEquals("0/1", health.get("destinationsAvailable"));

            badManager.close();
        }

        @Test
        @DisplayName("Should return UP when no destinations configured")
        void shouldReturnUpWhenNoDestinationsConfigured() {
            AppConfig emptyConfig = new AppConfig();
            emptyConfig.getResilience().setHealthCheckInterval(60);

            DestinationManager emptyManager = new DestinationManager(emptyConfig);
            TransferTracker tracker = new TransferTracker(tempDir);

            StatusResource resource = new StatusResource(emptyConfig, emptyManager, tracker);

            Response response = resource.getHealth();

            @SuppressWarnings("unchecked")
            Map<String, Object> health = (Map<String, Object>) response.getEntity();
            assertEquals("UP", health.get("status"));
            assertEquals("0/0", health.get("destinationsAvailable"));

            emptyManager.close();
        }
    }
}
