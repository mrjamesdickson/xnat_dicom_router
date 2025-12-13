/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.xnatworks.router.config.AppConfig;
import io.xnatworks.router.config.AppConfig.HonestBrokerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for interacting with Honest Broker de-identification services.
 *
 * <p>The Honest Broker service maps original patient identifiers (PatientID, PatientName)
 * to anonymized/de-identified values. This allows data to be transmitted without
 * revealing actual patient information.</p>
 *
 * <p>Supported broker types:
 * <ul>
 *   <li>"local" - Generates de-identified IDs locally using configurable naming schemes</li>
 *   <li>"remote" - External broker API with STS authentication and DeIdentification lookup</li>
 * </ul>
 * </p>
 *
 * <p>Features:
 * <ul>
 *   <li>Optional caching of lookup results to reduce API calls</li>
 *   <li>Support for multiple broker configurations</li>
 *   <li>Persistent crosswalk storage for audit trail</li>
 * </ul>
 * </p>
 */
public class HonestBrokerService {
    private static final Logger log = LoggerFactory.getLogger(HonestBrokerService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AppConfig config;

    // Cache for JWT tokens (broker name -> token)
    private final Map<String, TokenCache> tokenCache = new ConcurrentHashMap<>();

    // Cache for lookup results (broker name + idIn -> idOut)
    private final Map<String, LookupCache> lookupCaches = new ConcurrentHashMap<>();

    // Local broker instances (broker name -> LocalHonestBroker)
    private final Map<String, LocalHonestBroker> localBrokers = new ConcurrentHashMap<>();

    // Crosswalk store for persistent mapping storage
    private final CrosswalkStore crosswalkStore;

    public HonestBrokerService(AppConfig config) {
        this.config = config;
        this.crosswalkStore = new CrosswalkStore(config.getDataDirectory());
        log.info("HonestBrokerService initialized with {} configured brokers",
                config.getHonestBrokers() != null ? config.getHonestBrokers().size() : 0);
    }

    /**
     * Get or create a local broker instance.
     */
    private LocalHonestBroker getLocalBroker(String brokerName, HonestBrokerConfig brokerConfig) {
        return localBrokers.computeIfAbsent(brokerName,
                k -> new LocalHonestBroker(crosswalkStore, brokerName, brokerConfig));
    }

    /**
     * Get the crosswalk store for external access (e.g., API).
     */
    public CrosswalkStore getCrosswalkStore() {
        return crosswalkStore;
    }

    /**
     * Look up the de-identified ID for a given input ID.
     *
     * @param brokerName The name of the broker configuration to use
     * @param idIn The original ID (e.g., PatientID)
     * @return The de-identified ID, or null if lookup fails
     */
    public String lookup(String brokerName, String idIn) {
        HonestBrokerConfig brokerConfig = config.getHonestBroker(brokerName);
        if (brokerConfig == null) {
            log.error("Honest broker not found: {}", brokerName);
            return null;
        }

        if (!brokerConfig.isEnabled()) {
            log.warn("Honest broker {} is disabled", brokerName);
            return null;
        }

        // Check cache first
        if (brokerConfig.isCacheEnabled()) {
            String cached = getCachedLookup(brokerName, idIn);
            if (cached != null) {
                log.debug("Cache hit for broker {} idIn {}", brokerName, idIn);
                return cached;
            }
        }

        // Perform actual lookup based on broker type
        String result = null;
        String brokerType = brokerConfig.getBrokerType();

        if ("local".equalsIgnoreCase(brokerType)) {
            LocalHonestBroker localBroker = getLocalBroker(brokerName, brokerConfig);
            result = localBroker.lookup(idIn, "patient_id");
        } else if ("remote".equalsIgnoreCase(brokerType)) {
            result = remoteLookup(brokerName, brokerConfig, idIn);
        } else {
            log.error("Unknown broker type: {} for broker {}", brokerType, brokerName);
            return null;
        }

        // Cache result
        if (result != null && brokerConfig.isCacheEnabled()) {
            cacheLookup(brokerName, idIn, result, brokerConfig.getCacheTtlSeconds());
        }

        return result;
    }

    /**
     * Reverse lookup - get original ID from de-identified ID.
     *
     * @param brokerName The name of the broker configuration to use
     * @param idOut The de-identified ID
     * @return The original ID, or null if lookup fails
     */
    public String reverseLookup(String brokerName, String idOut) {
        HonestBrokerConfig brokerConfig = config.getHonestBroker(brokerName);
        if (brokerConfig == null) {
            log.error("Honest broker not found: {}", brokerName);
            return null;
        }

        if (!brokerConfig.isEnabled()) {
            log.warn("Honest broker {} is disabled", brokerName);
            return null;
        }

        String brokerType = brokerConfig.getBrokerType();

        if ("local".equalsIgnoreCase(brokerType)) {
            LocalHonestBroker localBroker = getLocalBroker(brokerName, brokerConfig);
            return localBroker.reverseLookup(idOut, "patient_id");
        } else if ("remote".equalsIgnoreCase(brokerType)) {
            return remoteReverseLookup(brokerName, brokerConfig, idOut);
        } else {
            log.error("Unknown broker type: {} for broker {}", brokerType, brokerName);
            return null;
        }
    }

    /**
     * Test connection to a broker.
     *
     * @param brokerName The name of the broker configuration to test
     * @return true if connection successful, false otherwise
     */
    public boolean testConnection(String brokerName) {
        HonestBrokerConfig brokerConfig = config.getHonestBroker(brokerName);
        if (brokerConfig == null) {
            log.error("Honest broker not found: {}", brokerName);
            return false;
        }

        String brokerType = brokerConfig.getBrokerType();

        // Local broker always succeeds
        if ("local".equalsIgnoreCase(brokerType)) {
            LocalHonestBroker localBroker = getLocalBroker(brokerName, brokerConfig);
            return localBroker.testConnection();
        }

        try {
            String token = authenticate(brokerName, brokerConfig);
            return token != null && !token.isEmpty();
        } catch (Exception e) {
            log.error("Failed to test connection for broker {}: {}", brokerName, e.getMessage());
            return false;
        }
    }

    /**
     * Clear cached tokens and lookups for a specific broker.
     */
    public void clearCache(String brokerName) {
        tokenCache.remove(brokerName);
        lookupCaches.remove(brokerName);
        log.info("Cleared cache for broker: {}", brokerName);
    }

    /**
     * Clear all cached data.
     */
    public void clearAllCaches() {
        tokenCache.clear();
        lookupCaches.clear();
        log.info("Cleared all broker caches");
    }

    // ========================================================================
    // Remote Broker API Implementation
    // ========================================================================

    /**
     * Authenticate with the remote STS to get a JWT token.
     */
    private String authenticate(String brokerName, HonestBrokerConfig brokerConfig) {
        // Check token cache
        TokenCache cached = tokenCache.get(brokerName);
        if (cached != null && !cached.isExpired()) {
            return cached.token;
        }

        String stsUrl = "https://" + brokerConfig.getStsHost() + "/token";

        // Build authentication request
        String tokenJson = String.format(
            "{\"UserName\":\"%s\",\"AppName\":\"%s\",\"AppKey\":\"%s\",\"Password\":\"%s\"}",
            brokerConfig.getUsername(),
            brokerConfig.getAppName(),
            brokerConfig.getAppKey(),
            brokerConfig.getPassword()
        );

        try {
            String token = doPost(stsUrl, tokenJson, null, brokerConfig.getTimeout());
            if (token != null) {
                // Cache token for 50 minutes (assuming 1 hour expiry)
                tokenCache.put(brokerName, new TokenCache(token, System.currentTimeMillis() + 50 * 60 * 1000));
                log.debug("Authenticated with broker {} successfully", brokerName);
            }
            return token;
        } catch (Exception e) {
            log.error("Authentication failed for broker {}: {}", brokerName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Perform lookup using remote DeIdentification API.
     */
    private String remoteLookup(String brokerName, HonestBrokerConfig brokerConfig, String idIn) {
        String token = authenticate(brokerName, brokerConfig);
        if (token == null) {
            log.error("Cannot lookup - authentication failed for broker {}", brokerName);
            return null;
        }

        String apiUrl = String.format("https://%s/DeIdentification/lookup?idIn=%s",
                brokerConfig.getApiHost(), idIn);

        try {
            String response = doGet(apiUrl, "Bearer " + token, brokerConfig.getTimeout());
            if (response != null) {
                // Parse response - expecting array of lookup results
                LookupResult[] results = objectMapper.readValue(response, LookupResult[].class);
                if (results != null && results.length > 0) {
                    String idOut = results[0].getIdOut();
                    log.debug("Lookup successful for broker {} idIn {} -> idOut {}", brokerName, idIn, idOut);
                    return idOut;
                }
            }
        } catch (Exception e) {
            log.error("Lookup failed for broker {} idIn {}: {}", brokerName, idIn, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Perform reverse lookup using remote DeIdentification API.
     */
    private String remoteReverseLookup(String brokerName, HonestBrokerConfig brokerConfig, String idOut) {
        String token = authenticate(brokerName, brokerConfig);
        if (token == null) {
            log.error("Cannot reverse lookup - authentication failed for broker {}", brokerName);
            return null;
        }

        String apiUrl = String.format("https://%s/DeIdentification/lookup?idOut=%s",
                brokerConfig.getApiHost(), idOut);

        try {
            String response = doGet(apiUrl, "Bearer " + token, brokerConfig.getTimeout());
            if (response != null) {
                LookupResult[] results = objectMapper.readValue(response, LookupResult[].class);
                if (results != null && results.length > 0) {
                    String idIn = results[0].getIdIn();
                    log.debug("Reverse lookup successful for broker {} idOut {} -> idIn {}", brokerName, idOut, idIn);
                    return idIn;
                }
            }
        } catch (Exception e) {
            log.error("Reverse lookup failed for broker {} idOut {}: {}", brokerName, idOut, e.getMessage(), e);
        }
        return null;
    }

    // ========================================================================
    // HTTP Utilities
    // ========================================================================

    private String doPost(String urlString, String body, String authHeader, int timeoutSeconds) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(timeoutSeconds * 1000);
        conn.setReadTimeout(timeoutSeconds * 1000);
        conn.setDoOutput(true);

        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = body.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode >= 200 && responseCode < 300) {
            return readResponse(conn.getInputStream());
        } else {
            String error = readResponse(conn.getErrorStream());
            throw new IOException("HTTP " + responseCode + ": " + error);
        }
    }

    private String doGet(String urlString, String authHeader, int timeoutSeconds) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutSeconds * 1000);
        conn.setReadTimeout(timeoutSeconds * 1000);

        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode >= 200 && responseCode < 300) {
            return readResponse(conn.getInputStream());
        } else {
            String error = readResponse(conn.getErrorStream());
            throw new IOException("HTTP " + responseCode + ": " + error);
        }
    }

    private String readResponse(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    // ========================================================================
    // Caching
    // ========================================================================

    private String getCachedLookup(String brokerName, String idIn) {
        LookupCache cache = lookupCaches.get(brokerName);
        if (cache == null) {
            return null;
        }
        return cache.get(idIn);
    }

    private void cacheLookup(String brokerName, String idIn, String idOut, int ttlSeconds) {
        HonestBrokerConfig brokerConfig = config.getHonestBroker(brokerName);
        int maxSize = brokerConfig != null ? brokerConfig.getCacheMaxSize() : 10000;

        LookupCache cache = lookupCaches.computeIfAbsent(brokerName,
                k -> new LookupCache(maxSize, ttlSeconds * 1000L));
        cache.put(idIn, idOut);
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * Token cache entry.
     */
    private static class TokenCache {
        final String token;
        final long expiresAt;

        TokenCache(String token, long expiresAt) {
            this.token = token;
            this.expiresAt = expiresAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }

    /**
     * Simple LRU-style lookup cache with TTL.
     */
    private static class LookupCache {
        private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
        private final int maxSize;
        private final long defaultTtl;

        LookupCache(int maxSize, long defaultTtl) {
            this.maxSize = maxSize;
            this.defaultTtl = defaultTtl;
        }

        String get(String key) {
            CacheEntry entry = cache.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.isExpired()) {
                cache.remove(key);
                return null;
            }
            return entry.value;
        }

        void put(String key, String value) {
            // Simple size management - clear old entries if over limit
            if (cache.size() >= maxSize) {
                // Remove expired entries first
                cache.entrySet().removeIf(e -> e.getValue().isExpired());

                // If still over, remove oldest entries (approximation)
                if (cache.size() >= maxSize) {
                    int toRemove = cache.size() - maxSize + 100;
                    cache.entrySet().stream()
                            .limit(toRemove)
                            .map(Map.Entry::getKey)
                            .forEach(cache::remove);
                }
            }
            cache.put(key, new CacheEntry(value, System.currentTimeMillis() + defaultTtl));
        }

        private static class CacheEntry {
            final String value;
            final long expiresAt;

            CacheEntry(String value, long expiresAt) {
                this.value = value;
                this.expiresAt = expiresAt;
            }

            boolean isExpired() {
                return System.currentTimeMillis() >= expiresAt;
            }
        }
    }

    /**
     * POJO for parsing lookup results from API.
     */
    private static class LookupResult {
        private String idIn;
        private String idOut;

        public String getIdIn() { return idIn; }
        public void setIdIn(String idIn) { this.idIn = idIn; }

        public String getIdOut() { return idOut; }
        public void setIdOut(String idOut) { this.idOut = idOut; }
    }
}
