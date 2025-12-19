/**
 * OCR Overlay Component
 *
 * Renders SVG bounding boxes over DICOM images to highlight detected text regions.
 * PHI regions are shown in red, non-PHI in yellow.
 */

import { useState } from 'react'
import { OcrRegion } from '../hooks/useCompare'

interface OcrOverlayProps {
  regions: OcrRegion[]
  imageWidth: number
  imageHeight: number
  containerWidth: number
  containerHeight: number
}

export default function OcrOverlay({
  regions,
  imageWidth,
  imageHeight,
  containerWidth,
  containerHeight
}: OcrOverlayProps) {
  const [hoveredRegion, setHoveredRegion] = useState<OcrRegion | null>(null)

  if (!regions || regions.length === 0) {
    return null
  }

  // Calculate scale factor to map from image coordinates to container coordinates
  const scaleX = containerWidth / imageWidth
  const scaleY = containerHeight / imageHeight
  const scale = Math.min(scaleX, scaleY)

  // Calculate offset to center the image
  const scaledWidth = imageWidth * scale
  const scaledHeight = imageHeight * scale
  const offsetX = (containerWidth - scaledWidth) / 2
  const offsetY = (containerHeight - scaledHeight) / 2

  return (
    <svg
      width={containerWidth}
      height={containerHeight}
      style={{
        position: 'absolute',
        top: 0,
        left: 0,
        pointerEvents: 'none'
      }}
    >
      {regions.map((region, index) => {
        const x = offsetX + region.x * scale
        const y = offsetY + region.y * scale
        const width = region.width * scale
        const height = region.height * scale

        const strokeColor = region.isPhi ? '#ff4444' : '#ffcc00'
        const fillColor = region.isPhi ? 'rgba(255, 68, 68, 0.1)' : 'rgba(255, 204, 0, 0.1)'

        return (
          <g key={index}>
            {/* Bounding box */}
            <rect
              x={x}
              y={y}
              width={width}
              height={height}
              fill={fillColor}
              stroke={strokeColor}
              strokeWidth={2}
              style={{ pointerEvents: 'all', cursor: 'pointer' }}
              onMouseEnter={() => setHoveredRegion(region)}
              onMouseLeave={() => setHoveredRegion(null)}
            />

            {/* Label above box */}
            {region.isPhi && (
              <g>
                <rect
                  x={x}
                  y={y - 18}
                  width={30}
                  height={16}
                  fill="#ff4444"
                  rx={2}
                />
                <text
                  x={x + 15}
                  y={y - 6}
                  fill="white"
                  fontSize={10}
                  fontWeight="bold"
                  textAnchor="middle"
                >
                  PHI
                </text>
              </g>
            )}
          </g>
        )
      })}

      {/* Tooltip for hovered region */}
      {hoveredRegion && (
        <g>
          <rect
            x={10}
            y={containerHeight - 60}
            width={Math.min(containerWidth - 20, 300)}
            height={50}
            fill="rgba(0, 0, 0, 0.85)"
            rx={4}
          />
          <text
            x={20}
            y={containerHeight - 40}
            fill="white"
            fontSize={12}
          >
            {hoveredRegion.isPhi ? 'PHI Detected: ' : 'Text: '}
            {hoveredRegion.text.length > 40
              ? hoveredRegion.text.substring(0, 40) + '...'
              : hoveredRegion.text}
          </text>
          <text
            x={20}
            y={containerHeight - 22}
            fill="#aaa"
            fontSize={10}
          >
            Confidence: {hoveredRegion.confidence.toFixed(1)}%
            {' | '}
            Position: ({hoveredRegion.x}, {hoveredRegion.y})
            {' | '}
            Size: {hoveredRegion.width}x{hoveredRegion.height}
          </text>
        </g>
      )}

      {/* Legend */}
      <g>
        <rect x={10} y={10} width={12} height={12} fill="rgba(255, 68, 68, 0.3)" stroke="#ff4444" strokeWidth={2} />
        <text x={28} y={20} fill="#333" fontSize={11}>PHI Detected</text>

        <rect x={110} y={10} width={12} height={12} fill="rgba(255, 204, 0, 0.3)" stroke="#ffcc00" strokeWidth={2} />
        <text x={128} y={20} fill="#333" fontSize={11}>Text Detected</text>
      </g>
    </svg>
  )
}
