/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * What the simplified library shows, worked out without rendering anything: one card per pack (all the
 * files of a pack, converted or not, are behind the same card), whether it is already on the device,
 * and the search, sort and "not on the device yet" filter, as in Lunii Pack's own library.
 */

import {matchingGroups} from "./librarySearch";

export const LIBRARY_MODE_FULL = 'full';
export const LIBRARY_MODE_SIMPLE = 'simple';
export const LOCAL_STORAGE_LIBRARY_MODE = 'libraryMode';

export const SIMPLE_SORTS = ['title', 'recent', 'size'];

/** The mode remembered in this browser, the full library when none is (or storage is unavailable). */
export function readLibraryMode(storage) {
    try {
        const store = storage === undefined ? window.localStorage : storage;
        const value = store && store.getItem(LOCAL_STORAGE_LIBRARY_MODE);
        return value === LIBRARY_MODE_SIMPLE ? LIBRARY_MODE_SIMPLE : LIBRARY_MODE_FULL;
    } catch (e) {
        return LIBRARY_MODE_FULL;
    }
}

/** Remembers the mode; a browser that refuses storage only loses the memory, not the switch. */
export function saveLibraryMode(mode, storage) {
    try {
        const store = storage === undefined ? window.localStorage : storage;
        if (store) {
            store.setItem(LOCAL_STORAGE_LIBRARY_MODE, mode === LIBRARY_MODE_SIMPLE ? LIBRARY_MODE_SIMPLE : LIBRARY_MODE_FULL);
        }
    } catch (e) {
        // Private browsing, blocked storage: the choice lasts until the page is reloaded
    }
}

/** A displayable title: the pack's own, or its uuid when it has none. */
export function packTitle(pack, uuid) {
    return pack && pack.title && pack.title !== 'MISSING_PACK_TITLE' ? pack.title : uuid;
}

/** One card of the simplified library, from a library group ({uuid, packs}). */
export function simpleItem(group, deviceUuids) {
    const packs = group.packs || [];
    const main = packs[0] || {};
    const sizes = packs.map(p => p.size).filter(size => typeof size === 'number' && size >= 0);
    return {
        uuid: group.uuid,
        group,
        title: packTitle(main, group.uuid),
        description: main.description || '',
        image: main.image || null,
        official: !!main.official,
        nightMode: !!main.nightModeAvailable,
        version: main.version,
        size: sizes.length > 0 ? sizes[0] : null,
        files: packs.length,
        timestamp: packs.reduce((latest, p) => Math.max(latest, p.timestamp || 0), 0),
        editable: packs.find(p => p.format === 'archive') || null,
        onDevice: deviceUuids.indexOf(group.uuid) !== -1
    };
}

const COMPARATORS = {
    title: (a, b) => a.title.localeCompare(b.title, 'fr', {numeric: true, sensitivity: 'base'}),
    recent: (a, b) => b.timestamp - a.timestamp,
    size: (a, b) => (b.size || 0) - (a.size || 0)
};

/**
 * The cards to show: the groups matching the search, optionally only those not on the device yet,
 * sorted. Never changes the arrays it is given.
 */
export function simpleItems(groups, devicePacks, {query = '', sort = 'title', notOnDevice = false} = {}) {
    const deviceUuids = (devicePacks || []).map(p => p.uuid);
    const items = matchingGroups(groups || [], query)
        .map(group => simpleItem(group, deviceUuids))
        .filter(item => !notOnDevice || !item.onDevice);
    return items.sort(COMPARATORS[sort] || COMPARATORS.title);
}

/** The uuids of the device after moving the pack at `index` by `delta` (-1 up, +1 down), or null. */
export function movedOrder(devicePacks, index, delta) {
    const uuids = (devicePacks || []).map(p => p.uuid);
    const target = index + delta;
    if (index < 0 || index >= uuids.length || target < 0 || target >= uuids.length) {
        return null;
    }
    [uuids[index], uuids[target]] = [uuids[target], uuids[index]];
    return uuids;
}

/** Share of the device storage in use, from 0 to 100, or null without storage figures. */
export function storagePercent(metadata) {
    const storage = metadata && metadata.storage;
    if (!storage || !(storage.size > 0)) {
        return null;
    }
    return Math.min(100, Math.max(0, Math.round(100 * storage.taken / storage.size)));
}
