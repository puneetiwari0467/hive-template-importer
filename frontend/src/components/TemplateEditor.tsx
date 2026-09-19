import { useMemo, useState } from 'react'
import type { Dispatch } from 'react'
import { AlignLeft, ArrowUpRight, ChevronDown, CircleAlert, Code2, Eye, Info, Layers3, MessageSquare, Search, TriangleAlert } from 'lucide-react'
import type { TemplateComment, TemplateDetail, TemplateItem } from '../contracts'
import { commentPreview, sourcePlainText } from '../lib/preview'
import type { DraftAction, ItemMatch, SectionMatch } from '../lib/template'
import { commentTone, countSectionComments, formatNumber } from '../lib/template'
import { CommentSourceAudit } from './SourceAudit'

function CommentEditor({
  comment,
  savedComment,
  defaultOpen,
  disabled,
  onChange,
}: {
  comment: TemplateComment
  savedComment?: TemplateComment
  defaultOpen: boolean
  disabled: boolean
  onChange: (change: Partial<Pick<TemplateComment, 'name' | 'contentHtml'>>) => void
}) {
  const [open, setOpen] = useState(defaultOpen)
  const tone = commentTone(comment.type)
  const TypeIcon = tone === 'limit' ? TriangleAlert : tone === 'defect' ? CircleAlert : Info
  const safeHtml = useMemo(() => commentPreview(comment, savedComment), [comment, savedComment])
  const contentChanged = savedComment ? comment.contentHtml !== savedComment.contentHtml : false
  const nameChanged = savedComment ? comment.name !== savedComment.name : false

  return (
    <article className={`comment-card comment-${tone} ${open ? 'is-open' : ''}`}>
      <button
        type="button"
        className="comment-toggle"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-controls={`comment-${comment.id}`}
      >
        <span className={`comment-type-icon tone-${tone}`}><TypeIcon size={16} aria-hidden="true" /></span>
        <span className="comment-toggle-text"><strong>{comment.name || 'Unnamed comment'}</strong>{!open && <span className="comment-excerpt">{sourcePlainText(comment.contentHtml) || 'No comment content'}</span>}</span>
        {(contentChanged || nameChanged) && <span className="edited-dot" title="Unsaved changes" aria-label="Unsaved changes" />}
        <span className={`type-badge tone-${tone}`}>{comment.type || 'Unspecified'}</span>
        <ChevronDown size={16} className="disclosure-icon" aria-hidden="true" />
      </button>
      {open && (
        <div className="comment-body" id={`comment-${comment.id}`}>
          <fieldset className="editing-fields" disabled={disabled}>
            <label className="field-label" htmlFor={`comment-name-${comment.id}`}>Comment name</label>
            <input id={`comment-name-${comment.id}`} className="text-input comment-name-input" value={comment.name} onChange={(event) => onChange({ name: event.target.value })} />
            <div className="comment-workbench">
              <div className="source-editor">
                <div className="editor-label"><label htmlFor={`comment-content-${comment.id}`}><Code2 size={15} aria-hidden="true" />Comment text / HTML</label><span>Editable source</span></div>
                <textarea
                  id={`comment-content-${comment.id}`}
                  aria-describedby={`source-help-${comment.id}`}
                  className="comment-textarea"
                  value={comment.contentHtml}
                  onChange={(event) => onChange({ contentHtml: event.target.value })}
                  rows={7}
                  spellCheck={false}
                />
                <p className="editor-help" id={`source-help-${comment.id}`}>Plain text or raw HTML. Unedited source is saved exactly as received.</p>
              </div>
              <div className="preview-pane">
                <div className="editor-label"><span><Eye size={15} aria-hidden="true" />Formatted preview</span><span>Display only</span></div>
                {safeHtml.trim()
                  ? <div className="formatted-preview" tabIndex={0} role="region" aria-label={`Formatted preview of ${comment.name || 'comment'}`} dangerouslySetInnerHTML={{ __html: safeHtml }} />
                  : <div className="formatted-preview empty-preview">No displayable content. The source field is still preserved.</div>}
                <p className="editor-help">Safe view: no active links, external images, embeds or source styling.</p>
              </div>
            </div>
          </fieldset>
          <CommentSourceAudit comment={savedComment ?? comment} />
        </div>
      )}
    </article>
  )
}

function ItemEditor({
  match,
  savedComments,
  index,
  defaultOpen,
  disabled,
  onName,
  onComment,
}: {
  match: ItemMatch
  savedComments: Map<string, TemplateComment>
  index: number
  defaultOpen: boolean
  disabled: boolean
  onName: (name: string) => void
  onComment: (commentId: string, change: Partial<Pick<TemplateComment, 'name' | 'contentHtml'>>) => void
}) {
  const [open, setOpen] = useState(defaultOpen)
  const { item, comments } = match
  return (
    <article className={`item-card ${open ? 'is-open' : ''}`}>
      <div className="item-heading">
        <span className="item-number">{String(index + 1).padStart(2, '0')}</span>
        <div className="item-name-field"><label htmlFor={`item-name-${item.id}`}>ITEM NAME</label><input id={`item-name-${item.id}`} value={item.name} onChange={(event) => onName(event.target.value)} disabled={disabled} /></div>
        <span className="item-comment-count"><MessageSquare size={14} aria-hidden="true" />{formatNumber(comments.length)}<span className="sr-only"> comments displayed</span></span>
        <button className="icon-button item-collapse" type="button" aria-label={`${open ? 'Collapse' : 'Expand'} item ${item.name || 'Unnamed item'}`} aria-expanded={open} aria-controls={`item-${item.id}`} onClick={() => setOpen(!open)}><ChevronDown size={19} className="disclosure-icon" aria-hidden="true" /></button>
      </div>
      {open && <div className="item-comments" id={`item-${item.id}`}>
        {comments.map((comment, commentIndex) => <CommentEditor key={comment.id} comment={comment} savedComment={savedComments.get(comment.id)} defaultOpen={commentIndex === 0} disabled={disabled} onChange={(change) => onComment(comment.id, change)} />)}
        {comments.length === 0 && <p className="no-comments">This item has no imported comments. Its place in the template is preserved.</p>}
      </div>}
    </article>
  )
}

export function TemplateEditor({
  match,
  template,
  savedTemplate,
  dispatch,
  disabled,
  query,
  onClearSearch,
  onReview,
}: {
  match?: SectionMatch
  template: TemplateDetail
  savedTemplate: TemplateDetail
  dispatch: Dispatch<DraftAction>
  disabled: boolean
  query: string
  onClearSearch: () => void
  onReview: () => void
}) {
  const savedComments = useMemo(() => new Map(savedTemplate.sections.flatMap((section) =>
    section.items.flatMap((item) => item.comments.map((comment) => [comment.id, comment] as const)),
  )), [savedTemplate])
  if (!match) return <div className="editor-no-selection"><Search size={30} aria-hidden="true" /><h2>{query.trim() ? 'No matching sections' : 'No sections in this template'}</h2><p>{query.trim() ? 'Search by item, comment name or a phrase in the comment text.' : 'Review the import notes to understand how this workbook was handled.'}</p><button className="button button-outline" type="button" onClick={query.trim() ? onClearSearch : onReview}>{query.trim() ? 'Clear search' : 'Review import'}</button></div>
  const { section, items } = match
  const position = template.sections.findIndex((candidate) => candidate.id === section.id) + 1
  const itemIndex = new Map<string, number>(section.items.map((item: TemplateItem, index) => [item.id, index]))

  return (
    <section className="section-editor" aria-label={`Editing section ${section.name}`}>
      <div className="section-editor-heading">
        <div className="section-editor-icon"><Layers3 size={23} aria-hidden="true" /></div>
        <div className="section-title-field"><label htmlFor={`section-name-${section.id}`}>SECTION {String(position).padStart(2, '0')} <span>OF {formatNumber(template.sections.length)}</span></label><input id={`section-name-${section.id}`} aria-label="Section name" value={section.name} onChange={(event) => dispatch({ type: 'section', sectionId: section.id, name: event.target.value })} disabled={disabled} /><p>{formatNumber(section.items.length)} items <span aria-hidden="true">·</span> {formatNumber(countSectionComments(section))} comments</p></div>
        <span className="hierarchy-label"><AlignLeft size={15} aria-hidden="true" />Template content</span>
      </div>
      <div className="editor-guidance"><Info size={15} aria-hidden="true" /><p>Edit names and comment content below. Source fields and hierarchy stay intact.</p><button className="text-button" type="button" onClick={onReview}>Import review<ArrowUpRight size={14} aria-hidden="true" /></button></div>
      {query.trim() && <div className="search-banner"><Search size={15} aria-hidden="true" /><span>Showing matching content for <strong>“{query}”</strong>. The complete template will still be saved.</span><button className="text-button" type="button" onClick={onClearSearch}>Show all</button></div>}
      <div className="items-list">
        {items.map((itemMatch, index) => <ItemEditor
          key={`${itemMatch.item.id}:${query.trim()}`}
          match={itemMatch}
          savedComments={savedComments}
          index={itemIndex.get(itemMatch.item.id) ?? index}
          defaultOpen={index === 0 || Boolean(query.trim())}
          disabled={disabled}
          onName={(name) => dispatch({ type: 'item', sectionId: section.id, itemId: itemMatch.item.id, name })}
          onComment={(commentId, change) => dispatch({ type: 'comment', sectionId: section.id, itemId: itemMatch.item.id, commentId, change })}
        />)}
      </div>
      <div className="section-end"><span /><span>{formatNumber(items.length)} {query.trim() ? 'matching' : ''} items in this section</span><span /></div>
    </section>
  )
}
