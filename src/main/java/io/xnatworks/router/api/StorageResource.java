/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
// Using fully qualified names to avoid conflict with java.nio.file.Path
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * REST API for browsing storage/cache directories.
 */
@jakarta.ws.rs.Path("/storage")
@Produces(MediaType.APPLICATION_JSON)
public class StorageResource {
    private static final Logger log = LoggerFactory.getLogger(StorageResource.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final AppConfig config;

    public StorageResource(AppConfig config) {
        this.config = config;
    }

    /**
     * Get overview of all storage directories with statistics.
     */
    @GET
    public Response getStorageOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();

        String dataDir = config.getDataDirectory();
        overview.put("dataDirectory", dataDir);

        // Get list of route directories (each route has its own folder)
        List<Map<String, Object>> routes = new ArrayList<>();
        Path dataPath = Paths.get(dataDir);

        if (Files.exists(dataPath) && Files.isDirectory(dataPath)) {
            try (Stream<Path> stream = Files.list(dataPath)) {
                stream.filter(Files::isDirectory)
                      .filter(p -> !p.getFileName().toString().startsWith("."))
                      .filter(p -> !p.getFileName().toString().equals("scripts"))
                      .forEach(routePath -> {
                          Map<String, Object> routeInfo = new LinkedHashMap<>();
                          String routeName = routePath.getFileName().toString();
                          routeInfo.put("name", routeName);
                          routeInfo.put("path", routePath.toString());

                          // Count files in each subdirectory
                          routeInfo.put("incoming", countFilesInDir(routePath.resolve("incoming")));
                          routeInfo.put("processing", countFilesInDir(routePath.resolve("processing")));
                          routeInfo.put("completed", countFilesInDir(routePath.resolve("completed")));
                          routeInfo.put("failed", countFilesInDir(routePath.resolve("failed")));

                          // Get disk usage
                          routeInfo.put("totalSize", formatSize(getDirSize(routePath)));
                          routeInfo.put("totalSizeBytes", getDirSize(routePath));

                          routes.add(routeInfo);
                      });
            } catch (IOException e) {
                log.error("Error listing data directory: {}", e.getMessage());
            }
        }

        overview.put("routes", routes);

        // Total statistics
        long totalFiles = routes.stream()
                .mapToLong(r -> ((Number) r.get("incoming")).longValue() +
                               ((Number) r.get("processing")).longValue() +
                               ((Number) r.get("completed")).longValue() +
                               ((Number) r.get("failed")).longValue())
                .sum();
        long totalSize = routes.stream()
                .mapToLong(r -> ((Number) r.get("totalSizeBytes")).longValue())
                .sum();

        overview.put("totalFiles", totalFiles);
        overview.put("totalSize", formatSize(totalSize));
        overview.put("totalSizeBytes", totalSize);

        return Response.ok(overview).build();
    }

    /**
     * Get contents of a specific route's storage directory.
     */
    @GET
    @jakarta.ws.rs.Path("/routes/{routeName}")
    public Response getRouteStorage(@PathParam("routeName") String routeName) {
        Path routePath = Paths.get(config.getDataDirectory(), routeName);

        if (!Files.exists(routePath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Route storage not found: " + routeName))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", routeName);
        result.put("path", routePath.toString());

        // List all subdirectories and their contents
        for (String subDir : Arrays.asList("incoming", "processing", "completed", "failed", "logs")) {
            Path subPath = routePath.resolve(subDir);
            if (Files.exists(subPath)) {
                result.put(subDir, listDirectory(subPath, true));
            } else {
                result.put(subDir, Map.of("exists", false, "items", Collections.emptyList()));
            }
        }

        return Response.ok(result).build();
    }

    /**
     * Browse a specific path within the data directory.
     */
    @GET
    @jakarta.ws.rs.Path("/browse")
    public Response browsePath(@QueryParam("path") String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            relativePath = "";
        }

        // Security: Ensure path stays within data directory
        Path basePath = Paths.get(config.getDataDirectory()).toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(relativePath).normalize();

        if (!targetPath.startsWith(basePath)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "Access denied: Path outside data directory"))
                    .build();
        }

        if (!Files.exists(targetPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Path not found: " + relativePath))
                    .build();
        }

        Map<String, Object> result = listDirectory(targetPath, false);
        result.put("currentPath", relativePath);
        result.put("absolutePath", targetPath.toString());

        // Add breadcrumbs for navigation
        List<Map<String, String>> breadcrumbs = new ArrayList<>();
        breadcrumbs.add(Map.of("name", "root", "path", ""));

        if (!relativePath.isEmpty()) {
            String[] parts = relativePath.split("/");
            StringBuilder pathBuilder = new StringBuilder();
            for (String part : parts) {
                if (!part.isEmpty()) {
                    if (pathBuilder.length() > 0) {
                        pathBuilder.append("/");
                    }
                    pathBuilder.append(part);
                    breadcrumbs.add(Map.of("name", part, "path", pathBuilder.toString()));
                }
            }
        }
        result.put("breadcrumbs", breadcrumbs);

        return Response.ok(result).build();
    }

    /**
     * Get details of a specific study directory.
     */
    @GET
    @jakarta.ws.rs.Path("/routes/{routeName}/studies/{studyFolder}")
    public Response getStudyDetails(@PathParam("routeName") String routeName,
                                    @PathParam("studyFolder") String studyFolder,
                                    @QueryParam("status") @DefaultValue("incoming") String status) {
        Path studyPath = Paths.get(config.getDataDirectory(), routeName, status, studyFolder);

        if (!Files.exists(studyPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Study folder not found"))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", routeName);
        result.put("status", status);
        result.put("studyFolder", studyFolder);
        result.put("path", studyPath.toString());

        // List DICOM files in the study
        List<Map<String, Object>> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(studyPath)) {
            stream.filter(Files::isRegularFile)
                  .forEach(filePath -> {
                      try {
                          BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                          Map<String, Object> fileInfo = new LinkedHashMap<>();
                          fileInfo.put("name", filePath.getFileName().toString());
                          fileInfo.put("size", attrs.size());
                          fileInfo.put("sizeFormatted", formatSize(attrs.size()));
                          fileInfo.put("modified", DATE_FORMAT.format(attrs.lastModifiedTime().toInstant()));
                          files.add(fileInfo);
                      } catch (IOException e) {
                          log.debug("Error reading file attributes: {}", e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.error("Error listing study directory: {}", e.getMessage());
        }

        result.put("files", files);
        result.put("fileCount", files.size());
        result.put("totalSize", formatSize(files.stream().mapToLong(f -> ((Number) f.get("size")).longValue()).sum()));

        return Response.ok(result).build();
    }

    /**
     * Delete a study folder (only from failed or completed directories).
     */
    @DELETE
    @jakarta.ws.rs.Path("/routes/{routeName}/studies/{studyFolder}")
    public Response deleteStudy(@PathParam("routeName") String routeName,
                                @PathParam("studyFolder") String studyFolder,
                                @QueryParam("status") String status) {
        // Only allow deletion from failed or completed directories
        if (status == null || (!status.equals("failed") && !status.equals("completed"))) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Can only delete from 'failed' or 'completed' directories"))
                    .build();
        }

        Path studyPath = Paths.get(config.getDataDirectory(), routeName, status, studyFolder);

        if (!Files.exists(studyPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Study folder not found"))
                    .build();
        }

        try {
            // Delete all files in the directory
            Files.walk(studyPath)
                 .sorted(Comparator.reverseOrder())
                 .forEach(path -> {
                     try {
                         Files.delete(path);
                     } catch (IOException e) {
                         log.error("Error deleting {}: {}", path, e.getMessage());
                     }
                 });

            log.info("Deleted study folder: {}/{}/{}", routeName, status, studyFolder);
            return Response.ok(Map.of("message", "Study deleted successfully")).build();
        } catch (IOException e) {
            log.error("Error deleting study: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to delete study: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Retry a failed study by moving it back to incoming.
     * Updates retry metadata to track retry count and history.
     */
    @POST
    @jakarta.ws.rs.Path("/routes/{routeName}/studies/{studyFolder}/retry")
    public Response retryStudy(@PathParam("routeName") String routeName,
                               @PathParam("studyFolder") String studyFolder) {
        Path failedPath = Paths.get(config.getDataDirectory(), routeName, "failed", studyFolder);
        Path incomingPath = Paths.get(config.getDataDirectory(), routeName, "incoming", studyFolder);

        if (!Files.exists(failedPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Failed study folder not found"))
                    .build();
        }

        if (Files.exists(incomingPath)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Study already exists in incoming directory"))
                    .build();
        }

        try {
            // Update retry metadata before moving
            updateRetryMetadata(failedPath);

            Files.move(failedPath, incomingPath, StandardCopyOption.ATOMIC_MOVE);
            log.info("Moved study from failed to incoming: {}/{}", routeName, studyFolder);
            return Response.ok(Map.of("message", "Study queued for retry")).build();
        } catch (IOException e) {
            log.error("Error moving study to retry: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to retry study: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get list of failed studies for a route, including retry metadata.
     */
    @GET
    @jakarta.ws.rs.Path("/routes/{routeName}/failed")
    public Response getFailedStudies(@PathParam("routeName") String routeName) {
        Path failedDir = Paths.get(config.getDataDirectory(), routeName, "failed");

        if (!Files.exists(failedDir)) {
            return Response.ok(Map.of(
                    "route", routeName,
                    "failedCount", 0,
                    "studies", Collections.emptyList()
            )).build();
        }

        List<Map<String, Object>> studies = new ArrayList<>();

        try (Stream<Path> stream = Files.list(failedDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> p.getFileName().toString().startsWith("study_"))
                  .forEach(studyPath -> {
                      try {
                          BasicFileAttributes attrs = Files.readAttributes(studyPath, BasicFileAttributes.class);
                          Map<String, Object> studyInfo = new LinkedHashMap<>();
                          studyInfo.put("name", studyPath.getFileName().toString());
                          studyInfo.put("fileCount", countFilesInDir(studyPath));
                          studyInfo.put("totalSize", getDirSize(studyPath));
                          studyInfo.put("totalSizeFormatted", formatSize(getDirSize(studyPath)));
                          studyInfo.put("modified", DATE_FORMAT.format(attrs.lastModifiedTime().toInstant()));

                          // Read failure reason if available
                          Path failureReasonFile = studyPath.resolve("failure_reason.txt");
                          if (Files.exists(failureReasonFile)) {
                              try {
                                  studyInfo.put("failureReason", Files.readString(failureReasonFile).trim());
                              } catch (IOException e) {
                                  studyInfo.put("failureReason", "Unknown");
                              }
                          }

                          // Read retry metadata
                          Map<String, Object> retryMetadata = readRetryMetadata(studyPath);
                          studyInfo.put("retryCount", retryMetadata.get("retryCount"));
                          studyInfo.put("lastRetryAt", retryMetadata.get("lastRetryAt"));
                          studyInfo.put("retryHistory", retryMetadata.get("retryHistory"));

                          studies.add(studyInfo);
                      } catch (IOException e) {
                          log.debug("Error reading study info: {}", e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.error("Error listing failed directory: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to list failed studies: " + e.getMessage()))
                    .build();
        }

        // Sort by modification time descending (most recent first)
        studies.sort((a, b) -> {
            String aTime = (String) a.get("modified");
            String bTime = (String) b.get("modified");
            return bTime.compareTo(aTime);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("route", routeName);
        result.put("failedCount", studies.size());
        result.put("studies", studies);

        return Response.ok(result).build();
    }

    /**
     * Reprocess a completed study by moving it back to incoming.
     * Useful when the study was uploaded with wrong settings (e.g., missing project).
     */
    @POST
    @jakarta.ws.rs.Path("/routes/{routeName}/studies/{studyFolder}/reprocess")
    public Response reprocessStudy(@PathParam("routeName") String routeName,
                                   @PathParam("studyFolder") String studyFolder) {
        Path completedPath = Paths.get(config.getDataDirectory(), routeName, "completed", studyFolder);
        Path incomingPath = Paths.get(config.getDataDirectory(), routeName, "incoming", studyFolder);

        if (!Files.exists(completedPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Completed study folder not found"))
                    .build();
        }

        if (Files.exists(incomingPath)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Study already exists in incoming directory"))
                    .build();
        }

        try {
            Files.move(completedPath, incomingPath, StandardCopyOption.ATOMIC_MOVE);
            log.info("Moved study from completed to incoming for reprocessing: {}/{}", routeName, studyFolder);
            return Response.ok(Map.of("message", "Study queued for reprocessing")).build();
        } catch (IOException e) {
            log.error("Error moving study for reprocessing: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to reprocess study: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Retry all failed studies for a route by moving them back to incoming.
     * Updates retry metadata to track retry count and history.
     */
    @POST
    @jakarta.ws.rs.Path("/routes/{routeName}/retry-all")
    public Response retryAllFailed(@PathParam("routeName") String routeName) {
        Path failedDir = Paths.get(config.getDataDirectory(), routeName, "failed");
        Path incomingDir = Paths.get(config.getDataDirectory(), routeName, "incoming");

        if (!Files.exists(failedDir)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Failed directory not found for route: " + routeName))
                    .build();
        }

        List<String> moved = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            // Ensure incoming directory exists
            Files.createDirectories(incomingDir);

            try (Stream<Path> stream = Files.list(failedDir)) {
                stream.filter(Files::isDirectory)
                      .filter(p -> p.getFileName().toString().startsWith("study_"))
                      .forEach(studyPath -> {
                          String studyFolder = studyPath.getFileName().toString();
                          Path targetPath = incomingDir.resolve(studyFolder);

                          if (Files.exists(targetPath)) {
                              skipped.add(studyFolder);
                              return;
                          }

                          try {
                              // Update retry metadata before moving
                              updateRetryMetadata(studyPath);

                              Files.move(studyPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
                              moved.add(studyFolder);
                              log.info("Moved study from failed to incoming for retry: {}/{}", routeName, studyFolder);
                          } catch (IOException e) {
                              errors.add(studyFolder + ": " + e.getMessage());
                              log.error("Error moving study {}: {}", studyFolder, e.getMessage());
                          }
                      });
            }
        } catch (IOException e) {
            log.error("Error processing failed directory: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to process failed directory: " + e.getMessage()))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moved", moved.size());
        result.put("skipped", skipped.size());
        result.put("errors", errors.size());
        result.put("movedStudies", moved);
        if (!skipped.isEmpty()) {
            result.put("skippedStudies", skipped);
        }
        if (!errors.isEmpty()) {
            result.put("errorDetails", errors);
        }
        result.put("message", String.format("Moved %d failed studies for retry (%d skipped, %d errors)",
                moved.size(), skipped.size(), errors.size()));

        return Response.ok(result).build();
    }

    /**
     * Reprocess all completed studies for a route by moving them back to incoming.
     */
    @POST
    @jakarta.ws.rs.Path("/routes/{routeName}/reprocess-all")
    public Response reprocessAllCompleted(@PathParam("routeName") String routeName) {
        Path completedDir = Paths.get(config.getDataDirectory(), routeName, "completed");
        Path incomingDir = Paths.get(config.getDataDirectory(), routeName, "incoming");

        if (!Files.exists(completedDir)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Completed directory not found for route: " + routeName))
                    .build();
        }

        List<String> moved = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try (Stream<Path> stream = Files.list(completedDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> p.getFileName().toString().startsWith("study_"))
                  .forEach(studyPath -> {
                      String studyFolder = studyPath.getFileName().toString();
                      Path targetPath = incomingDir.resolve(studyFolder);

                      if (Files.exists(targetPath)) {
                          skipped.add(studyFolder);
                          return;
                      }

                      try {
                          Files.move(studyPath, targetPath, StandardCopyOption.ATOMIC_MOVE);
                          moved.add(studyFolder);
                          log.info("Moved study from completed to incoming: {}/{}", routeName, studyFolder);
                      } catch (IOException e) {
                          errors.add(studyFolder + ": " + e.getMessage());
                          log.error("Error moving study {}: {}", studyFolder, e.getMessage());
                      }
                  });
        } catch (IOException e) {
            log.error("Error listing completed directory: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to list completed directory: " + e.getMessage()))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("moved", moved.size());
        result.put("skipped", skipped.size());
        result.put("errors", errors.size());
        result.put("movedStudies", moved);
        if (!skipped.isEmpty()) {
            result.put("skippedStudies", skipped);
        }
        if (!errors.isEmpty()) {
            result.put("errorDetails", errors);
        }
        result.put("message", String.format("Moved %d studies for reprocessing (%d skipped, %d errors)",
                moved.size(), skipped.size(), errors.size()));

        return Response.ok(result).build();
    }

    // Helper methods

    private Map<String, Object> listDirectory(Path dirPath, boolean includeSubdirs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", Files.exists(dirPath));

        if (!Files.exists(dirPath)) {
            result.put("items", Collections.emptyList());
            return result;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dirPath)) {
            stream.forEach(path -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", path.getFileName().toString());
                    item.put("isDirectory", Files.isDirectory(path));
                    item.put("size", attrs.size());
                    item.put("sizeFormatted", formatSize(attrs.size()));
                    item.put("modified", DATE_FORMAT.format(attrs.lastModifiedTime().toInstant()));

                    if (Files.isDirectory(path)) {
                        item.put("fileCount", countFilesInDir(path));
                        if (includeSubdirs) {
                            item.put("totalSize", getDirSize(path));
                            item.put("totalSizeFormatted", formatSize(getDirSize(path)));
                        }
                    }

                    items.add(item);
                } catch (IOException e) {
                    log.debug("Error reading attributes for {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("Error listing directory: {}", e.getMessage());
        }

        // Sort: directories first, then by name
        items.sort((a, b) -> {
            boolean aDir = (boolean) a.get("isDirectory");
            boolean bDir = (boolean) b.get("isDirectory");
            if (aDir != bDir) {
                return aDir ? -1 : 1;
            }
            return ((String) a.get("name")).compareTo((String) b.get("name"));
        });

        result.put("items", items);
        result.put("itemCount", items.size());

        return result;
    }

    private long countFilesInDir(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private long getDirSize(Path dir) {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                        .mapToLong(p -> {
                            try {
                                return Files.size(p);
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    private static final String RETRY_METADATA_FILE = "retry_metadata.json";

    /**
     * Update retry metadata for a study folder (increment count and add timestamp).
     */
    private void updateRetryMetadata(Path studyPath) throws IOException {
        Path metadataFile = studyPath.resolve(RETRY_METADATA_FILE);

        int retryCount = 1;
        List<String> retryHistory = new ArrayList<>();
        String lastError = null;

        // Read existing metadata if present
        if (Files.exists(metadataFile)) {
            try {
                String content = Files.readString(metadataFile);
                // Simple JSON parsing
                if (content.contains("\"retryCount\":")) {
                    String countStr = content.split("\"retryCount\":")[1].split("[,}]")[0].trim();
                    retryCount = Integer.parseInt(countStr) + 1;
                }
                if (content.contains("\"retryHistory\":")) {
                    String historyStr = content.split("\"retryHistory\":\\[")[1].split("]")[0];
                    if (!historyStr.trim().isEmpty()) {
                        for (String ts : historyStr.split(",")) {
                            retryHistory.add(ts.trim().replace("\"", ""));
                        }
                    }
                }
                if (content.contains("\"lastError\":")) {
                    String errorPart = content.split("\"lastError\":\"")[1];
                    lastError = errorPart.split("\"")[0];
                }
            } catch (Exception e) {
                log.warn("Failed to parse existing retry metadata, starting fresh: {}", e.getMessage());
                retryCount = 1;
                retryHistory.clear();
            }
        }

        // Read failure reason if available
        Path failureReasonFile = studyPath.resolve("failure_reason.txt");
        if (Files.exists(failureReasonFile)) {
            try {
                lastError = Files.readString(failureReasonFile).trim().replace("\"", "'").replace("\n", " ");
            } catch (IOException e) {
                log.debug("Failed to read failure reason: {}", e.getMessage());
            }
        }

        // Add current timestamp to history
        String now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        retryHistory.add(now);

        // Write updated metadata
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"retryCount\": ").append(retryCount).append(",\n");
        json.append("  \"lastRetryAt\": \"").append(now).append("\",\n");
        json.append("  \"retryHistory\": [");
        for (int i = 0; i < retryHistory.size(); i++) {
            if (i > 0) json.append(", ");
            json.append("\"").append(retryHistory.get(i)).append("\"");
        }
        json.append("],\n");
        if (lastError != null) {
            json.append("  \"lastError\": \"").append(lastError).append("\"\n");
        } else {
            json.append("  \"lastError\": null\n");
        }
        json.append("}\n");

        Files.writeString(metadataFile, json.toString());
        log.debug("Updated retry metadata for {}: retry #{}", studyPath.getFileName(), retryCount);
    }

    /**
     * Read retry metadata for a study folder.
     */
    private Map<String, Object> readRetryMetadata(Path studyPath) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retryCount", 0);
        metadata.put("lastRetryAt", null);
        metadata.put("retryHistory", Collections.emptyList());
        metadata.put("lastError", null);

        Path metadataFile = studyPath.resolve(RETRY_METADATA_FILE);
        if (!Files.exists(metadataFile)) {
            return metadata;
        }

        try {
            String content = Files.readString(metadataFile);

            if (content.contains("\"retryCount\":")) {
                String countStr = content.split("\"retryCount\":")[1].split("[,}]")[0].trim();
                metadata.put("retryCount", Integer.parseInt(countStr));
            }
            if (content.contains("\"lastRetryAt\":\"")) {
                String lastRetry = content.split("\"lastRetryAt\":\"")[1].split("\"")[0];
                metadata.put("lastRetryAt", lastRetry);
            }
            if (content.contains("\"retryHistory\":")) {
                String historyStr = content.split("\"retryHistory\":\\[")[1].split("]")[0];
                List<String> history = new ArrayList<>();
                if (!historyStr.trim().isEmpty()) {
                    for (String ts : historyStr.split(",")) {
                        history.add(ts.trim().replace("\"", ""));
                    }
                }
                metadata.put("retryHistory", history);
            }
            if (content.contains("\"lastError\":\"")) {
                String error = content.split("\"lastError\":\"")[1].split("\"")[0];
                metadata.put("lastError", error);
            }
        } catch (Exception e) {
            log.debug("Failed to read retry metadata: {}", e.getMessage());
        }

        return metadata;
    }
}
