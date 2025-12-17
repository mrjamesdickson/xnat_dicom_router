import { useState, useEffect, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { apiGet } from '../hooks/useApi'

interface IndexedStudy {
  id: number
  studyUid: string
  patientId: string
  patientName: string
  patientSex: string
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

interface FieldCount {
  value: string
  count: number
}

interface Aggregations {
  patientSex: FieldCount[]
  modalities: FieldCount[]
  institutionName: FieldCount[]
  sourceRoute: FieldCount[]
  referringPhysician: FieldCount[]
  modality: FieldCount[]
  bodyPart: FieldCount[]
  _total?: FieldCount[]
}

interface ActiveFilter {
  field: string
  value: string
  label: string
}

export default function Search() {
  const navigate = useNavigate()

  // Selected studies for batch Q/R
  const [selectedStudies, setSelectedStudies] = useState<Set<string>>(new Set())

  // Search filters state
  const [filters, setFilters] = useState({
    patientId: '',
    patientName: '',
    patientSex: '',
    studyDateFrom: '',
    studyDateTo: '',
    modality: '',
    accessionNumber: '',
    institutionName: '',
    studyDescription: '',
    sourceRoute: '',
    bodyPart: '',
  })

  const [searchResults, setSearchResults] = useState<SearchResult | null>(null)
  const [searching, setSearching] = useState(false)
  const [selectedStudy, setSelectedStudy] = useState<StudyDetail | null>(null)
  const [aggregations, setAggregations] = useState<Aggregations | null>(null)
  const [loadingAggregations, setLoadingAggregations] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [totalCount, setTotalCount] = useState<number>(0)

  // Pagination state
  const [currentPage, setCurrentPage] = useState(1)
  const pageSize = 50

  // Get active filters for display
  const getActiveFilters = useCallback((): ActiveFilter[] => {
    const active: ActiveFilter[] = []
    const fieldLabels: Record<string, string> = {
      patientId: 'Patient ID',
      patientName: 'Patient Name',
      patientSex: 'Sex',
      studyDateFrom: 'Date From',
      studyDateTo: 'Date To',
      modality: 'Modality',
      accessionNumber: 'Accession',
      institutionName: 'Institution',
      studyDescription: 'Description',
      sourceRoute: 'Source',
      bodyPart: 'Body Part',
    }

    Object.entries(filters).forEach(([field, value]) => {
      if (value) {
        active.push({
          field,
          value,
          label: fieldLabels[field] || field
        })
      }
    })
    return active
  }, [filters])

  // Fetch aggregations with current filters
  const fetchAggregations = useCallback(async () => {
    setLoadingAggregations(true)
    try {
      const params = new URLSearchParams()
      Object.entries(filters).forEach(([key, value]) => {
        if (value) params.append(key, value)
      })

      const data = await apiGet<Aggregations>(`/search/stats/aggregations?${params.toString()}`)
      setAggregations(data)

      // Extract total count from _total aggregation
      if (data._total && data._total.length > 0) {
        setTotalCount(data._total[0].count)
      }
    } catch (error) {
      console.error('Failed to fetch aggregations:', error)
    } finally {
      setLoadingAggregations(false)
    }
  }, [filters])

  // Fetch aggregations on mount and when filters change
  useEffect(() => {
    fetchAggregations()
  }, [fetchAggregations])

  const formatBytes = (bytes: number) => {
    if (!bytes) return '0 B'
    if (bytes < 1024) return bytes + ' B'
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
    if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
    return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
  }

  const formatDate = (dateStr: string) => {
    if (!dateStr) return '-'
    if (dateStr.length === 8) {
      return `${dateStr.slice(0, 4)}-${dateStr.slice(4, 6)}-${dateStr.slice(6, 8)}`
    }
    return dateStr
  }

  const handleSearch = useCallback(async () => {
    setSearching(true)
    try {
      const params = new URLSearchParams()
      Object.entries(filters).forEach(([key, value]) => {
        if (value) params.append(key, value)
      })
      params.append('limit', '1000')

      const data = await apiGet<SearchResult>(`/search/studies?${params.toString()}`)
      setSearchResults(data)
      setCurrentPage(1)  // Reset to first page on new search
    } catch (error) {
      console.error('Search failed:', error)
    } finally {
      setSearching(false)
    }
  }, [filters])

  // Pagination calculations
  const totalPages = searchResults ? Math.ceil(searchResults.studies.length / pageSize) : 0
  const startIndex = (currentPage - 1) * pageSize
  const endIndex = startIndex + pageSize
  const paginatedStudies = searchResults?.studies.slice(startIndex, endIndex) || []

  const handleViewStudy = async (studyUid: string) => {
    try {
      const data = await apiGet<StudyDetail>(`/search/studies/${encodeURIComponent(studyUid)}`)
      setSelectedStudy(data)
    } catch (error) {
      console.error('Failed to load study details:', error)
    }
  }

  // Apply filter from clicking on a chart
  const applyChartFilter = (field: string, value: string) => {
    setFilters(prev => ({
      ...prev,
      [field]: prev[field as keyof typeof prev] === value ? '' : value  // Toggle filter
    }))
  }

  // Remove a specific filter
  const removeFilter = (field: string) => {
    setFilters(prev => ({
      ...prev,
      [field]: ''
    }))
  }

  // Clear all filters
  const clearAllFilters = () => {
    setFilters({
      patientId: '',
      patientName: '',
      patientSex: '',
      studyDateFrom: '',
      studyDateTo: '',
      modality: '',
      accessionNumber: '',
      institutionName: '',
      studyDescription: '',
      sourceRoute: '',
      bodyPart: '',
    })
    setSearchResults(null)
  }

  const handleExportCsv = async () => {
    setExporting(true)
    try {
      const params = new URLSearchParams()
      Object.entries(filters).forEach(([key, value]) => {
        if (value) params.append(key, value)
      })
      params.append('limit', '10000')

      const response = await fetch(`/api/search/export/csv?${params.toString()}`, {
        headers: {
          'Authorization': `Basic ${btoa('admin:admin123')}`
        }
      })
      if (!response.ok) throw new Error('Export failed')

      const blob = await response.blob()
      const url = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `dicom_studies_${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(a)
      a.click()
      window.URL.revokeObjectURL(url)
      document.body.removeChild(a)
    } catch (error) {
      console.error('Export failed:', error)
      alert('Failed to export CSV')
    } finally {
      setExporting(false)
    }
  }

  // Selection handlers for batch Q/R
  const handleToggleStudy = (studyUid: string) => {
    const newSelected = new Set(selectedStudies)
    if (newSelected.has(studyUid)) {
      newSelected.delete(studyUid)
    } else {
      newSelected.add(studyUid)
    }
    setSelectedStudies(newSelected)
  }

  const handleSelectAll = () => {
    if (!searchResults) return
    if (selectedStudies.size === searchResults.studies.length) {
      setSelectedStudies(new Set())
    } else {
      setSelectedStudies(new Set(searchResults.studies.map(s => s.studyUid)))
    }
  }

  const handleQueryRetrieveSelected = () => {
    if (selectedStudies.size === 0) return

    // Store study UIDs in sessionStorage for the Q/R page
    sessionStorage.setItem('qr_bulk_uids', Array.from(selectedStudies).join('\n'))
    sessionStorage.setItem('qr_bulk_type', 'studyInstanceUID')

    // Navigate to Query/Retrieve page
    navigate('/query-retrieve?mode=bulk')
  }

  // Color palette for charts
  const chartColors = [
    '#3182ce', '#38a169', '#dd6b20', '#805ad5', '#d53f8c',
    '#319795', '#d69e2e', '#e53e3e', '#667eea', '#48bb78'
  ]

  // Pie chart component
  const PieChart = ({ data, title, filterField, currentFilter }: {
    data: FieldCount[]
    title: string
    filterField: string
    currentFilter?: string
  }) => {
    // If there's an active filter but no data, show the filter as the only item
    const displayData = (data && data.length > 0) ? data :
      (currentFilter ? [{ value: currentFilter, count: totalCount }] : [])
    const hasData = displayData.length > 0
    const total = hasData ? displayData.reduce((sum, d) => sum + d.count, 0) : 0
    const size = 140
    const centerX = size / 2
    const centerY = size / 2
    const radius = 55

    let currentAngle = -Math.PI / 2

    return (
      <div style={{ textAlign: 'center', minWidth: '160px' }}>
        <h4 style={{ marginBottom: '0.5rem', fontSize: '0.85rem', color: 'var(--text-color)' }}>{title}</h4>
        {!hasData ? (
          <div style={{ width: size, height: size, display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto' }}>
            <svg width={size} height={size}>
              <circle cx={centerX} cy={centerY} r={radius} fill="var(--border-color)" opacity={0.5} />
              <text x={centerX} y={centerY} textAnchor="middle" dominantBaseline="middle" fill="var(--text-light)" fontSize="12">No data</text>
            </svg>
          </div>
        ) : (
        <svg width={size} height={size} style={{ display: 'block', margin: '0 auto' }}>
          {displayData.slice(0, 8).map((item, i) => {
            const sliceAngle = (item.count / total) * 2 * Math.PI
            const startAngle = currentAngle
            const endAngle = currentAngle + sliceAngle
            currentAngle = endAngle

            const x1 = centerX + radius * Math.cos(startAngle)
            const y1 = centerY + radius * Math.sin(startAngle)
            const x2 = centerX + radius * Math.cos(endAngle)
            const y2 = centerY + radius * Math.sin(endAngle)

            const largeArcFlag = sliceAngle > Math.PI ? 1 : 0
            const pathD = `M ${centerX} ${centerY} L ${x1} ${y1} A ${radius} ${radius} 0 ${largeArcFlag} 1 ${x2} ${y2} Z`

            const isSelected = currentFilter === item.value

            return (
              <path
                key={item.value || 'empty'}
                d={pathD}
                fill={chartColors[i % chartColors.length]}
                stroke={isSelected ? '#000' : 'white'}
                strokeWidth={isSelected ? 3 : 1}
                style={{ cursor: 'pointer', opacity: isSelected ? 1 : 0.85 }}
                onClick={() => applyChartFilter(filterField, item.value)}
              >
                <title>{`${item.value || '(empty)'}: ${item.count.toLocaleString()} (${((item.count / total) * 100).toFixed(1)}%)`}</title>
              </path>
            )
          })}
        </svg>
        )}
        {hasData && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px', justifyContent: 'center', marginTop: '0.5rem', maxWidth: '180px', margin: '0.5rem auto 0' }}>
          {displayData.slice(0, 5).map((item, i) => {
            const isSelected = currentFilter === item.value
            return (
              <span
                key={item.value || 'empty'}
                style={{
                  fontSize: '0.65rem',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '2px',
                  cursor: 'pointer',
                  padding: '2px 6px',
                  borderRadius: '3px',
                  background: isSelected ? 'var(--secondary-color)' : 'var(--bg-alt, #f8f9fa)',
                  color: isSelected ? 'white' : 'var(--text-color)',
                  fontWeight: isSelected ? 600 : 400
                }}
                onClick={() => applyChartFilter(filterField, item.value)}
              >
                <span style={{ width: 8, height: 8, background: chartColors[i % chartColors.length], borderRadius: '50%', flexShrink: 0 }} />
                {(item.value || '(empty)').slice(0, 12)}{item.value && item.value.length > 12 ? '...' : ''}
              </span>
            )
          })}
        </div>
        )}
      </div>
    )
  }

  // Bar chart component
  const BarChart = ({ data, title, filterField, currentFilter, maxBars = 8 }: {
    data: FieldCount[]
    title: string
    filterField: string
    currentFilter?: string
    maxBars?: number
  }) => {
    // If there's an active filter but no data, show the filter as the only item
    const rawData = (data && data.length > 0) ? data :
      (currentFilter ? [{ value: currentFilter, count: totalCount }] : [])
    const hasData = rawData.length > 0
    const displayData = hasData ? rawData.slice(0, maxBars) : []
    const maxCount = hasData ? Math.max(...displayData.map(d => d.count), 1) : 1

    return (
      <div style={{ minWidth: '200px' }}>
        <h4 style={{ marginBottom: '0.5rem', fontSize: '0.85rem', color: 'var(--text-color)' }}>{title}</h4>
        {!hasData ? (
          <div style={{ padding: '2rem 1rem', textAlign: 'center', color: 'var(--text-light)', background: 'var(--border-color)', borderRadius: '4px', opacity: 0.5 }}>
            No data
          </div>
        ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
          {displayData.map((item, i) => {
            const isSelected = currentFilter === item.value
            return (
              <div
                key={item.value || 'empty'}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  cursor: 'pointer',
                  padding: '2px 4px',
                  borderRadius: '4px',
                  background: isSelected ? 'rgba(52, 152, 219, 0.1)' : 'transparent',
                  border: isSelected ? '1px solid var(--secondary-color)' : '1px solid transparent'
                }}
                onClick={() => applyChartFilter(filterField, item.value)}
              >
                <span style={{
                  width: '70px',
                  fontSize: '0.7rem',
                  textOverflow: 'ellipsis',
                  overflow: 'hidden',
                  whiteSpace: 'nowrap',
                  textAlign: 'right',
                  color: isSelected ? 'var(--secondary-color)' : 'var(--text-light)',
                  fontWeight: isSelected ? 600 : 400
                }} title={item.value || '(empty)'}>
                  {(item.value || '(empty)').slice(0, 12)}
                </span>
                <div style={{ flex: 1, height: '16px', background: 'var(--border-color)', borderRadius: '2px', overflow: 'hidden' }}>
                  <div style={{
                    width: `${(item.count / maxCount) * 100}%`,
                    height: '100%',
                    background: isSelected ? 'var(--secondary-color)' : chartColors[i % chartColors.length],
                    borderRadius: '2px',
                    transition: 'width 0.2s'
                  }} />
                </div>
                <span style={{
                  fontSize: '0.7rem',
                  minWidth: '45px',
                  color: isSelected ? 'var(--secondary-color)' : 'var(--text-light)',
                  fontWeight: isSelected ? 600 : 400
                }}>
                  {item.count.toLocaleString()}
                </span>
              </div>
            )
          })}
        </div>
        )}
      </div>
    )
  }

  const activeFilters = getActiveFilters()

  return (
    <div>
      {/* Active Filters & Stats Header */}
      <div className="card" style={{ marginBottom: '1rem' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '1rem' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
            <div style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--secondary-color)' }}>
              {loadingAggregations ? '...' : totalCount.toLocaleString()}
            </div>
            <div style={{ color: 'var(--text-light)' }}>matching studies</div>

            {activeFilters.length > 0 && (
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap', marginLeft: '1rem', paddingLeft: '1rem', borderLeft: '1px solid var(--border-color)' }}>
                <span style={{ fontSize: '0.8rem', color: 'var(--text-light)' }}>Filters:</span>
                {activeFilters.map(filter => (
                  <span
                    key={filter.field}
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: '4px',
                      padding: '2px 8px',
                      background: 'var(--secondary-color)',
                      color: 'white',
                      borderRadius: '12px',
                      fontSize: '0.75rem'
                    }}
                  >
                    <strong>{filter.label}:</strong> {filter.value}
                    <button
                      onClick={() => removeFilter(filter.field)}
                      style={{
                        background: 'none',
                        border: 'none',
                        color: 'white',
                        cursor: 'pointer',
                        padding: '0 2px',
                        fontSize: '1rem',
                        lineHeight: 1,
                        opacity: 0.8
                      }}
                    >
                      x
                    </button>
                  </span>
                ))}
                <button
                  onClick={clearAllFilters}
                  style={{
                    background: 'none',
                    border: 'none',
                    color: 'var(--danger-color)',
                    cursor: 'pointer',
                    fontSize: '0.75rem',
                    textDecoration: 'underline'
                  }}
                >
                  Clear all
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Interactive Charts Dashboard */}
      {aggregations && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Data Overview</h2>
            <span style={{ fontSize: '0.85rem', color: 'var(--text-light)' }}>
              Click any segment to filter
            </span>
          </div>

          <div style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
            gap: '2rem',
            padding: '1rem 0'
          }}>
            {/* Modality Pie Chart */}
            <PieChart
              data={aggregations.modality || []}
              title="Modality (Series)"
              filterField="modality"
              currentFilter={filters.modality}
            />

            {/* Patient Sex Pie Chart */}
            <PieChart
              data={aggregations.patientSex || []}
              title="Patient Sex"
              filterField="patientSex"
              currentFilter={filters.patientSex}
            />

            {/* Body Part Bar Chart */}
            <BarChart
              data={aggregations.bodyPart || []}
              title="Body Part"
              filterField="bodyPart"
              currentFilter={filters.bodyPart}
              maxBars={6}
            />

            {/* Institution Bar Chart */}
            <BarChart
              data={aggregations.institutionName || []}
              title="Institution"
              filterField="institutionName"
              currentFilter={filters.institutionName}
              maxBars={6}
            />

            {/* Source Route Bar Chart */}
            <BarChart
              data={aggregations.sourceRoute || []}
              title="Source Route"
              filterField="sourceRoute"
              currentFilter={filters.sourceRoute}
              maxBars={5}
            />

            {/* Study Modalities Bar Chart */}
            <BarChart
              data={aggregations.modalities || []}
              title="Modalities (Studies)"
              filterField="modality"
              currentFilter={filters.modality}
              maxBars={6}
            />
          </div>
        </div>
      )}

      {/* Search Form */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Search Studies</h2>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: '1rem' }}>
          <div className="form-group">
            <label>Patient ID</label>
            <input
              type="text"
              value={filters.patientId}
              onChange={(e) => setFilters({ ...filters, patientId: e.target.value })}
              placeholder="Search patient ID..."
            />
          </div>
          <div className="form-group">
            <label>Patient Name</label>
            <input
              type="text"
              value={filters.patientName}
              onChange={(e) => setFilters({ ...filters, patientName: e.target.value })}
              placeholder="Search patient name..."
            />
          </div>
          <div className="form-group">
            <label>Patient Sex</label>
            <select
              value={filters.patientSex}
              onChange={(e) => setFilters({ ...filters, patientSex: e.target.value })}
            >
              <option value="">All</option>
              <option value="M">Male</option>
              <option value="F">Female</option>
              <option value="O">Other</option>
            </select>
          </div>
          <div className="form-group">
            <label>Study Date From</label>
            <input
              type="date"
              value={filters.studyDateFrom}
              onChange={(e) => setFilters({ ...filters, studyDateFrom: e.target.value.replace(/-/g, '') })}
            />
          </div>
          <div className="form-group">
            <label>Study Date To</label>
            <input
              type="date"
              value={filters.studyDateTo}
              onChange={(e) => setFilters({ ...filters, studyDateTo: e.target.value.replace(/-/g, '') })}
            />
          </div>
          <div className="form-group">
            <label>Modality</label>
            <select
              value={filters.modality}
              onChange={(e) => setFilters({ ...filters, modality: e.target.value })}
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
              value={filters.accessionNumber}
              onChange={(e) => setFilters({ ...filters, accessionNumber: e.target.value })}
              placeholder="Search accession..."
            />
          </div>
          <div className="form-group">
            <label>Institution</label>
            <input
              type="text"
              value={filters.institutionName}
              onChange={(e) => setFilters({ ...filters, institutionName: e.target.value })}
              placeholder="Search institution..."
            />
          </div>
          <div className="form-group">
            <label>Description</label>
            <input
              type="text"
              value={filters.studyDescription}
              onChange={(e) => setFilters({ ...filters, studyDescription: e.target.value })}
              placeholder="Search description..."
            />
          </div>
          <div className="form-group">
            <label>Source Route</label>
            <input
              type="text"
              value={filters.sourceRoute}
              onChange={(e) => setFilters({ ...filters, sourceRoute: e.target.value })}
              placeholder="Search source..."
            />
          </div>
          <div className="form-group">
            <label>Body Part</label>
            <input
              type="text"
              value={filters.bodyPart}
              onChange={(e) => setFilters({ ...filters, bodyPart: e.target.value })}
              placeholder="Search body part..."
            />
          </div>
        </div>

        <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
          <button className="btn btn-primary" onClick={handleSearch} disabled={searching}>
            {searching ? 'Searching...' : 'Search'}
          </button>
          <button className="btn btn-secondary" onClick={clearAllFilters}>
            Clear
          </button>
        </div>
      </div>

      {/* Search Results */}
      {searchResults && (
        <div className="card">
          <div className="card-header">
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
              <h2 className="card-title">Results ({searchResults.count.toLocaleString()})</h2>
              {searchResults.studies.length > 0 && (
                <span style={{ fontSize: '0.85rem', color: 'var(--text-light)' }}>
                  {selectedStudies.size > 0 && `${selectedStudies.size} selected`}
                </span>
              )}
            </div>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              {searchResults.studies.length > 0 && (
                <>
                  <button
                    className="btn btn-sm btn-secondary"
                    onClick={handleSelectAll}
                  >
                    {selectedStudies.size === searchResults.studies.length ? 'Deselect All' : 'Select All'}
                  </button>
                  <button
                    className="btn btn-sm btn-primary"
                    onClick={handleQueryRetrieveSelected}
                    disabled={selectedStudies.size === 0}
                    title="Query remote PACS for selected studies"
                  >
                    Q/R Selected ({selectedStudies.size})
                  </button>
                </>
              )}
              <button
                className="btn btn-sm btn-secondary"
                onClick={handleExportCsv}
                disabled={exporting || searchResults.count === 0}
              >
                {exporting ? 'Exporting...' : 'Export CSV'}
              </button>
            </div>
          </div>

          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th style={{ width: '40px' }}>
                    <input
                      type="checkbox"
                      checked={searchResults.studies.length > 0 && selectedStudies.size === searchResults.studies.length}
                      onChange={handleSelectAll}
                      title="Select all"
                    />
                  </th>
                  <th>Patient ID</th>
                  <th>Patient Name</th>
                  <th>Sex</th>
                  <th>Study Date</th>
                  <th>Modalities</th>
                  <th>Description</th>
                  <th>Source</th>
                  <th>Series</th>
                  <th>Images</th>
                  <th>Size</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {paginatedStudies.map(study => (
                  <tr
                    key={study.studyUid}
                    style={{
                      background: selectedStudies.has(study.studyUid) ? 'rgba(52, 152, 219, 0.08)' : undefined
                    }}
                  >
                    <td>
                      <input
                        type="checkbox"
                        checked={selectedStudies.has(study.studyUid)}
                        onChange={() => handleToggleStudy(study.studyUid)}
                      />
                    </td>
                    <td>{study.patientId || '-'}</td>
                    <td>{study.patientName || '-'}</td>
                    <td>{study.patientSex || '-'}</td>
                    <td>{formatDate(study.studyDate)}</td>
                    <td>
                      <span className="status-badge status-up">{study.modalities || '-'}</span>
                    </td>
                    <td style={{ maxWidth: '200px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={study.studyDescription}>
                      {study.studyDescription || '-'}
                    </td>
                    <td>
                      {study.sourceRoute ? (
                        <span
                          style={{
                            cursor: 'pointer',
                            color: 'var(--secondary-color)',
                            textDecoration: 'underline'
                          }}
                          onClick={() => applyChartFilter('sourceRoute', study.sourceRoute)}
                        >
                          {study.sourceRoute}
                        </span>
                      ) : '-'}
                    </td>
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
                {paginatedStudies.length === 0 && (
                  <tr>
                    <td colSpan={12} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                      No studies found matching your criteria
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {/* Pagination Controls */}
          {totalPages > 1 && (
            <div style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginTop: '1rem',
              padding: '0.75rem 0',
              borderTop: '1px solid var(--border-color)'
            }}>
              <div style={{ color: 'var(--text-light)', fontSize: '0.9rem' }}>
                Showing {startIndex + 1}-{Math.min(endIndex, searchResults.studies.length)} of {searchResults.studies.length} studies
              </div>
              <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                <button
                  className="btn btn-sm btn-secondary"
                  onClick={() => setCurrentPage(1)}
                  disabled={currentPage === 1}
                  title="First page"
                >
                  ««
                </button>
                <button
                  className="btn btn-sm btn-secondary"
                  onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                  disabled={currentPage === 1}
                >
                  Previous
                </button>
                <span style={{
                  padding: '0.25rem 0.75rem',
                  background: 'var(--bg-alt, #f8f9fa)',
                  borderRadius: '4px',
                  fontSize: '0.9rem'
                }}>
                  Page {currentPage} of {totalPages}
                </span>
                <button
                  className="btn btn-sm btn-secondary"
                  onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                  disabled={currentPage === totalPages}
                >
                  Next
                </button>
                <button
                  className="btn btn-sm btn-secondary"
                  onClick={() => setCurrentPage(totalPages)}
                  disabled={currentPage === totalPages}
                  title="Last page"
                >
                  »»
                </button>
              </div>
            </div>
          )}
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
                  <strong>Patient Sex:</strong> {selectedStudy.study.patientSex || '-'}
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
                <div>
                  <strong>Source Route:</strong> {selectedStudy.study.sourceRoute || '-'}
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
