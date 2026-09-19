import { useEffect, useId, useRef } from 'react'
import type { ReactNode } from 'react'
import { CircleAlert, Loader2, RefreshCw, X } from 'lucide-react'
import { ApiError, errorTitle } from '../lib/api'

export function Spinner({ label }: { label: string }) {
  return <span className="inline-status"><Loader2 size={16} className="spin" aria-hidden="true" />{label}</span>
}

export function ErrorNotice({
  error,
  retry,
  reload,
  dismiss,
  hasDraft = false,
}: {
  error: ApiError
  retry?: () => void
  reload?: () => void
  dismiss?: () => void
  hasDraft?: boolean
}) {
  return (
    <div className="error-notice" role="alert">
      <CircleAlert size={20} className="notice-icon" aria-hidden="true" />
      <div className="notice-content">
        <strong>{errorTitle(error)}</strong>
        <p>{error.message}</p>
        {error.details.length > 0 && <ul>{error.details.map((detail, index) => <li key={index}>{detail}</li>)}</ul>}
        {error.status === 409 && <p>Your draft has not been overwritten. Copy any edits you want to keep, then reload the latest saved version before making changes again.</p>}
        {error.status === 429 && <p>This is a limited assessment environment. Keep your draft and try later; repeatedly submitting won’t increase the limit.</p>}
        {error.status === 401 && <p>Your browser credential has been kept. Retry if the service was restarted. A new workspace will not recover this workspace’s templates.</p>}
        {hasDraft && error.status !== 409 && <p className="notice-preserved">Your open draft is still here. Nothing has been discarded.</p>}
        <div className="notice-actions">
          {retry && <button type="button" className="button button-small button-outline" onClick={retry}><RefreshCw size={14} aria-hidden="true" />Retry</button>}
          {reload && <button type="button" className="button button-small button-outline" onClick={reload}><RefreshCw size={14} aria-hidden="true" />Reload latest</button>}
          <code className="error-code">{error.code}{error.status > 0 ? ` · HTTP ${error.status}` : ''}</code>
        </div>
      </div>
      {dismiss && <button type="button" className="icon-button" aria-label="Dismiss error" onClick={dismiss}><X size={16} aria-hidden="true" /></button>}
    </div>
  )
}

export function Dialog({
  title,
  description,
  children,
  onClose,
  busy = false,
  wide = false,
}: {
  title: string
  description?: string
  children: ReactNode
  onClose: () => void
  busy?: boolean
  wide?: boolean
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const headingId = useId()
  const descriptionId = useId()
  useEffect(() => {
    const node = dialog.current
    if (!node) return
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    node.showModal()
    return () => {
      node.close()
      if (previous?.isConnected) previous.focus()
    }
  }, [])

  return (
    <dialog
      ref={dialog}
      className={`dialog ${wide ? 'dialog-wide' : ''}`}
      aria-labelledby={headingId}
      aria-describedby={description ? descriptionId : undefined}
      aria-busy={busy}
      onCancel={(event) => { event.preventDefault(); if (!busy) onClose() }}
      onClick={(event) => { if (event.target === dialog.current && !busy) onClose() }}
    >
      <div className="dialog-heading">
        <div><p className="eyebrow">TEMPLATE STUDIO</p><h2 id={headingId}>{title}</h2></div>
        <button type="button" className="icon-button" aria-label="Close dialog" onClick={onClose} disabled={busy}><X size={20} aria-hidden="true" /></button>
      </div>
      {description && <p id={descriptionId} className="dialog-description">{description}</p>}
      {children}
    </dialog>
  )
}

export function EmptyState({ icon, title, children }: { icon: ReactNode; title: string; children: ReactNode }) {
  return <div className="empty-state"><div className="empty-icon">{icon}</div><h2>{title}</h2>{children}</div>
}
