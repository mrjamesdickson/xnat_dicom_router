import { useState } from 'react'
import { useFetch, apiPost } from '../hooks/useApi'

interface OcrStatus {
  available: boolean
  tessDataPath: string
  message: string
}

interface DetectedRegion {
  x: number
  y: number
  width: number
  height: number
  text: string
  confidence: number
  isPhi: boolean
}

interface ScanResult {
  sourceName: string
  imageWidth: number
  imageHeight: number
  totalRegions: number
  phiRegionCount: number
  regions: DetectedRegion[]
  alterPixelScript?: string
  error?: string
}

interface DirectoryScanResult {
  path: string
  filesScanned: number
  totalFiles: number
  totalPhiRegions: number
  results: Array<{
    file: string
    phiRegionCount: number
    regions?: DetectedRegion[]
    script?: string
    error?: string
  }>
}

export default function OCR() {
  const { data: status, loading: statusLoading, error: statusError } = useFetch<OcrStatus>('/ocr/status')
  const [filePath, setFilePath] = useState('')
  const [dirPath, setDirPath] = useState('')
  const [maxFiles, setMaxFiles] = useState(10)
  const [scanning, setScanning] = useState(false)
  const [scanResult, setScanResult] = useState<ScanResult | null>(null)
  const [dirResult, setDirResult] = useState<DirectoryScanResult | null>(null)
  const [activeTab, setActiveTab] = useState<'file' | 'directory'>('file')
  const [showScript, setShowScript] = useState(false)

  const handleScanFile = async () => {
    if (!filePath.trim()) {
      alert('Please enter a file path')
      return
    }
    setScanning(true)
    setScanResult(null)
    try {
      // Determine if it's DICOM or regular image
      const isDicom = filePath.toLowerCase().endsWith('.dcm') || !filePath.includes('.')
      const endpoint = isDicom ? '/ocr/scan' : '/ocr/scan-image'
      const result = await apiPost<ScanResult>(endpoint, { path: filePath })
      setScanResult(result)
    } catch (err) {
      alert('Scan failed: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setScanning(false)
    }
  }

  const handleScanDirectory = async () => {
    if (!dirPath.trim()) {
      alert('Please enter a directory path')
      return
    }
    setScanning(true)
    setDirResult(null)
    try {
      const result = await apiPost<DirectoryScanResult>('/ocr/scan-directory', {
        path: dirPath,
        maxFiles: maxFiles
      })
      setDirResult(result)
    } catch (err) {
      alert('Scan failed: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setScanning(false)
    }
  }

  if (statusLoading) {
    return <div className="loading"><div className="spinner" /></div>
  }

  return (
    <div>
      {/* Status Card */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">OCR PHI Detection</h2>
          <span className={`status-badge ${status?.available ? 'status-active' : 'status-inactive'}`}>
            {status?.available ? 'Available' : 'Unavailable'}
          </span>
        </div>

        {statusError && (
          <div className="error-message" style={{ marginBottom: '1rem' }}>
            Failed to check OCR status: {statusError}
          </div>
        )}

        {status && (
          <div style={{ marginBottom: '1rem' }}>
            <p><strong>Status:</strong> {status.message}</p>
            <p><strong>Tessdata Path:</strong> <code>{status.tessDataPath}</code></p>
          </div>
        )}

        {!status?.available && (
          <div className="info-box" style={{ background: '#fff3cd', padding: '1rem', borderRadius: '6px', marginBottom: '1rem' }}>
            <strong>OCR Not Available</strong>
            <p style={{ margin: '0.5rem 0 0' }}>
              Tesseract OCR is not properly configured. Ensure:
            </p>
            <ul style={{ margin: '0.5rem 0 0', paddingLeft: '1.5rem' }}>
              <li>Tesseract is installed (brew install tesseract)</li>
              <li>tessdata directory exists with eng.traineddata</li>
              <li>JNA library path is set: -Djna.library.path=/usr/local/lib</li>
            </ul>
          </div>
        )}
      </div>

      {/* Scan Card */}
      {status?.available && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Scan for PHI</h2>
          </div>

          {/* Tab Navigation */}
          <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem' }}>
            <button
              className={`btn ${activeTab === 'file' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => { setActiveTab('file'); setDirResult(null) }}
            >
              Single File
            </button>
            <button
              className={`btn ${activeTab === 'directory' ? 'btn-primary' : 'btn-secondary'}`}
              onClick={() => { setActiveTab('directory'); setScanResult(null) }}
            >
              Directory
            </button>
          </div>

          {activeTab === 'file' && (
            <>
              <div className="form-group">
                <label className="form-label">File Path</label>
                <input
                  type="text"
                  className="form-input"
                  value={filePath}
                  onChange={e => setFilePath(e.target.value)}
                  placeholder="/path/to/dicom/file.dcm or /path/to/image.png"
                />
                <small style={{ color: 'var(--text-light)', display: 'block', marginTop: '0.25rem' }}>
                  Supports DICOM files (.dcm) and images (.png, .jpg)
                </small>
              </div>

              <button
                className="btn btn-primary"
                onClick={handleScanFile}
                disabled={scanning || !filePath.trim()}
              >
                {scanning ? 'Scanning...' : 'Scan File'}
              </button>
            </>
          )}

          {activeTab === 'directory' && (
            <>
              <div className="form-group">
                <label className="form-label">Directory Path</label>
                <input
                  type="text"
                  className="form-input"
                  value={dirPath}
                  onChange={e => setDirPath(e.target.value)}
                  placeholder="/path/to/dicom/folder"
                />
              </div>

              <div className="form-group">
                <label className="form-label">Max Files to Scan</label>
                <input
                  type="number"
                  className="form-input"
                  value={maxFiles}
                  onChange={e => setMaxFiles(parseInt(e.target.value) || 10)}
                  min={1}
                  max={100}
                  style={{ width: '100px' }}
                />
              </div>

              <button
                className="btn btn-primary"
                onClick={handleScanDirectory}
                disabled={scanning || !dirPath.trim()}
              >
                {scanning ? 'Scanning...' : 'Scan Directory'}
              </button>
            </>
          )}
        </div>
      )}

      {/* Single File Results */}
      {scanResult && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Scan Results: {scanResult.sourceName}</h2>
            {scanResult.alterPixelScript && (
              <button
                className="btn btn-secondary"
                onClick={() => setShowScript(!showScript)}
              >
                {showScript ? 'Hide Script' : 'Show AlterPixel Script'}
              </button>
            )}
          </div>

          {scanResult.error && (
            <div className="error-message" style={{ marginBottom: '1rem' }}>
              Error: {scanResult.error}
            </div>
          )}

          {/* Summary Stats */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
            <div className="stat-box" style={{ background: 'var(--bg-color)', padding: '1rem', borderRadius: '6px', textAlign: 'center' }}>
              <div style={{ fontSize: '2rem', fontWeight: 'bold', color: 'var(--primary-color)' }}>
                {scanResult.totalRegions}
              </div>
              <div style={{ color: 'var(--text-light)', fontSize: '0.875rem' }}>Total Regions</div>
            </div>
            <div className="stat-box" style={{ background: scanResult.phiRegionCount > 0 ? '#fff3cd' : 'var(--bg-color)', padding: '1rem', borderRadius: '6px', textAlign: 'center' }}>
              <div style={{ fontSize: '2rem', fontWeight: 'bold', color: scanResult.phiRegionCount > 0 ? '#856404' : 'var(--primary-color)' }}>
                {scanResult.phiRegionCount}
              </div>
              <div style={{ color: 'var(--text-light)', fontSize: '0.875rem' }}>PHI Detected</div>
            </div>
            <div className="stat-box" style={{ background: 'var(--bg-color)', padding: '1rem', borderRadius: '6px', textAlign: 'center' }}>
              <div style={{ fontSize: '1.5rem', fontWeight: 'bold', color: 'var(--primary-color)' }}>
                {scanResult.imageWidth}x{scanResult.imageHeight}
              </div>
              <div style={{ color: 'var(--text-light)', fontSize: '0.875rem' }}>Image Size</div>
            </div>
          </div>

          {/* AlterPixel Script */}
          {showScript && scanResult.alterPixelScript && (
            <div style={{ marginBottom: '1.5rem' }}>
              <h3>AlterPixel Script</h3>
              <pre style={{
                background: '#1e1e1e',
                color: '#d4d4d4',
                padding: '1rem',
                borderRadius: '6px',
                overflow: 'auto',
                fontSize: '0.875rem',
                fontFamily: 'monospace',
                whiteSpace: 'pre-wrap'
              }}>
                {scanResult.alterPixelScript}
              </pre>
              <button
                className="btn btn-secondary"
                onClick={() => {
                  navigator.clipboard.writeText(scanResult.alterPixelScript || '')
                  alert('Script copied to clipboard!')
                }}
                style={{ marginTop: '0.5rem' }}
              >
                Copy to Clipboard
              </button>
            </div>
          )}

          {/* Detected Regions Table */}
          {scanResult.regions.length > 0 && (
            <>
              <h3>Detected Text Regions</h3>
              <div className="table-container">
                <table>
                  <thead>
                    <tr>
                      <th>Text</th>
                      <th>Position</th>
                      <th>Size</th>
                      <th>Confidence</th>
                      <th>PHI</th>
                    </tr>
                  </thead>
                  <tbody>
                    {scanResult.regions.map((region, i) => (
                      <tr key={i} style={{ background: region.isPhi ? '#fff3cd' : undefined }}>
                        <td><code>{region.text}</code></td>
                        <td>({region.x}, {region.y})</td>
                        <td>{region.width}x{region.height}</td>
                        <td>{region.confidence.toFixed(1)}%</td>
                        <td>
                          <span className={`status-badge ${region.isPhi ? 'status-warning' : 'status-active'}`}>
                            {region.isPhi ? 'PHI' : 'Clean'}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      )}

      {/* Directory Results */}
      {dirResult && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Directory Scan Results</h2>
          </div>

          <div style={{ marginBottom: '1rem' }}>
            <p><strong>Path:</strong> {dirResult.path}</p>
            <p><strong>Files Scanned:</strong> {dirResult.filesScanned} of {dirResult.totalFiles}</p>
            <p><strong>Total PHI Regions Found:</strong> <span style={{ color: dirResult.totalPhiRegions > 0 ? '#856404' : 'inherit', fontWeight: 'bold' }}>{dirResult.totalPhiRegions}</span></p>
          </div>

          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>File</th>
                  <th>PHI Regions</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {dirResult.results.map((result, i) => (
                  <tr key={i} style={{ background: result.phiRegionCount > 0 ? '#fff3cd' : undefined }}>
                    <td>{result.file}</td>
                    <td>{result.phiRegionCount || 0}</td>
                    <td>
                      {result.error ? (
                        <span className="status-badge status-inactive">{result.error}</span>
                      ) : result.phiRegionCount > 0 ? (
                        <span className="status-badge status-warning">PHI Found</span>
                      ) : (
                        <span className="status-badge status-active">Clean</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Usage Info */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">About OCR PHI Detection</h2>
        </div>
        <p>
          This tool uses Tesseract OCR to detect text burned into DICOM images and classifies
          potential Protected Health Information (PHI) using pattern matching.
        </p>
        <h4>Detected PHI Patterns:</h4>
        <ul>
          <li><strong>Patient Names:</strong> Multi-word capitalized sequences, "Patient:" prefix</li>
          <li><strong>Dates:</strong> MM/DD/YYYY, DD-Mon-YYYY, and similar formats</li>
          <li><strong>MRN/IDs:</strong> Numeric sequences of 6+ digits</li>
          <li><strong>SSN:</strong> XXX-XX-XXXX format</li>
          <li><strong>Phone:</strong> (XXX) XXX-XXXX and similar formats</li>
          <li><strong>Accession Numbers:</strong> ACC/ACN followed by numbers</li>
          <li><strong>Institutions:</strong> Hospital, Medical Center, Clinic, etc.</li>
          <li><strong>DOB/Age/Sex:</strong> Labels followed by values</li>
        </ul>
        <h4>Generated AlterPixel Script</h4>
        <p>
          When PHI is detected, the system generates AlterPixel commands compatible with
          DicomEdit scripts. These commands can be added to your anonymization scripts to
          black out the detected regions during processing.
        </p>
      </div>
    </div>
  )
}
