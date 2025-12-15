import { useState, useEffect, useRef } from 'react'
import { useFetch, getAuthToken } from '../hooks/useApi'

interface LogFile {
  route: string
  name: string
  path: string
  size: number
  sizeFormatted: string
  modified: string
}

interface LogsOverview {
  logs: LogFile[]
  count: number
}

interface TailResult {
  route: string
  file: string
  lines: string[]
  lineCount: number
  offset: number
  size: number
}

export default function Logs() {
  const { data: overview, loading, error, refetch } = useFetch<LogsOverview>('/logs', 30000)
  const [selectedLog, setSelectedLog] = useState<{ route: string; file: string } | null>(null)
  const [tailData, setTailData] = useState<TailResult | null>(null)
  const [tailOffset, setTailOffset] = useState<number>(0)
  const [isFollowing, setIsFollowing] = useState(false)
  const [numLines, setNumLines] = useState(100)
  const logContainerRef = useRef<HTMLDivElement>(null)
  const followIntervalRef = useRef<number | null>(null)

  // Fetch log tail when log is selected
  useEffect(() => {
    if (selectedLog) {
      fetchTail(0) // Start from beginning
    } else {
      setTailData(null)
      setTailOffset(0)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedLog, numLines])

  // Auto-follow functionality
  useEffect(() => {
    if (isFollowing && selectedLog) {
      // Immediately scroll to bottom when following starts
      if (logContainerRef.current) {
        logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight
      }
      followIntervalRef.current = window.setInterval(() => {
        fetchTailIncremental()
      }, 2000)
    }
    return () => {
      if (followIntervalRef.current) {
        clearInterval(followIntervalRef.current)
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isFollowing, selectedLog, tailOffset])

  const fetchTail = async (offset: number) => {
    if (!selectedLog) return
    try {
      const headers: HeadersInit = { 'Content-Type': 'application/json' }
      const token = getAuthToken()
      if (token) headers['X-Auth-Token'] = token
      const response = await fetch(`/api/logs/tail/${selectedLog.route}/${selectedLog.file}?lines=${numLines}&offset=${offset}`, { headers })
      if (response.ok) {
        const data = await response.json()
        setTailData(data)
        setTailOffset(data.offset)
        // Auto-scroll to bottom
        if (logContainerRef.current) {
          logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight
        }
      }
    } catch (err) {
      console.error('Failed to fetch log tail:', err)
    }
  }

  const fetchTailIncremental = async () => {
    if (!selectedLog || tailOffset === 0) return
    try {
      const headers: HeadersInit = { 'Content-Type': 'application/json' }
      const token = getAuthToken()
      if (token) headers['X-Auth-Token'] = token
      const response = await fetch(`/api/logs/tail/${selectedLog.route}/${selectedLog.file}?lines=${numLines}&offset=${tailOffset}`, { headers })
      if (response.ok) {
        const data = await response.json()
        if (data.lines && data.lines.length > 0) {
          setTailData(prev => ({
            ...data,
            lines: prev ? [...prev.lines, ...data.lines] : data.lines
          }))
          setTailOffset(data.offset)
          // Auto-scroll to bottom
          if (logContainerRef.current) {
            logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight
          }
        }
      }
    } catch (err) {
      console.error('Failed to fetch log updates:', err)
    }
  }

  const handleSelectLog = (route: string, file: string) => {
    setSelectedLog({ route, file })
    setIsFollowing(false)
  }

  const handleRefreshTail = () => {
    if (selectedLog) {
      fetchTail(0)
    }
  }

  const toggleFollow = () => {
    setIsFollowing(!isFollowing)
  }

  if (loading && !overview) return <div className="loading">Loading logs...</div>
  if (error) return <div className="error">Error: {error}</div>

  // Group logs by route
  const logsByRoute = overview?.logs.reduce((acc, log) => {
    if (!acc[log.route]) acc[log.route] = []
    acc[log.route].push(log)
    return acc
  }, {} as Record<string, LogFile[]>) || {}

  return (
    <div className="logs-page">
      <div className="page-header">
        <h2>Log Viewer</h2>
        <button onClick={() => refetch()} className="btn btn-secondary">
          Refresh List
        </button>
      </div>

      <div className="logs-layout">
        {/* Log file list */}
        <div className="logs-sidebar">
          <h3>Log Files ({overview?.count || 0})</h3>
          {Object.keys(logsByRoute).length === 0 ? (
            <p className="empty-message">No log files found</p>
          ) : (
            Object.entries(logsByRoute).map(([route, logs]) => (
              <div key={route} className="log-route-section">
                <h4>{route}</h4>
                <ul className="log-file-list">
                  {logs.map(log => (
                    <li
                      key={log.path}
                      className={selectedLog?.route === log.route && selectedLog?.file === log.name ? 'selected' : ''}
                      onClick={() => handleSelectLog(log.route, log.name)}
                    >
                      <span className="log-name">{log.name}</span>
                      <span className="log-meta">
                        {log.sizeFormatted}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))
          )}
        </div>

        {/* Log content */}
        <div className="logs-content">
          {!selectedLog ? (
            <div className="no-selection">
              <p>Select a log file from the sidebar to view its contents</p>
            </div>
          ) : (
            <>
              <div className="log-header">
                <span className="log-title">
                  {selectedLog.route} / {selectedLog.file}
                  {tailData && <span className="log-size"> ({tailData.size} bytes)</span>}
                </span>
                <div className="log-controls">
                  <label>
                    Lines:
                    <select
                      value={numLines}
                      onChange={e => setNumLines(Number(e.target.value))}
                    >
                      <option value={50}>50</option>
                      <option value={100}>100</option>
                      <option value={200}>200</option>
                      <option value={500}>500</option>
                      <option value={1000}>1000</option>
                    </select>
                  </label>
                  <button
                    onClick={toggleFollow}
                    className={`btn ${isFollowing ? 'btn-warning' : 'btn-secondary'}`}
                  >
                    {isFollowing ? 'Stop Follow' : 'Follow'}
                  </button>
                  <button onClick={handleRefreshTail} className="btn btn-primary">
                    Refresh
                  </button>
                </div>
              </div>
              <div className="log-viewer" ref={logContainerRef}>
                {tailData?.lines.map((line, idx) => (
                  <div key={idx} className="log-line">
                    <span className="line-number">{idx + 1}</span>
                    <span className="line-content">{line}</span>
                  </div>
                )) || <div className="loading">Loading log content...</div>}
              </div>
              {isFollowing && (
                <div className="follow-indicator">
                  Auto-refreshing every 2 seconds...
                </div>
              )}
            </>
          )}
        </div>
      </div>

      <style>{`
        .logs-page {
          padding: 1rem;
        }

        .page-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          margin-bottom: 1rem;
        }

        .page-header h2 {
          margin: 0;
        }

        .logs-layout {
          display: flex;
          gap: 1rem;
          height: calc(100vh - 200px);
        }

        .logs-sidebar {
          width: 300px;
          flex-shrink: 0;
          background: var(--card-bg);
          border-radius: 8px;
          padding: 1rem;
          overflow-y: auto;
        }

        .logs-sidebar h3 {
          margin: 0 0 1rem 0;
          font-size: 1rem;
          color: var(--text-color);
        }

        .log-route-section {
          margin-bottom: 1rem;
        }

        .log-route-section h4 {
          margin: 0 0 0.5rem 0;
          font-size: 0.9rem;
          color: var(--text-light);
          padding: 0.25rem 0.5rem;
          background: var(--bg-color);
          border-radius: 4px;
        }

        .log-file-list {
          list-style: none;
          padding: 0;
          margin: 0;
        }

        .log-file-list li {
          padding: 0.5rem;
          cursor: pointer;
          border-radius: 4px;
          display: flex;
          justify-content: space-between;
          align-items: center;
          font-size: 0.85rem;
        }

        .log-file-list li:hover {
          background: var(--hover-bg);
        }

        .log-file-list li.selected {
          background: var(--secondary-color);
          color: white;
        }

        .log-name {
          font-family: monospace;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .log-meta {
          font-size: 0.75rem;
          opacity: 0.8;
          flex-shrink: 0;
          margin-left: 0.5rem;
        }

        .logs-content {
          flex: 1;
          background: var(--card-bg);
          border-radius: 8px;
          display: flex;
          flex-direction: column;
          overflow: hidden;
        }

        .no-selection {
          display: flex;
          align-items: center;
          justify-content: center;
          height: 100%;
          color: var(--text-light);
        }

        .log-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: 0.75rem 1rem;
          background: #343a40;
          color: white;
          border-radius: 8px 8px 0 0;
        }

        .log-title {
          font-family: monospace;
          font-weight: 500;
        }

        .log-size {
          font-size: 0.85rem;
          opacity: 0.8;
        }

        .log-controls {
          display: flex;
          gap: 0.5rem;
          align-items: center;
        }

        .log-controls label {
          display: flex;
          align-items: center;
          gap: 0.25rem;
          font-size: 0.85rem;
        }

        .log-controls select {
          padding: 0.25rem 0.5rem;
          border-radius: 4px;
          border: 1px solid #495057;
          background: #495057;
          color: white;
        }

        .log-viewer {
          flex: 1;
          overflow-y: auto;
          background: #1e1e1e;
          padding: 0.5rem;
          font-family: 'Monaco', 'Consolas', 'Courier New', monospace;
          font-size: 0.8rem;
          color: #d4d4d4;
        }

        .log-line {
          display: flex;
          line-height: 1.4;
        }

        .line-number {
          width: 50px;
          flex-shrink: 0;
          color: #858585;
          text-align: right;
          padding-right: 1rem;
          user-select: none;
        }

        .line-content {
          white-space: pre-wrap;
          word-break: break-all;
        }

        .follow-indicator {
          padding: 0.5rem 1rem;
          background: #ffc107;
          color: #212529;
          font-size: 0.85rem;
          text-align: center;
        }

        .empty-message {
          color: var(--text-light);
          font-style: italic;
        }

        .btn {
          padding: 0.375rem 0.75rem;
          border: none;
          border-radius: 4px;
          cursor: pointer;
          font-size: 0.875rem;
        }

        .btn-primary {
          background: var(--secondary-color);
          color: white;
        }

        .btn-secondary {
          background: #6c757d;
          color: white;
        }

        .btn-warning {
          background: #ffc107;
          color: #212529;
        }

        .btn:hover {
          opacity: 0.9;
        }

        .loading {
          display: flex;
          align-items: center;
          justify-content: center;
          padding: 2rem;
          color: var(--text-light);
        }

        .error {
          color: var(--danger-color);
          padding: 1rem;
        }
      `}</style>
    </div>
  )
}
