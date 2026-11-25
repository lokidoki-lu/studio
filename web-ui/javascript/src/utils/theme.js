/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {LOCAL_STORAGE_THEME} from "./storage";

/* 'auto' follows the system setting; 'light' and 'dark' force it. Clicking cycles in this order. */
export const THEMES = ['auto', 'light', 'dark'];

export function nextTheme(theme) {
    const i = THEMES.indexOf(theme);
    return THEMES[(i + 1) % THEMES.length];
}

/*
 * The saved choice, or 'auto' when there is none or the storage cannot be read. Reading
 * window.localStorage itself throws when the browser blocks storage: it is done inside the try.
 */
export function loadTheme(storage) {
    try {
        const store = storage === undefined ? window.localStorage : storage;
        const saved = store.getItem(LOCAL_STORAGE_THEME);
        return THEMES.includes(saved) ? saved : 'auto';
    } catch (e) {
        return 'auto';
    }
}

export function saveTheme(theme, storage) {
    try {
        const store = storage === undefined ? window.localStorage : storage;
        if (theme === 'auto') {
            store.removeItem(LOCAL_STORAGE_THEME);
        } else {
            store.setItem(LOCAL_STORAGE_THEME, theme);
        }
    } catch (e) {
        // Not remembered, still applied for this session.
    }
}

/* index.css reads data-theme on <html>: absent means auto. */
export function applyTheme(theme, root = document.documentElement) {
    if (theme === 'light' || theme === 'dark') {
        root.setAttribute('data-theme', theme);
    } else {
        root.removeAttribute('data-theme');
    }
}
