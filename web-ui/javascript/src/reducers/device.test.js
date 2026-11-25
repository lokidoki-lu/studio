/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import device from './device';
import {devicePlugged, deviceUnplugged, setDevicePacks, devicePacksFailed} from '../actions';

describe('device story list loading', () => {
    const plugged = device(undefined, devicePlugged({uuid: 'x'}, true));

    it('a Lunii just plugged is loading, not empty', () => {
        expect(plugged.packsLoading).toBe(true);
        expect(plugged.packs).toEqual([]);
    });

    it('stops loading once the list is read, even when it is empty', () => {
        expect(device(plugged, setDevicePacks([])).packsLoading).toBe(false);
        expect(device(plugged, setDevicePacks([{uuid: 'a'}])).packs).toHaveLength(1);
    });

    it('stops loading when the list could not be read or the Lunii goes away', () => {
        expect(device(plugged, devicePacksFailed()).packsLoading).toBe(false);
        expect(device(plugged, deviceUnplugged()).packsLoading).toBe(false);
    });

    it('a refresh that already holds the list does not show the loader', () => {
        expect(device(undefined, devicePlugged({uuid: 'x'})).packsLoading).toBe(false);
    });
});
