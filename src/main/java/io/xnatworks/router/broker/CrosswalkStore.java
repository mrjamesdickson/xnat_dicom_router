/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persistent storage for Honest Broker crosswalk mappings using SQLite.
 *
 * <p>Stores the mapping between original patient identifiers and their
 * de-identified equivalents for audit purposes and consistency.</p>
 *
 * <p>Schema:
 * <ul>
 *   <li>crosswalk - main mapping table (id_in → id_out)</li>
 *   <li>crosswalk_log - audit log of all lookups/creates</li>
 * </ul>
 * </p>
 */
public class CrosswalkStore {
    private static final Logger log = LoggerFactory.getLogger(CrosswalkStore.class);
    private static final DateTimeFormatter BACKUP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final int DEFAULT_BACKUP_RETENTION_DAYS = 30;
    private static final int DEFAULT_MAX_BACKUPS = 10;

    private final String dbPath;
    private final String backupDirectory;
    private Connection connection;
    private ScheduledExecutorService backupScheduler;
    private int backupRetentionDays = DEFAULT_BACKUP_RETENTION_DAYS;
    private int maxBackups = DEFAULT_MAX_BACKUPS;

    public CrosswalkStore(String dataDirectory) {
        this.dbPath = dataDirectory + File.separator + "crosswalk.db";
        this.backupDirectory = dataDirectory + File.separator + "backups";
        initialize();
        startScheduledBackups();
    }

    /**
     * Initialize the database and create tables if needed.
     */
    private void initialize() {
        try {
            // Ensure directory exists
            File dbFile = new File(dbPath);
            dbFile.getParentFile().mkdirs();

            // Load SQLite JDBC driver
            Class.forName("org.sqlite.JDBC");

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            createTables();
            log.info("CrosswalkStore initialized at: {}", dbPath);
        } catch (Exception e) {
            log.error("Failed to initialize CrosswalkStore: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize crosswalk database", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Main crosswalk table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS crosswalk (" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    broker_name TEXT NOT NULL," +
                "    id_in TEXT NOT NULL," +
                "    id_out TEXT NOT NULL," +
                "    id_type TEXT DEFAULT 'patient_id'," +
                "    created_at TEXT NOT NULL," +
                "    updated_at TEXT NOT NULL," +
                "    UNIQUE(broker_name, id_in, id_type)" +
                ")");

            // Index for fast lookups
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_crosswalk_lookup " +
                "ON crosswalk(broker_name, id_in, id_type)");

            // Reverse lookup index
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_crosswalk_reverse " +
                "ON crosswalk(broker_name, id_out, id_type)");

            // Audit log table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS crosswalk_log (" +
                "    id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "    broker_name TEXT NOT NULL," +
                "    action TEXT NOT NULL," +
                "    id_in TEXT," +
                "    id_out TEXT," +
                "    id_type TEXT," +
                "    route_ae_title TEXT," +
                "    destination TEXT," +
                "    study_uid TEXT," +
                "    details TEXT," +
                "    timestamp TEXT NOT NULL" +
                ")");

            // Index for log queries
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_crosswalk_log_time " +
                "ON crosswalk_log(timestamp DESC)");
        }
    }

    /**
     * Look up the de-identified ID for a given original ID.
     *
     * @param brokerName Name of the broker configuration
     * @param idIn Original ID (e.g., PatientID)
     * @param idType Type of ID (patient_id, patient_name, accession)
     * @return The de-identified ID, or null if not found
     */
    public String lookup(String brokerName, String idIn, String idType) {
        String sql = "SELECT id_out FROM crosswalk WHERE broker_name = ? AND id_in = ? AND id_type = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, brokerName);
            stmt.setString(2, idIn);
            stmt.setString(3, idType);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("id_out");
            }
        } catch (SQLException e) {
            log.error("Failed to lookup crosswalk for broker={} idIn={}: {}", brokerName, idIn, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Reverse lookup - get original ID from de-identified ID.
     *
     * @param brokerName Name of the broker configuration
     * @param idOut De-identified ID
     * @param idType Type of ID
     * @return The original ID, or null if not found
     */
    public String reverseLookup(String brokerName, String idOut, String idType) {
        String sql = "SELECT id_in FROM crosswalk WHERE broker_name = ? AND id_out = ? AND id_type = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, brokerName);
            stmt.setString(2, idOut);
            stmt.setString(3, idType);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("id_in");
            }
        } catch (SQLException e) {
            log.error("Failed to reverse lookup crosswalk for broker={} idOut={}: {}", brokerName, idOut, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Store a new mapping or update an existing one.
     *
     * @param brokerName Name of the broker configuration
     * @param idIn Original ID
     * @param idOut De-identified ID
     * @param idType Type of ID
     * @return true if successful
     */
    public boolean store(String brokerName, String idIn, String idOut, String idType) {
        String now = Instant.now().toString();
        String sql =
            "INSERT INTO crosswalk (broker_name, id_in, id_out, id_type, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(broker_name, id_in, id_type) DO UPDATE SET " +
            "id_out = excluded.id_out, updated_at = excluded.updated_at";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, brokerName);
            stmt.setString(2, idIn);
            stmt.setString(3, idOut);
            stmt.setString(4, idType);
            stmt.setString(5, now);
            stmt.setString(6, now);

            stmt.executeUpdate();
            log.debug("Stored crosswalk mapping: broker={} {} {} -> {}", brokerName, idType, idIn, idOut);
            return true;
        } catch (SQLException e) {
            log.error("Failed to store crosswalk mapping: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Log a crosswalk operation for audit purposes.
     *
     * @param brokerName Name of the broker
     * @param action Action performed (lookup, create, route)
     * @param idIn Original ID
     * @param idOut De-identified ID
     * @param idType Type of ID
     * @param routeAeTitle AE title of the route (optional)
     * @param destination Destination name (optional)
     * @param studyUid Study UID (optional)
     * @param details Additional details (optional)
     */
    public void logOperation(String brokerName, String action, String idIn, String idOut,
                             String idType, String routeAeTitle, String destination,
                             String studyUid, String details) {
        String sql =
            "INSERT INTO crosswalk_log " +
            "(broker_name, action, id_in, id_out, id_type, route_ae_title, destination, study_uid, details, timestamp) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, brokerName);
            stmt.setString(2, action);
            stmt.setString(3, idIn);
            stmt.setString(4, idOut);
            stmt.setString(5, idType);
            stmt.setString(6, routeAeTitle);
            stmt.setString(7, destination);
            stmt.setString(8, studyUid);
            stmt.setString(9, details);
            stmt.setString(10, Instant.now().toString());

            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to log crosswalk operation: {}", e.getMessage(), e);
        }
    }

    /**
     * Get recent crosswalk log entries.
     *
     * @param limit Maximum number of entries to return
     * @return List of log entries
     */
    public List<CrosswalkLogEntry> getRecentLogs(int limit) {
        List<CrosswalkLogEntry> entries = new ArrayList<>();
        String sql = "SELECT * FROM crosswalk_log ORDER BY timestamp DESC LIMIT ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                CrosswalkLogEntry entry = new CrosswalkLogEntry();
                entry.setId(rs.getLong("id"));
                entry.setBrokerName(rs.getString("broker_name"));
                entry.setAction(rs.getString("action"));
                entry.setIdIn(rs.getString("id_in"));
                entry.setIdOut(rs.getString("id_out"));
                entry.setIdType(rs.getString("id_type"));
                entry.setRouteAeTitle(rs.getString("route_ae_title"));
                entry.setDestination(rs.getString("destination"));
                entry.setStudyUid(rs.getString("study_uid"));
                entry.setDetails(rs.getString("details"));
                entry.setTimestamp(rs.getString("timestamp"));
                entries.add(entry);
            }
        } catch (SQLException e) {
            log.error("Failed to get crosswalk logs: {}", e.getMessage(), e);
        }

        return entries;
    }

    /**
     * Get all mappings for a broker.
     *
     * @param brokerName Name of the broker
     * @return List of crosswalk entries
     */
    public List<CrosswalkEntry> getMappings(String brokerName) {
        List<CrosswalkEntry> entries = new ArrayList<>();
        String sql = "SELECT * FROM crosswalk WHERE broker_name = ? ORDER BY created_at DESC";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, brokerName);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                CrosswalkEntry entry = new CrosswalkEntry();
                entry.setId(rs.getLong("id"));
                entry.setBrokerName(rs.getString("broker_name"));
                entry.setIdIn(rs.getString("id_in"));
                entry.setIdOut(rs.getString("id_out"));
                entry.setIdType(rs.getString("id_type"));
                entry.setCreatedAt(rs.getString("created_at"));
                entry.setUpdatedAt(rs.getString("updated_at"));
                entries.add(entry);
            }
        } catch (SQLException e) {
            log.error("Failed to get crosswalk mappings: {}", e.getMessage(), e);
        }

        return entries;
    }

    /**
     * Get count of mappings for a broker.
     */
    public int getMappingCount(String brokerName) {
        String sql = "SELECT COUNT(*) FROM crosswalk WHERE broker_name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, brokerName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Failed to get mapping count: {}", e.getMessage(), e);
        }
        return 0;
    }

    // ========================================================================
    // Backup and Restore Methods
    // ========================================================================

    /**
     * Start the scheduled backup task (runs daily).
     */
    private void startScheduledBackups() {
        backupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CrosswalkBackupScheduler");
            t.setDaemon(true);
            return t;
        });

        // Run backup daily at midnight (initial delay calculated to next midnight)
        long initialDelay = calculateDelayToMidnight();
        backupScheduler.scheduleAtFixedRate(() -> {
            try {
                createBackup("scheduled");
                cleanupOldBackups();
            } catch (Exception e) {
                log.error("Scheduled backup failed: {}", e.getMessage(), e);
            }
        }, initialDelay, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);

        log.info("Scheduled daily backups enabled. First backup in {} hours", initialDelay / (1000 * 60 * 60));
    }

    private long calculateDelayToMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return java.time.Duration.between(now, nextMidnight).toMillis();
    }

    /**
     * Create a backup of the crosswalk database.
     *
     * @param reason Description of why backup was created (e.g., "manual", "scheduled", "pre-restore")
     * @return The backup file info, or null if backup failed
     */
    public synchronized BackupInfo createBackup(String reason) {
        try {
            // Ensure backup directory exists
            Path backupDir = Paths.get(backupDirectory);
            Files.createDirectories(backupDir);

            // Generate backup filename with timestamp
            String timestamp = LocalDateTime.now().format(BACKUP_DATE_FORMAT);
            String backupFilename = "crosswalk_" + timestamp + ".db";
            Path backupPath = backupDir.resolve(backupFilename);

            // Force SQLite to checkpoint WAL to main database file
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }

            // Copy database file
            Files.copy(Paths.get(dbPath), backupPath, StandardCopyOption.REPLACE_EXISTING);

            // Get file size
            long sizeBytes = Files.size(backupPath);

            // Get record counts
            int mappingCount = getTotalMappingCount();
            int logCount = getTotalLogCount();

            BackupInfo info = new BackupInfo();
            info.setFilename(backupFilename);
            info.setPath(backupPath.toString());
            info.setTimestamp(timestamp);
            info.setReason(reason);
            info.setSizeBytes(sizeBytes);
            info.setMappingCount(mappingCount);
            info.setLogCount(logCount);

            log.info("Created crosswalk backup: {} ({} mappings, {} logs, {} bytes)",
                    backupFilename, mappingCount, logCount, sizeBytes);

            return info;
        } catch (Exception e) {
            log.error("Failed to create crosswalk backup: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * List all available backups.
     *
     * @return List of backup info sorted by timestamp (newest first)
     */
    public List<BackupInfo> listBackups() {
        List<BackupInfo> backups = new ArrayList<>();
        Path backupDir = Paths.get(backupDirectory);

        if (!Files.exists(backupDir)) {
            return backups;
        }

        try {
            File[] files = backupDir.toFile().listFiles((dir, name) ->
                    name.startsWith("crosswalk_") && name.endsWith(".db"));

            if (files == null) {
                return backups;
            }

            for (File file : files) {
                BackupInfo info = new BackupInfo();
                info.setFilename(file.getName());
                info.setPath(file.getAbsolutePath());
                info.setSizeBytes(file.length());

                // Extract timestamp from filename: crosswalk_YYYYMMDD_HHMMSS.db
                String name = file.getName();
                if (name.length() >= 25) {
                    info.setTimestamp(name.substring(10, 25));
                }

                backups.add(info);
            }

            // Sort by filename (timestamp) descending
            backups.sort((a, b) -> b.getFilename().compareTo(a.getFilename()));

        } catch (Exception e) {
            log.error("Failed to list backups: {}", e.getMessage(), e);
        }

        return backups;
    }

    /**
     * Restore the database from a backup file.
     *
     * @param backupFilename The backup filename to restore from
     * @return true if restore succeeded
     */
    public synchronized boolean restoreFromBackup(String backupFilename) {
        Path backupPath = Paths.get(backupDirectory, backupFilename);

        if (!Files.exists(backupPath)) {
            log.error("Backup file not found: {}", backupFilename);
            return false;
        }

        try {
            // Create a pre-restore backup first
            createBackup("pre-restore");

            // Close current connection
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }

            // Copy backup over current database
            Files.copy(backupPath, Paths.get(dbPath), StandardCopyOption.REPLACE_EXISTING);

            // Reconnect
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

            log.info("Successfully restored crosswalk database from: {}", backupFilename);
            return true;
        } catch (Exception e) {
            log.error("Failed to restore from backup {}: {}", backupFilename, e.getMessage(), e);

            // Try to reconnect to existing database
            try {
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            } catch (SQLException ex) {
                log.error("Failed to reconnect after restore failure: {}", ex.getMessage(), ex);
            }

            return false;
        }
    }

    /**
     * Delete a backup file.
     *
     * @param backupFilename The backup filename to delete
     * @return true if deletion succeeded
     */
    public boolean deleteBackup(String backupFilename) {
        Path backupPath = Paths.get(backupDirectory, backupFilename);

        try {
            if (Files.deleteIfExists(backupPath)) {
                log.info("Deleted backup: {}", backupFilename);
                return true;
            }
            return false;
        } catch (IOException e) {
            log.error("Failed to delete backup {}: {}", backupFilename, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Clean up old backups based on retention policy.
     */
    public void cleanupOldBackups() {
        List<BackupInfo> backups = listBackups();

        if (backups.isEmpty()) {
            return;
        }

        int deleted = 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(backupRetentionDays);

        for (int i = 0; i < backups.size(); i++) {
            BackupInfo backup = backups.get(i);

            // Always keep at least one backup
            if (i == 0) continue;

            // Delete if over max count or past retention period
            boolean overMaxCount = i >= maxBackups;
            boolean pastRetention = false;

            try {
                String ts = backup.getTimestamp();
                if (ts != null && ts.length() >= 15) {
                    LocalDateTime backupTime = LocalDateTime.parse(ts, BACKUP_DATE_FORMAT);
                    pastRetention = backupTime.isBefore(cutoff);
                }
            } catch (Exception e) {
                // Can't parse timestamp, keep the backup
            }

            if (overMaxCount || pastRetention) {
                if (deleteBackup(backup.getFilename())) {
                    deleted++;
                }
            }
        }

        if (deleted > 0) {
            log.info("Cleaned up {} old backups", deleted);
        }
    }

    /**
     * Get total count of all mappings across all brokers.
     */
    public int getTotalMappingCount() {
        String sql = "SELECT COUNT(*) FROM crosswalk";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Failed to get total mapping count: {}", e.getMessage(), e);
        }
        return 0;
    }

    /**
     * Get total count of all log entries.
     */
    public int getTotalLogCount() {
        String sql = "SELECT COUNT(*) FROM crosswalk_log";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Failed to get total log count: {}", e.getMessage(), e);
        }
        return 0;
    }

    /**
     * Export all crosswalk data as CSV.
     *
     * @return CSV string of all mappings
     */
    public String exportToCsv() {
        StringBuilder csv = new StringBuilder();
        csv.append("id,broker_name,id_in,id_out,id_type,created_at,updated_at\n");

        String sql = "SELECT * FROM crosswalk ORDER BY created_at DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                csv.append(rs.getLong("id")).append(",");
                csv.append(escapeCsv(rs.getString("broker_name"))).append(",");
                csv.append(escapeCsv(rs.getString("id_in"))).append(",");
                csv.append(escapeCsv(rs.getString("id_out"))).append(",");
                csv.append(escapeCsv(rs.getString("id_type"))).append(",");
                csv.append(rs.getString("created_at")).append(",");
                csv.append(rs.getString("updated_at")).append("\n");
            }
        } catch (SQLException e) {
            log.error("Failed to export crosswalk to CSV: {}", e.getMessage(), e);
        }

        return csv.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Get the database file path.
     */
    public String getDbPath() {
        return dbPath;
    }

    /**
     * Get the backup directory path.
     */
    public String getBackupDirectory() {
        return backupDirectory;
    }

    /**
     * Set backup retention days.
     */
    public void setBackupRetentionDays(int days) {
        this.backupRetentionDays = days;
    }

    /**
     * Set maximum number of backups to keep.
     */
    public void setMaxBackups(int max) {
        this.maxBackups = max;
    }

    /**
     * Close the database connection.
     */
    public void close() {
        // Stop scheduled backups
        if (backupScheduler != null) {
            backupScheduler.shutdown();
            try {
                backupScheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("CrosswalkStore closed");
            }
        } catch (SQLException e) {
            log.error("Error closing CrosswalkStore: {}", e.getMessage(), e);
        }
    }

    // ========================================================================
    // Data Classes
    // ========================================================================

    public static class CrosswalkEntry {
        private long id;
        private String brokerName;
        private String idIn;
        private String idOut;
        private String idType;
        private String createdAt;
        private String updatedAt;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }

        public String getBrokerName() { return brokerName; }
        public void setBrokerName(String brokerName) { this.brokerName = brokerName; }

        public String getIdIn() { return idIn; }
        public void setIdIn(String idIn) { this.idIn = idIn; }

        public String getIdOut() { return idOut; }
        public void setIdOut(String idOut) { this.idOut = idOut; }

        public String getIdType() { return idType; }
        public void setIdType(String idType) { this.idType = idType; }

        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

        public String getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    }

    public static class CrosswalkLogEntry {
        private long id;
        private String brokerName;
        private String action;
        private String idIn;
        private String idOut;
        private String idType;
        private String routeAeTitle;
        private String destination;
        private String studyUid;
        private String details;
        private String timestamp;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }

        public String getBrokerName() { return brokerName; }
        public void setBrokerName(String brokerName) { this.brokerName = brokerName; }

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }

        public String getIdIn() { return idIn; }
        public void setIdIn(String idIn) { this.idIn = idIn; }

        public String getIdOut() { return idOut; }
        public void setIdOut(String idOut) { this.idOut = idOut; }

        public String getIdType() { return idType; }
        public void setIdType(String idType) { this.idType = idType; }

        public String getRouteAeTitle() { return routeAeTitle; }
        public void setRouteAeTitle(String routeAeTitle) { this.routeAeTitle = routeAeTitle; }

        public String getDestination() { return destination; }
        public void setDestination(String destination) { this.destination = destination; }

        public String getStudyUid() { return studyUid; }
        public void setStudyUid(String studyUid) { this.studyUid = studyUid; }

        public String getDetails() { return details; }
        public void setDetails(String details) { this.details = details; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }

    public static class BackupInfo {
        private String filename;
        private String path;
        private String timestamp;
        private String reason;
        private long sizeBytes;
        private int mappingCount;
        private int logCount;

        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public long getSizeBytes() { return sizeBytes; }
        public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

        public int getMappingCount() { return mappingCount; }
        public void setMappingCount(int mappingCount) { this.mappingCount = mappingCount; }

        public int getLogCount() { return logCount; }
        public void setLogCount(int logCount) { this.logCount = logCount; }

        public String getFormattedSize() {
            if (sizeBytes < 1024) return sizeBytes + " B";
            if (sizeBytes < 1024 * 1024) return String.format("%.1f KB", sizeBytes / 1024.0);
            return String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0));
        }
    }
}
