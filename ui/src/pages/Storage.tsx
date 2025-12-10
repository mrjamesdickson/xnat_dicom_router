import { useState } from 'react'
import { useFetch, apiPost, apiDelete } from '../hooks/useApi'

interface RouteStorage {
  name: string
  path: string
  incoming: number
  processing: number
  completed: number
  failed: number
  totalSize: string
  totalSizeBytes: number
}

interface StorageOverview {
  dataDirectory: string
  routes: RouteStorage[]
  totalFiles: number
  totalSize: string
  totalSizeBytes: number
}

interface DirectoryItem {
  name: string
  isDirectory: boolean
  size: number
  sizeFormatted: string
  modified: string
  fileCount?: number
  totalSize?: number
  totalSizeFormatted?: string
}

interface DirectoryListing {
  exists: boolean
  items: DirectoryItem[]
  itemCount: number
}

interface RouteStorageDetail {
  route: string
  path: string
  incoming: DirectoryListing
  processing: DirectoryListing
  completed: DirectoryListing
  failed: DirectoryListing
  logs: DirectoryListing
}

interface StudyDetail {
  route: string
  status: string
  studyFolder: string
  path: string
  files: Array<{
    name: string
    size: number
    sizeFormatted: string
    modified: string
  }>
  fileCount: number
  totalSize: string
}

interface BrowseResult {
  currentPath: string
  absolutePath: string
  exists: boolean
  items: DirectoryItem[]
  itemCount: number
  breadcrumbs: Array<{ name: string; path: string }>
}

export default function Storage() {
  const { data: overview, loading, error, refetch } = useFetch<StorageOverview>('/storage', 30000)
  const [selectedRoute, setSelectedRoute] = useState<string | null>(null)
  const [selectedStudy, setSelectedStudy] = useState<{ route: string; folder: string; status: string } | null>(null)
  const [browsePath, setBrowsePath] = useState<string | null>(null)
  const [actionInProgress, setActionInProgress] = useState<string | null>(null)

  const { data: routeDetail, refetch: refetchRoute } = useFetch<RouteStorageDetail>(
    selectedRoute ? `/storage/routes/${selectedRoute}` : null,
    30000
  )

  const { data: studyDetail, refetch: refetchStudy } = useFetch<StudyDetail>(
    selectedStudy
      ? `/storage/routes/${selectedStudy.route}/studies/${selectedStudy.folder}?status=${selectedStudy.status}`
      : null,
    30000
  )

  const { data: browseData, refetch: refetchBrowse } = useFetch<BrowseResult>(
    browsePath !== null ? `/storage/browse?path=${encodeURIComponent(browsePath)}` : null,
    30000
  )

  const handleDeleteStudy = async (route: string, folder: string, status: string) => {
    if (!confirm(`Are you sure you want to delete this study folder?\n\nRoute: ${route}\nFolder: ${folder}\nStatus: ${status}`)) {
      return
    }

    setActionInProgress(`delete-${folder}`)
    try {
      await apiDelete(`/storage/routes/${route}/studies/${folder}?status=${status}`)
      refetchRoute()
      setSelectedStudy(null)
      refetch()
    } catch (err) {
      alert('Failed to delete: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setActionInProgress(null)
    }
  }

  const handleRetryStudy = async (route: string, folder: string) => {
    if (!confirm(`Move this study back to incoming for retry?\n\nRoute: ${route}\nFolder: ${folder}`)) {
      return
    }

    setActionInProgress(`retry-${folder}`)
    try {
      await apiPost(`/storage/routes/${route}/studies/${folder}/retry`, {})
      refetchRoute()
      setSelectedStudy(null)
      refetch()
    } catch (err) {
      alert('Failed to retry: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setActionInProgress(null)
    }
  }

  const handleReprocessStudy = async (route: string, folder: string) => {
    if (!confirm(`Move this completed study back to incoming for reprocessing?\n\nRoute: ${route}\nFolder: ${folder}`)) {
      return
    }

    setActionInProgress(`reprocess-${folder}`)
    try {
      await apiPost(`/storage/routes/${route}/studies/${folder}/reprocess`, {})
      refetchRoute()
      setSelectedStudy(null)
      refetch()
    } catch (err) {
      alert('Failed to reprocess: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setActionInProgress(null)
    }
  }

  const handleReprocessAllCompleted = async (route: string) => {
    if (!confirm(`Move ALL completed studies back to incoming for reprocessing?\n\nRoute: ${route}\n\nThis will re-upload all studies to the destination.`)) {
      return
    }

    setActionInProgress(`reprocess-all-${route}`)
    try {
      const result = await apiPost(`/storage/routes/${route}/reprocess-all`, {})
      const data = result as { moved: number; skipped: number; errors: number }
      alert(`Reprocess complete:\n- Moved: ${data.moved}\n- Skipped: ${data.skipped}\n- Errors: ${data.errors}`)
      refetchRoute()
      refetch()
    } catch (err) {
      alert('Failed to reprocess: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setActionInProgress(null)
    }
  }

  const handleRetryAllFailed = async (route: string) => {
    if (!confirm(`Move ALL failed studies back to incoming for retry?\n\nRoute: ${route}\n\nThis will re-attempt to upload all failed studies.`)) {
      return
    }

    setActionInProgress(`retry-all-${route}`)
    try {
      const result = await apiPost(`/storage/routes/${route}/retry-all`, {})
      const data = result as { moved: number; skipped: number; errors: number }
      alert(`Retry all complete:\n- Moved: ${data.moved}\n- Skipped: ${data.skipped}\n- Errors: ${data.errors}`)
      refetchRoute()
      refetch()
    } catch (err) {
      alert('Failed to retry: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setActionInProgress(null)
    }
  }

  const getStatusColor = (status: string) => {
    switch (status) {
      case 'incoming': return 'var(--primary-color)'
      case 'processing': return 'var(--warning-color)'
      case 'completed': return 'var(--success-color)'
      case 'failed': return 'var(--danger-color)'
      default: return 'inherit'
    }
  }

  if (loading) {
    return <div className="loading"><div className="spinner" /></div>
  }

  if (error) {
    return <div className="error-message">Failed to load storage: {error}</div>
  }

  // Browse mode
  if (browsePath !== null) {
    return (
      <div>
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Browse Storage</h2>
            <button className="btn btn-secondary" onClick={() => setBrowsePath(null)}>
              Back to Overview
            </button>
          </div>

          {browseData && (
            <>
              <div style={{ marginBottom: '1rem' }}>
                <nav style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
                  {browseData.breadcrumbs.map((crumb, i) => (
                    <span key={crumb.path}>
                      {i > 0 && <span style={{ margin: '0 0.25rem' }}>/</span>}
                      <button
                        className="btn btn-secondary"
                        style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                        onClick={() => setBrowsePath(crumb.path)}
                      >
                        {crumb.name}
                      </button>
                    </span>
                  ))}
                </nav>
              </div>
              <div style={{ marginBottom: '1rem', fontSize: '0.875rem', color: 'var(--text-light)' }}>
                {browseData.absolutePath}
              </div>

              <div className="table-container">
                <table>
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Type</th>
                      <th>Size</th>
                      <th>Modified</th>
                    </tr>
                  </thead>
                  <tbody>
                    {browseData.items.map(item => (
                      <tr key={item.name}>
                        <td>
                          {item.isDirectory ? (
                            <button
                              className="btn btn-secondary"
                              style={{ padding: '0.25rem 0.5rem' }}
                              onClick={() => setBrowsePath(browsePath ? `${browsePath}/${item.name}` : item.name)}
                            >
                              {item.name}/
                            </button>
                          ) : (
                            <span>{item.name}</span>
                          )}
                        </td>
                        <td>{item.isDirectory ? 'Directory' : 'File'}</td>
                        <td>
                          {item.isDirectory ? (
                            item.fileCount !== undefined ? `${item.fileCount} files` : '-'
                          ) : (
                            item.sizeFormatted
                          )}
                        </td>
                        <td>{item.modified}</td>
                      </tr>
                    ))}
                    {browseData.items.length === 0 && (
                      <tr>
                        <td colSpan={4} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                          Empty directory
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      </div>
    )
  }

  // Study detail view
  if (selectedStudy && studyDetail) {
    return (
      <div>
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Study Details</h2>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              {selectedStudy.status === 'failed' && (
                <button
                  className="btn btn-primary"
                  onClick={() => handleRetryStudy(selectedStudy.route, selectedStudy.folder)}
                  disabled={actionInProgress !== null}
                >
                  {actionInProgress === `retry-${selectedStudy.folder}` ? 'Retrying...' : 'Retry'}
                </button>
              )}
              {selectedStudy.status === 'completed' && (
                <button
                  className="btn btn-primary"
                  onClick={() => handleReprocessStudy(selectedStudy.route, selectedStudy.folder)}
                  disabled={actionInProgress !== null}
                >
                  {actionInProgress === `reprocess-${selectedStudy.folder}` ? 'Reprocessing...' : 'Reprocess'}
                </button>
              )}
              {(selectedStudy.status === 'failed' || selectedStudy.status === 'completed') && (
                <button
                  className="btn btn-danger"
                  onClick={() => handleDeleteStudy(selectedStudy.route, selectedStudy.folder, selectedStudy.status)}
                  disabled={actionInProgress !== null}
                >
                  {actionInProgress === `delete-${selectedStudy.folder}` ? 'Deleting...' : 'Delete'}
                </button>
              )}
              <button className="btn btn-secondary" onClick={() => setSelectedStudy(null)}>
                Back
              </button>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
            <div><strong>Route:</strong><br />{studyDetail.route}</div>
            <div><strong>Status:</strong><br /><span style={{ color: getStatusColor(studyDetail.status) }}>{studyDetail.status}</span></div>
            <div><strong>Files:</strong><br />{studyDetail.fileCount}</div>
            <div><strong>Total Size:</strong><br />{studyDetail.totalSize}</div>
          </div>

          <div style={{ marginBottom: '1rem' }}>
            <strong>Folder:</strong>
            <code style={{ display: 'block', marginTop: '0.25rem', fontSize: '0.75rem', wordBreak: 'break-all' }}>
              {studyDetail.studyFolder}
            </code>
          </div>

          <div style={{ marginBottom: '1.5rem' }}>
            <strong>Path:</strong>
            <code style={{ display: 'block', marginTop: '0.25rem', fontSize: '0.75rem', wordBreak: 'break-all' }}>
              {studyDetail.path}
            </code>
          </div>

          <h3>Files ({studyDetail.fileCount})</h3>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Size</th>
                  <th>Modified</th>
                </tr>
              </thead>
              <tbody>
                {studyDetail.files.map(file => (
                  <tr key={file.name}>
                    <td><code style={{ fontSize: '0.75rem' }}>{file.name}</code></td>
                    <td>{file.sizeFormatted}</td>
                    <td>{file.modified}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    )
  }

  // Route detail view
  if (selectedRoute && routeDetail) {
    const renderDirectory = (name: string, dir: DirectoryListing, status: string) => {
      if (!dir.exists) return null

      return (
        <div style={{ marginBottom: '1.5rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginBottom: '0.5rem' }}>
            <h3 style={{ color: getStatusColor(status), margin: 0 }}>
              {name.charAt(0).toUpperCase() + name.slice(1)} ({dir.itemCount})
            </h3>
            {status === 'completed' && dir.itemCount > 0 && (
              <button
                className="btn btn-primary"
                style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                onClick={() => handleReprocessAllCompleted(selectedRoute!)}
                disabled={actionInProgress !== null}
              >
                {actionInProgress === `reprocess-all-${selectedRoute}` ? 'Reprocessing...' : 'Reprocess All'}
              </button>
            )}
            {status === 'failed' && dir.itemCount > 0 && (
              <button
                className="btn btn-primary"
                style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                onClick={() => handleRetryAllFailed(selectedRoute!)}
                disabled={actionInProgress !== null}
              >
                {actionInProgress === `retry-all-${selectedRoute}` ? 'Retrying...' : 'Retry All'}
              </button>
            )}
          </div>
          {dir.items.length > 0 ? (
            <div className="table-container">
              <table>
                <thead>
                  <tr>
                    <th>Folder</th>
                    <th>Files</th>
                    <th>Size</th>
                    <th>Modified</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {dir.items.filter(item => item.isDirectory).map(item => (
                    <tr key={item.name}>
                      <td>
                        <code style={{ fontSize: '0.75rem' }}>
                          {item.name.length > 40 ? item.name.substring(0, 40) + '...' : item.name}
                        </code>
                      </td>
                      <td>{item.fileCount || 0}</td>
                      <td>{item.totalSizeFormatted || item.sizeFormatted}</td>
                      <td>{item.modified}</td>
                      <td>
                        <div style={{ display: 'flex', gap: '0.5rem' }}>
                          <button
                            className="btn btn-secondary"
                            style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                            onClick={() => setSelectedStudy({ route: selectedRoute, folder: item.name, status })}
                          >
                            View
                          </button>
                          {status === 'failed' && (
                            <button
                              className="btn btn-primary"
                              style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                              onClick={() => handleRetryStudy(selectedRoute, item.name)}
                              disabled={actionInProgress !== null}
                            >
                              Retry
                            </button>
                          )}
                          {status === 'completed' && (
                            <button
                              className="btn btn-primary"
                              style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                              onClick={() => handleReprocessStudy(selectedRoute, item.name)}
                              disabled={actionInProgress !== null}
                            >
                              Reprocess
                            </button>
                          )}
                          {(status === 'failed' || status === 'completed') && (
                            <button
                              className="btn btn-danger"
                              style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                              onClick={() => handleDeleteStudy(selectedRoute, item.name, status)}
                              disabled={actionInProgress !== null}
                            >
                              Delete
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div style={{ color: 'var(--text-light)', fontStyle: 'italic' }}>Empty</div>
          )}
        </div>
      )
    }

    return (
      <div>
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Route: {selectedRoute}</h2>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button className="btn btn-secondary" onClick={() => refetchRoute()}>
                Refresh
              </button>
              <button className="btn btn-secondary" onClick={() => setSelectedRoute(null)}>
                Back to Overview
              </button>
            </div>
          </div>

          <div style={{ marginBottom: '1.5rem', fontSize: '0.875rem', color: 'var(--text-light)' }}>
            Path: {routeDetail.path}
          </div>

          {renderDirectory('incoming', routeDetail.incoming, 'incoming')}
          {renderDirectory('processing', routeDetail.processing, 'processing')}
          {renderDirectory('completed', routeDetail.completed, 'completed')}
          {renderDirectory('failed', routeDetail.failed, 'failed')}
          {renderDirectory('logs', routeDetail.logs, 'logs')}
        </div>
      </div>
    )
  }

  // Overview
  return (
    <div>
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Storage Overview</h2>
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <button className="btn btn-secondary" onClick={() => setBrowsePath('')}>
              Browse Files
            </button>
            <button className="btn btn-secondary" onClick={() => refetch()}>
              Refresh
            </button>
          </div>
        </div>

        <div style={{ marginBottom: '1.5rem', fontSize: '0.875rem', color: 'var(--text-light)' }}>
          Data Directory: {overview?.dataDirectory}
        </div>

        <div className="stat-grid">
          <div className="stat-item">
            <div className="stat-value">{overview?.routes.length || 0}</div>
            <div className="stat-label">Routes</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{overview?.totalFiles || 0}</div>
            <div className="stat-label">Total Files</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{overview?.totalSize || '0 B'}</div>
            <div className="stat-label">Total Size</div>
          </div>
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Route Storage</h2>
        </div>

        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>Route</th>
                <th style={{ color: 'var(--primary-color)' }}>Incoming</th>
                <th style={{ color: 'var(--warning-color)' }}>Processing</th>
                <th style={{ color: 'var(--success-color)' }}>Completed</th>
                <th style={{ color: 'var(--danger-color)' }}>Failed</th>
                <th>Total Size</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {overview?.routes.map(route => (
                <tr key={route.name}>
                  <td><strong>{route.name}</strong></td>
                  <td style={{ color: route.incoming > 0 ? 'var(--primary-color)' : 'inherit' }}>
                    {route.incoming}
                  </td>
                  <td style={{ color: route.processing > 0 ? 'var(--warning-color)' : 'inherit' }}>
                    {route.processing}
                  </td>
                  <td style={{ color: route.completed > 0 ? 'var(--success-color)' : 'inherit' }}>
                    {route.completed}
                  </td>
                  <td style={{ color: route.failed > 0 ? 'var(--danger-color)' : 'inherit' }}>
                    {route.failed}
                  </td>
                  <td>{route.totalSize}</td>
                  <td>
                    <button
                      className="btn btn-secondary"
                      onClick={() => setSelectedRoute(route.name)}
                    >
                      View Details
                    </button>
                  </td>
                </tr>
              ))}
              {(!overview?.routes || overview.routes.length === 0) && (
                <tr>
                  <td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                    No route storage found
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
