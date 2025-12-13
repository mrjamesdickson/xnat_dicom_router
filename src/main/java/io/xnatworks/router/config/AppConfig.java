/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Application configuration for DICOM Router.
 * Supports routing to multiple destination types:
 * - XNAT instances (with anonymization)
 * - DICOM AE Titles (C-STORE forwarding)
 * - File system (local storage)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /**
     * Admin server port.
     */
    @JsonProperty("admin_port")
    private int adminPort = 8080;

    /**
     * Admin server host.
     */
    @JsonProperty("admin_host")
    private String adminHost = "localhost";

    /**
     * Data directory for storage.
     */
    @JsonProperty("data_directory")
    private String dataDirectory = "./data";

    /**
     * Scripts directory for anonymization scripts.
     */
    @JsonProperty("scripts_directory")
    private String scriptsDirectory = "./scripts";

    /**
     * Log level.
     */
    @JsonProperty("log_level")
    private String logLevel = "INFO";

    /**
     * Admin username for web UI/API authentication.
     */
    @JsonProperty("admin_username")
    private String adminUsername = "admin";

    /**
     * Admin password for web UI/API authentication.
     */
    @JsonProperty("admin_password")
    private String adminPassword = "admin";

    /**
     * Whether authentication is required for the admin API.
     */
    @JsonProperty("auth_required")
    private boolean authRequired = true;

    /**
     * Named destinations - XNAT endpoints, DICOM AE Titles, or file system paths.
     * Key is the destination name (e.g., "production-xnat", "pacs-archive", "local-backup").
     */
    private Map<String, Destination> destinations = new HashMap<>();

    /**
     * Legacy single XNAT endpoint config - for backward compatibility.
     */
    private XnatConfig xnat = new XnatConfig();

    /**
     * Legacy xnat_endpoints - for backward compatibility.
     */
    @JsonProperty("xnat_endpoints")
    private Map<String, XnatEndpointLegacy> xnatEndpoints = new HashMap<>();

    private ReceiverConfig receiver = new ReceiverConfig();
    private ResilienceConfig resilience = new ResilienceConfig();
    private NotificationConfig notifications = new NotificationConfig();

    /**
     * Honest Broker configurations - maps broker name to config.
     * Supports multiple broker services for different use cases.
     */
    @JsonProperty("honest_brokers")
    private Map<String, HonestBrokerConfig> honestBrokers = new HashMap<>();

    /**
     * Routing rules - maps AE Title/port listeners to destinations.
     */
    private List<RouteConfig> routes = new ArrayList<>();

    /**
     * Legacy projects - for backward compatibility.
     */
    private List<ProjectMapping> projects = new ArrayList<>();

    /**
     * Path to the config file (set when loaded).
     */
    private transient File configFile;

    public static AppConfig load(File configFile) throws IOException {
        log.info("Loading configuration from: {}", configFile.getAbsolutePath());
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        AppConfig config = mapper.readValue(configFile, AppConfig.class);
        config.configFile = configFile;

        // Migrate legacy configurations
        config.migrateFromLegacy();

        return config;
    }

    public static AppConfig load(String configPath) throws IOException {
        return load(new File(configPath));
    }

    /**
     * Save the configuration back to the YAML file.
     */
    public void save() throws IOException {
        if (configFile == null) {
            throw new IOException("Config file path not set - cannot save");
        }
        save(configFile);
    }

    /**
     * Save the configuration to a specific file.
     */
    public void save(File file) throws IOException {
        log.info("Saving configuration to: {}", file.getAbsolutePath());
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, this);
    }

    /**
     * Get the config file path.
     */
    public File getConfigFile() {
        return configFile;
    }

    /**
     * Set the config file path.
     */
    public void setConfigFile(File configFile) {
        this.configFile = configFile;
    }

    /**
     * Migrate legacy configurations to new destination-based routing.
     */
    private void migrateFromLegacy() {
        // Migrate legacy single xnat config
        if (xnat != null && xnat.getUrl() != null && !xnat.getUrl().isEmpty()) {
            if (!destinations.containsKey("default")) {
                log.info("Migrating legacy xnat config to destination 'default'");
                XnatDestination dest = new XnatDestination();
                dest.setUrl(xnat.getUrl());
                dest.setUsername(xnat.getUsername());
                dest.setPassword(xnat.getPassword());
                dest.setDescription("Default XNAT (migrated from legacy config)");
                destinations.put("default", dest);
            }
        }

        // Migrate legacy xnat_endpoints
        for (Map.Entry<String, XnatEndpointLegacy> entry : xnatEndpoints.entrySet()) {
            String name = entry.getKey();
            if (!destinations.containsKey(name)) {
                log.info("Migrating legacy xnat_endpoint '{}' to destination", name);
                XnatEndpointLegacy legacy = entry.getValue();
                XnatDestination dest = new XnatDestination();
                dest.setUrl(legacy.getUrl());
                dest.setUsername(legacy.getUsername());
                dest.setPassword(legacy.getPassword());
                dest.setDescription(legacy.getDescription());
                dest.setEnabled(legacy.isEnabled());
                destinations.put(name, dest);
            }
        }

        // Migrate legacy projects to routes
        for (ProjectMapping project : projects) {
            // Check if route already exists
            boolean routeExists = routes.stream()
                    .anyMatch(r -> r.getAeTitle().equalsIgnoreCase(project.getAeTitle()));

            if (!routeExists) {
                log.info("Migrating legacy project '{}' to route", project.getAeTitle());
                RouteConfig route = new RouteConfig();
                route.setAeTitle(project.getAeTitle());
                route.setPort(project.getPort());
                route.setDescription(project.getDescription());

                // Create destination reference
                RouteDestination routeDest = new RouteDestination();
                String destName = project.getXnatEndpoint() != null ? project.getXnatEndpoint() : "default";
                routeDest.setDestination(destName);
                routeDest.setProjectId(project.getProjectId());
                routeDest.setSubjectPrefix(project.getSubjectPrefix());
                routeDest.setSessionPrefix(project.getSessionPrefix());
                routeDest.setAnonymize(true); // XNAT routes typically anonymize

                route.getDestinations().add(routeDest);
                routes.add(route);
            }
        }

        // Ensure all routes have at least one destination
        for (RouteConfig route : routes) {
            if (route.getDestinations().isEmpty()) {
                RouteDestination defaultDest = new RouteDestination();
                defaultDest.setDestination("default");
                route.getDestinations().add(defaultDest);
            }
        }
    }

    // Getters and setters
    public int getAdminPort() { return adminPort; }
    public void setAdminPort(int adminPort) { this.adminPort = adminPort; }

    public String getAdminHost() { return adminHost; }
    public void setAdminHost(String adminHost) { this.adminHost = adminHost; }

    public String getDataDirectory() { return dataDirectory; }
    public void setDataDirectory(String dataDirectory) { this.dataDirectory = dataDirectory; }

    public String getScriptsDirectory() { return scriptsDirectory; }
    public void setScriptsDirectory(String scriptsDirectory) { this.scriptsDirectory = scriptsDirectory; }

    public String getLogLevel() { return logLevel; }
    public void setLogLevel(String logLevel) { this.logLevel = logLevel; }

    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }

    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }

    public boolean isAuthRequired() { return authRequired; }
    public void setAuthRequired(boolean authRequired) { this.authRequired = authRequired; }

    public Map<String, Destination> getDestinations() { return destinations; }
    public void setDestinations(Map<String, Destination> destinations) { this.destinations = destinations; }

    public XnatConfig getXnat() { return xnat; }
    public void setXnat(XnatConfig xnat) { this.xnat = xnat; }

    public Map<String, XnatEndpointLegacy> getXnatEndpoints() { return xnatEndpoints; }
    public void setXnatEndpoints(Map<String, XnatEndpointLegacy> xnatEndpoints) { this.xnatEndpoints = xnatEndpoints; }

    public ReceiverConfig getReceiver() { return receiver; }
    public void setReceiver(ReceiverConfig receiver) { this.receiver = receiver; }

    public ResilienceConfig getResilience() { return resilience; }
    public void setResilience(ResilienceConfig resilience) { this.resilience = resilience; }

    public NotificationConfig getNotifications() { return notifications; }
    public void setNotifications(NotificationConfig notifications) { this.notifications = notifications; }

    public Map<String, HonestBrokerConfig> getHonestBrokers() { return honestBrokers; }
    public void setHonestBrokers(Map<String, HonestBrokerConfig> honestBrokers) { this.honestBrokers = honestBrokers; }

    /**
     * Get a specific honest broker config by name.
     */
    public HonestBrokerConfig getHonestBroker(String name) {
        return honestBrokers.get(name);
    }

    public List<RouteConfig> getRoutes() { return routes; }
    public void setRoutes(List<RouteConfig> routes) { this.routes = routes; }

    public List<ProjectMapping> getProjects() { return projects; }
    public void setProjects(List<ProjectMapping> projects) { this.projects = projects; }

    /**
     * Get destination by name.
     */
    public Destination getDestination(String name) {
        return destinations.get(name);
    }

    /**
     * Get all XNAT destinations.
     */
    public Map<String, XnatDestination> getXnatDestinations() {
        Map<String, XnatDestination> result = new HashMap<>();
        for (Map.Entry<String, Destination> entry : destinations.entrySet()) {
            if (entry.getValue() instanceof XnatDestination) {
                result.put(entry.getKey(), (XnatDestination) entry.getValue());
            }
        }
        return result;
    }

    /**
     * Get all DICOM AE destinations.
     */
    public Map<String, DicomAeDestination> getDicomAeDestinations() {
        Map<String, DicomAeDestination> result = new HashMap<>();
        for (Map.Entry<String, Destination> entry : destinations.entrySet()) {
            if (entry.getValue() instanceof DicomAeDestination) {
                result.put(entry.getKey(), (DicomAeDestination) entry.getValue());
            }
        }
        return result;
    }

    /**
     * Find route by AE Title.
     */
    public RouteConfig findRouteByAeTitle(String aeTitle) {
        return routes.stream()
                .filter(r -> r.getAeTitle().equalsIgnoreCase(aeTitle))
                .findFirst()
                .orElse(null);
    }

    /**
     * Find route by port.
     */
    public RouteConfig findRouteByPort(int port) {
        return routes.stream()
                .filter(r -> r.getPort() == port)
                .findFirst()
                .orElse(null);
    }

    // ========================================================================
    // Destination Types
    // ========================================================================

    /**
     * Base destination interface.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = XnatDestination.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = XnatDestination.class, name = "xnat"),
            @JsonSubTypes.Type(value = DicomAeDestination.class, name = "dicom"),
            @JsonSubTypes.Type(value = FileDestination.class, name = "file")
    })
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static abstract class Destination {
        protected String description = "";
        protected boolean enabled = true;

        public abstract String getType();

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * XNAT destination - uploads to XNAT instance via REST API.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class XnatDestination extends Destination {
        private String url = "http://localhost";
        private String username = "admin";
        private String password = "admin";
        private int timeout = 60;
        @JsonProperty("max_retries")
        private int maxRetries = 3;
        @JsonProperty("connection_pool_size")
        private int connectionPoolSize = 5;

        @Override
        public String getType() { return "xnat"; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public int getConnectionPoolSize() { return connectionPoolSize; }
        public void setConnectionPoolSize(int connectionPoolSize) { this.connectionPoolSize = connectionPoolSize; }

        @Override
        public String toString() {
            return String.format("XnatDestination{url='%s', enabled=%s}", url, enabled);
        }
    }

    /**
     * DICOM AE destination - forwards via C-STORE to another DICOM node.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DicomAeDestination extends Destination {
        @JsonProperty("ae_title")
        private String aeTitle;

        private String host;
        private int port = 104;

        /**
         * Our calling AE Title when connecting to this destination.
         */
        @JsonProperty("calling_ae_title")
        private String callingAeTitle = "DICOM_ROUTER";

        /**
         * TLS settings for secure DICOM connections.
         */
        @JsonProperty("use_tls")
        private boolean useTls = false;

        @JsonProperty("tls_enabled")
        private boolean tlsEnabled = false;

        private int timeout = 60;

        @JsonProperty("max_retries")
        private int maxRetries = 3;

        @Override
        public String getType() { return "dicom"; }

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getCallingAeTitle() { return callingAeTitle; }
        public void setCallingAeTitle(String callingAeTitle) { this.callingAeTitle = callingAeTitle; }

        public boolean isUseTls() { return useTls; }
        public void setUseTls(boolean useTls) { this.useTls = useTls; }

        public boolean isTlsEnabled() { return tlsEnabled || useTls; }
        public void setTlsEnabled(boolean tlsEnabled) { this.tlsEnabled = tlsEnabled; }

        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        @Override
        public String toString() {
            return String.format("DicomAeDestination{aeTitle='%s', host='%s', port=%d, enabled=%s}",
                    aeTitle, host, port, enabled);
        }
    }

    /**
     * File system destination - stores DICOM files locally.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileDestination extends Destination {
        private String path;

        /**
         * Directory structure pattern: {PatientID}/{StudyDate}/{Modality}
         */
        @JsonProperty("directory_pattern")
        private String directoryPattern = "{PatientID}/{StudyDate}_{StudyTime}";

        /**
         * Whether to organize by AE Title first.
         */
        @JsonProperty("organize_by_ae")
        private boolean organizeByAe = true;

        @JsonProperty("create_subdirectories")
        private boolean createSubdirectories = true;

        @JsonProperty("naming_pattern")
        private String namingPattern = "{SOPInstanceUID}.dcm";

        @Override
        public String getType() { return "file"; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public String getDirectoryPattern() { return directoryPattern; }
        public void setDirectoryPattern(String directoryPattern) { this.directoryPattern = directoryPattern; }

        public boolean isOrganizeByAe() { return organizeByAe; }
        public void setOrganizeByAe(boolean organizeByAe) { this.organizeByAe = organizeByAe; }

        public boolean isCreateSubdirectories() { return createSubdirectories; }
        public void setCreateSubdirectories(boolean createSubdirectories) { this.createSubdirectories = createSubdirectories; }

        public String getNamingPattern() { return namingPattern; }
        public void setNamingPattern(String namingPattern) { this.namingPattern = namingPattern; }

        @Override
        public String toString() {
            return String.format("FileDestination{path='%s', enabled=%s}", path, enabled);
        }
    }

    // ========================================================================
    // Route Configuration
    // ========================================================================

    /**
     * Route configuration - defines a DICOM listener and its destinations.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RouteConfig {
        @JsonProperty("ae_title")
        private String aeTitle;

        private int port;
        private String description = "";
        private boolean enabled = true;

        /**
         * Number of worker threads for processing/forwarding.
         * Each route can have its own thread pool.
         */
        @JsonProperty("worker_threads")
        private int workerThreads = 2;

        /**
         * Maximum concurrent transfers to destinations.
         */
        @JsonProperty("max_concurrent_transfers")
        private int maxConcurrentTransfers = 4;

        /**
         * Study timeout in seconds - how long to wait for additional files.
         */
        @JsonProperty("study_timeout_seconds")
        private int studyTimeoutSeconds = 30;

        /**
         * Rate limit - max studies per minute (0 = unlimited).
         */
        @JsonProperty("rate_limit_per_minute")
        private int rateLimitPerMinute = 0;

        /**
         * Conditional routing rules - determines which destinations to use
         * based on DICOM attributes.
         */
        @JsonProperty("routing_rules")
        private List<RoutingRule> routingRules = new ArrayList<>();

        /**
         * Validation rules - DICOM must pass these to be processed.
         */
        @JsonProperty("validation_rules")
        private List<ValidationRule> validationRules = new ArrayList<>();

        /**
         * Filter rules - exclude certain DICOM from forwarding.
         */
        @JsonProperty("filters")
        private List<FilterRule> filters = new ArrayList<>();

        /**
         * Tag modification rules - applied before anonymization.
         */
        @JsonProperty("tag_modifications")
        private List<TagModification> tagModifications = new ArrayList<>();

        /**
         * Webhook URL to call on events.
         */
        @JsonProperty("webhook_url")
        private String webhookUrl;

        /**
         * Events to send to webhook.
         */
        @JsonProperty("webhook_events")
        private List<String> webhookEvents = new ArrayList<>();

        /**
         * Destinations for this route (can forward to multiple destinations).
         */
        private List<RouteDestination> destinations = new ArrayList<>();

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }

        public int getMaxConcurrentTransfers() { return maxConcurrentTransfers; }
        public void setMaxConcurrentTransfers(int maxConcurrentTransfers) { this.maxConcurrentTransfers = maxConcurrentTransfers; }

        public int getStudyTimeoutSeconds() { return studyTimeoutSeconds; }
        public void setStudyTimeoutSeconds(int studyTimeoutSeconds) { this.studyTimeoutSeconds = studyTimeoutSeconds; }

        public int getRateLimitPerMinute() { return rateLimitPerMinute; }
        public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }

        public List<RoutingRule> getRoutingRules() { return routingRules; }
        public void setRoutingRules(List<RoutingRule> routingRules) { this.routingRules = routingRules; }

        public List<ValidationRule> getValidationRules() { return validationRules; }
        public void setValidationRules(List<ValidationRule> validationRules) { this.validationRules = validationRules; }

        public List<FilterRule> getFilters() { return filters; }
        public void setFilters(List<FilterRule> filters) { this.filters = filters; }

        public List<TagModification> getTagModifications() { return tagModifications; }
        public void setTagModifications(List<TagModification> tagModifications) { this.tagModifications = tagModifications; }

        public String getWebhookUrl() { return webhookUrl; }
        public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

        public List<String> getWebhookEvents() { return webhookEvents; }
        public void setWebhookEvents(List<String> webhookEvents) { this.webhookEvents = webhookEvents; }

        public List<RouteDestination> getDestinations() { return destinations; }
        public void setDestinations(List<RouteDestination> destinations) { this.destinations = destinations; }

        @Override
        public String toString() {
            return String.format("RouteConfig{aeTitle='%s', port=%d, destinations=%d, threads=%d}",
                    aeTitle, port, destinations.size(), workerThreads);
        }
    }

    // ========================================================================
    // Routing and Filter Rules
    // ========================================================================

    /**
     * Conditional routing rule - route to specific destinations based on DICOM attributes.
     * If any routing rules match, only the matched destinations are used.
     * If no rules match (or no rules defined), all enabled destinations are used.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoutingRule {
        private String name;
        private String description;

        /**
         * DICOM tag to check (e.g., "0008,0060" for Modality).
         */
        private String tag;

        /**
         * Operator: equals, contains, starts_with, ends_with, matches (regex), in (list)
         */
        private String operator = "equals";

        /**
         * Value(s) to compare against.
         */
        private String value;

        /**
         * List of values for 'in' operator.
         */
        private List<String> values = new ArrayList<>();

        /**
         * Destinations to use if this rule matches.
         */
        private List<String> destinations = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }

        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }

        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }

        public List<String> getDestinations() { return destinations; }
        public void setDestinations(List<String> destinations) { this.destinations = destinations; }
    }

    /**
     * Validation rule - DICOM must pass all validation rules to be processed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ValidationRule {
        private String name;
        private String description;

        /**
         * Type: required_tag, tag_value, tag_length, modality_check
         */
        private String type = "required_tag";

        /**
         * DICOM tag to validate.
         */
        private String tag;

        /**
         * For tag_value: operator and value
         */
        private String operator;
        private String value;
        private List<String> values = new ArrayList<>();

        /**
         * For tag_length: min and max length
         */
        @JsonProperty("min_length")
        private Integer minLength;
        @JsonProperty("max_length")
        private Integer maxLength;

        /**
         * Action on failure: reject, warn, log
         */
        @JsonProperty("on_failure")
        private String onFailure = "reject";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }

        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }

        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }

        public Integer getMinLength() { return minLength; }
        public void setMinLength(Integer minLength) { this.minLength = minLength; }

        public Integer getMaxLength() { return maxLength; }
        public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }

        public String getOnFailure() { return onFailure; }
        public void setOnFailure(String onFailure) { this.onFailure = onFailure; }
    }

    /**
     * Filter rule - exclude certain DICOM from forwarding.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FilterRule {
        private String name;
        private String description;

        /**
         * DICOM tag to check.
         */
        private String tag;

        /**
         * Operator: equals, contains, starts_with, ends_with, matches (regex), in (list)
         */
        private String operator = "equals";

        /**
         * Value to match.
         */
        private String value;
        private List<String> values = new ArrayList<>();

        /**
         * Action: include (only include matching), exclude (exclude matching)
         */
        private String action = "exclude";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }

        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }

        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }

    /**
     * Tag modification rule - modify DICOM tags before forwarding.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TagModification {
        private String name;
        private String description;

        /**
         * DICOM tag to modify.
         */
        private String tag;

        /**
         * Action: set, copy_from, prefix, suffix, replace, remove, hash
         */
        private String action = "set";

        /**
         * Value for set action.
         */
        private String value;

        /**
         * Source tag for copy_from action.
         */
        @JsonProperty("source_tag")
        private String sourceTag;

        /**
         * Pattern for replace action (regex).
         */
        private String pattern;

        /**
         * Replacement for replace action.
         */
        private String replacement;

        /**
         * Apply only when condition matches (optional).
         */
        private String condition;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getTag() { return tag; }
        public void setTag(String tag) { this.tag = tag; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }

        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }

        public String getSourceTag() { return sourceTag; }
        public void setSourceTag(String sourceTag) { this.sourceTag = sourceTag; }

        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }

        public String getReplacement() { return replacement; }
        public void setReplacement(String replacement) { this.replacement = replacement; }

        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
    }

    /**
     * Route destination - reference to a destination with route-specific settings.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RouteDestination {
        /**
         * Name of the destination (references key in destinations map).
         */
        private String destination;

        /**
         * Whether to anonymize before sending to this destination.
         */
        private boolean anonymize = false;

        /**
         * Name of the anonymization script from the script library.
         * If not specified, uses 'hipaa_standard' for XNAT destinations
         * or 'passthrough' for DICOM destinations.
         */
        @JsonProperty("anon_script")
        private String anonScript;

        /**
         * XNAT-specific: Project ID for uploads.
         */
        @JsonProperty("project_id")
        private String projectId;

        /**
         * XNAT-specific: Subject ID prefix.
         */
        @JsonProperty("subject_prefix")
        private String subjectPrefix = "SUBJ";

        /**
         * XNAT-specific: Session label prefix.
         */
        @JsonProperty("session_prefix")
        private String sessionPrefix = "SESSION";

        /**
         * XNAT-specific: Whether to auto-archive the session after upload.
         * If true, session is committed to the archive immediately.
         * If false, session stays in prearchive for review.
         */
        @JsonProperty("auto_archive")
        private boolean autoArchive = true;

        /**
         * Whether this destination is enabled for this route.
         */
        private boolean enabled = true;

        /**
         * Priority (lower = higher priority, for failover).
         */
        private int priority = 0;

        /**
         * Retry count for this destination if forward fails.
         */
        @JsonProperty("retry_count")
        private int retryCount = 3;

        /**
         * Retry delay in seconds.
         */
        @JsonProperty("retry_delay_seconds")
        private int retryDelaySeconds = 60;

        /**
         * Whether to use an honest broker for de-identification ID lookup.
         * When enabled, PatientID and PatientName are replaced with values
         * from the configured honest broker service.
         */
        @JsonProperty("use_honest_broker")
        private boolean useHonestBroker = false;

        /**
         * Name of the honest broker configuration to use (references key in honest_brokers map).
         * Only used when use_honest_broker is true.
         */
        @JsonProperty("honest_broker")
        private String honestBrokerName;

        public String getDestination() { return destination; }
        public void setDestination(String destination) { this.destination = destination; }

        public boolean isAnonymize() { return anonymize; }
        public void setAnonymize(boolean anonymize) { this.anonymize = anonymize; }

        public String getAnonScript() { return anonScript; }
        public void setAnonScript(String anonScript) { this.anonScript = anonScript; }

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }

        public String getSubjectPrefix() { return subjectPrefix; }
        public void setSubjectPrefix(String subjectPrefix) { this.subjectPrefix = subjectPrefix; }

        public String getSessionPrefix() { return sessionPrefix; }
        public void setSessionPrefix(String sessionPrefix) { this.sessionPrefix = sessionPrefix; }

        public boolean isAutoArchive() { return autoArchive; }
        public void setAutoArchive(boolean autoArchive) { this.autoArchive = autoArchive; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }

        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

        public int getRetryDelaySeconds() { return retryDelaySeconds; }
        public void setRetryDelaySeconds(int retryDelaySeconds) { this.retryDelaySeconds = retryDelaySeconds; }

        public boolean isUseHonestBroker() { return useHonestBroker; }
        public void setUseHonestBroker(boolean useHonestBroker) { this.useHonestBroker = useHonestBroker; }

        public String getHonestBrokerName() { return honestBrokerName; }
        public void setHonestBrokerName(String honestBrokerName) { this.honestBrokerName = honestBrokerName; }

        /**
         * Get the effective anonymization script name.
         * Returns explicit script if set, otherwise returns default based on anonymize flag.
         */
        public String getEffectiveAnonScript() {
            if (anonScript != null && !anonScript.isEmpty()) {
                return anonScript;
            }
            return anonymize ? "hipaa_standard" : "passthrough";
        }

        @Override
        public String toString() {
            return String.format("RouteDestination{destination='%s', anonymize=%s, script='%s', projectId='%s', honestBroker=%s}",
                    destination, anonymize, anonScript, projectId, useHonestBroker ? honestBrokerName : "disabled");
        }
    }

    // ========================================================================
    // Other Configuration Classes
    // ========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReceiverConfig {
        /**
         * Base storage directory. Each AE Title gets its own subdirectory.
         */
        @JsonProperty("base_dir")
        private String baseDir = "./data";

        // Legacy fields for backward compatibility
        @JsonProperty("storage_dir")
        private String storageDir = "./incoming";

        @JsonProperty("processed_dir")
        private String processedDir = "./processed";

        @JsonProperty("failed_dir")
        private String failedDir = "./failed";

        @JsonProperty("done_dir")
        private String doneDir = "./done";

        public String getBaseDir() { return baseDir; }
        public void setBaseDir(String baseDir) { this.baseDir = baseDir; }

        public String getStorageDir() { return storageDir; }
        public void setStorageDir(String storageDir) { this.storageDir = storageDir; }

        public String getProcessedDir() { return processedDir; }
        public void setProcessedDir(String processedDir) { this.processedDir = processedDir; }

        public String getFailedDir() { return failedDir; }
        public void setFailedDir(String failedDir) { this.failedDir = failedDir; }

        public String getDoneDir() { return doneDir; }
        public void setDoneDir(String doneDir) { this.doneDir = doneDir; }

        /**
         * Get AE-specific incoming directory.
         */
        public String getIncomingDir(String aeTitle) {
            return baseDir + "/" + aeTitle + "/incoming";
        }

        /**
         * Get AE-specific processing directory.
         */
        public String getProcessingDir(String aeTitle) {
            return baseDir + "/" + aeTitle + "/processing";
        }

        /**
         * Get AE-specific completed directory.
         */
        public String getCompletedDir(String aeTitle) {
            return baseDir + "/" + aeTitle + "/completed";
        }

        /**
         * Get AE-specific failed directory.
         */
        public String getFailedDir(String aeTitle) {
            return baseDir + "/" + aeTitle + "/failed";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResilienceConfig {
        @JsonProperty("health_check_interval")
        private int healthCheckInterval = 60;

        @JsonProperty("cache_dir")
        private String cacheDir = "./cache/pending";

        @JsonProperty("max_retries")
        private int maxRetries = 10;

        @JsonProperty("retry_delay")
        private int retryDelay = 300;

        @JsonProperty("retention_days")
        private int retentionDays = 30;

        public int getHealthCheckInterval() { return healthCheckInterval; }
        public void setHealthCheckInterval(int healthCheckInterval) { this.healthCheckInterval = healthCheckInterval; }

        public String getCacheDir() { return cacheDir; }
        public void setCacheDir(String cacheDir) { this.cacheDir = cacheDir; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public int getRetryDelay() { return retryDelay; }
        public void setRetryDelay(int retryDelay) { this.retryDelay = retryDelay; }

        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NotificationConfig {
        private boolean enabled = false;

        @JsonProperty("smtp_server")
        private String smtpServer = "localhost";

        @JsonProperty("smtp_port")
        private int smtpPort = 25;

        @JsonProperty("smtp_use_tls")
        private boolean smtpUseTls = false;

        @JsonProperty("smtp_username")
        private String smtpUsername = "";

        @JsonProperty("smtp_password")
        private String smtpPassword = "";

        @JsonProperty("from_address")
        private String fromAddress = "dicom-router@localhost";

        @JsonProperty("admin_email")
        private String adminEmail = "admin@localhost";

        @JsonProperty("notify_on_destination_down")
        private boolean notifyOnDestinationDown = true;

        @JsonProperty("notify_on_destination_recovered")
        private boolean notifyOnDestinationRecovered = true;

        @JsonProperty("notify_on_forward_failure")
        private boolean notifyOnForwardFailure = true;

        @JsonProperty("notify_on_daily_summary")
        private boolean notifyOnDailySummary = true;

        // Getters and setters
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getSmtpServer() { return smtpServer; }
        public void setSmtpServer(String smtpServer) { this.smtpServer = smtpServer; }

        public int getSmtpPort() { return smtpPort; }
        public void setSmtpPort(int smtpPort) { this.smtpPort = smtpPort; }

        public boolean isSmtpUseTls() { return smtpUseTls; }
        public void setSmtpUseTls(boolean smtpUseTls) { this.smtpUseTls = smtpUseTls; }

        public String getSmtpUsername() { return smtpUsername; }
        public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }

        public String getSmtpPassword() { return smtpPassword; }
        public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }

        public String getFromAddress() { return fromAddress; }
        public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }

        public String getAdminEmail() { return adminEmail; }
        public void setAdminEmail(String adminEmail) { this.adminEmail = adminEmail; }

        public boolean isNotifyOnDestinationDown() { return notifyOnDestinationDown; }
        public void setNotifyOnDestinationDown(boolean v) { this.notifyOnDestinationDown = v; }

        public boolean isNotifyOnDestinationRecovered() { return notifyOnDestinationRecovered; }
        public void setNotifyOnDestinationRecovered(boolean v) { this.notifyOnDestinationRecovered = v; }

        public boolean isNotifyOnForwardFailure() { return notifyOnForwardFailure; }
        public void setNotifyOnForwardFailure(boolean v) { this.notifyOnForwardFailure = v; }

        public boolean isNotifyOnDailySummary() { return notifyOnDailySummary; }
        public void setNotifyOnDailySummary(boolean v) { this.notifyOnDailySummary = v; }
    }

    // ========================================================================
    // Honest Broker Configuration
    // ========================================================================

    /**
     * Configuration for an Honest Broker service.
     * An honest broker provides de-identification ID mapping - given an input ID (like PatientID),
     * it returns a de-identified output ID. This is used to replace PatientID and PatientName
     * in DICOM files during routing.
     *
     * <p>Supports multiple broker types/implementations. The default "local" implementation
     * generates de-identified IDs locally using configurable naming schemes.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HonestBrokerConfig {
        /**
         * Human-readable name/description for this broker.
         */
        private String description = "";

        /**
         * Whether this broker is enabled.
         */
        private boolean enabled = true;

        /**
         * Broker type - determines how de-identified IDs are generated.
         * Currently supported: "local" (default) - generates IDs locally using naming schemes
         */
        @JsonProperty("broker_type")
        private String brokerType = "local";

        /**
         * STS (Security Token Service) host for authentication.
         * The token endpoint is: https://{stsHost}/token
         */
        @JsonProperty("sts_host")
        private String stsHost;

        /**
         * API host for the de-identification lookup service.
         * The lookup endpoint is: https://{apiHost}/DeIdentification/lookup
         */
        @JsonProperty("api_host")
        private String apiHost;

        /**
         * Application name for authentication.
         */
        @JsonProperty("app_name")
        private String appName;

        /**
         * Application key for authentication.
         */
        @JsonProperty("app_key")
        private String appKey;

        /**
         * Username for authentication.
         */
        private String username;

        /**
         * Password for authentication.
         */
        private String password;

        /**
         * Request timeout in seconds.
         */
        private int timeout = 30;

        /**
         * Whether to cache lookup results to reduce API calls.
         */
        @JsonProperty("cache_enabled")
        private boolean cacheEnabled = true;

        /**
         * Cache TTL in seconds (how long to keep cached results).
         */
        @JsonProperty("cache_ttl_seconds")
        private int cacheTtlSeconds = 3600; // 1 hour default

        /**
         * Maximum number of entries in the cache.
         */
        @JsonProperty("cache_max_size")
        private int cacheMaxSize = 10000;

        /**
         * Whether to use the broker result for PatientID replacement.
         */
        @JsonProperty("replace_patient_id")
        private boolean replacePatientId = true;

        /**
         * Whether to use the broker result for PatientName replacement.
         */
        @JsonProperty("replace_patient_name")
        private boolean replacePatientName = true;

        /**
         * Prefix to add to the broker result for PatientID (optional).
         */
        @JsonProperty("patient_id_prefix")
        private String patientIdPrefix = "";

        /**
         * Prefix to add to the broker result for PatientName (optional).
         */
        @JsonProperty("patient_name_prefix")
        private String patientNamePrefix = "";

        /**
         * Naming scheme for local broker type.
         * Options: adjective_animal, color_animal, nato_phonetic, sequential, hash, script
         */
        @JsonProperty("naming_scheme")
        private String namingScheme = "adjective_animal";

        /**
         * Custom JavaScript code for the 'script' naming scheme.
         * The script should define a function that takes (idIn, idType, prefix, context) and returns the de-identified ID.
         * Example:
         * <pre>
         * function lookup(idIn, idType, prefix, context) {
         *   // Custom logic here
         *   return prefix + "-" + idIn.substring(0, 4).toUpperCase();
         * }
         * </pre>
         */
        @JsonProperty("lookup_script")
        private String lookupScript;

        // Getters and setters
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getBrokerType() { return brokerType; }
        public void setBrokerType(String brokerType) { this.brokerType = brokerType; }

        public String getStsHost() { return stsHost; }
        public void setStsHost(String stsHost) { this.stsHost = stsHost; }

        public String getApiHost() { return apiHost; }
        public void setApiHost(String apiHost) { this.apiHost = apiHost; }

        public String getAppName() { return appName; }
        public void setAppName(String appName) { this.appName = appName; }

        public String getAppKey() { return appKey; }
        public void setAppKey(String appKey) { this.appKey = appKey; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public int getTimeout() { return timeout; }
        public void setTimeout(int timeout) { this.timeout = timeout; }

        public boolean isCacheEnabled() { return cacheEnabled; }
        public void setCacheEnabled(boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; }

        public int getCacheTtlSeconds() { return cacheTtlSeconds; }
        public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }

        public int getCacheMaxSize() { return cacheMaxSize; }
        public void setCacheMaxSize(int cacheMaxSize) { this.cacheMaxSize = cacheMaxSize; }

        public boolean isReplacePatientId() { return replacePatientId; }
        public void setReplacePatientId(boolean replacePatientId) { this.replacePatientId = replacePatientId; }

        public boolean isReplacePatientName() { return replacePatientName; }
        public void setReplacePatientName(boolean replacePatientName) { this.replacePatientName = replacePatientName; }

        public String getPatientIdPrefix() { return patientIdPrefix; }
        public void setPatientIdPrefix(String patientIdPrefix) { this.patientIdPrefix = patientIdPrefix; }

        public String getPatientNamePrefix() { return patientNamePrefix; }
        public void setPatientNamePrefix(String patientNamePrefix) { this.patientNamePrefix = patientNamePrefix; }

        public String getNamingScheme() { return namingScheme; }
        public void setNamingScheme(String namingScheme) { this.namingScheme = namingScheme; }

        public String getLookupScript() { return lookupScript; }
        public void setLookupScript(String lookupScript) { this.lookupScript = lookupScript; }

        @Override
        public String toString() {
            return String.format("HonestBrokerConfig{type='%s', apiHost='%s', enabled=%s}",
                    brokerType, apiHost, enabled);
        }
    }

    // ========================================================================
    // Legacy Classes (for backward compatibility)
    // ========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class XnatConfig {
        private String url = "http://localhost";
        private String username = "admin";
        private String password = "admin";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /**
     * XNAT endpoint configuration for use with clients.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class XnatEndpoint {
        private String url = "http://localhost";
        private String username = "admin";
        private String password = "admin";
        private String description = "";
        private boolean enabled = true;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class XnatEndpointLegacy {
        private String url = "http://localhost";
        private String username = "admin";
        private String password = "admin";
        private String description = "";
        private boolean enabled = true;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectMapping {
        @JsonProperty("ae_title")
        private String aeTitle;

        private int port;

        @JsonProperty("project_id")
        private String projectId;

        @JsonProperty("xnat_endpoint")
        private String xnatEndpoint = "default";

        @JsonProperty("subject_prefix")
        private String subjectPrefix = "SUBJ";

        @JsonProperty("session_prefix")
        private String sessionPrefix = "SESSION";

        private String description = "";

        public String getAeTitle() { return aeTitle; }
        public void setAeTitle(String aeTitle) { this.aeTitle = aeTitle; }

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }

        public String getProjectId() { return projectId; }
        public void setProjectId(String projectId) { this.projectId = projectId; }

        public String getXnatEndpoint() { return xnatEndpoint; }
        public void setXnatEndpoint(String xnatEndpoint) { this.xnatEndpoint = xnatEndpoint; }

        public String getSubjectPrefix() { return subjectPrefix; }
        public void setSubjectPrefix(String subjectPrefix) { this.subjectPrefix = subjectPrefix; }

        public String getSessionPrefix() { return sessionPrefix; }
        public void setSessionPrefix(String sessionPrefix) { this.sessionPrefix = sessionPrefix; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
