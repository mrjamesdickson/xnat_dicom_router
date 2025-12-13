import { useState, useEffect } from 'react'
import { useFetch, apiCall } from '../hooks/useApi'

interface RouteInfo {
  aeTitle: string
  port: number
  description: string
  destinationCount: number
}

interface StudyInfo {
  studyUid: string
  fileCount: number
  totalSize: number
  patientId?: string
  patientName?: string
  studyDate?: string
  modality?: string
  studyDescription?: string
}

interface ScanResult {
  path: string
  totalFiles: number
  studies: StudyInfo[]
}

interface ImportJob {
  id: string
  path: string
  route: string
  status: string
  recursive: boolean
  moveFiles: boolean
  totalFiles: number
  totalStudies: number
  processedStudies: number
  successCount: number
  failCount: number
  currentStudy: string | null
  errors: string[]
  startTime: number
  elapsedMs: number
}

export default function Import() {
  const [path, setPath] = useState('')
  const [route, setRoute] = useState('')
  const [recursive, setRecursive] = useState(true)
  const [moveFiles, setMoveFiles] = useState(false)
  const [scanning, setScanning] = useState(false)
  const [scanResult, setScanResult] = useState<ScanResult | null>(null)
  const [importing, setImporting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const { data: routes, loading: routesLoading } = useFetch<RouteInfo[]>('/import/routes', 60000)
  const { data: jobs, loading: jobsLoading, refetch: refetchJobs } = useFetch<ImportJob[]>('/import/jobs', 2000)

  // Set default route when routes load
  useEffect(() => {
    if (routes && routes.length > 0 && !route) {
      setRoute(routes[0].aeTitle)
    }
  }, [routes, route])

  const handleScan = async () => {
    if (!path.trim()) {
      setError('Please enter a directory path')
      return
    }

    setScanning(true)
    setError(null)
    setScanResult(null)

    try {
      const result = await apiCall<ScanResult>('/import/scan', {
        method: 'POST',
        body: JSON.stringify({ path, recursive })
      })
      setScanResult(result)
    } catch (err: any) {
      setError(err.message || 'Failed to scan directory')
    } finally {
      setScanning(false)
    }
  }

  const handleImport = async () => {
    if (!path.trim() || !route) {
      setError('Please enter a directory path and select a route')
      return
    }

    setImporting(true)
    setError(null)

    try {
      await apiCall('/import/start', {
        method: 'POST',
        body: JSON.stringify({ path, route, recursive, moveFiles })
      })
      setScanResult(null)
      refetchJobs()
    } catch (err: any) {
      setError(err.message || 'Failed to start import')
    } finally {
      setImporting(false)
    }
  }

  const handleCancelJob = async (jobId: string) => {
    try {
      await apiCall(`/import/jobs/${jobId}`, { method: 'DELETE' })
      refetchJobs()
    } catch (err: any) {
      setError(err.message || 'Failed to cancel job')
    }
  }

  const formatBytes = (bytes: number): string => {
    if (bytes === 0) return '0 B'
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i]
  }

  const formatDuration = (ms: number): string => {
    if (ms < 1000) return `${ms}ms`
    if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`
    return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`
  }

  const getStatusBadge = (status: string) => {
    const statusClasses: Record<string, string> = {
      pending: 'status-pending',
      scanning: 'status-processing',
      processing: 'status-processing',
      completed: 'status-up',
      failed: 'status-down',
      cancelled: 'status-unavailable'
    }
    return `status-badge ${statusClasses[status] || 'status-pending'}`
  }

  return (
    <div>
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Import DICOM Files from Disk</h2>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          {/* Directory Input */}
          <div>
            <label style={{ display: 'block', marginBottom: '0.5rem', fontWeight: 500 }}>
              Directory Path
            </label>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <input
                type="text"
                value={path}
                onChange={e => setPath(e.target.value)}
                placeholder="/path/to/dicom/files"
                style={{
                  flex: 1,
                  padding: '0.75rem',
                  border: '1px solid var(--border-color)',
                  borderRadius: '4px',
                  fontSize: '14px'
                }}
              />
              <button
                onClick={handleScan}
                disabled={scanning || !path.trim()}
                className="btn btn-secondary"
              >
                {scanning ? 'Scanning...' : 'Scan'}
              </button>
            </div>
          </div>

          {/* Route Selection */}
          <div>
            <label style={{ display: 'block', marginBottom: '0.5rem', fontWeight: 500 }}>
              Target Route
            </label>
            <select
              value={route}
              onChange={e => setRoute(e.target.value)}
              disabled={routesLoading}
              style={{
                width: '100%',
                padding: '0.75rem',
                border: '1px solid var(--border-color)',
                borderRadius: '4px',
                fontSize: '14px'
              }}
            >
              {routes?.map(r => (
                <option key={r.aeTitle} value={r.aeTitle}>
                  {r.aeTitle} - Port {r.port} ({r.destinationCount} destinations)
                </option>
              ))}
            </select>
          </div>

          {/* Options */}
          <div style={{ display: 'flex', gap: '2rem' }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <input
                type="checkbox"
                checked={recursive}
                onChange={e => setRecursive(e.target.checked)}
              />
              Scan subdirectories recursively
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <input
                type="checkbox"
                checked={moveFiles}
                onChange={e => setMoveFiles(e.target.checked)}
              />
              Move files (instead of copy)
            </label>
          </div>

          {/* Error */}
          {error && (
            <div className="error-message">
              {error}
            </div>
          )}

          {/* Scan Results */}
          {scanResult && (
            <div style={{ marginTop: '1rem' }}>
              <h3 style={{ marginBottom: '1rem' }}>
                Found {scanResult.totalFiles} files in {scanResult.studies.length} studies
              </h3>
              <div className="table-container" style={{ maxHeight: '300px', overflowY: 'auto' }}>
                <table>
                  <thead>
                    <tr>
                      <th>Study UID</th>
                      <th>Patient</th>
                      <th>Date</th>
                      <th>Modality</th>
                      <th>Files</th>
                      <th>Size</th>
                    </tr>
                  </thead>
                  <tbody>
                    {scanResult.studies.map(study => (
                      <tr key={study.studyUid}>
                        <td style={{ fontFamily: 'monospace', fontSize: '12px' }}>
                          {study.studyUid.length > 30 ? '...' + study.studyUid.slice(-27) : study.studyUid}
                        </td>
                        <td>{study.patientId || '-'}</td>
                        <td>{study.studyDate || '-'}</td>
                        <td>{study.modality || '-'}</td>
                        <td>{study.fileCount}</td>
                        <td>{formatBytes(study.totalSize)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div style={{ marginTop: '1rem', textAlign: 'right' }}>
                <button
                  onClick={handleImport}
                  disabled={importing}
                  className="btn btn-primary"
                >
                  {importing ? 'Starting Import...' : `Import ${scanResult.studies.length} Studies`}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Active Jobs */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Import Jobs</h2>
          <button onClick={() => refetchJobs()} className="btn btn-secondary btn-small">
            Refresh
          </button>
        </div>

        {jobsLoading && <div className="loading"><div className="spinner" /></div>}

        {!jobsLoading && (!jobs || jobs.length === 0) && (
          <div style={{ textAlign: 'center', color: 'var(--text-light)', padding: '2rem' }}>
            No active import jobs
          </div>
        )}

        {jobs && jobs.length > 0 && (
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Job ID</th>
                  <th>Path</th>
                  <th>Route</th>
                  <th>Progress</th>
                  <th>Status</th>
                  <th>Duration</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {jobs.map(job => (
                  <tr key={job.id}>
                    <td style={{ fontFamily: 'monospace' }}>{job.id}</td>
                    <td title={job.path}>
                      {job.path.length > 30 ? '...' + job.path.slice(-27) : job.path}
                    </td>
                    <td>{job.route}</td>
                    <td>
                      {job.totalStudies > 0 ? (
                        <div>
                          <div style={{ fontSize: '12px', marginBottom: '4px' }}>
                            {job.processedStudies}/{job.totalStudies} studies
                          </div>
                          <div style={{
                            width: '100px',
                            height: '6px',
                            background: '#eee',
                            borderRadius: '3px',
                            overflow: 'hidden'
                          }}>
                            <div style={{
                              width: `${(job.processedStudies / job.totalStudies) * 100}%`,
                              height: '100%',
                              background: 'var(--primary-color)',
                              transition: 'width 0.3s'
                            }} />
                          </div>
                          {job.currentStudy && (
                            <div style={{ fontSize: '10px', color: 'var(--text-light)', marginTop: '2px' }}>
                              Processing: ...{job.currentStudy.slice(-20)}
                            </div>
                          )}
                        </div>
                      ) : '-'}
                    </td>
                    <td>
                      <span className={getStatusBadge(job.status)}>
                        {job.status}
                      </span>
                      {job.status === 'completed' && (
                        <div style={{ fontSize: '11px', marginTop: '4px' }}>
                          <span style={{ color: 'var(--success-color)' }}>{job.successCount} ok</span>
                          {job.failCount > 0 && (
                            <span style={{ color: 'var(--danger-color)', marginLeft: '8px' }}>
                              {job.failCount} failed
                            </span>
                          )}
                        </div>
                      )}
                    </td>
                    <td>{formatDuration(job.elapsedMs)}</td>
                    <td>
                      {(job.status === 'processing' || job.status === 'scanning') && (
                        <button
                          onClick={() => handleCancelJob(job.id)}
                          className="btn btn-danger btn-small"
                        >
                          Cancel
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Show errors from completed jobs */}
        {jobs?.filter(j => j.errors && j.errors.length > 0).map(job => (
          <div key={job.id + '-errors'} style={{
            marginTop: '1rem',
            padding: '1rem',
            background: '#fff5f5',
            border: '1px solid #fed7d7',
            borderRadius: '4px'
          }}>
            <strong>Errors from job {job.id}:</strong>
            <ul style={{ margin: '0.5rem 0 0 1rem', padding: 0 }}>
              {job.errors.slice(0, 5).map((err, i) => (
                <li key={i} style={{ fontSize: '13px', color: 'var(--danger-color)' }}>{err}</li>
              ))}
              {job.errors.length > 5 && (
                <li style={{ fontSize: '13px', color: 'var(--text-light)' }}>
                  ...and {job.errors.length - 5} more errors
                </li>
              )}
            </ul>
          </div>
        ))}
      </div>
    </div>
  )
}
