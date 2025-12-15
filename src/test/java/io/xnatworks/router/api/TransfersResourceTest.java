/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.tracking.TransferTracker;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TransfersResource.
 */
@DisplayName("TransfersResource Tests")
class TransfersResourceTest {

    @TempDir
    Path tempDir;

    private TransferTracker transferTracker;
    private TransfersResource transfersResource;

    @BeforeEach
    void setUp() {
        transferTracker = new TransferTracker(tempDir);
        transfersResource = new TransfersResource(transferTracker);
    }

    @Nested
    @DisplayName("GET /transfers Tests")
    class ListTransfersTests {

        @Test
        @DisplayName("Should return empty list when no transfers")
        void shouldReturnEmptyListWhenNoTransfers() {
            Response response = transfersResource.listTransfers(null, null, 100, 0);

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) result.get("transfers");

            assertTrue(transfers.isEmpty());
            assertEquals(0, result.get("total"));
            assertEquals(0, result.get("offset"));
            assertEquals(100, result.get("limit"));
        }

        @Test
        @DisplayName("Should return 400 for invalid status filter")
        void shouldReturn400ForInvalidStatusFilter() {
            Response response = transfersResource.listTransfers(null, "INVALID_STATUS", 100, 0);

            assertEquals(400, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Invalid status"));
        }

        @Test
        @DisplayName("Should filter by status")
        void shouldFilterByStatus() {
            // Create a completed transfer
            TransferTracker.TransferRecord record = transferTracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);
            transferTracker.startForwarding(record.getId(), Arrays.asList("dest1"));
            transferTracker.updateDestinationResult(record.getId(), "dest1",
                    TransferTracker.DestinationStatus.SUCCESS, "OK", 1000, 10);

            // Filter by COMPLETED status
            Response response = transfersResource.listTransfers(null, "COMPLETED", 100, 0);

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) result.get("transfers");

            assertEquals(1, transfers.size());
            assertEquals("COMPLETED", transfers.get(0).get("status"));
        }

        @Test
        @DisplayName("Should filter by AE Title")
        void shouldFilterByAeTitle() {
            // Create transfers for different AE titles
            TransferTracker.TransferRecord record1 = transferTracker.createTransfer(
                    "AE_ONE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);
            TransferTracker.TransferRecord record2 = transferTracker.createTransfer(
                    "AE_TWO", "1.2.3.4.6", "CALLING_AE", 10, 1024000);

            // Complete both
            transferTracker.startForwarding(record1.getId(), Arrays.asList("dest1"));
            transferTracker.updateDestinationResult(record1.getId(), "dest1",
                    TransferTracker.DestinationStatus.SUCCESS, "OK", 1000, 10);

            transferTracker.startForwarding(record2.getId(), Arrays.asList("dest1"));
            transferTracker.updateDestinationResult(record2.getId(), "dest1",
                    TransferTracker.DestinationStatus.SUCCESS, "OK", 1000, 10);

            // Filter by AE_ONE
            Response response = transfersResource.listTransfers("AE_ONE", null, 100, 0);

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) result.get("transfers");

            assertEquals(1, transfers.size());
            assertEquals("AE_ONE", transfers.get(0).get("aeTitle"));
        }
    }

    @Nested
    @DisplayName("GET /transfers/active Tests")
    class GetActiveTransfersTests {

        @Test
        @DisplayName("Should return empty list when no active transfers")
        void shouldReturnEmptyListWhenNoActiveTransfers() {
            Response response = transfersResource.getActiveTransfers();

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) result.get("transfers");

            assertTrue(transfers.isEmpty());
            assertEquals(0, result.get("count"));
        }

        @Test
        @DisplayName("Should return active transfers")
        void shouldReturnActiveTransfers() {
            // Create an active transfer (not completed)
            TransferTracker.TransferRecord record = transferTracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);
            transferTracker.startProcessing(record.getId());

            Response response = transfersResource.getActiveTransfers();

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) result.get("transfers");

            assertEquals(1, transfers.size());
            assertEquals(1, result.get("count"));
            assertEquals("PROCESSING", transfers.get(0).get("status"));
        }
    }

    @Nested
    @DisplayName("GET /transfers/{transferId} Tests")
    class GetTransferTests {

        @Test
        @DisplayName("Should return 404 for unknown transfer ID")
        void shouldReturn404ForUnknownTransferId() {
            Response response = transfersResource.getTransfer("unknown-id");

            assertEquals(404, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("Transfer not found"));
        }

        @Test
        @DisplayName("Should return transfer details")
        void shouldReturnTransferDetails() {
            TransferTracker.TransferRecord record = transferTracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);

            Response response = transfersResource.getTransfer(record.getId());

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> transfer = (Map<String, Object>) response.getEntity();
            assertEquals(record.getId(), transfer.get("transferId"));
            assertEquals("TEST_AE", transfer.get("aeTitle"));
            assertEquals("1.2.3.4.5", transfer.get("studyUid"));
            assertEquals("CALLING_AE", transfer.get("callingAeTitle"));
            assertEquals(10, ((Number) transfer.get("fileCount")).intValue());
            assertEquals(1024000L, ((Number) transfer.get("totalSize")).longValue());
            assertEquals("RECEIVED", transfer.get("status"));
        }
    }

    @Nested
    @DisplayName("GET /transfers/statistics Tests")
    class GetGlobalStatisticsTests {

        @Test
        @DisplayName("Should return global statistics")
        void shouldReturnGlobalStatistics() {
            Response response = transfersResource.getGlobalStatistics();

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> stats = (Map<String, Object>) response.getEntity();
            assertNotNull(stats.get("totalTransfers"));
            assertNotNull(stats.get("successfulTransfers"));
            assertNotNull(stats.get("failedTransfers"));
            assertNotNull(stats.get("activeTransfers"));
            assertNotNull(stats.get("successRate"));
        }
    }

    @Nested
    @DisplayName("GET /transfers/failed Tests")
    class GetFailedTransfersTests {

        @Test
        @DisplayName("Should return empty list when no failed transfers")
        void shouldReturnEmptyListWhenNoFailedTransfers() {
            Response response = transfersResource.getFailedTransfers(null, 50);

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) result.get("transfers");

            assertTrue(transfers.isEmpty());
            assertEquals(0, result.get("count"));
        }

        @Test
        @DisplayName("Should return failed transfers")
        void shouldReturnFailedTransfers() {
            // Create and fail a transfer
            TransferTracker.TransferRecord record = transferTracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);
            transferTracker.failTransfer(record.getId(), "Test failure reason");

            Response response = transfersResource.getFailedTransfers(null, 50);

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) result.get("transfers");

            assertEquals(1, transfers.size());
            assertEquals(1, result.get("count"));
            assertEquals("FAILED", transfers.get(0).get("status"));
            assertEquals("Test failure reason", transfers.get(0).get("errorMessage"));
        }
    }

    @Nested
    @DisplayName("POST /transfers/{transferId}/retry Tests")
    class RetryTransferTests {

        @Test
        @DisplayName("Should return 404 for unknown transfer ID")
        void shouldReturn404ForUnknownTransferId() {
            Response response = transfersResource.retryTransfer("unknown-id");

            assertEquals(404, response.getStatus());
        }

        @Test
        @DisplayName("Should return 400 when retrying non-failed transfer")
        void shouldReturn400WhenRetryingNonFailedTransfer() {
            // Create an active transfer (not failed)
            TransferTracker.TransferRecord record = transferTracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);

            Response response = transfersResource.retryTransfer(record.getId());

            assertEquals(400, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) response.getEntity();
            assertTrue(error.get("error").toString().contains("only retry failed transfers"));
        }
    }

    @Nested
    @DisplayName("GET /transfers/by-study/{studyUid} Tests")
    class GetTransfersByStudyTests {

        @Test
        @DisplayName("Should return empty list for unknown study UID")
        void shouldReturnEmptyListForUnknownStudyUid() {
            Response response = transfersResource.getTransfersByStudy("unknown-study-uid");

            assertEquals(200, response.getStatus());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getEntity();
            assertEquals("unknown-study-uid", result.get("studyUid"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transfers = (List<Map<String, Object>>) result.get("transfers");
            assertTrue(transfers.isEmpty());
        }
    }

    @Nested
    @DisplayName("Transfer Record Mapping Tests")
    class TransferRecordMappingTests {

        @Test
        @DisplayName("Should include all transfer fields in response")
        void shouldIncludeAllTransferFields() {
            TransferTracker.TransferRecord record = transferTracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 100, 10240000);
            transferTracker.startProcessing(record.getId());
            transferTracker.updateProgress(record.getId(), 50, 5120000);

            Response response = transfersResource.getTransfer(record.getId());

            @SuppressWarnings("unchecked")
            Map<String, Object> transfer = (Map<String, Object>) response.getEntity();

            // Verify all expected fields
            assertNotNull(transfer.get("transferId"));
            assertNotNull(transfer.get("aeTitle"));
            assertNotNull(transfer.get("studyUid"));
            assertNotNull(transfer.get("callingAeTitle"));
            assertNotNull(transfer.get("fileCount"));
            assertNotNull(transfer.get("totalSize"));
            assertNotNull(transfer.get("status"));
            assertNotNull(transfer.get("receivedAt"));
            assertNotNull(transfer.get("processingStartedAt"));
            assertNotNull(transfer.get("filesProcessed"));
            assertNotNull(transfer.get("bytesProcessed"));
            assertNotNull(transfer.get("progressPercent"));
            assertNotNull(transfer.get("destinations"));

            // Verify progress values
            assertEquals(50, ((Number) transfer.get("filesProcessed")).intValue());
            assertEquals(5120000L, ((Number) transfer.get("bytesProcessed")).longValue());
        }

        @Test
        @DisplayName("Should include destination results")
        void shouldIncludeDestinationResults() {
            TransferTracker.TransferRecord record = transferTracker.createTransfer(
                    "TEST_AE", "1.2.3.4.5", "CALLING_AE", 10, 1024000);
            transferTracker.startForwarding(record.getId(), Arrays.asList("dest1", "dest2"));
            transferTracker.updateDestinationResult(record.getId(), "dest1",
                    TransferTracker.DestinationStatus.SUCCESS, "Upload complete", 5000, 10);

            Response response = transfersResource.getTransfer(record.getId());

            @SuppressWarnings("unchecked")
            Map<String, Object> transfer = (Map<String, Object>) response.getEntity();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> destinations = (List<Map<String, Object>>) transfer.get("destinations");

            assertEquals(2, destinations.size());

            // Find the completed destination
            Map<String, Object> completedDest = destinations.stream()
                    .filter(d -> "dest1".equals(d.get("destination")))
                    .findFirst()
                    .orElse(null);

            assertNotNull(completedDest);
            assertEquals("SUCCESS", completedDest.get("status"));
            assertEquals("Upload complete", completedDest.get("message"));
            assertEquals(5000L, completedDest.get("durationMs"));
            assertEquals(10, completedDest.get("filesTransferred"));
        }
    }
}
