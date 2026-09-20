import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import type { TemplateDetail, TemplateSummary } from '../contracts'
import { ApiError, asApiError, templatesApi } from '../lib/api'
import { draftReducer, isDraftDirty, toTemplateUpdate, upsertSummary } from '../lib/template'
import { ensureWorkspace, provisionWorkspace } from '../lib/workspace'

type Operation = 'save' | 'switch' | 'reload' | 'import' | 'duplicate' | 'delete' | null
type Phase = 'loading' | 'ready' | 'error'

interface RequestAttempt {
  operation: Exclude<Operation, null>
  task: (credential: string) => Promise<TemplateDetail>
  successNotice?: string
}

export function useTemplateWorkspace() {
  const [state, dispatch] = useReducer(draftReducer, { saved: null, draft: null })
  const [templates, setTemplates] = useState<TemplateSummary[]>([])
  const [token, setToken] = useState<string | null>(null)
  const [phase, setPhase] = useState<Phase>('loading')
  const [bootError, setBootError] = useState<ApiError | null>(null)
  const [actionError, setActionError] = useState<ApiError | null>(null)
  const [operation, setOperation] = useState<Operation>(null)
  const [failedOperation, setFailedOperation] = useState<Operation>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const bootSequence = useRef(0)
  const operationLock = useRef(false)
  const lastAttempt = useRef<RequestAttempt | null>(null)
  const dirty = useMemo(() => isDraftDirty(state), [state])

  const acceptTemplate = useCallback((detail: TemplateDetail) => {
    dispatch({ type: 'loaded', template: detail })
    setTemplates((current) => upsertSummary(current, detail))
    setActionError(null)
    setFailedOperation(null)
  }, [])

  const bootstrap = useCallback(async (newWorkspace = false) => {
    const sequence = ++bootSequence.current
    setPhase('loading')
    setBootError(null)
    try {
      const session = await (newWorkspace ? provisionWorkspace() : ensureWorkspace())
      const result = await templatesApi.list(session.token)
      const id = session.initialTemplateId ?? result.templates[0]?.id
      const detail = id ? await templatesApi.get(session.token, id) : null
      if (sequence !== bootSequence.current) return
      setToken(session.token)
      setTemplates(result.templates)
      if (detail) acceptTemplate(detail)
      setPhase('ready')
    } catch (error) {
      if (sequence !== bootSequence.current) return
      setBootError(asApiError(error))
      setPhase('error')
    }
  }, [acceptTemplate])

  useEffect(() => {
    void bootstrap()
    return () => { bootSequence.current += 1 }
  }, [bootstrap])

  useEffect(() => {
    if (!dirty && !operation) return
    const protectDraft = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', protectDraft)
    return () => window.removeEventListener('beforeunload', protectDraft)
  }, [dirty, operation])

  const perform = useCallback(async (
    nextOperation: Exclude<Operation, null>,
    task: (credential: string) => Promise<TemplateDetail>,
    successNotice?: string,
  ): Promise<boolean> => {
    if (!token || operationLock.current) return false
    operationLock.current = true
    lastAttempt.current = { operation: nextOperation, task, successNotice }
    setOperation(nextOperation)
    setFailedOperation(null)
    setActionError(null)
    setNotice(null)
    try {
      const result = await task(token)
      acceptTemplate(result)
      if (successNotice) setNotice(successNotice)
      return true
    } catch (error) {
      setActionError(asApiError(error))
      setFailedOperation(nextOperation)
      return false
    } finally {
      operationLock.current = false
      setOperation(null)
    }
  }, [token, acceptTemplate])

  const selectTemplate = useCallback((id: string) =>
    perform('switch', (credential) => templatesApi.get(credential, id)),
  [perform])

  const reload = useCallback(() => {
    const current = state.saved
    if (!current) return Promise.resolve(false)
    return perform('reload', (credential) => templatesApi.get(credential, current.id), 'Latest saved version loaded.')
  }, [perform, state.saved])

  const save = useCallback(() => {
    const current = state.draft
    if (!current || !dirty) return Promise.resolve(false)
    return perform(
      'save',
      (credential) => templatesApi.update(credential, current.id, toTemplateUpdate(current)),
      'Your changes are saved to this workspace.',
    )
  }, [perform, state.draft, dirty])

  const importTemplate = useCallback((file: File, name: string) =>
    perform('import', (credential) => templatesApi.import(credential, file, name), 'Workbook imported. Review its counts, notes and source warnings below.'),
  [perform])

  const duplicate = useCallback((name: string) => {
    const current = state.saved
    if (!current) return Promise.resolve(false)
    return perform('duplicate', (credential) => templatesApi.duplicate(credential, current.id, name), 'Independent copy created. The original template is unchanged.')
  }, [perform, state.saved])

  const removeTemplate = useCallback(async (id: string): Promise<boolean> => {
    if (!token || operationLock.current) return false
    operationLock.current = true
    lastAttempt.current = null
    setOperation('delete')
    setFailedOperation(null)
    setActionError(null)
    setNotice(null)
    try {
      await templatesApi.remove(token, id)
    } catch (error) {
      setActionError(asApiError(error))
      setFailedOperation('delete')
      operationLock.current = false
      setOperation(null)
      return false
    }

    const remaining = templates.filter((template) => template.id !== id)
    setTemplates(remaining)
    setNotice('Template deleted from this workspace.')
    if (state.saved?.id === id) {
      dispatch({ type: 'cleared' })
      const next = remaining[0]
      if (next) {
        try {
          acceptTemplate(await templatesApi.get(token, next.id))
          setNotice('Template deleted. Another saved template is now open.')
        } catch (error) {
          const loadError = asApiError(error)
          setActionError(new ApiError(
            loadError.status,
            loadError.code,
            `The template was deleted, but the next saved template could not be opened. ${loadError.message}`,
            loadError.details,
          ))
        }
      }
    }
    operationLock.current = false
    setOperation(null)
    return true
  }, [token, templates, state.saved, acceptTemplate])

  const retry = useCallback(() => {
    const attempt = lastAttempt.current
    if (!attempt) return Promise.resolve(false)
    if (attempt.operation === 'save') return save()
    return perform(attempt.operation, attempt.task, attempt.successNotice)
  }, [perform, save])

  return {
    ...state,
    dispatch,
    templates,
    phase,
    bootError,
    actionError,
    operation,
    failedOperation,
    dirty,
    notice,
    bootstrap,
    selectTemplate,
    reload,
    save,
    importTemplate,
    duplicate,
    removeTemplate,
    retry,
    clearActionError: () => { setActionError(null); setFailedOperation(null) },
    clearNotice: () => setNotice(null),
  }
}
