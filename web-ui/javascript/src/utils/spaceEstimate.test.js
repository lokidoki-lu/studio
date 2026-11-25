/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {freeBytes, estimatePaths, selectionPaths, selectionRequirement, checkFits} from './spaceEstimate';

const ARCHIVE_ONLY = {uuid: 'a', packs: [{uuid: 'a', path: 'a.zip', format: 'archive'}]};
const CONVERTED = {uuid: 'b', packs: [{uuid: 'b', path: 'b.converted_1', format: 'fs'}]};
const BOTH = {uuid: 'c', packs: [{uuid: 'c', path: 'c.converted_2', format: 'fs'}, {uuid: 'c', path: 'c.zip', format: 'archive'}]};

describe('freeBytes', () => {
    it('reads the free space, or size minus taken, or nothing', () => {
        expect(freeBytes({storage: {free: 10, size: 100, taken: 50}})).toBe(10);
        expect(freeBytes({storage: {size: 100, taken: 30}})).toBe(70);
        expect(freeBytes({storage: {size: 10, taken: 30}})).toBe(0);
        expect(freeBytes({})).toBeNull();
        expect(freeBytes(null)).toBeNull();
    });
});

describe('estimatePaths', () => {
    it('asks about the file that would be sent or converted, and both when the user may be asked', () => {
        expect(estimatePaths(ARCHIVE_ONLY, 'fs')).toEqual(['a.zip']);
        expect(estimatePaths(CONVERTED, 'fs')).toEqual(['b.converted_1']);
        expect(estimatePaths(BOTH, 'fs')).toEqual(['c.converted_2', 'c.zip']);
        expect(estimatePaths({uuid: 'x', packs: []}, 'fs')).toEqual([]);
    });

    it('lists each path once for a selection', () => {
        expect(selectionPaths([ARCHIVE_ONLY, ARCHIVE_ONLY, BOTH], 'raw')).toEqual(['a.zip', 'c.converted_2']);
    });
});

describe('selectionRequirement', () => {
    it('adds up the packs, counting the larger file when either may be sent, and lists the unknown ones', () => {
        const estimate = {method: 'estimated', packs: [
            {path: 'a.zip', bytes: 1000}, {path: 'c.converted_2', bytes: 300}, {path: 'c.zip', bytes: 500}
        ]};
        const requirement = selectionRequirement([ARCHIVE_ONLY, BOTH, CONVERTED], 'fs', estimate);
        expect(requirement).toEqual({required: 1500, perGroup: {a: 1000, c: 500}, unknown: ['b'], method: 'estimated'});
    });
});

describe('checkFits', () => {
    it('says how much is missing, and lets through what cannot be told', () => {
        expect(checkFits(100, 200)).toEqual({fits: true, missing: 0});
        expect(checkFits(300, 200)).toEqual({fits: false, missing: 100});
        expect(checkFits(300, null)).toEqual({fits: true, missing: 0});
    });
});
