import { ChevronRight, ListTree, Search, X } from 'lucide-react'
import type { TemplateSearch } from '../lib/template'
import { countSectionComments, formatNumber } from '../lib/template'

export function SectionNavigator({
  result,
  query,
  onQuery,
  selectedId,
  onSelect,
  totalSections,
}: {
  result: TemplateSearch
  query: string
  onQuery: (query: string) => void
  selectedId?: string
  onSelect: (id: string) => void
  totalSections: number
}) {
  return (
    <aside className="section-rail" aria-label="Template structure">
      <div className="structure-heading"><ListTree size={18} aria-hidden="true" /><h2>Sections</h2><span className="count-pill">{formatNumber(totalSections)}</span></div>
      <div className="compact-section-picker">
        <label className="sr-only" htmlFor="section-picker">Select section</label>
        <select id="section-picker" value={selectedId ?? ''} disabled={result.sections.length === 0} onChange={(event) => onSelect(event.target.value)}>
          {result.sections.length === 0 && <option value="">No matching sections</option>}
          {result.sections.map(({ section, ordinal }) => <option key={section.id} value={section.id}>{String(ordinal).padStart(2, '0')} - {section.name || 'Untitled section'}</option>)}
        </select>
      </div>
      <div className="search-field"><Search size={16} aria-hidden="true" /><input aria-label="Search items and comments" type="search" placeholder="Find items or comments…" value={query} onChange={(event) => onQuery(event.target.value)} />{query && <button className="icon-button" type="button" aria-label="Clear search" onClick={() => onQuery('')}><X size={14} aria-hidden="true" /></button>}</div>
      {result.isSearching
        ? <p className="search-count" role="status">{formatNumber(result.itemCount)} matching items · {formatNumber(result.commentCount)} comments</p>
        : null}
      <nav className="section-list" aria-label="Sections">
        {result.sections.map(({ section, ordinal, matchingItems, matchingComments }) => (
          <button
            key={section.id}
            type="button"
            className={`section-link ${selectedId === section.id ? 'is-active' : ''}`}
            aria-current={selectedId === section.id ? 'true' : undefined}
            onClick={() => onSelect(section.id)}
          >
            <span className="section-number">{String(ordinal).padStart(2, '0')}</span>
            <span className="section-link-name">{section.name || 'Untitled section'}<small>{formatNumber(result.isSearching ? matchingItems : section.items.length)} items · {formatNumber(result.isSearching ? matchingComments : countSectionComments(section))} comments</small></span>
            <ChevronRight size={15} aria-hidden="true" />
          </button>
        ))}
        {result.sections.length === 0 && <div className="rail-empty"><Search size={24} aria-hidden="true" /><strong>No matching content</strong><p>Try a different item, comment name or phrase.</p><button className="text-button" type="button" onClick={() => onQuery('')}>Clear search</button></div>}
      </nav>
    </aside>
  )
}
