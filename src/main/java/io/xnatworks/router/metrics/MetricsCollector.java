/*
 * XNAT DICOM Router
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */
package io.xnatworks.router.metrics;

import io.xnatworks.router.store.RouterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects and stores time-series metrics for the DICOM router.
 * Maintains rolling windows of data for dashboard visualization.
 * Persists data to RouterStore for survival across restarts.
 */
public class MetricsCollector {
    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    // How long to keep detailed metrics (1 hour of per-minute data)
    private static final int MINUTE_RETENTION_MINUTES = 60;
    // How long to keep hourly aggregates (24 hours)
    private static final int HOURLY_RETENTION_HOURS = 24;
    // How long to keep daily aggregates (30 days)
    private static final int DAILY_RETENTION_DAYS = 30;

    // Counters for current minute
    private final AtomicLong currentMinuteTransfers = new AtomicLong(0);
    private final AtomicLong currentMinuteSuccessful = new AtomicLong(0);
    private final AtomicLong currentMinuteFailed = new AtomicLong(0);
    private final AtomicLong currentMinuteBytes = new AtomicLong(0);
    private final AtomicLong currentMinuteFiles = new AtomicLong(0);

    // Per-route counters for current minute
    private final ConcurrentHashMap<String, RouteMetrics> routeMetrics = new ConcurrentHashMap<>();

    // Time-series storage (thread-safe)
    private final ConcurrentLinkedDeque<MetricPoint> minuteData = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<MetricPoint> hourlyData = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<MetricPoint> dailyData = new ConcurrentLinkedDeque<>();

    // Per-route time-series
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<MetricPoint>> routeMinuteData = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private volatile long lastMinuteTimestamp;
    private volatile long lastHourTimestamp;
    private volatile long lastDayTimestamp;

    private volatile boolean running = false;

    // Persistent storage (optional - can be null for in-memory only)
    private final RouterStore store;

    /**
     * Create a MetricsCollector with no persistence (in-memory only).
     */
    public MetricsCollector() {
        this(null);
    }

    /**
     * Create a MetricsCollector with persistence to RouterStore.
     * @param store RouterStore instance for persistence, or null for in-memory only
     */
    public MetricsCollector(RouterStore store) {
        this.store = store;
        this.lastMinuteTimestamp = truncateToMinute(System.currentTimeMillis());
        this.lastHourTimestamp = truncateToHour(System.currentTimeMillis());
        this.lastDayTimestamp = truncateToDay(System.currentTimeMillis());

        // Load historical data from store if available
        if (store != null) {
            loadFromStore();
        }
    }

    /**
     * Load historical metrics from the persistent store.
     */
    private void loadFromStore() {
        try {
            // Load minute data (last hour)
            List<RouterStore.MetricPoint> storedMinutes = store.getMinuteMetrics(MINUTE_RETENTION_MINUTES);
            for (RouterStore.MetricPoint sp : storedMinutes) {
                MetricPoint mp = new MetricPoint();
                mp.timestamp = sp.timestamp;
                mp.transfers = sp.transfers;
                mp.successful = sp.successful;
                mp.failed = sp.failed;
                mp.bytes = sp.bytes;
                mp.files = sp.files;
                minuteData.addLast(mp);
            }

            // Load hourly data (last 24 hours)
            List<RouterStore.MetricPoint> storedHours = store.getHourMetrics(HOURLY_RETENTION_HOURS);
            for (RouterStore.MetricPoint sp : storedHours) {
                MetricPoint mp = new MetricPoint();
                mp.timestamp = sp.timestamp;
                mp.transfers = sp.transfers;
                mp.successful = sp.successful;
                mp.failed = sp.failed;
                mp.bytes = sp.bytes;
                mp.files = sp.files;
                hourlyData.addLast(mp);
            }

            // Load daily data (last 30 days)
            List<RouterStore.MetricPoint> storedDays = store.getDayMetrics(DAILY_RETENTION_DAYS);
            for (RouterStore.MetricPoint sp : storedDays) {
                MetricPoint mp = new MetricPoint();
                mp.timestamp = sp.timestamp;
                mp.transfers = sp.transfers;
                mp.successful = sp.successful;
                mp.failed = sp.failed;
                mp.bytes = sp.bytes;
                mp.files = sp.files;
                dailyData.addLast(mp);
            }

            // Load cumulative route stats
            List<RouterStore.RouteStats> storedRouteStats = store.getAllRouteStats();
            for (RouterStore.RouteStats rs : storedRouteStats) {
                RouteMetrics rm = getOrCreateRouteMetrics(rs.aeTitle);
                rm.transfers.set(rs.totalTransfers);
                rm.successful.set(rs.successfulTransfers);
                rm.failed.set(rs.failedTransfers);
                rm.bytes.set(rs.totalBytes);
                rm.files.set(rs.totalFiles);
            }

            log.info("Loaded historical metrics from store: {} minute, {} hour, {} day records, {} routes",
                    minuteData.size(), hourlyData.size(), dailyData.size(), storedRouteStats.size());
        } catch (Exception e) {
            log.error("Failed to load metrics from store: {}", e.getMessage(), e);
        }
    }

    /**
     * Start the metrics collector.
     */
    public void start() {
        if (running) {
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-collector");
            t.setDaemon(true);
            return t;
        });

        // Roll metrics every minute
        scheduler.scheduleAtFixedRate(this::rollMinuteMetrics, 60, 60, TimeUnit.SECONDS);

        running = true;
        log.info("MetricsCollector started");
    }

    /**
     * Stop the metrics collector.
     */
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("MetricsCollector stopped");
    }

    // ==================== Recording Methods ====================

    /**
     * Record a transfer received.
     */
    public void recordTransferReceived(String aeTitle) {
        currentMinuteTransfers.incrementAndGet();
        getOrCreateRouteMetrics(aeTitle).transfers.incrementAndGet();
    }

    /**
     * Record a successful transfer.
     */
    public void recordTransferSuccess(String aeTitle, long bytes, int files) {
        currentMinuteSuccessful.incrementAndGet();
        currentMinuteBytes.addAndGet(bytes);
        currentMinuteFiles.addAndGet(files);

        RouteMetrics rm = getOrCreateRouteMetrics(aeTitle);
        rm.successful.incrementAndGet();
        rm.bytes.addAndGet(bytes);
        rm.files.addAndGet(files);

        // Persist route stats
        if (store != null) {
            store.updateRouteStats(aeTitle, true, bytes, files);
        }
    }

    /**
     * Record a failed transfer.
     */
    public void recordTransferFailed(String aeTitle) {
        currentMinuteFailed.incrementAndGet();
        getOrCreateRouteMetrics(aeTitle).failed.incrementAndGet();

        // Persist route stats
        if (store != null) {
            store.updateRouteStats(aeTitle, false, 0, 0);
        }
    }

    // ==================== Query Methods ====================

    /**
     * Get metrics for the last N minutes.
     */
    public List<MetricPoint> getMinuteMetrics(int minutes) {
        List<MetricPoint> result = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - (minutes * 60 * 1000L);

        for (MetricPoint point : minuteData) {
            if (point.timestamp >= cutoff) {
                result.add(point);
            }
        }

        return result;
    }

    /**
     * Get hourly metrics for the last N hours.
     */
    public List<MetricPoint> getHourlyMetrics(int hours) {
        List<MetricPoint> result = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - (hours * 60 * 60 * 1000L);

        for (MetricPoint point : hourlyData) {
            if (point.timestamp >= cutoff) {
                result.add(point);
            }
        }

        return result;
    }

    /**
     * Get daily metrics for the last N days.
     */
    public List<MetricPoint> getDailyMetrics(int days) {
        List<MetricPoint> result = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L);

        for (MetricPoint point : dailyData) {
            if (point.timestamp >= cutoff) {
                result.add(point);
            }
        }

        return result;
    }

    /**
     * Get metrics for a specific route (last N minutes).
     */
    public List<MetricPoint> getRouteMetrics(String aeTitle, int minutes) {
        ConcurrentLinkedDeque<MetricPoint> data = routeMinuteData.get(aeTitle);
        if (data == null) {
            return Collections.emptyList();
        }

        List<MetricPoint> result = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - (minutes * 60 * 1000L);

        for (MetricPoint point : data) {
            if (point.timestamp >= cutoff) {
                result.add(point);
            }
        }

        return result;
    }

    /**
     * Get current throughput (transfers per minute, averaged over last 5 minutes).
     */
    public double getCurrentThroughput() {
        List<MetricPoint> recent = getMinuteMetrics(5);
        if (recent.isEmpty()) {
            return 0.0;
        }

        long totalTransfers = 0;
        for (MetricPoint p : recent) {
            totalTransfers += p.transfers;
        }

        return totalTransfers / (double) recent.size();
    }

    /**
     * Get current bytes per minute (averaged over last 5 minutes).
     */
    public double getCurrentBytesPerMinute() {
        List<MetricPoint> recent = getMinuteMetrics(5);
        if (recent.isEmpty()) {
            return 0.0;
        }

        long totalBytes = 0;
        for (MetricPoint p : recent) {
            totalBytes += p.bytes;
        }

        return totalBytes / (double) recent.size();
    }

    /**
     * Get all route names that have metrics.
     */
    public Set<String> getRouteNames() {
        return new HashSet<>(routeMetrics.keySet());
    }

    /**
     * Get summary metrics for all routes.
     */
    public Map<String, RouteSummary> getRouteSummaries() {
        Map<String, RouteSummary> summaries = new LinkedHashMap<>();

        for (Map.Entry<String, RouteMetrics> entry : routeMetrics.entrySet()) {
            String aeTitle = entry.getKey();
            RouteMetrics rm = entry.getValue();

            RouteSummary summary = new RouteSummary();
            summary.aeTitle = aeTitle;
            summary.totalTransfers = rm.transfers.get();
            summary.successfulTransfers = rm.successful.get();
            summary.failedTransfers = rm.failed.get();
            summary.totalBytes = rm.bytes.get();
            summary.totalFiles = rm.files.get();

            // Calculate recent throughput from minute data
            List<MetricPoint> routeData = getRouteMetrics(aeTitle, 5);
            if (!routeData.isEmpty()) {
                long recentTransfers = 0;
                for (MetricPoint p : routeData) {
                    recentTransfers += p.transfers;
                }
                summary.recentThroughput = recentTransfers / (double) routeData.size();
            }

            summaries.put(aeTitle, summary);
        }

        return summaries;
    }

    // ==================== Internal Methods ====================

    private RouteMetrics getOrCreateRouteMetrics(String aeTitle) {
        return routeMetrics.computeIfAbsent(aeTitle, k -> new RouteMetrics());
    }

    /**
     * Roll current minute metrics into storage and reset counters.
     */
    private void rollMinuteMetrics() {
        try {
            long now = System.currentTimeMillis();
            long currentMinute = truncateToMinute(now);
            long currentHour = truncateToHour(now);
            long currentDay = truncateToDay(now);

            // Create metric point for the last minute
            MetricPoint point = new MetricPoint();
            point.timestamp = lastMinuteTimestamp;
            point.transfers = currentMinuteTransfers.getAndSet(0);
            point.successful = currentMinuteSuccessful.getAndSet(0);
            point.failed = currentMinuteFailed.getAndSet(0);
            point.bytes = currentMinuteBytes.getAndSet(0);
            point.files = currentMinuteFiles.getAndSet(0);

            minuteData.addLast(point);

            // Persist minute data to store
            if (store != null) {
                store.recordMinuteMetric(point.timestamp, (int) point.transfers, (int) point.successful,
                        (int) point.failed, point.bytes, (int) point.files);
            }

            // Roll per-route metrics
            for (Map.Entry<String, RouteMetrics> entry : routeMetrics.entrySet()) {
                String aeTitle = entry.getKey();
                RouteMetrics rm = entry.getValue();

                MetricPoint routePoint = new MetricPoint();
                routePoint.timestamp = lastMinuteTimestamp;
                routePoint.transfers = rm.currentMinuteTransfers.getAndSet(0);
                routePoint.successful = rm.currentMinuteSuccessful.getAndSet(0);
                routePoint.failed = rm.currentMinuteFailed.getAndSet(0);
                routePoint.bytes = rm.currentMinuteBytes.getAndSet(0);
                routePoint.files = rm.currentMinuteFiles.getAndSet(0);

                ConcurrentLinkedDeque<MetricPoint> routeData = routeMinuteData.computeIfAbsent(
                    aeTitle, k -> new ConcurrentLinkedDeque<>());
                routeData.addLast(routePoint);

                // Prune old route data
                pruneOldData(routeData, MINUTE_RETENTION_MINUTES * 60 * 1000L);
            }

            lastMinuteTimestamp = currentMinute;

            // Roll to hourly if hour changed
            if (currentHour != lastHourTimestamp) {
                rollHourlyMetrics(currentHour);
                lastHourTimestamp = currentHour;
            }

            // Roll to daily if day changed
            if (currentDay != lastDayTimestamp) {
                rollDailyMetrics(currentDay);
                lastDayTimestamp = currentDay;
            }

            // Prune old data from in-memory storage
            pruneOldData(minuteData, MINUTE_RETENTION_MINUTES * 60 * 1000L);
            pruneOldData(hourlyData, HOURLY_RETENTION_HOURS * 60 * 60 * 1000L);
            pruneOldData(dailyData, DAILY_RETENTION_DAYS * 24 * 60 * 60 * 1000L);

            // Periodically cleanup old data from store
            if (store != null) {
                store.cleanupOldMetrics();
            }

        } catch (Exception e) {
            log.error("Error rolling metrics: {}", e.getMessage(), e);
        }
    }

    /**
     * Aggregate minute data into hourly point.
     */
    private void rollHourlyMetrics(long currentHour) {
        MetricPoint hourlyPoint = new MetricPoint();
        hourlyPoint.timestamp = lastHourTimestamp;

        // Sum up the last hour's worth of minute data
        long hourStart = lastHourTimestamp;
        long hourEnd = currentHour;

        for (MetricPoint p : minuteData) {
            if (p.timestamp >= hourStart && p.timestamp < hourEnd) {
                hourlyPoint.transfers += p.transfers;
                hourlyPoint.successful += p.successful;
                hourlyPoint.failed += p.failed;
                hourlyPoint.bytes += p.bytes;
                hourlyPoint.files += p.files;
            }
        }

        hourlyData.addLast(hourlyPoint);

        // Persist hourly data to store
        if (store != null) {
            store.recordHourMetric(hourlyPoint.timestamp, (int) hourlyPoint.transfers, (int) hourlyPoint.successful,
                    (int) hourlyPoint.failed, hourlyPoint.bytes, (int) hourlyPoint.files);
        }

        log.debug("Rolled hourly metrics: {} transfers", hourlyPoint.transfers);
    }

    /**
     * Aggregate hourly data into daily point.
     */
    private void rollDailyMetrics(long currentDay) {
        MetricPoint dailyPoint = new MetricPoint();
        dailyPoint.timestamp = lastDayTimestamp;

        // Sum up the last day's worth of hourly data
        long dayStart = lastDayTimestamp;
        long dayEnd = currentDay;

        for (MetricPoint p : hourlyData) {
            if (p.timestamp >= dayStart && p.timestamp < dayEnd) {
                dailyPoint.transfers += p.transfers;
                dailyPoint.successful += p.successful;
                dailyPoint.failed += p.failed;
                dailyPoint.bytes += p.bytes;
                dailyPoint.files += p.files;
            }
        }

        dailyData.addLast(dailyPoint);

        // Persist daily data to store
        if (store != null) {
            store.recordDayMetric(dailyPoint.timestamp, (int) dailyPoint.transfers, (int) dailyPoint.successful,
                    (int) dailyPoint.failed, dailyPoint.bytes, (int) dailyPoint.files);
        }

        log.debug("Rolled daily metrics: {} transfers", dailyPoint.transfers);
    }

    private void pruneOldData(ConcurrentLinkedDeque<MetricPoint> data, long retentionMs) {
        long cutoff = System.currentTimeMillis() - retentionMs;
        while (!data.isEmpty() && data.peekFirst().timestamp < cutoff) {
            data.pollFirst();
        }
    }

    private long truncateToMinute(long timestamp) {
        return (timestamp / 60000) * 60000;
    }

    private long truncateToHour(long timestamp) {
        return (timestamp / 3600000) * 3600000;
    }

    private long truncateToDay(long timestamp) {
        return (timestamp / 86400000) * 86400000;
    }

    // ==================== Data Classes ====================

    /**
     * A single metric data point.
     */
    public static class MetricPoint {
        public long timestamp;
        public long transfers;
        public long successful;
        public long failed;
        public long bytes;
        public long files;

        public String getTimestampIso() {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).toString();
        }
    }

    /**
     * Per-route metrics counters.
     */
    private static class RouteMetrics {
        final AtomicLong transfers = new AtomicLong(0);
        final AtomicLong successful = new AtomicLong(0);
        final AtomicLong failed = new AtomicLong(0);
        final AtomicLong bytes = new AtomicLong(0);
        final AtomicLong files = new AtomicLong(0);

        // Current minute counters (reset each minute)
        final AtomicLong currentMinuteTransfers = new AtomicLong(0);
        final AtomicLong currentMinuteSuccessful = new AtomicLong(0);
        final AtomicLong currentMinuteFailed = new AtomicLong(0);
        final AtomicLong currentMinuteBytes = new AtomicLong(0);
        final AtomicLong currentMinuteFiles = new AtomicLong(0);
    }

    /**
     * Summary for a route.
     */
    public static class RouteSummary {
        public String aeTitle;
        public long totalTransfers;
        public long successfulTransfers;
        public long failedTransfers;
        public long totalBytes;
        public long totalFiles;
        public double recentThroughput;
    }
}
