import { useState } from 'react'
import { useFetch, apiPost, apiPut, apiDelete } from '../hooks/useApi'

interface Script {
  name: string
  description: string | null
  builtIn: boolean
  source: string
  createdAt: string | null
  modifiedAt: string | null
}

interface ScriptWithContent extends Script {
  content: string
}

export default function Scripts() {
  const { data: scripts, loading, error, refetch } = useFetch<Script[]>('/scripts')
  const [selectedScript, setSelectedScript] = useState<string | null>(null)
  const { data: scriptDetail } = useFetch<ScriptWithContent>(
    selectedScript ? `/scripts/${selectedScript}` : ''
  )
  const [isEditing, setIsEditing] = useState(false)
  const [editContent, setEditContent] = useState('')
  const [editDescription, setEditDescription] = useState('')
  const [saving, setSaving] = useState(false)
  const [showNew, setShowNew] = useState(false)
  const [newName, setNewName] = useState('')
  const [newDescription, setNewDescription] = useState('')
  const [newContent, setNewContent] = useState('')
  const [creating, setCreating] = useState(false)

  const handleEdit = (script: ScriptWithContent) => {
    setEditContent(script.content)
    setEditDescription(script.description || '')
    setIsEditing(true)
  }

  const handleSave = async () => {
    if (!selectedScript) return
    setSaving(true)
    try {
      await apiPut(`/scripts/${selectedScript}`, {
        content: editContent,
        description: editDescription
      })
      setIsEditing(false)
      refetch()
    } catch (err) {
      alert('Failed to save: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (name: string) => {
    if (!confirm(`Delete script "${name}"?`)) return
    try {
      await apiDelete(`/scripts/${name}`)
      setSelectedScript(null)
      refetch()
    } catch (err) {
      alert('Failed to delete: ' + (err instanceof Error ? err.message : 'Unknown error'))
    }
  }

  const handleCreate = async () => {
    if (!newName.trim() || !newContent.trim()) {
      alert('Name and content are required')
      return
    }
    setCreating(true)
    try {
      await apiPost('/scripts', {
        name: newName,
        description: newDescription,
        content: newContent
      })
      setShowNew(false)
      setNewName('')
      setNewDescription('')
      setNewContent('')
      refetch()
    } catch (err) {
      alert('Failed to create: ' + (err instanceof Error ? err.message : 'Unknown error'))
    } finally {
      setCreating(false)
    }
  }

  if (loading) {
    return <div className="loading"><div className="spinner" /></div>
  }

  if (error) {
    return <div className="error-message">Failed to load scripts: {error}</div>
  }

  const builtInScripts = scripts?.filter(s => s.builtIn) || []
  const customScripts = scripts?.filter(s => !s.builtIn) || []

  return (
    <div>
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Anonymization Scripts</h2>
          <button className="btn btn-primary" onClick={() => setShowNew(true)}>
            New Script
          </button>
        </div>

        <h3 style={{ marginBottom: '1rem' }}>Built-in Scripts</h3>
        <div className="table-container" style={{ marginBottom: '1.5rem' }}>
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Description</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {builtInScripts.map(script => (
                <tr key={script.name}>
                  <td><strong>{script.name}</strong></td>
                  <td>{script.description}</td>
                  <td>
                    <button
                      className="btn btn-secondary"
                      onClick={() => { setSelectedScript(script.name); setIsEditing(false) }}
                    >
                      View
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <h3 style={{ marginBottom: '1rem' }}>Custom Scripts</h3>
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Description</th>
                <th>Source</th>
                <th>Modified</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {customScripts.map(script => (
                <tr key={script.name}>
                  <td><strong>{script.name}</strong></td>
                  <td>{script.description || '-'}</td>
                  <td>{script.source}</td>
                  <td>{script.modifiedAt ? new Date(script.modifiedAt).toLocaleDateString() : '-'}</td>
                  <td>
                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      <button
                        className="btn btn-secondary"
                        onClick={() => { setSelectedScript(script.name); setIsEditing(false) }}
                      >
                        View
                      </button>
                      <button
                        className="btn btn-secondary"
                        onClick={() => handleDelete(script.name)}
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {customScripts.length === 0 && (
                <tr>
                  <td colSpan={5} style={{ textAlign: 'center', color: 'var(--text-light)' }}>
                    No custom scripts
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {showNew && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Create New Script</h2>
            <button className="btn btn-secondary" onClick={() => setShowNew(false)}>
              Cancel
            </button>
          </div>

          <div className="form-group">
            <label className="form-label">Name</label>
            <input
              type="text"
              className="form-input"
              value={newName}
              onChange={e => setNewName(e.target.value)}
              placeholder="my_custom_script"
            />
          </div>

          <div className="form-group">
            <label className="form-label">Description</label>
            <input
              type="text"
              className="form-input"
              value={newDescription}
              onChange={e => setNewDescription(e.target.value)}
              placeholder="Description of the script"
            />
          </div>

          <div className="form-group">
            <label className="form-label">Script Content (DicomEdit format)</label>
            <textarea
              className="form-textarea"
              value={newContent}
              onChange={e => setNewContent(e.target.value)}
              rows={15}
              style={{ fontFamily: 'monospace' }}
              placeholder={`version "6.3"\n\n// Example: Remove patient name\n(0010,0010) := "ANONYMOUS"`}
            />
          </div>

          <button
            className="btn btn-primary"
            onClick={handleCreate}
            disabled={creating}
          >
            {creating ? 'Creating...' : 'Create Script'}
          </button>
        </div>
      )}

      {selectedScript && scriptDetail && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Script: {scriptDetail.name}</h2>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              {!scriptDetail.builtIn && !isEditing && (
                <button className="btn btn-primary" onClick={() => handleEdit(scriptDetail)}>
                  Edit
                </button>
              )}
              <button className="btn btn-secondary" onClick={() => setSelectedScript(null)}>
                Close
              </button>
            </div>
          </div>

          <div style={{ marginBottom: '1rem' }}>
            <p><strong>Description:</strong> {scriptDetail.description || '-'}</p>
            <p><strong>Source:</strong> {scriptDetail.source}</p>
            {scriptDetail.modifiedAt && (
              <p><strong>Modified:</strong> {new Date(scriptDetail.modifiedAt).toLocaleString()}</p>
            )}
          </div>

          {isEditing ? (
            <>
              <div className="form-group">
                <label className="form-label">Description</label>
                <input
                  type="text"
                  className="form-input"
                  value={editDescription}
                  onChange={e => setEditDescription(e.target.value)}
                />
              </div>

              <div className="form-group">
                <label className="form-label">Script Content</label>
                <textarea
                  className="form-textarea"
                  value={editContent}
                  onChange={e => setEditContent(e.target.value)}
                  rows={20}
                  style={{ fontFamily: 'monospace' }}
                />
              </div>

              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button
                  className="btn btn-primary"
                  onClick={handleSave}
                  disabled={saving}
                >
                  {saving ? 'Saving...' : 'Save Changes'}
                </button>
                <button
                  className="btn btn-secondary"
                  onClick={() => setIsEditing(false)}
                >
                  Cancel
                </button>
              </div>
            </>
          ) : (
            <pre style={{
              background: 'var(--bg-color)',
              padding: '1rem',
              borderRadius: '6px',
              overflow: 'auto',
              fontSize: '0.875rem',
              fontFamily: 'monospace',
              whiteSpace: 'pre-wrap'
            }}>
              {scriptDetail.content}
            </pre>
          )}
        </div>
      )}
    </div>
  )
}
