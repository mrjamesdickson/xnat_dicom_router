import { useState } from 'react'
import { useFetch, apiPost, apiPut, apiDelete } from '../hooks/useApi'

interface Destination {
  name: string
  type: string
  url?: string
  aeTitle?: string
  host?: string
  port?: number
  path?: string
  enabled: boolean
  available: boolean
  availabilityPercent: number
  lastCheck?: string
  consecutiveFailures?: number
}

interface DestinationHealth {
  name: string
  type: string
  available: boolean
  availabilityPercent: number
  totalChecks: number
  successfulChecks: number
  consecutiveFailures: number
  lastCheck: string | null
  lastAvailable: string | null
  downtimeSeconds: number
}

interface DestinationType {
  type: string
  description: string
}

type DestinationFormData = {
  name: string
  type: string
  enabled: boolean
  // XNAT fields
  url: string
  username: string
  password: string
  // DICOM fields
  aeTitle: string
  host: string
  port: number
  // File fields
  path: string
}

const defaultFormData: DestinationFormData = {
  name: '',
  type: 'xnat',
  enabled: true,
  url: '',
  username: '',
  password: '',
  aeTitle: '',
  host: '',
  port: 104,
  path: ''
}

export default function Destinations() {
  const { data: destinations, loading, error, refetch } = useFetch<Destination[]>('/destinations', 30000)
  const { data: destinationTypes } = useFetch<DestinationType[]>('/destinations/types')
  const [checking, setChecking] = useState<string | null>(null)
  const [selectedDest, setSelectedDest] = useState<string | null>(null)
  const { data: destDetail, refetch: refetchDetail } = useFetch<{ health: DestinationHealth; config: Record<string, unknown> }>(
    selectedDest ? `/destinations/${selectedDest}` : ''
  )

  // Form states
  const [showForm, setShowForm] = useState(false)
  const [editingDest, setEditingDest] = useState<string | null>(null)
  const [formData, setFormData] = useState<DestinationFormData>(defaultFormData)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  const handleCheckDestination = async (name: string) => {
    setChecking(name)
    try {
      await apiPost(`/destinations/${name}/check`, {})
      refetch()
      if (selectedDest === name) {
        refetchDetail()
      }
    } catch (err) {
      console.error('Health check failed:', err)
    } finally {
      setChecking(null)
    }
  }

  const handleCreateDestination = () => {
    setFormData(defaultFormData)
    setEditingDest(null)
    setShowForm(true)
    setFormError(null)
  }

  const handleEditDestination = (dest: Destination) => {
    setFormData({
      name: dest.name,
      type: dest.type,
      enabled: dest.enabled,
      url: dest.url || '',
      username: '',
      password: '',
      aeTitle: dest.aeTitle || '',
      host: dest.host || '',
      port: dest.port || 104,
      path: dest.path || ''
    })
    setEditingDest(dest.name)
    setShowForm(true)
    setFormError(null)
  }

  const handleSaveDestination = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setFormError(null)

    try {
      const payload: Record<string, unknown> = {
        name: formData.name,
        type: formData.type,
        enabled: formData.enabled
      }

      // Add type-specific fields
      if (formData.type === 'xnat') {
        payload.url = formData.url
        if (formData.username) payload.username = formData.username
        if (formData.password) payload.password = formData.password
      } else if (formData.type === 'dicom') {
        payload.aeTitle = formData.aeTitle
        payload.host = formData.host
        payload.port = formData.port
      } else if (formData.type === 'file') {
        payload.path = formData.path
      }

      if (editingDest) {
        await apiPut(`/destinations/${editingDest}`, payload)
      } else {
        await apiPost('/destinations', payload)
      }
      setShowForm(false)
      refetch()
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Failed to save destination')
    } finally {
      setSaving(false)
    }
  }

  const handleDeleteDestination = async (name: string) => {
    if (!confirm(`Delete destination "${name}"? This cannot be undone.`)) {
      return
    }

    try {
      await apiDelete(`/destinations/${name}`)
      if (selectedDest === name) {
        setSelectedDest(null)
      }
      refetch()
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete destination')
    }
  }

  if (loading) {
    return <div className="loading"><div className="spinner" /></div>
  }

  if (error) {
    return <div className="error-message">Failed to load destinations: {error}</div>
  }

  return (
    <div>
      {/* Create/Edit Destination Modal */}
      {showForm && (
        <div className="modal-overlay" onClick={() => setShowForm(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>{editingDest ? 'Edit Destination' : 'Create Destination'}</h2>
              <button className="btn btn-secondary" onClick={() => setShowForm(false)}>
                Cancel
              </button>
            </div>
            <form onSubmit={handleSaveDestination}>
              {formError && (
                <div className="error-message" style={{ marginBottom: '1rem' }}>
                  {formError}
                </div>
              )}

              <div className="form-grid">
                <div className="form-group">
                  <label>Name *</label>
                  <input
                    type="text"
                    value={formData.name}
                    onChange={e => setFormData({ ...formData, name: e.target.value })}
                    required
                    disabled={!!editingDest}
                    placeholder="my-destination"
                  />
                </div>

                <div className="form-group">
                  <label>Type *</label>
                  <select
                    value={formData.type}
                    onChange={e => setFormData({ ...formData, type: e.target.value })}
                    required
                    disabled={!!editingDest}
                  >
                    {destinationTypes?.map(t => (
                      <option key={t.type} value={t.type}>
                        {t.type.toUpperCase()} - {t.description}
                      </option>
                    )) || (
                      <>
                        <option value="xnat">XNAT - XNAT instance</option>
                        <option value="dicom">DICOM - DICOM AE Title</option>
                        <option value="file">FILE - File system directory</option>
                      </>
                    )}
                  </select>
                </div>

                {/* XNAT-specific fields */}
                {formData.type === 'xnat' && (
                  <>
                    <div className="form-group" style={{ gridColumn: 'span 2' }}>
                      <label>URL *</label>
                      <input
                        type="url"
                        value={formData.url}
                        onChange={e => setFormData({ ...formData, url: e.target.value })}
                        required
                        placeholder="https://xnat.example.com"
                      />
                    </div>

                    <div className="form-group">
                      <label>Username</label>
                      <input
                        type="text"
                        value={formData.username}
                        onChange={e => setFormData({ ...formData, username: e.target.value })}
                        placeholder="admin"
                      />
                      {editingDest && <small>Leave blank to keep existing</small>}
                    </div>

                    <div className="form-group">
                      <label>Password</label>
                      <input
                        type="password"
                        value={formData.password}
                        onChange={e => setFormData({ ...formData, password: e.target.value })}
                        placeholder="********"
                      />
                      {editingDest && <small>Leave blank to keep existing</small>}
                    </div>
                  </>
                )}

                {/* DICOM-specific fields */}
                {formData.type === 'dicom' && (
                  <>
                    <div className="form-group">
                      <label>AE Title *</label>
                      <input
                        type="text"
                        value={formData.aeTitle}
                        onChange={e => setFormData({ ...formData, aeTitle: e.target.value.toUpperCase() })}
                        required
                        maxLength={16}
                        placeholder="DESTINATION_AE"
                      />
                    </div>

                    <div className="form-group">
                      <label>Host *</label>
                      <input
                        type="text"
                        value={formData.host}
                        onChange={e => setFormData({ ...formData, host: e.target.value })}
                        required
                        placeholder="192.168.1.100"
                      />
                    </div>

                    <div className="form-group">
                      <label>Port *</label>
                      <input
                        type="number"
                        value={formData.port}
                        onChange={e => setFormData({ ...formData, port: parseInt(e.target.value) || 104 })}
                        required
                        min={1}
                        max={65535}
                      />
                    </div>
                  </>
                )}

                {/* File-specific fields */}
                {formData.type === 'file' && (
                  <div className="form-group" style={{ gridColumn: 'span 2' }}>
                    <label>Path *</label>
                    <input
                      type="text"
                      value={formData.path}
                      onChange={e => setFormData({ ...formData, path: e.target.value })}
                      required
                      placeholder="/path/to/output"
                    />
                  </div>
                )}

                <div className="form-group checkbox-group">
                  <label>
                    <input
                      type="checkbox"
                      checked={formData.enabled}
                      onChange={e => setFormData({ ...formData, enabled: e.target.checked })}
                    />
                    Enabled
                  </label>
                </div>
              </div>

              <div className="form-actions">
                <button type="submit" className="btn btn-primary" disabled={saving}>
                  {saving ? 'Saving...' : 'Save Destination'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Configured Destinations</h2>
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <button className="btn btn-primary" onClick={handleCreateDestination}>
              + New Destination
            </button>
            <button className="btn btn-secondary" onClick={() => refetch()}>
              Refresh
            </button>
          </div>
        </div>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Type</th>
                <th>Target</th>
                <th>Availability</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {destinations?.map(dest => (
                <tr key={dest.name}>
                  <td><strong>{dest.name}</strong></td>
                  <td>
                    <span style={{
                      padding: '0.125rem 0.5rem',
                      borderRadius: '4px',
                      fontSize: '0.75rem',
                      background: dest.type === 'xnat' ? '#e3f2fd' :
                                  dest.type === 'dicom' ? '#fff3e0' : '#e8f5e9',
                      color: dest.type === 'xnat' ? '#1565c0' :
                             dest.type === 'dicom' ? '#ef6c00' : '#2e7d32'
                    }}>
                      {dest.type.toUpperCase()}
                    </span>
                  </td>
                  <td>
                    {dest.type === 'xnat' && dest.url}
                    {dest.type === 'dicom' && `${dest.aeTitle}@${dest.host}:${dest.port}`}
                    {dest.type === 'file' && dest.path}
                  </td>
                  <td>{dest.availabilityPercent.toFixed(1)}%</td>
                  <td>
                    <span className={`status-badge status-${dest.available ? 'available' : 'unavailable'}`}>
                      {dest.available ? 'Available' : 'Unavailable'}
                    </span>
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      <button
                        className="btn btn-secondary"
                        onClick={() => handleCheckDestination(dest.name)}
                        disabled={checking === dest.name}
                      >
                        {checking === dest.name ? 'Checking...' : 'Check'}
                      </button>
                      <button
                        className="btn btn-secondary"
                        onClick={() => setSelectedDest(dest.name)}
                      >
                        Details
                      </button>
                      <button
                        className="btn btn-secondary"
                        onClick={() => handleEditDestination(dest)}
                      >
                        Edit
                      </button>
                      <button
                        className="btn btn-danger"
                        onClick={() => handleDeleteDestination(dest.name)}
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {(!destinations || destinations.length === 0) && (
                <tr>
                  <td colSpan={6} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                    No destinations configured
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {selectedDest && destDetail && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Destination: {selectedDest}</h2>
            <button className="btn btn-secondary" onClick={() => setSelectedDest(null)}>
              Close
            </button>
          </div>

          {destDetail.health && (
            <div>
              <h3 style={{ marginBottom: '1rem' }}>Health Statistics</h3>
              <div className="stat-grid">
                <div className="stat-item">
                  <div className="stat-value">{destDetail.health.availabilityPercent.toFixed(1)}%</div>
                  <div className="stat-label">Availability</div>
                </div>
                <div className="stat-item">
                  <div className="stat-value">{destDetail.health.totalChecks}</div>
                  <div className="stat-label">Total Checks</div>
                </div>
                <div className="stat-item">
                  <div className="stat-value">{destDetail.health.successfulChecks}</div>
                  <div className="stat-label">Successful</div>
                </div>
                <div className="stat-item">
                  <div className="stat-value" style={{ color: destDetail.health.consecutiveFailures > 0 ? 'var(--danger-color)' : 'inherit' }}>
                    {destDetail.health.consecutiveFailures}
                  </div>
                  <div className="stat-label">Consecutive Failures</div>
                </div>
              </div>

              <div style={{ marginTop: '1.5rem' }}>
                <p><strong>Last Check:</strong> {destDetail.health.lastCheck || 'Never'}</p>
                <p><strong>Last Available:</strong> {destDetail.health.lastAvailable || 'Never'}</p>
                {destDetail.health.downtimeSeconds > 0 && (
                  <p><strong>Current Downtime:</strong> {Math.round(destDetail.health.downtimeSeconds / 60)} minutes</p>
                )}
              </div>
            </div>
          )}

          {destDetail.config && (
            <div style={{ marginTop: '1.5rem' }}>
              <h3 style={{ marginBottom: '1rem' }}>Configuration</h3>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem' }}>
                {Object.entries(destDetail.config).map(([key, value]) => (
                  <div key={key}>
                    <strong>{key}:</strong> {key.toLowerCase().includes('password') ? '********' : String(value)}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
