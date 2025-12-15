import { useState } from 'react'
import { useFetch, apiPost } from '../hooks/useApi'

interface Transfer {
  transferId: string
  aeTitle: string
  studyUid: string
  callingAeTitle: string
  fileCount: number
  totalSize: number
  status: string
  receivedAt: string
  completedAt: string | null
  errorMessage: string | null
  durationMs: number
  filesProcessed: number
  bytesProcessed: number
  progressPercent: number
  destinations: Array<{
    destination: string
    status: string
    message: string | null
    durationMs: number
    filesTransferred: number
    completedAt: string | null
  }>
}

interface TransferStats {
  totalTransfers: number
  successfulTransfers: number
  failedTransfers: number
  activeTransfers: number
  successRate: number
  totalBytes: number
  averageTransferTime: number
}

interface TransfersResponse {
  transfers: Transfer[]
  total: number
  offset: number
  limit: number
}

export default function Transfers() {
  const [statusFilter, setStatusFilter] = useState<string>('')
  const [aeTitleFilter, setAeTitleFilter] = useState<string>('')
  const { data: stats } = useFetch<TransferStats>('/transfers/statistics', 30000)
  // Poll active transfers more frequently (every 2 seconds) for live progress
  const { data: active, refetch: refetchActive } = useFetch<{ transfers: Transfer[], count: number }>(
    '/transfers/active', 2000
  )
  const {
    data: transfers,
    loading,
    error,
    refetch
  } = useFetch<TransfersResponse>(
    `/transfers?limit=50${statusFilter ? `&status=${statusFilter}` : ''}${aeTitleFilter ? `&aeTitle=${aeTitleFilter}` : ''}`,
    30000
  )
  const [selectedTransfer, setSelectedTransfer] = useState<Transfer | null>(null)
  const [retrying, setRetrying] = useState<string | null>(null)

  const handleRetry = async (transferId: string) => {
    setRetrying(transferId)
    try {
      await apiPost(`/transfers/${transferId}/retry`, {})
      refetch()
      refetchActive()
    } catch (err) {
      alert('Failed to retry: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setRetrying(null)
    }
  }

  const formatBytes = (bytes: number) => {
    if (bytes < 1024) return bytes + ' B'
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
  }

  const formatDuration = (ms: number) => {
    if (ms < 1000) return ms + 'ms'
    if (ms < 60000) return (ms / 1000).toFixed(1) + 's'
    return Math.round(ms / 60000) + 'm ' + Math.round((ms % 60000) / 1000) + 's'
  }

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'COMPLETED': return 'var(--success-color)'
      case 'FAILED': return 'var(--danger-color)'
      case 'PARTIAL': return 'var(--warning-color)'
      case 'FORWARDING':
      case 'PROCESSING': return 'var(--secondary-color)'
      default: return 'inherit'
    }
  }

  if (loading) {
    return <div className="loading"><div className="spinner" /></div>
  }

  if (error) {
    return <div className="error-message">Failed to load transfers: {error}</div>
  }

  return (
    <div>
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Transfer Statistics</h2>
        </div>
        <div className="stat-grid">
          <div className="stat-item">
            <div className="stat-value">{stats?.totalTransfers || 0}</div>
            <div className="stat-label">Total</div>
          </div>
          <div className="stat-item">
            <div className="stat-value" style={{ color: 'var(--success-color)' }}>
              {stats?.successfulTransfers || 0}
            </div>
            <div className="stat-label">Successful</div>
          </div>
          <div className="stat-item">
            <div className="stat-value" style={{ color: 'var(--danger-color)' }}>
              {stats?.failedTransfers || 0}
            </div>
            <div className="stat-label">Failed</div>
          </div>
          <div className="stat-item">
            <div className="stat-value" style={{ color: 'var(--warning-color)' }}>
              {active?.count || 0}
            </div>
            <div className="stat-label">Active</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{stats?.successRate?.toFixed(1) || 0}%</div>
            <div className="stat-label">Success Rate</div>
          </div>
        </div>
      </div>

      {active && active.count > 0 && (
        <div className="card" style={{ borderLeft: '4px solid var(--warning-color)' }}>
          <div className="card-header">
            <h2 className="card-title">Active Transfers ({active.count})</h2>
            <span style={{ color: 'var(--text-light)', fontSize: '0.875rem' }}>
              Auto-refreshing every 2s
            </span>
          </div>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>AE Title</th>
                  <th>Study UID</th>
                  <th>Progress</th>
                  <th>Files</th>
                  <th>Status</th>
                  <th>Started</th>
                </tr>
              </thead>
              <tbody>
                {active.transfers.map(t => (
                  <tr key={t.transferId}>
                    <td><strong>{t.aeTitle}</strong></td>
                    <td><code style={{ fontSize: '0.75rem' }}>{t.studyUid.substring(0, 30)}...</code></td>
                    <td style={{ minWidth: '150px' }}>
                      <div style={{
                        background: 'var(--border-color)',
                        borderRadius: '4px',
                        height: '20px',
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
                          fontSize: '0.75rem',
                          color: t.progressPercent > 50 ? 'white' : 'var(--text-color)'
                        }}>
                          {(t.progressPercent ?? 0).toFixed(1)}%
                        </div>
                      </div>
                    </td>
                    <td>{t.filesProcessed} / {t.fileCount}</td>
                    <td>
                      <span style={{ color: getStatusColor(t.status) }}>
                        {t.status}
                      </span>
                    </td>
                    <td>{new Date(t.receivedAt).toLocaleTimeString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Transfer History</h2>
          <button className="btn btn-secondary" onClick={() => refetch()}>
            Refresh
          </button>
        </div>

        <div style={{ display: 'flex', gap: '1rem', marginBottom: '1rem' }}>
          <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
            <select
              className="form-select"
              value={statusFilter}
              onChange={e => setStatusFilter(e.target.value)}
            >
              <option value="">All Statuses</option>
              <option value="COMPLETED">Completed</option>
              <option value="FAILED">Failed</option>
              <option value="PARTIAL">Partial</option>
            </select>
          </div>
          <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
            <input
              type="text"
              className="form-input"
              placeholder="Filter by AE Title"
              value={aeTitleFilter}
              onChange={e => setAeTitleFilter(e.target.value)}
            />
          </div>
        </div>

        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>AE Title</th>
                <th>Study UID</th>
                <th>Files</th>
                <th>Size</th>
                <th>Duration</th>
                <th>Status</th>
                <th>Received</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {transfers?.transfers.map(t => (
                <tr key={t.transferId}>
                  <td><strong>{t.aeTitle}</strong></td>
                  <td><code style={{ fontSize: '0.75rem' }}>{t.studyUid.substring(0, 20)}...</code></td>
                  <td>{t.fileCount}</td>
                  <td>{formatBytes(t.totalSize)}</td>
                  <td>{formatDuration(t.durationMs)}</td>
                  <td>
                    <span className={`status-badge status-${t.status === 'COMPLETED' ? 'up' : t.status === 'FAILED' ? 'down' : 'degraded'}`}>
                      {t.status}
                    </span>
                  </td>
                  <td>{new Date(t.receivedAt).toLocaleString()}</td>
                  <td>
                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      <button
                        className="btn btn-secondary"
                        onClick={() => setSelectedTransfer(t)}
                      >
                        Details
                      </button>
                      {t.status === 'FAILED' && (
                        <button
                          className="btn btn-primary"
                          onClick={() => handleRetry(t.transferId)}
                          disabled={retrying === t.transferId}
                        >
                          {retrying === t.transferId ? 'Retrying...' : 'Retry'}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
              {(!transfers?.transfers || transfers.transfers.length === 0) && (
                <tr>
                  <td colSpan={8} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                    No transfers found
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {transfers && transfers.total > transfers.limit && (
          <div style={{ marginTop: '1rem', textAlign: 'center', color: 'var(--text-light)' }}>
            Showing {transfers.transfers.length} of {transfers.total} transfers
          </div>
        )}
      </div>

      {selectedTransfer && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Transfer Details</h2>
            <button className="btn btn-secondary" onClick={() => setSelectedTransfer(null)}>
              Close
            </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
            <div><strong>Transfer ID:</strong><br />{selectedTransfer.transferId}</div>
            <div><strong>AE Title:</strong><br />{selectedTransfer.aeTitle}</div>
            <div><strong>Calling AE:</strong><br />{selectedTransfer.callingAeTitle}</div>
            <div><strong>Status:</strong><br /><span style={{ color: getStatusColor(selectedTransfer.status) }}>{selectedTransfer.status}</span></div>
            <div><strong>Files:</strong><br />{selectedTransfer.fileCount}</div>
            <div><strong>Size:</strong><br />{formatBytes(selectedTransfer.totalSize)}</div>
            <div><strong>Duration:</strong><br />{formatDuration(selectedTransfer.durationMs)}</div>
            <div><strong>Received:</strong><br />{new Date(selectedTransfer.receivedAt).toLocaleString()}</div>
          </div>

          <div style={{ marginBottom: '1rem' }}>
            <strong>Study UID:</strong>
            <code style={{ display: 'block', marginTop: '0.25rem', fontSize: '0.875rem', wordBreak: 'break-all' }}>
              {selectedTransfer.studyUid}
            </code>
          </div>

          {selectedTransfer.errorMessage && (
            <div className="error-message">
              <strong>Error:</strong> {selectedTransfer.errorMessage}
            </div>
          )}

          <h3 style={{ marginTop: '1.5rem', marginBottom: '1rem' }}>Destination Results</h3>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Destination</th>
                  <th>Status</th>
                  <th>Files</th>
                  <th>Duration</th>
                  <th>Message</th>
                </tr>
              </thead>
              <tbody>
                {selectedTransfer.destinations.map((d, i) => (
                  <tr key={i}>
                    <td><strong>{d.destination}</strong></td>
                    <td>
                      <span className={`status-badge status-${d.status === 'SUCCESS' ? 'up' : d.status === 'FAILED' ? 'down' : 'degraded'}`}>
                        {d.status}
                      </span>
                    </td>
                    <td>{d.filesTransferred}</td>
                    <td>{formatDuration(d.durationMs)}</td>
                    <td>{d.message || '-'}</td>
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
