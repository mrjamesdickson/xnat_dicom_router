/**
 * DICOM Review Page
 *
 * Side-by-side comparison of original vs anonymized DICOM studies.
 * Features:
 * - Image comparison with OCR overlay
 * - DICOM header diff view
 * - Scan/instance navigation
 * - Review actions (mark reviewed, flag issue, test send)
 */

import { useState, useEffect, useMemo } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import {
  useStudyComparison,
  useScanComparisons,
  fetchHeaderComparison,
  HeaderComparison,
  ScanComparison,
  FileComparison
} from '../hooks/useCompare'
import ImageCompare from '../components/ImageCompare'
import HeaderDiff from '../components/HeaderDiff'

type Tab = 'image' | 'header'

export default function DicomReview() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()

  // URL parameters
  const aeTitle = searchParams.get('aeTitle') || ''
  const studyUid = searchParams.get('studyUid') || ''

  // State
  const [activeTab, setActiveTab] = useState<Tab>('image')
  const [selectedScanIndex, setSelectedScanIndex] = useState(0)
  const [selectedFileIndex, setSelectedFileIndex] = useState(0)
  const [headerComparison, setHeaderComparison] = useState<HeaderComparison | null>(null)
  const [headerLoading, setHeaderLoading] = useState(false)
  const [headerError, setHeaderError] = useState<string | null>(null)

  // Fetch study and scans
  const { data: study, loading: studyLoading, error: studyError } = useStudyComparison(aeTitle, studyUid)
  const { data: scansData, loading: scansLoading, error: scansError } = useScanComparisons(aeTitle, studyUid)

  const scans = scansData?.scans || []
  const selectedScan: ScanComparison | undefined = scans[selectedScanIndex]
  const files = selectedScan?.files || []
  const selectedFile: FileComparison | undefined = files[selectedFileIndex]

  // Reset file selection when scan changes
  useEffect(() => {
    setSelectedFileIndex(0)
  }, [selectedScanIndex])

  // Load header comparison when file changes
  useEffect(() => {
    if (!selectedFile?.originalFile || !selectedFile?.anonymizedFile) {
      setHeaderComparison(null)
      return
    }

    const loadHeaders = async () => {
      setHeaderLoading(true)
      setHeaderError(null)
      try {
        const comparison = await fetchHeaderComparison(
          selectedFile.originalFile,
          selectedFile.anonymizedFile
        )
        setHeaderComparison(comparison)
      } catch (err) {
        setHeaderError(err instanceof Error ? err.message : 'Failed to load headers')
        setHeaderComparison(null)
      } finally {
        setHeaderLoading(false)
      }
    }
    loadHeaders()
  }, [selectedFile])

  // Navigation helpers
  const prevFile = () => {
    if (selectedFileIndex > 0) {
      setSelectedFileIndex(selectedFileIndex - 1)
    } else if (selectedScanIndex > 0) {
      setSelectedScanIndex(selectedScanIndex - 1)
      const prevScan = scans[selectedScanIndex - 1]
      setSelectedFileIndex((prevScan?.files?.length || 1) - 1)
    }
  }

  const nextFile = () => {
    if (selectedFileIndex < files.length - 1) {
      setSelectedFileIndex(selectedFileIndex + 1)
    } else if (selectedScanIndex < scans.length - 1) {
      setSelectedScanIndex(selectedScanIndex + 1)
      setSelectedFileIndex(0)
    }
  }

  const hasPrev = selectedFileIndex > 0 || selectedScanIndex > 0
  const hasNext = selectedFileIndex < files.length - 1 || selectedScanIndex < scans.length - 1

  // Loading state
  if (studyLoading || scansLoading) {
    return (
      <div style={pageStyle}>
        <div style={{ padding: '4rem', textAlign: 'center' }}>
          <div className="spinner" />
          <p style={{ marginTop: '1rem', color: '#7f8c8d' }}>Loading study...</p>
        </div>
      </div>
    )
  }

  // Error state
  if (studyError || scansError || !aeTitle || !studyUid) {
    return (
      <div style={pageStyle}>
        <div style={{ padding: '4rem', textAlign: 'center' }}>
          <h2 style={{ color: '#e74c3c' }}>Error Loading Study</h2>
          <p style={{ color: '#7f8c8d' }}>
            {studyError || scansError || 'Missing aeTitle or studyUid parameters'}
          </p>
          <button
            onClick={() => navigate('/storage')}
            style={{
              marginTop: '1rem',
              padding: '0.5rem 1rem',
              background: '#3498db',
              color: 'white',
              border: 'none',
              borderRadius: '4px',
              cursor: 'pointer'
            }}
          >
            Back to Storage
          </button>
        </div>
      </div>
    )
  }

  return (
    <div style={pageStyle}>
      {/* Study info header */}
      <div style={studyHeaderStyle}>
        <div>
          <strong>Study:</strong> {study?.studyUid || studyUid}
          <span style={{ margin: '0 1rem' }}>|</span>
          <strong>Patient:</strong> {study?.patientName || 'N/A'} ({study?.patientId || 'N/A'})
          <span style={{ margin: '0 1rem' }}>|</span>
          <strong>Date:</strong> {study?.studyDate || 'N/A'}
        </div>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button style={backButtonStyle} onClick={() => navigate('/storage')}>
            Back
          </button>
        </div>
      </div>

      {/* Scan/instance navigation */}
      <div style={navigationStyle}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <span style={{ fontWeight: 500, color: '#7f8c8d' }}>Scan:</span>
            <select
              value={selectedScanIndex}
              onChange={(e) => setSelectedScanIndex(Number(e.target.value))}
              style={selectStyle}
            >
              {scans.map((scan, idx) => (
                <option key={scan.seriesUid} value={idx}>
                  {idx + 1}. {scan.seriesDescription || scan.modality || 'Series'} ({scan.instanceCount} files)
                </option>
              ))}
            </select>
          </label>

          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <button
              onClick={prevFile}
              disabled={!hasPrev}
              style={navButtonStyle}
            >
              Prev
            </button>
            <span style={{ minWidth: '80px', textAlign: 'center' }}>
              Instance {selectedFileIndex + 1} / {files.length}
            </span>
            <button
              onClick={nextFile}
              disabled={!hasNext}
              style={navButtonStyle}
            >
              Next
            </button>
          </div>
        </div>

        {/* Tab switcher */}
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button
            onClick={() => setActiveTab('image')}
            style={{
              ...tabButtonStyle,
              background: activeTab === 'image' ? '#3498db' : '#ecf0f1',
              color: activeTab === 'image' ? 'white' : '#2c3e50'
            }}
          >
            Image
          </button>
          <button
            onClick={() => setActiveTab('header')}
            style={{
              ...tabButtonStyle,
              background: activeTab === 'header' ? '#3498db' : '#ecf0f1',
              color: activeTab === 'header' ? 'white' : '#2c3e50'
            }}
          >
            Header
          </button>
        </div>
      </div>

      {/* Main content area */}
      <div style={contentStyle}>
        {activeTab === 'image' && selectedFile && (
          <ImageCompare
            originalPath={selectedFile.originalFile}
            anonymizedPath={selectedFile.anonymizedFile}
            showOverlay={true}
          />
        )}

        {activeTab === 'header' && (
          <HeaderDiff
            comparison={headerComparison}
            loading={headerLoading}
            error={headerError}
          />
        )}

        {!selectedFile && (
          <div style={{ padding: '2rem', textAlign: 'center', color: '#7f8c8d' }}>
            No files available for comparison
          </div>
        )}
      </div>

      {/* Actions footer */}
      <div style={actionsStyle}>
        <div style={{ display: 'flex', gap: '1rem' }}>
          <span style={{ color: '#7f8c8d', fontSize: '0.85rem' }}>
            {study?.scanCount || 0} scans, {study?.fileCount || 0} files
            {study?.phiFieldsModified > 0 && (
              <span style={{ marginLeft: '1rem', color: '#27ae60' }}>
                {study.phiFieldsModified} PHI fields modified
              </span>
            )}
          </span>
        </div>
        <div style={{ display: 'flex', gap: '0.5rem' }}>
          <button style={actionButtonStyle('#27ae60')}>
            Mark Reviewed
          </button>
          <button style={actionButtonStyle('#e67e22')}>
            Flag Issue
          </button>
          <button style={actionButtonStyle('#3498db')}>
            Test Send
          </button>
        </div>
      </div>
    </div>
  )
}

// Styles
const pageStyle: React.CSSProperties = {
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
  background: '#f5f7fa'
}

const studyHeaderStyle: React.CSSProperties = {
  padding: '0.75rem 1rem',
  background: '#2c3e50',
  color: 'white',
  fontSize: '0.9rem',
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center'
}

const navigationStyle: React.CSSProperties = {
  padding: '0.75rem 1rem',
  background: 'white',
  borderBottom: '1px solid #ddd',
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  flexWrap: 'wrap',
  gap: '0.5rem'
}

const contentStyle: React.CSSProperties = {
  flex: 1,
  overflow: 'hidden',
  display: 'flex',
  flexDirection: 'column',
  background: 'white',
  margin: '1rem',
  borderRadius: '8px',
  boxShadow: '0 1px 3px rgba(0,0,0,0.1)'
}

const actionsStyle: React.CSSProperties = {
  padding: '0.75rem 1rem',
  background: 'white',
  borderTop: '1px solid #ddd',
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center'
}

const selectStyle: React.CSSProperties = {
  padding: '0.35rem 0.5rem',
  borderRadius: '4px',
  border: '1px solid #ddd',
  minWidth: '200px'
}

const navButtonStyle: React.CSSProperties = {
  padding: '0.35rem 0.75rem',
  background: '#ecf0f1',
  border: '1px solid #bdc3c7',
  borderRadius: '4px',
  cursor: 'pointer'
}

const tabButtonStyle: React.CSSProperties = {
  padding: '0.35rem 1rem',
  border: 'none',
  borderRadius: '4px',
  cursor: 'pointer',
  fontWeight: 500
}

const backButtonStyle: React.CSSProperties = {
  padding: '0.35rem 0.75rem',
  background: 'rgba(255,255,255,0.2)',
  color: 'white',
  border: '1px solid rgba(255,255,255,0.3)',
  borderRadius: '4px',
  cursor: 'pointer'
}

const actionButtonStyle = (color: string): React.CSSProperties => ({
  padding: '0.5rem 1rem',
  background: color,
  color: 'white',
  border: 'none',
  borderRadius: '4px',
  cursor: 'pointer',
  fontWeight: 500
})
