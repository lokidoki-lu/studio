/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The sample pack (utils/sample.js, about a megabyte of base64) is not part of the main bundle: it is
 * only loaded, through import(), when the user opens it.
 */

const mockSampleLoaded = jest.fn();
jest.mock('../utils/sample', () => {
    mockSampleLoaded();
    return {
        simplifiedSample: () => ({title: 'Sample', version: 1, getEntryPoint: () => ({getUuid: () => 'uuid-1'})})
    };
});
jest.mock('react-toastify', () => ({
    toast: Object.assign(jest.fn(() => 'toast-id'), {
        update: jest.fn(),
        error: jest.fn(),
        dismiss: jest.fn(),
        TYPE: {INFO: 'info', SUCCESS: 'success', ERROR: 'error', WARNING: 'warning'}
    })
}));

const {toast} = require('react-toastify');
const {actionLoadSampleInEditor, loadSample} = require('./index');

const t = key => key;

describe('the sample pack', () => {
    it('is not loaded with the actions, only when it is opened', async () => {
        expect(mockSampleLoaded).not.toHaveBeenCalled();
        const dispatched = [];
        await actionLoadSampleInEditor(t)(action => dispatched.push(action));
        expect(mockSampleLoaded).toHaveBeenCalledTimes(1);
        expect(dispatched.map(a => a.type)).toEqual(['SET_EDITOR_DIAGRAM', 'SHOW_EDITOR']);
        expect(dispatched[0].filename).toBe('Sample-uuid-1-v1.zip');
    });

    it('loadSample resolves with the sample model', async () => {
        const model = await loadSample();
        expect(model.title).toBe('Sample');
    });

    it('says so when it cannot be loaded, and leaves the editor alone', async () => {
        const dispatched = [];
        await actionLoadSampleInEditor(t, () => Promise.reject(new Error('chunk failed')))(action => dispatched.push(action));
        expect(dispatched).toEqual([]);
        expect(toast).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({type: 'error'}));
    });
});
