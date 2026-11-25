/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Whether packs fit on the plugged device. The size each pack takes once in the device's format is
 * the backend's (POST /api/library/estimate, which reads headers and rounds to the cluster): nothing
 * is guessed here, this only picks which file of each pack is asked about and adds things up.
 */

import {chooseDropAction, needsProvenanceProof} from "./packs";

/** Free bytes of the device, from its metadata; null when unknown. */
export function freeBytes(metadata) {
    const storage = metadata && metadata.storage;
    if (!storage) {
        return null;
    }
    if (typeof storage.free === 'number' && storage.free >= 0) {
        return storage.free;
    }
    if (typeof storage.size === 'number' && typeof storage.taken === 'number') {
        return Math.max(storage.size - storage.taken, 0);
    }
    return null;
}

/**
 * The library files of a group whose size on the device is to be estimated: the file that would be
 * sent as it is, the one that would be converted, or both when the user may be asked which
 * (sendToDevice): the larger of the two is then counted.
 */
export function estimatePaths(group, driverFormat) {
    const {action, source, cached} = chooseDropAction(group && group.packs, driverFormat);
    if (action === 'transfer') {
        // A converted folder without its source archive is not sent (sendToDevice): nothing to count
        return needsProvenanceProof(cached) ? [] : [cached.path];
    }
    if (action === 'convert') {
        return source ? [source.path] : [];
    }
    if (action === 'confirm') {
        return [cached.path, source.path];
    }
    return [];
}

/** Every path to ask the backend about for these groups, each once. */
export function selectionPaths(groups, driverFormat) {
    const paths = [];
    (groups || []).forEach(group => estimatePaths(group, driverFormat).forEach(path => {
        if (paths.indexOf(path) === -1) {
            paths.push(path);
        }
    }));
    return paths;
}

/**
 * What the groups need, from the backend's answer: {required, perGroup: {uuid: bytes}, unknown:
 * [uuid], method}. A group none of whose files could be estimated is listed in unknown and not
 * counted (the transfer's own check still guards it).
 */
export function selectionRequirement(groups, driverFormat, estimate) {
    const bytes = {};
    ((estimate && estimate.packs) || []).forEach(p => {
        bytes[p.path] = p.bytes;
    });
    const perGroup = {};
    const unknown = [];
    let required = 0;
    (groups || []).forEach(group => {
        const paths = estimatePaths(group, driverFormat);
        if (paths.length === 0) {
            // Nothing of this group would go to the device: it takes no space, and is no unknown
            return;
        }
        const known = paths.map(path => bytes[path]).filter(b => typeof b === 'number');
        if (known.length === 0) {
            unknown.push(group.uuid);
            return;
        }
        perGroup[group.uuid] = Math.max(...known);
        required += perGroup[group.uuid];
    });
    return {required, perGroup, unknown, method: (estimate && estimate.method) || 'measured'};
}

/** {fits, missing}: missing is how many bytes would have to be freed (0 when it fits or is unknown). */
export function checkFits(required, free) {
    if (typeof free !== 'number' || typeof required !== 'number') {
        return {fits: true, missing: 0};
    }
    return {fits: required <= free, missing: Math.max(required - free, 0)};
}
