/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import editor from './editor';

describe('editor reducer', () => {
    it('installs a diagram and its file name', () => {
        const diagram = {title: 'A'};
        const state = editor(undefined, {type: 'SET_EDITOR_DIAGRAM', diagram, filename: 'a.zip'});
        expect(state.diagram).toBe(diagram);
        expect(state.filename).toBe('a.zip');
    });

    it('ignores a missing diagram: the pack shown keeps its model and its name', () => {
        const diagram = {title: 'A'};
        const before = editor(undefined, {type: 'SET_EDITOR_DIAGRAM', diagram, filename: 'a.zip'});
        const after = editor(before, {type: 'SET_EDITOR_DIAGRAM', diagram: undefined, filename: 'broken.zip'});
        expect(after).toBe(before);
    });
});
