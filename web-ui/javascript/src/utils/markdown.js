/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import marked from 'marked';

/*
 * Markdown fetched from a remote source (the evergreen announce) and injected with
 * dangerouslySetInnerHTML. Nothing in it may run script:
 *   - raw HTML, block or inline, is escaped and shown as text;
 *   - links and images are kept only for http(s) (and mailto for links); anything else —
 *     javascript:, vbscript:, data:, relative URLs — is reduced to its text.
 * No sanitizer library is bundled, and marked's own `sanitize` option is deprecated, so the
 * renderer is restricted here instead.
 */

const HTML_ESCAPES = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'};

export function escapeHtml(text) {
    return String(text).replace(/[&<>"']/g, c => HTML_ESCAPES[c]);
}

function decodeEntities(text) {
    // Enough to see through entity-encoded schemes such as "javascript&#58;".
    return String(text)
        .replace(/&#x([0-9a-f]+);?/gi, (m, hex) => String.fromCharCode(parseInt(hex, 16)))
        .replace(/&#(\d+);?/g, (m, dec) => String.fromCharCode(parseInt(dec, 10)))
        .replace(/&colon;?/gi, ':');
}

/** True only for an absolute http(s) URL, or mailto when allowMailto. */
export function isSafeUrl(href, allowMailto = false) {
    if (typeof href !== 'string') {
        return false;
    }
    let normalized;
    try {
        normalized = decodeURIComponent(decodeEntities(href));
    } catch (e) {
        return false;
    }
    // Browsers ignore control characters and whitespace inside a scheme ("java\tscript:").
    // eslint-disable-next-line no-control-regex
    normalized = normalized.replace(/[\u0000- \u007f-\u009f]/g, '').toLowerCase();
    return /^https?:\/\//.test(normalized) || (allowMailto && /^mailto:/.test(normalized));
}

const defaultRenderer = new marked.Renderer();
const safeRenderer = new marked.Renderer();
safeRenderer.html = html => escapeHtml(html);
safeRenderer.link = function (href, title, text) {
    if (!isSafeUrl(href, true)) {
        return text;
    }
    return defaultRenderer.link.call(this, href, title, text);
};
safeRenderer.image = function (href, title, text) {
    if (!isSafeUrl(href)) {
        return text;
    }
    return defaultRenderer.image.call(this, href, title, text);
};

/** Renders untrusted Markdown to HTML that is safe to inject. */
export function renderUntrustedMarkdown(markdown) {
    if (typeof markdown !== 'string') {
        return '';
    }
    return marked(markdown, {renderer: safeRenderer, headerIds: false});
}
