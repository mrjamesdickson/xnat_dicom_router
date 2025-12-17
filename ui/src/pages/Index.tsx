import { useState } from 'react'
import { useFetch, apiPost, apiPut, apiDelete } from '../hooks/useApi'

interface IndexStats {
  studyCount: number
  seriesCount: number
  instanceCount: number
  totalSizeBytes: number
  customFieldCount: number
  oldestIndexedAt: string
  newestIndexedAt: string
}

interface CustomField {
  id: number
  fieldName: string
  displayName: string
  dicomTag: string
  level: string
  fieldType: string
  searchable: boolean
  displayInList: boolean
  enabled: boolean
  createdAt: string
  updatedAt: string
}

interface TagInfo {
  keyword: string
  tag: string
  displayName: string
  level: string
  fieldType: string
}

interface ReindexJob {
  id: number
  status: string
  totalFiles: number
  processedFiles: number
  errorCount: number
  startedAt: string
  completedAt: string
  errorMessage: string
  message: string
  createdAt: string
}

interface IndexableDestination {
  name: string
  type: string
  description: string
  enabled: boolean
  indexable: boolean
  path?: string
  host?: string
  port?: number
  aeTitle?: string
}

export default function Index() {
  const [selectedDestination, setSelectedDestination] = useState<string>('')
  const [clearExisting, setClearExisting] = useState(false)
  const [indexStudyDateFrom, setIndexStudyDateFrom] = useState<string>('')
  const [indexStudyDateTo, setIndexStudyDateTo] = useState<string>('')
  const [chunkSize, setChunkSize] = useState<string>('NONE')
  const [showFieldConfig, setShowFieldConfig] = useState(false)
  const [newField, setNewField] = useState({
    fieldName: '',
    displayName: '',
    dicomTag: '',
    level: 'study',
    fieldType: 'string',
    searchable: true,
    displayInList: true,
    enabled: true,
  })

  const { data: indexStats, refetch: refetchStats } = useFetch<IndexStats>('/search/stats', 60000)
  const { data: customFields, refetch: refetchFields } = useFetch<{ fields: CustomField[] }>('/search/fields')
  const { data: availableTags } = useFetch<{ tags: TagInfo[] }>('/search/fields/available-tags')
  const { data: reindexStatus, refetch: refetchReindex } = useFetch<ReindexJob | { status: string }>('/search/reindex/status', 2000)
  const { data: destinations } = useFetch<{ destinations: IndexableDestination[] }>('/search/destinations')

  const formatBytes = (bytes: number) => {
    if (!bytes) return '0 B'
    if (bytes < 1024) return bytes + ' B'
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
  }

  const toDicomDate = (htmlDate: string): string => {
    if (!htmlDate) return ''
    return htmlDate.replace(/-/g, '')
  }

  const handleStartReindex = async () => {
    if (selectedDestination) {
      const dest = destinations?.destinations.find(d => d.name === selectedDestination)
      const destDesc = dest ? `${dest.name} (${dest.type})` : selectedDestination
      const isDicomDest = dest?.type === 'dicom'

      let dateRangeDesc = ''
      if (isDicomDest && (indexStudyDateFrom || indexStudyDateTo)) {
        if (indexStudyDateFrom && indexStudyDateTo) {
          dateRangeDesc = ` Date range: ${indexStudyDateFrom} to ${indexStudyDateTo}.`
        } else if (indexStudyDateFrom) {
          dateRangeDesc = ` Studies from ${indexStudyDateFrom} onwards.`
        } else if (indexStudyDateTo) {
          dateRangeDesc = ` Studies up to ${indexStudyDateTo}.`
        }
      }

      if (!confirm(`This will index DICOM data from destination "${destDesc}".${dateRangeDesc} ${clearExisting ? 'Existing index will be cleared. ' : ''}Continue?`)) {
        return
      }
      try {
        const params = new URLSearchParams()
        params.append('clearExisting', String(clearExisting))
        if (isDicomDest) {
          if (indexStudyDateFrom) params.append('studyDateFrom', toDicomDate(indexStudyDateFrom))
          if (indexStudyDateTo) params.append('studyDateTo', toDicomDate(indexStudyDateTo))
          if (chunkSize !== 'NONE') params.append('chunkSize', chunkSize)
        }

        await apiPost(`/search/reindex/destination/${encodeURIComponent(selectedDestination)}?${params.toString()}`, {})
        refetchReindex()
        refetchStats()
      } catch (error) {
        console.error('Failed to start reindex:', error)
      }
    } else {
      if (!confirm('This will scan all DICOM files in the receiver base directory and rebuild the search index. Continue?')) {
        return
      }
      try {
        await apiPost('/search/reindex', {})
        refetchReindex()
        refetchStats()
      } catch (error) {
        console.error('Failed to start reindex:', error)
      }
    }
  }

  const handleCancelReindex = async () => {
    if (!confirm('Are you sure you want to cancel the current indexing job?')) {
      return
    }
    try {
      await apiPost('/search/reindex/cancel', {})
      refetchReindex()
    } catch (error) {
      console.error('Failed to cancel reindex:', error)
    }
  }

  const handleAddField = async () => {
    if (!newField.fieldName || !newField.dicomTag) {
      alert('Field name and DICOM tag are required')
      return
    }
    try {
      await apiPost('/search/fields', newField)
      refetchFields()
      setNewField({
        fieldName: '',
        displayName: '',
        dicomTag: '',
        level: 'study',
        fieldType: 'string',
        searchable: true,
        displayInList: true,
        enabled: true,
      })
    } catch (error) {
      console.error('Failed to add field:', error)
    }
  }

  const handleDeleteField = async (id: number) => {
    if (!confirm('Delete this custom field? This will also remove all indexed values.')) {
      return
    }
    try {
      await apiDelete(`/search/fields/${id}`)
      refetchFields()
    } catch (error) {
      console.error('Failed to delete field:', error)
    }
  }

  const handleToggleField = async (field: CustomField) => {
    try {
      await apiPut(`/search/fields/${field.id}`, { ...field, enabled: !field.enabled })
      refetchFields()
    } catch (error) {
      console.error('Failed to toggle field:', error)
    }
  }

  const isReindexing = reindexStatus && 'status' in reindexStatus && reindexStatus.status === 'running'

  return (
    <div>
      {/* Index Statistics */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">DICOM Index</h2>
          <button
            className="btn btn-secondary"
            onClick={() => setShowFieldConfig(!showFieldConfig)}
          >
            {showFieldConfig ? 'Hide' : 'Configure'} Fields
          </button>
        </div>

        <div className="stat-grid">
          <div className="stat-item">
            <div className="stat-value">{indexStats?.studyCount?.toLocaleString() || 0}</div>
            <div className="stat-label">Studies</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{indexStats?.seriesCount?.toLocaleString() || 0}</div>
            <div className="stat-label">Series</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{indexStats?.instanceCount?.toLocaleString() || 0}</div>
            <div className="stat-label">Instances</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{formatBytes(indexStats?.totalSizeBytes || 0)}</div>
            <div className="stat-label">Total Size</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{indexStats?.customFieldCount || 0}</div>
            <div className="stat-label">Custom Fields</div>
          </div>
        </div>

        {/* Index Source Selection */}
        <div style={{ marginTop: '1rem', padding: '1rem', background: 'var(--bg-color)', borderRadius: '6px' }}>
          <h3 style={{ marginBottom: '0.75rem', fontSize: '1rem' }}>Index Source</h3>
          <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <div className="form-group" style={{ minWidth: '250px', margin: 0 }}>
              <label>Destination</label>
              <select
                value={selectedDestination}
                onChange={(e) => setSelectedDestination(e.target.value)}
                disabled={isReindexing}
              >
                <option value="">Receiver base directory (default)</option>
                {destinations?.destinations.filter(d => d.indexable).map(dest => (
                  <option key={dest.name} value={dest.name}>
                    {dest.name} ({dest.type})
                    {dest.path && ` - ${dest.path}`}
                    {dest.host && ` - ${dest.host}:${dest.port}`}
                  </option>
                ))}
              </select>
            </div>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={clearExisting}
                onChange={(e) => setClearExisting(e.target.checked)}
                disabled={isReindexing}
              />
              Clear existing index
            </label>
            <button
              className="btn btn-primary"
              onClick={handleStartReindex}
              disabled={isReindexing}
            >
              {isReindexing ? 'Indexing...' : (selectedDestination ? `Index from ${selectedDestination}` : 'Index All')}
            </button>
          </div>

          {/* Date Range and Chunk Size - only for DICOM destinations */}
          {selectedDestination && destinations?.destinations.find(d => d.name === selectedDestination)?.type === 'dicom' && (
            <div style={{ marginTop: '1rem', padding: '1rem', background: 'var(--bg-alt, #f8f9fa)', borderRadius: '6px', border: '1px solid var(--border-color)' }}>
              <h4 style={{ marginBottom: '0.75rem', fontSize: '0.9rem', color: 'var(--text-color)' }}>
                Query Options for DICOM Server
              </h4>
              <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-end', flexWrap: 'wrap' }}>
                <div className="form-group" style={{ margin: 0 }}>
                  <label>Study Date From</label>
                  <input
                    type="date"
                    value={indexStudyDateFrom}
                    onChange={(e) => setIndexStudyDateFrom(e.target.value)}
                    disabled={isReindexing}
                    style={{ width: '160px' }}
                  />
                </div>
                <div className="form-group" style={{ margin: 0 }}>
                  <label>Study Date To</label>
                  <input
                    type="date"
                    value={indexStudyDateTo}
                    onChange={(e) => setIndexStudyDateTo(e.target.value)}
                    disabled={isReindexing}
                    style={{ width: '160px' }}
                  />
                </div>
                <div className="form-group" style={{ margin: 0, minWidth: '140px' }}>
                  <label>Chunk Size</label>
                  <select
                    value={chunkSize}
                    onChange={(e) => setChunkSize(e.target.value)}
                    disabled={isReindexing}
                  >
                    <option value="NONE">No Chunking</option>
                    <option value="YEARLY">Yearly</option>
                    <option value="MONTHLY">Monthly</option>
                    <option value="WEEKLY">Weekly</option>
                    <option value="DAILY">Daily</option>
                    <option value="HOURLY">Hourly</option>
                  </select>
                </div>
              </div>
              <p style={{ marginTop: '0.75rem', color: 'var(--text-light)', fontSize: '0.8rem' }}>
                <strong>Tip:</strong> For large PACS with millions of studies, use date ranges and chunking to avoid timeouts.
                {chunkSize !== 'NONE' && <span> Queries will be split into <strong>{chunkSize.toLowerCase()}</strong> chunks.</span>}
              </p>
            </div>
          )}

          {selectedDestination && destinations?.destinations.find(d => d.name === selectedDestination) && (
            <p style={{ marginTop: '0.5rem', color: 'var(--text-light)', fontSize: '0.875rem' }}>
              {(() => {
                const dest = destinations.destinations.find(d => d.name === selectedDestination)
                if (!dest) return null
                if (dest.type === 'file') {
                  return `Will scan DICOM files from: ${dest.path}`
                } else if (dest.type === 'dicom') {
                  return `Will query DICOM server: ${dest.host}:${dest.port} (AE: ${dest.aeTitle})`
                }
                return null
              })()}
            </p>
          )}
        </div>

        {/* Reindex Progress */}
        {reindexStatus && 'totalFiles' in reindexStatus && (reindexStatus.status === 'running' || reindexStatus.status === 'completed' || reindexStatus.status === 'failed' || reindexStatus.status === 'cancelled') && (
          <div style={{
            marginTop: '1rem',
            padding: '1rem',
            background: reindexStatus.status === 'failed' ? 'var(--danger-bg, #fff5f5)' :
                        reindexStatus.status === 'cancelled' ? 'var(--warning-bg, #fffaf0)' :
                        reindexStatus.status === 'completed' ? 'var(--success-bg, #f0fff4)' :
                        'var(--bg-color)',
            borderRadius: '6px',
            borderLeft: reindexStatus.status === 'failed' ? '4px solid var(--danger-color)' :
                        reindexStatus.status === 'cancelled' ? '4px solid var(--warning-color, #dd6b20)' :
                        reindexStatus.status === 'completed' ? '4px solid var(--success-color)' :
                        '4px solid var(--primary-color)'
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
              <div>
                <strong style={{
                  color: reindexStatus.status === 'failed' ? 'var(--danger-color)' :
                         reindexStatus.status === 'cancelled' ? 'var(--warning-color, #dd6b20)' :
                         reindexStatus.status === 'completed' ? 'var(--success-color)' :
                         'inherit'
                }}>
                  {reindexStatus.status === 'running' && 'Indexing in progress...'}
                  {reindexStatus.status === 'completed' && 'Indexing completed'}
                  {reindexStatus.status === 'failed' && 'Indexing failed'}
                  {reindexStatus.status === 'cancelled' && 'Indexing cancelled'}
                </strong>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                <span style={{ fontSize: '0.875rem' }}>
                  {reindexStatus.processedFiles.toLocaleString()} / {reindexStatus.totalFiles.toLocaleString()} studies
                </span>
                {reindexStatus.status === 'running' && (
                  <button
                    className="btn btn-sm btn-danger"
                    onClick={handleCancelReindex}
                    style={{ padding: '0.25rem 0.75rem' }}
                  >
                    Cancel
                  </button>
                )}
                {(reindexStatus.status === 'completed' || reindexStatus.status === 'failed' || reindexStatus.status === 'cancelled') && (
                  <button
                    className="btn btn-sm btn-primary"
                    onClick={handleStartReindex}
                    style={{ padding: '0.25rem 0.75rem' }}
                  >
                    Restart
                  </button>
                )}
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
                background: reindexStatus.status === 'failed' ? 'var(--danger-color)' :
                           reindexStatus.status === 'cancelled' ? 'var(--warning-color, #dd6b20)' :
                           reindexStatus.status === 'completed' ? 'var(--success-color)' :
                           'linear-gradient(90deg, var(--primary-color), var(--secondary-color, #4a90d9))',
                height: '100%',
                width: `${reindexStatus.totalFiles > 0 ? (reindexStatus.processedFiles / reindexStatus.totalFiles * 100) : 0}%`,
                transition: 'width 0.3s',
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
                fontSize: '0.75rem',
                fontWeight: 600,
                color: (reindexStatus.processedFiles / reindexStatus.totalFiles) > 0.5 ? 'white' : 'var(--text-color)'
              }}>
                {reindexStatus.totalFiles > 0 ?
                  `${((reindexStatus.processedFiles / reindexStatus.totalFiles) * 100).toFixed(1)}%` :
                  'Querying...'}
              </div>
            </div>

            {/* Status details */}
            <div style={{ marginTop: '0.75rem', display: 'flex', gap: '1.5rem', fontSize: '0.875rem', color: 'var(--text-light)' }}>
              {reindexStatus.startedAt && (
                <span>Started: {new Date(reindexStatus.startedAt).toLocaleTimeString()}</span>
              )}
              {reindexStatus.completedAt && (
                <span>Completed: {new Date(reindexStatus.completedAt).toLocaleTimeString()}</span>
              )}
              {reindexStatus.errorCount > 0 && (
                <span style={{ color: 'var(--danger-color)' }}>
                  {reindexStatus.errorCount} error{reindexStatus.errorCount !== 1 ? 's' : ''}
                </span>
              )}
            </div>

            {reindexStatus.errorMessage && (
              <div style={{
                marginTop: '0.75rem',
                padding: '0.5rem',
                background: 'rgba(255,0,0,0.1)',
                borderRadius: '4px',
                fontSize: '0.875rem',
                color: 'var(--danger-color)'
              }}>
                <strong>Error:</strong> {reindexStatus.errorMessage}
              </div>
            )}

            {reindexStatus.status === 'running' && reindexStatus.message && (
              <div style={{ marginTop: '0.5rem', fontSize: '0.875rem', color: 'var(--primary-color)', fontWeight: 500 }}>
                {reindexStatus.message}
              </div>
            )}

            {reindexStatus.status === 'running' && reindexStatus.totalFiles === 0 && !reindexStatus.message && (
              <div style={{ marginTop: '0.5rem', fontSize: '0.875rem', color: 'var(--text-light)' }}>
                Performing C-FIND query to discover studies...
              </div>
            )}
          </div>
        )}
      </div>

      {/* Custom Field Configuration */}
      {showFieldConfig && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Custom DICOM Fields</h2>
          </div>

          {/* Add New Field */}
          <div style={{ marginBottom: '1.5rem', padding: '1rem', background: 'var(--bg-color)', borderRadius: '6px' }}>
            <h3 style={{ marginBottom: '1rem', fontSize: '1rem' }}>Add Custom Field</h3>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: '1rem' }}>
              <div className="form-group">
                <label>Field Name</label>
                <input
                  type="text"
                  value={newField.fieldName}
                  onChange={(e) => setNewField({ ...newField, fieldName: e.target.value })}
                  placeholder="e.g., manufacturer"
                />
              </div>
              <div className="form-group">
                <label>Display Name</label>
                <input
                  type="text"
                  value={newField.displayName}
                  onChange={(e) => setNewField({ ...newField, displayName: e.target.value })}
                  placeholder="e.g., Manufacturer"
                />
              </div>
              <div className="form-group">
                <label>DICOM Tag</label>
                <select
                  value={newField.dicomTag}
                  onChange={(e) => {
                    const tag = availableTags?.tags.find(t => t.tag === e.target.value || t.keyword === e.target.value)
                    if (tag) {
                      setNewField({
                        ...newField,
                        dicomTag: tag.tag,
                        displayName: newField.displayName || tag.displayName,
                        fieldName: newField.fieldName || tag.keyword.toLowerCase(),
                        level: tag.level,
                        fieldType: tag.fieldType,
                      })
                    } else {
                      setNewField({ ...newField, dicomTag: e.target.value })
                    }
                  }}
                >
                  <option value="">Select a tag...</option>
                  {availableTags?.tags.map(tag => (
                    <option key={tag.keyword} value={tag.tag}>
                      {tag.displayName} ({tag.tag})
                    </option>
                  ))}
                </select>
              </div>
              <div className="form-group">
                <label>Level</label>
                <select
                  value={newField.level}
                  onChange={(e) => setNewField({ ...newField, level: e.target.value })}
                >
                  <option value="study">Study</option>
                  <option value="series">Series</option>
                  <option value="instance">Instance</option>
                </select>
              </div>
              <div className="form-group">
                <label>Type</label>
                <select
                  value={newField.fieldType}
                  onChange={(e) => setNewField({ ...newField, fieldType: e.target.value })}
                >
                  <option value="string">String</option>
                  <option value="number">Number</option>
                  <option value="date">Date</option>
                </select>
              </div>
              <div className="form-group" style={{ display: 'flex', alignItems: 'center', gap: '1rem', paddingTop: '1.5rem' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={newField.searchable}
                    onChange={(e) => setNewField({ ...newField, searchable: e.target.checked })}
                  />
                  Searchable
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
                  <input
                    type="checkbox"
                    checked={newField.displayInList}
                    onChange={(e) => setNewField({ ...newField, displayInList: e.target.checked })}
                  />
                  Show in List
                </label>
              </div>
            </div>
            <button className="btn btn-primary" onClick={handleAddField} style={{ marginTop: '1rem' }}>
              Add Field
            </button>
          </div>

          {/* Existing Fields */}
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Field Name</th>
                  <th>Display Name</th>
                  <th>DICOM Tag</th>
                  <th>Level</th>
                  <th>Type</th>
                  <th>Searchable</th>
                  <th>Show in List</th>
                  <th>Enabled</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {customFields?.fields.map(field => (
                  <tr key={field.id}>
                    <td><code>{field.fieldName}</code></td>
                    <td>{field.displayName}</td>
                    <td><code>{field.dicomTag}</code></td>
                    <td>{field.level}</td>
                    <td>{field.fieldType}</td>
                    <td>{field.searchable ? 'Yes' : 'No'}</td>
                    <td>{field.displayInList ? 'Yes' : 'No'}</td>
                    <td>
                      <button
                        className={`btn btn-sm ${field.enabled ? 'btn-success' : 'btn-secondary'}`}
                        onClick={() => handleToggleField(field)}
                      >
                        {field.enabled ? 'On' : 'Off'}
                      </button>
                    </td>
                    <td>
                      <button
                        className="btn btn-sm btn-danger"
                        onClick={() => handleDeleteField(field.id)}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
                {(!customFields?.fields || customFields.fields.length === 0) && (
                  <tr>
                    <td colSpan={9} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                      No custom fields configured. Add fields above to index additional DICOM tags.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <p style={{ marginTop: '1rem', color: 'var(--text-light)', fontSize: '0.875rem' }}>
            After adding new fields, run "Index All" to populate values for existing DICOM files.
          </p>
        </div>
      )}
    </div>
  )
}
