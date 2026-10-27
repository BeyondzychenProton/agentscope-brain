import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

export type RenderedMarkdown = { kind: 'html' | 'plain'; value: string }
export type PurifyFn = (dirty: string) => string

const ALLOWED_TAGS = [
  'p', 'br', 'strong', 'b', 'em', 'i', 's', 'del', 'ul', 'ol', 'li', 'blockquote',
  'pre', 'code', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'table', 'thead', 'tbody', 'tr', 'th', 'td', 'hr', 'a',
]

const ALLOWED_ATTR = ['href', 'title', 'class', 'colspan', 'rowspan', 'align']

/** Only http(s) after markdown-it / browser URL normalization. */
const HTTP_URI = /^(?:https?):/i

function createParser() {
  const md = new MarkdownIt({ html: false, linkify: false, typographer: false })
  md.disable(['image'])
  md.validateLink = (url) => {
    try {
      if (/^\/\//.test(url)) return false
      const href = url.trim()
      if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(href)) return false
      return HTTP_URI.test(href)
    } catch {
      return false
    }
  }
  return md
}

const parser = createParser()

/** Browser default; injectable in tests so Node need not host a DOM. */
export function browserPurify(dirty: string): string {
  return DOMPurify.sanitize(dirty, {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    ALLOWED_URI_REGEXP: HTTP_URI,
    ALLOW_DATA_ATTR: false,
  })
}

/**
 * Render markdown-v1 for display only. Verified source text remains authoritative.
 * On parse/purify failure returns plain source (never throws to the caller).
 */
export function renderMarkdownV1(source: string, purify: PurifyFn = browserPurify): RenderedMarkdown {
  if (typeof source !== 'string') return { kind: 'plain', value: '' }
  try {
    const dirty = parser.render(source)
    const clean = purify(dirty)
    if (typeof clean !== 'string') return { kind: 'plain', value: source }
    return { kind: 'html', value: clean }
  } catch {
    return { kind: 'plain', value: source }
  }
}
