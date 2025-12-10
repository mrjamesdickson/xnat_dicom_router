/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.xnat;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.xnatworks.router.config.AppConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * XNAT REST API client for authentication, fetching anon scripts, and uploading data.
 * Each instance connects to a single XNAT endpoint.
 */
public class XnatClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(XnatClient.class);

    private final String endpointName;
    private final String baseUrl;
    private final String username;
    private final String password;
    private final OkHttpClient httpClient;
    private String jsessionId;

    /**
     * Create client from named endpoint configuration.
     */
    public XnatClient(String endpointName, AppConfig.XnatEndpoint endpoint) {
        this.endpointName = endpointName;
        this.baseUrl = endpoint.getUrl().replaceAll("/$", "");
        this.username = endpoint.getUsername();
        this.password = endpoint.getPassword();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();

        log.debug("Created XnatClient for endpoint '{}': {}", endpointName, baseUrl);
    }

    /**
     * Create client from legacy config (backward compatibility).
     */
    public XnatClient(AppConfig.XnatConfig config) {
        this.endpointName = "default";
        this.baseUrl = config.getUrl().replaceAll("/$", "");
        this.username = config.getUsername();
        this.password = config.getPassword();

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public String getEndpointName() {
        return endpointName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Check if XNAT is available.
     * Note: This method does NOT persist the session - it's only for health checking.
     */
    public boolean isAvailable() {
        try {
            // Use a separate request to test authentication without affecting the main session
            String credential = Credentials.basic(username, password);
            Request request = new Request.Builder()
                    .url(baseUrl + "/data/JSESSION")
                    .post(RequestBody.create("", null))
                    .header("Authorization", credential)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String testSession = response.body().string().trim();
                    // Immediately invalidate the test session
                    invalidateSession(testSession);
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("XNAT health check failed for '{}': {}", endpointName, e.getMessage());
        }
        return false;
    }

    /**
     * Authenticate and get JSESSION token.
     */
    public String authenticate() throws IOException {
        String credential = Credentials.basic(username, password);

        Request request = new Request.Builder()
                .url(baseUrl + "/data/JSESSION")
                .post(RequestBody.create("", null))
                .header("Authorization", credential)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                jsessionId = response.body().string().trim();
                log.debug("Authenticated with XNAT '{}', JSESSION: {}...",
                        endpointName, jsessionId.substring(0, Math.min(8, jsessionId.length())));
                return jsessionId;
            } else {
                throw new IOException("Authentication failed for endpoint '" + endpointName + "': HTTP " + response.code());
            }
        }
    }

    /**
     * Invalidate session.
     */
    public void invalidateSession(String sessionId) {
        if (sessionId == null) return;

        Request request = new Request.Builder()
                .url(baseUrl + "/data/JSESSION")
                .delete()
                .header("Cookie", "JSESSIONID=" + sessionId)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            log.debug("Session invalidated for endpoint '{}'", endpointName);
        } catch (Exception e) {
            log.debug("Failed to invalidate session for '{}': {}", endpointName, e.getMessage());
        }
    }

    /**
     * Fetch site-wide anonymization script.
     */
    public String fetchSiteAnonScript() throws IOException {
        ensureAuthenticated();

        Request request = new Request.Builder()
                .url(baseUrl + "/data/config/anon/script?format=json")
                .get()
                .header("Cookie", "JSESSIONID=" + jsessionId)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch anon script from '" + endpointName + "': HTTP " + response.code());
            }

            String json = response.body().string();
            return extractScriptFromJson(json);
        }
    }

    /**
     * Fetch project-level anonymization script.
     */
    public String fetchProjectAnonScript(String projectId) throws IOException {
        ensureAuthenticated();

        Request request = new Request.Builder()
                .url(baseUrl + "/data/projects/" + projectId + "/config/anon/script?format=json")
                .get()
                .header("Cookie", "JSESSIONID=" + jsessionId)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 404) {
                    return null; // No project-level script
                }
                throw new IOException("Failed to fetch project anon script from '" + endpointName + "': HTTP " + response.code());
            }

            String json = response.body().string();
            return extractScriptFromJson(json);
        }
    }

    /**
     * Upload ZIP file to XNAT with retry logic.
     */
    public UploadResult upload(File zipFile, String projectId, String subjectId, String sessionLabel) throws IOException {
        return uploadWithRetry(zipFile, projectId, subjectId, sessionLabel, true, 3, 5000);
    }

    /**
     * Upload ZIP file to XNAT with configurable retry logic.
     *
     * @param zipFile the ZIP file to upload
     * @param projectId the project ID
     * @param subjectId the subject ID (optional)
     * @param sessionLabel the session label (optional)
     * @param autoArchive whether to auto-archive the session after upload
     * @param maxRetries maximum number of retry attempts
     * @param retryDelayMs delay between retries in milliseconds
     * @return the upload result
     */
    public UploadResult uploadWithRetry(File zipFile, String projectId, String subjectId, String sessionLabel,
                                         boolean autoArchive, int maxRetries, long retryDelayMs) throws IOException {
        byte[] zipBytes = Files.readAllBytes(zipFile.toPath());

        // Use project-specific import endpoint with proper parameters
        // For auto-archive, use /archive destination directly; otherwise use /prearchive
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
                .append("/data/services/import?inbody=true&prevent_anon=true&format=DICOM");

        if (autoArchive) {
            // Direct archive - skip prearchive entirely
            urlBuilder.append("&dest=/archive/projects/").append(projectId);
        } else {
            // Send to prearchive for manual review
            urlBuilder.append("&dest=/prearchive/projects/").append(projectId);
        }

        if (subjectId != null && !subjectId.isEmpty()) {
            urlBuilder.append("&SUBJECT_ID=").append(subjectId);
        }
        if (sessionLabel != null && !sessionLabel.isEmpty()) {
            urlBuilder.append("&EXPT_LABEL=").append(sessionLabel);
        }

        String url = urlBuilder.toString();
        UploadResult lastResult = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // Always authenticate fresh for each attempt
                authenticate();

                Request request = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(zipBytes, MediaType.parse("application/zip")))
                        .header("Cookie", "JSESSIONID=" + jsessionId)
                        .header("Content-Type", "application/zip")
                        .build();

                long startTime = System.currentTimeMillis();

                try (Response response = httpClient.newCall(request).execute()) {
                    long duration = System.currentTimeMillis() - startTime;

                    UploadResult result = new UploadResult();
                    result.setSuccess(response.isSuccessful());
                    result.setHttpCode(response.code());
                    result.setDurationMs(duration);
                    result.setFileSizeBytes(zipBytes.length);
                    result.setEndpointName(endpointName);
                    result.setAttempt(attempt + 1);

                    if (response.body() != null) {
                        result.setResponseBody(response.body().string());
                    }

                    if (response.isSuccessful()) {
                        if (attempt > 0) {
                            log.info("Upload to '{}' succeeded on attempt {}", endpointName, attempt + 1);
                        }
                        return result;
                    }

                    // Failed - check if retryable
                    result.setErrorMessage("HTTP " + response.code() + ": " + result.getResponseBody());
                    lastResult = result;

                    // Only retry on 401 (auth issues), 500+ (server errors), or network timeouts
                    if (response.code() == 401 || response.code() >= 500) {
                        if (attempt < maxRetries) {
                            log.warn("Upload to '{}' failed with HTTP {} on attempt {}/{}, retrying in {}ms...",
                                    endpointName, response.code(), attempt + 1, maxRetries + 1, retryDelayMs);
                            // Clear session so next attempt will re-authenticate
                            jsessionId = null;
                            Thread.sleep(retryDelayMs);
                        }
                    } else {
                        // Non-retryable error (400, 403, 404, etc.)
                        log.error("Upload to '{}' failed with non-retryable HTTP {}", endpointName, response.code());
                        return result;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Upload interrupted", e);
            } catch (IOException e) {
                // Network error - retryable
                if (attempt < maxRetries) {
                    log.warn("Upload to '{}' failed with network error on attempt {}/{}: {}, retrying in {}ms...",
                            endpointName, attempt + 1, maxRetries + 1, e.getMessage(), retryDelayMs);
                    jsessionId = null;
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Upload interrupted", ie);
                    }
                } else {
                    throw e;
                }
            }
        }

        // All retries exhausted
        return lastResult;
    }

    private void ensureAuthenticated() throws IOException {
        if (jsessionId == null) {
            authenticate();
        }
    }

    private String extractScriptFromJson(String json) {
        // Simple JSON parsing - extract "contents" field
        // Format: {"ResultSet":{"Result":[{"contents":"..."}]}}
        int contentsIndex = json.indexOf("\"contents\"");
        if (contentsIndex < 0) return null;

        int colonIndex = json.indexOf(":", contentsIndex);
        int startQuote = json.indexOf("\"", colonIndex + 1);
        if (startQuote < 0) return null;

        StringBuilder content = new StringBuilder();
        boolean escaped = false;
        for (int i = startQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                if (c == 'n') content.append('\n');
                else if (c == 't') content.append('\t');
                else if (c == 'r') content.append('\r');
                else content.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                content.append(c);
            }
        }

        return content.toString();
    }

    @Override
    public void close() {
        if (jsessionId != null) {
            invalidateSession(jsessionId);
            jsessionId = null;
        }
    }

    /**
     * Upload result details.
     */
    public static class UploadResult {
        private boolean success;
        private int httpCode;
        private long durationMs;
        private long fileSizeBytes;
        private String responseBody;
        private String errorMessage;
        private String endpointName;
        private int attempt = 1;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public int getHttpCode() { return httpCode; }
        public void setHttpCode(int httpCode) { this.httpCode = httpCode; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

        public long getFileSizeBytes() { return fileSizeBytes; }
        public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

        public String getResponseBody() { return responseBody; }
        public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

        public String getEndpointName() { return endpointName; }
        public void setEndpointName(String endpointName) { this.endpointName = endpointName; }

        public int getAttempt() { return attempt; }
        public void setAttempt(int attempt) { this.attempt = attempt; }

        public double getSpeedMBps() {
            if (durationMs <= 0) return 0;
            return (fileSizeBytes / 1048576.0) / (durationMs / 1000.0);
        }
    }
}
