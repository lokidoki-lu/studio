/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {errorMessage, errorCode, handleJsonOrError, apiFetch, httpErrorMessage} from './fetch';

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
    it('rejects with the backend message alone when it gives one', async () => {
        const response = {ok: false, status: 409, statusText: 'Conflict', text: () => Promise.resolve('{"message":"already there"}')};
        const error = await handleJsonOrError(response).catch(e => e);
        expect(error.message).toBe('already there');
        expect(error.status).toBe(409);
    });

    it('rejects with the status text and the body otherwise', async () => {
        const response = {ok: false, status: 500, statusText: 'Internal Server Error', text: () => Promise.resolve('boom')};
        await expect(handleJsonOrError(response)).rejects.toThrow('Internal Server Error: boom');
        expect(httpErrorMessage('Bad Gateway', '')).toBe('Bad Gateway');
    });

    it('keeps the backend error code and the HTTP status on the rejection', async () => {
        const response = {ok: false, status: 404, statusText: 'Not Found',
            text: () => Promise.resolve('{"success":false,"error":"TRANSFER_NOT_FOUND","message":"No transfer with id x"}')};
        const error = await handleJsonOrError(response).catch(e => e);
        expect(error).toBeInstanceOf(Error);
        expect(error.message).toBe('No transfer with id x');
        expect(error.code).toBe('TRANSFER_NOT_FOUND');
        expect(error.status).toBe(404);
    });

    it('keeps the status, and no code, for a plain-text error', async () => {
        const response = {ok: false, status: 500, statusText: 'Internal Server Error', text: () => Promise.resolve('boom')};
        const error = await handleJsonOrError(response).catch(e => e);
        expect(error.status).toBe(500);
        expect(error.code).toBeUndefined();
    });

    it('resolves with the parsed body of a successful response', async () => {
        const response = {ok: true, json: () => Promise.resolve({transferId: 't'})};
        await expect(handleJsonOrError(response)).resolves.toEqual({transferId: 't'});
    });
});

describe('apiFetch', () => {
    afterEach(() => {
        delete global.fetch;
    });

    it('says STUdio does not answer when the request gets no answer at all', async () => {
        global.fetch = jest.fn(() => Promise.reject(new TypeError('Failed to fetch')));
        const error = await apiFetch('/api/library/infos').catch(e => e);
        expect(error.code).toBe('UNREACHABLE');
        expect(error.message).toMatch(/STUdio/);
        expect(error.cause).toBeInstanceOf(TypeError);
    });

    it('passes the answer, and any other failure, through', async () => {
        const answer = {ok: true};
        global.fetch = jest.fn(() => Promise.resolve(answer));
        await expect(apiFetch('/api/x', {method: 'POST'})).resolves.toBe(answer);
        expect(global.fetch).toHaveBeenCalledWith('/api/x', {method: 'POST'});
        const aborted = new Error('aborted');
        global.fetch = jest.fn(() => Promise.reject(aborted));
        await expect(apiFetch('/api/x')).rejects.toBe(aborted);
    });
});

describe('errorCode', () => {
    it('reads the code of a JSON refusal, and nothing else', () => {
        expect(errorCode('{"error":"NOT_ENOUGH_SPACE","message":"full"}')).toBe('NOT_ENOUGH_SPACE');
        expect(errorCode('Internal Server Error')).toBeUndefined();
        expect(errorCode('{"message":"x"}')).toBeUndefined();
    });
});
