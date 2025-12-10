import { useState, useEffect } from 'react'
import { useFetch, apiPut } from '../hooks/useApi'

interface Config {
  adminPort: number
  adminHost: string
  dataDirectory: string
  scriptsDirectory: string
  logLevel: string
  configFile: string | null
  routeCount: number
  destinations: { xnat: number; dicom: number; file: number }
  receiver: {
    baseDir: string
    storageDir: string
  }
  resilience: {
    healthCheckInterval: number
    cacheDir: string
    maxRetries: number
    retryDelay: number
    retentionDays: number
  }
  notifications: {
    enabled: boolean
    smtpServer: string
    smtpPort: number
    smtpUseTls: boolean
    smtpUsername: string
    fromAddress: string
    adminEmail: string
    notifyOnDestinationDown: boolean
    notifyOnDestinationRecovered: boolean
    notifyOnForwardFailure: boolean
    notifyOnDailySummary: boolean
  }
}

export default function Settings() {
  const { data: config, loading, error, refetch } = useFetch<Config>('/config')
  const [editing, setEditing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saveSuccess, setSaveSuccess] = useState(false)

  // Form state
  const [formData, setFormData] = useState<Partial<Config>>({})

  useEffect(() => {
    if (config) {
      setFormData({
        dataDirectory: config.dataDirectory,
        scriptsDirectory: config.scriptsDirectory,
        logLevel: config.logLevel,
        receiver: { ...config.receiver },
        resilience: { ...config.resilience },
        notifications: { ...config.notifications }
      })
    }
  }, [config])

  const handleSave = async () => {
    setSaving(true)
    setSaveError(null)
    setSaveSuccess(false)

    try {
      await apiPut('/config', formData)
      setSaveSuccess(true)
      setEditing(false)
      refetch()
      setTimeout(() => setSaveSuccess(false), 3000)
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  const handleCancel = () => {
    if (config) {
      setFormData({
        dataDirectory: config.dataDirectory,
        scriptsDirectory: config.scriptsDirectory,
        logLevel: config.logLevel,
        receiver: { ...config.receiver },
        resilience: { ...config.resilience },
        notifications: { ...config.notifications }
      })
    }
    setEditing(false)
    setSaveError(null)
  }

  if (loading) {
    return <div className="loading"><div className="spinner" /></div>
  }

  if (error) {
    return <div className="error-message">Failed to load configuration: {error}</div>
  }

  return (
    <div>
      {saveSuccess && (
        <div style={{
          background: '#d4edda',
          border: '1px solid #c3e6cb',
          color: '#155724',
          padding: '0.75rem 1rem',
          borderRadius: '4px',
          marginBottom: '1rem'
        }}>
          Configuration saved successfully!
        </div>
      )}

      {saveError && (
        <div style={{
          background: '#f8d7da',
          border: '1px solid #f5c6cb',
          color: '#721c24',
          padding: '0.75rem 1rem',
          borderRadius: '4px',
          marginBottom: '1rem'
        }}>
          Error: {saveError}
        </div>
      )}

      {/* General Settings */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">General Settings</h2>
          {!editing ? (
            <button className="btn btn-primary" onClick={() => setEditing(true)}>
              Edit
            </button>
          ) : (
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button className="btn btn-primary" onClick={handleSave} disabled={saving}>
                {saving ? 'Saving...' : 'Save'}
              </button>
              <button className="btn btn-secondary" onClick={handleCancel} disabled={saving}>
                Cancel
              </button>
            </div>
          )}
        </div>

        <div style={{ display: 'grid', gap: '1rem', gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))' }}>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Config File</label>
            <input
              type="text"
              value={config?.configFile || 'Not set'}
              disabled
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Admin Port</label>
            <input
              type="text"
              value={config?.adminPort || ''}
              disabled
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: '#f5f5f5' }}
            />
            <small style={{ color: '#666' }}>Restart required to change</small>
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Data Directory</label>
            <input
              type="text"
              value={formData.dataDirectory || ''}
              onChange={e => setFormData({ ...formData, dataDirectory: e.target.value })}
              disabled={!editing}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Scripts Directory</label>
            <input
              type="text"
              value={formData.scriptsDirectory || ''}
              onChange={e => setFormData({ ...formData, scriptsDirectory: e.target.value })}
              disabled={!editing}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Log Level</label>
            <select
              value={formData.logLevel || 'INFO'}
              onChange={e => setFormData({ ...formData, logLevel: e.target.value })}
              disabled={!editing}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            >
              <option value="TRACE">TRACE</option>
              <option value="DEBUG">DEBUG</option>
              <option value="INFO">INFO</option>
              <option value="WARN">WARN</option>
              <option value="ERROR">ERROR</option>
            </select>
          </div>
        </div>
      </div>

      {/* Resilience Settings */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Resilience Settings</h2>
        </div>

        <div style={{ display: 'grid', gap: '1rem', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))' }}>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Health Check Interval (sec)</label>
            <input
              type="number"
              value={formData.resilience?.healthCheckInterval || 60}
              onChange={e => setFormData({
                ...formData,
                resilience: { ...formData.resilience!, healthCheckInterval: parseInt(e.target.value) }
              })}
              disabled={!editing}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Max Retries</label>
            <input
              type="number"
              value={formData.resilience?.maxRetries || 10}
              onChange={e => setFormData({
                ...formData,
                resilience: { ...formData.resilience!, maxRetries: parseInt(e.target.value) }
              })}
              disabled={!editing}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Retry Delay (sec)</label>
            <input
              type="number"
              value={formData.resilience?.retryDelay || 300}
              onChange={e => setFormData({
                ...formData,
                resilience: { ...formData.resilience!, retryDelay: parseInt(e.target.value) }
              })}
              disabled={!editing}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Retention Days</label>
            <input
              type="number"
              value={formData.resilience?.retentionDays || 30}
              onChange={e => setFormData({
                ...formData,
                resilience: { ...formData.resilience!, retentionDays: parseInt(e.target.value) }
              })}
              disabled={!editing}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
        </div>
      </div>

      {/* Notification Settings */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Email Notifications</h2>
        </div>

        <div style={{ marginBottom: '1rem' }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: editing ? 'pointer' : 'default' }}>
            <input
              type="checkbox"
              checked={formData.notifications?.enabled || false}
              onChange={e => setFormData({
                ...formData,
                notifications: { ...formData.notifications!, enabled: e.target.checked }
              })}
              disabled={!editing}
            />
            <span style={{ fontWeight: 500 }}>Enable Email Notifications</span>
          </label>
        </div>

        <div style={{ display: 'grid', gap: '1rem', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', opacity: formData.notifications?.enabled ? 1 : 0.6 }}>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>SMTP Server</label>
            <input
              type="text"
              value={formData.notifications?.smtpServer || ''}
              onChange={e => setFormData({
                ...formData,
                notifications: { ...formData.notifications!, smtpServer: e.target.value }
              })}
              disabled={!editing || !formData.notifications?.enabled}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>SMTP Port</label>
            <input
              type="number"
              value={formData.notifications?.smtpPort || 25}
              onChange={e => setFormData({
                ...formData,
                notifications: { ...formData.notifications!, smtpPort: parseInt(e.target.value) }
              })}
              disabled={!editing || !formData.notifications?.enabled}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>SMTP Username</label>
            <input
              type="text"
              value={formData.notifications?.smtpUsername || ''}
              onChange={e => setFormData({
                ...formData,
                notifications: { ...formData.notifications!, smtpUsername: e.target.value }
              })}
              disabled={!editing || !formData.notifications?.enabled}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>From Address</label>
            <input
              type="email"
              value={formData.notifications?.fromAddress || ''}
              onChange={e => setFormData({
                ...formData,
                notifications: { ...formData.notifications!, fromAddress: e.target.value }
              })}
              disabled={!editing || !formData.notifications?.enabled}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 500 }}>Admin Email</label>
            <input
              type="email"
              value={formData.notifications?.adminEmail || ''}
              onChange={e => setFormData({
                ...formData,
                notifications: { ...formData.notifications!, adminEmail: e.target.value }
              })}
              disabled={!editing || !formData.notifications?.enabled}
              style={{ width: '100%', padding: '0.5rem', border: '1px solid #ddd', borderRadius: '4px', background: editing ? '#fff' : '#f5f5f5' }}
            />
          </div>
          <div>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <input
                type="checkbox"
                checked={formData.notifications?.smtpUseTls || false}
                onChange={e => setFormData({
                  ...formData,
                  notifications: { ...formData.notifications!, smtpUseTls: e.target.checked }
                })}
                disabled={!editing || !formData.notifications?.enabled}
              />
              <span>Use TLS</span>
            </label>
          </div>
        </div>

        <h3 style={{ marginTop: '1.5rem', marginBottom: '1rem' }}>Notification Events</h3>
        <div style={{ display: 'grid', gap: '0.5rem', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', opacity: formData.notifications?.enabled ? 1 : 0.6 }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <input
              type="checkbox"
              checked={formData.notifications?.notifyOnDestinationDown || false}
              onChange={e => setFormData({
                ...formData,
                notifications: { ...formData.notifications!, notifyOnDestinationDown: e.target.checked }
              })}
              disabled={!editing || !formData.notifications?.enabled}
            />
            <span>Destination Down</span>
          </label>
          <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <input
              type="checkbox"
              checked={formData.notifications?.notifyOnDestinationRecovered || false}
              onChange={e => setFormData({
                ...formData,
                notifications: { ...formData.notifications!, notifyOnDestinationRecovered: e.target.checked }
              })}
              disabled={!editing || !formData.notifications?.enabled}
            />
            <span>Destination Recovered</span>
          </label>
          <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <input
              type="checkbox"
              checked={formData.notifications?.notifyOnForwardFailure || false}
              onChange={e => setFormData({
                ...formData,
                notifications: { ...formData.notifications!, notifyOnForwardFailure: e.target.checked }
              })}
              disabled={!editing || !formData.notifications?.enabled}
            />
            <span>Forward Failure</span>
          </label>
          <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
            <input
              type="checkbox"
              checked={formData.notifications?.notifyOnDailySummary || false}
              onChange={e => setFormData({
                ...formData,
                notifications: { ...formData.notifications!, notifyOnDailySummary: e.target.checked }
              })}
              disabled={!editing || !formData.notifications?.enabled}
            />
            <span>Daily Summary</span>
          </label>
        </div>
      </div>

      {/* Summary */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Configuration Summary</h2>
        </div>
        <div className="stat-grid">
          <div className="stat-item">
            <div className="stat-value">{config?.routeCount || 0}</div>
            <div className="stat-label">Routes</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{config?.destinations.xnat || 0}</div>
            <div className="stat-label">XNAT Destinations</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{config?.destinations.dicom || 0}</div>
            <div className="stat-label">DICOM Destinations</div>
          </div>
          <div className="stat-item">
            <div className="stat-value">{config?.destinations.file || 0}</div>
            <div className="stat-label">File Destinations</div>
          </div>
        </div>
      </div>
    </div>
  )
}
