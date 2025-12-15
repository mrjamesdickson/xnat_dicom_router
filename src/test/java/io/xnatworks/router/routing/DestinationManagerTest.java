/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.routing;

import io.xnatworks.router.config.AppConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DestinationManager.
 */
@DisplayName("DestinationManager Tests")
class DestinationManagerTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("DestinationHealth Tests")
    class DestinationHealthTests {

        @Test
        @DisplayName("Should initialize with available status")
        void shouldInitializeWithAvailableStatus() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            assertTrue(health.isAvailable());
            assertEquals(0, health.getConsecutiveFailures());
            assertEquals(0, health.getTotalChecks());
            assertEquals(0, health.getSuccessfulChecks());
        }

        @Test
        @DisplayName("Should update status correctly when available")
        void shouldUpdateStatusWhenAvailable() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.updateStatus(true);

            assertTrue(health.isAvailable());
            assertEquals(0, health.getConsecutiveFailures());
            assertEquals(1, health.getTotalChecks());
            assertEquals(1, health.getSuccessfulChecks());
            assertNotNull(health.getLastCheckTime());
            assertNotNull(health.getLastAvailableTime());
            assertNull(health.getUnavailableSince());
        }

        @Test
        @DisplayName("Should update status correctly when unavailable")
        void shouldUpdateStatusWhenUnavailable() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.updateStatus(false);

            assertFalse(health.isAvailable());
            assertEquals(1, health.getConsecutiveFailures());
            assertEquals(1, health.getTotalChecks());
            assertEquals(0, health.getSuccessfulChecks());
            assertNotNull(health.getLastCheckTime());
            assertNotNull(health.getUnavailableSince());
        }

        @Test
        @DisplayName("Should track consecutive failures")
        void shouldTrackConsecutiveFailures() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.updateStatus(false);
            health.updateStatus(false);
            health.updateStatus(false);

            assertEquals(3, health.getConsecutiveFailures());
            assertEquals(3, health.getTotalChecks());
            assertEquals(0, health.getSuccessfulChecks());
        }

        @Test
        @DisplayName("Should reset consecutive failures on success")
        void shouldResetConsecutiveFailuresOnSuccess() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.updateStatus(false);
            health.updateStatus(false);
            health.updateStatus(true);

            assertEquals(0, health.getConsecutiveFailures());
            assertTrue(health.isAvailable());
            assertNull(health.getUnavailableSince());
        }

        @Test
        @DisplayName("Should calculate availability percentage correctly")
        void shouldCalculateAvailabilityPercentageCorrectly() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.updateStatus(true);
            health.updateStatus(true);
            health.updateStatus(true);
            health.updateStatus(false);

            assertEquals(75.0, health.getAvailabilityPercent(), 0.1);
        }

        @Test
        @DisplayName("Should return 100% availability when no checks")
        void shouldReturn100PercentWhenNoChecks() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            assertEquals(100.0, health.getAvailabilityPercent(), 0.1);
        }

        @Test
        @DisplayName("Should calculate downtime seconds correctly")
        void shouldCalculateDowntimeSecondsCorrectly() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.updateStatus(false);

            // Should have non-zero downtime after being marked unavailable
            assertTrue(health.getDowntimeSeconds() >= 0);
        }

        @Test
        @DisplayName("Should return 0 downtime when available")
        void shouldReturn0DowntimeWhenAvailable() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.updateStatus(true);

            assertEquals(0, health.getDowntimeSeconds());
        }

        @Test
        @DisplayName("Should set and get name correctly")
        void shouldSetAndGetNameCorrectly() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.setName("test-destination");
            assertEquals("test-destination", health.getName());
        }

        @Test
        @DisplayName("Should set and get type correctly")
        void shouldSetAndGetTypeCorrectly() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.setType("xnat");
            assertEquals("xnat", health.getType());
        }

        @Test
        @DisplayName("Should set and get description correctly")
        void shouldSetAndGetDescriptionCorrectly() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.setDescription("Test Description");
            assertEquals("Test Description", health.getDescription());
        }

        @Test
        @DisplayName("Should set and get URL correctly")
        void shouldSetAndGetUrlCorrectly() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.setUrl("http://example.com");
            assertEquals("http://example.com", health.getUrl());
        }

        @Test
        @DisplayName("Should set and get AE Title correctly")
        void shouldSetAndGetAeTitleCorrectly() {
            DestinationManager.DestinationHealth health = new DestinationManager.DestinationHealth();
            health.setAeTitle("DICOM_AE");
            assertEquals("DICOM_AE", health.getAeTitle());
        }
    }

    @Nested
    @DisplayName("ForwardResult Tests")
    class ForwardResultTests {

        @Test
        @DisplayName("Should initialize with correct defaults")
        void shouldInitializeWithCorrectDefaults() {
            DestinationManager.ForwardResult result = new DestinationManager.ForwardResult();
            assertEquals(0, result.getTotalFiles());
            assertEquals(0, result.getSuccessCount());
            assertEquals(0, result.getFailedCount());
            assertEquals(0, result.getDurationMs());
            assertNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("Should set and get destination correctly")
        void shouldSetAndGetDestinationCorrectly() {
            DestinationManager.ForwardResult result = new DestinationManager.ForwardResult();
            result.setDestination("test-dest");
            assertEquals("test-dest", result.getDestination());
        }

        @Test
        @DisplayName("Should set and get total files correctly")
        void shouldSetAndGetTotalFilesCorrectly() {
            DestinationManager.ForwardResult result = new DestinationManager.ForwardResult();
            result.setTotalFiles(100);
            assertEquals(100, result.getTotalFiles());
        }

        @Test
        @DisplayName("Should increment success count correctly")
        void shouldIncrementSuccessCountCorrectly() {
            DestinationManager.ForwardResult result = new DestinationManager.ForwardResult();
            result.incrementSuccess();
            result.incrementSuccess();
            result.incrementSuccess();
            assertEquals(3, result.getSuccessCount());
        }

        @Test
        @DisplayName("Should increment failed count correctly")
        void shouldIncrementFailedCountCorrectly() {
            DestinationManager.ForwardResult result = new DestinationManager.ForwardResult();
            result.incrementFailed();
            result.incrementFailed();
            assertEquals(2, result.getFailedCount());
        }

        @Test
        @DisplayName("Should set and get duration correctly")
        void shouldSetAndGetDurationCorrectly() {
            DestinationManager.ForwardResult result = new DestinationManager.ForwardResult();
            result.setDurationMs(5000);
            assertEquals(5000, result.getDurationMs());
        }

        @Test
        @DisplayName("Should set and get error message correctly")
        void shouldSetAndGetErrorMessageCorrectly() {
            DestinationManager.ForwardResult result = new DestinationManager.ForwardResult();
            result.setErrorMessage("Test error");
            assertEquals("Test error", result.getErrorMessage());
        }

        @Test
        @DisplayName("Should report success when no failures")
        void shouldReportSuccessWhenNoFailures() {
            DestinationManager.ForwardResult result = new DestinationManager.ForwardResult();
            result.setTotalFiles(10);
            result.incrementSuccess();
            result.incrementSuccess();

            assertTrue(result.isSuccess());
            assertFalse(result.isPartial());
        }

        @Test
        @DisplayName("Should report failure when all failed")
        void shouldReportFailureWhenAllFailed() {
            DestinationManager.ForwardResult result = new DestinationManager.ForwardResult();
            result.setTotalFiles(2);
            result.incrementFailed();
            result.incrementFailed();

            assertFalse(result.isSuccess());
            assertFalse(result.isPartial());
        }

        @Test
        @DisplayName("Should report partial when some succeeded and some failed")
        void shouldReportPartialWhenMixed() {
            DestinationManager.ForwardResult result = new DestinationManager.ForwardResult();
            result.setTotalFiles(4);
            result.incrementSuccess();
            result.incrementSuccess();
            result.incrementFailed();
            result.incrementFailed();

            assertFalse(result.isSuccess());
            assertTrue(result.isPartial());
        }
    }

    @Nested
    @DisplayName("DestinationManager Initialization Tests")
    class DestinationManagerInitializationTests {

        @Test
        @DisplayName("Should initialize with empty config")
        void shouldInitializeWithEmptyConfig() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            try (DestinationManager manager = new DestinationManager(config)) {
                Map<String, DestinationManager.DestinationHealth> health = manager.getAllHealth();
                assertTrue(health.isEmpty());
            }
        }

        @Test
        @DisplayName("Should initialize file destination")
        void shouldInitializeFileDestination() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            AppConfig.FileDestination fileDest = new AppConfig.FileDestination();
            fileDest.setPath(tempDir.toString());
            fileDest.setEnabled(true);
            fileDest.setDescription("Test File Destination");
            config.getDestinations().put("file-test", fileDest);

            try (DestinationManager manager = new DestinationManager(config)) {
                Map<String, DestinationManager.DestinationHealth> health = manager.getAllHealth();
                assertEquals(1, health.size());
                assertTrue(health.containsKey("file-test"));

                DestinationManager.DestinationHealth fileHealth = health.get("file-test");
                assertEquals("file-test", fileHealth.getName());
                assertEquals("file", fileHealth.getType());
                assertEquals("Test File Destination", fileHealth.getDescription());
                assertEquals(tempDir.toString(), fileHealth.getUrl());
            }
        }

        @Test
        @DisplayName("Should skip disabled destinations")
        void shouldSkipDisabledDestinations() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            AppConfig.FileDestination fileDest = new AppConfig.FileDestination();
            fileDest.setPath(tempDir.toString());
            fileDest.setEnabled(false); // Disabled
            config.getDestinations().put("disabled-dest", fileDest);

            try (DestinationManager manager = new DestinationManager(config)) {
                Map<String, DestinationManager.DestinationHealth> health = manager.getAllHealth();
                assertTrue(health.isEmpty());
            }
        }

        @Test
        @DisplayName("Should return empty list when no available destinations")
        void shouldReturnEmptyListWhenNoAvailableDestinations() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            try (DestinationManager manager = new DestinationManager(config)) {
                List<String> available = manager.getAvailableDestinations();
                assertTrue(available.isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("Health Check Tests")
    class HealthCheckTests {

        @Test
        @DisplayName("Should check file destination availability")
        void shouldCheckFileDestinationAvailability() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            AppConfig.FileDestination fileDest = new AppConfig.FileDestination();
            fileDest.setPath(tempDir.toString());
            fileDest.setEnabled(true);
            config.getDestinations().put("file-test", fileDest);

            try (DestinationManager manager = new DestinationManager(config)) {
                boolean available = manager.checkDestination("file-test");
                assertTrue(available);
                assertTrue(manager.isAvailable("file-test"));
            }
        }

        @Test
        @DisplayName("Should report unavailable for non-existent path")
        void shouldReportUnavailableForNonExistentPath() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            AppConfig.FileDestination fileDest = new AppConfig.FileDestination();
            fileDest.setPath("/nonexistent/path/that/does/not/exist");
            fileDest.setEnabled(true);
            config.getDestinations().put("file-test", fileDest);

            try (DestinationManager manager = new DestinationManager(config)) {
                boolean available = manager.checkDestination("file-test");
                assertFalse(available);
            }
        }

        @Test
        @DisplayName("Should return false for unknown destination")
        void shouldReturnFalseForUnknownDestination() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            try (DestinationManager manager = new DestinationManager(config)) {
                boolean available = manager.checkDestination("unknown");
                assertFalse(available);
            }
        }

        @Test
        @DisplayName("Should return null health for unknown destination")
        void shouldReturnNullHealthForUnknownDestination() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            try (DestinationManager manager = new DestinationManager(config)) {
                DestinationManager.DestinationHealth health = manager.getHealth("unknown");
                assertNull(health);
            }
        }

        @Test
        @DisplayName("Should return false for isAvailable with unknown destination")
        void shouldReturnFalseForIsAvailableWithUnknownDestination() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            try (DestinationManager manager = new DestinationManager(config)) {
                assertFalse(manager.isAvailable("unknown"));
            }
        }
    }

    @Nested
    @DisplayName("Dynamic Destination Management Tests")
    class DynamicDestinationManagementTests {

        @Test
        @DisplayName("Should add destination at runtime")
        void shouldAddDestinationAtRuntime() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            try (DestinationManager manager = new DestinationManager(config)) {
                assertTrue(manager.getAllHealth().isEmpty());

                AppConfig.FileDestination newDest = new AppConfig.FileDestination();
                newDest.setPath(tempDir.toString());
                newDest.setEnabled(true);
                newDest.setDescription("Runtime Added");

                manager.addDestination("runtime-dest", newDest);

                assertEquals(1, manager.getAllHealth().size());
                assertNotNull(manager.getHealth("runtime-dest"));
                assertEquals("Runtime Added", manager.getHealth("runtime-dest").getDescription());
            }
        }

        @Test
        @DisplayName("Should throw exception when adding duplicate destination")
        void shouldThrowExceptionWhenAddingDuplicate() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            AppConfig.FileDestination fileDest = new AppConfig.FileDestination();
            fileDest.setPath(tempDir.toString());
            fileDest.setEnabled(true);
            config.getDestinations().put("existing-dest", fileDest);

            try (DestinationManager manager = new DestinationManager(config)) {
                AppConfig.FileDestination newDest = new AppConfig.FileDestination();
                newDest.setPath(tempDir.toString());

                assertThrows(IllegalArgumentException.class, () ->
                        manager.addDestination("existing-dest", newDest));
            }
        }

        @Test
        @DisplayName("Should remove destination at runtime")
        void shouldRemoveDestinationAtRuntime() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            AppConfig.FileDestination fileDest = new AppConfig.FileDestination();
            fileDest.setPath(tempDir.toString());
            fileDest.setEnabled(true);
            config.getDestinations().put("to-remove", fileDest);

            try (DestinationManager manager = new DestinationManager(config)) {
                assertEquals(1, manager.getAllHealth().size());

                manager.removeDestination("to-remove");

                assertTrue(manager.getAllHealth().isEmpty());
                assertNull(manager.getHealth("to-remove"));
            }
        }

        @Test
        @DisplayName("Should update destination at runtime")
        void shouldUpdateDestinationAtRuntime() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            AppConfig.FileDestination fileDest = new AppConfig.FileDestination();
            fileDest.setPath(tempDir.toString());
            fileDest.setEnabled(true);
            fileDest.setDescription("Original");
            config.getDestinations().put("to-update", fileDest);

            try (DestinationManager manager = new DestinationManager(config)) {
                assertEquals("Original", manager.getHealth("to-update").getDescription());

                AppConfig.FileDestination updatedDest = new AppConfig.FileDestination();
                updatedDest.setPath(tempDir.toString());
                updatedDest.setEnabled(true);
                updatedDest.setDescription("Updated");

                manager.updateDestination("to-update", updatedDest);

                assertEquals("Updated", manager.getHealth("to-update").getDescription());
            }
        }
    }

    @Nested
    @DisplayName("Client Access Tests")
    class ClientAccessTests {

        @Test
        @DisplayName("Should return null for unknown XNAT client")
        void shouldReturnNullForUnknownXnatClient() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            try (DestinationManager manager = new DestinationManager(config)) {
                assertNull(manager.getXnatClient("unknown"));
            }
        }

        @Test
        @DisplayName("Should return null for unknown DICOM client")
        void shouldReturnNullForUnknownDicomClient() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            try (DestinationManager manager = new DestinationManager(config)) {
                assertNull(manager.getDicomClient("unknown"));
            }
        }
    }

    @Nested
    @DisplayName("File Forwarding Tests")
    class FileForwardingTests {

        @Test
        @DisplayName("Should throw exception for non-file destination")
        void shouldThrowExceptionForNonFileDestination() {
            AppConfig config = new AppConfig();
            config.getResilience().setHealthCheckInterval(60);

            AppConfig.XnatDestination xnatDest = new AppConfig.XnatDestination();
            xnatDest.setUrl("http://xnat.example.com");
            xnatDest.setEnabled(true);
            config.getDestinations().put("xnat-dest", xnatDest);

            try (DestinationManager manager = new DestinationManager(config)) {
                assertThrows(IllegalArgumentException.class, () ->
                        manager.forwardToFile("xnat-dest", List.of(), null));
            }
        }
    }
}
