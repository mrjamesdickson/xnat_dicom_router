import { useState } from 'react'
import { useFetch } from '../hooks/useApi'
import { Chart } from '../components/Chart'

interface StatusData {
  version: string
  startTime: string
  uptime: string
  routes: Array<{ aeTitle: string; port: number; enabled: boolean; destinations: number }>
  destinations: Array<{ name: string; type: string; available: boolean; availabilityPercent: number }>
  transfers: {
    total: number
    successful: number
    failed: number
    active: number
    successRate: number
  }
}

interface HealthData {
  status: string
  timestamp: string
  destinationsAvailable: string
}

interface ActiveTransfer {
  transferId: string
  aeTitle: string
  studyUid: string
  fileCount: number
  totalSize: number
  status: string
  receivedAt: string
  filesProcessed: number
  bytesProcessed: number
  progressPercent: number
}

interface ActiveTransfersData {
  transfers: ActiveTransfer[]
  count: number
}

interface MetricPoint {
  timestamp: number
  timestampIso: string
  transfers: number
  successful: number
  failed: number
  bytes: number
  files: number
}

interface MetricsData {
  resolution: string
  count: number
  data: MetricPoint[]
}

interface MetricsSummary {
  currentThroughput: number
  currentBytesPerMinute: number
  routes: Record<string, {
    aeTitle: string
    totalTransfers: number
    successfulTransfers: number
    failedTransfers: number
    totalBytes: number
    totalFiles: number
    recentThroughput: number
  }>
}

export default function Dashboard() {
  const [timeRange, setTimeRange] = useState<'minutes' | 'hours' | 'days'>('minutes')

  const { data: status, loading: statusLoading, error: statusError } = useFetch<StatusData>('/status', 30000)
  const { data: health, loading: healthLoading, error: healthError } = useFetch<HealthData>('/status/health', 10000)
  // Poll active transfers more frequently (every 2 seconds) for live progress
  const { data: activeTransfers } = useFetch<ActiveTransfersData>('/transfers/active', 2000)
  // Fetch metrics data
  const { data: metricsSummary } = useFetch<MetricsSummary>('/metrics', 30000)
  const { data: minuteMetrics } = useFetch<MetricsData>('/metrics/timeseries/minutes?count=60', 30000)
  const { data: hourMetrics } = useFetch<MetricsData>('/metrics/timeseries/hours?count=24', 60000)
  const { data: dayMetrics } = useFetch<MetricsData>('/metrics/timeseries/days?count=30', 300000)

  const formatBytes = (bytes: number) => {
    if (bytes < 1024) return bytes + ' B'
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
  }

  if (statusLoading || healthLoading) {
    return (
      <div className="loading">
        <div className="spinner" />
      </div>
    )
  }

  if (statusError || healthError) {
    return (
      <div className="error-message">
        Failed to load status: {statusError || healthError}
      </div>
    )
  }

  return (
    <div>
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">System Status</h2>
          <span className={`status-badge status-${health?.status.toLowerCase() || 'down'}`}>
            {health?.status || 'Unknown'}
          </span>
        </div>

        <div className="stat-grid">
          <div className="stat-item">
            <div className="stat-value">{status?.version}</div>
            <div className="stat-label">Version</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{status?.uptime}</div>
            <div className="stat-label">Uptime</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{health?.destinationsAvailable}</div>
            <div className="stat-label">Destinations Available</div>
          </div>
        </div>
      </div>

      <div className="dashboard-grid">
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Transfer Statistics</h2>
          </div>
          <div className="stat-grid">
            <div className="stat-item">
              <div className="stat-value">{status?.transfers.total || 0}</div>
              <div className="stat-label">Total</div>
            </div>
            <div className="stat-item">
              <div className="stat-value" style={{ color: 'var(--success-color)' }}>
                {status?.transfers.successful || 0}
              </div>
              <div className="stat-label">Successful</div>
            </div>
            <div className="stat-item">
              <div className="stat-value" style={{ color: 'var(--danger-color)' }}>
                {status?.transfers.failed || 0}
              </div>
              <div className="stat-label">Failed</div>
            </div>
            <div className="stat-item">
              <div className="stat-value" style={{ color: 'var(--warning-color)' }}>
                {status?.transfers.active || 0}
              </div>
              <div className="stat-label">Active</div>
            </div>
          </div>
          {status?.transfers.total && status.transfers.total > 0 && (
            <div style={{ marginTop: '1rem', textAlign: 'center', color: 'var(--text-light)' }}>
              Success Rate: {(status.transfers.successRate ?? 0).toFixed(1)}%
            </div>
          )}
        </div>

        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Active Routes</h2>
          </div>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>AE Title</th>
                  <th>Port</th>
                  <th>Destinations</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {status?.routes.map(route => (
                  <tr key={route.aeTitle}>
                    <td><strong>{route.aeTitle}</strong></td>
                    <td>{route.port}</td>
                    <td>{route.destinations}</td>
                    <td>
                      <span className={`status-badge status-${route.enabled ? 'up' : 'down'}`}>
                        {route.enabled ? 'Enabled' : 'Disabled'}
                      </span>
                    </td>
                  </tr>
                ))}
                {(!status?.routes || status.routes.length === 0) && (
                  <tr>
                    <td colSpan={4} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                      No routes configured
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>

      {/* Active Transfers with Live Progress */}
      {activeTransfers && activeTransfers.count > 0 && (
        <div className="card" style={{ borderLeft: '4px solid var(--warning-color)' }}>
          <div className="card-header">
            <h2 className="card-title">Active Transfers ({activeTransfers.count})</h2>
            <span style={{ color: 'var(--text-light)', fontSize: '0.875rem' }}>
              Auto-refreshing every 2s
            </span>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
            {activeTransfers.transfers.map(t => (
              <div
                key={t.transferId}
                style={{
                  background: 'var(--bg-color)',
                  borderRadius: '6px',
                  padding: '1rem',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                  <div>
                    <strong>{t.aeTitle}</strong>
                    <span style={{ marginLeft: '0.5rem', color: 'var(--text-light)', fontSize: '0.875rem' }}>
                      {t.status}
                    </span>
                  </div>
                  <div style={{ fontSize: '0.875rem', color: 'var(--text-light)' }}>
                    {t.filesProcessed} / {t.fileCount} files
                    <span style={{ marginLeft: '0.5rem' }}>
                      ({formatBytes(t.bytesProcessed)} / {formatBytes(t.totalSize)})
                    </span>
                  </div>
                </div>
                {/* Progress bar */}
                <div style={{
                  background: 'var(--border-color)',
                  borderRadius: '4px',
                  height: '24px',
                  overflow: 'hidden',
                  position: 'relative'
                }}>
                  <div style={{
                    background: 'linear-gradient(90deg, var(--secondary-color), var(--success-color))',
                    height: '100%',
                    width: `${t.progressPercent}%`,
                    transition: 'width 0.3s ease-in-out',
                    borderRadius: '4px'
                  }} />
                  <div style={{
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    right: 0,
                    bottom: 0,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontWeight: 600,
                    fontSize: '0.875rem',
                    color: t.progressPercent > 50 ? 'white' : 'var(--text-color)'
                  }}>
                    {(t.progressPercent ?? 0).toFixed(1)}%
                  </div>
                </div>
                <div style={{ marginTop: '0.5rem', fontSize: '0.75rem', color: 'var(--text-light)' }}>
                  <code style={{ fontSize: '0.75rem' }}>{t.studyUid.substring(0, 40)}...</code>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Destinations</h2>
        </div>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Availability</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {status?.destinations.map(dest => (
                <tr key={dest.name}>
                  <td><strong>{dest.name}</strong></td>
                  <td>{dest.type}</td>
                  <td>{(dest.availabilityPercent ?? 0).toFixed(1)}%</td>
                  <td>
                    <span className={`status-badge status-${dest.available ? 'available' : 'unavailable'}`}>
                      {dest.available ? 'Available' : 'Unavailable'}
                    </span>
                  </td>
                </tr>
              ))}
              {(!status?.destinations || status.destinations.length === 0) && (
                <tr>
                  <td colSpan={4} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                    No destinations configured
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Metrics Charts Section */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Transfer Metrics</h2>
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <button
              className={`btn btn-sm ${timeRange === 'minutes' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setTimeRange('minutes')}
            >
              Last Hour
            </button>
            <button
              className={`btn btn-sm ${timeRange === 'hours' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setTimeRange('hours')}
            >
              Last 24 Hours
            </button>
            <button
              className={`btn btn-sm ${timeRange === 'days' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => setTimeRange('days')}
            >
              Last 30 Days
            </button>
          </div>
        </div>

        {/* Current throughput stats */}
        {metricsSummary && (
          <div className="stat-grid" style={{ marginBottom: '1rem' }}>
            <div className="stat-item">
              <div className="stat-value">{metricsSummary.currentThroughput.toFixed(1)}</div>
              <div className="stat-label">Transfers/min</div>
            </div>
            <div className="stat-item">
              <div className="stat-value">{formatBytes(metricsSummary.currentBytesPerMinute)}</div>
              <div className="stat-label">Throughput/min</div>
            </div>
          </div>
        )}

        {/* Charts */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          <Chart
            data={
              timeRange === 'minutes' ? minuteMetrics?.data || [] :
              timeRange === 'hours' ? hourMetrics?.data || [] :
              dayMetrics?.data || []
            }
            type="transfers"
            title="Transfer Activity"
            width={700}
            height={200}
          />
          <Chart
            data={
              timeRange === 'minutes' ? minuteMetrics?.data || [] :
              timeRange === 'hours' ? hourMetrics?.data || [] :
              dayMetrics?.data || []
            }
            type="bytes"
            title="Data Volume"
            width={700}
            height={200}
          />
        </div>
      </div>

      {/* Route Performance */}
      {metricsSummary && Object.keys(metricsSummary.routes).length > 0 && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Route Performance</h2>
          </div>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>AE Title</th>
                  <th>Total Transfers</th>
                  <th>Success</th>
                  <th>Failed</th>
                  <th>Data Volume</th>
                  <th>Throughput</th>
                </tr>
              </thead>
              <tbody>
                {Object.entries(metricsSummary.routes).map(([aeTitle, route]) => (
                  <tr key={aeTitle}>
                    <td><strong>{route.aeTitle}</strong></td>
                    <td>{route.totalTransfers}</td>
                    <td style={{ color: 'var(--success-color)' }}>{route.successfulTransfers}</td>
                    <td style={{ color: route.failedTransfers > 0 ? 'var(--danger-color)' : undefined }}>
                      {route.failedTransfers}
                    </td>
                    <td>{formatBytes(route.totalBytes)}</td>
                    <td>{route.recentThroughput.toFixed(1)}/min</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}
