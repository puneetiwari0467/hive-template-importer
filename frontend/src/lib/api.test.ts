import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fixtureTemplate } from '../test/fixtures'
import { ApiError, MAX_UPLOAD_BYTES, errorTitle, templatesApi, validateUpload } from './api'
import { toTemplateUpdate } from './template'

describe('HTTP client', () => {
  const fetchMock = vi.fn<typeof fetch>()
  beforeEach(() => { fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock) })

  it('sends bearer auth and the complete update, returning the real response', async () => {
    const template = fixtureTemplate()
    const updated = { ...template, version: 4 }
    fetchMock.mockResolvedValue(new Response(JSON.stringify(updated), { status: 200 }))
    const result = await templatesApi.update('private-test-token', template.id, toTemplateUpdate(template))
    expect(result.version).toBe(4)
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe(`/api/templates/${template.id}`)
    expect(options?.method).toBe('PUT')
    const headers = new Headers(options?.headers)
    expect(headers.get('Authorization')).toBe('Bearer private-test-token')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(JSON.parse(String(options?.body))).toEqual(toTemplateUpdate(template))
  })

  it('sets multipart fields without replacing the browser’s boundary header', async () => {
    const file = new File(['workbook bytes'], 'template.xlsx')
    fetchMock.mockResolvedValue(new Response(JSON.stringify(fixtureTemplate()), { status: 201 }))
    await templatesApi.import('test-token', file, '   ')
    const options = fetchMock.mock.calls[0][1]
    expect(options?.body).toBeInstanceOf(FormData)
    const body = options?.body
    if (!(body instanceof FormData)) throw new Error('Expected multipart form data')
    expect(body.get('file')).toBeInstanceOf(File)
    expect(body.has('name')).toBe(false)
    expect(new Headers(options?.headers).has('Content-Type')).toBe(false)
  })

  it('accepts an empty 204 response when deleting a template', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    await expect(templatesApi.remove('test-token', 'template/id')).resolves.toBeUndefined()
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/templates/template%2Fid')
    expect(options?.method).toBe('DELETE')
    expect(new Headers(options?.headers).get('Authorization')).toMatch(/^Bearer .+/)
  })

  it('keeps structured delete failures', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      code: 'NOT_FOUND',
      message: 'Template not found.',
      details: [],
    }), { status: 404 }))
    await expect(templatesApi.remove('test-token', 'missing')).rejects.toMatchObject({
      status: 404,
      code: 'NOT_FOUND',
      message: 'Template not found.',
    })
  })

  it.each([401, 409, 413, 422, 429, 500])('retains structured HTTP %i errors and details', async (status) => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ code: 'SPECIFIC_ERROR', message: 'An actionable server message', details: ['First detail', 'Second detail'] }), { status }))
    await expect(templatesApi.list('test-token')).rejects.toMatchObject({
      status,
      code: 'SPECIFIC_ERROR',
      message: 'An actionable server message',
      details: ['First detail', 'Second detail'],
    })
  })

  it('distinguishes an unreachable API from a valid empty template library', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
    await expect(templatesApi.list('kept-token')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
  })

  it('reports a non-JSON gateway response, never a success-shaped empty result', async () => {
    fetchMock.mockResolvedValue(new Response('<h1>Unavailable</h1>', { status: 503 }))
    await expect(templatesApi.list('kept-token')).rejects.toMatchObject({ status: 503, code: 'INVALID_RESPONSE' })
    expect(errorTitle(new ApiError(503, 'INVALID_RESPONSE', 'Gateway unavailable'))).toContain('service is unavailable')
  })

  it('gives conflicts, quotas and invalid files distinct titles', () => {
    expect(errorTitle(new ApiError(409, 'CONFLICT', 'Conflict'))).toContain('newer version')
    expect(errorTitle(new ApiError(429, 'QUOTA', 'Limit'))).toContain('demo limit')
    expect(errorTitle(new ApiError(422, 'INVALID_FILE', 'Bad file'))).toContain('couldn’t be imported')
  })
})

describe('workbook selection validation', () => {
  it('accepts both supported extensions case-insensitively and exactly 10 MiB', () => {
    const file = new File(['data'], 'EXPORT.XLSX')
    Object.defineProperty(file, 'size', { value: MAX_UPLOAD_BYTES })
    expect(validateUpload(file)).toBeNull()
    expect(validateUpload(new File(['data'], 'legacy.xls'))).toBeNull()
  })

  it('rejects empty, oversized and unsupported file selections with useful messages', () => {
    const file = new File(['data'], 'oversized.xlsx')
    Object.defineProperty(file, 'size', { value: MAX_UPLOAD_BYTES + 1 })
    expect(validateUpload(file)).toContain('10 MiB')
    expect(validateUpload(new File([], 'empty.xls'))).toContain('empty')
    expect(validateUpload(new File(['data'], 'export.csv'))).toContain('.xls or .xlsx')
  })
})
