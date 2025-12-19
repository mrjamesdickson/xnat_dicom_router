/**
 * Image Compare Component
 *
 * Displays side-by-side DICOM images with OCR overlay for comparing
 * original and anonymized versions.
 */

import { useState, useEffect, useRef, useCallback } from 'react'
import { fetchImageUrl, fetchOcrRegions, fetchImageInfo, OcrRegion, ImageInfo } from '../hooks/useCompare'
import OcrOverlay from './OcrOverlay'

interface ImageCompareProps {
  originalPath: string
  anonymizedPath: string
  showOverlay?: boolean
}

interface ImageState {
  url: string | null
  loading: boolean
  error: string | null
  info: ImageInfo | null
  ocrRegions: OcrRegion[]
}

export default function ImageCompare({
  originalPath,
  anonymizedPath,
  showOverlay = true
}: ImageCompareProps) {
  const [original, setOriginal] = useState<ImageState>({
    url: null, loading: true, error: null, info: null, ocrRegions: []
  })
  const [anonymized, setAnonymized] = useState<ImageState>({
    url: null, loading: true, error: null, info: null, ocrRegions: []
  })
  const [frame, setFrame] = useState(0)
  const [overlayEnabled, setOverlayEnabled] = useState(showOverlay)

  const originalContainerRef = useRef<HTMLDivElement>(null)
  const anonymizedContainerRef = useRef<HTMLDivElement>(null)
  const [containerSize, setContainerSize] = useState({ width: 400, height: 400 })

  // Load image and OCR data
  const loadImage = useCallback(async (
    path: string,
    setState: React.Dispatch<React.SetStateAction<ImageState>>,
    frameNum: number
  ) => {
    if (!path) {
      setState(prev => ({ ...prev, loading: false, error: 'No file path' }))
      return
    }

    setState(prev => ({ ...prev, loading: true, error: null }))

    try {
      // Load image info first
      const info = await fetchImageInfo(path)

      // Load image with overlay baked in (optional) or without
      const url = await fetchImageUrl(path, false, frameNum)

      // Load OCR regions separately for interactive overlay
      let ocrRegions: OcrRegion[] = []
      try {
        const ocrResult = await fetchOcrRegions(path)
        ocrRegions = ocrResult.regions || []
      } catch (e) {
        console.warn('Failed to load OCR regions:', e)
      }

      setState({
        url,
        loading: false,
        error: null,
        info,
        ocrRegions
      })
    } catch (err) {
      setState(prev => ({
        ...prev,
        loading: false,
        error: err instanceof Error ? err.message : 'Failed to load image'
      }))
    }
  }, [])

  // Load images when paths change
  useEffect(() => {
    loadImage(originalPath, setOriginal, frame)
  }, [originalPath, frame, loadImage])

  useEffect(() => {
    loadImage(anonymizedPath, setAnonymized, frame)
  }, [anonymizedPath, frame, loadImage])

  // Measure container size
  useEffect(() => {
    const updateSize = () => {
      if (originalContainerRef.current) {
        const rect = originalContainerRef.current.getBoundingClientRect()
        setContainerSize({ width: rect.width, height: rect.height })
      }
    }
    updateSize()
    window.addEventListener('resize', updateSize)
    return () => window.removeEventListener('resize', updateSize)
  }, [])

  // Clean up blob URLs
  useEffect(() => {
    return () => {
      if (original.url) URL.revokeObjectURL(original.url)
      if (anonymized.url) URL.revokeObjectURL(anonymized.url)
    }
  }, [original.url, anonymized.url])

  const maxFrames = Math.max(original.info?.frames || 1, anonymized.info?.frames || 1)

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Controls bar */}
      <div style={{
        display: 'flex',
        gap: '1rem',
        padding: '0.5rem 1rem',
        background: '#f8f9fa',
        borderBottom: '1px solid #eee',
        alignItems: 'center'
      }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
          <input
            type="checkbox"
            checked={overlayEnabled}
            onChange={(e) => setOverlayEnabled(e.target.checked)}
          />
          <span style={{ fontSize: '0.85rem' }}>Show OCR Overlay</span>
        </label>

        {maxFrames > 1 && (
          <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <span style={{ fontSize: '0.85rem', color: '#7f8c8d' }}>Frame:</span>
            <button
              onClick={() => setFrame(f => Math.max(0, f - 1))}
              disabled={frame === 0}
              style={navButtonStyle}
            >
              -
            </button>
            <span style={{ fontSize: '0.85rem', minWidth: '60px', textAlign: 'center' }}>
              {frame + 1} / {maxFrames}
            </span>
            <button
              onClick={() => setFrame(f => Math.min(maxFrames - 1, f + 1))}
              disabled={frame >= maxFrames - 1}
              style={navButtonStyle}
            >
              +
            </button>
          </div>
        )}

        <div style={{ flex: 1 }} />

        <span style={{ fontSize: '0.8rem', color: '#7f8c8d' }}>
          Original: {original.info ? `${original.info.width}x${original.info.height}` : '-'}
          {' | '}
          Anonymized: {anonymized.info ? `${anonymized.info.width}x${anonymized.info.height}` : '-'}
        </span>
      </div>

      {/* Images side by side */}
      <div style={{ flex: 1, display: 'flex', gap: '1rem', padding: '1rem', overflow: 'hidden' }}>
        {/* Original */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          <div style={{
            padding: '0.5rem',
            background: '#2c3e50',
            color: 'white',
            fontWeight: 600,
            textAlign: 'center',
            borderRadius: '4px 4px 0 0'
          }}>
            ORIGINAL
            {original.ocrRegions.length > 0 && (
              <span style={{ marginLeft: '0.5rem', opacity: 0.8, fontSize: '0.85rem' }}>
                ({original.ocrRegions.filter(r => r.isPhi).length} PHI detected)
              </span>
            )}
          </div>
          <div
            ref={originalContainerRef}
            style={{
              flex: 1,
              background: '#000',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              position: 'relative',
              borderRadius: '0 0 4px 4px',
              overflow: 'hidden',
              minHeight: '300px'
            }}
          >
            {original.loading && (
              <div style={{ color: '#fff' }}>
                <div className="spinner" style={{ borderColor: '#fff', borderTopColor: 'transparent' }} />
              </div>
            )}
            {original.error && (
              <div style={{ color: '#e74c3c', textAlign: 'center', padding: '1rem' }}>
                {original.error}
              </div>
            )}
            {original.url && !original.loading && (
              <>
                <img
                  src={original.url}
                  alt="Original DICOM"
                  style={{
                    maxWidth: '100%',
                    maxHeight: '100%',
                    objectFit: 'contain'
                  }}
                />
                {overlayEnabled && original.info && (
                  <OcrOverlay
                    regions={original.ocrRegions}
                    imageWidth={original.info.width}
                    imageHeight={original.info.height}
                    containerWidth={containerSize.width}
                    containerHeight={containerSize.height}
                  />
                )}
              </>
            )}
          </div>
        </div>

        {/* Anonymized */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          <div style={{
            padding: '0.5rem',
            background: '#27ae60',
            color: 'white',
            fontWeight: 600,
            textAlign: 'center',
            borderRadius: '4px 4px 0 0'
          }}>
            ANONYMIZED
            {anonymized.ocrRegions.length > 0 && (
              <span style={{ marginLeft: '0.5rem', opacity: 0.8, fontSize: '0.85rem' }}>
                ({anonymized.ocrRegions.filter(r => r.isPhi).length} PHI detected)
              </span>
            )}
          </div>
          <div
            ref={anonymizedContainerRef}
            style={{
              flex: 1,
              background: '#000',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              position: 'relative',
              borderRadius: '0 0 4px 4px',
              overflow: 'hidden',
              minHeight: '300px'
            }}
          >
            {anonymized.loading && (
              <div style={{ color: '#fff' }}>
                <div className="spinner" style={{ borderColor: '#fff', borderTopColor: 'transparent' }} />
              </div>
            )}
            {anonymized.error && (
              <div style={{ color: '#e74c3c', textAlign: 'center', padding: '1rem' }}>
                {anonymized.error}
              </div>
            )}
            {anonymized.url && !anonymized.loading && (
              <>
                <img
                  src={anonymized.url}
                  alt="Anonymized DICOM"
                  style={{
                    maxWidth: '100%',
                    maxHeight: '100%',
                    objectFit: 'contain'
                  }}
                />
                {overlayEnabled && anonymized.info && (
                  <OcrOverlay
                    regions={anonymized.ocrRegions}
                    imageWidth={anonymized.info.width}
                    imageHeight={anonymized.info.height}
                    containerWidth={containerSize.width}
                    containerHeight={containerSize.height}
                  />
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

const navButtonStyle: React.CSSProperties = {
  padding: '0.25rem 0.75rem',
  background: '#3498db',
  color: 'white',
  border: 'none',
  borderRadius: '4px',
  cursor: 'pointer',
  fontSize: '1rem'
}
