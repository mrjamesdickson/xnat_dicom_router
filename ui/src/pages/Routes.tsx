import { useState } from 'react'
import { useFetch, apiPost, apiPut, apiDelete } from '../hooks/useApi'

interface Route {
  aeTitle: string
  port: number
  description: string
  enabled: boolean
  workerThreads: number
  destinationCount: number
}

interface RouteDestination {
  destination: string
  enabled: boolean
  anonymize: boolean
  anonScript: string | null
  projectId: string | null
  subjectPrefix: string
  sessionPrefix: string
  priority: number
  retryCount: number
  retryDelaySeconds: number
  useHonestBroker: boolean
  honestBroker: string | null
}

interface HonestBroker {
  name: string
  description: string
  enabled: boolean
  type: string
  apiHost: string
}

interface RouteDetail extends Route {
  maxConcurrentTransfers: number
  studyTimeoutSeconds: number
  rateLimitPerMinute: number
  webhookUrl: string | null
  webhookEvents: string[]
  routingRules: Array<{
    name: string
    description: string
    tag: string
    operator: string
    value: string
    values: string[]
    destinations: string[]
  }>
  validationRules: Array<{
    name: string
    type: string
    tag: string
    onFailure: string
  }>
  filters: Array<{
    name: string
    tag: string
    operator: string
    value: string
    action: string
  }>
  destinations: RouteDestination[]
}

interface DestinationOption {
  name: string
  type: string
  url?: string
}

interface RouteFormData {
  aeTitle: string
  port: number
  description: string
  enabled: boolean
  workerThreads: number
  maxConcurrentTransfers: number
  studyTimeoutSeconds: number
  rateLimitPerMinute: number
}

const defaultRouteForm: RouteFormData = {
  aeTitle: '',
  port: 11112,
  description: '',
  enabled: true,
  workerThreads: 4,
  maxConcurrentTransfers: 2,
  studyTimeoutSeconds: 300,
  rateLimitPerMinute: 0
}

interface RouteDestFormData {
  destination: string
  enabled: boolean
  anonymize: boolean
  anonScript: string
  projectId: string
  subjectPrefix: string
  sessionPrefix: string
  priority: number
  retryCount: number
  retryDelaySeconds: number
  useHonestBroker: boolean
  honestBroker: string
}

const defaultRouteDestForm: RouteDestFormData = {
  destination: '',
  enabled: true,
  anonymize: false,
  anonScript: '',
  projectId: '',
  subjectPrefix: '',
  sessionPrefix: '',
  priority: 0,
  retryCount: 3,
  retryDelaySeconds: 60,
  useHonestBroker: false,
  honestBroker: ''
}

export default function Routes_Page() {
  const { data: routes, loading, error, refetch } = useFetch<Route[]>('/routes')
  const { data: availableDestinations } = useFetch<DestinationOption[]>('/destinations')
  const { data: availableBrokers } = useFetch<HonestBroker[]>('/brokers')
  const [selectedRoute, setSelectedRoute] = useState<string | null>(null)
  const { data: routeDetail, refetch: refetchDetail } = useFetch<RouteDetail>(
    selectedRoute ? `/routes/${selectedRoute}` : '',
  )

  // Form states
  const [showCreateForm, setShowCreateForm] = useState(false)
  const [editingRoute, setEditingRoute] = useState<string | null>(null)
  const [routeForm, setRouteForm] = useState<RouteFormData>(defaultRouteForm)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)

  // Destination form states
  const [showDestForm, setShowDestForm] = useState(false)
  const [editingDest, setEditingDest] = useState<string | null>(null)
  const [destForm, setDestForm] = useState<RouteDestFormData>(defaultRouteDestForm)

  const handleCreateRoute = () => {
    setRouteForm(defaultRouteForm)
    setEditingRoute(null)
    setShowCreateForm(true)
    setFormError(null)
  }

  const handleEditRoute = (route: RouteDetail) => {
    setRouteForm({
      aeTitle: route.aeTitle,
      port: route.port,
      description: route.description || '',
      enabled: route.enabled,
      workerThreads: route.workerThreads,
      maxConcurrentTransfers: route.maxConcurrentTransfers,
      studyTimeoutSeconds: route.studyTimeoutSeconds,
      rateLimitPerMinute: route.rateLimitPerMinute || 0
    })
    setEditingRoute(route.aeTitle)
    setShowCreateForm(true)
    setFormError(null)
  }

  const handleSaveRoute = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setFormError(null)

    try {
      if (editingRoute) {
        await apiPut(`/routes/${editingRoute}`, routeForm)
      } else {
        await apiPost('/routes', routeForm)
      }
      setShowCreateForm(false)
      refetch()
      if (selectedRoute) {
        refetchDetail()
      }
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Failed to save route')
    } finally {
      setSaving(false)
    }
  }

  const handleDeleteRoute = async (aeTitle: string) => {
    if (!confirm(`Delete route "${aeTitle}"? This cannot be undone.`)) {
      return
    }

    try {
      await apiDelete(`/routes/${aeTitle}`)
      if (selectedRoute === aeTitle) {
        setSelectedRoute(null)
      }
      refetch()
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete route')
    }
  }

  const handleAddDestination = () => {
    setDestForm(defaultRouteDestForm)
    setEditingDest(null)
    setShowDestForm(true)
    setFormError(null)
  }

  const handleEditDestination = (dest: RouteDestination) => {
    setDestForm({
      destination: dest.destination,
      enabled: dest.enabled,
      anonymize: dest.anonymize,
      anonScript: dest.anonScript || '',
      projectId: dest.projectId || '',
      subjectPrefix: dest.subjectPrefix || '',
      sessionPrefix: dest.sessionPrefix || '',
      priority: dest.priority,
      retryCount: dest.retryCount,
      retryDelaySeconds: dest.retryDelaySeconds,
      useHonestBroker: dest.useHonestBroker || false,
      honestBroker: dest.honestBroker || ''
    })
    setEditingDest(dest.destination)
    setShowDestForm(true)
    setFormError(null)
  }

  const handleSaveDestination = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!selectedRoute) return

    setSaving(true)
    setFormError(null)

    try {
      if (editingDest) {
        await apiPut(`/routes/${selectedRoute}/destinations/${editingDest}`, destForm)
      } else {
        await apiPost(`/routes/${selectedRoute}/destinations`, destForm)
      }
      setShowDestForm(false)
      refetchDetail()
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Failed to save destination')
    } finally {
      setSaving(false)
    }
  }

  const handleRemoveDestination = async (destName: string) => {
    if (!selectedRoute) return
    if (!confirm(`Remove destination "${destName}" from this route?`)) {
      return
    }

    try {
      await apiDelete(`/routes/${selectedRoute}/destinations/${destName}`)
      refetchDetail()
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to remove destination')
    }
  }

  // Create a lookup map for destination info (type and URL)
  const destinationMap = (availableDestinations || []).reduce((acc, d) => {
    acc[d.name] = d
    return acc
  }, {} as Record<string, DestinationOption>)

  if (loading) {
    return <div className="loading"><div className="spinner" /></div>
  }

  if (error) {
    return <div className="error-message">Failed to load routes: {error}</div>
  }

  return (
    <div>
      {/* Create/Edit Route Modal */}
      {showCreateForm && (
        <div className="modal-overlay" onClick={() => setShowCreateForm(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>{editingRoute ? 'Edit Route' : 'Create Route'}</h2>
              <button className="btn btn-secondary" onClick={() => setShowCreateForm(false)}>
                Cancel
              </button>
            </div>
            <form onSubmit={handleSaveRoute}>
              {formError && (
                <div className="error-message" style={{ marginBottom: '1rem' }}>
                  {formError}
                </div>
              )}

              <div className="form-grid">
                <div className="form-group">
                  <label>AE Title *</label>
                  <input
                    type="text"
                    value={routeForm.aeTitle}
                    onChange={e => setRouteForm({ ...routeForm, aeTitle: e.target.value.toUpperCase() })}
                    required
                    maxLength={16}
                    pattern="[A-Z0-9_]+"
                    disabled={!!editingRoute}
                    placeholder="ROUTER_AE"
                  />
                  <small>Up to 16 characters, uppercase letters, numbers, and underscores only</small>
                </div>

                <div className="form-group">
                  <label>Port *</label>
                  <input
                    type="number"
                    value={routeForm.port}
                    onChange={e => setRouteForm({ ...routeForm, port: parseInt(e.target.value) || 11112 })}
                    required
                    min={1024}
                    max={65535}
                  />
                </div>

                <div className="form-group" style={{ gridColumn: 'span 2' }}>
                  <label>Description</label>
                  <input
                    type="text"
                    value={routeForm.description}
                    onChange={e => setRouteForm({ ...routeForm, description: e.target.value })}
                    placeholder="Optional description"
                  />
                </div>

                <div className="form-group">
                  <label>Worker Threads</label>
                  <input
                    type="number"
                    value={routeForm.workerThreads}
                    onChange={e => setRouteForm({ ...routeForm, workerThreads: parseInt(e.target.value) || 4 })}
                    min={1}
                    max={32}
                  />
                </div>

                <div className="form-group">
                  <label>Max Concurrent Transfers</label>
                  <input
                    type="number"
                    value={routeForm.maxConcurrentTransfers}
                    onChange={e => setRouteForm({ ...routeForm, maxConcurrentTransfers: parseInt(e.target.value) || 2 })}
                    min={1}
                    max={16}
                  />
                </div>

                <div className="form-group">
                  <label>Study Timeout (seconds)</label>
                  <input
                    type="number"
                    value={routeForm.studyTimeoutSeconds}
                    onChange={e => setRouteForm({ ...routeForm, studyTimeoutSeconds: parseInt(e.target.value) || 300 })}
                    min={60}
                  />
                </div>

                <div className="form-group">
                  <label>Rate Limit (per minute)</label>
                  <input
                    type="number"
                    value={routeForm.rateLimitPerMinute}
                    onChange={e => setRouteForm({ ...routeForm, rateLimitPerMinute: parseInt(e.target.value) || 0 })}
                    min={0}
                  />
                  <small>0 = no limit</small>
                </div>

                <div className="form-group checkbox-group">
                  <label>
                    <input
                      type="checkbox"
                      checked={routeForm.enabled}
                      onChange={e => setRouteForm({ ...routeForm, enabled: e.target.checked })}
                    />
                    Enabled
                  </label>
                </div>
              </div>

              <div className="form-actions">
                <button type="submit" className="btn btn-primary" disabled={saving}>
                  {saving ? 'Saving...' : 'Save Route'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Add/Edit Destination Modal */}
      {showDestForm && selectedRoute && (
        <div className="modal-overlay" onClick={() => setShowDestForm(false)}>
          <div className="modal" onClick={e => e.stopPropagation()}>
            <div className="modal-header">
              <h2>{editingDest ? 'Edit Destination' : 'Add Destination'}</h2>
              <button className="btn btn-secondary" onClick={() => setShowDestForm(false)}>
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
                <div className="form-group" style={{ gridColumn: 'span 2' }}>
                  <label>Destination *</label>
                  <select
                    value={destForm.destination}
                    onChange={e => setDestForm({ ...destForm, destination: e.target.value })}
                    required
                    disabled={!!editingDest}
                  >
                    <option value="">Select a destination...</option>
                    {availableDestinations?.map(d => (
                      <option key={d.name} value={d.name}>
                        {d.name} ({d.type})
                      </option>
                    ))}
                  </select>
                </div>

                <div className="form-group">
                  <label>Project ID</label>
                  <input
                    type="text"
                    value={destForm.projectId}
                    onChange={e => setDestForm({ ...destForm, projectId: e.target.value })}
                    placeholder="XNAT project ID"
                  />
                </div>

                <div className="form-group">
                  <label>Priority</label>
                  <input
                    type="number"
                    value={destForm.priority}
                    onChange={e => setDestForm({ ...destForm, priority: parseInt(e.target.value) || 0 })}
                    min={0}
                  />
                  <small>Higher = processed first</small>
                </div>

                <div className="form-group">
                  <label>Subject Prefix</label>
                  <input
                    type="text"
                    value={destForm.subjectPrefix}
                    onChange={e => setDestForm({ ...destForm, subjectPrefix: e.target.value })}
                  />
                </div>

                <div className="form-group">
                  <label>Session Prefix</label>
                  <input
                    type="text"
                    value={destForm.sessionPrefix}
                    onChange={e => setDestForm({ ...destForm, sessionPrefix: e.target.value })}
                  />
                </div>

                <div className="form-group">
                  <label>Retry Count</label>
                  <input
                    type="number"
                    value={destForm.retryCount}
                    onChange={e => setDestForm({ ...destForm, retryCount: parseInt(e.target.value) || 3 })}
                    min={0}
                    max={10}
                  />
                </div>

                <div className="form-group">
                  <label>Retry Delay (seconds)</label>
                  <input
                    type="number"
                    value={destForm.retryDelaySeconds}
                    onChange={e => setDestForm({ ...destForm, retryDelaySeconds: parseInt(e.target.value) || 60 })}
                    min={1}
                  />
                </div>

                <div className="form-group checkbox-group">
                  <label>
                    <input
                      type="checkbox"
                      checked={destForm.enabled}
                      onChange={e => setDestForm({ ...destForm, enabled: e.target.checked })}
                    />
                    Enabled
                  </label>
                </div>

                <div className="form-group checkbox-group">
                  <label>
                    <input
                      type="checkbox"
                      checked={destForm.anonymize}
                      onChange={e => setDestForm({ ...destForm, anonymize: e.target.checked })}
                    />
                    Anonymize
                  </label>
                </div>

                {destForm.anonymize && (
                  <div className="form-group" style={{ gridColumn: 'span 2' }}>
                    <label>Anonymization Script</label>
                    <input
                      type="text"
                      value={destForm.anonScript}
                      onChange={e => setDestForm({ ...destForm, anonScript: e.target.value })}
                      placeholder="Script name from library"
                    />
                  </div>
                )}

                <div className="form-group checkbox-group" style={{ gridColumn: 'span 2' }}>
                  <label>
                    <input
                      type="checkbox"
                      checked={destForm.useHonestBroker}
                      onChange={e => setDestForm({ ...destForm, useHonestBroker: e.target.checked })}
                    />
                    Use Honest Broker
                  </label>
                  <small>Replace PatientID/Name with de-identified values</small>
                </div>

                {destForm.useHonestBroker && (
                  <>
                    <div className="form-group">
                      <label>Honest Broker</label>
                      <select
                        value={destForm.honestBroker}
                        onChange={e => setDestForm({ ...destForm, honestBroker: e.target.value })}
                        required={destForm.useHonestBroker}
                      >
                        <option value="">Select a broker...</option>
                        {availableBrokers?.filter(b => b.enabled).map(b => (
                          <option key={b.name} value={b.name}>
                            {b.name} ({b.type})
                          </option>
                        ))}
                      </select>
                    </div>
                    <div style={{
                      gridColumn: 'span 2',
                      background: 'var(--bg-secondary)',
                      padding: '1rem',
                      borderRadius: '8px',
                      fontSize: '0.9em',
                      lineHeight: '1.5'
                    }}>
                      <strong style={{ display: 'block', marginBottom: '0.5rem' }}>How Honest Broker Works:</strong>
                      <ul style={{ margin: 0, paddingLeft: '1.2rem' }}>
                        <li>Original PatientID is mapped to a de-identified ID (e.g., "SUBJ-PEACEFUL-PEACOCK")</li>
                        <li>The same input always produces the same output (deterministic)</li>
                        <li>All mappings are stored in a crosswalk database for audit purposes</li>
                        <li>Reverse lookup is available to recover original IDs if needed</li>
                      </ul>
                      <div style={{ marginTop: '0.75rem', color: 'var(--text-light)' }}>
                        <strong>Naming schemes:</strong> adjective_animal, color_animal, nato_phonetic, sequential, hash
                      </div>
                    </div>
                  </>
                )}
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
          <h2 className="card-title">Configured Routes</h2>
          <button className="btn btn-primary" onClick={handleCreateRoute}>
            + New Route
          </button>
        </div>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>AE Title</th>
                <th>Port</th>
                <th>Description</th>
                <th>Worker Threads</th>
                <th>Destinations</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {routes?.map(route => (
                <tr key={route.aeTitle}>
                  <td><strong>{route.aeTitle}</strong></td>
                  <td>{route.port}</td>
                  <td>{route.description || '-'}</td>
                  <td>{route.workerThreads}</td>
                  <td>{route.destinationCount}</td>
                  <td>
                    <span className={`status-badge status-${route.enabled ? 'up' : 'down'}`}>
                      {route.enabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      <button
                        className="btn btn-secondary"
                        onClick={() => setSelectedRoute(route.aeTitle)}
                      >
                        Details
                      </button>
                      <button
                        className="btn btn-danger"
                        onClick={() => handleDeleteRoute(route.aeTitle)}
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {(!routes || routes.length === 0) && (
                <tr>
                  <td colSpan={7} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                    No routes configured
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {selectedRoute && routeDetail && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Route: {routeDetail.aeTitle}</h2>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <button className="btn btn-primary" onClick={() => handleEditRoute(routeDetail)}>
                Edit
              </button>
              <button className="btn btn-secondary" onClick={() => setSelectedRoute(null)}>
                Close
              </button>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
            <div><strong>Port:</strong> {routeDetail.port}</div>
            <div><strong>Description:</strong> {routeDetail.description || '-'}</div>
            <div><strong>Worker Threads:</strong> {routeDetail.workerThreads}</div>
            <div><strong>Max Concurrent Transfers:</strong> {routeDetail.maxConcurrentTransfers}</div>
            <div><strong>Study Timeout:</strong> {routeDetail.studyTimeoutSeconds}s</div>
            <div><strong>Rate Limit:</strong> {routeDetail.rateLimitPerMinute || 'Unlimited'}/min</div>
          </div>

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '1.5rem', marginBottom: '1rem' }}>
            <h3 style={{ margin: 0 }}>Destinations</h3>
            <button className="btn btn-primary" onClick={handleAddDestination}>
              + Add Destination
            </button>
          </div>
          <div className="table-container">
            <table>
              <thead>
                <tr>
                  <th>Destination</th>
                  <th>Project ID</th>
                  <th>Anonymize</th>
                  <th>Script</th>
                  <th>Honest Broker</th>
                  <th>Priority</th>
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {routeDetail.destinations.map((dest, i) => {
                  const destInfo = destinationMap[dest.destination]
                  const isXnat = destInfo?.type === 'xnat'
                  const xnatUrl = destInfo?.url
                  return (
                  <tr key={i}>
                    <td>
                      <strong>{dest.destination}</strong>
                      {isXnat && xnatUrl && (
                        <a
                          href={xnatUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                          style={{ marginLeft: '0.5rem', fontSize: '0.85em' }}
                          title={`Open ${xnatUrl}`}
                        >
                          (open XNAT)
                        </a>
                      )}
                    </td>
                    <td>{dest.projectId || '-'}</td>
                    <td>{dest.anonymize ? 'Yes' : 'No'}</td>
                    <td>{dest.anonScript || '-'}</td>
                    <td>{dest.useHonestBroker ? dest.honestBroker : '-'}</td>
                    <td>{dest.priority}</td>
                    <td>
                      <span className={`status-badge status-${dest.enabled ? 'up' : 'down'}`}>
                        {dest.enabled ? 'Enabled' : 'Disabled'}
                      </span>
                    </td>
                    <td>
                      <div style={{ display: 'flex', gap: '0.5rem' }}>
                        <button
                          className="btn btn-secondary"
                          onClick={() => handleEditDestination(dest)}
                        >
                          Edit
                        </button>
                        <button
                          className="btn btn-danger"
                          onClick={() => handleRemoveDestination(dest.destination)}
                        >
                          Remove
                        </button>
                      </div>
                    </td>
                  </tr>
                )})}
                {routeDetail.destinations.length === 0 && (
                  <tr>
                    <td colSpan={8} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                      No destinations configured for this route
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          {routeDetail.routingRules.length > 0 && (
            <>
              <h3 style={{ marginTop: '1.5rem', marginBottom: '1rem' }}>Routing Rules</h3>
              <div className="table-container">
                <table>
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Tag</th>
                      <th>Operator</th>
                      <th>Value</th>
                      <th>Destinations</th>
                    </tr>
                  </thead>
                  <tbody>
                    {routeDetail.routingRules.map((rule, i) => (
                      <tr key={i}>
                        <td>{rule.name}</td>
                        <td><code>{rule.tag}</code></td>
                        <td>{rule.operator}</td>
                        <td>{rule.value || rule.values.join(', ')}</td>
                        <td>{rule.destinations.join(', ')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {routeDetail.validationRules.length > 0 && (
            <>
              <h3 style={{ marginTop: '1.5rem', marginBottom: '1rem' }}>Validation Rules</h3>
              <div className="table-container">
                <table>
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Type</th>
                      <th>Tag</th>
                      <th>On Failure</th>
                    </tr>
                  </thead>
                  <tbody>
                    {routeDetail.validationRules.map((rule, i) => (
                      <tr key={i}>
                        <td>{rule.name}</td>
                        <td>{rule.type}</td>
                        <td><code>{rule.tag}</code></td>
                        <td>{rule.onFailure}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {routeDetail.filters.length > 0 && (
            <>
              <h3 style={{ marginTop: '1.5rem', marginBottom: '1rem' }}>Filters</h3>
              <div className="table-container">
                <table>
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Tag</th>
                      <th>Operator</th>
                      <th>Value</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {routeDetail.filters.map((filter, i) => (
                      <tr key={i}>
                        <td>{filter.name}</td>
                        <td><code>{filter.tag}</code></td>
                        <td>{filter.operator}</td>
                        <td>{filter.value}</td>
                        <td>{filter.action}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}
