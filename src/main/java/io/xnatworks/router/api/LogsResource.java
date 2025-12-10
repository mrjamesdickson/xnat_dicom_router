/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.api;

import io.xnatworks.router.config.AppConfig;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * REST API for viewing and tailing log files.
 */
@jakarta.ws.rs.Path("/logs")
@Produces(MediaType.APPLICATION_JSON)
public class LogsResource {
    private static final Logger log = LoggerFactory.getLogger(LogsResource.class);
    private static final int DEFAULT_LINES = 100;
    private static final int MAX_LINES = 1000;

    private final AppConfig config;

    public LogsResource(AppConfig config) {
        this.config = config;
    }

    /**
     * List available log files.
     */
    @GET
    public Response listLogs() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> logFiles = new ArrayList<>();

        // List route logs
        Path dataDir = Paths.get(config.getDataDirectory());
        if (Files.exists(dataDir)) {
            try (Stream<Path> routes = Files.list(dataDir)) {
                routes.filter(Files::isDirectory)
                      .filter(p -> !p.getFileName().toString().startsWith("."))
                      .filter(p -> !p.getFileName().toString().equals("scripts"))
                      .forEach(routePath -> {
                          Path logsDir = routePath.resolve("logs");
                          if (Files.exists(logsDir)) {
                              try (Stream<Path> files = Files.list(logsDir)) {
                                  files.filter(Files::isRegularFile)
                                       .filter(p -> p.toString().endsWith(".log") || p.toString().endsWith(".csv"))
                                       .forEach(logPath -> {
                                           try {
                                               Map<String, Object> logInfo = new LinkedHashMap<>();
                                               String routeName = routePath.getFileName().toString();
                                               String fileName = logPath.getFileName().toString();
                                               logInfo.put("route", routeName);
                                               logInfo.put("name", fileName);
                                               logInfo.put("path", routeName + "/logs/" + fileName);
                                               logInfo.put("size", Files.size(logPath));
                                               logInfo.put("sizeFormatted", formatSize(Files.size(logPath)));
                                               logInfo.put("modified", Files.getLastModifiedTime(logPath).toInstant().toString());
                                               logFiles.add(logInfo);
                                           } catch (IOException e) {
                                               log.debug("Error reading log file: {}", e.getMessage());
                                           }
                                       });
                              } catch (IOException e) {
                                  log.debug("Error listing logs directory: {}", e.getMessage());
                              }
                          }
                      });
            } catch (IOException e) {
                log.error("Error listing data directory: {}", e.getMessage());
            }
        }

        result.put("logs", logFiles);
        result.put("count", logFiles.size());

        return Response.ok(result).build();
    }

    /**
     * Get the last N lines of a log file (tail).
     */
    @GET
    @jakarta.ws.rs.Path("/tail/{route}/{file}")
    public Response tailLog(@PathParam("route") String route,
                            @PathParam("file") String file,
                            @QueryParam("lines") @DefaultValue("100") int lines,
                            @QueryParam("offset") @DefaultValue("0") long offset) {
        // Security: validate path components
        if (route.contains("..") || file.contains("..") || route.contains("/") || file.contains("/")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid path"))
                    .build();
        }

        Path logPath = Paths.get(config.getDataDirectory(), route, "logs", file);

        if (!Files.exists(logPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Log file not found"))
                    .build();
        }

        // Limit lines
        if (lines > MAX_LINES) {
            lines = MAX_LINES;
        }
        if (lines < 1) {
            lines = DEFAULT_LINES;
        }

        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("route", route);
            result.put("file", file);

            long fileSize = Files.size(logPath);
            result.put("size", fileSize);

            // If offset is provided and less than file size, read from that point
            // Otherwise, tail the last N lines
            List<String> content;
            long newOffset;

            if (offset > 0 && offset < fileSize) {
                // Read from offset (for live following)
                content = readFromOffset(logPath, offset, lines);
                newOffset = fileSize;
            } else {
                // Tail the last N lines
                content = tailFile(logPath, lines);
                newOffset = fileSize;
            }

            result.put("lines", content);
            result.put("lineCount", content.size());
            result.put("offset", newOffset);

            return Response.ok(result).build();
        } catch (IOException e) {
            log.error("Error reading log file: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to read log: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get the content of a log file.
     */
    @GET
    @jakarta.ws.rs.Path("/content/{route}/{file}")
    public Response getLogContent(@PathParam("route") String route,
                                   @PathParam("file") String file,
                                   @QueryParam("start") @DefaultValue("0") int start,
                                   @QueryParam("limit") @DefaultValue("500") int limit) {
        // Security: validate path components
        if (route.contains("..") || file.contains("..") || route.contains("/") || file.contains("/")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid path"))
                    .build();
        }

        Path logPath = Paths.get(config.getDataDirectory(), route, "logs", file);

        if (!Files.exists(logPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Log file not found"))
                    .build();
        }

        // Limit
        if (limit > MAX_LINES) {
            limit = MAX_LINES;
        }

        try {
            List<String> allLines = Files.readAllLines(logPath);
            int totalLines = allLines.size();

            // Apply pagination
            int endIndex = Math.min(start + limit, totalLines);
            List<String> content = start < totalLines
                ? allLines.subList(start, endIndex)
                : List.of();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("route", route);
            result.put("file", file);
            result.put("lines", content);
            result.put("start", start);
            result.put("count", content.size());
            result.put("totalLines", totalLines);
            result.put("hasMore", endIndex < totalLines);

            return Response.ok(result).build();
        } catch (IOException e) {
            log.error("Error reading log file: {}", e.getMessage());
            return Response.serverError()
                    .entity(Map.of("error", "Failed to read log: " + e.getMessage()))
                    .build();
        }
    }

    // Helper methods

    private List<String> tailFile(Path filePath, int lines) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) {
                return List.of();
            }

            List<String> result = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            long pos = fileLength - 1;
            int lineCount = 0;

            // Read backwards
            while (pos >= 0 && lineCount < lines) {
                raf.seek(pos);
                char c = (char) raf.read();

                if (c == '\n') {
                    if (sb.length() > 0) {
                        result.add(0, sb.reverse().toString());
                        sb = new StringBuilder();
                        lineCount++;
                    }
                } else {
                    sb.append(c);
                }
                pos--;
            }

            // Add the first line if it doesn't end with newline
            if (sb.length() > 0 && lineCount < lines) {
                result.add(0, sb.reverse().toString());
            }

            return result;
        }
    }

    private List<String> readFromOffset(Path filePath, long offset, int maxLines) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            raf.seek(offset);
            List<String> lines = new ArrayList<>();
            String line;
            int count = 0;

            while ((line = raf.readLine()) != null && count < maxLines) {
                lines.add(line);
                count++;
            }

            return lines;
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
}
