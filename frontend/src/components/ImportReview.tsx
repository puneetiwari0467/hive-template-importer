import { useMemo, useState } from 'react'
import { Archive, ArrowRight, Check, ChevronDown, FileCheck2, FileSpreadsheet, Info, ListChecks, TriangleAlert } from 'lucide-react'
import type { ImportWarning, TemplateDetail } from '../contracts'
import type { WarningGroup } from '../lib/template'
import { formatDate, formatNumber, groupWarnings } from '../lib/template'

function WarningLocation({ warning }: { warning: ImportWarning }) {
  const parts = [
    warning.sheet,
    warning.row != null ? `Row ${warning.row}` : null,
    warning.column ? `Column: ${warning.column}` : null,
  ].filter(Boolean)
  return <div className="warning-location"><FileSpreadsheet size={13} aria-hidden="true" />{parts.length ? parts.join(' · ') : 'Workbook-level notice'}</div>
}

function ImportWarningGroup({ group }: { group: WarningGroup }) {
  const [shown, setShown] = useState(3)
  const WarningIcon = group.severity === 'warning' ? TriangleAlert : Info
  return (
    <div className={`warning-group warning-group-${group.severity}`}>
      <div className="warning-group-heading"><span className="warning-group-icon"><WarningIcon size={17} aria-hidden="true" /></span><div><h4>{group.code.replace(/_/g, ' ').toLowerCase()}</h4><code>{group.code}</code></div><span className={`type-badge ${group.severity === 'warning' ? 'tone-limit' : 'tone-info'}`}>{group.severity === 'warning' ? 'Warning' : 'Information'} · {formatNumber(group.warnings.length)}</span></div>
      <ul className="warning-records">{group.warnings.slice(0, shown).map((warning, index) => <li key={index}><p>{warning.message}</p><WarningLocation warning={warning} />{warning.value != null && <details className="warning-source-value"><summary>Source value</summary><pre>{warning.value || '(empty)'}</pre></details>}</li>)}</ul>
      {shown < group.warnings.length && <button type="button" className="text-button warning-show-more" onClick={() => setShown((current) => current + 25)}>Show {Math.min(25, group.warnings.length - shown)} more source notices ({formatNumber(group.warnings.length - shown)} remaining)<ChevronDown size={14} aria-hidden="true" /></button>}
    </div>
  )
}

export function ImportReview({ template, onAudit }: { template: TemplateDetail; onAudit: () => void }) {
  const report = template.importReport
  const groups = useMemo(() => groupWarnings(report.warnings), [report.warnings])
  const warningCount = report.warnings.filter((warning) => warning.severity === 'warning').length
  const infoCount = report.warnings.length - warningCount
  const metadataColumns = useMemo(() => new Set(template.sections.flatMap((section) =>
    section.items.flatMap((item) => item.comments.flatMap((comment) => Object.keys(comment.metadata))),
  )).size, [template])
  const rowsAgree = report.sourceRows === report.importedRows

  return (
    <div className="review-page">
      <div className="review-heading"><div className="review-heading-icon"><FileCheck2 size={27} aria-hidden="true" /></div><div><p className="eyebrow">A CLEAR VIEW OF YOUR MIGRATION</p><h2>Import review</h2><p>Actual importer counts, original source fields and specific limitations. No estimated preservation scores, and no hidden changes to your source.</p></div><button className="button button-outline" type="button" onClick={onAudit}><Archive size={16} aria-hidden="true" />Inspect source</button></div>

      <div className="row-metrics">
        <div><span className="metric-caption">SOURCE ROWS</span><strong>{formatNumber(report.sourceRows)}</strong><p>Rows reported from the workbook</p></div>
        <span className="row-metric-arrow" aria-hidden="true"><ArrowRight size={24} /></span>
        <div><span className="metric-caption">IMPORTED ROWS</span><strong>{formatNumber(report.importedRows)}</strong><p>Rows accepted by the importer</p></div>
        <div><span className="metric-caption">SOURCE COLUMNS</span><strong>{formatNumber(report.sourceColumns.length)}</strong><p>{formatNumber(metadataColumns)} distinct metadata fields retained</p></div>
      </div>
      <div className={`row-coverage-note ${rowsAgree ? '' : 'row-coverage-warning'}`}>{rowsAgree ? <Check size={16} aria-hidden="true" /> : <TriangleAlert size={17} aria-hidden="true" />}<p>{rowsAgree ? 'Source and imported row totals agree.' : 'Source and imported row totals differ. Review the notes and source warnings before relying on this template.'} <span>Row totals alone do not measure formatting or field-level fidelity.</span></p></div>

      <div className="review-grid">
        <section className="review-card"><div className="review-card-heading"><ListChecks size={19} aria-hidden="true" /><h3>Import notes</h3><span className="count-pill">{formatNumber(report.notes.length)}</span></div>{report.notes.length ? <ul className="import-notes">{report.notes.map((note, index) => <li key={index}><span>{String(index + 1).padStart(2, '0')}</span><p>{note}</p></li>)}</ul> : <p className="review-empty-copy">The importer returned no additional notes.</p>}</section>
        <section className="review-card"><div className="review-card-heading"><FileSpreadsheet size={19} aria-hidden="true" /><h3>Workbook identity</h3></div><dl className="identity-list"><div><dt>Original file</dt><dd>{template.sourceFileName}</dd></div><div><dt>Imported record</dt><dd>{formatDate(template.createdAt)}</dd></div><div><dt>Last saved</dt><dd>{formatDate(template.updatedAt)}</dd></div><div><dt>Source SHA-256</dt><dd><code>{template.sourceSha256}</code></dd></div>{template.duplicateOf && <div><dt>Independent copy of</dt><dd><code>{template.duplicateOf}</code></dd></div>}</dl></section>
      </div>

      <section className="review-card source-columns-card"><div className="review-card-heading"><Archive size={19} aria-hidden="true" /><h3>Source columns & preserved metadata</h3></div><p className="review-card-description">Original headers are shown below. Open a source record to inspect its original comment text and complete metadata, including fields this editor does not interpret.</p><div className="source-column-chips">{report.sourceColumns.map((column, index) => <span key={`${index}:${column}`}>{column}</span>)}</div><button className="text-button" type="button" onClick={onAudit}>Browse read-only source records<ArrowRight size={15} aria-hidden="true" /></button></section>

      <section className="warnings-section" aria-labelledby="warnings-title">
        <div className="warnings-heading"><div><p className="eyebrow">KNOW WHAT TO REVIEW</p><h3 id="warnings-title">Warnings & display limitations</h3></div><span>{formatNumber(warningCount)} warnings · {formatNumber(infoCount)} informational notices</span></div>
        <div className="preview-policy"><Info size={18} aria-hidden="true" /><p><strong>Safe preview is intentionally limited.</strong> Images, embedded content, active links and source styling are not rendered. Editable source stays intact until you change it; the original source audit is read-only.</p></div>
        {groups.length
          ? <div className="warning-groups">{groups.map((group) => <ImportWarningGroup key={group.key} group={group} />)}</div>
          : <div className="no-warnings"><Check size={19} aria-hidden="true" /><div><strong>No import warnings were reported</strong><p>This means the importer raised no notices; it is not a guarantee that every source feature is editable.</p></div></div>}
      </section>
    </div>
  )
}
