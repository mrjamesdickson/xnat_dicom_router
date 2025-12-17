import { useState, useCallback } from 'react'
import { useFetch, apiGet, apiPost, apiPut, apiDelete } from '../hooks/useApi'

interface IndexedStudy {
  id: number
  studyUid: string
  patientId: string
  patientName: string
  studyDate: string
  studyTime: string
  accessionNumber: string
  studyDescription: string
  modalities: string
  institutionName: string
  referringPhysician: string
  seriesCount: number
  instanceCount: number
  totalSize: number
  sourceRoute: string
  indexedAt: string
  filePaths: string
  customFields?: Record<string, string>
}

interface IndexedSeries {
  id: number
  seriesUid: string
  studyUid: string
  modality: string
  seriesNumber: number
  seriesDescription: string
  bodyPart: string
  instanceCount: number
  indexedAt: string
  customFields?: Record<string, string>
}

interface SearchResult {
  studies: IndexedStudy[]
  count: number
  offset: number
  limit: number
}

interface StudyDetail {
  study: IndexedStudy
  series: IndexedSeries[]
}

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
  message: string  // Progress message (e.g., "Chunk 2/12: Jan 2024")
  createdAt: string
}

interface DateCount {
  date: string  // YYYYMMDD format
  count: number
}

interface StudiesByDateResponse {
  dateCounts: DateCount[]
  totalDates: number
  totalStudies: number
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

export default function Search() {
  const [searchParams, setSearchParams] = useState({
    patientId: '',
    patientName: '',
    studyDateFrom: '',
    studyDateTo: '',
    modality: '',
    accessionNumber: '',
    institutionName: '',
    studyDescription: '',
    sourceRoute: '',
  })
  const [searchResults, setSearchResults] = useState<SearchResult | null>(null)
  const [searching, setSearching] = useState(false)
  const [selectedStudy, setSelectedStudy] = useState<StudyDetail | null>(null)
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

  const [selectedDestination, setSelectedDestination] = useState<string>('')
  const [clearExisting, setClearExisting] = useState(false)
  const [indexStudyDateFrom, setIndexStudyDateFrom] = useState<string>('')
  const [indexStudyDateTo, setIndexStudyDateTo] = useState<string>('')
  const [chunkSize, setChunkSize] = useState<string>('NONE')
  const [showDateChart, setShowDateChart] = useState(false)

  const { data: indexStats, refetch: refetchStats } = useFetch<IndexStats>('/search/stats', 60000)
  const { data: studiesByDate } = useFetch<StudiesByDateResponse>('/search/stats/by-date', 60000)
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

  const formatDate = (dateStr: string) => {
    if (!dateStr) return '-'
    // DICOM format: YYYYMMDD
    if (dateStr.length === 8) {
      return `${dateStr.slice(0, 4)}-${dateStr.slice(4, 6)}-${dateStr.slice(6, 8)}`
    }
    return dateStr
  }

  const handleSearch = useCallback(async () => {
    setSearching(true)
    try {
      const params = new URLSearchParams()
      Object.entries(searchParams).forEach(([key, value]) => {
        if (value) params.append(key, value)
      })
      params.append('limit', '100')

      const data = await apiGet<SearchResult>(`/search/studies?${params.toString()}`)
      setSearchResults(data)
    } catch (error) {
      console.error('Search failed:', error)
    } finally {
      setSearching(false)
    }
  }, [searchParams])

  const handleViewStudy = async (studyUid: string) => {
    try {
      const data = await apiGet<StudyDetail>(`/search/studies/${encodeURIComponent(studyUid)}`)
      setSelectedStudy(data)
    } catch (error) {
      console.error('Failed to load study details:', error)
    }
  }

  // Convert HTML date input (YYYY-MM-DD) to DICOM date format (YYYYMMDD)
  const toDicomDate = (htmlDate: string): string => {
    if (!htmlDate) return ''
    return htmlDate.replace(/-/g, '')
  }

  const handleStartReindex = async () => {
    if (selectedDestination) {
      const dest = destinations?.destinations.find(d => d.name === selectedDestination)
      const destDesc = dest ? `${dest.name} (${dest.type})` : selectedDestination
      const isDicomDest = dest?.type === 'dicom'

      // Build date range description for confirmation
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
        // Build query string with optional date range and chunk size
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

  const isReindexing = reindexStatus && 'status' in reindexStatus && reindexStatus.status === 'running'
  const reindexCompleted = reindexStatus && 'status' in reindexStatus &&
    (reindexStatus.status === 'completed' || reindexStatus.status === 'failed' || reindexStatus.status === 'cancelled')

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
            <div className="stat-value">{indexStats?.studyCount || 0}</div>
            <div className="stat-label">Studies</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{indexStats?.seriesCount || 0}</div>
            <div className="stat-label">Series</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{indexStats?.instanceCount || 0}</div>
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

        {/* Studies by Date Chart */}
        <div style={{ marginTop: '1rem' }}>
          <button
            className="btn btn-sm btn-secondary"
            onClick={() => setShowDateChart(!showDateChart)}
            style={{ marginBottom: showDateChart ? '1rem' : 0 }}
          >
            {showDateChart ? 'Hide' : 'Show'} Studies by Date Chart
          </button>

          {showDateChart && studiesByDate && studiesByDate.dateCounts.length > 0 && (
            <div style={{ background: 'var(--bg-color)', borderRadius: '6px', padding: '1rem' }}>
              <div style={{ marginBottom: '0.5rem', fontSize: '0.875rem', color: 'var(--text-light)' }}>
                {studiesByDate.totalStudies} studies across {studiesByDate.totalDates} different dates
              </div>
              <div style={{ overflowX: 'auto' }}>
                <svg
                  width={Math.max(600, studiesByDate.dateCounts.length * 20)}
                  height={200}
                  style={{ display: 'block', minWidth: '100%' }}
                >
                  {/* Background */}
                  <rect x={0} y={0} width="100%" height={200} fill="var(--bg-alt, #f8f9fa)" rx={4} />

                  {/* Grid lines */}
                  {[0, 0.25, 0.5, 0.75, 1].map((fraction, i) => (
                    <line
                      key={i}
                      x1={40}
                      y1={20 + fraction * 140}
                      x2={Math.max(600, studiesByDate.dateCounts.length * 20) - 20}
                      y2={20 + fraction * 140}
                      stroke="var(--border-color)"
                      strokeDasharray="4 4"
                      opacity={0.5}
                    />
                  ))}

                  {/* Y-axis labels */}
                  {(() => {
                    const maxCount = Math.max(...studiesByDate.dateCounts.map(d => d.count), 1)
                    return (
                      <>
                        <text x={35} y={24} textAnchor="end" fontSize={10} fill="var(--text-light)">{maxCount}</text>
                        <text x={35} y={94} textAnchor="end" fontSize={10} fill="var(--text-light)">{Math.round(maxCount / 2)}</text>
                        <text x={35} y={164} textAnchor="end" fontSize={10} fill="var(--text-light)">0</text>
                      </>
                    )
                  })()}

                  {/* Bars */}
                  {studiesByDate.dateCounts.map((dc, i) => {
                    const maxCount = Math.max(...studiesByDate.dateCounts.map(d => d.count), 1)
                    const barHeight = (dc.count / maxCount) * 140
                    const barWidth = Math.min(16, Math.max(600 - 60, studiesByDate.dateCounts.length * 20 - 60) / studiesByDate.dateCounts.length - 2)
                    const x = 45 + i * ((Math.max(600, studiesByDate.dateCounts.length * 20) - 65) / studiesByDate.dateCounts.length)

                    return (
                      <g key={dc.date}>
                        <rect
                          x={x}
                          y={160 - barHeight}
                          width={barWidth}
                          height={barHeight}
                          fill="var(--primary-color)"
                          opacity={0.8}
                          rx={2}
                        >
                          <title>{`${formatDate(dc.date)}: ${dc.count} studies`}</title>
                        </rect>
                        {/* Show date label for every Nth bar depending on count */}
                        {(studiesByDate.dateCounts.length <= 30 ||
                          i % Math.ceil(studiesByDate.dateCounts.length / 30) === 0) && (
                          <text
                            x={x + barWidth / 2}
                            y={180}
                            textAnchor="middle"
                            fontSize={8}
                            fill="var(--text-light)"
                            transform={`rotate(-45, ${x + barWidth / 2}, 180)`}
                          >
                            {dc.date.slice(4, 6) + '/' + dc.date.slice(6, 8)}
                          </text>
                        )}
                      </g>
                    )
                  })}
                </svg>
              </div>
            </div>
          )}

          {showDateChart && (!studiesByDate || studiesByDate.dateCounts.length === 0) && (
            <div style={{
              background: 'var(--bg-color)',
              borderRadius: '6px',
              padding: '2rem',
              textAlign: 'center',
              color: 'var(--text-light)'
            }}>
              No study date data available. Index some DICOM files to see the distribution.
            </div>
          )}
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
                {selectedDestination && (
                  <span style={{ marginLeft: '0.5rem', color: 'var(--text-light)', fontSize: '0.875rem' }}>
                    from {selectedDestination}
                  </span>
                )}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                <span style={{ fontSize: '0.875rem' }}>
                  {reindexStatus.processedFiles} / {reindexStatus.totalFiles} {
                    destinations?.destinations.find(d => d.name === selectedDestination)?.type === 'dicom' ? 'studies' : 'files'
                  }
                </span>
                {/* Cancel button - only show when running */}
                {reindexStatus.status === 'running' && (
                  <button
                    className="btn btn-sm btn-danger"
                    onClick={handleCancelReindex}
                    style={{ padding: '0.25rem 0.75rem' }}
                  >
                    Cancel
                  </button>
                )}
                {/* Restart button - show when completed/failed/cancelled */}
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

            {/* Error message */}
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

            {/* Progress message (e.g., chunk info) */}
            {reindexStatus.status === 'running' && reindexStatus.message && (
              <div style={{ marginTop: '0.5rem', fontSize: '0.875rem', color: 'var(--primary-color)', fontWeight: 500 }}>
                {reindexStatus.message}
              </div>
            )}

            {/* For DICOM destinations, show what we're doing when no progress yet */}
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
            After adding new fields, run "Reindex All" to populate values for existing DICOM files.
          </p>
        </div>
      )}

      {/* Search Form */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Search Studies</h2>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: '1rem' }}>
          <div className="form-group">
            <label>Patient ID</label>
            <input
              type="text"
              value={searchParams.patientId}
              onChange={(e) => setSearchParams({ ...searchParams, patientId: e.target.value })}
              placeholder="Search patient ID..."
            />
          </div>
          <div className="form-group">
            <label>Patient Name</label>
            <input
              type="text"
              value={searchParams.patientName}
              onChange={(e) => setSearchParams({ ...searchParams, patientName: e.target.value })}
              placeholder="Search patient name..."
            />
          </div>
          <div className="form-group">
            <label>Study Date From</label>
            <input
              type="date"
              value={searchParams.studyDateFrom}
              onChange={(e) => setSearchParams({ ...searchParams, studyDateFrom: e.target.value.replace(/-/g, '') })}
            />
          </div>
          <div className="form-group">
            <label>Study Date To</label>
            <input
              type="date"
              value={searchParams.studyDateTo}
              onChange={(e) => setSearchParams({ ...searchParams, studyDateTo: e.target.value.replace(/-/g, '') })}
            />
          </div>
          <div className="form-group">
            <label>Modality</label>
            <select
              value={searchParams.modality}
              onChange={(e) => setSearchParams({ ...searchParams, modality: e.target.value })}
            >
              <option value="">All</option>
              <option value="CT">CT</option>
              <option value="MR">MR</option>
              <option value="US">US</option>
              <option value="XA">XA</option>
              <option value="CR">CR</option>
              <option value="DX">DX</option>
              <option value="PT">PT</option>
              <option value="NM">NM</option>
            </select>
          </div>
          <div className="form-group">
            <label>Accession Number</label>
            <input
              type="text"
              value={searchParams.accessionNumber}
              onChange={(e) => setSearchParams({ ...searchParams, accessionNumber: e.target.value })}
              placeholder="Search accession..."
            />
          </div>
          <div className="form-group">
            <label>Institution</label>
            <input
              type="text"
              value={searchParams.institutionName}
              onChange={(e) => setSearchParams({ ...searchParams, institutionName: e.target.value })}
              placeholder="Search institution..."
            />
          </div>
          <div className="form-group">
            <label>Description</label>
            <input
              type="text"
              value={searchParams.studyDescription}
              onChange={(e) => setSearchParams({ ...searchParams, studyDescription: e.target.value })}
              placeholder="Search description..."
            />
          </div>
        </div>

        <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
          <button className="btn btn-primary" onClick={handleSearch} disabled={searching}>
            {searching ? 'Searching...' : 'Search'}
          </button>
          <button
            className="btn btn-secondary"
            onClick={() => {
              setSearchParams({
                patientId: '',
                patientName: '',
                studyDateFrom: '',
                studyDateTo: '',
                modality: '',
                accessionNumber: '',
                institutionName: '',
                studyDescription: '',
                sourceRoute: '',
              })
              setSearchResults(null)
            }}
          >
            Clear
          </button>
        </div>
      </div>

      {/* Search Results */}
      {searchResults && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Results ({searchResults.count})</h2>
          </div>

          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Patient ID</th>
                  <th>Patient Name</th>
                  <th>Study Date</th>
                  <th>Modalities</th>
                  <th>Description</th>
                  <th>Series</th>
                  <th>Images</th>
                  <th>Size</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {searchResults.studies.map(study => (
                  <tr key={study.studyUid}>
                    <td>{study.patientId || '-'}</td>
                    <td>{study.patientName || '-'}</td>
                    <td>{formatDate(study.studyDate)}</td>
                    <td>
                      <span className="status-badge status-up">{study.modalities || '-'}</span>
                    </td>
                    <td>{study.studyDescription || '-'}</td>
                    <td>{study.seriesCount}</td>
                    <td>{study.instanceCount}</td>
                    <td>{formatBytes(study.totalSize)}</td>
                    <td>
                      <button
                        className="btn btn-sm btn-secondary"
                        onClick={() => handleViewStudy(study.studyUid)}
                      >
                        View
                      </button>
                    </td>
                  </tr>
                ))}
                {searchResults.studies.length === 0 && (
                  <tr>
                    <td colSpan={9} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                      No studies found matching your criteria
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Study Detail Modal */}
      {selectedStudy && (
        <div className="modal-overlay" onClick={() => setSelectedStudy(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '900px' }}>
            <div className="modal-header">
              <h2>Study Details</h2>
              <button className="close-btn" onClick={() => setSelectedStudy(null)}>x</button>
            </div>
            <div className="modal-content">
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1.5rem' }}>
                <div>
                  <strong>Patient ID:</strong> {selectedStudy.study.patientId || '-'}
                </div>
                <div>
                  <strong>Patient Name:</strong> {selectedStudy.study.patientName || '-'}
                </div>
                <div>
                  <strong>Study Date:</strong> {formatDate(selectedStudy.study.studyDate)}
                </div>
                <div>
                  <strong>Accession #:</strong> {selectedStudy.study.accessionNumber || '-'}
                </div>
                <div>
                  <strong>Institution:</strong> {selectedStudy.study.institutionName || '-'}
                </div>
                <div>
                  <strong>Referring:</strong> {selectedStudy.study.referringPhysician || '-'}
                </div>
                <div style={{ gridColumn: '1 / -1' }}>
                  <strong>Description:</strong> {selectedStudy.study.studyDescription || '-'}
                </div>
                <div style={{ gridColumn: '1 / -1' }}>
                  <strong>Study UID:</strong> <code style={{ fontSize: '0.75rem' }}>{selectedStudy.study.studyUid}</code>
                </div>

                {/* Custom Fields */}
                {selectedStudy.study.customFields && Object.keys(selectedStudy.study.customFields).length > 0 && (
                  <>
                    <div style={{ gridColumn: '1 / -1', marginTop: '0.5rem', borderTop: '1px solid var(--border-color)', paddingTop: '0.5rem' }}>
                      <strong>Custom Fields:</strong>
                    </div>
                    {Object.entries(selectedStudy.study.customFields).map(([key, value]) => (
                      <div key={key}>
                        <strong>{key}:</strong> {value}
                      </div>
                    ))}
                  </>
                )}
              </div>

              <h3 style={{ marginBottom: '0.5rem' }}>Series ({selectedStudy.series.length})</h3>
              <div className="table-container">
                <table>
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Modality</th>
                      <th>Description</th>
                      <th>Body Part</th>
                      <th>Images</th>
                    </tr>
                  </thead>
                  <tbody>
                    {selectedStudy.series.map(s => (
                      <tr key={s.seriesUid}>
                        <td>{s.seriesNumber}</td>
                        <td><span className="status-badge status-up">{s.modality}</span></td>
                        <td>{s.seriesDescription || '-'}</td>
                        <td>{s.bodyPart || '-'}</td>
                        <td>{s.instanceCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
