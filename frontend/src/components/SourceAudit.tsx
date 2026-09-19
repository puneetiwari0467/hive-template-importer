import { useMemo, useState } from 'react'
import { Archive, ChevronDown, FileText, Search, X } from 'lucide-react'
import type { TemplateComment, TemplateDetail } from '../contracts'
import { formatNumber } from '../lib/template'

export function SourceRecord({ comment }: { comment: TemplateComment }) {
  const entries = Object.entries(comment.metadata)
  return (
    <div className="source-record">
      <dl className="source-location">
        <div><dt>Worksheet</dt><dd>{comment.sourceSheet || 'Not provided'}</dd></div>
        <div><dt>Source row</dt><dd>{comment.sourceRow}</dd></div>
        <div><dt>Source type</dt><dd>{comment.type || 'Not provided'}</dd></div>
      </dl>
      <div className="source-block-heading"><h4>Original comment text / HTML</h4><span className="read-only-label">Read-only · unchanged by editing</span></div>
      <pre className="original-source" tabIndex={0} role="region" aria-label="Original comment source, read-only">{comment.originalHtml || '(Empty source field)'}</pre>
      <div className="source-block-heading"><h4>Original export fields</h4><span className="read-only-label">{formatNumber(entries.length)} fields</span></div>
      {entries.length > 0
        ? <div className="metadata-table-wrapper" tabIndex={0} role="region" aria-label="Original metadata, scrollable"><table className="metadata-table"><thead><tr><th scope="col">Source column</th><th scope="col">Preserved value</th></tr></thead><tbody>{entries.map(([key, value]) => <tr key={key}><th scope="row">{key}</th><td>{value || <span className="empty-value">(empty)</span>}</td></tr>)}</tbody></table></div>
        : <p className="muted">No additional metadata was returned for this record.</p>}
    </div>
  )
}

export function CommentSourceAudit({ comment }: { comment: TemplateComment }) {
  return <details className="comment-source-audit"><summary><Archive size={16} aria-hidden="true" /><span>Original source & metadata</span><span className="source-row-chip">Row {comment.sourceRow}</span><ChevronDown size={15} aria-hidden="true" /></summary><SourceRecord comment={comment} /></details>
}

export function SourceAudit({ template }: { template: TemplateDetail }) {
  const [query, setQuery] = useState('')
  const [limit, setLimit] = useState(25)
  const records = useMemo(() => template.sections.flatMap((section) =>
    section.items.flatMap((item) => item.comments.map((comment) => ({ section, item, comment }))),
  ), [template])
  const filtered = useMemo(() => {
    const needle = query.trim().toLocaleLowerCase()
    if (!needle) return records
    return records.filter(({ section, item, comment }) =>
      `${section.name}\n${item.name}\n${comment.name}\n${comment.originalHtml}\n${comment.sourceSheet}\n${comment.sourceRow}\n${Object.entries(comment.metadata).flat().join('\n')}`
        .toLocaleLowerCase().includes(needle),
    )
  }, [records, query])

  return (
    <div className="review-page audit-page">
      <div className="review-heading"><div className="review-heading-icon"><Archive size={25} aria-hidden="true" /></div><div><p className="eyebrow">THE ORIGINAL, ALWAYS WITHIN REACH</p><h2>Source audit</h2><p>Read the untouched comment field and every metadata value returned by the importer. Source values are text only: links and HTML are never executed here.</p></div></div>
      <div className="audit-toolbar"><div className="search-field"><Search size={17} aria-hidden="true" /><input type="search" aria-label="Search original source records" placeholder="Find a source row, phrase or metadata value…" value={query} onChange={(event) => { setQuery(event.target.value); setLimit(25) }} />{query && <button className="icon-button" aria-label="Clear source search" type="button" onClick={() => { setQuery(''); setLimit(25) }}><X size={15} aria-hidden="true" /></button>}</div><p role="status">{formatNumber(filtered.length)} of {formatNumber(records.length)} source records</p></div>
      <div className="audit-records">
        {filtered.slice(0, limit).map(({ section, item, comment }) => (
          <details className="audit-record-card" key={comment.id}>
            <summary><FileText size={19} aria-hidden="true" /><span><small>{section.name} / {item.name}</small><strong>{comment.name || 'Unnamed comment'}</strong></span><span className="source-row-chip">{comment.sourceSheet} · Row {comment.sourceRow}</span><ChevronDown size={17} aria-hidden="true" /></summary>
            <SourceRecord comment={comment} />
          </details>
        ))}
      </div>
      {filtered.length === 0 && <div className="audit-no-results"><Search size={28} aria-hidden="true" /><h3>No source records match that search</h3><p>Try a different phrase, worksheet name or row number.</p></div>}
      {filtered.length > limit && <div className="load-more"><p>Showing {formatNumber(Math.min(limit, filtered.length))} of {formatNumber(filtered.length)} records</p><button type="button" className="button button-outline" onClick={() => setLimit((current) => current + 25)}>Show next {Math.min(25, filtered.length - limit)} records<ChevronDown size={16} aria-hidden="true" /></button></div>}
      <p className="review-footnote">Editing or renaming a template never changes its imported source audit. No preservation score is inferred from these records.</p>
    </div>
  )
}
