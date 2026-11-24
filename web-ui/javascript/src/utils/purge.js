/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Helpers for the "remove old packs" dialog. Which entries are old is decided by the backend
 * (LibraryService.purgeCandidates), and checked again by it when the purge is asked for: nothing here
 * decides what may be deleted, only what the dialog shows and sends.
 */

/* The selection the dialog opens with: what the backend marked as selected, and nothing else. */
export function initialSelection(candidates) {
    const selection = {};
    (candidates || []).forEach(c => {
        selection[c.path] = c.selected === true;
    });
    return selection;
}

/* The paths to send, in the order the dialog lists them. */
export function selectedPaths(candidates, selection) {
    return (candidates || []).filter(c => selection && selection[c.path] === true).map(c => c.path);
}

/* Bytes freed by removing the selected candidates. Unknown sizes (negative) count as nothing. */
export function selectedSize(candidates, selection) {
    return (candidates || [])
        .filter(c => selection && selection[c.path] === true)
        .reduce((total, c) => total + (c.size > 0 ? c.size : 0), 0);
}

/* A size as the translation key and value to show it with (KB, MB or GB, decimal units). */
export function sizeUnit(bytes) {
    const size = bytes > 0 ? bytes : 0;
    if (size >= 1e9) {
        return { key: 'gb', value: (size / 1e9).toFixed(1) };
    }
    if (size >= 1e6) {
        return { key: 'mb', value: Math.round(size / 1e6).toString() };
    }
    return { key: 'kb', value: Math.ceil(size / 1e3).toString() };
}

/* What the backend did with a purge request, summed up for the toast. */
export function purgeSummary(response) {
    const results = (response && Array.isArray(response.results)) ? response.results : [];
    const removed = results.filter(r => r.removed === true);
    return {
        removed: removed.length,
        failed: results.length - removed.length,
        freed: removed.reduce((total, r) => total + (r.size > 0 ? r.size : 0), 0)
    };
}
