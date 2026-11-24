/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {renderUntrustedMarkdown, isSafeUrl} from './markdown';

describe('renderUntrustedMarkdown', () => {
    it('still renders ordinary Markdown', () => {
        const html = renderUntrustedMarkdown('# Title\n\nSome **bold** and a [link](https://example.org).');
        expect(html).toContain('<strong>bold</strong>');
        expect(html).toContain('<a href="https://example.org">link</a>');
    });

    it('escapes raw HTML, block and inline', () => {
        const html = renderUntrustedMarkdown('<script>alert(1)</script>\n\ntext <img src=x onerror=alert(1)> more');
        expect(html).not.toMatch(/<script/i);
        expect(html).not.toMatch(/<img/i);
        expect(html).toContain('&lt;script&gt;');
    });

    it('drops javascript:, data: and obfuscated schemes from links and images', () => {
        [
            '[x](javascript:alert(1))',
            '[x](JaVaScRiPt:alert(1))',
            '[x](javascript&#58;alert(1))',
            '<javascript:alert(1)>',
            '![x](data:image/svg+xml;base64,AAAA)',
            '[x](vbscript:msgbox(1))'
        ].forEach(md => {
            const html = renderUntrustedMarkdown(md);
            expect(html).not.toMatch(/href=|src=/);
        });
    });

    it('returns nothing for a missing announce', () => {
        expect(renderUntrustedMarkdown(undefined)).toBe('');
    });
});

describe('isSafeUrl', () => {
    it('accepts http(s), and mailto only when asked', () => {
        expect(isSafeUrl('https://example.org')).toBe(true);
        expect(isSafeUrl('http://example.org')).toBe(true);
        expect(isSafeUrl('mailto:a@b.c')).toBe(false);
        expect(isSafeUrl('mailto:a@b.c', true)).toBe(true);
        expect(isSafeUrl('java\tscript:alert(1)')).toBe(false);
        expect(isSafeUrl('/relative')).toBe(false);
    });
});
