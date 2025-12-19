import { useState, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useFetch, apiPost, apiDelete, getAuthToken } from '../hooks/useApi'

interface UploadResult {
  message: string
  route: string
  studyFolder: string
  filesUploaded: number
  totalFiles: number
  uploadedFiles: string[]
  errors?: string[]
}

interface RouteStorage {
  name: string
  path: string
  incoming: number
  processing: number
  completed: number
  failed: number
  deleted: number
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
  deleted: DirectoryListing
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
  const navigate = useNavigate()
  const { data: overview, loading, error, refetch } = useFetch<StorageOverview>('/storage', 30000)
  const [selectedRoute, setSelectedRoute] = useState<string | null>(null)
  const [selectedStudy, setSelectedStudy] = useState<{ route: string; folder: string; status: string } | null>(null)
  const [browsePath, setBrowsePath] = useState<string | null>(null)
  const [actionInProgress, setActionInProgress] = useState<string | null>(null)

  // Upload state
  const [showUploadModal, setShowUploadModal] = useState(false)
  const [uploadRoute, setUploadRoute] = useState<string | null>(null)
  const [uploadFiles, setUploadFiles] = useState<File[]>([])
  const [uploadProgress, setUploadProgress] = useState<{ uploading: boolean; progress: number; result?: UploadResult; error?: string }>({ uploading: false, progress: 0 })
  const [isDragging, setIsDragging] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

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

  const handleMoveToImport = async (route: string, folder: string, status: string = 'incoming') => {
    if (!confirm(`Move this study to import for processing?\n\nRoute: ${route}\nFolder: ${folder}\n\nThis will trigger routing to configured destinations.`)) {
      return
    }

    setActionInProgress(`import-${folder}`)
    try {
      await apiPost(`/storage/routes/${route}/studies/${folder}/move-to-import?status=${status}`, {})
      alert('Study queued for import processing')
      refetchRoute()
      refetch()
    } catch (err) {
      alert('Failed to move to import: ' + (err instanceof Error ? err.message : 'Unknown error'))
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

  const handleRemoveAllStorage = async (route: string) => {
    // Confirmation for soft delete (moves to deleted folder)
    if (!confirm(`Move ALL storage for route "${route}" to the deleted folder?\n\nThis includes:\n- All incoming studies\n- All processing studies\n- All completed studies\n- All failed studies\n\nStudies will be moved to the 'deleted' folder and can be purged later.`)) {
      return
    }

    setActionInProgress(`remove-all-${route}`)
    try {
      const result = await apiDelete(`/storage/routes/${route}/all`)
      const data = result as { totalFilesMoved: number; totalSizeMoved: string; movedDirectories: string[] }
      alert(`Storage moved to deleted folder for route: ${route}\n\n- Files moved: ${data.totalFilesMoved}\n- Size: ${data.totalSizeMoved}\n- Directories cleared: ${data.movedDirectories.join(', ')}`)
      refetchRoute()
      refetch()
    } catch (err) {
      alert('Failed to remove storage: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setActionInProgress(null)
    }
  }

  const handlePurgeDeleted = async (route: string) => {
    // Strong confirmation for permanent deletion
    if (!confirm(`WARNING: This will PERMANENTLY DELETE all items in the deleted folder for route "${route}"!\n\nThis action CANNOT be undone.`)) {
      return
    }

    const confirmation = prompt(`Type "PURGE ${route}" to confirm permanent deletion:`)
    if (confirmation !== `PURGE ${route}`) {
      alert('Purge cancelled - confirmation text did not match.')
      return
    }

    setActionInProgress(`purge-${route}`)
    try {
      const result = await apiDelete(`/storage/routes/${route}/deleted/purge`)
      const data = result as { filesPurged: number; sizePurged: string }
      alert(`Deleted folder purged for route: ${route}\n\n- Files purged: ${data.filesPurged}\n- Space freed: ${data.sizePurged}`)
      refetchRoute()
      refetch()
    } catch (err) {
      alert('Failed to purge deleted folder: ' + (err instanceof Error ? err.message : 'Unknown error'))
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
      case 'deleted': return '#999'
      default: return 'inherit'
    }
  }

  // Upload handlers
  const openUploadModal = (route: string) => {
    setUploadRoute(route)
    setUploadFiles([])
    setUploadProgress({ uploading: false, progress: 0 })
    setShowUploadModal(true)
  }

  const closeUploadModal = () => {
    setShowUploadModal(false)
    setUploadRoute(null)
    setUploadFiles([])
    setUploadProgress({ uploading: false, progress: 0 })
  }

  const handleDragEnter = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    setIsDragging(true)
  }, [])

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    setIsDragging(false)
  }, [])

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
  }, [])

  const handleDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    e.stopPropagation()
    setIsDragging(false)

    const items = e.dataTransfer.items
    const files: File[] = []

    // Process dropped items (files and folders)
    const processEntry = async (entry: FileSystemEntry): Promise<File[]> => {
      const result: File[] = []
      if (entry.isFile) {
        const fileEntry = entry as FileSystemFileEntry
        const file = await new Promise<File>((resolve, reject) => {
          fileEntry.file(resolve, reject)
        })
        result.push(file)
      } else if (entry.isDirectory) {
        const dirEntry = entry as FileSystemDirectoryEntry
        const reader = dirEntry.createReader()
        const entries = await new Promise<FileSystemEntry[]>((resolve, reject) => {
          reader.readEntries(resolve, reject)
        })
        for (const childEntry of entries) {
          const childFiles = await processEntry(childEntry)
          result.push(...childFiles)
        }
      }
      return result
    }

    // Handle dropped items
    const promises: Promise<File[]>[] = []
    for (let i = 0; i < items.length; i++) {
      const item = items[i]
      const entry = item.webkitGetAsEntry()
      if (entry) {
        promises.push(processEntry(entry))
      }
    }

    Promise.all(promises).then(results => {
      const allFiles = results.flat()
      setUploadFiles(prev => [...prev, ...allFiles])
    })
  }, [])

  const handleFileSelect = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files
    if (files) {
      setUploadFiles(prev => [...prev, ...Array.from(files)])
    }
  }

  const removeFile = (index: number) => {
    setUploadFiles(prev => prev.filter((_, i) => i !== index))
  }

  const formatFileSize = (bytes: number) => {
    if (bytes < 1024) return bytes + ' B'
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
  }

  const handleUpload = async () => {
    if (!uploadRoute || uploadFiles.length === 0) return

    setUploadProgress({ uploading: true, progress: 0 })

    const formData = new FormData()
    uploadFiles.forEach(file => {
      formData.append('files', file)
    })

    try {
      const response = await fetch(`/api/storage/routes/${uploadRoute}/upload`, {
        method: 'POST',
        headers: {
          'X-Auth-Token': getAuthToken() || ''
        },
        body: formData
      })

      if (!response.ok) {
        const errorData = await response.json()
        throw new Error(errorData.error || `Upload failed: ${response.status}`)
      }

      const result = await response.json() as UploadResult
      setUploadProgress({ uploading: false, progress: 100, result })

      // Refresh the route data
      refetchRoute()
      refetch()
    } catch (err) {
      setUploadProgress({
        uploading: false,
        progress: 0,
        error: err instanceof Error ? err.message : 'Upload failed'
      })
    }
  }

  // Upload modal component
  const renderUploadModal = () => {
    if (!showUploadModal || !uploadRoute) return null

    return (
      <div
        style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          background: 'rgba(0,0,0,0.5)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 1000
        }}
        onClick={closeUploadModal}
      >
        <div
          style={{
            background: 'white',
            borderRadius: '8px',
            padding: '1.5rem',
            width: '600px',
            maxWidth: '90vw',
            maxHeight: '80vh',
            overflow: 'auto'
          }}
          onClick={e => e.stopPropagation()}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
            <h2 style={{ margin: 0 }}>Upload to {uploadRoute}</h2>
            <button
              onClick={closeUploadModal}
              style={{
                background: 'none',
                border: 'none',
                fontSize: '1.5rem',
                cursor: 'pointer',
                padding: '0.25rem'
              }}
            >
              x
            </button>
          </div>

          {uploadProgress.result ? (
            <div>
              <div style={{
                background: 'var(--success-color)',
                color: 'white',
                padding: '1rem',
                borderRadius: '4px',
                marginBottom: '1rem'
              }}>
                <strong>Upload Complete!</strong>
                <p style={{ margin: '0.5rem 0 0' }}>
                  {uploadProgress.result.totalFiles} files uploaded to folder: {uploadProgress.result.studyFolder}
                </p>
              </div>
              <button className="btn btn-primary" onClick={closeUploadModal}>
                Close
              </button>
            </div>
          ) : uploadProgress.error ? (
            <div>
              <div style={{
                background: 'var(--danger-color)',
                color: 'white',
                padding: '1rem',
                borderRadius: '4px',
                marginBottom: '1rem'
              }}>
                <strong>Upload Failed</strong>
                <p style={{ margin: '0.5rem 0 0' }}>{uploadProgress.error}</p>
              </div>
              <button className="btn btn-secondary" onClick={() => setUploadProgress({ uploading: false, progress: 0 })}>
                Try Again
              </button>
            </div>
          ) : (
            <>
              {/* Drop zone */}
              <div
                onDragEnter={handleDragEnter}
                onDragLeave={handleDragLeave}
                onDragOver={handleDragOver}
                onDrop={handleDrop}
                style={{
                  border: `2px dashed ${isDragging ? 'var(--primary-color)' : '#ccc'}`,
                  borderRadius: '8px',
                  padding: '2rem',
                  textAlign: 'center',
                  marginBottom: '1rem',
                  background: isDragging ? 'rgba(52, 152, 219, 0.1)' : '#f9f9f9',
                  cursor: 'pointer',
                  transition: 'all 0.2s'
                }}
                onClick={() => fileInputRef.current?.click()}
              >
                <div style={{ fontSize: '3rem', marginBottom: '0.5rem' }}>
                  {isDragging ? '+' : 'o'}
                </div>
                <p style={{ margin: 0, fontWeight: 500 }}>
                  {isDragging ? 'Drop files here' : 'Drag & drop files or folders here'}
                </p>
                <p style={{ margin: '0.5rem 0 0', fontSize: '0.875rem', color: '#666' }}>
                  or click to select files
                </p>
                <p style={{ margin: '0.5rem 0 0', fontSize: '0.75rem', color: '#999' }}>
                  Supports: DICOM files, ZIP, TAR, TAR.GZ archives
                </p>
              </div>

              <input
                ref={fileInputRef}
                type="file"
                multiple
                style={{ display: 'none' }}
                onChange={handleFileSelect}
              />

              {/* File list */}
              {uploadFiles.length > 0 && (
                <div style={{ marginBottom: '1rem' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                    <strong>{uploadFiles.length} file{uploadFiles.length !== 1 ? 's' : ''} selected</strong>
                    <button
                      className="btn btn-secondary"
                      style={{ padding: '0.25rem 0.5rem', fontSize: '0.75rem' }}
                      onClick={() => setUploadFiles([])}
                    >
                      Clear All
                    </button>
                  </div>
                  <div style={{ maxHeight: '200px', overflow: 'auto', border: '1px solid #eee', borderRadius: '4px' }}>
                    {uploadFiles.map((file, index) => (
                      <div
                        key={index}
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          alignItems: 'center',
                          padding: '0.5rem',
                          borderBottom: index < uploadFiles.length - 1 ? '1px solid #eee' : 'none'
                        }}
                      >
                        <div style={{ flex: 1, minWidth: 0 }}>
                          <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {file.name}
                          </div>
                          <div style={{ fontSize: '0.75rem', color: '#666' }}>
                            {formatFileSize(file.size)}
                          </div>
                        </div>
                        <button
                          onClick={() => removeFile(index)}
                          style={{
                            background: 'none',
                            border: 'none',
                            color: 'var(--danger-color)',
                            cursor: 'pointer',
                            padding: '0.25rem'
                          }}
                        >
                          x
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Progress bar */}
              {uploadProgress.uploading && (
                <div style={{ marginBottom: '1rem' }}>
                  <div style={{
                    background: '#eee',
                    borderRadius: '4px',
                    height: '8px',
                    overflow: 'hidden'
                  }}>
                    <div style={{
                      background: 'var(--primary-color)',
                      height: '100%',
                      width: '100%',
                      animation: 'progress-indeterminate 1.5s infinite ease-in-out'
                    }} />
                  </div>
                  <p style={{ margin: '0.5rem 0 0', textAlign: 'center', fontSize: '0.875rem' }}>
                    Uploading...
                  </p>
                </div>
              )}

              {/* Action buttons */}
              <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'flex-end' }}>
                <button className="btn btn-secondary" onClick={closeUploadModal} disabled={uploadProgress.uploading}>
                  Cancel
                </button>
                <button
                  className="btn btn-primary"
                  onClick={handleUpload}
                  disabled={uploadFiles.length === 0 || uploadProgress.uploading}
                >
                  {uploadProgress.uploading ? 'Uploading...' : `Upload ${uploadFiles.length} file${uploadFiles.length !== 1 ? 's' : ''}`}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    )
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
                <>
                  <button
                    className="btn btn-primary"
                    onClick={() => navigate(`/review?aeTitle=${encodeURIComponent(selectedStudy.route)}&studyUid=${encodeURIComponent(selectedStudy.folder)}`)}
                  >
                    Compare
                  </button>
                  <button
                    className="btn btn-primary"
                    onClick={() => handleReprocessStudy(selectedStudy.route, selectedStudy.folder)}
                    disabled={actionInProgress !== null}
                  >
                    {actionInProgress === `reprocess-${selectedStudy.folder}` ? 'Reprocessing...' : 'Reprocess'}
                  </button>
                </>
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
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {studyDetail.files.map(file => (
                  <tr key={file.name}>
                    <td><code style={{ fontSize: '0.75rem' }}>{file.name}</code></td>
                    <td>{file.sizeFormatted}</td>
                    <td>{file.modified}</td>
                    <td>
                      <button
                        className="btn btn-secondary"
                        style={{ padding: '0.25rem 0.5rem', fontSize: '0.75rem' }}
                        onClick={() => {
                          const relativePath = `${selectedStudy!.route}/${selectedStudy!.status}/${selectedStudy!.folder}/${file.name}`
                          navigate(`/dicom-viewer?path=${encodeURIComponent(relativePath)}`)
                        }}
                      >
                        Preview
                      </button>
                    </td>
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
                          {status === 'incoming' && (
                            <button
                              className="btn btn-primary"
                              style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                              onClick={() => handleMoveToImport(selectedRoute, item.name, status)}
                              disabled={actionInProgress !== null}
                            >
                              Import
                            </button>
                          )}
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
                            <>
                              <button
                                className="btn btn-secondary"
                                style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                                onClick={() => navigate(`/review?aeTitle=${encodeURIComponent(selectedRoute)}&studyUid=${encodeURIComponent(item.name)}`)}
                              >
                                Compare
                              </button>
                              <button
                                className="btn btn-primary"
                                style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                                onClick={() => handleReprocessStudy(selectedRoute, item.name)}
                                disabled={actionInProgress !== null}
                              >
                                Reprocess
                              </button>
                            </>
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

    const renderDeletedDirectory = (dir: DirectoryListing) => {
      if (!dir || !dir.exists) return null

      return (
        <div style={{ marginBottom: '1.5rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginBottom: '0.5rem' }}>
            <h3 style={{ color: '#999', margin: 0 }}>
              Deleted ({dir.itemCount})
            </h3>
            {dir.itemCount > 0 && (
              <button
                className="btn btn-danger"
                style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                onClick={() => handlePurgeDeleted(selectedRoute!)}
                disabled={actionInProgress !== null}
              >
                {actionInProgress === `purge-${selectedRoute}` ? 'Purging...' : 'Purge All'}
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
                  </tr>
                </thead>
                <tbody>
                  {dir.items.filter(item => item.isDirectory).map(item => (
                    <tr key={item.name} style={{ opacity: 0.7 }}>
                      <td>
                        <code style={{ fontSize: '0.75rem' }}>
                          {item.name.length > 40 ? item.name.substring(0, 40) + '...' : item.name}
                        </code>
                      </td>
                      <td>{item.fileCount || 0}</td>
                      <td>{item.totalSizeFormatted || item.sizeFormatted}</td>
                      <td>{item.modified}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div style={{ color: 'var(--text-light)', fontStyle: 'italic' }}>Empty (no deleted items)</div>
          )}
        </div>
      )
    }

    const renderLogsDirectory = (dir: DirectoryListing) => {
      if (!dir.exists) return null

      // Show all items (files AND directories) for logs
      const allItems = dir.items

      return (
        <div style={{ marginBottom: '1.5rem' }}>
          <h3 style={{ color: 'var(--text-light)', margin: '0 0 0.5rem 0' }}>
            Logs ({dir.itemCount})
          </h3>
          {allItems.length > 0 ? (
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
                  {allItems.map(item => (
                    <tr key={item.name}>
                      <td>
                        <code style={{ fontSize: '0.75rem' }}>
                          {item.name.length > 50 ? item.name.substring(0, 50) + '...' : item.name}
                        </code>
                      </td>
                      <td>{item.isDirectory ? 'Directory' : 'File'}</td>
                      <td>{item.isDirectory ? `${item.fileCount || 0} files` : item.sizeFormatted}</td>
                      <td>{item.modified}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div style={{ color: 'var(--text-light)', fontStyle: 'italic' }}>No logs</div>
          )}
        </div>
      )
    }

    return (
      <>
        <div>
          <div className="card">
            <div className="card-header">
              <h2 className="card-title">Route: {selectedRoute}</h2>
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button className="btn btn-primary" onClick={() => openUploadModal(selectedRoute)}>
                  Upload Data
                </button>
                <button
                  className="btn btn-danger"
                  onClick={() => handleRemoveAllStorage(selectedRoute)}
                  disabled={actionInProgress !== null}
                >
                  {actionInProgress === `remove-all-${selectedRoute}` ? 'Removing...' : 'Remove All'}
                </button>
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
            {renderDeletedDirectory(routeDetail.deleted)}
            {renderLogsDirectory(routeDetail.logs)}
          </div>
        </div>
        {renderUploadModal()}
      </>
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
                <th style={{ color: '#999' }}>Deleted</th>
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
                  <td style={{ color: route.deleted > 0 ? '#999' : 'inherit' }}>
                    {route.deleted || 0}
                  </td>
                  <td>{route.totalSize}</td>
                  <td>
                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      <button
                        className="btn btn-primary"
                        style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                        onClick={() => openUploadModal(route.name)}
                      >
                        Upload
                      </button>
                      <button
                        className="btn btn-secondary"
                        style={{ padding: '0.25rem 0.5rem', fontSize: '0.875rem' }}
                        onClick={() => setSelectedRoute(route.name)}
                      >
                        Details
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {(!overview?.routes || overview.routes.length === 0) && (
                <tr>
                  <td colSpan={8} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                    No route storage found
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
      {renderUploadModal()}
    </div>
  )
}
