import { useState, useEffect } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { apiGet, apiFetchImageUrl } from '../hooks/useApi'

interface DicomHeader {
  path: string
  filename: string
  patient: {
    name: string
    id: string
    birthDate: string
    sex: string
    age: string
  }
  study: {
    instanceUID: string
    id: string
    date: string
    time: string
    description: string
    accessionNumber: string
  }
  series: {
    instanceUID: string
    number: string
    description: string
    modality: string
    bodyPart: string
  }
  instance: {
    sopInstanceUID: string
    sopClassUID: string
    instanceNumber: string
  }
  image: {
    rows: number
    columns: number
    bitsAllocated: number
    bitsStored: number
    photometricInterpretation: string
    samplesPerPixel: number
    pixelSpacing: string[] | null
    sliceThickness: string
  }
  equipment: {
    manufacturer: string
    institutionName: string
    stationName: string
    modelName: string
  }
  transferSyntaxUID: string
  allTags: Array<{
    tag: string
    vr: string
    keyword: string
    value: string
  }>
}

type TabType = 'image' | 'header' | 'tags'

export default function DicomViewer() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const filePath = searchParams.get('path') || ''

  const [activeTab, setActiveTab] = useState<TabType>('image')
  const [dicomHeader, setDicomHeader] = useState<DicomHeader | null>(null)
  const [imageUrl, setImageUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [tagFilter, setTagFilter] = useState('')

  useEffect(() => {
    if (!filePath) {
      setError('No file path provided')
      setLoading(false)
      return
    }

    setLoading(true)
    setError(null)

    Promise.all([
      apiGet<DicomHeader>(`/storage/dicom/header?path=${encodeURIComponent(filePath)}`),
      apiFetchImageUrl(`/storage/dicom/image?path=${encodeURIComponent(filePath)}`)
    ])
      .then(([header, url]) => {
        setDicomHeader(header)
        setImageUrl(url)
        setLoading(false)
      })
      .catch(err => {
        setError(err instanceof Error ? err.message : 'Failed to load DICOM')
        setLoading(false)
      })

    return () => {
      if (imageUrl) {
        URL.revokeObjectURL(imageUrl)
      }
    }
  }, [filePath])

  const handleBack = () => {
    navigate(-1)
  }

  if (loading) {
    return (
      <div>
        <div className="card">
          <div className="loading"><div className="spinner" /></div>
        </div>
      </div>
    )
  }

  if (error) {
    return (
      <div>
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">DICOM Viewer</h2>
            <button className="btn btn-secondary" onClick={handleBack}>
              ← Back
            </button>
          </div>
          <div className="error-message">{error}</div>
        </div>
      </div>
    )
  }

  if (!dicomHeader) {
    return (
      <div>
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">DICOM Viewer</h2>
            <button className="btn btn-secondary" onClick={handleBack}>
              ← Back
            </button>
          </div>
          <p>No DICOM data available</p>
        </div>
      </div>
    )
  }

  const filename = filePath.split('/').pop() || filePath

  return (
    <div>
      <div className="card">
        <div className="card-header">
          <div>
            <h2 className="card-title">DICOM Viewer</h2>
            <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.875rem', color: 'var(--text-light)' }}>
              {filename}
            </p>
          </div>
          <button className="btn btn-secondary" onClick={handleBack}>
            ← Back
          </button>
        </div>

        {/* Tabs */}
        <div style={{
          display: 'flex',
          gap: '0',
          borderBottom: '1px solid var(--border-color)',
          marginBottom: '1.5rem'
        }}>
          <button
            onClick={() => setActiveTab('image')}
            style={{
              padding: '0.75rem 1.5rem',
              border: 'none',
              background: activeTab === 'image' ? 'var(--bg-secondary)' : 'transparent',
              color: activeTab === 'image' ? 'var(--primary-color)' : 'var(--text-light)',
              borderBottom: activeTab === 'image' ? '2px solid var(--primary-color)' : '2px solid transparent',
              cursor: 'pointer',
              fontWeight: activeTab === 'image' ? '600' : '400',
              fontSize: '0.9rem'
            }}
          >
            Image
          </button>
          <button
            onClick={() => setActiveTab('header')}
            style={{
              padding: '0.75rem 1.5rem',
              border: 'none',
              background: activeTab === 'header' ? 'var(--bg-secondary)' : 'transparent',
              color: activeTab === 'header' ? 'var(--primary-color)' : 'var(--text-light)',
              borderBottom: activeTab === 'header' ? '2px solid var(--primary-color)' : '2px solid transparent',
              cursor: 'pointer',
              fontWeight: activeTab === 'header' ? '600' : '400',
              fontSize: '0.9rem'
            }}
          >
            Header
          </button>
          <button
            onClick={() => setActiveTab('tags')}
            style={{
              padding: '0.75rem 1.5rem',
              border: 'none',
              background: activeTab === 'tags' ? 'var(--bg-secondary)' : 'transparent',
              color: activeTab === 'tags' ? 'var(--primary-color)' : 'var(--text-light)',
              borderBottom: activeTab === 'tags' ? '2px solid var(--primary-color)' : '2px solid transparent',
              cursor: 'pointer',
              fontWeight: activeTab === 'tags' ? '600' : '400',
              fontSize: '0.9rem'
            }}
          >
            All Tags ({dicomHeader.allTags.length})
          </button>
        </div>

        {/* Image Tab */}
        {activeTab === 'image' && (
          <div>
            <div style={{
              display: 'grid',
              gridTemplateColumns: '2fr 1fr',
              gap: '2rem'
            }}>
              {/* Image */}
              <div style={{
                backgroundColor: '#000',
                borderRadius: '8px',
                padding: '1rem',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                minHeight: '500px'
              }}>
                {imageUrl ? (
                  <img
                    src={imageUrl}
                    alt="DICOM Preview"
                    style={{
                      maxWidth: '100%',
                      maxHeight: '600px',
                      objectFit: 'contain'
                    }}
                  />
                ) : (
                  <p style={{ color: '#666' }}>Image not available</p>
                )}
              </div>

              {/* Image Properties */}
              <div>
                <h3 style={{ marginBottom: '1rem' }}>Image Properties</h3>
                <div className="table-container">
                  <table>
                    <tbody>
                      <tr>
                        <td><strong>Dimensions</strong></td>
                        <td>{dicomHeader.image.columns} × {dicomHeader.image.rows}</td>
                      </tr>
                      <tr>
                        <td><strong>Bits Allocated</strong></td>
                        <td>{dicomHeader.image.bitsAllocated}</td>
                      </tr>
                      <tr>
                        <td><strong>Bits Stored</strong></td>
                        <td>{dicomHeader.image.bitsStored}</td>
                      </tr>
                      <tr>
                        <td><strong>Photometric</strong></td>
                        <td>{dicomHeader.image.photometricInterpretation || '-'}</td>
                      </tr>
                      <tr>
                        <td><strong>Samples/Pixel</strong></td>
                        <td>{dicomHeader.image.samplesPerPixel}</td>
                      </tr>
                      <tr>
                        <td><strong>Pixel Spacing</strong></td>
                        <td>{dicomHeader.image.pixelSpacing?.join(' × ') || '-'}</td>
                      </tr>
                      <tr>
                        <td><strong>Slice Thickness</strong></td>
                        <td>{dicomHeader.image.sliceThickness || '-'}</td>
                      </tr>
                      <tr>
                        <td><strong>Transfer Syntax</strong></td>
                        <td style={{ fontSize: '0.75rem', wordBreak: 'break-all' }}>
                          {dicomHeader.transferSyntaxUID || '-'}
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>

                <h3 style={{ marginTop: '1.5rem', marginBottom: '1rem' }}>Quick Info</h3>
                <div className="table-container">
                  <table>
                    <tbody>
                      <tr>
                        <td><strong>Patient</strong></td>
                        <td>{dicomHeader.patient.name || '-'}</td>
                      </tr>
                      <tr>
                        <td><strong>Patient ID</strong></td>
                        <td>{dicomHeader.patient.id || '-'}</td>
                      </tr>
                      <tr>
                        <td><strong>Modality</strong></td>
                        <td>{dicomHeader.series.modality || '-'}</td>
                      </tr>
                      <tr>
                        <td><strong>Study Date</strong></td>
                        <td>{dicomHeader.study.date || '-'}</td>
                      </tr>
                      <tr>
                        <td><strong>Series</strong></td>
                        <td>{dicomHeader.series.description || '-'}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Header Tab */}
        {activeTab === 'header' && (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '1.5rem' }}>
            {/* Patient */}
            <div className="card" style={{ margin: 0 }}>
              <h3 style={{ marginBottom: '1rem', color: 'var(--primary-color)' }}>Patient</h3>
              <div className="table-container">
                <table>
                  <tbody>
                    <tr><td><strong>Name</strong></td><td>{dicomHeader.patient.name || '-'}</td></tr>
                    <tr><td><strong>ID</strong></td><td>{dicomHeader.patient.id || '-'}</td></tr>
                    <tr><td><strong>Birth Date</strong></td><td>{dicomHeader.patient.birthDate || '-'}</td></tr>
                    <tr><td><strong>Sex</strong></td><td>{dicomHeader.patient.sex || '-'}</td></tr>
                    <tr><td><strong>Age</strong></td><td>{dicomHeader.patient.age || '-'}</td></tr>
                  </tbody>
                </table>
              </div>
            </div>

            {/* Study */}
            <div className="card" style={{ margin: 0 }}>
              <h3 style={{ marginBottom: '1rem', color: 'var(--primary-color)' }}>Study</h3>
              <div className="table-container">
                <table>
                  <tbody>
                    <tr><td><strong>Date</strong></td><td>{dicomHeader.study.date || '-'}</td></tr>
                    <tr><td><strong>Time</strong></td><td>{dicomHeader.study.time || '-'}</td></tr>
                    <tr><td><strong>Description</strong></td><td>{dicomHeader.study.description || '-'}</td></tr>
                    <tr><td><strong>Accession #</strong></td><td>{dicomHeader.study.accessionNumber || '-'}</td></tr>
                    <tr><td><strong>Study ID</strong></td><td>{dicomHeader.study.id || '-'}</td></tr>
                    <tr>
                      <td><strong>Study UID</strong></td>
                      <td style={{ fontSize: '0.75rem', wordBreak: 'break-all' }}>
                        {dicomHeader.study.instanceUID || '-'}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            {/* Series */}
            <div className="card" style={{ margin: 0 }}>
              <h3 style={{ marginBottom: '1rem', color: 'var(--primary-color)' }}>Series</h3>
              <div className="table-container">
                <table>
                  <tbody>
                    <tr><td><strong>Number</strong></td><td>{dicomHeader.series.number || '-'}</td></tr>
                    <tr><td><strong>Modality</strong></td><td>{dicomHeader.series.modality || '-'}</td></tr>
                    <tr><td><strong>Description</strong></td><td>{dicomHeader.series.description || '-'}</td></tr>
                    <tr><td><strong>Body Part</strong></td><td>{dicomHeader.series.bodyPart || '-'}</td></tr>
                    <tr>
                      <td><strong>Series UID</strong></td>
                      <td style={{ fontSize: '0.75rem', wordBreak: 'break-all' }}>
                        {dicomHeader.series.instanceUID || '-'}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            {/* Equipment */}
            <div className="card" style={{ margin: 0 }}>
              <h3 style={{ marginBottom: '1rem', color: 'var(--primary-color)' }}>Equipment</h3>
              <div className="table-container">
                <table>
                  <tbody>
                    <tr><td><strong>Manufacturer</strong></td><td>{dicomHeader.equipment.manufacturer || '-'}</td></tr>
                    <tr><td><strong>Model</strong></td><td>{dicomHeader.equipment.modelName || '-'}</td></tr>
                    <tr><td><strong>Institution</strong></td><td>{dicomHeader.equipment.institutionName || '-'}</td></tr>
                    <tr><td><strong>Station</strong></td><td>{dicomHeader.equipment.stationName || '-'}</td></tr>
                  </tbody>
                </table>
              </div>
            </div>

            {/* Instance */}
            <div className="card" style={{ margin: 0 }}>
              <h3 style={{ marginBottom: '1rem', color: 'var(--primary-color)' }}>Instance</h3>
              <div className="table-container">
                <table>
                  <tbody>
                    <tr><td><strong>Instance #</strong></td><td>{dicomHeader.instance.instanceNumber || '-'}</td></tr>
                    <tr>
                      <td><strong>SOP Instance UID</strong></td>
                      <td style={{ fontSize: '0.75rem', wordBreak: 'break-all' }}>
                        {dicomHeader.instance.sopInstanceUID || '-'}
                      </td>
                    </tr>
                    <tr>
                      <td><strong>SOP Class UID</strong></td>
                      <td style={{ fontSize: '0.75rem', wordBreak: 'break-all' }}>
                        {dicomHeader.instance.sopClassUID || '-'}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            {/* Image Properties */}
            <div className="card" style={{ margin: 0 }}>
              <h3 style={{ marginBottom: '1rem', color: 'var(--primary-color)' }}>Image</h3>
              <div className="table-container">
                <table>
                  <tbody>
                    <tr><td><strong>Dimensions</strong></td><td>{dicomHeader.image.columns} × {dicomHeader.image.rows}</td></tr>
                    <tr><td><strong>Bits Stored</strong></td><td>{dicomHeader.image.bitsStored}</td></tr>
                    <tr><td><strong>Photometric</strong></td><td>{dicomHeader.image.photometricInterpretation || '-'}</td></tr>
                    <tr><td><strong>Samples/Pixel</strong></td><td>{dicomHeader.image.samplesPerPixel}</td></tr>
                    <tr><td><strong>Slice Thickness</strong></td><td>{dicomHeader.image.sliceThickness || '-'}</td></tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}

        {/* All Tags Tab */}
        {activeTab === 'tags' && (
          <div>
            <div style={{ marginBottom: '1rem' }}>
              <input
                type="text"
                placeholder="Filter tags by tag, keyword, or value..."
                value={tagFilter}
                onChange={e => setTagFilter(e.target.value)}
                style={{
                  padding: '0.5rem 1rem',
                  width: '400px',
                  maxWidth: '100%'
                }}
              />
            </div>
            <div className="table-container" style={{ maxHeight: '600px', overflow: 'auto' }}>
              <table>
                <thead style={{ position: 'sticky', top: 0, background: 'var(--bg-secondary)' }}>
                  <tr>
                    <th style={{ width: '120px' }}>Tag</th>
                    <th style={{ width: '60px' }}>VR</th>
                    <th style={{ width: '200px' }}>Keyword</th>
                    <th>Value</th>
                  </tr>
                </thead>
                <tbody>
                  {dicomHeader.allTags
                    .filter(tag =>
                      !tagFilter ||
                      tag.tag.toLowerCase().includes(tagFilter.toLowerCase()) ||
                      tag.keyword.toLowerCase().includes(tagFilter.toLowerCase()) ||
                      tag.value.toLowerCase().includes(tagFilter.toLowerCase())
                    )
                    .map((tag, i) => (
                      <tr key={i}>
                        <td><code style={{ color: 'var(--primary-color)' }}>{tag.tag}</code></td>
                        <td>{tag.vr}</td>
                        <td>{tag.keyword}</td>
                        <td style={{
                          maxWidth: '500px',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap'
                        }}>
                          {tag.value}
                        </td>
                      </tr>
                    ))
                  }
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
