import DOMPurify from 'dompurify'
import type { TemplateComment } from '../contracts'

const htmlTag = /<\/?[a-z][^>]*>/i

export function sanitizePreview(html: string): string {
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      'p', 'br', 'div', 'span', 'strong', 'b', 'em', 'i', 'u', 's',
      'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'hr', 'sup', 'sub',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'table', 'thead', 'tbody',
      'tfoot', 'tr', 'th', 'td', 'a',
    ],
    ALLOWED_ATTR: ['colspan', 'rowspan'],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
  })
}

export function draftPreview(source: string): string {
  if (htmlTag.test(source)) return sanitizePreview(source)
  return sanitizePreview(
    source
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;')
      .replace(/\r\n|\r|\n/g, '<br>'),
  )
}

export function commentPreview(comment: TemplateComment, savedComment?: TemplateComment): string {
  return savedComment && savedComment.contentHtml === comment.contentHtml
    ? sanitizePreview(savedComment.previewHtml)
    : draftPreview(comment.contentHtml)
}

export function sourcePlainText(source: string): string {
  if (!htmlTag.test(source)) return source
  const node = document.createElement('div')
  node.innerHTML = sanitizePreview(source).replace(/<(?:br\b[^>]*|\/(?:p|div|li|h[1-6]|tr|td))>/gi, ' ')
  return node.textContent ?? ''
}
