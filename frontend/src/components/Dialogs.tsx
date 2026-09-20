import { useId, useRef, useState } from 'react'
import { ArrowRight, Copy, FileSpreadsheet, LockKeyhole, Trash2, Upload, X } from 'lucide-react'
import { ApiError, sampleUrl, validateUpload } from '../lib/api'
import { Dialog, ErrorNotice, Spinner } from './Ui'

export function ImportDialog({
  busy,
  error,
  hasDraft,
  onClose,
  onSubmit,
}: {
  busy: boolean
  error: ApiError | null
  hasDraft: boolean
  onClose: () => void
  onSubmit: (file: File, name: string) => Promise<void>
}) {
  const [file, setFile] = useState<File | null>(null)
  const [name, setName] = useState('')
  const [validation, setValidation] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const inputId = useId()
  const nameId = useId()

  function chooseFiles(files: FileList | null) {
    if (busy || !files || files.length === 0) return
    if (files.length !== 1) {
      setFile(null)
      setValidation('Import one workbook at a time. Please choose a single .xls or .xlsx file.')
      return
    }
    const chosen = files[0]
    const issue = validateUpload(chosen)
    setValidation(issue)
    setFile(issue ? null : chosen)
  }

  return (
    <Dialog title="Bring your template along." description="Use Spectora's Export to spreadsheet > Export HTML Text. The file must be a real Excel workbook; renaming a text or PDF file to .xls does not convert it." onClose={onClose} busy={busy} wide>
      <form onSubmit={(event) => {
        event.preventDefault()
        if (busy) return
        if (!file) { setValidation('Choose a workbook before importing.'); return }
        const issue = validateUpload(file)
        if (issue) { setValidation(issue); return }
        void onSubmit(file, name)
      }}>
        <div
          className={`drop-zone ${dragging ? 'is-dragging' : ''} ${file ? 'has-file' : ''}`}
          onDragOver={(event) => { event.preventDefault(); if (!busy) setDragging(true) }}
          onDragLeave={(event) => { if (!(event.relatedTarget instanceof Node) || !event.currentTarget.contains(event.relatedTarget)) setDragging(false) }}
          onDrop={(event) => { event.preventDefault(); setDragging(false); chooseFiles(event.dataTransfer.files) }}
          aria-busy={busy}
        >
          <div className="drop-zone-icon">{file ? <FileSpreadsheet size={28} aria-hidden="true" /> : <Upload size={28} aria-hidden="true" />}</div>
          {file ? <><strong className="selected-file-name">{file.name}</strong><p>{(file.size / 1024).toLocaleString(undefined, { maximumFractionDigits: 1 })} KiB · File selected; contents checked on import</p></> : <><strong>Drop your workbook here</strong><p>Excel .xls or .xlsx · Up to 10 MiB</p></>}
          <input ref={fileInput} id={inputId} type="file" accept=".xls,.xlsx,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" aria-label="Select Excel workbook" className="file-input" disabled={busy} onChange={(event) => chooseFiles(event.target.files)} />
          <button type="button" className="button button-outline" disabled={busy} onClick={() => { if (fileInput.current) { fileInput.current.value = ''; fileInput.current.click() } }}>{file ? 'Choose a different workbook' : 'Choose a workbook'}</button>
          {file && <button type="button" className="text-button remove-file" disabled={busy} onClick={() => { setFile(null); setValidation(null) }}><X size={13} aria-hidden="true" />Remove file</button>}
        </div>
        {validation && <p className="field-error" role="alert">{validation}</p>}
        <div className="dialog-form-field"><label className="field-label" htmlFor={nameId}>Template name <span>Optional</span></label><input className="text-input" id={nameId} value={name} onChange={(event) => setName(event.target.value)} placeholder="Use the workbook name" disabled={busy} /></div>
        <div className="dialog-info"><LockKeyhole size={17} aria-hidden="true" /><p>Assessment workspace only. Don’t upload real customer data. The server validates the file and keeps the imported template in this private workspace.</p></div>
        <p className="sample-inline">Need a workbook to try? <a href={sampleUrl} download>Download the bundled sample</a></p>
        {error && <ErrorNotice error={error} hasDraft={hasDraft} />}
        {busy && <p className="operation-detail" role="status">Reading the workbook and checking its hierarchy. Don’t close this tab while the import is in progress.</p>}
        <div className="dialog-actions"><button type="button" className="button button-quiet" disabled={busy} onClick={onClose}>Cancel</button><button type="submit" className="button button-primary" disabled={busy || !file}>{busy ? <Spinner label="Importing workbook…" /> : <><Upload size={16} aria-hidden="true" />Import workbook</>}</button></div>
      </form>
    </Dialog>
  )
}

export function DuplicateDialog({
  sourceName,
  busy,
  error,
  onClose,
  onSubmit,
}: {
  sourceName: string
  busy: boolean
  error: ApiError | null
  onClose: () => void
  onSubmit: (name: string) => Promise<void>
}) {
  const [name, setName] = useState(`${sourceName} (copy)`)
  const [validation, setValidation] = useState<string | null>(null)
  const nameId = useId()
  return <Dialog title="A fresh copy. A familiar starting point." description="Duplicate the latest saved template into an independent copy. Your original template stays untouched." onClose={onClose} busy={busy}>
    <form onSubmit={(event) => { event.preventDefault(); if (busy) return; if (!name.trim()) { setValidation('Give your new copy a name.'); return } setValidation(null); void onSubmit(name.trim()) }}>
      <div className="duplicate-source"><div className="duplicate-source-icon"><Copy size={22} aria-hidden="true" /></div><div><span>COPYING SAVED TEMPLATE</span><strong>{sourceName}</strong></div></div>
      <label className="field-label" htmlFor={nameId}>Name your copy</label><input id={nameId} autoFocus className="text-input" value={name} onChange={(event) => { setName(event.target.value); setValidation(null) }} disabled={busy} />
      {validation && <p className="field-error" role="alert">{validation}</p>}
      <p className="dialog-hint">Sections, items, comments and the original source audit are copied. Future edits to either template are independent.</p>
      {error && <ErrorNotice error={error} hasDraft />}
      <div className="dialog-actions"><button type="button" className="button button-quiet" disabled={busy} onClick={onClose}>Cancel</button><button type="submit" className="button button-primary" disabled={busy || !name.trim()}>{busy ? <Spinner label="Creating copy…" /> : <><Copy size={16} aria-hidden="true" />Create independent copy</>}</button></div>
    </form>
  </Dialog>
}

export function DeleteDialog({
  templateName,
  busy,
  error,
  onClose,
  onConfirm,
}: {
  templateName: string
  busy: boolean
  error: ApiError | null
  onClose: () => void
  onConfirm: () => void
}) {
  return <Dialog title="Delete this template?" description="This permanently removes the template and all of its sections, items and comments from this workspace. It cannot be undone." onClose={onClose} busy={busy}>
    <div className="delete-source"><div className="delete-source-icon"><Trash2 size={22} aria-hidden="true" /></div><div><span>DELETING TEMPLATE</span><strong>{templateName}</strong></div></div>
    <p className="dialog-hint">Independent copies of this template are not deleted and keep their own content. The original Spectora workbook in the repository is unaffected.</p>
    {error && <ErrorNotice error={error} />}
    <div className="dialog-actions"><button type="button" className="button button-quiet" disabled={busy} onClick={onClose}>Cancel</button><button type="button" className="button button-danger" autoFocus disabled={busy} onClick={onConfirm}>{busy ? <Spinner label="Deleting…" /> : <><Trash2 size={16} aria-hidden="true" />Delete template</>}</button></div>
  </Dialog>
}

export interface Confirmation {
  title: string
  description: string
  label: string
  run: () => void | Promise<void>
}

export function ConfirmDialog({ confirmation, onClose, onConfirm }: { confirmation: Confirmation; onClose: () => void; onConfirm: () => void }) {
  return <Dialog title={confirmation.title} description={confirmation.description} onClose={onClose}><div className="confirm-note">Unsaved edits are held only in this open tab. They are not backed up in browser storage.</div><div className="dialog-actions"><button type="button" className="button button-outline" autoFocus onClick={onClose}>Keep editing</button><button type="button" className="button button-primary" onClick={onConfirm}>{confirmation.label}<ArrowRight size={16} aria-hidden="true" /></button></div></Dialog>
}
