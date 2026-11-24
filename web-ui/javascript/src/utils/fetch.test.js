/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {errorMessage, handleJsonOrError} from './fetch';

describe('errorMessage', () => {
    it('keeps only the readable message of a JSON refusal', () => {
        expect(errorMessage('{"success":false,"error":"PACK_ALREADY_ON_DEVICE","message":"The pack is already on the device"}'))
            .toBe('The pack is already on the device');
    });

    it('passes any other text through unchanged', () => {
        expect(errorMessage('Internal Server Error')).toBe('Internal Server Error');
        expect(errorMessage('{"other":1}')).toBe('{"other":1}');
    });
});

describe('handleJsonOrError', () => {
    it('rejects with the status text and the backend message', async () => {
        const response = {ok: false, statusText: 'Conflict', text: () => Promise.resolve('{"message":"already there"}')};
        await expect(handleJsonOrError(response)).rejects.toThrow('Conflict: already there');
    });
});
