import type { TemplateComment, TemplateDetail } from '../contracts'

export function fixtureTemplate(): TemplateDetail {
  const makeComment = (id: string, name: string, sourceRow: number, text: string, type = 'Information'): TemplateComment => ({
    id,
    name,
    position: sourceRow,
    type,
    contentHtml: text,
    previewHtml: text,
    originalHtml: text,
    sourceRow,
    sourceSheet: 'Inspection template',
    metadata: { Name: name, Comment: text, Category: type, 'Uninterpreted field': '  original  ' },
  })

  return {
    id: '00000000-0000-4000-8000-000000000001',
    name: 'Home inspection sample',
    version: 3,
    sourceFileName: 'inspection-sample.xlsx',
    sourceSha256: 'a'.repeat(64),
    createdAt: '2026-01-02T09:00:00Z',
    updatedAt: '2026-01-03T09:00:00Z',
    duplicateOf: null,
    counts: { sections: 2, items: 2, comments: 3 },
    warningCount: 1,
    importReport: {
      sourceRows: 3,
      importedRows: 3,
      sourceColumns: ['Section', 'Item', 'Name', 'Comment', 'Category', 'Uninterpreted field'],
      warnings: [{
        code: 'PREVIEW_LIMITATION',
        severity: 'info',
        message: 'Source styling is retained but not displayed.',
        sheet: 'Inspection template',
        row: 3,
        column: 'Comment',
        value: '<p>Original styling</p>',
      }],
      notes: ['Original field values are available in the source audit.'],
    },
    sections: [{
      id: 'section-exterior',
      name: 'Exterior',
      position: 0,
      items: [{
        id: 'item-walls',
        name: 'Wall covering',
        position: 0,
        comments: [
          makeComment('comment-wall-info', 'Visible wall materials', 2, '  Brick and siding.\r\nVisual inspection only.  '),
          makeComment('comment-wall-limit', 'Concealed areas', 3, '  <p data-source="untouched">Behind <strong>walls</strong>.</p>\r\n', 'Limitation'),
        ],
      }],
    }, {
      id: 'section-roof',
      name: 'Roofing',
      position: 1,
      items: [{
        id: 'item-shingles',
        name: 'Roof covering',
        position: 0,
        comments: [makeComment('comment-roof-defect', 'Damaged shingles', 4, 'Several shingles need review.', 'Defect')],
      }],
    }],
  }
}

export function otherTemplate(): TemplateDetail {
  return {
    ...fixtureTemplate(),
    id: '00000000-0000-4000-8000-000000000002',
    name: 'Other inspection template',
    version: 1,
    duplicateOf: null,
  }
}
