import { describe, expect, it, vi } from 'vitest'
import { ApiError, templatesApi } from './api'
import { WORKSPACE_TOKEN_KEY, ensureWorkspace, provisionWorkspace } from './workspace'

describe('private browser credentials', () => {
  it('creates one workspace for concurrent first-visit requests and stores only its token', async () => {
    const create = vi.spyOn(templatesApi, 'createWorkspace').mockResolvedValue({ token: 'private-token', workspaceId: 'workspace-id', templateId: 'sample-id' })
    const [first, second] = await Promise.all([ensureWorkspace(), ensureWorkspace()])
    expect(first).toEqual({ token: 'private-token', initialTemplateId: 'sample-id' })
    expect(second).toEqual(first)
    expect(create).toHaveBeenCalledTimes(1)
    expect(localStorage.length).toBe(1)
    expect(localStorage.getItem(WORKSPACE_TOKEN_KEY)).toBe('private-token')
  })

  it('reuses a stored credential without reprovisioning on later visits', async () => {
    localStorage.setItem(WORKSPACE_TOKEN_KEY, 'existing-token')
    const create = vi.spyOn(templatesApi, 'createWorkspace')
    expect(await ensureWorkspace()).toEqual({ token: 'existing-token' })
    expect(create).not.toHaveBeenCalled()
  })

  it('does not clear the old credential when explicit replacement fails', async () => {
    localStorage.setItem(WORKSPACE_TOKEN_KEY, 'existing-token')
    vi.spyOn(templatesApi, 'createWorkspace').mockRejectedValue(new ApiError(503, 'UNAVAILABLE', 'Service unavailable'))
    await expect(provisionWorkspace()).rejects.toMatchObject({ status: 503 })
    expect(localStorage.getItem(WORKSPACE_TOKEN_KEY)).toBe('existing-token')
  })

  it('reports blocked browser storage rather than silently creating throwaway workspaces', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('Storage blocked', 'SecurityError') })
    const create = vi.spyOn(templatesApi, 'createWorkspace')
    await expect(ensureWorkspace()).rejects.toMatchObject({ code: 'STORAGE_UNAVAILABLE' })
    expect(create).not.toHaveBeenCalled()
  })
})
