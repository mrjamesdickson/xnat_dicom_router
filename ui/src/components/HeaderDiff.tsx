/**
 * Header Diff Component
 *
 * Displays a comparison table of DICOM headers between original and anonymized files.
 * Highlights changes and allows filtering by category and change type.
 */

import { useState, useMemo } from 'react'
import { HeaderComparison, TagComparison } from '../hooks/useCompare'

interface HeaderDiffProps {
  comparison: HeaderComparison | null
  loading?: boolean
  error?: string | null
}

type FilterType = 'all' | 'changed' | 'phi' | 'removed' | 'added'
type CategoryType = 'all' | 'Patient' | 'Study' | 'Series' | 'Equipment' | 'Image' | 'Other'

const CATEGORY_ORDER = ['Patient', 'Study', 'Series', 'Equipment', 'Image', 'Other']

export default function HeaderDiff({ comparison, loading, error }: HeaderDiffProps) {
  const [filter, setFilter] = useState<FilterType>('all')
  const [category, setCategory] = useState<CategoryType>('all')
  const [searchTerm, setSearchTerm] = useState('')

  const filteredTags = useMemo(() => {
    if (!comparison?.tags) return []

    return comparison.tags.filter(tag => {
      // Apply filter
      if (filter === 'changed' && !tag.changed && !tag.removed && !tag.added) return false
      if (filter === 'phi' && !tag.isPhi) return false
      if (filter === 'removed' && !tag.removed) return false
      if (filter === 'added' && !tag.added) return false

      // Apply category filter
      if (category !== 'all' && tag.category !== category) return false

      // Apply search
      if (searchTerm) {
        const search = searchTerm.toLowerCase()
        return (
          tag.tag.toLowerCase().includes(search) ||
          tag.name.toLowerCase().includes(search) ||
          (tag.originalValue && tag.originalValue.toLowerCase().includes(search)) ||
          (tag.anonymizedValue && tag.anonymizedValue.toLowerCase().includes(search))
        )
      }

      return true
    })
  }, [comparison, filter, category, searchTerm])

  // Group by category
  const groupedTags = useMemo(() => {
    const groups: Record<string, TagComparison[]> = {}
    for (const tag of filteredTags) {
      const cat = tag.category || 'Other'
      if (!groups[cat]) groups[cat] = []
      groups[cat].push(tag)
    }

    // Sort categories
    const sorted: [string, TagComparison[]][] = []
    for (const cat of CATEGORY_ORDER) {
      if (groups[cat]) {
        sorted.push([cat, groups[cat]])
      }
    }
    return sorted
  }, [filteredTags])

  if (loading) {
    return (
      <div style={{ padding: '2rem', textAlign: 'center' }}>
        <div className="spinner" />
        <p>Loading header comparison...</p>
      </div>
    )
  }

  if (error) {
    return (
      <div style={{ padding: '2rem', textAlign: 'center', color: '#e74c3c' }}>
        <p>Error loading headers: {error}</p>
      </div>
    )
  }

  if (!comparison) {
    return (
      <div style={{ padding: '2rem', textAlign: 'center', color: '#7f8c8d' }}>
        <p>Select files to compare headers</p>
      </div>
    )
  }

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Stats bar */}
      <div style={{
        display: 'flex',
        gap: '1rem',
        padding: '0.75rem 1rem',
        background: '#f8f9fa',
        borderBottom: '1px solid #eee',
        fontSize: '0.85rem'
      }}>
        <span>Total: <strong>{comparison.totalTags}</strong></span>
        <span style={{ color: '#e67e22' }}>Changed: <strong>{comparison.changedTags}</strong></span>
        <span style={{ color: '#e74c3c' }}>Removed: <strong>{comparison.removedTags}</strong></span>
        <span style={{ color: '#27ae60' }}>Added: <strong>{comparison.addedTags}</strong></span>
        <span style={{ color: '#9b59b6' }}>PHI: <strong>{comparison.phiTags}</strong></span>
      </div>

      {/* Filter bar */}
      <div style={{
        display: 'flex',
        gap: '1rem',
        padding: '0.75rem 1rem',
        borderBottom: '1px solid #eee',
        alignItems: 'center',
        flexWrap: 'wrap'
      }}>
        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
          <label style={{ fontSize: '0.85rem', color: '#7f8c8d' }}>Filter:</label>
          <select
            value={filter}
            onChange={(e) => setFilter(e.target.value as FilterType)}
            style={{ padding: '0.25rem 0.5rem', borderRadius: '4px', border: '1px solid #ddd' }}
          >
            <option value="all">All Tags</option>
            <option value="changed">Changed Only</option>
            <option value="phi">PHI Only</option>
            <option value="removed">Removed</option>
            <option value="added">Added</option>
          </select>
        </div>

        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
          <label style={{ fontSize: '0.85rem', color: '#7f8c8d' }}>Category:</label>
          <select
            value={category}
            onChange={(e) => setCategory(e.target.value as CategoryType)}
            style={{ padding: '0.25rem 0.5rem', borderRadius: '4px', border: '1px solid #ddd' }}
          >
            <option value="all">All Categories</option>
            {CATEGORY_ORDER.map(cat => (
              <option key={cat} value={cat}>{cat}</option>
            ))}
          </select>
        </div>

        <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flex: 1 }}>
          <input
            type="text"
            placeholder="Search tags..."
            value={searchTerm}
            onChange={(e) => setSearchTerm(e.target.value)}
            style={{
              flex: 1,
              padding: '0.25rem 0.5rem',
              borderRadius: '4px',
              border: '1px solid #ddd',
              minWidth: '150px',
              maxWidth: '300px'
            }}
          />
        </div>

        <span style={{ fontSize: '0.85rem', color: '#7f8c8d' }}>
          Showing {filteredTags.length} tags
        </span>
      </div>

      {/* Tags table */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
          <thead style={{ position: 'sticky', top: 0, background: '#f8f9fa' }}>
            <tr>
              <th style={thStyle}>Category</th>
              <th style={thStyle}>Tag</th>
              <th style={thStyle}>Name</th>
              <th style={thStyle}>VR</th>
              <th style={{ ...thStyle, minWidth: '200px' }}>Original</th>
              <th style={{ ...thStyle, minWidth: '200px' }}>Anonymized</th>
            </tr>
          </thead>
          <tbody>
            {groupedTags.map(([categoryName, tags]) => (
              tags.map((tag, idx) => (
                <tr
                  key={tag.tag}
                  style={{
                    background: getRowBackground(tag),
                    borderTop: idx === 0 ? '2px solid #ddd' : undefined
                  }}
                >
                  {idx === 0 && (
                    <td
                      rowSpan={tags.length}
                      style={{
                        ...tdStyle,
                        fontWeight: 600,
                        background: '#f0f0f0',
                        verticalAlign: 'top',
                        padding: '0.5rem'
                      }}
                    >
                      {categoryName}
                    </td>
                  )}
                  <td style={{ ...tdStyle, fontFamily: 'monospace', fontSize: '0.8rem' }}>
                    {tag.tag}
                  </td>
                  <td style={tdStyle}>
                    {tag.isPhi && (
                      <span style={{
                        background: '#9b59b6',
                        color: 'white',
                        fontSize: '0.7rem',
                        padding: '0.1rem 0.3rem',
                        borderRadius: '3px',
                        marginRight: '0.5rem'
                      }}>
                        PHI
                      </span>
                    )}
                    {tag.name}
                  </td>
                  <td style={{ ...tdStyle, fontFamily: 'monospace', fontSize: '0.8rem' }}>
                    {tag.vr}
                  </td>
                  <td style={{
                    ...tdStyle,
                    fontFamily: 'monospace',
                    fontSize: '0.8rem',
                    color: tag.removed ? '#e74c3c' : undefined,
                    textDecoration: tag.removed ? 'line-through' : undefined
                  }}>
                    {truncateValue(tag.originalValue)}
                  </td>
                  <td style={{
                    ...tdStyle,
                    fontFamily: 'monospace',
                    fontSize: '0.8rem',
                    color: tag.added ? '#27ae60' : (tag.changed ? '#e67e22' : undefined),
                    fontWeight: tag.changed ? 500 : undefined
                  }}>
                    {truncateValue(tag.anonymizedValue)}
                  </td>
                </tr>
              ))
            ))}
            {filteredTags.length === 0 && (
              <tr>
                <td colSpan={6} style={{ padding: '2rem', textAlign: 'center', color: '#7f8c8d' }}>
                  No matching tags found
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

const thStyle: React.CSSProperties = {
  padding: '0.5rem',
  textAlign: 'left',
  borderBottom: '2px solid #ddd',
  fontWeight: 600,
  color: '#2c3e50'
}

const tdStyle: React.CSSProperties = {
  padding: '0.35rem 0.5rem',
  borderBottom: '1px solid #eee',
  verticalAlign: 'top'
}

function getRowBackground(tag: TagComparison): string {
  if (tag.removed) return 'rgba(231, 76, 60, 0.1)'
  if (tag.added) return 'rgba(39, 174, 96, 0.1)'
  if (tag.changed) return 'rgba(230, 126, 34, 0.1)'
  return 'transparent'
}

function truncateValue(value: string): string {
  if (!value) return ''
  if (value.length > 100) {
    return value.substring(0, 100) + '...'
  }
  return value
}
