import type {
  ApiErrorBody,
  TemplateDetail,
  TemplateSummary,
  TemplateUpdate,
  WorkspaceCreated,
} from '../contracts'

const apiBase = (import.meta.env.VITE_API_BASE_URL ?? '').trim().replace(/\/+$/, '')

export const apiUrl = (path: string) => `${apiBase}/api${path}`
export const sampleUrl = apiUrl('/sample')
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: string[]

  constructor(status: number, code: string, message: string, details: string[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

export function asApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error
  return new ApiError(
    0,
    'UNEXPECTED_CLIENT_ERROR',
    error instanceof Error ? error.message : 'An unexpected error interrupted this operation.',
  )
}

export function errorTitle(error: ApiError): string {
  if (error.code === 'STORAGE_UNAVAILABLE') return 'Browser storage is unavailable'
  if (error.code === 'REQUEST_TIMEOUT') return 'The server took too long to respond'
  if (error.status >= 500) return 'The workspace service is unavailable'
  if (error.code === 'INVALID_RESPONSE') return 'The server returned an unexpected response'
  if (error.status === 0) return 'We couldn’t reach your workspace'
  if (error.status === 401) return 'This workspace credential is no longer accepted'
  if (error.status === 404) return 'This template is no longer available'
  if (error.status === 409) return 'A newer version was saved elsewhere'
  if (error.status === 413) return 'This workbook is too large'
  if (error.status === 422) return 'This workbook couldn’t be imported'
  if (error.status === 429) return 'The demo limit has been reached'
  return 'This change couldn’t be completed'
}

function errorBody(value: unknown): ApiErrorBody | null {
  if (typeof value !== 'object' || value === null) return null
  if (!('message' in value) || typeof value.message !== 'string') return null
  const code = 'code' in value && typeof value.code === 'string' ? value.code : 'REQUEST_FAILED'
  const details =
    'details' in value && Array.isArray(value.details)
      ? value.details.filter((entry): entry is string => typeof entry === 'string')
      : []
  return { code, message: value.message, details }
}

async function request<T>(path: string, token?: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (options.body && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(apiUrl(path), {
      ...options,
      headers,
      signal: options.signal ?? AbortSignal.timeout(90_000),
    })
  } catch (error) {
    const timedOut = error instanceof Error && error.name === 'TimeoutError'
    throw new ApiError(
      0,
      timedOut ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR',
      timedOut
        ? 'The request timed out. Your browser credential and any open draft have been kept. Reload the saved template before retrying a write whose result is uncertain.'
        : 'Check your connection and that the API is running, then try again. Your browser credential and any open draft have been kept.',
    )
  }

  const text = await response.text()
  let body: unknown
  try {
    body = JSON.parse(text)
  } catch {
    throw new ApiError(
      response.status,
      'INVALID_RESPONSE',
      response.ok
        ? 'The API did not return valid JSON. Check the API URL or deployment rewrite, then retry.'
        : `The API returned HTTP ${response.status} without a structured error. Check the API service and retry.`,
    )
  }

  if (!response.ok) {
    const parsed = errorBody(body)
    throw new ApiError(
      response.status,
      parsed?.code ?? 'REQUEST_FAILED',
      parsed?.message ?? `The server rejected this request (HTTP ${response.status}).`,
      parsed?.details,
    )
  }
  return body as T
}

export function validateUpload(file: File): string | null {
  if (!/\.(xls|xlsx)$/i.test(file.name)) {
    return 'Choose an Excel workbook ending in .xls or .xlsx. CSV, PDF and renamed files are not supported.'
  }
  if (file.size === 0) return 'This file is empty. Choose a workbook containing a template export.'
  if (file.size > MAX_UPLOAD_BYTES) return 'The upload limit is 10 MiB. Choose a smaller workbook.'
  return null
}

export const templatesApi = {
  createWorkspace: () => request<WorkspaceCreated>('/workspaces', undefined, { method: 'POST' }),
  list: (token: string) => request<{ templates: TemplateSummary[] }>('/templates', token),
  get: (token: string, id: string) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(id)}`, token),
  update: (token: string, id: string, update: TemplateUpdate) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(id)}`, token, {
      method: 'PUT',
      body: JSON.stringify(update),
    }),
  duplicate: (token: string, id: string, name: string) =>
    request<TemplateDetail>(`/templates/${encodeURIComponent(id)}/duplicate`, token, {
      method: 'POST',
      body: JSON.stringify({ name }),
    }),
  import: (token: string, file: File, name: string) => {
    const body = new FormData()
    body.append('file', file)
    if (name.trim()) body.append('name', name.trim())
    return request<TemplateDetail>('/templates/import', token, { method: 'POST', body })
  },
}
