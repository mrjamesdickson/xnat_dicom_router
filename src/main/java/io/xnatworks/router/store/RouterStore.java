/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persistent SQLite storage for router settings and metrics.
 *
 * <p>This store provides persistence for data that shouldn't be in the config file:
 * <ul>
 *   <li>Runtime settings and preferences</li>
 *   <li>Time-series metrics data</li>
 *   <li>Per-route cumulative statistics</li>
 * </ul>
 * </p>
 *
 * <p>Designed for easy migration to PostgreSQL later.</p>
 */
public class RouterStore {
    private static final Logger log = LoggerFactory.getLogger(RouterStore.class);
    private static final int SCHEMA_VERSION = 4;  // Bumped for patient_sex column

    private final String dbPath;
    private Connection connection;

    public RouterStore(String dataDirectory) {
        this.dbPath = dataDirectory + File.separator + "router.db";
        initialize();
    }

    /**
     * Initialize the database and create tables if needed.
     */
    private void initialize() {
        try {
            File dbFile = new File(dbPath);
            dbFile.getParentFile().mkdirs();

            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

            // Enable WAL mode for better concurrent access
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }

            createBaseTables();
            migrateIfNeeded();
            createIndexes();
            log.info("RouterStore initialized at: {}", dbPath);
        } catch (Exception e) {
            log.error("Failed to initialize RouterStore: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize router database", e);
        }
    }

    /**
     * Create base tables without indexes (indexes created after migration).
     */
    private void createBaseTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Schema version tracking
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS schema_info (" +
                "    key TEXT PRIMARY KEY," +
                "    value TEXT NOT NULL" +
                ")");

            // Key-value settings storage
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS settings (" +
                "    key TEXT PRIMARY KEY," +
                "    value TEXT," +
                "    category TEXT DEFAULT 'general'," +
                "    updated_at TEXT NOT NULL" +
                ")");

            // Minute-level metrics (keep 24 hours = 1440 records)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS metrics_minute (" +
                "    timestamp INTEGER PRIMARY KEY," +  // Unix timestamp in millis
                "    transfers INTEGER DEFAULT 0," +
                "    successful INTEGER DEFAULT 0," +
                "    failed INTEGER DEFAULT 0," +
                "    bytes INTEGER DEFAULT 0," +
                "    files INTEGER DEFAULT 0" +
                ")");

            // Hourly metrics (keep 30 days = 720 records)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS metrics_hour (" +
                "    timestamp INTEGER PRIMARY KEY," +
                "    transfers INTEGER DEFAULT 0," +
                "    successful INTEGER DEFAULT 0," +
                "    failed INTEGER DEFAULT 0," +
                "    bytes INTEGER DEFAULT 0," +
                "    files INTEGER DEFAULT 0" +
                ")");

            // Daily metrics (keep 365 days)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS metrics_day (" +
                "    timestamp INTEGER PRIMARY KEY," +
                "    transfers INTEGER DEFAULT 0," +
                "    successful INTEGER DEFAULT 0," +
                "    failed INTEGER DEFAULT 0," +
                "    bytes INTEGER DEFAULT 0," +
                "    files INTEGER DEFAULT 0" +
                ")");

            // Per-route cumulative statistics
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS route_stats (" +
                "    ae_title TEXT PRIMARY KEY," +
                "    total_transfers INTEGER DEFAULT 0," +
                "    successful_transfers INTEGER DEFAULT 0," +
                "    failed_transfers INTEGER DEFAULT 0," +
                "    total_bytes INTEGER DEFAULT 0," +
                "    total_files INTEGER DEFAULT 0," +
                "    first_transfer_at TEXT," +
                "    last_transfer_at TEXT," +
                "    updated_at TEXT NOT NULL" +
                ")");

            // Set initial schema version (only if new DB)
            stmt.execute(
                "INSERT OR IGNORE INTO schema_info (key, value) VALUES ('version', '1')");

            // ========================================================================
            // DICOM Index Tables
            // ========================================================================

            // Studies table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dicom_studies (" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    study_uid TEXT NOT NULL UNIQUE," +
                "    patient_id TEXT," +
                "    patient_name TEXT," +
                "    patient_sex TEXT," +  // M, F, O (Other)
                "    study_date TEXT," +
                "    study_time TEXT," +
                "    accession_number TEXT," +
                "    study_description TEXT," +
                "    modalities TEXT," +  // comma-separated
                "    institution_name TEXT," +
                "    referring_physician TEXT," +
                "    series_count INTEGER DEFAULT 0," +
                "    instance_count INTEGER DEFAULT 0," +
                "    total_size INTEGER DEFAULT 0," +
                "    source_route TEXT," +
                "    indexed_at TEXT NOT NULL," +
                "    file_paths TEXT" +  // JSON array
                ")");

            // Series table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dicom_series (" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    series_uid TEXT NOT NULL UNIQUE," +
                "    study_uid TEXT NOT NULL," +
                "    modality TEXT," +
                "    series_number INTEGER," +
                "    series_description TEXT," +
                "    body_part TEXT," +
                "    instance_count INTEGER DEFAULT 0," +
                "    indexed_at TEXT NOT NULL," +
                "    FOREIGN KEY (study_uid) REFERENCES dicom_studies(study_uid)" +
                ")");

            // Instances table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dicom_instances (" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    sop_instance_uid TEXT NOT NULL UNIQUE," +
                "    series_uid TEXT NOT NULL," +
                "    sop_class_uid TEXT," +
                "    instance_number INTEGER," +
                "    file_path TEXT," +
                "    file_size INTEGER," +
                "    file_hash TEXT," +
                "    indexed_at TEXT NOT NULL," +
                "    FOREIGN KEY (series_uid) REFERENCES dicom_series(series_uid)" +
                ")");

            // Custom DICOM fields configuration table
            // Allows users to define additional DICOM tags to extract and index
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dicom_custom_fields (" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    field_name TEXT NOT NULL UNIQUE," +  // e.g., "manufacturer"
                "    display_name TEXT NOT NULL," +       // e.g., "Manufacturer"
                "    dicom_tag TEXT NOT NULL," +          // e.g., "0008,0070" or "Manufacturer"
                "    level TEXT NOT NULL DEFAULT 'study'," +  // study, series, or instance
                "    field_type TEXT NOT NULL DEFAULT 'string'," +  // string, number, date
                "    searchable INTEGER DEFAULT 1," +     // whether to include in search
                "    display_in_list INTEGER DEFAULT 1," +  // show in results list
                "    enabled INTEGER DEFAULT 1," +
                "    created_at TEXT NOT NULL," +
                "    updated_at TEXT NOT NULL" +
                ")");

            // Custom field values table (EAV pattern for flexibility)
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dicom_custom_values (" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    field_id INTEGER NOT NULL," +
                "    entity_uid TEXT NOT NULL," +  // study_uid, series_uid, or sop_instance_uid
                "    value TEXT," +
                "    indexed_at TEXT NOT NULL," +
                "    FOREIGN KEY (field_id) REFERENCES dicom_custom_fields(id)," +
                "    UNIQUE(field_id, entity_uid)" +
                ")");

            // Reindex job tracking table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS dicom_reindex_jobs (" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    status TEXT NOT NULL DEFAULT 'pending'," +  // pending, running, completed, failed
                "    total_files INTEGER DEFAULT 0," +
                "    processed_files INTEGER DEFAULT 0," +
                "    error_count INTEGER DEFAULT 0," +
                "    started_at TEXT," +
                "    completed_at TEXT," +
                "    error_message TEXT," +
                "    created_at TEXT NOT NULL" +
                ")");
        }
    }

    /**
     * Create all indexes. Called AFTER migrations so columns exist.
     */
    private void createIndexes() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Metrics indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_minute_ts ON metrics_minute(timestamp DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_hour_ts ON metrics_hour(timestamp DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_metrics_day_ts ON metrics_day(timestamp DESC)");

            // DICOM study indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_studies_patient_id ON dicom_studies(patient_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_studies_patient_sex ON dicom_studies(patient_sex)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_studies_study_date ON dicom_studies(study_date)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_studies_modalities ON dicom_studies(modalities)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_studies_institution ON dicom_studies(institution_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_studies_accession ON dicom_studies(accession_number)");

            // DICOM series indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_series_modality ON dicom_series(modality)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_series_study_uid ON dicom_series(study_uid)");

            // DICOM instance indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_instances_series_uid ON dicom_instances(series_uid)");

            // Custom field indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_custom_values_field ON dicom_custom_values(field_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_custom_values_entity ON dicom_custom_values(entity_uid)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_custom_values_value ON dicom_custom_values(value)");
        }
    }

    private void migrateIfNeeded() throws SQLException {
        int currentVersion = getSchemaVersion();
        if (currentVersion < SCHEMA_VERSION) {
            log.info("Migrating RouterStore schema from version {} to {}", currentVersion, SCHEMA_VERSION);

            // Migration to version 4: Add patient_sex column
            if (currentVersion < 4) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("ALTER TABLE dicom_studies ADD COLUMN patient_sex TEXT");
                    log.info("Added patient_sex column to dicom_studies table");
                } catch (SQLException e) {
                    // Column might already exist (e.g., new DB with patient_sex in CREATE TABLE)
                    log.debug("Migration v4: {}", e.getMessage());
                }
            }

            // Update schema version
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("UPDATE schema_info SET value = '" + SCHEMA_VERSION + "' WHERE key = 'version'");
            }
        }
    }

    private int getSchemaVersion() {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT value FROM schema_info WHERE key = 'version'")) {
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Integer.parseInt(rs.getString("value"));
            }
        } catch (Exception e) {
            log.debug("Could not read schema version: {}", e.getMessage());
        }
        return 0;
    }

    // ========================================================================
    // Settings Methods
    // ========================================================================

    /**
     * Get a setting value.
     */
    public String getSetting(String key) {
        return getSetting("general", key);
    }

    /**
     * Get a setting value with category.
     */
    public String getSetting(String category, String key) {
        String sql = "SELECT value FROM settings WHERE category = ? AND key = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, category);
            stmt.setString(2, key);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("value");
            }
        } catch (SQLException e) {
            log.error("Failed to get setting {}/{}: {}", category, key, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Set a setting value.
     */
    public void setSetting(String key, String value) {
        setSetting("general", key, value);
    }

    /**
     * Set a setting value with category.
     */
    public void setSetting(String category, String key, String value) {
        String sql = "INSERT INTO settings (category, key, value, updated_at) VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT(key) DO UPDATE SET value = excluded.value, " +
                     "category = excluded.category, updated_at = excluded.updated_at";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, category);
            stmt.setString(2, key);
            stmt.setString(3, value);
            stmt.setString(4, Instant.now().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to set setting {}/{}: {}", category, key, e.getMessage(), e);
        }
    }

    /**
     * Delete a setting.
     */
    public void deleteSetting(String key) {
        String sql = "DELETE FROM settings WHERE key = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete setting {}: {}", key, e.getMessage(), e);
        }
    }

    // ========================================================================
    // Metrics Methods
    // ========================================================================

    /**
     * Record a minute-level metric point.
     */
    public void recordMinuteMetric(long timestamp, int transfers, int successful, int failed, long bytes, int files) {
        String sql = "INSERT INTO metrics_minute (timestamp, transfers, successful, failed, bytes, files) " +
                     "VALUES (?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(timestamp) DO UPDATE SET " +
                     "transfers = transfers + excluded.transfers, " +
                     "successful = successful + excluded.successful, " +
                     "failed = failed + excluded.failed, " +
                     "bytes = bytes + excluded.bytes, " +
                     "files = files + excluded.files";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, timestamp);
            stmt.setInt(2, transfers);
            stmt.setInt(3, successful);
            stmt.setInt(4, failed);
            stmt.setLong(5, bytes);
            stmt.setInt(6, files);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to record minute metric: {}", e.getMessage(), e);
        }
    }

    /**
     * Record an hourly metric point.
     */
    public void recordHourMetric(long timestamp, int transfers, int successful, int failed, long bytes, int files) {
        String sql = "INSERT INTO metrics_hour (timestamp, transfers, successful, failed, bytes, files) " +
                     "VALUES (?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(timestamp) DO UPDATE SET " +
                     "transfers = transfers + excluded.transfers, " +
                     "successful = successful + excluded.successful, " +
                     "failed = failed + excluded.failed, " +
                     "bytes = bytes + excluded.bytes, " +
                     "files = files + excluded.files";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, timestamp);
            stmt.setInt(2, transfers);
            stmt.setInt(3, successful);
            stmt.setInt(4, failed);
            stmt.setLong(5, bytes);
            stmt.setInt(6, files);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to record hour metric: {}", e.getMessage(), e);
        }
    }

    /**
     * Record a daily metric point.
     */
    public void recordDayMetric(long timestamp, int transfers, int successful, int failed, long bytes, int files) {
        String sql = "INSERT INTO metrics_day (timestamp, transfers, successful, failed, bytes, files) " +
                     "VALUES (?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(timestamp) DO UPDATE SET " +
                     "transfers = transfers + excluded.transfers, " +
                     "successful = successful + excluded.successful, " +
                     "failed = failed + excluded.failed, " +
                     "bytes = bytes + excluded.bytes, " +
                     "files = files + excluded.files";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, timestamp);
            stmt.setInt(2, transfers);
            stmt.setInt(3, successful);
            stmt.setInt(4, failed);
            stmt.setLong(5, bytes);
            stmt.setInt(6, files);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to record day metric: {}", e.getMessage(), e);
        }
    }

    /**
     * Get minute-level metrics for the last N minutes.
     */
    public List<MetricPoint> getMinuteMetrics(int count) {
        return getMetrics("metrics_minute", count);
    }

    /**
     * Get hourly metrics for the last N hours.
     */
    public List<MetricPoint> getHourMetrics(int count) {
        return getMetrics("metrics_hour", count);
    }

    /**
     * Get daily metrics for the last N days.
     */
    public List<MetricPoint> getDayMetrics(int count) {
        return getMetrics("metrics_day", count);
    }

    private List<MetricPoint> getMetrics(String table, int count) {
        List<MetricPoint> points = new ArrayList<>();
        String sql = "SELECT * FROM " + table + " ORDER BY timestamp DESC LIMIT ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, count);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                MetricPoint point = new MetricPoint();
                point.timestamp = rs.getLong("timestamp");
                point.transfers = rs.getInt("transfers");
                point.successful = rs.getInt("successful");
                point.failed = rs.getInt("failed");
                point.bytes = rs.getLong("bytes");
                point.files = rs.getInt("files");
                points.add(point);
            }
        } catch (SQLException e) {
            log.error("Failed to get metrics from {}: {}", table, e.getMessage(), e);
        }
        // Reverse to get chronological order
        java.util.Collections.reverse(points);
        return points;
    }

    /**
     * Cleanup old metrics data.
     */
    public void cleanupOldMetrics() {
        long now = System.currentTimeMillis();
        long dayMs = 24 * 60 * 60 * 1000L;

        // Keep 24 hours of minute data
        long minuteCutoff = now - (24 * 60 * 60 * 1000L);
        // Keep 30 days of hour data
        long hourCutoff = now - (30 * dayMs);
        // Keep 365 days of day data
        long dayCutoff = now - (365 * dayMs);

        try (Statement stmt = connection.createStatement()) {
            int minuteDeleted = stmt.executeUpdate("DELETE FROM metrics_minute WHERE timestamp < " + minuteCutoff);
            int hourDeleted = stmt.executeUpdate("DELETE FROM metrics_hour WHERE timestamp < " + hourCutoff);
            int dayDeleted = stmt.executeUpdate("DELETE FROM metrics_day WHERE timestamp < " + dayCutoff);

            if (minuteDeleted > 0 || hourDeleted > 0 || dayDeleted > 0) {
                log.debug("Cleaned up old metrics: {} minute, {} hour, {} day records",
                         minuteDeleted, hourDeleted, dayDeleted);
            }
        } catch (SQLException e) {
            log.error("Failed to cleanup old metrics: {}", e.getMessage(), e);
        }
    }

    // ========================================================================
    // Route Stats Methods
    // ========================================================================

    /**
     * Update route statistics after a transfer.
     */
    public void updateRouteStats(String aeTitle, boolean success, long bytes, int files) {
        String now = Instant.now().toString();
        String sql = "INSERT INTO route_stats (ae_title, total_transfers, successful_transfers, failed_transfers, " +
                     "total_bytes, total_files, first_transfer_at, last_transfer_at, updated_at) " +
                     "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(ae_title) DO UPDATE SET " +
                     "total_transfers = total_transfers + 1, " +
                     "successful_transfers = successful_transfers + ?, " +
                     "failed_transfers = failed_transfers + ?, " +
                     "total_bytes = total_bytes + ?, " +
                     "total_files = total_files + ?, " +
                     "last_transfer_at = excluded.last_transfer_at, " +
                     "updated_at = excluded.updated_at";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            int successVal = success ? 1 : 0;
            int failedVal = success ? 0 : 1;

            // INSERT values
            stmt.setString(1, aeTitle);
            stmt.setInt(2, successVal);
            stmt.setInt(3, failedVal);
            stmt.setLong(4, bytes);
            stmt.setInt(5, files);
            stmt.setString(6, now);
            stmt.setString(7, now);
            stmt.setString(8, now);

            // UPDATE values
            stmt.setInt(9, successVal);
            stmt.setInt(10, failedVal);
            stmt.setLong(11, bytes);
            stmt.setInt(12, files);

            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update route stats for {}: {}", aeTitle, e.getMessage(), e);
        }
    }

    /**
     * Get statistics for a specific route.
     */
    public RouteStats getRouteStats(String aeTitle) {
        String sql = "SELECT * FROM route_stats WHERE ae_title = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, aeTitle);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return extractRouteStats(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get route stats for {}: {}", aeTitle, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Get statistics for all routes.
     */
    public List<RouteStats> getAllRouteStats() {
        List<RouteStats> stats = new ArrayList<>();
        String sql = "SELECT * FROM route_stats ORDER BY total_transfers DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                stats.add(extractRouteStats(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to get all route stats: {}", e.getMessage(), e);
        }
        return stats;
    }

    private RouteStats extractRouteStats(ResultSet rs) throws SQLException {
        RouteStats stats = new RouteStats();
        stats.aeTitle = rs.getString("ae_title");
        stats.totalTransfers = rs.getLong("total_transfers");
        stats.successfulTransfers = rs.getLong("successful_transfers");
        stats.failedTransfers = rs.getLong("failed_transfers");
        stats.totalBytes = rs.getLong("total_bytes");
        stats.totalFiles = rs.getLong("total_files");
        stats.firstTransferAt = rs.getString("first_transfer_at");
        stats.lastTransferAt = rs.getString("last_transfer_at");
        return stats;
    }

    // ========================================================================
    // DICOM Index Methods
    // ========================================================================

    /**
     * Insert or update a study in the index.
     */
    public void upsertStudy(IndexedStudy study) {
        String sql = "INSERT INTO dicom_studies (study_uid, patient_id, patient_name, patient_sex, study_date, " +
                     "study_time, accession_number, study_description, modalities, institution_name, " +
                     "referring_physician, series_count, instance_count, total_size, source_route, " +
                     "indexed_at, file_paths) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(study_uid) DO UPDATE SET " +
                     "patient_id = excluded.patient_id, patient_name = excluded.patient_name, " +
                     "patient_sex = excluded.patient_sex, " +
                     "study_date = excluded.study_date, study_time = excluded.study_time, " +
                     "accession_number = excluded.accession_number, study_description = excluded.study_description, " +
                     "modalities = excluded.modalities, institution_name = excluded.institution_name, " +
                     "referring_physician = excluded.referring_physician, series_count = excluded.series_count, " +
                     "instance_count = excluded.instance_count, total_size = excluded.total_size, " +
                     "source_route = excluded.source_route, indexed_at = excluded.indexed_at, " +
                     "file_paths = excluded.file_paths";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, study.studyUid);
            stmt.setString(2, study.patientId);
            stmt.setString(3, study.patientName);
            stmt.setString(4, study.patientSex);
            stmt.setString(5, study.studyDate);
            stmt.setString(6, study.studyTime);
            stmt.setString(7, study.accessionNumber);
            stmt.setString(8, study.studyDescription);
            stmt.setString(9, study.modalities);
            stmt.setString(10, study.institutionName);
            stmt.setString(11, study.referringPhysician);
            stmt.setInt(12, study.seriesCount);
            stmt.setInt(13, study.instanceCount);
            stmt.setLong(14, study.totalSize);
            stmt.setString(15, study.sourceRoute);
            stmt.setString(16, Instant.now().toString());
            stmt.setString(17, study.filePaths);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to upsert study {}: {}", study.studyUid, e.getMessage(), e);
        }
    }

    /**
     * Insert or update a series in the index.
     */
    public void upsertSeries(IndexedSeries series) {
        String sql = "INSERT INTO dicom_series (series_uid, study_uid, modality, series_number, " +
                     "series_description, body_part, instance_count, indexed_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(series_uid) DO UPDATE SET " +
                     "modality = excluded.modality, series_number = excluded.series_number, " +
                     "series_description = excluded.series_description, body_part = excluded.body_part, " +
                     "instance_count = excluded.instance_count, indexed_at = excluded.indexed_at";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, series.seriesUid);
            stmt.setString(2, series.studyUid);
            stmt.setString(3, series.modality);
            stmt.setInt(4, series.seriesNumber);
            stmt.setString(5, series.seriesDescription);
            stmt.setString(6, series.bodyPart);
            stmt.setInt(7, series.instanceCount);
            stmt.setString(8, Instant.now().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to upsert series {}: {}", series.seriesUid, e.getMessage(), e);
        }
    }

    /**
     * Insert or update an instance in the index.
     */
    public void upsertInstance(IndexedInstance instance) {
        String sql = "INSERT INTO dicom_instances (sop_instance_uid, series_uid, sop_class_uid, " +
                     "instance_number, file_path, file_size, file_hash, indexed_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(sop_instance_uid) DO UPDATE SET " +
                     "sop_class_uid = excluded.sop_class_uid, instance_number = excluded.instance_number, " +
                     "file_path = excluded.file_path, file_size = excluded.file_size, " +
                     "file_hash = excluded.file_hash, indexed_at = excluded.indexed_at";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, instance.sopInstanceUid);
            stmt.setString(2, instance.seriesUid);
            stmt.setString(3, instance.sopClassUid);
            stmt.setInt(4, instance.instanceNumber);
            stmt.setString(5, instance.filePath);
            stmt.setLong(6, instance.fileSize);
            stmt.setString(7, instance.fileHash);
            stmt.setString(8, Instant.now().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to upsert instance {}: {}", instance.sopInstanceUid, e.getMessage(), e);
        }
    }

    /**
     * Search studies with flexible criteria.
     */
    public List<IndexedStudy> searchStudies(SearchCriteria criteria) {
        List<IndexedStudy> results = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT s.* FROM dicom_studies s WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (criteria.patientId != null && !criteria.patientId.isEmpty()) {
            sql.append(" AND s.patient_id LIKE ?");
            params.add("%" + criteria.patientId + "%");
        }
        if (criteria.patientName != null && !criteria.patientName.isEmpty()) {
            sql.append(" AND s.patient_name LIKE ?");
            params.add("%" + criteria.patientName + "%");
        }
        if (criteria.studyDateFrom != null && !criteria.studyDateFrom.isEmpty()) {
            sql.append(" AND s.study_date >= ?");
            params.add(criteria.studyDateFrom);
        }
        if (criteria.studyDateTo != null && !criteria.studyDateTo.isEmpty()) {
            sql.append(" AND s.study_date <= ?");
            params.add(criteria.studyDateTo);
        }
        if (criteria.modality != null && !criteria.modality.isEmpty()) {
            sql.append(" AND s.modalities LIKE ?");
            params.add("%" + criteria.modality + "%");
        }
        if (criteria.accessionNumber != null && !criteria.accessionNumber.isEmpty()) {
            sql.append(" AND s.accession_number LIKE ?");
            params.add("%" + criteria.accessionNumber + "%");
        }
        if (criteria.institutionName != null && !criteria.institutionName.isEmpty()) {
            sql.append(" AND s.institution_name LIKE ?");
            params.add("%" + criteria.institutionName + "%");
        }
        if (criteria.studyDescription != null && !criteria.studyDescription.isEmpty()) {
            sql.append(" AND s.study_description LIKE ?");
            params.add("%" + criteria.studyDescription + "%");
        }
        if (criteria.sourceRoute != null && !criteria.sourceRoute.isEmpty()) {
            sql.append(" AND s.source_route = ?");
            params.add(criteria.sourceRoute);
        }
        if (criteria.patientSex != null && !criteria.patientSex.isEmpty()) {
            sql.append(" AND s.patient_sex = ?");
            params.add(criteria.patientSex);
        }
        if (criteria.bodyPart != null && !criteria.bodyPart.isEmpty()) {
            sql.append(" AND s.study_uid IN (SELECT DISTINCT study_uid FROM dicom_series WHERE body_part = ?)");
            params.add(criteria.bodyPart);
        }

        // Custom field search
        if (criteria.customFields != null && !criteria.customFields.isEmpty()) {
            for (Map.Entry<String, String> entry : criteria.customFields.entrySet()) {
                sql.append(" AND s.study_uid IN (SELECT cv.entity_uid FROM dicom_custom_values cv " +
                          "JOIN dicom_custom_fields cf ON cv.field_id = cf.id " +
                          "WHERE cf.field_name = ? AND cv.value LIKE ?)");
                params.add(entry.getKey());
                params.add("%" + entry.getValue() + "%");
            }
        }

        sql.append(" ORDER BY s.study_date DESC, s.indexed_at DESC");

        if (criteria.limit > 0) {
            sql.append(" LIMIT ?");
            params.add(criteria.limit);
        }
        if (criteria.offset > 0) {
            sql.append(" OFFSET ?");
            params.add(criteria.offset);
        }

        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    stmt.setString(i + 1, (String) param);
                } else if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                }
            }
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                results.add(extractIndexedStudy(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to search studies: {}", e.getMessage(), e);
        }
        return results;
    }

    /**
     * Get a study by UID.
     */
    public IndexedStudy getStudy(String studyUid) {
        String sql = "SELECT * FROM dicom_studies WHERE study_uid = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, studyUid);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return extractIndexedStudy(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get study {}: {}", studyUid, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Get series for a study.
     */
    public List<IndexedSeries> getSeriesForStudy(String studyUid) {
        List<IndexedSeries> results = new ArrayList<>();
        String sql = "SELECT * FROM dicom_series WHERE study_uid = ? ORDER BY series_number";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, studyUid);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                IndexedSeries series = new IndexedSeries();
                series.id = rs.getLong("id");
                series.seriesUid = rs.getString("series_uid");
                series.studyUid = rs.getString("study_uid");
                series.modality = rs.getString("modality");
                series.seriesNumber = rs.getInt("series_number");
                series.seriesDescription = rs.getString("series_description");
                series.bodyPart = rs.getString("body_part");
                series.instanceCount = rs.getInt("instance_count");
                series.indexedAt = rs.getString("indexed_at");
                results.add(series);
            }
        } catch (SQLException e) {
            log.error("Failed to get series for study {}: {}", studyUid, e.getMessage(), e);
        }
        return results;
    }

    /**
     * Get instances for a series.
     */
    public List<IndexedInstance> getInstancesForSeries(String seriesUid) {
        List<IndexedInstance> results = new ArrayList<>();
        String sql = "SELECT * FROM dicom_instances WHERE series_uid = ? ORDER BY instance_number";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, seriesUid);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                IndexedInstance instance = new IndexedInstance();
                instance.id = rs.getLong("id");
                instance.sopInstanceUid = rs.getString("sop_instance_uid");
                instance.seriesUid = rs.getString("series_uid");
                instance.sopClassUid = rs.getString("sop_class_uid");
                instance.instanceNumber = rs.getInt("instance_number");
                instance.filePath = rs.getString("file_path");
                instance.fileSize = rs.getLong("file_size");
                instance.fileHash = rs.getString("file_hash");
                instance.indexedAt = rs.getString("indexed_at");
                results.add(instance);
            }
        } catch (SQLException e) {
            log.error("Failed to get instances for series {}: {}", seriesUid, e.getMessage(), e);
        }
        return results;
    }

    private IndexedStudy extractIndexedStudy(ResultSet rs) throws SQLException {
        IndexedStudy study = new IndexedStudy();
        study.id = rs.getLong("id");
        study.studyUid = rs.getString("study_uid");
        study.patientId = rs.getString("patient_id");
        study.patientName = rs.getString("patient_name");
        study.patientSex = rs.getString("patient_sex");
        study.studyDate = rs.getString("study_date");
        study.studyTime = rs.getString("study_time");
        study.accessionNumber = rs.getString("accession_number");
        study.studyDescription = rs.getString("study_description");
        study.modalities = rs.getString("modalities");
        study.institutionName = rs.getString("institution_name");
        study.referringPhysician = rs.getString("referring_physician");
        study.seriesCount = rs.getInt("series_count");
        study.instanceCount = rs.getInt("instance_count");
        study.totalSize = rs.getLong("total_size");
        study.sourceRoute = rs.getString("source_route");
        study.indexedAt = rs.getString("indexed_at");
        study.filePaths = rs.getString("file_paths");
        return study;
    }

    /**
     * Get index statistics.
     */
    public IndexStats getIndexStats() {
        IndexStats stats = new IndexStats();
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM dicom_studies");
            if (rs.next()) stats.studyCount = rs.getInt(1);

            rs = stmt.executeQuery("SELECT COUNT(*) FROM dicom_series");
            if (rs.next()) stats.seriesCount = rs.getInt(1);

            rs = stmt.executeQuery("SELECT COUNT(*) FROM dicom_instances");
            if (rs.next()) stats.instanceCount = rs.getInt(1);

            rs = stmt.executeQuery("SELECT SUM(total_size) FROM dicom_studies");
            if (rs.next()) stats.totalSizeBytes = rs.getLong(1);

            rs = stmt.executeQuery("SELECT COUNT(*) FROM dicom_custom_fields WHERE enabled = 1");
            if (rs.next()) stats.customFieldCount = rs.getInt(1);

            rs = stmt.executeQuery("SELECT MIN(indexed_at), MAX(indexed_at) FROM dicom_studies");
            if (rs.next()) {
                stats.oldestIndexedAt = rs.getString(1);
                stats.newestIndexedAt = rs.getString(2);
            }
        } catch (SQLException e) {
            log.error("Failed to get index stats: {}", e.getMessage(), e);
        }
        return stats;
    }

    /**
     * Get study counts grouped by study date.
     *
     * @param fromDate optional start date (YYYYMMDD format)
     * @param toDate optional end date (YYYYMMDD format)
     * @return list of DateCount objects with date and count
     */
    public List<DateCount> getStudiesByDate(String fromDate, String toDate) {
        List<DateCount> results = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT study_date, COUNT(*) as count FROM dicom_studies WHERE study_date IS NOT NULL AND study_date != ''");
        List<Object> params = new ArrayList<>();

        if (fromDate != null && !fromDate.isEmpty()) {
            sql.append(" AND study_date >= ?");
            params.add(fromDate);
        }
        if (toDate != null && !toDate.isEmpty()) {
            sql.append(" AND study_date <= ?");
            params.add(toDate);
        }

        sql.append(" GROUP BY study_date ORDER BY study_date ASC");

        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                DateCount dc = new DateCount();
                dc.date = rs.getString("study_date");
                dc.count = rs.getInt("count");
                results.add(dc);
            }
        } catch (SQLException e) {
            log.error("Failed to get studies by date: {}", e.getMessage(), e);
        }
        return results;
    }

    /**
     * Simple POJO for date/count pairs.
     */
    public static class DateCount {
        public String date;
        public int count;
    }

    /**
     * Simple POJO for field value/count pairs.
     */
    public static class FieldCount {
        public String value;
        public int count;
    }

    /**
     * Get aggregated study counts for a specific field.
     * Supported fields: patient_sex, modalities, institution_name, source_route
     *
     * @param field the database column name to aggregate
     * @param limit maximum number of results (top N), 0 for unlimited
     * @return list of FieldCount with value and count, ordered by count descending
     */
    public List<FieldCount> getStudyAggregation(String field, int limit) {
        List<FieldCount> results = new ArrayList<>();

        // Whitelist allowed fields for safety
        if (!isAllowedAggregationField(field)) {
            log.warn("Invalid aggregation field requested: {}", field);
            return results;
        }

        String sql = "SELECT " + field + " as value, COUNT(*) as count FROM dicom_studies " +
                     "WHERE " + field + " IS NOT NULL AND " + field + " != '' " +
                     "GROUP BY " + field + " ORDER BY count DESC";
        if (limit > 0) {
            sql += " LIMIT " + limit;
        }

        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                FieldCount fc = new FieldCount();
                fc.value = rs.getString("value");
                fc.count = rs.getInt("count");
                results.add(fc);
            }
        } catch (SQLException e) {
            log.error("Failed to get aggregation for {}: {}", field, e.getMessage(), e);
        }
        return results;
    }

    /**
     * Get aggregated series counts for a specific field.
     * Supported fields: modality, body_part
     *
     * @param field the database column name to aggregate
     * @param limit maximum number of results (top N), 0 for unlimited
     * @return list of FieldCount with value and count, ordered by count descending
     */
    public List<FieldCount> getSeriesAggregation(String field, int limit) {
        List<FieldCount> results = new ArrayList<>();

        // Whitelist allowed fields for safety
        if (!isAllowedSeriesAggregationField(field)) {
            log.warn("Invalid series aggregation field requested: {}", field);
            return results;
        }

        String sql = "SELECT " + field + " as value, COUNT(*) as count FROM dicom_series " +
                     "WHERE " + field + " IS NOT NULL AND " + field + " != '' " +
                     "GROUP BY " + field + " ORDER BY count DESC";
        if (limit > 0) {
            sql += " LIMIT " + limit;
        }

        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);
            while (rs.next()) {
                FieldCount fc = new FieldCount();
                fc.value = rs.getString("value");
                fc.count = rs.getInt("count");
                results.add(fc);
            }
        } catch (SQLException e) {
            log.error("Failed to get series aggregation for {}: {}", field, e.getMessage(), e);
        }
        return results;
    }

    /**
     * Get all aggregations for the dashboard (unfiltered).
     * Returns aggregations for: patient_sex, modalities, institution_name, source_route, modality (series), body_part (series)
     */
    public Map<String, List<FieldCount>> getAllAggregations() {
        return getAllAggregations(null);
    }

    /**
     * Get all aggregations for the dashboard with optional filter criteria.
     * When criteria is provided, aggregations are computed only for matching studies.
     * Used for interactive filtering in the UI where clicking a chart updates other charts.
     *
     * @param criteria optional search criteria to filter aggregations
     * @return map of aggregation name to list of field counts
     */
    public Map<String, List<FieldCount>> getAllAggregations(SearchCriteria criteria) {
        Map<String, List<FieldCount>> aggregations = new java.util.LinkedHashMap<>();

        // Build WHERE clause from criteria
        String whereClause = "";
        List<Object> params = new ArrayList<>();
        if (criteria != null) {
            StringBuilder whereParts = new StringBuilder();
            buildCriteriaWhere(criteria, whereParts, params);
            if (whereParts.length() > 0) {
                whereClause = whereParts.toString();
            }
        }

        // Study-level aggregations
        aggregations.put("patientSex", getFilteredStudyAggregation("patient_sex", 0, whereClause, params));
        aggregations.put("modalities", getFilteredStudyAggregation("modalities", 20, whereClause, params));
        aggregations.put("institutionName", getFilteredStudyAggregation("institution_name", 20, whereClause, params));
        aggregations.put("sourceRoute", getFilteredStudyAggregation("source_route", 0, whereClause, params));
        aggregations.put("referringPhysician", getFilteredStudyAggregation("referring_physician", 20, whereClause, params));

        // Series-level aggregations (need to join with studies if filtered)
        aggregations.put("modality", getFilteredSeriesAggregation("modality", 0, whereClause, params));
        aggregations.put("bodyPart", getFilteredSeriesAggregation("body_part", 20, whereClause, params));

        // Add total matching count
        int totalCount = getFilteredStudyCount(whereClause, params);
        List<FieldCount> totalList = new ArrayList<>();
        FieldCount total = new FieldCount();
        total.value = "total";
        total.count = totalCount;
        totalList.add(total);
        aggregations.put("_total", totalList);

        return aggregations;
    }

    /**
     * Build WHERE clause parts from search criteria.
     */
    private void buildCriteriaWhere(SearchCriteria criteria, StringBuilder whereParts, List<Object> params) {
        if (criteria.patientId != null && !criteria.patientId.isEmpty()) {
            whereParts.append(" AND patient_id LIKE ?");
            params.add("%" + criteria.patientId + "%");
        }
        if (criteria.patientName != null && !criteria.patientName.isEmpty()) {
            whereParts.append(" AND patient_name LIKE ?");
            params.add("%" + criteria.patientName + "%");
        }
        if (criteria.studyDateFrom != null && !criteria.studyDateFrom.isEmpty()) {
            whereParts.append(" AND study_date >= ?");
            params.add(criteria.studyDateFrom);
        }
        if (criteria.studyDateTo != null && !criteria.studyDateTo.isEmpty()) {
            whereParts.append(" AND study_date <= ?");
            params.add(criteria.studyDateTo);
        }
        if (criteria.modality != null && !criteria.modality.isEmpty()) {
            whereParts.append(" AND modalities LIKE ?");
            params.add("%" + criteria.modality + "%");
        }
        if (criteria.accessionNumber != null && !criteria.accessionNumber.isEmpty()) {
            whereParts.append(" AND accession_number LIKE ?");
            params.add("%" + criteria.accessionNumber + "%");
        }
        if (criteria.institutionName != null && !criteria.institutionName.isEmpty()) {
            whereParts.append(" AND institution_name LIKE ?");
            params.add("%" + criteria.institutionName + "%");
        }
        if (criteria.studyDescription != null && !criteria.studyDescription.isEmpty()) {
            whereParts.append(" AND study_description LIKE ?");
            params.add("%" + criteria.studyDescription + "%");
        }
        if (criteria.sourceRoute != null && !criteria.sourceRoute.isEmpty()) {
            whereParts.append(" AND source_route = ?");
            params.add(criteria.sourceRoute);
        }
        if (criteria.patientSex != null && !criteria.patientSex.isEmpty()) {
            whereParts.append(" AND patient_sex = ?");
            params.add(criteria.patientSex);
        }
        if (criteria.bodyPart != null && !criteria.bodyPart.isEmpty()) {
            whereParts.append(" AND study_uid IN (SELECT DISTINCT study_uid FROM dicom_series WHERE body_part = ?)");
            params.add(criteria.bodyPart);
        }
    }

    /**
     * Get filtered study aggregation with WHERE clause.
     */
    private List<FieldCount> getFilteredStudyAggregation(String field, int limit, String whereClause, List<Object> params) {
        List<FieldCount> results = new ArrayList<>();

        if (!isAllowedAggregationField(field)) {
            log.warn("Invalid aggregation field requested: {}", field);
            return results;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(field).append(" as value, COUNT(*) as count FROM dicom_studies ");
        sql.append("WHERE ").append(field).append(" IS NOT NULL AND ").append(field).append(" != ''");
        sql.append(whereClause);
        sql.append(" GROUP BY ").append(field).append(" ORDER BY count DESC");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }

        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    stmt.setString(i + 1, (String) param);
                } else if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                }
            }
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                FieldCount fc = new FieldCount();
                fc.value = rs.getString("value");
                fc.count = rs.getInt("count");
                results.add(fc);
            }
        } catch (SQLException e) {
            log.error("Failed to get filtered aggregation for {}: {}", field, e.getMessage(), e);
        }
        return results;
    }

    /**
     * Get filtered series aggregation with WHERE clause (joins with studies).
     */
    private List<FieldCount> getFilteredSeriesAggregation(String field, int limit, String whereClause, List<Object> params) {
        List<FieldCount> results = new ArrayList<>();

        if (!isAllowedSeriesAggregationField(field)) {
            log.warn("Invalid series aggregation field requested: {}", field);
            return results;
        }

        StringBuilder sql = new StringBuilder();
        if (whereClause.isEmpty()) {
            // No filter - simple aggregation
            sql.append("SELECT ").append(field).append(" as value, COUNT(*) as count FROM dicom_series ");
            sql.append("WHERE ").append(field).append(" IS NOT NULL AND ").append(field).append(" != ''");
        } else {
            // Join with studies to apply filter
            sql.append("SELECT ser.").append(field).append(" as value, COUNT(*) as count ");
            sql.append("FROM dicom_series ser ");
            sql.append("JOIN dicom_studies s ON ser.study_uid = s.study_uid ");
            sql.append("WHERE ser.").append(field).append(" IS NOT NULL AND ser.").append(field).append(" != ''");
            sql.append(whereClause);
        }
        sql.append(" GROUP BY ").append(whereClause.isEmpty() ? field : "ser." + field).append(" ORDER BY count DESC");
        if (limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }

        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    stmt.setString(i + 1, (String) param);
                } else if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                }
            }
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                FieldCount fc = new FieldCount();
                fc.value = rs.getString("value");
                fc.count = rs.getInt("count");
                results.add(fc);
            }
        } catch (SQLException e) {
            log.error("Failed to get filtered series aggregation for {}: {}", field, e.getMessage(), e);
        }
        return results;
    }

    /**
     * Get count of studies matching the filter.
     */
    private int getFilteredStudyCount(String whereClause, List<Object> params) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dicom_studies WHERE 1=1");
        sql.append(whereClause);

        try (PreparedStatement stmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String) {
                    stmt.setString(i + 1, (String) param);
                } else if (param instanceof Integer) {
                    stmt.setInt(i + 1, (Integer) param);
                }
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Failed to get filtered study count: {}", e.getMessage(), e);
        }
        return 0;
    }

    private boolean isAllowedAggregationField(String field) {
        return "patient_sex".equals(field) ||
               "modalities".equals(field) ||
               "institution_name".equals(field) ||
               "source_route".equals(field) ||
               "referring_physician".equals(field);
    }

    private boolean isAllowedSeriesAggregationField(String field) {
        return "modality".equals(field) ||
               "body_part".equals(field);
    }

    // ========================================================================
    // Custom Field Methods
    // ========================================================================

    /**
     * Add a custom DICOM field to extract and index.
     */
    public CustomField addCustomField(CustomField field) {
        String sql = "INSERT INTO dicom_custom_fields (field_name, display_name, dicom_tag, level, " +
                     "field_type, searchable, display_in_list, enabled, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String now = Instant.now().toString();
        try (PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, field.fieldName);
            stmt.setString(2, field.displayName);
            stmt.setString(3, field.dicomTag);
            stmt.setString(4, field.level);
            stmt.setString(5, field.fieldType);
            stmt.setInt(6, field.searchable ? 1 : 0);
            stmt.setInt(7, field.displayInList ? 1 : 0);
            stmt.setInt(8, field.enabled ? 1 : 0);
            stmt.setString(9, now);
            stmt.setString(10, now);
            stmt.executeUpdate();

            ResultSet keys = stmt.getGeneratedKeys();
            if (keys.next()) {
                field.id = keys.getLong(1);
            }
            field.createdAt = now;
            field.updatedAt = now;
            return field;
        } catch (SQLException e) {
            log.error("Failed to add custom field {}: {}", field.fieldName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Update a custom field.
     */
    public boolean updateCustomField(CustomField field) {
        String sql = "UPDATE dicom_custom_fields SET display_name = ?, dicom_tag = ?, level = ?, " +
                     "field_type = ?, searchable = ?, display_in_list = ?, enabled = ?, updated_at = ? " +
                     "WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, field.displayName);
            stmt.setString(2, field.dicomTag);
            stmt.setString(3, field.level);
            stmt.setString(4, field.fieldType);
            stmt.setInt(5, field.searchable ? 1 : 0);
            stmt.setInt(6, field.displayInList ? 1 : 0);
            stmt.setInt(7, field.enabled ? 1 : 0);
            stmt.setString(8, Instant.now().toString());
            stmt.setLong(9, field.id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to update custom field {}: {}", field.id, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Delete a custom field and its values.
     */
    public boolean deleteCustomField(long fieldId) {
        try {
            // Delete values first
            try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM dicom_custom_values WHERE field_id = ?")) {
                stmt.setLong(1, fieldId);
                stmt.executeUpdate();
            }
            // Delete field
            try (PreparedStatement stmt = connection.prepareStatement(
                    "DELETE FROM dicom_custom_fields WHERE id = ?")) {
                stmt.setLong(1, fieldId);
                return stmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.error("Failed to delete custom field {}: {}", fieldId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Get all custom fields.
     */
    public List<CustomField> getAllCustomFields() {
        List<CustomField> fields = new ArrayList<>();
        String sql = "SELECT * FROM dicom_custom_fields ORDER BY display_name";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                fields.add(extractCustomField(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to get custom fields: {}", e.getMessage(), e);
        }
        return fields;
    }

    /**
     * Get enabled custom fields.
     */
    public List<CustomField> getEnabledCustomFields() {
        List<CustomField> fields = new ArrayList<>();
        String sql = "SELECT * FROM dicom_custom_fields WHERE enabled = 1 ORDER BY display_name";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                fields.add(extractCustomField(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to get enabled custom fields: {}", e.getMessage(), e);
        }
        return fields;
    }

    /**
     * Get custom field by ID.
     */
    public CustomField getCustomField(long id) {
        String sql = "SELECT * FROM dicom_custom_fields WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return extractCustomField(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get custom field {}: {}", id, e.getMessage(), e);
        }
        return null;
    }

    private CustomField extractCustomField(ResultSet rs) throws SQLException {
        CustomField field = new CustomField();
        field.id = rs.getLong("id");
        field.fieldName = rs.getString("field_name");
        field.displayName = rs.getString("display_name");
        field.dicomTag = rs.getString("dicom_tag");
        field.level = rs.getString("level");
        field.fieldType = rs.getString("field_type");
        field.searchable = rs.getInt("searchable") == 1;
        field.displayInList = rs.getInt("display_in_list") == 1;
        field.enabled = rs.getInt("enabled") == 1;
        field.createdAt = rs.getString("created_at");
        field.updatedAt = rs.getString("updated_at");
        return field;
    }

    /**
     * Store a custom field value for an entity.
     */
    public void setCustomFieldValue(long fieldId, String entityUid, String value) {
        String sql = "INSERT INTO dicom_custom_values (field_id, entity_uid, value, indexed_at) " +
                     "VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT(field_id, entity_uid) DO UPDATE SET " +
                     "value = excluded.value, indexed_at = excluded.indexed_at";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, fieldId);
            stmt.setString(2, entityUid);
            stmt.setString(3, value);
            stmt.setString(4, Instant.now().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to set custom field value: {}", e.getMessage(), e);
        }
    }

    /**
     * Get custom field values for an entity.
     */
    public Map<String, String> getCustomFieldValues(String entityUid) {
        Map<String, String> values = new java.util.HashMap<>();
        String sql = "SELECT cf.field_name, cv.value FROM dicom_custom_values cv " +
                     "JOIN dicom_custom_fields cf ON cv.field_id = cf.id " +
                     "WHERE cv.entity_uid = ? AND cf.enabled = 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, entityUid);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                values.put(rs.getString("field_name"), rs.getString("value"));
            }
        } catch (SQLException e) {
            log.error("Failed to get custom field values for {}: {}", entityUid, e.getMessage(), e);
        }
        return values;
    }

    // ========================================================================
    // Reindex Job Methods
    // ========================================================================

    /**
     * Create a new reindex job.
     */
    public ReindexJob createReindexJob() {
        String sql = "INSERT INTO dicom_reindex_jobs (status, created_at) VALUES ('pending', ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, Instant.now().toString());
            stmt.executeUpdate();

            // SQLite: use last_insert_rowid() instead of getGeneratedKeys()
            try (Statement idStmt = connection.createStatement();
                 ResultSet rs = idStmt.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) {
                    return getReindexJob(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to create reindex job: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Get a reindex job by ID.
     */
    public ReindexJob getReindexJob(long id) {
        String sql = "SELECT * FROM dicom_reindex_jobs WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                ReindexJob job = new ReindexJob();
                job.id = rs.getLong("id");
                job.status = rs.getString("status");
                job.totalFiles = rs.getInt("total_files");
                job.processedFiles = rs.getInt("processed_files");
                job.errorCount = rs.getInt("error_count");
                job.startedAt = rs.getString("started_at");
                job.completedAt = rs.getString("completed_at");
                job.errorMessage = rs.getString("error_message");
                job.createdAt = rs.getString("created_at");
                return job;
            }
        } catch (SQLException e) {
            log.error("Failed to get reindex job {}: {}", id, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Update reindex job progress.
     */
    public void updateReindexJob(long jobId, String status, int totalFiles, int processedFiles,
                                  int errorCount, String errorMessage) {
        String sql = "UPDATE dicom_reindex_jobs SET status = ?, total_files = ?, processed_files = ?, " +
                     "error_count = ?, error_message = ?, " +
                     "started_at = COALESCE(started_at, CASE WHEN ? = 'running' THEN ? ELSE NULL END), " +
                     "completed_at = CASE WHEN ? IN ('completed', 'failed') THEN ? ELSE NULL END " +
                     "WHERE id = ?";
        String now = Instant.now().toString();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setInt(2, totalFiles);
            stmt.setInt(3, processedFiles);
            stmt.setInt(4, errorCount);
            stmt.setString(5, errorMessage);
            stmt.setString(6, status);
            stmt.setString(7, now);
            stmt.setString(8, status);
            stmt.setString(9, now);
            stmt.setLong(10, jobId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update reindex job {}: {}", jobId, e.getMessage(), e);
        }
    }

    /**
     * Get the latest reindex job.
     */
    public ReindexJob getLatestReindexJob() {
        String sql = "SELECT * FROM dicom_reindex_jobs ORDER BY id DESC LIMIT 1";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                ReindexJob job = new ReindexJob();
                job.id = rs.getLong("id");
                job.status = rs.getString("status");
                job.totalFiles = rs.getInt("total_files");
                job.processedFiles = rs.getInt("processed_files");
                job.errorCount = rs.getInt("error_count");
                job.startedAt = rs.getString("started_at");
                job.completedAt = rs.getString("completed_at");
                job.errorMessage = rs.getString("error_message");
                job.createdAt = rs.getString("created_at");
                return job;
            }
        } catch (SQLException e) {
            log.error("Failed to get latest reindex job: {}", e.getMessage(), e);
        }
        return null;
    }

    /**
     * Clear all index data (for full reindex).
     */
    public void clearIndex() {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("DELETE FROM dicom_custom_values");
            stmt.executeUpdate("DELETE FROM dicom_instances");
            stmt.executeUpdate("DELETE FROM dicom_series");
            stmt.executeUpdate("DELETE FROM dicom_studies");
            log.info("Cleared DICOM index");
        } catch (SQLException e) {
            log.error("Failed to clear index: {}", e.getMessage(), e);
        }
    }

    /**
     * Get the database connection for advanced operations.
     */
    public Connection getConnection() {
        return connection;
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Get the database file path.
     */
    public String getDbPath() {
        return dbPath;
    }

    /**
     * Get database statistics.
     */
    public DatabaseStats getDatabaseStats() {
        DatabaseStats stats = new DatabaseStats();
        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM settings");
            if (rs.next()) stats.settingsCount = rs.getInt(1);

            rs = stmt.executeQuery("SELECT COUNT(*) FROM metrics_minute");
            if (rs.next()) stats.minuteMetricsCount = rs.getInt(1);

            rs = stmt.executeQuery("SELECT COUNT(*) FROM metrics_hour");
            if (rs.next()) stats.hourMetricsCount = rs.getInt(1);

            rs = stmt.executeQuery("SELECT COUNT(*) FROM metrics_day");
            if (rs.next()) stats.dayMetricsCount = rs.getInt(1);

            rs = stmt.executeQuery("SELECT COUNT(*) FROM route_stats");
            if (rs.next()) stats.routeStatsCount = rs.getInt(1);

            // Get file size
            File dbFile = new File(dbPath);
            if (dbFile.exists()) {
                stats.fileSizeBytes = dbFile.length();
            }
        } catch (SQLException e) {
            log.error("Failed to get database stats: {}", e.getMessage(), e);
        }
        return stats;
    }

    /**
     * Close the database connection.
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("RouterStore closed");
            }
        } catch (SQLException e) {
            log.error("Error closing RouterStore: {}", e.getMessage(), e);
        }
    }

    // ========================================================================
    // Data Classes
    // ========================================================================

    public static class MetricPoint {
        public long timestamp;
        public int transfers;
        public int successful;
        public int failed;
        public long bytes;
        public int files;

        public String getTimestampIso() {
            return Instant.ofEpochMilli(timestamp).toString();
        }
    }

    public static class RouteStats {
        public String aeTitle;
        public long totalTransfers;
        public long successfulTransfers;
        public long failedTransfers;
        public long totalBytes;
        public long totalFiles;
        public String firstTransferAt;
        public String lastTransferAt;

        public double getSuccessRate() {
            if (totalTransfers == 0) return 0;
            return (successfulTransfers * 100.0) / totalTransfers;
        }
    }

    public static class DatabaseStats {
        public int settingsCount;
        public int minuteMetricsCount;
        public int hourMetricsCount;
        public int dayMetricsCount;
        public int routeStatsCount;
        public long fileSizeBytes;

        public String getFormattedSize() {
            if (fileSizeBytes < 1024) return fileSizeBytes + " B";
            if (fileSizeBytes < 1024 * 1024) return String.format("%.1f KB", fileSizeBytes / 1024.0);
            return String.format("%.1f MB", fileSizeBytes / (1024.0 * 1024.0));
        }
    }

    // ========================================================================
    // DICOM Index Data Classes
    // ========================================================================

    public static class IndexedStudy {
        public long id;
        public String studyUid;
        public String patientId;
        public String patientName;
        public String patientSex;  // M, F, O (Other)
        public String studyDate;
        public String studyTime;
        public String accessionNumber;
        public String studyDescription;
        public String modalities;
        public String institutionName;
        public String referringPhysician;
        public int seriesCount;
        public int instanceCount;
        public long totalSize;
        public String sourceRoute;
        public String indexedAt;
        public String filePaths;  // JSON array
        public Map<String, String> customFields;  // Populated separately

        public String getFormattedSize() {
            if (totalSize < 1024) return totalSize + " B";
            if (totalSize < 1024 * 1024) return String.format("%.1f KB", totalSize / 1024.0);
            if (totalSize < 1024 * 1024 * 1024) return String.format("%.1f MB", totalSize / (1024.0 * 1024.0));
            return String.format("%.2f GB", totalSize / (1024.0 * 1024.0 * 1024.0));
        }
    }

    public static class IndexedSeries {
        public long id;
        public String seriesUid;
        public String studyUid;
        public String modality;
        public int seriesNumber;
        public String seriesDescription;
        public String bodyPart;
        public int instanceCount;
        public String indexedAt;
        public Map<String, String> customFields;
    }

    public static class IndexedInstance {
        public long id;
        public String sopInstanceUid;
        public String seriesUid;
        public String sopClassUid;
        public int instanceNumber;
        public String filePath;
        public long fileSize;
        public String fileHash;
        public String indexedAt;
        public Map<String, String> customFields;
    }

    public static class SearchCriteria {
        public String patientId;
        public String patientName;
        public String patientSex;       // M, F, O (Other)
        public String studyDateFrom;
        public String studyDateTo;
        public String modality;
        public String accessionNumber;
        public String institutionName;
        public String studyDescription;
        public String sourceRoute;
        public String bodyPart;         // For filtering by series body part
        public Map<String, String> customFields;  // field_name -> value pattern
        public int limit = 100;
        public int offset = 0;
    }

    public static class IndexStats {
        public int studyCount;
        public int seriesCount;
        public int instanceCount;
        public long totalSizeBytes;
        public int customFieldCount;
        public String oldestIndexedAt;
        public String newestIndexedAt;

        public String getFormattedSize() {
            if (totalSizeBytes < 1024) return totalSizeBytes + " B";
            if (totalSizeBytes < 1024 * 1024) return String.format("%.1f KB", totalSizeBytes / 1024.0);
            if (totalSizeBytes < 1024 * 1024 * 1024) return String.format("%.1f MB", totalSizeBytes / (1024.0 * 1024.0));
            return String.format("%.2f GB", totalSizeBytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    public static class CustomField {
        public long id;
        public String fieldName;      // e.g., "manufacturer"
        public String displayName;    // e.g., "Manufacturer"
        public String dicomTag;       // e.g., "0008,0070" or "Manufacturer"
        public String level = "study";  // study, series, or instance
        public String fieldType = "string";  // string, number, date
        public boolean searchable = true;
        public boolean displayInList = true;
        public boolean enabled = true;
        public String createdAt;
        public String updatedAt;
    }

    public static class ReindexJob {
        public long id;
        public String status;  // pending, running, completed, failed
        public int totalFiles;
        public int processedFiles;
        public int errorCount;
        public String startedAt;
        public String completedAt;
        public String errorMessage;
        public String createdAt;

        public double getProgress() {
            if (totalFiles == 0) return 0;
            return (processedFiles * 100.0) / totalFiles;
        }
    }
}
