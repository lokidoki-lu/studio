/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {applyTheme, loadTheme, nextTheme, saveTheme} from "./theme";

function memoryStorage() {
    const data = {};
    return {
        getItem: k => (k in data ? data[k] : null),
        setItem: (k, v) => { data[k] = String(v); },
        removeItem: k => { delete data[k]; }
    };
}

it('cycles auto, light, dark, then back to auto', () => {
    expect(nextTheme('auto')).toBe('light');
    expect(nextTheme('light')).toBe('dark');
    expect(nextTheme('dark')).toBe('auto');
    expect(nextTheme('garbage')).toBe('auto');
});

it('remembers a forced theme and forgets auto', () => {
    const storage = memoryStorage();
    expect(loadTheme(storage)).toBe('auto');
    saveTheme('dark', storage);
    expect(loadTheme(storage)).toBe('dark');
    saveTheme('auto', storage);
    expect(loadTheme(storage)).toBe('auto');
});

it('falls back to auto on an unknown value or an unreadable storage', () => {
    const storage = memoryStorage();
    storage.setItem('theme', 'purple');
    expect(loadTheme(storage)).toBe('auto');
    expect(loadTheme({getItem: () => { throw new Error('blocked'); }})).toBe('auto');
    expect(() => saveTheme('dark', {setItem: () => { throw new Error('blocked'); }})).not.toThrow();
});

it('sets data-theme only when the theme is forced', () => {
    const root = document.createElement('html');
    applyTheme('dark', root);
    expect(root.getAttribute('data-theme')).toBe('dark');
    applyTheme('auto', root);
    expect(root.hasAttribute('data-theme')).toBe(false);
});
