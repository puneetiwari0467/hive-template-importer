import { ArrowDownToLine, ArrowUpRight, Copy, FileSpreadsheet, LockKeyhole, Plus } from 'lucide-react'
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
  onDuplicate,
}: {
  templates: TemplateSummary[]
  selectedId?: string
  disabled: boolean
  ready: boolean
  loading: boolean
  onImport: () => void
  onSelect: (id: string) => void
  onDuplicate: () => void
}) {
  return (
    <aside className="workspace-sidebar" aria-label="Template library">
      <a className="brand" href="#main-content" aria-label="Template Studio, skip to workspace">
        <svg className="brand-mark" viewBox="0 0 40 44" fill="none" aria-hidden="true">
          <path d="M20 2 37 12v20L20 42 3 32V12L20 2Z" stroke="currentColor" strokeWidth="2.4" />
          <path d="M13 14v16m14-16v16M13 22h14" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
        </svg>
        <span><strong>Template<br />Studio<span className="brand-dot">.</span></strong><small>HIVE INSPECT ASSESSMENT</small></span>
      </a>

      <div className="workspace-label"><span className="workspace-avatar">TS</span><div><strong>Your workspace</strong><span>Personal template library</span></div><LockKeyhole size={14} aria-hidden="true" /></div>

      <div className="sidebar-library">
        <div className="sidebar-heading"><span>TEMPLATES</span><span className="sidebar-count">{formatNumber(templates.length)}</span></div>
        <button type="button" className="sidebar-import" disabled={disabled || !ready} onClick={onImport}><Plus size={18} aria-hidden="true" />Import a template<ArrowUpRight size={14} className="push-right" aria-hidden="true" /></button>

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
        {selectedId && <button type="button" className="sidebar-secondary" disabled={disabled || !ready} onClick={onDuplicate}><Copy size={16} aria-hidden="true" />Duplicate selected template</button>}
      </div>

      <div className="sidebar-bottom">
        <a className="sample-link" href={sampleUrl} download><ArrowDownToLine size={17} aria-hidden="true" /><span>Download sample workbook<small>Try an import without customer data</small></span></a>
        <div className="privacy-note"><LockKeyhole size={17} aria-hidden="true" /><div><strong>Private to this browser</strong><p>No login needed. Only your workspace credential is stored here; templates live on the server. Clearing site data removes your access.</p></div></div>
        <p className="demo-label"><span aria-hidden="true" />Demo data only. No real customer data.</p>
        <div className="sidebar-signoff">A LITTLE LESS ADMIN.<br /><span>A little more inspecting.</span></div>
      </div>
    </aside>
  )
}
