import { useState, useEffect } from 'react'
import { Routes, Route, NavLink, useLocation } from 'react-router-dom'
import Dashboard from './pages/Dashboard'
import Routes_Page from './pages/Routes'
import Destinations from './pages/Destinations'
import Scripts from './pages/Scripts'
import Transfers from './pages/Transfers'
import Storage from './pages/Storage'
import Logs from './pages/Logs'
import Settings from './pages/Settings'
import Login from './pages/Login'
import { checkAuth, setAuthToken, clearAuthToken, getAuthToken } from './hooks/useApi'

function App() {
  const location = useLocation()
  const [authenticated, setAuthenticated] = useState<boolean | null>(null)
  const [authRequired, setAuthRequired] = useState(true)
  const [username, setUsername] = useState<string | null>(null)

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
    <div className="app">
      <header className="header">
        <h1>XNAT DICOM Router - Admin</h1>
        <nav className="header-nav">
          <NavLink to="/" className={location.pathname === '/' ? 'active' : ''}>
            Dashboard
          </NavLink>
          <NavLink to="/routes" className={location.pathname === '/routes' ? 'active' : ''}>
            Routes
          </NavLink>
          <NavLink to="/destinations" className={location.pathname === '/destinations' ? 'active' : ''}>
            Destinations
          </NavLink>
          <NavLink to="/scripts" className={location.pathname === '/scripts' ? 'active' : ''}>
            Scripts
          </NavLink>
          <NavLink to="/transfers" className={location.pathname === '/transfers' ? 'active' : ''}>
            Transfers
          </NavLink>
          <NavLink to="/storage" className={location.pathname === '/storage' ? 'active' : ''}>
            Storage
          </NavLink>
          <NavLink to="/logs" className={location.pathname === '/logs' ? 'active' : ''}>
            Logs
          </NavLink>
          <NavLink to="/settings" className={location.pathname === '/settings' ? 'active' : ''}>
            Settings
          </NavLink>
        </nav>
        <div className="header-user">
          {username && (
            <>
              <span style={{ marginRight: '1rem', opacity: 0.8 }}>{username}</span>
              <button
                onClick={handleLogout}
                style={{
                  background: 'rgba(255,255,255,0.2)',
                  border: 'none',
                  color: 'white',
                  padding: '0.5rem 1rem',
                  borderRadius: '4px',
                  cursor: 'pointer',
                  fontSize: '0.9rem'
                }}
              >
                Logout
              </button>
            </>
          )}
        </div>
      </header>

      <main className="main-content">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/routes" element={<Routes_Page />} />
          <Route path="/destinations" element={<Destinations />} />
          <Route path="/scripts" element={<Scripts />} />
          <Route path="/transfers" element={<Transfers />} />
          <Route path="/storage" element={<Storage />} />
          <Route path="/logs" element={<Logs />} />
          <Route path="/settings" element={<Settings />} />
        </Routes>
      </main>
    </div>
  )
}

export default App
