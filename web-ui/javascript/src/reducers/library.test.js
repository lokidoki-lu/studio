/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import library from './library';
import {setLibrary, libraryLoadFailed} from '../actions';

describe('library loading', () => {
    it('is loading until read, not empty', () => {
        expect(library(undefined, {type: '@@INIT'}).loading).toBe(true);
    });

    it('stops loading once read, even when empty, or when reading failed', () => {
        const initial = library(undefined, {type: '@@INIT'});
        expect(library(initial, setLibrary({}, [])).loading).toBe(false);
        expect(library(initial, libraryLoadFailed()).loading).toBe(false);
    });
});
