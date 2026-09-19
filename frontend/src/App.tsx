import { useEffect, useMemo, useRef, useState } from 'react'
import { Archive, ArrowRight, Check, ChevronRight, Copy, FileCheck2, FileSpreadsheet, Layers3, ListTree, LockKeyhole, MessageSquare, PanelTop, RefreshCw, Save, Upload, X } from 'lucide-react'
import { ConfirmDialog, DuplicateDialog, ImportDialog } from './components/Dialogs'
import type { Confirmation } from './components/Dialogs'
import { ImportReview } from './components/ImportReview'
import { SectionNavigator } from './components/SectionNavigator'
import { SourceAudit } from './components/SourceAudit'
import { TemplateEditor } from './components/TemplateEditor'
import { EmptyState, ErrorNotice, Spinner } from './components/Ui'
import { WorkspaceSidebar } from './components/WorkspaceSidebar'
import { useTemplateWorkspace } from './hooks/useTemplateWorkspace'
import { formatDate, formatNumber, searchTemplate } from './lib/template'

type WorkspaceView = 'editor' | 'review' | 'audit'

export default function App() {
  const workspace = useTemplateWorkspace()
  const { draft, saved, templates, phase, operation, dirty, dispatch } = workspace
  const [view, setView] = useState<WorkspaceView>('editor')
  const [sectionId, setSectionId] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [modal, setModal] = useState<'import' | 'duplicate' | null>(null)
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)
  const editorScroll = useRef<HTMLDivElement>(null)
  const busy = operation !== null
  const searchResult = useMemo(() => draft ? searchTemplate(draft, query) : null, [draft, query])
  const selectedSection = searchResult?.sections.find(({ section }) => section.id === sectionId) ?? searchResult?.sections[0]

  useEffect(() => {
    if (editorScroll.current) editorScroll.current.scrollTop = 0
  }, [selectedSection?.section.id, view])
  useEffect(() => {
    function saveShortcut(event: KeyboardEvent) {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's' && phase === 'ready') {
        event.preventDefault()
        if (dirty && !busy && !modal && !confirmation && draft?.name.trim()) void workspace.save()
      }
    }
    window.addEventListener('keydown', saveShortcut)
    return () => window.removeEventListener('keydown', saveShortcut)
  }, [workspace.save, dirty, busy, modal, confirmation, draft?.name, phase])

  function guard(action: Confirmation) {
    if (busy) return
    if (dirty) setConfirmation(action)
    else void action.run()
  }

  function resetNavigation(nextView: WorkspaceView) {
    setQuery('')
    setSectionId(null)
    setView(nextView)
  }

  function openImport() {
    guard({
      title: 'Import without saving these changes?',
      description: 'A successful import will open a different template and discard this unsaved draft. If the import fails or you cancel, your draft stays here.',
      label: 'Continue to import',
      run: () => { workspace.clearActionError(); setModal('import') },
    })
  }

  function openDuplicate() {
    guard({
      title: 'Duplicate the saved version?',
      description: 'Your unsaved edits are not part of the copy. Creating a copy will open it and discard this draft. Cancel and save first if you want those edits included.',
      label: 'Continue to duplicate',
      run: () => { workspace.clearActionError(); setModal('duplicate') },
    })
  }

  function reloadTemplate() {
    guard({
      title: 'Reload and discard your draft?',
      description: 'This replaces every unsaved change in this template with the latest version from the server. Your imported source audit will not be changed.',
      label: 'Reload latest version',
      run: async () => { await workspace.reload() },
    })
  }

  function selectTemplate(id: string) {
    if (id === draft?.id) return
    guard({
      title: 'Switch templates without saving?',
      description: 'Your edits across all sections of this template have not been saved. Switching successfully will discard them. If the next template cannot be loaded, this draft stays here.',
      label: 'Discard & switch',
      run: async () => { if (await workspace.selectTemplate(id)) resetNavigation('editor') },
    })
  }

  function closeModal() {
    if (busy) return
    setModal(null)
    workspace.clearActionError()
  }

  function retryAction() {
    if (workspace.failedOperation === 'save') { void workspace.save(); return }
    guard({
      title: 'Retry loading and discard this draft?',
      description: 'Your current draft is kept if loading fails. A successful retry replaces it with the saved template from the server.',
      label: 'Retry loading',
      run: async () => { if (await workspace.retry()) resetNavigation('editor') },
    })
  }

  const warnings = draft?.importReport.warnings.filter((warning) => warning.severity === 'warning').length ?? 0
  const notices = draft?.importReport.warnings.length ?? 0
  const notes = draft?.importReport.notes.length ?? 0
  const nameInvalid = draft !== null && !draft.name.trim()

  return (
    <>
      <a className="skip-link" href="#main-content">Skip to workspace</a>
      <div className="app-shell">
        <WorkspaceSidebar templates={templates} selectedId={draft?.id} disabled={busy} ready={phase === 'ready'} loading={phase === 'loading'} onImport={openImport} onSelect={selectTemplate} onDuplicate={openDuplicate} />
        <main className="work-area" id="main-content" tabIndex={-1}>
          <div className="topbar"><div className="breadcrumb"><PanelTop size={16} aria-hidden="true" /><span>Workspace</span><ChevronRight size={13} aria-hidden="true" /><strong>Template editor</strong></div><span className="private-workspace" title="No account is needed. This browser stores only your workspace credential. Clearing site data removes access, not server records."><LockKeyhole size={13} aria-hidden="true" />PRIVATE TO THIS BROWSER</span></div>

          {phase === 'loading' && <div className="loading-workspace" aria-busy="true"><div className="loading-copy"><span className="loading-emblem"><Layers3 size={32} aria-hidden="true" /></span><p className="eyebrow">A HOME FOR YOUR TEMPLATES</p><h1>Opening your workspace.</h1><p>Loading saved templates. On your first visit, we’ll import the bundled sample so you can explore right away.</p><div role="status"><Spinner label="Connecting to the workspace API…" /></div></div><div className="skeleton-layout" aria-hidden="true"><div className="skeleton-block" /><div><div className="skeleton-line" /><div className="skeleton-line short" /><div className="skeleton-block" /></div></div></div>}

          {phase === 'error' && workspace.bootError && <div className="boot-error"><EmptyState icon={<FileSpreadsheet size={31} aria-hidden="true" />} title="Your workspace isn’t ready yet."><p>The editor needs the workspace API. This is not an empty library, and no local placeholder templates have been loaded.</p></EmptyState><ErrorNotice error={workspace.bootError} retry={() => { void workspace.bootstrap() }} />{workspace.bootError.status === 401 && <div className="new-workspace-recovery"><p>If the saved credential is permanently invalid, you can explicitly replace it with a new demo workspace. Existing server records are not deleted, but this browser will lose access to them.</p><button type="button" className="button button-outline" onClick={() => setConfirmation({ title: 'Replace this browser’s workspace?', description: 'A new credential will replace the old one only after creation succeeds. The previous workspace will no longer be accessible from this browser, and this does not delete its server records.', label: 'Create a new workspace', run: async () => { await workspace.bootstrap(true) } })}>Create a new demo workspace<ArrowRight size={15} aria-hidden="true" /></button></div>}</div>}

          {phase === 'ready' && !draft && <EmptyState icon={<FileSpreadsheet size={32} aria-hidden="true" />} title="Your library is ready for its first template."><p>Import an Excel template export, or download the sample workbook from the sidebar. Templates are stored in your private server-side workspace.</p><button className="button button-primary" type="button" onClick={openImport}><Upload size={17} aria-hidden="true" />Import a template</button></EmptyState>}

          {phase === 'ready' && draft && saved && searchResult && <>
            <header className="template-header">
              <h1 className="sr-only">Template editor: {draft.name || 'Unnamed template'}</h1>
              <div className="template-heading-row"><div className="template-heading"><p className="eyebrow">YOUR EXPERTISE. A BETTER STARTING POINT.</p><label className="sr-only" htmlFor="template-name">Template name</label><input className="template-title-input" id="template-name" value={draft.name} onChange={(event) => dispatch({ type: 'name', name: event.target.value })} disabled={busy} aria-invalid={nameInvalid} aria-describedby={nameInvalid ? 'template-name-error' : undefined} />{nameInvalid && <p id="template-name-error" className="field-error">Give this template a name before saving.</p>}<div className="template-source-line"><FileSpreadsheet size={14} aria-hidden="true" /><span title={draft.sourceFileName}>{draft.sourceFileName}</span><span className="source-separator" aria-hidden="true">·</span><span title={formatDate(saved.updatedAt)}>Saved {formatDate(saved.updatedAt)}</span></div></div><div className="header-actions"><button type="button" className="button button-outline" onClick={openDuplicate} disabled={busy}><Copy size={16} aria-hidden="true" /><span>Duplicate</span></button><button type="button" className="button button-primary save-button" disabled={busy || !dirty || nameInvalid} onClick={() => { void workspace.save() }}>{operation === 'save' ? <Spinner label="Saving…" /> : <><Save size={16} aria-hidden="true" />Save changes</>}</button></div></div>
              <div className="template-stats"><div className="template-stat"><Layers3 size={19} aria-hidden="true" /><strong>{formatNumber(draft.counts.sections)}</strong><span>Sections</span></div><div className="template-stat"><ListIcon /><strong>{formatNumber(draft.counts.items)}</strong><span>Items</span></div><div className="template-stat"><MessageSquare size={19} aria-hidden="true" /><strong>{formatNumber(draft.counts.comments)}</strong><span>Comments</span></div><div className="template-stat source-stat"><FileSpreadsheet size={19} aria-hidden="true" /><span><strong>{formatNumber(draft.importReport.importedRows)}</strong> imported rows<small>from {formatNumber(draft.importReport.sourceRows)} source rows</small></span></div><div className={`save-state ${dirty ? 'is-dirty' : ''}`} role="status">{dirty ? <span className="status-dot" aria-hidden="true" /> : <Check size={14} aria-hidden="true" />}<span>{dirty ? 'Unsaved changes' : `Saved · version ${saved.version}`}<small>{dirty ? 'Save applies to all sections' : 'Changes stay in this workspace'}</small></span></div></div>
              <div className={`import-summary ${warnings > 0 ? 'has-warnings' : ''}`}><FileCheck2 size={19} aria-hidden="true" /><div><strong>Workbook imported. Source audit attached.</strong><span>{warnings > 0 ? `${formatNumber(warnings)} warnings to review` : 'No import warnings reported'} · {formatNumber(notices - warnings)} informational notices · {formatNumber(notes)} import notes</span></div><button type="button" className="text-button" onClick={() => setView('review')}>Review import<ArrowRight size={15} aria-hidden="true" /></button></div>
            </header>

            <div className="workspace-tabs"><div className="view-switcher" aria-label="Workspace views"><button type="button" className={view === 'editor' ? 'is-active' : ''} aria-pressed={view === 'editor'} onClick={() => setView('editor')}><PanelTop size={16} aria-hidden="true" />Editor</button><button type="button" className={view === 'review' ? 'is-active' : ''} aria-pressed={view === 'review'} onClick={() => setView('review')}><FileCheck2 size={16} aria-hidden="true" />Import review{notices > 0 && <span className="tab-count">{formatNumber(notices)}</span>}</button><button type="button" className={view === 'audit' ? 'is-active' : ''} aria-pressed={view === 'audit'} onClick={() => setView('audit')}><Archive size={16} aria-hidden="true" />Source audit</button></div><button type="button" className="reload-button" onClick={reloadTemplate} disabled={busy}>{operation === 'reload' ? <Spinner label="Reloading…" /> : <><RefreshCw size={14} aria-hidden="true" /><span>Reload saved</span></>}</button></div>

            {workspace.actionError && !modal && <div className="action-error-wrap"><ErrorNotice error={workspace.actionError} hasDraft={dirty} dismiss={workspace.clearActionError} reload={reloadTemplate} retry={workspace.actionError.status === 409 ? undefined : retryAction} /></div>}
            {workspace.notice && !dirty && <div className="success-notice" role="status"><Check size={16} aria-hidden="true" /><span>{workspace.notice}</span><button className="icon-button" type="button" aria-label="Dismiss success message" onClick={workspace.clearNotice}><X size={15} aria-hidden="true" /></button></div>}
            {operation === 'switch' && <div className="loading-template-banner" role="status"><Spinner label="Opening the selected template…" /><span>Your current draft stays here until loading succeeds.</span></div>}

            <div className={`workspace-content ${view !== 'editor' ? 'full-width-content' : ''}`}>
              {view === 'editor' && <SectionNavigator result={searchResult} query={query} onQuery={setQuery} selectedId={selectedSection?.section.id} onSelect={setSectionId} totalSections={draft.sections.length} />}
              <div className="content-scroll" ref={editorScroll} aria-busy={operation === 'switch' || operation === 'reload'}>
                {view === 'editor' && <TemplateEditor key={selectedSection?.section.id ?? 'empty'} match={selectedSection} template={draft} savedTemplate={saved} dispatch={dispatch} disabled={busy} query={query} onClearSearch={() => setQuery('')} onReview={() => setView('review')} />}
                {view === 'review' && <ImportReview key={draft.id} template={saved} onAudit={() => setView('audit')} />}
                {view === 'audit' && <SourceAudit key={draft.id} template={saved} />}
              </div>
            </div>
            <footer className="workspace-footer"><span><LockKeyhole size={12} aria-hidden="true" />Demo workspace · No real customer data</span><span>{view === 'editor' ? 'Edit the template. Keep the original.' : 'Original source fields are read-only.'}<kbd>Ctrl / ⌘ S</kbd></span></footer>
          </>}
        </main>
      </div>

      {modal === 'import' && <ImportDialog busy={operation === 'import'} error={workspace.actionError} hasDraft={dirty} onClose={closeModal} onSubmit={async (file, name) => { if (await workspace.importTemplate(file, name)) { setModal(null); resetNavigation('review') } }} />}
      {modal === 'duplicate' && saved && <DuplicateDialog sourceName={saved.name} busy={operation === 'duplicate'} error={workspace.actionError} onClose={closeModal} onSubmit={async (name) => { if (await workspace.duplicate(name)) { setModal(null); resetNavigation('editor') } }} />}
      {confirmation && <ConfirmDialog confirmation={confirmation} onClose={() => setConfirmation(null)} onConfirm={() => { const action = confirmation; setConfirmation(null); void action.run() }} />}
    </>
  )
}

function ListIcon() {
  return <ListTree size={19} aria-hidden="true" />
}
