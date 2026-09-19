import type {
  ImportWarning,
  TemplateComment,
  TemplateDetail,
  TemplateItem,
  TemplateSection,
  TemplateSummary,
  TemplateUpdate,
} from '../contracts'
import { sourcePlainText } from './preview'

export interface DraftState {
  saved: TemplateDetail | null
  draft: TemplateDetail | null
}

export type DraftAction =
  | { type: 'loaded'; template: TemplateDetail }
  | { type: 'name'; name: string }
  | { type: 'section'; sectionId: string; name: string }
  | { type: 'item'; sectionId: string; itemId: string; name: string }
  | {
      type: 'comment'
      sectionId: string
      itemId: string
      commentId: string
      change: Partial<Pick<TemplateComment, 'name' | 'contentHtml'>>
    }

export function toTemplateUpdate(template: TemplateDetail): TemplateUpdate {
  return {
    version: template.version,
    name: template.name,
    sections: template.sections.map((section) => ({
      id: section.id,
      name: section.name,
      items: section.items.map((item) => ({
        id: item.id,
        name: item.name,
        comments: item.comments.map((comment) => ({
          id: comment.id,
          name: comment.name,
          contentHtml: comment.contentHtml,
        })),
      })),
    })),
  }
}

export function isDraftDirty(state: DraftState): boolean {
  if (!state.draft || !state.saved || state.draft === state.saved) return false
  return JSON.stringify(toTemplateUpdate(state.draft)) !== JSON.stringify(toTemplateUpdate(state.saved))
}

export function draftReducer(state: DraftState, action: DraftAction): DraftState {
  if (action.type === 'loaded') return { draft: action.template, saved: action.template }
  if (!state.draft) return state
  if (action.type === 'name') return { ...state, draft: { ...state.draft, name: action.name } }

  return {
    ...state,
    draft: {
      ...state.draft,
      sections: state.draft.sections.map((section) => {
        if (section.id !== action.sectionId) return section
        if (action.type === 'section') return { ...section, name: action.name }
        return {
          ...section,
          items: section.items.map((item) => {
            if (item.id !== action.itemId) return item
            if (action.type === 'item') return { ...item, name: action.name }
            return {
              ...item,
              comments: item.comments.map((comment) =>
                comment.id === action.commentId ? { ...comment, ...action.change } : comment,
              ),
            }
          }),
        }
      }),
    },
  }
}

export function upsertSummary(templates: TemplateSummary[], detail: TemplateDetail): TemplateSummary[] {
  const { sections: _sections, importReport: _report, ...summary } = detail
  if (templates.some((template) => template.id === detail.id)) {
    return templates.map((template) => (template.id === detail.id ? summary : template))
  }
  return [...templates, summary]
}

export interface ItemMatch {
  item: TemplateItem
  comments: TemplateComment[]
  nameMatches: boolean
}

export interface SectionMatch {
  section: TemplateSection
  ordinal: number
  items: ItemMatch[]
  matchingItems: number
  matchingComments: number
}

export interface TemplateSearch {
  sections: SectionMatch[]
  itemCount: number
  commentCount: number
  isSearching: boolean
}

export function searchTemplate(template: TemplateDetail, query: string): TemplateSearch {
  const needle = query.trim().toLocaleLowerCase()
  const sections: SectionMatch[] = []
  let itemCount = 0
  let commentCount = 0
  for (const [sectionIndex, section] of template.sections.entries()) {
    const sectionMatches = !!needle && section.name.toLocaleLowerCase().includes(needle)
    const items: ItemMatch[] = []
    let matchingItems = 0
    let matchingComments = 0
    for (const item of section.items) {
      const nameMatches = !!needle && item.name.toLocaleLowerCase().includes(needle)
      const directCommentMatches = needle
        ? item.comments.filter((comment) =>
            `${comment.name}\n${sourcePlainText(comment.contentHtml)}`.toLocaleLowerCase().includes(needle),
          )
        : item.comments
      const comments = !needle || sectionMatches || nameMatches ? item.comments : directCommentMatches
      if (!needle || sectionMatches || nameMatches || directCommentMatches.length) {
        items.push({ item, comments, nameMatches })
        matchingItems += 1
        matchingComments += comments.length
      }
    }
    if (!needle || sectionMatches || items.length) {
      sections.push({ section, ordinal: sectionIndex + 1, items, matchingItems, matchingComments })
      itemCount += matchingItems
      commentCount += matchingComments
    }
  }
  return { sections, itemCount, commentCount, isSearching: Boolean(needle) }
}

export function countSectionComments(section: TemplateSection): number {
  return section.items.reduce((count, item) => count + item.comments.length, 0)
}

export function commentTone(type: string): 'info' | 'limit' | 'defect' | 'neutral' {
  const value = type.trim().toLowerCase()
  if (['information', 'informational', 'info', 'i'].includes(value)) return 'info'
  if (['limitation', 'limitations', 'limit', 'l'].includes(value)) return 'limit'
  if (['defect', 'defects', 'deficiency', 'deficiencies', 'd'].includes(value)) return 'defect'
  return 'neutral'
}

export interface WarningGroup {
  key: string
  code: string
  severity: ImportWarning['severity']
  warnings: ImportWarning[]
}

export function groupWarnings(warnings: ImportWarning[]): WarningGroup[] {
  const groups = new Map<string, WarningGroup>()
  for (const warning of warnings) {
    const key = `${warning.severity}:${warning.code}`
    const group = groups.get(key)
    if (group) group.warnings.push(warning)
    else groups.set(key, { key, code: warning.code, severity: warning.severity, warnings: [warning] })
  }
  return [...groups.values()].sort((a, b) =>
    a.severity === b.severity ? 0 : a.severity === 'warning' ? -1 : 1,
  )
}

export const formatNumber = (value: number) => new Intl.NumberFormat().format(value)

export function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(date)
}
