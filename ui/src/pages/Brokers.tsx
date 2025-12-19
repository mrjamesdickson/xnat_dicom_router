import { useState } from 'react'
import { useFetch, apiGet, apiPost, apiPut, apiDelete } from '../hooks/useApi'

interface Broker {
  name: string
  description: string
  enabled: boolean
  type: string
  namingScheme: string
  crosswalk?: {
    totalMappings: number
  }
}

interface BrokerDetail {
  name: string
  description: string
  enabled: boolean
  type: string
  connection: {
    stsHost: string
    apiHost: string
    timeout: number
  }
  auth: {
    appName: string
    username: string
    hasAppKey: boolean
    hasPassword: boolean
  }
  behavior: {
    cacheEnabled: boolean
    cacheTtlSeconds: number
    cacheMaxSize: number
    replacePatientId: boolean
    replacePatientName: boolean
    patientIdPrefix: string
    patientNamePrefix: string
    namingScheme: string
    lookupScript: string
  }
  dateShift?: {
    enabled: boolean
    minDays: number
    maxDays: number
  }
  hashUidsEnabled?: boolean
  crosswalk?: {
    totalMappings: number
  }
}

interface CrosswalkEntry {
  id: number
  brokerName: string
  idIn: string
  idOut: string
  idType: string
  createdAt: string
  updatedAt: string
}

interface CrosswalkResponse {
  broker: string
  totalMappings: number
  mappings: CrosswalkEntry[]
}

interface BrokerFormData {
  name: string
  description: string
  enabled: boolean
  broker_type: string
  naming_scheme: string
  patient_id_prefix: string
  patient_name_prefix: string
  cache_enabled: boolean
  cache_ttl_seconds: number
  cache_max_size: number
  replace_patient_id: boolean
  replace_patient_name: boolean
  lookup_script: string
  // Remote broker fields
  sts_host: string
  api_host: string
  app_name: string
  app_key: string
  username: string
  password: string
  timeout: number
  // Date shift fields
  date_shift_enabled: boolean
  date_shift_min_days: number
  date_shift_max_days: number
  // UID hashing
  hash_uids_enabled: boolean
}

const defaultBrokerForm: BrokerFormData = {
  name: '',
  description: '',
  enabled: true,
  broker_type: 'local',
  naming_scheme: 'adjective_animal',
  patient_id_prefix: 'SUBJ',
  patient_name_prefix: '',
  cache_enabled: true,
  cache_ttl_seconds: 3600,
  cache_max_size: 10000,
  replace_patient_id: true,
  replace_patient_name: true,
  lookup_script: '',
  // Remote broker defaults
  sts_host: '',
  api_host: '',
  app_name: '',
  app_key: '',
  username: '',
  password: '',
  timeout: 30,
  // Date shift defaults
  date_shift_enabled: false,
  date_shift_min_days: -365,
  date_shift_max_days: 365,
  // UID hashing default
  hash_uids_enabled: false
}

const NAMING_SCHEMES = [
  { value: 'adjective_animal', label: 'Adjective-Animal', example: 'SUBJ-PEACEFUL-PEACOCK' },
  { value: 'color_animal', label: 'Color-Animal', example: 'SUBJ-AZURE-FALCON' },
  { value: 'nato_phonetic', label: 'NATO Phonetic', example: 'SUBJ-ALPHA-BRAVO-CHARLIE' },
  { value: 'sequential', label: 'Sequential', example: 'SUBJ-000001' },
  { value: 'hash', label: 'Hash-based', example: 'SUBJ-A7F3B2C1' },
  { value: 'script', label: 'Custom Script', example: 'Your custom logic' }
]

const DEFAULT_LOOKUP_SCRIPT = `// Custom lookup function for de-identification
// Parameters:
//   idIn - Original patient ID
//   idType - "patient_id", "patient_name", or "accession"
//   prefix - Configured prefix (e.g., "SUBJ")
//   context - { brokerName, mappingCount }
//
// Return: The de-identified ID string

function lookup(idIn, idType, prefix, context) {
  // Example: Use first 4 chars with prefix and sequential number
  var shortId = idIn.substring(0, Math.min(4, idIn.length)).toUpperCase();
  var seq = context.mappingCount + 1;
  return prefix + "-" + shortId + "-" + String(seq).padStart(4, "0");
}`

export default function Brokers() {
  const { data: brokers, loading, error, refetch } = useFetch<Broker[]>('/brokers')
  const [selectedBroker, setSelectedBroker] = useState<string | null>(null)
  const { data: brokerDetail, refetch: refetchDetail } = useFetch<BrokerDetail>(
    selectedBroker ? `/brokers/${selectedBroker}` : ''
  )
  const { data: crosswalkData, refetch: refetchCrosswalk } = useFetch<CrosswalkResponse>(
    selectedBroker ? `/brokers/${selectedBroker}/crosswalk?limit=${showAllMappings ? 10000 : 100}` : ''
  )

  // Form states
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [editingBroker, setEditingBroker] = useState<string | null>(null)
  const [brokerForm, setBrokerForm] = useState<BrokerFormData>(defaultBrokerForm)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  // Test lookup states
  const [testInput, setTestInput] = useState('')
  const [testResult, setTestResult] = useState<{ idIn: string; idOut: string } | null>(null)
  const [testError, setTestError] = useState<string | null>(null)
  const [testing, setTesting] = useState(false)

  // Full mapping view states
  const [showAllMappings, setShowAllMappings] = useState(false)
  const [exporting, setExporting] = useState(false)

  const handleCreateBroker = () => {
    setBrokerForm(defaultBrokerForm)
    setEditingBroker(null)
    setShowCreateForm(true)
    setFormError(null)
  }

  const handleEditBroker = (broker: BrokerDetail) => {
    setBrokerForm({
      name: broker.name,
      description: broker.description || '',
      enabled: broker.enabled,
      broker_type: broker.type,
      naming_scheme: broker.behavior?.namingScheme || 'adjective_animal',
      patient_id_prefix: broker.behavior?.patientIdPrefix || 'SUBJ',
      patient_name_prefix: broker.behavior?.patientNamePrefix || '',
      cache_enabled: broker.behavior?.cacheEnabled ?? true,
      cache_ttl_seconds: broker.behavior?.cacheTtlSeconds || 3600,
      cache_max_size: broker.behavior?.cacheMaxSize || 10000,
      replace_patient_id: broker.behavior?.replacePatientId ?? true,
      replace_patient_name: broker.behavior?.replacePatientName ?? true,
      lookup_script: broker.behavior?.lookupScript || '',
      // Remote broker fields
      sts_host: broker.connection?.stsHost || '',
      api_host: broker.connection?.apiHost || '',
      app_name: broker.auth?.appName || '',
      app_key: '', // Don't pre-fill password fields
      username: broker.auth?.username || '',
      password: '', // Don't pre-fill password fields
      timeout: broker.connection?.timeout || 30,
      // Date shift fields
      date_shift_enabled: broker.dateShift?.enabled ?? false,
      date_shift_min_days: broker.dateShift?.minDays ?? -365,
      date_shift_max_days: broker.dateShift?.maxDays ?? 365,
      // UID hashing
      hash_uids_enabled: broker.hashUidsEnabled ?? false
    })
    setEditingBroker(broker.name)
    setShowCreateForm(true)
    setFormError(null)
  }

  const handleSaveBroker = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setFormError(null)

    try {
      if (editingBroker) {
        await apiPut(`/brokers/${editingBroker}`, brokerForm)
      } else {
        await apiPost('/brokers', brokerForm)
      }
      setShowCreateForm(false)
      refetch()
      if (selectedBroker) {
        refetchDetail()
      }
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Failed to save broker')
    } finally {
      setSaving(false)
    }
  }

  const handleDeleteBroker = async (name: string) => {
    if (!confirm(`Delete broker "${name}"? This will remove all crosswalk mappings!`)) {
      return
    }

    try {
      await apiDelete(`/brokers/${name}`)
      if (selectedBroker === name) {
        setSelectedBroker(null)
      }
      refetch()
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete broker')
    }
  }

  const handleTestLookup = async () => {
    if (!selectedBroker || !testInput.trim()) return

    setTesting(true)
    setTestError(null)
    setTestResult(null)

    try {
      const data = await apiGet<{ success: boolean; idIn: string; idOut: string; error?: string }>(
        `/brokers/${selectedBroker}/lookup?idIn=${encodeURIComponent(testInput.trim())}`
      )
      if (data.success) {
        setTestResult({ idIn: data.idIn, idOut: data.idOut })
        refetchDetail() // Refresh to update mapping count
      } else {
        setTestError(data.error || 'Lookup failed')
      }
    } catch (err) {
      setTestError(err instanceof Error ? err.message : 'Lookup failed')
    } finally {
      setTesting(false)
    }
  }

  const handleExportCsv = async () => {
    setExporting(true)
    try {
      // Fetch CSV from API
      const response = await fetch('/api/brokers/crosswalk/export', {
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('authToken') || ''}`
        }
      })
      if (!response.ok) {
        throw new Error('Failed to export CSV')
      }
      const csvContent = await response.text()

      // Create download link
      const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `crosswalk_export_${new Date().toISOString().split('T')[0]}.csv`
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      URL.revokeObjectURL(url)
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to export CSV')
    } finally {
      setExporting(false)
    }
  }

  if (loading) {
    return <div className="loading"><div className="spinner" /></div>
  }

  if (error) {
    return <div className="error-message">Failed to load brokers: {error}</div>
  }

  return (
    <div>
      {/* Create/Edit Broker Modal */}
      {showCreateForm && (
        <div className="modal-overlay" onClick={() => setShowCreateForm(false)}>
          <div className="modal" onClick={e => e.stopPropagation()} style={{ maxWidth: '700px' }}>
            <div className="modal-header">
              <h2>{editingBroker ? 'Edit Broker' : 'Create Honest Broker'}</h2>
              <button className="btn btn-secondary" onClick={() => setShowCreateForm(false)}>
                Cancel
              </button>
            </div>
            <form onSubmit={handleSaveBroker}>
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
                    value={brokerForm.name}
                    onChange={e => setBrokerForm({ ...brokerForm, name: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '-') })}
                    required
                    disabled={!!editingBroker}
                    placeholder="my-broker"
                  />
                  <small>Lowercase letters, numbers, and hyphens only</small>
                </div>

                <div className="form-group">
                  <label>Type</label>
                  <select
                    value={brokerForm.broker_type}
                    onChange={e => setBrokerForm({ ...brokerForm, broker_type: e.target.value })}
                  >
                    <option value="local">Local (generates IDs locally)</option>
                    <option value="remote">Remote (external API)</option>
                  </select>
                </div>

                <div className="form-group" style={{ gridColumn: 'span 2' }}>
                  <label>Description</label>
                  <input
                    type="text"
                    value={brokerForm.description}
                    onChange={e => setBrokerForm({ ...brokerForm, description: e.target.value })}
                    placeholder="Optional description"
                  />
                </div>

                {brokerForm.broker_type === 'remote' && (
                  <>
                    <div className="form-group" style={{ gridColumn: 'span 2' }}>
                      <h4 style={{ margin: '0 0 0.5rem 0', color: 'var(--primary)' }}>Remote API Configuration</h4>
                    </div>

                    <div className="form-group">
                      <label>API Host *</label>
                      <input
                        type="text"
                        value={brokerForm.api_host}
                        onChange={e => setBrokerForm({ ...brokerForm, api_host: e.target.value })}
                        placeholder="api.example.com"
                        required={brokerForm.broker_type === 'remote'}
                      />
                      <small>De-identification lookup API host</small>
                    </div>

                    <div className="form-group">
                      <label>STS Host</label>
                      <input
                        type="text"
                        value={brokerForm.sts_host}
                        onChange={e => setBrokerForm({ ...brokerForm, sts_host: e.target.value })}
                        placeholder="sts.example.com"
                      />
                      <small>Security Token Service host (for auth)</small>
                    </div>

                    <div className="form-group">
                      <label>App Name</label>
                      <input
                        type="text"
                        value={brokerForm.app_name}
                        onChange={e => setBrokerForm({ ...brokerForm, app_name: e.target.value })}
                        placeholder="MyApp"
                      />
                    </div>

                    <div className="form-group">
                      <label>App Key</label>
                      <input
                        type="password"
                        value={brokerForm.app_key}
                        onChange={e => setBrokerForm({ ...brokerForm, app_key: e.target.value })}
                        placeholder="Enter app key"
                      />
                    </div>

                    <div className="form-group">
                      <label>Username</label>
                      <input
                        type="text"
                        value={brokerForm.username}
                        onChange={e => setBrokerForm({ ...brokerForm, username: e.target.value })}
                        placeholder="Username"
                      />
                    </div>

                    <div className="form-group">
                      <label>Password</label>
                      <input
                        type="password"
                        value={brokerForm.password}
                        onChange={e => setBrokerForm({ ...brokerForm, password: e.target.value })}
                        placeholder="Password"
                      />
                    </div>

                    <div className="form-group">
                      <label>Timeout (seconds)</label>
                      <input
                        type="number"
                        value={brokerForm.timeout}
                        onChange={e => setBrokerForm({ ...brokerForm, timeout: parseInt(e.target.value) || 30 })}
                        min={1}
                        max={300}
                      />
                    </div>
                  </>
                )}

                {brokerForm.broker_type === 'local' && (
                  <>
                    <div className="form-group" style={{ gridColumn: 'span 2' }}>
                      <label>Naming Scheme</label>
                      <select
                        value={brokerForm.naming_scheme}
                        onChange={e => {
                          const newScheme = e.target.value
                          setBrokerForm({
                            ...brokerForm,
                            naming_scheme: newScheme,
                            // Set default script when switching to script mode
                            lookup_script: newScheme === 'script' && !brokerForm.lookup_script
                              ? DEFAULT_LOOKUP_SCRIPT
                              : brokerForm.lookup_script
                          })
                        }}
                      >
                        {NAMING_SCHEMES.map(scheme => (
                          <option key={scheme.value} value={scheme.value}>
                            {scheme.label} (e.g., {scheme.example})
                          </option>
                        ))}
                      </select>
                    </div>

                    {brokerForm.naming_scheme === 'script' && (
                      <div className="form-group" style={{ gridColumn: 'span 2' }}>
                        <label>Lookup Script (JavaScript)</label>
                        <textarea
                          value={brokerForm.lookup_script}
                          onChange={e => setBrokerForm({ ...brokerForm, lookup_script: e.target.value })}
                          placeholder="function lookup(idIn, idType, prefix, context) { ... }"
                          style={{
                            fontFamily: 'monospace',
                            fontSize: '13px',
                            lineHeight: '1.4',
                            minHeight: '300px',
                            resize: 'vertical',
                            backgroundColor: '#1e1e1e',
                            color: '#d4d4d4',
                            border: '1px solid #3c3c3c',
                            borderRadius: '4px',
                            padding: '12px'
                          }}
                        />
                        <small style={{ display: 'block', marginTop: '0.5rem', color: 'var(--text-light)' }}>
                          Define a <code>lookup(idIn, idType, prefix, context)</code> function that returns the de-identified ID.
                          Available context: brokerName, mappingCount.
                        </small>
                      </div>
                    )}

                    <div className="form-group">
                      <label>Patient ID Prefix</label>
                      <input
                        type="text"
                        value={brokerForm.patient_id_prefix}
                        onChange={e => setBrokerForm({ ...brokerForm, patient_id_prefix: e.target.value })}
                        placeholder="SUBJ"
                      />
                    </div>

                    <div className="form-group">
                      <label>Patient Name Prefix</label>
                      <input
                        type="text"
                        value={brokerForm.patient_name_prefix}
                        onChange={e => setBrokerForm({ ...brokerForm, patient_name_prefix: e.target.value })}
                        placeholder="ANON"
                      />
                    </div>
                  </>
                )}

                <div className="form-group checkbox-group">
                  <label>
                    <input
                      type="checkbox"
                      checked={brokerForm.replace_patient_id}
                      onChange={e => setBrokerForm({ ...brokerForm, replace_patient_id: e.target.checked })}
                    />
                    Replace Patient ID
                  </label>
                </div>

                <div className="form-group checkbox-group">
                  <label>
                    <input
                      type="checkbox"
                      checked={brokerForm.replace_patient_name}
                      onChange={e => setBrokerForm({ ...brokerForm, replace_patient_name: e.target.checked })}
                    />
                    Replace Patient Name
                  </label>
                </div>

                <div className="form-group checkbox-group">
                  <label>
                    <input
                      type="checkbox"
                      checked={brokerForm.cache_enabled}
                      onChange={e => setBrokerForm({ ...brokerForm, cache_enabled: e.target.checked })}
                    />
                    Enable Caching
                  </label>
                </div>

                {brokerForm.broker_type === 'local' && (
                  <>
                    <h4 style={{ marginTop: '20px', marginBottom: '10px', borderBottom: '1px solid #ddd', paddingBottom: '5px' }}>
                      Date Shifting
                    </h4>

                    <div className="form-group checkbox-group">
                      <label>
                        <input
                          type="checkbox"
                          checked={brokerForm.date_shift_enabled}
                          onChange={e => setBrokerForm({ ...brokerForm, date_shift_enabled: e.target.checked })}
                        />
                        Enable Date Shifting
                      </label>
                      <small className="form-help">Shift dates by a random offset (consistent per patient, stored in crosswalk)</small>
                    </div>

                    {brokerForm.date_shift_enabled && (
                      <div className="form-row" style={{ display: 'flex', gap: '20px' }}>
                        <div className="form-group" style={{ flex: 1 }}>
                          <label>Min Days</label>
                          <input
                            type="number"
                            value={brokerForm.date_shift_min_days}
                            onChange={e => setBrokerForm({ ...brokerForm, date_shift_min_days: parseInt(e.target.value) || -365 })}
                          />
                          <small className="form-help">e.g. -365 for up to 1 year back</small>
                        </div>
                        <div className="form-group" style={{ flex: 1 }}>
                          <label>Max Days</label>
                          <input
                            type="number"
                            value={brokerForm.date_shift_max_days}
                            onChange={e => setBrokerForm({ ...brokerForm, date_shift_max_days: parseInt(e.target.value) || 365 })}
                          />
                          <small className="form-help">e.g. 365 for up to 1 year forward</small>
                        </div>
                      </div>
                    )}

                    <h4 style={{ marginTop: '20px', marginBottom: '10px', borderBottom: '1px solid #ddd', paddingBottom: '5px' }}>
                      UID Hashing
                    </h4>

                    <div className="form-group checkbox-group">
                      <label>
                        <input
                          type="checkbox"
                          checked={brokerForm.hash_uids_enabled}
                          onChange={e => setBrokerForm({ ...brokerForm, hash_uids_enabled: e.target.checked })}
                        />
                        Store UID Mappings in Crosswalk
                      </label>
                      <small className="form-help">When UIDs are hashed, store the mapping for audit and traceability</small>
                    </div>
                  </>
                )}

                <div className="form-group checkbox-group">
                  <label>
                    <input
                      type="checkbox"
                      checked={brokerForm.enabled}
                      onChange={e => setBrokerForm({ ...brokerForm, enabled: e.target.checked })}
                    />
                    Enabled
                  </label>
                </div>
              </div>

              <div className="form-actions">
                <button type="submit" className="btn btn-primary" disabled={saving}>
                  {saving ? 'Saving...' : 'Save Broker'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Info Card */}
      <div className="card" style={{ marginBottom: '1.5rem', background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)', color: 'white' }}>
        <h2 style={{ marginTop: 0 }}>Honest Broker De-Identification</h2>
        <p style={{ marginBottom: '1rem', opacity: 0.95 }}>
          An Honest Broker maps original patient identifiers (PatientID, PatientName) to de-identified values,
          enabling data sharing while protecting patient privacy. All mappings are stored in a crosswalk database
          for audit purposes and consistent re-identification if needed.
        </p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem' }}>
          <div>
            <strong>Local Broker:</strong><br/>
            <span style={{ opacity: 0.9 }}>Generates de-identified IDs locally using configurable naming schemes</span>
          </div>
          <div>
            <strong>Remote Broker:</strong><br/>
            <span style={{ opacity: 0.9 }}>Connects to external API with STS authentication</span>
          </div>
        </div>
      </div>

      {/* Brokers List */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Configured Brokers</h2>
          <button className="btn btn-primary" onClick={handleCreateBroker}>
            + New Broker
          </button>
        </div>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Description</th>
                <th>Type</th>
                <th>Naming Scheme</th>
                <th>Mappings</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {brokers?.map(broker => (
                <tr key={broker.name}>
                  <td><strong>{broker.name}</strong></td>
                  <td>{broker.description || '-'}</td>
                  <td>
                    <span className={`status-badge ${broker.type === 'local' ? 'status-up' : 'status-pending'}`}>
                      {broker.type}
                    </span>
                  </td>
                  <td>{broker.namingScheme || '-'}</td>
                  <td>{broker.crosswalk?.totalMappings || 0}</td>
                  <td>
                    <span className={`status-badge status-${broker.enabled ? 'up' : 'down'}`}>
                      {broker.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      <button
                        className="btn btn-secondary"
                        onClick={() => setSelectedBroker(broker.name)}
                      >
                        Details
                      </button>
                      <button
                        className="btn btn-danger"
                        onClick={() => handleDeleteBroker(broker.name)}
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {(!brokers || brokers.length === 0) && (
                <tr>
                  <td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                    No brokers configured. Click "New Broker" to create one.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Broker Details */}
      {selectedBroker && brokerDetail && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Broker: {brokerDetail.name}</h2>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button className="btn btn-primary" onClick={() => handleEditBroker(brokerDetail)}>
                Edit
              </button>
              <button className="btn btn-secondary" onClick={() => setSelectedBroker(null)}>
                Close
              </button>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
            <div><strong>Type:</strong> {brokerDetail.type}</div>
            <div><strong>Description:</strong> {brokerDetail.description || '-'}</div>
            <div><strong>Naming Scheme:</strong> {brokerDetail.behavior.namingScheme}</div>
            <div><strong>Patient ID Prefix:</strong> {brokerDetail.behavior.patientIdPrefix || '-'}</div>
            <div><strong>Total Mappings:</strong> {brokerDetail.crosswalk?.totalMappings || 0}</div>
            <div><strong>Cache:</strong> {brokerDetail.behavior.cacheEnabled ? 'Enabled' : 'Disabled'}</div>
          </div>

          {/* Test Lookup */}
          <div style={{
            background: 'var(--bg-secondary)',
            padding: '1rem',
            borderRadius: '8px',
            marginBottom: '1.5rem'
          }}>
            <h3 style={{ marginTop: 0, marginBottom: '1rem' }}>Test Lookup</h3>
            <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-end' }}>
              <div style={{ flex: 1 }}>
                <label style={{ display: 'block', marginBottom: '0.25rem', fontSize: '0.9em' }}>
                  Original Patient ID
                </label>
                <input
                  type="text"
                  value={testInput}
                  onChange={e => setTestInput(e.target.value)}
                  placeholder="e.g., PATIENT001"
                  style={{ width: '100%' }}
                />
              </div>
              <button
                className="btn btn-primary"
                onClick={handleTestLookup}
                disabled={testing || !testInput.trim()}
              >
                {testing ? 'Looking up...' : 'Lookup'}
              </button>
            </div>
            {testResult && (
              <div style={{ marginTop: '1rem', padding: '0.75rem', background: 'var(--success-bg, #e8f5e9)', borderRadius: '4px' }}>
                <strong>{testResult.idIn}</strong> &rarr; <strong style={{ color: 'var(--success, #4caf50)' }}>{testResult.idOut}</strong>
              </div>
            )}
            {testError && (
              <div style={{ marginTop: '1rem', padding: '0.75rem', background: 'var(--error-bg, #ffebee)', borderRadius: '4px', color: 'var(--error, #f44336)' }}>
                {testError}
              </div>
            )}
          </div>

          {/* Crosswalk Mappings */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
            <h3 style={{ margin: 0 }}>Crosswalk Mappings</h3>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button
                className="btn btn-secondary"
                onClick={() => setShowAllMappings(!showAllMappings)}
              >
                {showAllMappings ? 'Show Recent' : `View All (${crosswalkData?.totalMappings || 0})`}
              </button>
              <button
                className="btn btn-primary"
                onClick={handleExportCsv}
                disabled={exporting}
              >
                {exporting ? 'Exporting...' : 'Export CSV'}
              </button>
            </div>
          </div>
          <div className="table-container" style={{ maxHeight: showAllMappings ? '600px' : 'auto', overflowY: showAllMappings ? 'auto' : 'visible' }}>
            <table>
              <thead>
                <tr>
                  <th>Original ID</th>
                  <th>De-identified ID</th>
                  <th>Type</th>
                  <th>Created</th>
                </tr>
              </thead>
              <tbody>
                {(showAllMappings ? crosswalkData?.mappings : crosswalkData?.mappings?.slice(0, 20))?.map((entry, i) => (
                  <tr key={i}>
                    <td><code>{entry.idIn}</code></td>
                    <td><code style={{ color: 'var(--primary)' }}>{entry.idOut}</code></td>
                    <td>
                      <span className={`status-badge ${entry.idType === 'patient_id' ? 'status-up' : entry.idType === 'session_id' ? 'status-pending' : 'status-down'}`}>
                        {entry.idType}
                      </span>
                    </td>
                    <td>{new Date(entry.createdAt).toLocaleString()}</td>
                  </tr>
                ))}
                {(!crosswalkData?.mappings || crosswalkData.mappings.length === 0) && (
                  <tr>
                    <td colSpan={4} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                      No mappings yet. Use "Test Lookup" above to create one.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          {!showAllMappings && crosswalkData && crosswalkData.mappings && crosswalkData.mappings.length > 20 && (
            <p style={{ color: 'var(--text-light)', fontSize: '0.9em', marginTop: '0.5rem' }}>
              Showing first 20 of {crosswalkData.totalMappings} mappings. Click "View All" to see complete list.
            </p>
          )}
        </div>
      )}
    </div>
  )
}
