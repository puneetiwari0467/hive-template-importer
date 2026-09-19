import { describe, expect, it } from 'vitest'
import { fixtureTemplate } from '../test/fixtures'
import { commentPreview, draftPreview, sanitizePreview, sourcePlainText } from './preview'

describe('display-only sanitization', () => {
  it('removes executable content, external resources and all link destinations', () => {
    const html = sanitizePreview('<p onclick="alert(1)" style="background:url(https://example.invalid)">Safe<strong> text</strong><img src="https://example.invalid/a.jpg"><iframe src="https://example.invalid"></iframe><video poster="https://example.invalid/a.jpg"></video><script>alert(1)</script><a href="javascript:alert(1)" target="_blank">link</a></p>')
    expect(html).toContain('<strong> text</strong>')
    expect(html).toContain('<a>link</a>')
    expect(html).not.toMatch(/onclick|style|img|iframe|video|script|href|target|example\.invalid/)
  })

  it('escapes plain text while preserving readable newlines', () => {
    expect(draftPreview('Pressure < 80 & > 10\r\nNext line')).toBe('Pressure &lt; 80 &amp; &gt; 10<br>Next line')
  })

  it('uses the sanitized backend projection until the user edits the source', () => {
    const comment = fixtureTemplate().sections[0].items[0].comments[0]
    const saved = { ...comment, previewHtml: '<p>Server projection</p><img src="https://example.invalid">' }
    expect(commentPreview(comment, saved)).toBe('<p>Server projection</p>')
    expect(commentPreview({ ...comment, contentHtml: 'Changed source' }, saved)).toBe('Changed source')
    expect(comment.originalHtml).toBe('  Brick and siding.\r\nVisual inspection only.  ')
  })

  it('separates block and line-break text for useful phrase searches', () => {
    expect(sourcePlainText('<p>Roof</p><p>covering</p>').replace(/\s+/g, ' ').trim()).toBe('Roof covering')
    expect(sourcePlainText('First<br>second')).toBe('First second')
  })
})
