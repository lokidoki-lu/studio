/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {
    readLibraryMode, saveLibraryMode, simpleItems, simpleItem, movedOrder, storagePercent, packTitle,
    LIBRARY_MODE_FULL, LIBRARY_MODE_SIMPLE, LOCAL_STORAGE_LIBRARY_MODE
} from './simpleLibrary';

function memoryStorage(initial = {}) {
    const values = {...initial};
    return {
        getItem: key => (key in values ? values[key] : null),
        setItem: (key, value) => { values[key] = String(value); },
        values
    };
}

const throwing = {
    getItem: () => { throw new Error('SecurityError'); },
    setItem: () => { throw new Error('QuotaExceededError'); }
};

const GROUPS = [
    {uuid: 'u-b', packs: [
        {path: 'b.zip', format: 'archive', title: 'Bambi', description: 'Un faon', size: 3e6, timestamp: 100},
        {path: 'b.pack', format: 'raw', title: 'Bambi', size: 2e6, timestamp: 300}
    ]},
    {uuid: 'u-a', packs: [{path: 'a.pack', format: 'raw', title: 'Aladdin', size: 9e6, timestamp: 200}]},
    {uuid: 'u-c', packs: [{path: 'c.zip', format: 'archive', title: 'MISSING_PACK_TITLE', size: 1e6, timestamp: 50}]},
    {uuid: 'u-10', packs: [{path: 'd.zip', format: 'archive', title: 'Épisode 10', timestamp: 10}]},
    {uuid: 'u-2', packs: [{path: 'e.zip', format: 'archive', title: 'Épisode 2', timestamp: 20}]}
];

describe('library mode memory', () => {
    it('defaults to the full library', () => {
        expect(readLibraryMode(memoryStorage())).toBe(LIBRARY_MODE_FULL);
    });

    it('reads back the simplified library once chosen', () => {
        const storage = memoryStorage();
        saveLibraryMode(LIBRARY_MODE_SIMPLE, storage);
        expect(storage.values[LOCAL_STORAGE_LIBRARY_MODE]).toBe('simple');
        expect(readLibraryMode(storage)).toBe(LIBRARY_MODE_SIMPLE);
        saveLibraryMode(LIBRARY_MODE_FULL, storage);
        expect(readLibraryMode(storage)).toBe(LIBRARY_MODE_FULL);
    });

    it('ignores an unknown stored value', () => {
        expect(readLibraryMode(memoryStorage({[LOCAL_STORAGE_LIBRARY_MODE]: 'weird'}))).toBe(LIBRARY_MODE_FULL);
    });

    it('survives a storage that throws or is missing', () => {
        expect(readLibraryMode(throwing)).toBe(LIBRARY_MODE_FULL);
        expect(() => saveLibraryMode(LIBRARY_MODE_SIMPLE, throwing)).not.toThrow();
        expect(readLibraryMode(null)).toBe(LIBRARY_MODE_FULL);
        expect(() => saveLibraryMode(LIBRARY_MODE_SIMPLE, null)).not.toThrow();
    });
});

describe('simpleItems', () => {
    it('makes one card per pack, whatever its number of files', () => {
        const items = simpleItems(GROUPS, []);
        expect(items).toHaveLength(5);
        const bambi = items.find(i => i.uuid === 'u-b');
        expect(bambi).toMatchObject({title: 'Bambi', description: 'Un faon', size: 3e6, files: 2, timestamp: 300});
        expect(bambi.editable.path).toBe('b.zip');
        expect(items.find(i => i.uuid === 'u-a').editable).toBeNull();
    });

    it('falls back to the uuid for a pack without a title', () => {
        expect(simpleItems(GROUPS, []).find(i => i.uuid === 'u-c').title).toBe('u-c');
        expect(packTitle(null, 'x')).toBe('x');
    });

    it('sorts titles naturally, ignoring case and accents', () => {
        expect(simpleItems(GROUPS, []).map(i => i.title)).toEqual(['Aladdin', 'Bambi', 'Épisode 2', 'Épisode 10', 'u-c']);
    });

    it('sorts by date and by size', () => {
        expect(simpleItems(GROUPS, [], {sort: 'recent'}).map(i => i.uuid)).toEqual(['u-b', 'u-a', 'u-c', 'u-2', 'u-10']);
        expect(simpleItems(GROUPS, [], {sort: 'size'}).map(i => i.uuid).slice(0, 3)).toEqual(['u-a', 'u-b', 'u-c']);
    });

    it('marks the packs on the device and can hide them', () => {
        const device = [{uuid: 'u-a'}];
        expect(simpleItems(GROUPS, device).find(i => i.uuid === 'u-a').onDevice).toBe(true);
        expect(simpleItems(GROUPS, device, {notOnDevice: true}).map(i => i.uuid)).not.toContain('u-a');
    });

    it('searches like the full library', () => {
        expect(simpleItems(GROUPS, [], {query: 'bambi'}).map(i => i.uuid)).toEqual(['u-b']);
        expect(simpleItems(GROUPS, [], {query: 'episode'}).map(i => i.uuid)).toEqual(['u-2', 'u-10']);
    });

    it('does not change the arrays it is given', () => {
        const groups = [...GROUPS];
        simpleItems(groups, [], {sort: 'size'});
        expect(groups).toEqual(GROUPS);
    });

    it('copes with missing data', () => {
        expect(simpleItems(undefined, undefined)).toEqual([]);
        expect(simpleItem({uuid: 'x'}, []).size).toBeNull();
    });
});

describe('movedOrder', () => {
    const packs = [{uuid: 'a'}, {uuid: 'b'}, {uuid: 'c'}];

    it('swaps a pack with its neighbour', () => {
        expect(movedOrder(packs, 1, -1)).toEqual(['b', 'a', 'c']);
        expect(movedOrder(packs, 1, 1)).toEqual(['a', 'c', 'b']);
        expect(packs.map(p => p.uuid)).toEqual(['a', 'b', 'c']);
    });

    it('refuses to move past the ends', () => {
        expect(movedOrder(packs, 0, -1)).toBeNull();
        expect(movedOrder(packs, 2, 1)).toBeNull();
    });
});

describe('storagePercent', () => {
    it('rounds the used share', () => {
        expect(storagePercent({storage: {size: 1000, taken: 333}})).toBe(33);
    });

    it('is null without figures', () => {
        expect(storagePercent(null)).toBeNull();
        expect(storagePercent({storage: {size: 0, taken: 0}})).toBeNull();
    });
});
