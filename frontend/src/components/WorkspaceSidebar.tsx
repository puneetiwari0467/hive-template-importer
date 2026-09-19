import { ArrowDownToLine, FileSpreadsheet, LockKeyhole, Plus } from 'lucide-react'
import type { TemplateSummary } from '../contracts'
import { sampleUrl } from '../lib/api'
import { formatNumber } from '../lib/template'

export function WorkspaceSidebar({
  templates,
  selectedId,
  disabled,
  ready,
  loading,
  onImport,
  onSelect,
}: {
  templates: TemplateSummary[]
  selectedId?: string
  disabled: boolean
  ready: boolean
  loading: boolean
  onImport: () => void
  onSelect: (id: string) => void
}) {
  return (
    <aside className="workspace-sidebar" aria-label="Template library">
      <a className="brand" href="#main-content" aria-label="Template Studio, skip to workspace">
        <svg className="brand-mark" viewBox="0 0 40 44" fill="none" aria-hidden="true">
          <path d="M20 2 37 12v20L20 42 3 32V12L20 2Z" stroke="currentColor" strokeWidth="2.4" />
          <path d="M13 14v16m14-16v16M13 22h14" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
        </svg>
        <span><strong>Template Studio<span className="brand-dot">.</span></strong><small>HIVE INSPECT ASSESSMENT</small></span>
      </a>
      <button type="button" className="sidebar-import" disabled={disabled || !ready} onClick={onImport}><Plus size={18} aria-hidden="true" />Import a template</button>
      <div className="compact-template-picker">
        <label className="sr-only" htmlFor="workspace-template-picker">Current template</label>
        <select id="workspace-template-picker" value={selectedId ?? ''} disabled={disabled || !ready || templates.length === 0} onChange={(event) => onSelect(event.target.value)}>
          <option value="" disabled>{loading ? 'Opening templates...' : 'Select a template'}</option>
          {templates.map((template) => <option key={template.id} value={template.id}>{template.name}</option>)}
        </select>
      </div>

      <div className="sidebar-library">
        <div className="sidebar-heading"><h2>Templates</h2><span className="sidebar-count">{formatNumber(templates.length)}</span></div>

        <nav className="template-list" aria-label="Saved templates">
          {loading && <p className="sidebar-hint">Opening your private library…</p>}
          {!loading && ready && templates.length === 0 && <p className="sidebar-hint">Your imported templates will appear here.</p>}
          {templates.map((template) => (
            <button
              type="button"
              key={template.id}
              className={`template-link ${template.id === selectedId ? 'is-active' : ''}`}
              aria-current={template.id === selectedId ? 'page' : undefined}
              disabled={disabled || !ready}
              onClick={() => onSelect(template.id)}
              title={template.name}
            >
              <FileSpreadsheet size={19} aria-hidden="true" />
              <span className="template-link-text"><strong>{template.name}</strong><small>{formatNumber(template.counts.sections)} sections <span aria-hidden="true">·</span> {formatNumber(template.counts.comments)} comments</small></span>
              {template.id === selectedId && <span className="active-template-dot" aria-hidden="true" />}
            </button>
          ))}
        </nav>
      </div>

      <div className="sidebar-bottom">
        <a className="sample-link" href={sampleUrl} download><ArrowDownToLine size={17} aria-hidden="true" /><span>Download sample workbook</span></a>
        <details className="sidebar-help"><summary><LockKeyhole size={15} aria-hidden="true" />Private demo workspace</summary><p>No login needed. Only your workspace credential is stored in this browser; templates live on the server. Clearing site data removes your access. Use sample data only, not real customer information.</p></details>
      </div>
    </aside>
  )
}
