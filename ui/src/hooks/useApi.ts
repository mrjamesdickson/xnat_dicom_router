import { useState, useEffect, useCallback } from 'react'

const API_BASE = '/api'

// Auth token storage
let authToken: string | null = localStorage.getItem('auth_token')

export function setAuthToken(token: string | null) {
  authToken = token
  if (token) {
    localStorage.setItem('auth_token', token)
  } else {
    localStorage.removeItem('auth_token')
  }
}

export function getAuthToken(): string | null {
  return authToken
}

export function clearAuthToken() {
  setAuthToken(null)
}

interface FetchState<T> {
  data: T | null
  loading: boolean
  error: string | null
  refetch: () => void
}

function getHeaders(): HeadersInit {
  const headers: HeadersInit = {
    'Content-Type': 'application/json'
  }
  if (authToken) {
    headers['X-Auth-Token'] = authToken
  }
  return headers
}

export function useFetch<T>(endpoint: string, pollInterval?: number): FetchState<T> {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchData = useCallback(async () => {
    if (!endpoint) {
      setLoading(false)
      return
    }

    try {
      const response = await fetch(`${API_BASE}${endpoint}`, {
        headers: getHeaders()
      })
      if (response.status === 401) {
        // Clear auth on 401
        clearAuthToken()
        window.location.reload()
        return
      }
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`)
      }
      const json = await response.json()
      setData(json)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unknown error')
    } finally {
      setLoading(false)
    }
  }, [endpoint])

  useEffect(() => {
    fetchData()

    if (pollInterval) {
      const interval = setInterval(fetchData, pollInterval)
      return () => clearInterval(interval)
    }
  }, [fetchData, pollInterval])

  return { data, loading, error, refetch: fetchData }
}

export async function apiPost<T>(endpoint: string, body: unknown): Promise<T> {
  const response = await fetch(`${API_BASE}${endpoint}`, {
    method: 'POST',
    headers: getHeaders(),
    body: JSON.stringify(body)
  })
  if (response.status === 401) {
    clearAuthToken()
    window.location.reload()
    throw new Error('Authentication required')
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({ error: response.statusText }))
    throw new Error(error.error || `HTTP ${response.status}`)
  }
  return response.json()
}

export async function apiPut<T>(endpoint: string, body: unknown): Promise<T> {
  const response = await fetch(`${API_BASE}${endpoint}`, {
    method: 'PUT',
    headers: getHeaders(),
    body: JSON.stringify(body)
  })
  if (response.status === 401) {
    clearAuthToken()
    window.location.reload()
    throw new Error('Authentication required')
  }
  if (!response.ok) {
    const error = await response.json().catch(() => ({ error: response.statusText }))
    throw new Error(error.error || `HTTP ${response.status}`)
  }
  return response.json()
}

export async function apiDelete(endpoint: string): Promise<void> {
  const response = await fetch(`${API_BASE}${endpoint}`, {
    method: 'DELETE',
    headers: getHeaders()
  })
  if (response.status === 401) {
    clearAuthToken()
    window.location.reload()
    throw new Error('Authentication required')
  }
  if (!response.ok && response.status !== 204) {
    const error = await response.json().catch(() => ({ error: response.statusText }))
    throw new Error(error.error || `HTTP ${response.status}`)
  }
}

export async function checkAuth(): Promise<{ authenticated: boolean; authRequired: boolean; username?: string }> {
  const response = await fetch(`${API_BASE}/auth/check`, {
    headers: getHeaders()
  })
  if (!response.ok) {
    return { authenticated: false, authRequired: true }
  }
  return response.json()
}
