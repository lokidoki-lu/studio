/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/** Lower case, without accents, so that "eleve" finds "Élève". */
function fold(text) {
    return String(text || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/**
 * The library groups matching a search query: every word of the query must appear in the pack's
 * title, its uuid or one of its file names, ignoring case and accents. An empty query matches all.
 */
export function matchingGroups(groups, query) {
    const words = fold(query).split(/\s+/).filter(w => w);
    if (words.length === 0) {
        return groups;
    }
    return groups.filter(group => {
        const haystack = fold([group.uuid, ...(group.packs || []).map(p => `${p.title || ''} ${p.path || ''}`)].join(' '));
        return words.every(w => haystack.includes(w));
    });
}
