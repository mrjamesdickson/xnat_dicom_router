import { useState, useEffect } from 'react'
import { Routes, Route, NavLink, useLocation } from 'react-router-dom'
import Dashboard from './pages/Dashboard'
import Routes_Page from './pages/Routes'
import Destinations from './pages/Destinations'
import Brokers from './pages/Brokers'
import Scripts from './pages/Scripts'
import Transfers from './pages/Transfers'
import Import from './pages/Import'
import Storage from './pages/Storage'
import DicomViewer from './pages/DicomViewer'
import Logs from './pages/Logs'
import Settings from './pages/Settings'
import Login from './pages/Login'
import OCR from './pages/OCR'
import QueryRetrieve from './pages/QueryRetrieve'
import Search from './pages/Search'
import Index from './pages/Index'
import DicomReview from './pages/DicomReview'
import { checkAuth, setAuthToken, clearAuthToken, useFetch } from './hooks/useApi'

const APP_VERSION = '2.1.0'

interface FeaturesConfig {
  enableIndexing: boolean
  enableReview: boolean
  enableOcr: boolean
  enableQueryRetrieve: boolean
}

interface ConfigResponse {
  features: FeaturesConfig
}
const BUILD_TIME = '__BUILD_TIME__'

interface AboutModalProps {
  isOpen: boolean
  onClose: () => void
}

function AboutModal({ isOpen, onClose }: AboutModalProps) {
  if (!isOpen) return null

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        background: 'rgba(0,0,0,0.5)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000
      }}
      onClick={onClose}
    >
      <div
        style={{
          background: 'white',
          borderRadius: '8px',
          padding: '2rem',
          maxWidth: '500px',
          width: '90%',
          boxShadow: '0 4px 20px rgba(0,0,0,0.3)'
        }}
        onClick={e => e.stopPropagation()}
      >
        <div style={{ textAlign: 'center', marginBottom: '1.5rem' }}>
          <div style={{
            width: '80px',
            height: '80px',
            background: 'linear-gradient(135deg, #2c3e50 0%, #3498db 100%)',
            borderRadius: '16px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            margin: '0 auto 1rem',
            fontSize: '2.5rem'
          }}>
            <span role="img" aria-label="router">&#128268;</span>
          </div>
          <h2 style={{ margin: '0 0 0.5rem', color: '#2c3e50' }}>XNAT DICOM Router</h2>
          <p style={{ margin: 0, color: '#7f8c8d', fontSize: '0.9rem' }}>
            Enterprise DICOM Routing Solution
          </p>
        </div>

        <div style={{
          background: '#f8f9fa',
          borderRadius: '6px',
          padding: '1rem',
          marginBottom: '1.5rem'
        }}>
          <table style={{ width: '100%', fontSize: '0.9rem' }}>
            <tbody>
              <tr>
                <td style={{ padding: '0.25rem 0', color: '#7f8c8d' }}>Version</td>
                <td style={{ padding: '0.25rem 0', textAlign: 'right', fontWeight: 500 }}>{APP_VERSION}</td>
              </tr>
              <tr>
                <td style={{ padding: '0.25rem 0', color: '#7f8c8d' }}>Build</td>
                <td style={{ padding: '0.25rem 0', textAlign: 'right', fontFamily: 'monospace', fontSize: '0.8rem' }}>
                  {BUILD_TIME.startsWith('__') ? 'Development' : BUILD_TIME}
                </td>
              </tr>
              <tr>
                <td style={{ padding: '0.25rem 0', color: '#7f8c8d' }}>dcm4che</td>
                <td style={{ padding: '0.25rem 0', textAlign: 'right' }}>5.x</td>
              </tr>
              <tr>
                <td style={{ padding: '0.25rem 0', color: '#7f8c8d' }}>Java</td>
                <td style={{ padding: '0.25rem 0', textAlign: 'right' }}>11+</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div style={{
          borderTop: '1px solid #eee',
          paddingTop: '1rem',
          marginBottom: '1rem',
          textAlign: 'center',
          fontSize: '0.85rem',
          color: '#7f8c8d'
        }}>
          <p style={{ margin: '0 0 0.5rem' }}>
            Copyright 2024 XNATWorks.
          </p>
          <p style={{ margin: '0 0 0.5rem' }}>
            This software is proprietary and confidential.
          </p>
          <p style={{ margin: 0 }}>
            <a
              href="https://xnatworks.com"
              target="_blank"
              rel="noopener noreferrer"
              style={{ color: '#3498db', textDecoration: 'none' }}
            >
              xnatworks.com
            </a>
          </p>
        </div>

        <div style={{ textAlign: 'center' }}>
          <button
            onClick={onClose}
            style={{
              background: '#3498db',
              color: 'white',
              border: 'none',
              padding: '0.75rem 2rem',
              borderRadius: '4px',
              cursor: 'pointer',
              fontSize: '0.9rem',
              fontWeight: 500
            }}
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}

type Theme = 'light' | 'dark' | 'high-contrast' | 'ocean'

const THEME_LABELS: Record<Theme, string> = {
  'light': 'Light',
  'dark': 'Dark',
  'high-contrast': 'High Contrast',
  'ocean': 'Ocean'
}

// Navigation menu structure
interface NavItem {
  path: string
  label: string
  icon: string
  featureKey?: keyof FeaturesConfig  // Optional feature flag to check
}

interface NavSection {
  title: string
  items: NavItem[]
}

const NAV_SECTIONS: NavSection[] = [
  {
    title: 'Overview',
    items: [
      { path: '/', label: 'Dashboard', icon: '' },
    ]
  },
  {
    title: 'Configuration',
    items: [
      { path: '/routes', label: 'Routes', icon: '' },
      { path: '/destinations', label: 'Destinations', icon: '' },
      { path: '/brokers', label: 'Brokers', icon: '' },
      { path: '/scripts', label: 'Scripts', icon: '' },
      { path: '/ocr', label: 'OCR', icon: '', featureKey: 'enableOcr' },
    ]
  },
  {
    title: 'Operations',
    items: [
      { path: '/transfers', label: 'Transfers', icon: '' },
      { path: '/query-retrieve', label: 'Query/Retrieve', icon: '', featureKey: 'enableQueryRetrieve' },
      { path: '/import', label: 'Import', icon: '' },
      { path: '/storage', label: 'Storage', icon: '' },
      { path: '/review', label: 'Review', icon: '', featureKey: 'enableReview' },
    ]
  },
  {
    title: 'Data',
    items: [
      { path: '/index', label: 'Index', icon: '', featureKey: 'enableIndexing' },
      { path: '/search', label: 'Search', icon: '', featureKey: 'enableIndexing' },
    ]
  },
  {
    title: 'System',
    items: [
      { path: '/logs', label: 'Logs', icon: '' },
      { path: '/settings', label: 'Settings', icon: '' },
    ]
  }
]

function App() {
  const location = useLocation()
  const [authenticated, setAuthenticated] = useState<boolean | null>(null)
  const [authRequired, setAuthRequired] = useState(true)
  const [username, setUsername] = useState<string | null>(null)
  const [showAbout, setShowAbout] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [theme, setTheme] = useState<Theme>(() => {
    const saved = localStorage.getItem('theme')
    return (saved as Theme) || 'light'
  })

  // Fetch feature flags from config
  const { data: configData } = useFetch<ConfigResponse>('/config')
  const features = configData?.features || {
    enableIndexing: false,  // Disabled by default (experimental)
    enableReview: true,
    enableOcr: true,
    enableQueryRetrieve: true
  }

  // Filter navigation based on feature flags
  const filteredNavSections = NAV_SECTIONS.map(section => ({
    ...section,
    items: section.items.filter(item =>
      !item.featureKey || features[item.featureKey]
    )
  })).filter(section => section.items.length > 0)

  // Apply theme to document
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem('theme', theme)
  }, [theme])

  useEffect(() => {
    const verifyAuth = async () => {
      const result = await checkAuth()
      setAuthRequired(result.authRequired)
      setAuthenticated(result.authenticated || !result.authRequired)
      setUsername(result.username || null)
    }
    verifyAuth()
  }, [])

  const handleLogin = (token: string, user: string) => {
    setAuthToken(token)
    setAuthenticated(true)
    setUsername(user)
  }

  const handleLogout = () => {
    clearAuthToken()
    setAuthenticated(false)
    setUsername(null)
  }

  // Still checking auth
  if (authenticated === null) {
    return (
      <div style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f5f7fa'
      }}>
        <div className="spinner" />
      </div>
    )
  }

  // Show login if auth required and not authenticated
  if (authRequired && !authenticated) {
    return <Login onLogin={handleLogin} />
  }

  return (
    <div className="app-layout">
      {/* Left Sidebar */}
      <aside className={`sidebar ${sidebarCollapsed ? 'collapsed' : ''}`}>
        <div className="sidebar-header">
          <div className="sidebar-logo">
            <span className="logo-icon">🔗</span>
          </div>
          <button
            className="sidebar-toggle"
            onClick={() => setSidebarCollapsed(!sidebarCollapsed)}
            title={sidebarCollapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            {sidebarCollapsed ? '→' : '←'}
          </button>
        </div>

        <nav className="sidebar-nav">
          {filteredNavSections.map((section) => (
            <div key={section.title} className="nav-section">
              {!sidebarCollapsed && <div className="nav-section-title">{section.title}</div>}
              {section.items.map((item) => (
                <NavLink
                  key={item.path}
                  to={item.path}
                  className={({ isActive }) =>
                    `nav-item ${isActive || (item.path === '/' && location.pathname === '/') ? 'active' : ''}`
                  }
                  title={sidebarCollapsed ? item.label : undefined}
                >
                  {!sidebarCollapsed && <span className="nav-label">{item.label}</span>}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <div className="sidebar-footer">
          {!sidebarCollapsed && (
            <div className="sidebar-user">
              {username && <span className="user-name">{username}</span>}
            </div>
          )}
        </div>
      </aside>

      {/* Main Content Area */}
      <div className="main-area">
        {/* Top Header Bar */}
        <header className="top-header">
          <div className="header-left">
            <h1 className="page-title">
              {filteredNavSections.flatMap(s => s.items).find(item =>
                item.path === location.pathname ||
                (item.path === '/' && location.pathname === '/')
              )?.label || 'DICOM Router'}
            </h1>
          </div>
          <div className="header-right">
            <span style={{ fontWeight: 600, marginRight: '1rem', color: 'var(--header-text)' }}>XNATWorks DICOM Router</span>
            <select
              className="theme-select"
              value={theme}
              onChange={(e) => setTheme(e.target.value as Theme)}
              title="Theme"
            >
              {(Object.keys(THEME_LABELS) as Theme[]).map(t => (
                <option key={t} value={t}>{THEME_LABELS[t]}</option>
              ))}
            </select>
            <button
              className="header-btn about-btn"
              onClick={() => setShowAbout(true)}
              title="About"
            >
              ?
            </button>
            {username && (
              <button className="header-btn logout-btn" onClick={handleLogout}>
                Logout
              </button>
            )}
          </div>
        </header>

        <AboutModal isOpen={showAbout} onClose={() => setShowAbout(false)} />

        <main className="main-content">
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/routes" element={<Routes_Page />} />
            <Route path="/destinations" element={<Destinations />} />
            <Route path="/brokers" element={<Brokers />} />
            <Route path="/scripts" element={<Scripts />} />
            <Route path="/ocr" element={<OCR />} />
            <Route path="/transfers" element={<Transfers />} />
            <Route path="/query-retrieve" element={<QueryRetrieve />} />
            <Route path="/import" element={<Import />} />
            <Route path="/storage" element={<Storage />} />
            <Route path="/index" element={<Index />} />
            <Route path="/search" element={<Search />} />
            <Route path="/dicom-viewer" element={<DicomViewer />} />
            <Route path="/review" element={<DicomReview />} />
            <Route path="/logs" element={<Logs />} />
            <Route path="/settings" element={<Settings />} />
          </Routes>
        </main>
      </div>
    </div>
  )
}

export default App
