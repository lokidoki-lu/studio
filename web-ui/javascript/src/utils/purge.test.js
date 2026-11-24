/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {initialSelection, selectedPaths, selectedSize, sizeUnit, purgeSummary} from './purge';

const daltons = [
    {path: 'd86a08cd-3fff-5141-94a4-1b6f53a2c1a5.converted_1795359228662', size: 529000000, reason: 'SUPERSEDED', selected: true},
    {path: 'copie.zip', size: 970000000, reason: 'DUPLICATE_SOURCE', selected: false},
    {path: 'odd', size: -1, reason: 'SUPERSEDED', selected: true}
];

describe('purge dialog', () => {

    it('opens with exactly what the backend selected', () => {
        expect(initialSelection(daltons)).toEqual({
            'd86a08cd-3fff-5141-94a4-1b6f53a2c1a5.converted_1795359228662': true,
            'copie.zip': false,
            'odd': true
        });
    });

    it('treats anything but an explicit true as unselected', () => {
        expect(initialSelection([{path: 'a', selected: 'true'}, {path: 'b'}])).toEqual({a: false, b: false});
        expect(initialSelection(undefined)).toEqual({});
    });

    it('sends only the checked paths, and sums only known sizes', () => {
        const selection = initialSelection(daltons);
        expect(selectedPaths(daltons, selection)).toEqual(['d86a08cd-3fff-5141-94a4-1b6f53a2c1a5.converted_1795359228662', 'odd']);
        expect(selectedSize(daltons, selection)).toBe(529000000);
        selection['copie.zip'] = true;
        expect(selectedSize(daltons, selection)).toBe(1499000000);
        expect(selectedPaths(daltons, {})).toEqual([]);
    });

    it('formats sizes', () => {
        expect(sizeUnit(529000000)).toEqual({key: 'mb', value: '529'});
        expect(sizeUnit(1499000000)).toEqual({key: 'gb', value: '1.5'});
        expect(sizeUnit(1500)).toEqual({key: 'kb', value: '2'});
        expect(sizeUnit(-1)).toEqual({key: 'kb', value: '0'});
    });

    it('sums up the purge result', () => {
        expect(purgeSummary({results: [
            {path: 'a', removed: true, size: 10},
            {path: 'b', removed: false, size: 0, error: 'NOT_A_CANDIDATE'}
        ]})).toEqual({removed: 1, failed: 1, freed: 10});
        expect(purgeSummary(undefined)).toEqual({removed: 0, failed: 0, freed: 0});
    });
});
