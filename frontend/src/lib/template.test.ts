import { describe, expect, it } from 'vitest'
import { fixtureTemplate } from '../test/fixtures'
import type { DraftState } from './template'
import { commentTone, draftReducer, groupWarnings, isDraftDirty, searchTemplate, toTemplateUpdate, upsertSummary } from './template'

describe('complete template serialization and immutable drafts', () => {
  it('sends the exact update contract, keeping every ID and parent even when only one field changes', () => {
    const template = fixtureTemplate()
    const update = toTemplateUpdate(template)
    expect(update).toEqual({
      version: 3,
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
    })
    expect(update.sections[0].items[0].comments[1].contentHtml).toBe('  <p data-source="untouched">Behind <strong>walls</strong>.</p>\r\n')
    expect(JSON.stringify(update)).not.toContain('originalHtml')
    expect(JSON.stringify(update)).not.toContain('metadata')
    expect(JSON.stringify(update)).not.toContain('sourceRow')
    expect(JSON.stringify(update)).not.toContain('previewHtml')
    expect(JSON.stringify(update)).not.toContain('position')
  })

  it('retains drafts from multiple sections without mutating source, metadata or saved baseline', () => {
    const template = fixtureTemplate()
    let state: DraftState = draftReducer({ saved: null, draft: null }, { type: 'loaded', template })
    state = draftReducer(state, { type: 'comment', sectionId: 'section-exterior', itemId: 'item-walls', commentId: 'comment-wall-info', change: { contentHtml: 'Edited exterior text', name: 'Changed comment' } })
    state = draftReducer(state, { type: 'section', sectionId: 'section-roof', name: 'New roof name' })
    state = draftReducer(state, { type: 'item', sectionId: 'section-roof', itemId: 'item-shingles', name: 'New covering name' })
    expect(state.draft?.sections[0].items[0].comments[0].contentHtml).toBe('Edited exterior text')
    expect(state.draft?.sections[1].name).toBe('New roof name')
    expect(state.draft?.sections[1].items[0].name).toBe('New covering name')
    expect(state.saved).toBe(template)
    expect(template.sections[0].items[0].comments[0].contentHtml).toBe('  Brick and siding.\r\nVisual inspection only.  ')
    expect(state.draft?.sections[0].items[0].comments[0].metadata).toBe(template.sections[0].items[0].comments[0].metadata)
    expect(state.draft?.sections[0].items[0].comments[0].originalHtml).toBe(template.sections[0].items[0].comments[0].originalHtml)
    expect(isDraftDirty(state)).toBe(true)
  })

  it('clears dirty state when edits are reverted and when a saved response is accepted', () => {
    const template = fixtureTemplate()
    const initial = draftReducer({ saved: null, draft: null }, { type: 'loaded', template })
    expect(isDraftDirty(initial)).toBe(false)
    const renamed = draftReducer(initial, { type: 'name', name: 'Changed name' })
    expect(isDraftDirty(renamed)).toBe(true)
    expect(isDraftDirty(draftReducer(renamed, { type: 'name', name: template.name }))).toBe(false)
    expect(isDraftDirty(draftReducer(renamed, { type: 'loaded', template: { ...template, version: 4, name: 'Changed name' } }))).toBe(false)
  })

  it('upserts only the returned template and leaves the original summary untouched', () => {
    const original = fixtureTemplate()
    const copy = { ...fixtureTemplate(), id: 'copy-id', duplicateOf: original.id, name: 'My copy' }
    const list = upsertSummary([original], copy)
    expect(list).toHaveLength(2)
    expect(list[0]).toBe(original)
    expect(list[1]).not.toHaveProperty('sections')
    expect(list[1].duplicateOf).toBe(original.id)
  })
})

describe('search and migration facts', () => {
  it('searches comment content across sections, preserving the hierarchy and original ordinal', () => {
    const result = searchTemplate(fixtureTemplate(), 'Shingles')
    expect(result.sections).toHaveLength(1)
    expect(result.sections[0].section.id).toBe('section-roof')
    expect(result.sections[0].ordinal).toBe(2)
    expect(result.itemCount).toBe(1)
    expect(result.commentCount).toBe(1)
    expect(result.sections[0].items[0].comments[0].id).toBe('comment-roof-defect')
  })

  it('matches visible HTML text and includes child comments when an item name matches', () => {
    expect(searchTemplate(fixtureTemplate(), 'Behind walls').commentCount).toBe(1)
    const itemResult = searchTemplate(fixtureTemplate(), 'wall covering')
    expect(itemResult.itemCount).toBe(1)
    expect(itemResult.commentCount).toBe(2)
    expect(searchTemplate(fixtureTemplate(), 'not in the workbook').sections).toEqual([])
  })

  it('groups warnings without losing exact source values, locations or severities', () => {
    const warning = { code: 'FIELD_RETAINED', severity: 'warning' as const, message: 'Needs review', row: 7, value: '  untouched  ' }
    const groups = groupWarnings([...fixtureTemplate().importReport.warnings, warning, { ...warning, row: 8 }])
    expect(groups[0].severity).toBe('warning')
    expect(groups[0].warnings.map((entry) => entry.row)).toEqual([7, 8])
    expect(groups[0].warnings[0].value).toBe('  untouched  ')
    expect(groups[1].severity).toBe('info')
  })

  it('styles known comment categories without rewriting values or inventing a category', () => {
    expect(commentTone('Information')).toBe('info')
    expect(commentTone('LIMITATION')).toBe('limit')
    expect(commentTone('Defect')).toBe('defect')
    expect(commentTone('Unrecognized source category')).toBe('neutral')
  })
})
