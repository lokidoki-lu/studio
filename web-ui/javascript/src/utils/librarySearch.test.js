/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {matchingGroups} from './librarySearch';

const groups = [
    {uuid: 'aaaa-1111', packs: [{title: "L'élève et la forêt", path: 'eleve.zip'}]},
    {uuid: 'bbbb-2222', packs: [{title: 'Histoires du soir', path: 'bbbb-2222.converted_123'}, {title: 'Histoires du soir', path: 'soir-v1.zip'}]},
    {uuid: 'cccc-3333', packs: [{title: null, path: 'mystere.pack'}]}
];

it('returns every group for an empty or blank query', () => {
    expect(matchingGroups(groups, '')).toBe(groups);
    expect(matchingGroups(groups, '   ')).toBe(groups);
    expect(matchingGroups(groups, undefined)).toBe(groups);
});

it('ignores case and accents', () => {
    expect(matchingGroups(groups, 'ELEVE FORET').map(g => g.uuid)).toEqual(['aaaa-1111']);
});

it('matches file names and uuids, of any version', () => {
    expect(matchingGroups(groups, 'soir-v1').map(g => g.uuid)).toEqual(['bbbb-2222']);
    expect(matchingGroups(groups, 'cccc').map(g => g.uuid)).toEqual(['cccc-3333']);
});

it('requires every word', () => {
    expect(matchingGroups(groups, 'histoires forêt')).toEqual([]);
});
