/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */
package io.xnatworks.router.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AppConfig.
 */
@DisplayName("AppConfig Tests")
class AppConfigTest {

    @TempDir
    Path tempDir;

    @Nested
    @DisplayName("Default Values Tests")
    class DefaultValuesTests {

        @Test
        @DisplayName("Should have correct default admin settings")
        void shouldHaveDefaultAdminSettings() {
            AppConfig config = new AppConfig();

            assertEquals(8080, config.getAdminPort());
            assertEquals("localhost", config.getAdminHost());
            assertEquals("admin", config.getAdminUsername());
            assertEquals("admin", config.getAdminPassword());
            assertTrue(config.isAuthRequired());
        }

        @Test
        @DisplayName("Should have correct default directories")
        void shouldHaveDefaultDirectories() {
            AppConfig config = new AppConfig();

            assertEquals("./data", config.getDataDirectory());
            assertEquals("./scripts", config.getScriptsDirectory());
        }

        @Test
        @DisplayName("Should have correct default log level")
        void shouldHaveDefaultLogLevel() {
            AppConfig config = new AppConfig();

            assertEquals("INFO", config.getLogLevel());
        }
    }

    @Nested
    @DisplayName("YAML Loading Tests")
    class YamlLoadingTests {

        @Test
        @DisplayName("Should load config from YAML file")
        void shouldLoadConfigFromYaml() throws IOException {
            String yaml = """
                admin_port: 9090
                admin_host: 0.0.0.0
                admin_username: testuser
                admin_password: testpass
                data_directory: /data/test
                log_level: DEBUG
                auth_required: false
                """;

            File configFile = tempDir.resolve("config.yaml").toFile();
            Files.writeString(configFile.toPath(), yaml);

            AppConfig config = AppConfig.load(configFile);

            assertEquals(9090, config.getAdminPort());
            assertEquals("0.0.0.0", config.getAdminHost());
            assertEquals("testuser", config.getAdminUsername());
            assertEquals("testpass", config.getAdminPassword());
            assertEquals("/data/test", config.getDataDirectory());
            assertEquals("DEBUG", config.getLogLevel());
            assertFalse(config.isAuthRequired());
        }

        @Test
        @DisplayName("Should load XNAT destination from YAML")
        void shouldLoadXnatDestination() throws IOException {
            String yaml = """
                destinations:
                  production:
                    type: xnat
                    url: http://xnat.example.com
                    username: xnat_user
                    password: xnat_pass
                    timeout: 120
                    max_retries: 5
                    connection_pool_size: 10
                    description: Production XNAT
                    enabled: true
                """;

            File configFile = tempDir.resolve("config.yaml").toFile();
            Files.writeString(configFile.toPath(), yaml);

            AppConfig config = AppConfig.load(configFile);

            AppConfig.Destination dest = config.getDestination("production");
            assertNotNull(dest);
            assertTrue(dest instanceof AppConfig.XnatDestination);

            AppConfig.XnatDestination xnatDest = (AppConfig.XnatDestination) dest;
            assertEquals("xnat", xnatDest.getType());
            assertEquals("http://xnat.example.com", xnatDest.getUrl());
            assertEquals("xnat_user", xnatDest.getUsername());
            assertEquals("xnat_pass", xnatDest.getPassword());
            assertEquals(120, xnatDest.getTimeout());
            assertEquals(5, xnatDest.getMaxRetries());
            assertEquals(10, xnatDest.getConnectionPoolSize());
            assertEquals("Production XNAT", xnatDest.getDescription());
            assertTrue(xnatDest.isEnabled());
        }

        @Test
        @DisplayName("Should load DICOM AE destination from YAML")
        void shouldLoadDicomAeDestination() throws IOException {
            String yaml = """
                destinations:
                  pacs:
                    type: dicom
                    ae_title: PACS_AE
                    host: pacs.example.com
                    port: 11112
                    calling_ae_title: ROUTER
                    use_tls: true
                    timeout: 30
                    max_retries: 2
                    description: PACS Server
                    enabled: true
                """;

            File configFile = tempDir.resolve("config.yaml").toFile();
            Files.writeString(configFile.toPath(), yaml);

            AppConfig config = AppConfig.load(configFile);

            AppConfig.Destination dest = config.getDestination("pacs");
            assertNotNull(dest);
            assertTrue(dest instanceof AppConfig.DicomAeDestination);

            AppConfig.DicomAeDestination dicomDest = (AppConfig.DicomAeDestination) dest;
            assertEquals("dicom", dicomDest.getType());
            assertEquals("PACS_AE", dicomDest.getAeTitle());
            assertEquals("pacs.example.com", dicomDest.getHost());
            assertEquals(11112, dicomDest.getPort());
            assertEquals("ROUTER", dicomDest.getCallingAeTitle());
            assertTrue(dicomDest.isUseTls());
            assertEquals(30, dicomDest.getTimeout());
            assertEquals(2, dicomDest.getMaxRetries());
        }

        @Test
        @DisplayName("Should load File destination from YAML")
        void shouldLoadFileDestination() throws IOException {
            String yaml = """
                destinations:
                  backup:
                    type: file
                    path: /data/backup
                    directory_pattern: "{StudyInstanceUID}"
                    organize_by_ae: true
                    create_subdirectories: true
                    naming_pattern: "{SOPInstanceUID}.dcm"
                    description: Local Backup
                    enabled: true
                """;

            File configFile = tempDir.resolve("config.yaml").toFile();
            Files.writeString(configFile.toPath(), yaml);

            AppConfig config = AppConfig.load(configFile);

            AppConfig.Destination dest = config.getDestination("backup");
            assertNotNull(dest);
            assertTrue(dest instanceof AppConfig.FileDestination);

            AppConfig.FileDestination fileDest = (AppConfig.FileDestination) dest;
            assertEquals("file", fileDest.getType());
            assertEquals("/data/backup", fileDest.getPath());
            assertEquals("{StudyInstanceUID}", fileDest.getDirectoryPattern());
            assertTrue(fileDest.isOrganizeByAe());
            assertTrue(fileDest.isCreateSubdirectories());
            assertEquals("{SOPInstanceUID}.dcm", fileDest.getNamingPattern());
        }

        @Test
        @DisplayName("Should load routes from YAML")
        void shouldLoadRoutes() throws IOException {
            String yaml = """
                routes:
                - ae_title: TEST_RECV
                  port: 11112
                  description: Test Route
                  enabled: true
                  worker_threads: 4
                  max_concurrent_transfers: 8
                  study_timeout_seconds: 60
                  destinations:
                  - destination: production
                    anonymize: true
                    project_id: TEST_PROJECT
                    subject_prefix: SUBJ
                    session_prefix: SESS
                    auto_archive: true
                    priority: 1
                """;

            File configFile = tempDir.resolve("config.yaml").toFile();
            Files.writeString(configFile.toPath(), yaml);

            AppConfig config = AppConfig.load(configFile);

            assertEquals(1, config.getRoutes().size());

            AppConfig.RouteConfig route = config.getRoutes().get(0);
            assertEquals("TEST_RECV", route.getAeTitle());
            assertEquals(11112, route.getPort());
            assertEquals("Test Route", route.getDescription());
            assertTrue(route.isEnabled());
            assertEquals(4, route.getWorkerThreads());
            assertEquals(8, route.getMaxConcurrentTransfers());
            assertEquals(60, route.getStudyTimeoutSeconds());

            assertEquals(1, route.getDestinations().size());
            AppConfig.RouteDestination routeDest = route.getDestinations().get(0);
            assertEquals("production", routeDest.getDestination());
            assertTrue(routeDest.isAnonymize());
            assertEquals("TEST_PROJECT", routeDest.getProjectId());
            assertEquals("SUBJ", routeDest.getSubjectPrefix());
            assertEquals("SESS", routeDest.getSessionPrefix());
            assertTrue(routeDest.isAutoArchive());
            assertEquals(1, routeDest.getPriority());
        }

        @Test
        @DisplayName("Should throw exception for non-existent file")
        void shouldThrowExceptionForNonExistentFile() {
            File nonExistent = tempDir.resolve("nonexistent.yaml").toFile();
            assertThrows(IOException.class, () -> AppConfig.load(nonExistent));
        }
    }

    @Nested
    @DisplayName("YAML Saving Tests")
    class YamlSavingTests {

        @Test
        @DisplayName("Should save config to YAML file")
        void shouldSaveConfigToYaml() throws IOException {
            AppConfig config = new AppConfig();
            config.setAdminPort(9999);
            config.setAdminHost("0.0.0.0");
            config.setLogLevel("DEBUG");

            File configFile = tempDir.resolve("saved_config.yaml").toFile();
            config.save(configFile);

            assertTrue(configFile.exists());
            String content = Files.readString(configFile.toPath());
            assertTrue(content.contains("9999"));
            assertTrue(content.contains("0.0.0.0"));
            assertTrue(content.contains("DEBUG"));
        }

        @Test
        @DisplayName("Should round-trip config through save and load")
        void shouldRoundTripConfig() throws IOException {
            AppConfig original = new AppConfig();
            original.setAdminPort(8888);
            original.setAdminUsername("roundtrip_user");
            original.setLogLevel("TRACE");

            // Add a destination
            AppConfig.XnatDestination xnatDest = new AppConfig.XnatDestination();
            xnatDest.setUrl("http://test.example.com");
            xnatDest.setUsername("test_user");
            xnatDest.setPassword("test_pass");
            original.getDestinations().put("test", xnatDest);

            File configFile = tempDir.resolve("roundtrip.yaml").toFile();
            original.save(configFile);

            AppConfig loaded = AppConfig.load(configFile);

            assertEquals(original.getAdminPort(), loaded.getAdminPort());
            assertEquals(original.getAdminUsername(), loaded.getAdminUsername());
            assertEquals(original.getLogLevel(), loaded.getLogLevel());
            assertNotNull(loaded.getDestination("test"));
        }

        @Test
        @DisplayName("Should throw exception when saving without config file set")
        void shouldThrowExceptionWhenSavingWithoutConfigFile() {
            AppConfig config = new AppConfig();
            // configFile is null by default
            assertThrows(IOException.class, config::save);
        }
    }

    @Nested
    @DisplayName("Route Finding Tests")
    class RouteFindingTests {

        private AppConfig config;

        @BeforeEach
        void setup() {
            config = new AppConfig();

            AppConfig.RouteConfig route1 = new AppConfig.RouteConfig();
            route1.setAeTitle("ROUTE_A");
            route1.setPort(11112);

            AppConfig.RouteConfig route2 = new AppConfig.RouteConfig();
            route2.setAeTitle("ROUTE_B");
            route2.setPort(11113);

            config.getRoutes().add(route1);
            config.getRoutes().add(route2);
        }

        @Test
        @DisplayName("Should find route by AE Title")
        void shouldFindRouteByAeTitle() {
            AppConfig.RouteConfig found = config.findRouteByAeTitle("ROUTE_A");
            assertNotNull(found);
            assertEquals("ROUTE_A", found.getAeTitle());
            assertEquals(11112, found.getPort());
        }

        @Test
        @DisplayName("Should find route by AE Title case insensitive")
        void shouldFindRouteByAeTitleCaseInsensitive() {
            AppConfig.RouteConfig found = config.findRouteByAeTitle("route_a");
            assertNotNull(found);
            assertEquals("ROUTE_A", found.getAeTitle());
        }

        @Test
        @DisplayName("Should return null for unknown AE Title")
        void shouldReturnNullForUnknownAeTitle() {
            assertNull(config.findRouteByAeTitle("UNKNOWN"));
        }

        @Test
        @DisplayName("Should find route by port")
        void shouldFindRouteByPort() {
            AppConfig.RouteConfig found = config.findRouteByPort(11113);
            assertNotNull(found);
            assertEquals("ROUTE_B", found.getAeTitle());
            assertEquals(11113, found.getPort());
        }

        @Test
        @DisplayName("Should return null for unknown port")
        void shouldReturnNullForUnknownPort() {
            assertNull(config.findRouteByPort(9999));
        }
    }

    @Nested
    @DisplayName("Destination Type Filtering Tests")
    class DestinationTypeFilteringTests {

        private AppConfig config;

        @BeforeEach
        void setup() {
            config = new AppConfig();

            AppConfig.XnatDestination xnat1 = new AppConfig.XnatDestination();
            xnat1.setUrl("http://xnat1.example.com");
            config.getDestinations().put("xnat1", xnat1);

            AppConfig.XnatDestination xnat2 = new AppConfig.XnatDestination();
            xnat2.setUrl("http://xnat2.example.com");
            config.getDestinations().put("xnat2", xnat2);

            AppConfig.DicomAeDestination dicom = new AppConfig.DicomAeDestination();
            dicom.setAeTitle("DICOM_AE");
            dicom.setHost("dicom.example.com");
            dicom.setPort(104);
            config.getDestinations().put("dicom1", dicom);

            AppConfig.FileDestination file = new AppConfig.FileDestination();
            file.setPath("/data/files");
            config.getDestinations().put("file1", file);
        }

        @Test
        @DisplayName("Should filter XNAT destinations")
        void shouldFilterXnatDestinations() {
            Map<String, AppConfig.XnatDestination> xnatDests = config.getXnatDestinations();
            assertEquals(2, xnatDests.size());
            assertTrue(xnatDests.containsKey("xnat1"));
            assertTrue(xnatDests.containsKey("xnat2"));
        }

        @Test
        @DisplayName("Should filter DICOM AE destinations")
        void shouldFilterDicomAeDestinations() {
            Map<String, AppConfig.DicomAeDestination> dicomDests = config.getDicomAeDestinations();
            assertEquals(1, dicomDests.size());
            assertTrue(dicomDests.containsKey("dicom1"));
            assertEquals("DICOM_AE", dicomDests.get("dicom1").getAeTitle());
        }
    }

    @Nested
    @DisplayName("RouteDestination Tests")
    class RouteDestinationTests {

        @Test
        @DisplayName("Should return default effective anon script when anonymize is true")
        void shouldReturnDefaultAnonScriptWhenAnonymize() {
            AppConfig.RouteDestination dest = new AppConfig.RouteDestination();
            dest.setAnonymize(true);
            dest.setAnonScript(null);

            assertEquals("hipaa_standard", dest.getEffectiveAnonScript());
        }

        @Test
        @DisplayName("Should return passthrough when anonymize is false")
        void shouldReturnPassthroughWhenNotAnonymize() {
            AppConfig.RouteDestination dest = new AppConfig.RouteDestination();
            dest.setAnonymize(false);
            dest.setAnonScript(null);

            assertEquals("passthrough", dest.getEffectiveAnonScript());
        }

        @Test
        @DisplayName("Should return explicit anon script when set")
        void shouldReturnExplicitAnonScript() {
            AppConfig.RouteDestination dest = new AppConfig.RouteDestination();
            dest.setAnonymize(true);
            dest.setAnonScript("custom_script");

            assertEquals("custom_script", dest.getEffectiveAnonScript());
        }

        @Test
        @DisplayName("Should have correct default values")
        void shouldHaveCorrectDefaultValues() {
            AppConfig.RouteDestination dest = new AppConfig.RouteDestination();

            assertFalse(dest.isAnonymize());
            assertTrue(dest.isEnabled());
            assertTrue(dest.isAutoArchive());
            assertEquals(0, dest.getPriority());
            assertEquals(3, dest.getRetryCount());
            assertEquals(60, dest.getRetryDelaySeconds());
            assertEquals("SUBJ", dest.getSubjectPrefix());
            assertEquals("SESSION", dest.getSessionPrefix());
            assertFalse(dest.isUseHonestBroker());
            assertFalse(dest.isOcrEnabled());
            assertFalse(dest.isOcrRedact());
            assertEquals(60.0f, dest.getOcrConfidenceThreshold(), 0.1f);
            assertEquals(2, dest.getOcrRedactPadding());
        }
    }

    @Nested
    @DisplayName("ReceiverConfig Tests")
    class ReceiverConfigTests {

        @Test
        @DisplayName("Should generate correct AE-specific directories")
        void shouldGenerateAeSpecificDirectories() {
            AppConfig.ReceiverConfig receiver = new AppConfig.ReceiverConfig();
            receiver.setBaseDir("/data/receiver");

            assertEquals("/data/receiver/TEST_AE/incoming", receiver.getIncomingDir("TEST_AE"));
            assertEquals("/data/receiver/TEST_AE/processing", receiver.getProcessingDir("TEST_AE"));
            assertEquals("/data/receiver/TEST_AE/completed", receiver.getCompletedDir("TEST_AE"));
            assertEquals("/data/receiver/TEST_AE/failed", receiver.getFailedDir("TEST_AE"));
        }
    }

    @Nested
    @DisplayName("ResilienceConfig Tests")
    class ResilienceConfigTests {

        @Test
        @DisplayName("Should have correct default values")
        void shouldHaveCorrectDefaultValues() {
            AppConfig.ResilienceConfig resilience = new AppConfig.ResilienceConfig();

            assertEquals(60, resilience.getHealthCheckInterval());
            assertEquals("./cache/pending", resilience.getCacheDir());
            assertEquals(10, resilience.getMaxRetries());
            assertEquals(300, resilience.getRetryDelay());
            assertEquals(30, resilience.getRetentionDays());
        }
    }

    @Nested
    @DisplayName("HonestBrokerConfig Tests")
    class HonestBrokerConfigTests {

        @Test
        @DisplayName("Should have correct default values")
        void shouldHaveCorrectDefaultValues() {
            AppConfig.HonestBrokerConfig broker = new AppConfig.HonestBrokerConfig();

            assertTrue(broker.isEnabled());
            assertEquals("local", broker.getBrokerType());
            assertEquals(30, broker.getTimeout());
            assertTrue(broker.isCacheEnabled());
            assertEquals(3600, broker.getCacheTtlSeconds());
            assertEquals(10000, broker.getCacheMaxSize());
            assertTrue(broker.isReplacePatientId());
            assertTrue(broker.isReplacePatientName());
            assertEquals("", broker.getPatientIdPrefix());
            assertEquals("", broker.getPatientNamePrefix());
            assertEquals("adjective_animal", broker.getNamingScheme());
        }

        @Test
        @DisplayName("Should load honest broker from YAML")
        void shouldLoadHonestBrokerFromYaml() throws IOException {
            String yaml = """
                honest_brokers:
                  local-broker:
                    description: Local Test Broker
                    enabled: true
                    broker_type: local
                    naming_scheme: hash
                    patient_id_prefix: SUBJ
                    cache_enabled: true
                    cache_ttl_seconds: 7200
                """;

            File configFile = tempDir.resolve("config.yaml").toFile();
            Files.writeString(configFile.toPath(), yaml);

            AppConfig config = AppConfig.load(configFile);

            AppConfig.HonestBrokerConfig broker = config.getHonestBroker("local-broker");
            assertNotNull(broker);
            assertEquals("Local Test Broker", broker.getDescription());
            assertTrue(broker.isEnabled());
            assertEquals("local", broker.getBrokerType());
            assertEquals("hash", broker.getNamingScheme());
            assertEquals("SUBJ", broker.getPatientIdPrefix());
            assertTrue(broker.isCacheEnabled());
            assertEquals(7200, broker.getCacheTtlSeconds());
        }
    }

    @Nested
    @DisplayName("Legacy Migration Tests")
    class LegacyMigrationTests {

        @Test
        @DisplayName("Should migrate legacy xnat config to default destination")
        void shouldMigrateLegacyXnatConfig() throws IOException {
            String yaml = """
                xnat:
                  url: http://legacy.xnat.com
                  username: legacy_user
                  password: legacy_pass
                """;

            File configFile = tempDir.resolve("config.yaml").toFile();
            Files.writeString(configFile.toPath(), yaml);

            AppConfig config = AppConfig.load(configFile);

            // Should have migrated to 'default' destination
            AppConfig.Destination dest = config.getDestination("default");
            assertNotNull(dest);
            assertTrue(dest instanceof AppConfig.XnatDestination);

            AppConfig.XnatDestination xnatDest = (AppConfig.XnatDestination) dest;
            assertEquals("http://legacy.xnat.com", xnatDest.getUrl());
            assertEquals("legacy_user", xnatDest.getUsername());
            assertEquals("legacy_pass", xnatDest.getPassword());
        }
    }

    @Nested
    @DisplayName("DicomAeDestination TLS Tests")
    class DicomAeDestinationTlsTests {

        @Test
        @DisplayName("Should report TLS enabled when use_tls is true")
        void shouldReportTlsEnabledWhenUseTlsTrue() {
            AppConfig.DicomAeDestination dest = new AppConfig.DicomAeDestination();
            dest.setUseTls(true);
            assertTrue(dest.isTlsEnabled());
        }

        @Test
        @DisplayName("Should report TLS enabled when tls_enabled is true")
        void shouldReportTlsEnabledWhenTlsEnabledTrue() {
            AppConfig.DicomAeDestination dest = new AppConfig.DicomAeDestination();
            dest.setTlsEnabled(true);
            assertTrue(dest.isTlsEnabled());
        }

        @Test
        @DisplayName("Should report TLS disabled when both are false")
        void shouldReportTlsDisabledWhenBothFalse() {
            AppConfig.DicomAeDestination dest = new AppConfig.DicomAeDestination();
            dest.setUseTls(false);
            dest.setTlsEnabled(false);
            assertFalse(dest.isTlsEnabled());
        }
    }
}
