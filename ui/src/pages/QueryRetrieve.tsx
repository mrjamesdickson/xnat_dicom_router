import { useState, useEffect } from 'react'
import { useFetch, apiPost, apiGet } from '../hooks/useApi'

interface DicomSource {
  name: string
  aeTitle: string
  host: string
  port: number
  enabled: boolean
  available: boolean
  description: string
}

interface TargetRoute {
  aeTitle: string
  port: number
  description: string
  destinationCount: number
}

interface StudyResult {
  studyInstanceUID: string
  patientID: string
  patientName: string
  accessionNumber: string
  studyDate: string
  studyTime: string
  studyDescription: string
  modality: string
  numberOfSeries: number
  numberOfInstances: number
  referringPhysician: string
  queryIdentifier?: string
}

interface RetrieveJob {
  jobId: string
  source: string
  targetRoute: string
  status: string
  totalStudies: number
  completedCount: number
  failedCount: number
  progress: number
  startTime: number
  endTime?: number
  durationMs?: number
  messages: string[]
  errors: string[]
}

interface SeriesResult {
  seriesInstanceUID: string
  seriesNumber: number
  seriesDescription: string
  modality: string
  numberOfInstances: number
  seriesDate: string
  seriesTime: string
  bodyPartExamined: string
}

interface ImageResult {
  sopInstanceUID: string
  sopClassUID: string
  instanceNumber: number
  rows: number
  columns: number
  imageType: string
  sliceLocation: number
  sliceThickness: number
}

type QueryMode = 'single' | 'bulk'
type BulkIdentifierType = 'studyInstanceUID' | 'accessionNumber' | 'patientID'

export default function QueryRetrieve() {
  // Sources and routes
  const { data: sourcesData, loading: loadingSources, refetch: refetchSources } = useFetch<{ sources: DicomSource[] }>('/query-retrieve/sources')
  const { data: routesData, loading: loadingRoutes } = useFetch<{ routes: TargetRoute[] }>('/query-retrieve/routes')
  const { data: jobsData, refetch: refetchJobs } = useFetch<{ jobs: RetrieveJob[] }>('/query-retrieve/retrieve/jobs', 5000)

  // Query mode
  const [queryMode, setQueryMode] = useState<QueryMode>('single')

  // Selected source
  const [selectedSource, setSelectedSource] = useState<string>('')

  // Single query fields
  const [patientID, setPatientID] = useState('')
  const [patientName, setPatientName] = useState('')
  const [accessionNumber, setAccessionNumber] = useState('')
  const [studyInstanceUID, setStudyInstanceUID] = useState('')
  const [modality, setModality] = useState('')
  const [studyDateFrom, setStudyDateFrom] = useState('')
  const [studyDateTo, setStudyDateTo] = useState('')

  // Bulk query fields
  const [bulkIdentifiers, setBulkIdentifiers] = useState('')
  const [bulkIdentifierType, setBulkIdentifierType] = useState<BulkIdentifierType>('studyInstanceUID')

  // Query results
  const [queryResults, setQueryResults] = useState<StudyResult[]>([])
  const [notFound, setNotFound] = useState<string[]>([])
  const [querying, setQuerying] = useState(false)
  const [queryError, setQueryError] = useState<string | null>(null)

  // Selected studies for retrieve
  const [selectedStudies, setSelectedStudies] = useState<Set<string>>(new Set())

  // Retrieve settings
  const [targetRoute, setTargetRoute] = useState<string>('')
  const [retrieving, setRetrieving] = useState(false)
  const [retrieveError, setRetrieveError] = useState<string | null>(null)

  // Study Details modal state
  const [showStudyDetails, setShowStudyDetails] = useState(false)
  const [selectedStudyForDetails, setSelectedStudyForDetails] = useState<StudyResult | null>(null)
  const [seriesList, setSeriesList] = useState<SeriesResult[]>([])
  const [loadingSeries, setLoadingSeries] = useState(false)
  const [selectedSeries, setSelectedSeries] = useState<SeriesResult | null>(null)
  const [imageList, setImageList] = useState<ImageResult[]>([])
  const [loadingImages, setLoadingImages] = useState(false)
  const [currentImageIndex, setCurrentImageIndex] = useState(0)

  // Set default source when loaded
  useEffect(() => {
    if (sourcesData?.sources && sourcesData.sources.length > 0 && !selectedSource) {
      const availableSource = sourcesData.sources.find(s => s.enabled && s.available)
      if (availableSource) {
        setSelectedSource(availableSource.name)
      }
    }
  }, [sourcesData, selectedSource])

  // Set default route when loaded
  useEffect(() => {
    if (routesData?.routes && routesData.routes.length > 0 && !targetRoute) {
      setTargetRoute(routesData.routes[0].aeTitle)
    }
  }, [routesData, targetRoute])

  const handleSingleQuery = async () => {
    if (!selectedSource) {
      setQueryError('Please select a source')
      return
    }

    setQuerying(true)
    setQueryError(null)
    setQueryResults([])
    setSelectedStudies(new Set())

    try {
      const queryParams: Record<string, string> = {
        source: selectedSource
      }

      if (patientID) queryParams.patientID = patientID
      if (patientName) queryParams.patientName = patientName
      if (accessionNumber) queryParams.accessionNumber = accessionNumber
      if (studyInstanceUID) queryParams.studyInstanceUID = studyInstanceUID
      if (modality) queryParams.modality = modality
      if (studyDateFrom) queryParams.studyDateFrom = studyDateFrom
      if (studyDateTo) queryParams.studyDateTo = studyDateTo

      const result = await apiPost<{ studies: StudyResult[], count: number }>('/query-retrieve/query', queryParams)
      setQueryResults(result.studies)

      if (result.studies.length === 0) {
        setQueryError('No studies found matching the criteria')
      }
    } catch (err) {
      setQueryError(err instanceof Error ? err.message : 'Query failed')
    } finally {
      setQuerying(false)
    }
  }

  const handleBulkQuery = async () => {
    if (!selectedSource) {
      setQueryError('Please select a source')
      return
    }

    const identifiers = bulkIdentifiers
      .split(/[\n,;]/)
      .map(id => id.trim())
      .filter(id => id.length > 0)

    if (identifiers.length === 0) {
      setQueryError('Please enter at least one identifier')
      return
    }

    setQuerying(true)
    setQueryError(null)
    setQueryResults([])
    setNotFound([])
    setSelectedStudies(new Set())

    try {
      const result = await apiPost<{
        studies: StudyResult[]
        found: number
        notFound: string[]
        errors: string[]
      }>('/query-retrieve/query/bulk', {
        source: selectedSource,
        identifiers,
        identifierType: bulkIdentifierType
      })

      setQueryResults(result.studies)
      setNotFound(result.notFound)

      if (result.studies.length === 0) {
        setQueryError('No studies found')
      } else if (result.notFound.length > 0) {
        setQueryError(`Found ${result.found} studies, ${result.notFound.length} not found`)
      }
    } catch (err) {
      setQueryError(err instanceof Error ? err.message : 'Bulk query failed')
    } finally {
      setQuerying(false)
    }
  }

  const handleQuery = () => {
    if (queryMode === 'single') {
      handleSingleQuery()
    } else {
      handleBulkQuery()
    }
  }

  const handleSelectAll = () => {
    if (selectedStudies.size === queryResults.length) {
      setSelectedStudies(new Set())
    } else {
      setSelectedStudies(new Set(queryResults.map(s => s.studyInstanceUID)))
    }
  }

  const handleSelectStudy = (uid: string) => {
    const newSelected = new Set(selectedStudies)
    if (newSelected.has(uid)) {
      newSelected.delete(uid)
    } else {
      newSelected.add(uid)
    }
    setSelectedStudies(newSelected)
  }

  const handleRetrieve = async () => {
    if (selectedStudies.size === 0) {
      setRetrieveError('Please select at least one study')
      return
    }

    if (!targetRoute) {
      setRetrieveError('Please select a target route')
      return
    }

    setRetrieving(true)
    setRetrieveError(null)

    try {
      await apiPost('/query-retrieve/retrieve', {
        source: selectedSource,
        targetRoute,
        studyUIDs: Array.from(selectedStudies)
      })

      // Clear selection after successful retrieve start
      setSelectedStudies(new Set())
      refetchJobs()
    } catch (err) {
      setRetrieveError(err instanceof Error ? err.message : 'Retrieve failed')
    } finally {
      setRetrieving(false)
    }
  }

  // Study Details handlers
  const handleViewStudyDetails = async (study: StudyResult) => {
    setSelectedStudyForDetails(study)
    setShowStudyDetails(true)
    setSeriesList([])
    setSelectedSeries(null)
    setImageList([])
    setCurrentImageIndex(0)
    setLoadingSeries(true)

    try {
      const result = await apiGet<{ series: SeriesResult[] }>(
        `/query-retrieve/study/${encodeURIComponent(study.studyInstanceUID)}/series?source=${encodeURIComponent(selectedSource)}`
      )
      setSeriesList(result.series || [])
    } catch (err) {
      console.error('Failed to fetch series:', err)
    } finally {
      setLoadingSeries(false)
    }
  }

  const handleSelectSeries = async (series: SeriesResult) => {
    if (!selectedStudyForDetails) return

    setSelectedSeries(series)
    setImageList([])
    setCurrentImageIndex(0)
    setLoadingImages(true)

    try {
      const result = await apiGet<{ images: ImageResult[] }>(
        `/query-retrieve/series/${encodeURIComponent(series.seriesInstanceUID)}/images?source=${encodeURIComponent(selectedSource)}&studyUID=${encodeURIComponent(selectedStudyForDetails.studyInstanceUID)}`
      )
      setImageList(result.images || [])
    } catch (err) {
      console.error('Failed to fetch images:', err)
    } finally {
      setLoadingImages(false)
    }
  }

  const handlePrevImage = () => {
    setCurrentImageIndex(prev => Math.max(0, prev - 1))
  }

  const handleNextImage = () => {
    setCurrentImageIndex(prev => Math.min(imageList.length - 1, prev + 1))
  }

  const handleCloseStudyDetails = () => {
    setShowStudyDetails(false)
    setSelectedStudyForDetails(null)
    setSeriesList([])
    setSelectedSeries(null)
    setImageList([])
    setCurrentImageIndex(0)
  }

  const getImagePreviewUrl = (sopInstanceUID: string) => {
    return `/api/query-retrieve/image/preview?source=${encodeURIComponent(selectedSource)}&sopInstanceUID=${encodeURIComponent(sopInstanceUID)}`
  }

  const formatDate = (dateStr: string) => {
    if (!dateStr || dateStr.length !== 8) return dateStr
    return `${dateStr.slice(0, 4)}-${dateStr.slice(4, 6)}-${dateStr.slice(6, 8)}`
  }

  const formatTime = (timeStr: string) => {
    if (!timeStr || timeStr.length < 6) return timeStr
    return `${timeStr.slice(0, 2)}:${timeStr.slice(2, 4)}:${timeStr.slice(4, 6)}`
  }

  const getStatusBadgeClass = (status: string) => {
    switch (status) {
      case 'COMPLETED': return 'badge badge-success'
      case 'RUNNING': return 'badge badge-primary'
      case 'PENDING': return 'badge badge-secondary'
      case 'FAILED': return 'badge badge-danger'
      case 'CANCELLED': return 'badge badge-warning'
      case 'COMPLETED_WITH_ERRORS': return 'badge badge-warning'
      default: return 'badge'
    }
  }

  return (
    <div className="page-container">
      <div className="page-header">
        <h2>Query / Retrieve</h2>
        <p className="subtitle">Query remote PACS servers and retrieve studies to configured routes</p>
        {(!sourcesData?.sources || sourcesData.sources.length === 0) && !loadingSources && (
          <div style={{
            marginTop: '0.5rem',
            padding: '0.75rem 1rem',
            background: 'var(--info-bg, #e3f2fd)',
            borderRadius: '4px',
            border: '1px solid var(--info-border, #90caf9)',
            fontSize: '0.9rem'
          }}>
            <strong>No PACS sources configured.</strong>{' '}
            <a href="/destinations" style={{ color: 'var(--primary-color)' }}>
              Add a DICOM destination
            </a>{' '}
            to use as a query source.
          </div>
        )}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
        {/* Left column - Query */}
        <div className="card">
          <h3>Query DICOM Source</h3>

          {/* Source selection */}
          <div className="form-group">
            <label>DICOM Source (PACS)</label>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <select
                value={selectedSource}
                onChange={(e) => setSelectedSource(e.target.value)}
                style={{ flex: 1 }}
              >
                <option value="">Select a source...</option>
                {sourcesData?.sources.map(source => (
                  <option
                    key={source.name}
                    value={source.name}
                    disabled={!source.enabled}
                  >
                    {source.name} ({source.aeTitle}@{source.host}:{source.port})
                    {!source.enabled && ' [disabled]'}
                    {source.enabled && !source.available && ' [unavailable]'}
                  </option>
                ))}
              </select>
              <button onClick={() => refetchSources()} className="btn-secondary" title="Refresh sources">
                Refresh
              </button>
              <a
                href="/destinations"
                className="btn-secondary"
                style={{ textDecoration: 'none', display: 'flex', alignItems: 'center' }}
                title="Add a new DICOM source"
              >
                + Add
              </a>
            </div>
          </div>

          {/* Query mode tabs */}
          <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem' }}>
            <button
              className={queryMode === 'single' ? 'btn-primary' : 'btn-secondary'}
              onClick={() => setQueryMode('single')}
            >
              Single Query
            </button>
            <button
              className={queryMode === 'bulk' ? 'btn-primary' : 'btn-secondary'}
              onClick={() => setQueryMode('bulk')}
            >
              Bulk Query
            </button>
          </div>

          {queryMode === 'single' ? (
            <>
              {/* Single query fields */}
              <div className="form-group">
                <label>Patient ID</label>
                <input
                  type="text"
                  value={patientID}
                  onChange={(e) => setPatientID(e.target.value)}
                  placeholder="Patient ID (wildcards: * or ?)"
                />
              </div>

              <div className="form-group">
                <label>Patient Name</label>
                <input
                  type="text"
                  value={patientName}
                  onChange={(e) => setPatientName(e.target.value)}
                  placeholder="Last^First or Last* for wildcard"
                />
              </div>

              <div className="form-group">
                <label>Accession Number</label>
                <input
                  type="text"
                  value={accessionNumber}
                  onChange={(e) => setAccessionNumber(e.target.value)}
                  placeholder="Accession number"
                />
              </div>

              <div className="form-group">
                <label>Study Instance UID</label>
                <input
                  type="text"
                  value={studyInstanceUID}
                  onChange={(e) => setStudyInstanceUID(e.target.value)}
                  placeholder="Study UID"
                />
              </div>

              <div className="form-group">
                <label>Modality</label>
                <select value={modality} onChange={(e) => setModality(e.target.value)}>
                  <option value="">Any</option>
                  <option value="CT">CT</option>
                  <option value="MR">MR</option>
                  <option value="PT">PET</option>
                  <option value="NM">NM</option>
                  <option value="US">US</option>
                  <option value="CR">CR</option>
                  <option value="DX">DX</option>
                  <option value="XA">XA</option>
                  <option value="MG">MG</option>
                  <option value="RF">RF</option>
                </select>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                <div className="form-group">
                  <label>Study Date From</label>
                  <input
                    type="date"
                    value={studyDateFrom}
                    onChange={(e) => setStudyDateFrom(e.target.value)}
                  />
                </div>
                <div className="form-group">
                  <label>Study Date To</label>
                  <input
                    type="date"
                    value={studyDateTo}
                    onChange={(e) => setStudyDateTo(e.target.value)}
                  />
                </div>
              </div>
            </>
          ) : (
            <>
              {/* Bulk query fields */}
              <div className="form-group">
                <label>Identifier Type</label>
                <select
                  value={bulkIdentifierType}
                  onChange={(e) => setBulkIdentifierType(e.target.value as BulkIdentifierType)}
                >
                  <option value="studyInstanceUID">Study Instance UID</option>
                  <option value="accessionNumber">Accession Number</option>
                  <option value="patientID">Patient ID</option>
                </select>
              </div>

              <div className="form-group">
                <label>Identifiers (one per line, or comma/semicolon separated)</label>
                <textarea
                  value={bulkIdentifiers}
                  onChange={(e) => setBulkIdentifiers(e.target.value)}
                  rows={10}
                  placeholder={`Enter ${bulkIdentifierType}s, one per line...\n\nExample:\n1.2.840.113619.2.55.3\n1.2.840.113619.2.55.4\n1.2.840.113619.2.55.5`}
                  style={{ fontFamily: 'monospace', fontSize: '0.85rem' }}
                />
              </div>

              {notFound.length > 0 && (
                <div style={{
                  background: 'var(--warning-bg, #fff3cd)',
                  border: '1px solid var(--warning-border, #ffc107)',
                  borderRadius: '4px',
                  padding: '0.75rem',
                  marginBottom: '1rem'
                }}>
                  <strong>Not found ({notFound.length}):</strong>
                  <div style={{ fontSize: '0.85rem', fontFamily: 'monospace', marginTop: '0.5rem' }}>
                    {notFound.slice(0, 10).join(', ')}
                    {notFound.length > 10 && ` ... and ${notFound.length - 10} more`}
                  </div>
                </div>
              )}
            </>
          )}

          {queryError && (
            <div className="error-message" style={{ marginBottom: '1rem' }}>
              {queryError}
            </div>
          )}

          <button
            className="btn-primary"
            onClick={handleQuery}
            disabled={querying || !selectedSource}
            style={{ width: '100%' }}
          >
            {querying ? 'Querying...' : 'Query'}
          </button>
        </div>

        {/* Right column - Results and Retrieve */}
        <div className="card">
          <h3>
            Query Results
            {queryResults.length > 0 && (
              <span style={{ fontWeight: 'normal', fontSize: '0.9rem', marginLeft: '0.5rem' }}>
                ({queryResults.length} studies)
              </span>
            )}
          </h3>

          {queryResults.length > 0 && (
            <>
              <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem', alignItems: 'center' }}>
                <button onClick={handleSelectAll} className="btn-secondary">
                  {selectedStudies.size === queryResults.length ? 'Deselect All' : 'Select All'}
                </button>
                <span style={{ marginLeft: 'auto', fontSize: '0.9rem' }}>
                  {selectedStudies.size} selected
                </span>
              </div>

              <div style={{
                maxHeight: '400px',
                overflowY: 'auto',
                border: '1px solid var(--border-color)',
                borderRadius: '4px'
              }}>
                <table className="data-table" style={{ margin: 0 }}>
                  <thead>
                    <tr>
                      <th style={{ width: '40px' }}></th>
                      <th>Patient</th>
                      <th>Accession</th>
                      <th>Date</th>
                      <th>Modality</th>
                      <th>Series</th>
                      <th>Images</th>
                      <th style={{ width: '60px' }}></th>
                    </tr>
                  </thead>
                  <tbody>
                    {queryResults.map(study => (
                      <tr
                        key={study.studyInstanceUID}
                        onClick={() => handleSelectStudy(study.studyInstanceUID)}
                        style={{ cursor: 'pointer' }}
                        className={selectedStudies.has(study.studyInstanceUID) ? 'selected' : ''}
                      >
                        <td>
                          <input
                            type="checkbox"
                            checked={selectedStudies.has(study.studyInstanceUID)}
                            onChange={() => handleSelectStudy(study.studyInstanceUID)}
                            onClick={(e) => e.stopPropagation()}
                          />
                        </td>
                        <td>
                          <div style={{ fontWeight: 500 }}>{study.patientName || study.patientID}</div>
                          <div style={{ fontSize: '0.8rem', opacity: 0.7 }}>{study.patientID}</div>
                        </td>
                        <td>{study.accessionNumber}</td>
                        <td>
                          <div>{formatDate(study.studyDate)}</div>
                          <div style={{ fontSize: '0.8rem', opacity: 0.7 }}>{formatTime(study.studyTime)}</div>
                        </td>
                        <td>{study.modality}</td>
                        <td style={{ textAlign: 'center' }}>{study.numberOfSeries}</td>
                        <td style={{ textAlign: 'center' }}>{study.numberOfInstances}</td>
                        <td>
                          <button
                            className="btn-secondary"
                            onClick={(e) => {
                              e.stopPropagation()
                              handleViewStudyDetails(study)
                            }}
                            style={{ padding: '2px 8px', fontSize: '0.8rem' }}
                            title="View study details"
                          >
                            View
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Retrieve section */}
              <div style={{ marginTop: '1.5rem', paddingTop: '1rem', borderTop: '1px solid var(--border-color)' }}>
                <h4>Retrieve to Route</h4>

                <div className="form-group">
                  <label>Target Route (C-MOVE destination)</label>
                  <select
                    value={targetRoute}
                    onChange={(e) => setTargetRoute(e.target.value)}
                  >
                    <option value="">Select a route...</option>
                    {routesData?.routes.map(route => (
                      <option key={route.aeTitle} value={route.aeTitle}>
                        {route.aeTitle} (port {route.port}) - {route.destinationCount} destination(s)
                      </option>
                    ))}
                  </select>
                </div>

                {retrieveError && (
                  <div className="error-message" style={{ marginBottom: '1rem' }}>
                    {retrieveError}
                  </div>
                )}

                <button
                  className="btn-primary"
                  onClick={handleRetrieve}
                  disabled={retrieving || selectedStudies.size === 0 || !targetRoute}
                  style={{ width: '100%' }}
                >
                  {retrieving ? 'Starting Retrieve...' : `Retrieve ${selectedStudies.size} Study(ies)`}
                </button>
              </div>
            </>
          )}

          {queryResults.length === 0 && !querying && (
            <div style={{ padding: '2rem', textAlign: 'center', opacity: 0.6 }}>
              Query results will appear here
            </div>
          )}
        </div>
      </div>

      {/* Active Jobs */}
      {jobsData?.jobs && jobsData.jobs.length > 0 && (
        <div className="card" style={{ marginTop: '1.5rem' }}>
          <h3>Retrieve Jobs</h3>
          <table className="data-table">
            <thead>
              <tr>
                <th>Job ID</th>
                <th>Source</th>
                <th>Target Route</th>
                <th>Status</th>
                <th>Progress</th>
                <th>Studies</th>
                <th>Duration</th>
              </tr>
            </thead>
            <tbody>
              {jobsData.jobs.map(job => (
                <tr key={job.jobId}>
                  <td style={{ fontFamily: 'monospace', fontSize: '0.85rem' }}>{job.jobId}</td>
                  <td>{job.source}</td>
                  <td>{job.targetRoute}</td>
                  <td>
                    <span className={getStatusBadgeClass(job.status)}>{job.status}</span>
                  </td>
                  <td>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <div style={{
                        width: '100px',
                        height: '8px',
                        background: 'var(--progress-bg, #e0e0e0)',
                        borderRadius: '4px',
                        overflow: 'hidden'
                      }}>
                        <div style={{
                          width: `${job.progress}%`,
                          height: '100%',
                          background: job.status === 'FAILED' ? 'var(--error-color)' : 'var(--success-color)',
                          transition: 'width 0.3s'
                        }} />
                      </div>
                      <span style={{ fontSize: '0.85rem' }}>{job.progress}%</span>
                    </div>
                  </td>
                  <td>
                    <span style={{ color: 'var(--success-color)' }}>{job.completedCount}</span>
                    {' / '}
                    {job.failedCount > 0 && (
                      <span style={{ color: 'var(--error-color)' }}>{job.failedCount} failed / </span>
                    )}
                    {job.totalStudies}
                  </td>
                  <td>
                    {job.durationMs ? `${(job.durationMs / 1000).toFixed(1)}s` :
                      job.startTime ? `${((Date.now() - job.startTime) / 1000).toFixed(0)}s` : '-'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Study Details Modal */}
      {showStudyDetails && selectedStudyForDetails && (
        <div
          style={{
            position: 'fixed',
            top: 0,
            left: 0,
            right: 0,
            bottom: 0,
            background: 'rgba(0,0,0,0.6)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000
          }}
          onClick={handleCloseStudyDetails}
        >
          <div
            style={{
              background: 'var(--card-background, white)',
              borderRadius: '8px',
              width: '95%',
              maxWidth: '1400px',
              maxHeight: '90vh',
              overflow: 'hidden',
              display: 'flex',
              flexDirection: 'column',
              boxShadow: '0 4px 20px rgba(0,0,0,0.3)'
            }}
            onClick={e => e.stopPropagation()}
          >
            {/* Modal Header */}
            <div style={{
              padding: '1rem 1.5rem',
              borderBottom: '1px solid var(--border-color)',
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center'
            }}>
              <div>
                <h3 style={{ margin: 0 }}>Study Details</h3>
                <div style={{ fontSize: '0.9rem', opacity: 0.8, marginTop: '0.25rem' }}>
                  {selectedStudyForDetails.patientName || selectedStudyForDetails.patientID} - {selectedStudyForDetails.studyDescription || 'No Description'}
                </div>
              </div>
              <button
                onClick={handleCloseStudyDetails}
                style={{
                  background: 'none',
                  border: 'none',
                  fontSize: '1.5rem',
                  cursor: 'pointer',
                  opacity: 0.7,
                  padding: '0.25rem 0.5rem'
                }}
              >
                x
              </button>
            </div>

            {/* Modal Body */}
            <div style={{ display: 'flex', flex: 1, overflow: 'hidden' }}>
              {/* Left Panel - Series List */}
              <div style={{
                width: '300px',
                borderRight: '1px solid var(--border-color)',
                display: 'flex',
                flexDirection: 'column',
                overflow: 'hidden'
              }}>
                <div style={{
                  padding: '0.75rem 1rem',
                  background: 'var(--background-secondary, #f5f5f5)',
                  fontWeight: 500,
                  fontSize: '0.9rem'
                }}>
                  Series ({seriesList.length})
                </div>
                <div style={{ flex: 1, overflowY: 'auto' }}>
                  {loadingSeries ? (
                    <div style={{ padding: '2rem', textAlign: 'center', opacity: 0.6 }}>
                      Loading series...
                    </div>
                  ) : seriesList.length === 0 ? (
                    <div style={{ padding: '2rem', textAlign: 'center', opacity: 0.6 }}>
                      No series found
                    </div>
                  ) : (
                    seriesList.map(series => (
                      <div
                        key={series.seriesInstanceUID}
                        onClick={() => handleSelectSeries(series)}
                        style={{
                          padding: '0.75rem 1rem',
                          borderBottom: '1px solid var(--border-color)',
                          cursor: 'pointer',
                          background: selectedSeries?.seriesInstanceUID === series.seriesInstanceUID
                            ? 'var(--primary-light, #e3f2fd)'
                            : 'transparent'
                        }}
                      >
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                          <span style={{ fontWeight: 500 }}>Series {series.seriesNumber}</span>
                          <span className="badge">{series.modality}</span>
                        </div>
                        <div style={{ fontSize: '0.85rem', opacity: 0.8, marginTop: '0.25rem' }}>
                          {series.seriesDescription || 'No Description'}
                        </div>
                        <div style={{ fontSize: '0.8rem', opacity: 0.6, marginTop: '0.25rem' }}>
                          {series.numberOfInstances} images
                          {series.bodyPartExamined && ` - ${series.bodyPartExamined}`}
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </div>

              {/* Right Panel - Image Viewer */}
              <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
                {!selectedSeries ? (
                  <div style={{
                    flex: 1,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    opacity: 0.6
                  }}>
                    Select a series to view images
                  </div>
                ) : loadingImages ? (
                  <div style={{
                    flex: 1,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    opacity: 0.6
                  }}>
                    Loading images...
                  </div>
                ) : imageList.length === 0 ? (
                  <div style={{
                    flex: 1,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    opacity: 0.6
                  }}>
                    No images found
                  </div>
                ) : (
                  <>
                    {/* Image Display */}
                    <div style={{
                      flex: 1,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      background: '#1a1a1a',
                      overflow: 'hidden',
                      position: 'relative'
                    }}>
                      <img
                        src={getImagePreviewUrl(imageList[currentImageIndex].sopInstanceUID)}
                        alt={`Image ${imageList[currentImageIndex].instanceNumber}`}
                        style={{
                          maxWidth: '100%',
                          maxHeight: '100%',
                          objectFit: 'contain'
                        }}
                        onError={(e) => {
                          (e.target as HTMLImageElement).style.display = 'none'
                        }}
                      />
                      <div style={{
                        position: 'absolute',
                        top: '1rem',
                        left: '1rem',
                        color: 'white',
                        background: 'rgba(0,0,0,0.5)',
                        padding: '0.25rem 0.5rem',
                        borderRadius: '4px',
                        fontSize: '0.85rem'
                      }}>
                        Image {currentImageIndex + 1} / {imageList.length}
                      </div>
                      {imageList[currentImageIndex].sliceLocation !== 0 && (
                        <div style={{
                          position: 'absolute',
                          top: '1rem',
                          right: '1rem',
                          color: 'white',
                          background: 'rgba(0,0,0,0.5)',
                          padding: '0.25rem 0.5rem',
                          borderRadius: '4px',
                          fontSize: '0.85rem'
                        }}>
                          Slice: {(imageList[currentImageIndex].sliceLocation ?? 0).toFixed(2)}mm
                        </div>
                      )}
                    </div>

                    {/* Image Navigation */}
                    <div style={{
                      padding: '1rem',
                      borderTop: '1px solid var(--border-color)',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '1rem'
                    }}>
                      <button
                        className="btn-secondary"
                        onClick={handlePrevImage}
                        disabled={currentImageIndex === 0}
                      >
                        Previous
                      </button>
                      <input
                        type="range"
                        min={0}
                        max={imageList.length - 1}
                        value={currentImageIndex}
                        onChange={(e) => setCurrentImageIndex(parseInt(e.target.value))}
                        style={{ flex: 1 }}
                      />
                      <button
                        className="btn-secondary"
                        onClick={handleNextImage}
                        disabled={currentImageIndex === imageList.length - 1}
                      >
                        Next
                      </button>
                    </div>
                  </>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
