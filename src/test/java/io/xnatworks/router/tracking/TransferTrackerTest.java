/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.tracking;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TransferTracker.
 */
@DisplayName("TransferTracker Tests")
class TransferTrackerTest {

    @TempDir
    Path tempDir;

    private TransferTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new TransferTracker(tempDir);
    }

    @Nested
    @DisplayName("Transfer Creation Tests")
    class TransferCreationTests {

        @Test
        @DisplayName("Should create transfer with correct initial state")
        void createTransfer_ShouldSetCorrectInitialState() {
            TransferTracker.TransferRecord record = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);

            assertNotNull(record.getId());
            assertEquals("TEST_AE", record.getAeTitle());
            assertEquals("1.2.3.4.5", record.getStudyUid());
            assertEquals("CALLING_AE", record.getCallingAeTitle());
            assertEquals(10, record.getFileCount());
            assertEquals(1024000, record.getTotalSize());
            assertEquals(TransferTracker.TransferStatus.RECEIVED, record.getStatus());
            assertNotNull(record.getReceivedAt());
            assertNotNull(record.getDestinationResults());
            assertTrue(record.getDestinationResults().isEmpty());
        }

        @Test
        @DisplayName("Should generate unique transfer IDs")
        void createTransfer_ShouldGenerateUniqueIds() {
            TransferTracker.TransferRecord record1 = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);
            TransferTracker.TransferRecord record2 = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.6", "CALLING_AE", 20, 2048000);

            assertNotEquals(record1.getId(), record2.getId());
        }

        @Test
        @DisplayName("Should track transfer in active list")
        void createTransfer_ShouldAddToActiveList() {
            TransferTracker.TransferRecord record = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);

            List<TransferTracker.TransferRecord> active = tracker.getActiveTransfers();
            assertEquals(1, active.size());
            assertEquals(record.getId(), active.get(0).getId());
        }
    }

    @Nested
    @DisplayName("Transfer Status Update Tests")
    class TransferStatusTests {

        private TransferTracker.TransferRecord record;

        @BeforeEach
        void createRecord() {
            record = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);
        }

        @Test
        @DisplayName("Should update status to processing")
        void startProcessing_ShouldUpdateStatus() {
            tracker.startProcessing(record.getId());

            TransferTracker.TransferRecord updated = tracker.getTransfer(record.getId());
            assertEquals(TransferTracker.TransferStatus.PROCESSING, updated.getStatus());
            assertNotNull(updated.getProcessingStartedAt());
        }

        @Test
        @DisplayName("Should update status to forwarding")
        void startForwarding_ShouldUpdateStatus() {
            tracker.startForwarding(record.getId(), Arrays.asList("dest1", "dest2"));

            TransferTracker.TransferRecord updated = tracker.getTransfer(record.getId());
            assertEquals(TransferTracker.TransferStatus.FORWARDING, updated.getStatus());
            assertNotNull(updated.getForwardingStartedAt());
            assertEquals(2, updated.getDestinationResults().size());
        }

        @Test
        @DisplayName("Should handle non-existent transfer gracefully")
        void startProcessing_WithInvalidId_ShouldNotThrow() {
            assertDoesNotThrow(() -> tracker.startProcessing("non-existent-id"));
        }
    }

    @Nested
    @DisplayName("Progress Tracking Tests")
    class ProgressTrackingTests {

        private TransferTracker.TransferRecord record;

        @BeforeEach
        void createRecord() {
            record = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 100, 1024000);
        }

        @Test
        @DisplayName("Should update progress correctly")
        void updateProgress_ShouldSetValues() {
            tracker.updateProgress(record.getId(), 50, 512000);

            TransferTracker.TransferRecord updated = tracker.getTransfer(record.getId());
            assertEquals(50, updated.getFilesProcessed());
            assertEquals(512000, updated.getBytesProcessed());
        }

        @Test
        @DisplayName("Should increment progress correctly")
        void incrementProgress_ShouldIncrementValues() {
            tracker.incrementProgress(record.getId(), 10000);
            tracker.incrementProgress(record.getId(), 20000);

            TransferTracker.TransferRecord updated = tracker.getTransfer(record.getId());
            assertEquals(2, updated.getFilesProcessed());
            assertEquals(30000, updated.getBytesProcessed());
        }

        @Test
        @DisplayName("Should calculate progress percentage correctly")
        void getProgressPercent_ShouldCalculateCorrectly() {
            tracker.updateProgress(record.getId(), 50, 512000);

            TransferTracker.TransferRecord updated = tracker.getTransfer(record.getId());
            assertEquals(50.0, updated.getProgressPercent(), 0.1);
        }

        @Test
        @DisplayName("Should return 100% for completed transfers")
        void getProgressPercent_WhenCompleted_ShouldReturn100() {
            tracker.startForwarding(record.getId(), Arrays.asList("dest1"));
            tracker.updateDestinationResult(record.getId(), "dest1",
                    TransferTracker.DestinationStatus.SUCCESS, "OK", 1000, 100);

            // Get from history since it's removed from active
            List<TransferTracker.TransferRecord> history = tracker.getTransferHistory("TEST_AE", 10);
            assertTrue(history.stream().anyMatch(r -> r.getProgressPercent() == 100.0));
        }

        @Test
        @DisplayName("Should return 0% for failed transfers")
        void getProgressPercent_WhenFailed_ShouldReturn0() {
            record.setStatus(TransferTracker.TransferStatus.FAILED);
            assertEquals(0.0, record.getProgressPercent());
        }
    }

    @Nested
    @DisplayName("Destination Result Tests")
    class DestinationResultTests {

        private TransferTracker.TransferRecord record;

        @BeforeEach
        void createAndForward() {
            record = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);
            tracker.startForwarding(record.getId(), Arrays.asList("dest1", "dest2"));
        }

        @Test
        @DisplayName("Should update destination result correctly")
        void updateDestinationResult_ShouldSetValues() {
            tracker.updateDestinationResult(record.getId(), "dest1",
                    TransferTracker.DestinationStatus.SUCCESS, "Upload complete", 5000, 10);

            TransferTracker.TransferRecord updated = tracker.getTransfer(record.getId());
            TransferTracker.DestinationResult dest1 = updated.getDestinationResults().stream()
                    .filter(d -> d.getDestination().equals("dest1"))
                    .findFirst()
                    .orElse(null);

            assertNotNull(dest1);
            assertEquals(TransferTracker.DestinationStatus.SUCCESS, dest1.getStatus());
            assertEquals("Upload complete", dest1.getMessage());
            assertEquals(5000, dest1.getDurationMs());
            assertEquals(10, dest1.getFilesTransferred());
            assertNotNull(dest1.getCompletedAt());
        }

        @Test
        @DisplayName("Should mark transfer completed when all destinations succeed")
        void updateDestinationResult_WhenAllSuccess_ShouldCompleteTransfer() {
            tracker.updateDestinationResult(record.getId(), "dest1",
                    TransferTracker.DestinationStatus.SUCCESS, "OK", 1000, 10);
            tracker.updateDestinationResult(record.getId(), "dest2",
                    TransferTracker.DestinationStatus.SUCCESS, "OK", 2000, 10);

            // Transfer should be removed from active and moved to history
            assertNull(tracker.getTransfer(record.getId()));

            List<TransferTracker.TransferRecord> history = tracker.getTransferHistory("TEST_AE", 10);
            assertTrue(history.stream().anyMatch(r ->
                    r.getStatus() == TransferTracker.TransferStatus.COMPLETED));
        }

        @Test
        @DisplayName("Should mark transfer partial when some destinations fail")
        void updateDestinationResult_WhenSomeFail_ShouldMarkPartial() {
            tracker.updateDestinationResult(record.getId(), "dest1",
                    TransferTracker.DestinationStatus.SUCCESS, "OK", 1000, 10);
            tracker.updateDestinationResult(record.getId(), "dest2",
                    TransferTracker.DestinationStatus.FAILED, "Connection refused", 500, 0);

            List<TransferTracker.TransferRecord> history = tracker.getTransferHistory("TEST_AE", 10);
            assertTrue(history.stream().anyMatch(r ->
                    r.getStatus() == TransferTracker.TransferStatus.PARTIAL));
        }

        @Test
        @DisplayName("Should mark transfer failed when all destinations fail")
        void updateDestinationResult_WhenAllFail_ShouldMarkFailed() {
            tracker.updateDestinationResult(record.getId(), "dest1",
                    TransferTracker.DestinationStatus.FAILED, "Error 1", 500, 0);
            tracker.updateDestinationResult(record.getId(), "dest2",
                    TransferTracker.DestinationStatus.FAILED, "Error 2", 500, 0);

            List<TransferTracker.TransferRecord> history = tracker.getTransferHistory("TEST_AE", 10);
            assertTrue(history.stream().anyMatch(r ->
                    r.getStatus() == TransferTracker.TransferStatus.FAILED));
        }
    }

    @Nested
    @DisplayName("Transfer Failure Tests")
    class TransferFailureTests {

        @Test
        @DisplayName("Should mark transfer as failed with reason")
        void failTransfer_ShouldSetFailedStatus() {
            TransferTracker.TransferRecord record = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);

            tracker.failTransfer(record.getId(), "Processing error");

            // Should be removed from active
            assertNull(tracker.getTransfer(record.getId()));

            // Should be in history with failed status
            List<TransferTracker.TransferRecord> history = tracker.getTransferHistory("TEST_AE", 10);
            TransferTracker.TransferRecord failed = history.stream()
                    .filter(r -> r.getId().equals(record.getId()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(failed);
            assertEquals(TransferTracker.TransferStatus.FAILED, failed.getStatus());
            assertEquals("Processing error", failed.getErrorMessage());
        }
    }

    @Nested
    @DisplayName("Statistics Tests")
    class StatisticsTests {

        @Test
        @DisplayName("Should track AE statistics correctly")
        void getStatistics_ShouldReturnCorrectCounts() {
            // Create and complete a successful transfer
            TransferTracker.TransferRecord record1 = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);
            tracker.startForwarding(record1.getId(), Arrays.asList("dest1"));
            tracker.updateDestinationResult(record1.getId(), "dest1",
                    TransferTracker.DestinationStatus.SUCCESS, "OK", 1000, 10);

            // Create and fail a transfer
            TransferTracker.TransferRecord record2 = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.6", "CALLING_AE", 10, 1024000);
            tracker.failTransfer(record2.getId(), "Error");

            TransferTracker.AeStatistics stats = tracker.getStatistics("TEST_AE");
            assertEquals(2, stats.getReceived());
            assertEquals(1, stats.getSuccess());
            assertEquals(1, stats.getFailed());
        }

        @Test
        @DisplayName("Should calculate success rate correctly")
        void getSuccessRate_ShouldCalculateCorrectly() {
            // Create 4 transfers: 3 success, 1 failed
            for (int i = 0; i < 3; i++) {
                TransferTracker.TransferRecord record = tracker.createTransfer(
                        "TEST_AE", "1.2.3.4." + i, "CALLING_AE", 10, 1024000);
                tracker.startForwarding(record.getId(), Arrays.asList("dest1"));
                tracker.updateDestinationResult(record.getId(), "dest1",
                        TransferTracker.DestinationStatus.SUCCESS, "OK", 1000, 10);
            }

            TransferTracker.TransferRecord failedRecord = tracker.createTransfer(
                    "TEST_AE", "1.2.3.4.99", "CALLING_AE", 10, 1024000);
            tracker.failTransfer(failedRecord.getId(), "Error");

            TransferTracker.AeStatistics stats = tracker.getStatistics("TEST_AE");
            assertEquals(75.0, stats.getSuccessRate(), 0.1);
        }
    }

    @Nested
    @DisplayName("Active Transfer Filtering Tests")
    class ActiveTransferFilteringTests {

        @Test
        @DisplayName("Should filter active transfers by AE Title")
        void getActiveTransfers_ByAeTitle_ShouldFilter() {
            tracker.createTransfer("AE_1", "1.2.3", "CALLING", 10, 1000);
            tracker.createTransfer("AE_1", "1.2.4", "CALLING", 10, 1000);
            tracker.createTransfer("AE_2", "1.2.5", "CALLING", 10, 1000);

            List<TransferTracker.TransferRecord> ae1Transfers = tracker.getActiveTransfers("AE_1");
            List<TransferTracker.TransferRecord> ae2Transfers = tracker.getActiveTransfers("AE_2");

            assertEquals(2, ae1Transfers.size());
            assertEquals(1, ae2Transfers.size());
            assertTrue(ae1Transfers.stream().allMatch(t -> t.getAeTitle().equals("AE_1")));
            assertTrue(ae2Transfers.stream().allMatch(t -> t.getAeTitle().equals("AE_2")));
        }

        @Test
        @DisplayName("Should return empty list for unknown AE Title")
        void getActiveTransfers_UnknownAeTitle_ShouldReturnEmpty() {
            tracker.createTransfer("AE_1", "1.2.3", "CALLING", 10, 1000);

            List<TransferTracker.TransferRecord> transfers = tracker.getActiveTransfers("UNKNOWN");
            assertTrue(transfers.isEmpty());
        }
    }

    @Nested
    @DisplayName("TransferRecord Tests")
    class TransferRecordTests {

        @Test
        @DisplayName("Should calculate total duration correctly")
        void getTotalDurationMs_ShouldCalculateCorrectly() {
            TransferTracker.TransferRecord record = new TransferTracker.TransferRecord();
            record.setReceivedAt(java.time.LocalDateTime.now().minusSeconds(10));
            record.setCompletedAt(java.time.LocalDateTime.now());

            long duration = record.getTotalDurationMs();
            assertTrue(duration >= 9000 && duration <= 11000);
        }

        @Test
        @DisplayName("Should return 0 duration when not completed")
        void getTotalDurationMs_WhenNotCompleted_ShouldReturn0() {
            TransferTracker.TransferRecord record = new TransferTracker.TransferRecord();
            record.setReceivedAt(java.time.LocalDateTime.now());
            // No completedAt set

            assertEquals(0, record.getTotalDurationMs());
        }
    }

    @Nested
    @DisplayName("Global Statistics Tests")
    class GlobalStatisticsTests {

        @Test
        @DisplayName("Should return global statistics")
        void getGlobalStatistics_ShouldReturnStats() {
            TransferTracker.GlobalStatistics stats = tracker.getGlobalStatistics();
            assertNotNull(stats);
            assertEquals(0, stats.getActiveTransfers());
        }

        @Test
        @DisplayName("Should calculate global success rate")
        void getSuccessRate_ShouldCalculate() {
            TransferTracker.GlobalStatistics stats = new TransferTracker.GlobalStatistics();
            stats.setTotalTransfers(100);
            stats.setSuccessfulTransfers(75);

            assertEquals(75.0, stats.getSuccessRate(), 0.1);
        }

        @Test
        @DisplayName("Should return 0 success rate when no transfers")
        void getSuccessRate_WhenNoTransfers_ShouldReturn0() {
            TransferTracker.GlobalStatistics stats = new TransferTracker.GlobalStatistics();
            stats.setTotalTransfers(0);
            stats.setSuccessfulTransfers(0);

            assertEquals(0.0, stats.getSuccessRate());
        }
    }
}
