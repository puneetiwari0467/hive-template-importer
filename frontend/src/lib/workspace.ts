import { ApiError, templatesApi } from './api'

export const WORKSPACE_TOKEN_KEY = 'hive-template-studio.workspace-token'

interface WorkspaceSession {
  token: string
  initialTemplateId?: string
}

let provisioning: Promise<WorkspaceSession> | null = null

function readToken(): string | null {
  try {
    return localStorage.getItem(WORKSPACE_TOKEN_KEY)
  } catch {
    throw new ApiError(
      0,
      'STORAGE_UNAVAILABLE',
      'Allow local storage for this site, then retry. It stores only your private workspace credential, never your templates.',
    )
  }
}

function storeToken(token: string): void {
  try {
    localStorage.setItem(WORKSPACE_TOKEN_KEY, token)
  } catch {
    throw new ApiError(
      0,
      'STORAGE_UNAVAILABLE',
      'The browser could not keep the workspace credential. Allow local storage for this site, then retry.',
    )
  }
}

export async function ensureWorkspace(): Promise<WorkspaceSession> {
  const token = readToken()
  if (token) return { token }
  // A shared in-flight request avoids creating two workspaces during Strict Mode mounting.
  provisioning ??= provisionWorkspace().finally(() => {
    provisioning = null
  })
  return provisioning
}

export async function provisionWorkspace(): Promise<WorkspaceSession> {
  const workspace = await templatesApi.createWorkspace()
  if (!workspace.token || !workspace.templateId) {
    throw new ApiError(502, 'INVALID_RESPONSE', 'The API did not return a workspace credential and sample template.')
  }
  storeToken(workspace.token)
  return { token: workspace.token, initialTemplateId: workspace.templateId }
}
