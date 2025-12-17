import { useMemo } from 'react'

interface DataPoint {
  timestamp: number
  timestampIso: string
  transfers: number
  successful: number
  failed: number
  bytes: number
  files: number
}

interface ChartProps {
  data: DataPoint[]
  width?: number
  height?: number
  type?: 'transfers' | 'bytes'
  title?: string
}

export function Chart({ data, width = 600, height = 200, type = 'transfers', title }: ChartProps) {
  const chartData = useMemo(() => {
    if (!data || data.length === 0) {
      return { points: [], maxValue: 0, labels: [] }
    }

    // Extract values based on type
    const values = data.map(d => type === 'transfers' ? d.transfers : d.bytes)
    const successValues = type === 'transfers' ? data.map(d => d.successful) : []
    const failedValues = type === 'transfers' ? data.map(d => d.failed) : []

    const maxValue = Math.max(...values, 1)

    const padding = 40
    const chartWidth = width - padding * 2
    const chartHeight = height - padding * 2

    const points = values.map((v, i) => ({
      x: padding + (i / Math.max(data.length - 1, 1)) * chartWidth,
      y: height - padding - (v / maxValue) * chartHeight,
      value: v
    }))

    const successPoints = successValues.map((v, i) => ({
      x: padding + (i / Math.max(data.length - 1, 1)) * chartWidth,
      y: height - padding - (v / maxValue) * chartHeight,
      value: v
    }))

    const failedPoints = failedValues.map((v, i) => ({
      x: padding + (i / Math.max(data.length - 1, 1)) * chartWidth,
      y: height - padding - (v / maxValue) * chartHeight,
      value: v
    }))

    // Generate time labels
    const labels = data.length > 0 ? [
      formatTime(data[0].timestampIso),
      data.length > 1 ? formatTime(data[Math.floor(data.length / 2)].timestampIso) : '',
      data.length > 1 ? formatTime(data[data.length - 1].timestampIso) : ''
    ] : []

    return { points, successPoints, failedPoints, maxValue, labels, padding }
  }, [data, width, height, type])

  if (!data || data.length === 0) {
    return (
      <div style={{
        width,
        height,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'var(--bg-color)',
        borderRadius: '6px',
        color: 'var(--text-light)'
      }}>
        No data available
      </div>
    )
  }

  const { points, successPoints, failedPoints, maxValue, labels, padding } = chartData

  // Create path for area chart
  const createPath = (pts: typeof points) => {
    if (pts.length === 0) return ''
    const pathPoints = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ')
    return pathPoints
  }

  // Create filled area path
  const createAreaPath = (pts: typeof points) => {
    if (pts.length === 0) return ''
    const linePath = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ')
    return `${linePath} L ${pts[pts.length - 1].x} ${height - padding} L ${pts[0].x} ${height - padding} Z`
  }

  return (
    <div style={{ marginBottom: '1rem' }}>
      {title && (
        <div style={{ fontWeight: 600, marginBottom: '0.5rem', fontSize: '0.875rem' }}>
          {title}
        </div>
      )}
      <svg width={width} height={height} style={{ display: 'block' }}>
        {/* Background */}
        <rect x={0} y={0} width={width} height={height} fill="var(--bg-color)" rx={6} />

        {/* Grid lines */}
        {[0, 0.25, 0.5, 0.75, 1].map((fraction, i) => (
          <line
            key={i}
            x1={padding}
            y1={padding + fraction * (height - padding * 2)}
            x2={width - padding}
            y2={padding + fraction * (height - padding * 2)}
            stroke="var(--border-color)"
            strokeDasharray="4 4"
            opacity={0.5}
          />
        ))}

        {type === 'transfers' && successPoints.length > 0 ? (
          <>
            {/* Success area */}
            <path
              d={createAreaPath(successPoints)}
              fill="var(--success-color)"
              opacity={0.2}
            />
            {/* Success line */}
            <path
              d={createPath(successPoints)}
              fill="none"
              stroke="var(--success-color)"
              strokeWidth={2}
            />

            {/* Failed area */}
            <path
              d={createAreaPath(failedPoints)}
              fill="var(--danger-color)"
              opacity={0.2}
            />
            {/* Failed line */}
            <path
              d={createPath(failedPoints)}
              fill="none"
              stroke="var(--danger-color)"
              strokeWidth={2}
            />
          </>
        ) : (
          <>
            {/* Main area */}
            <path
              d={createAreaPath(points)}
              fill="var(--secondary-color)"
              opacity={0.2}
            />
            {/* Main line */}
            <path
              d={createPath(points)}
              fill="none"
              stroke="var(--secondary-color)"
              strokeWidth={2}
            />
          </>
        )}

        {/* Y-axis labels */}
        <text x={padding - 5} y={padding} textAnchor="end" fontSize={10} fill="var(--text-light)">
          {formatValue(maxValue, type)}
        </text>
        <text x={padding - 5} y={height - padding} textAnchor="end" fontSize={10} fill="var(--text-light)">
          0
        </text>

        {/* X-axis labels */}
        {labels[0] && (
          <text x={padding} y={height - 5} textAnchor="start" fontSize={10} fill="var(--text-light)">
            {labels[0]}
          </text>
        )}
        {labels[1] && (
          <text x={width / 2} y={height - 5} textAnchor="middle" fontSize={10} fill="var(--text-light)">
            {labels[1]}
          </text>
        )}
        {labels[2] && (
          <text x={width - padding} y={height - 5} textAnchor="end" fontSize={10} fill="var(--text-light)">
            {labels[2]}
          </text>
        )}

        {/* Legend for transfers chart */}
        {type === 'transfers' && (
          <>
            <circle cx={width - 100} cy={15} r={4} fill="var(--success-color)" />
            <text x={width - 92} y={18} fontSize={10} fill="var(--text-light)">Successful</text>
            <circle cx={width - 100} cy={30} r={4} fill="var(--danger-color)" />
            <text x={width - 92} y={33} fontSize={10} fill="var(--text-light)">Failed</text>
          </>
        )}
      </svg>
    </div>
  )
}

function formatTime(isoString: string): string {
  try {
    const date = new Date(isoString)
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  } catch {
    return ''
  }
}

function formatValue(value: number, type: string): string {
  if (type === 'bytes') {
    if (value < 1024) return value + ' B'
    if (value < 1024 * 1024) return (value / 1024).toFixed(0) + ' KB'
    if (value < 1024 * 1024 * 1024) return (value / (1024 * 1024)).toFixed(0) + ' MB'
    return (value / (1024 * 1024 * 1024)).toFixed(1) + ' GB'
  }
  return value.toString()
}

export default Chart
