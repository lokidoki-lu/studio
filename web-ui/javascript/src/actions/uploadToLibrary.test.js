/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * actionUploadToLibrary: one pack file into the library (import of a single file, save from the
 * editor). Its toast has to survive until the server answers, and its outcome has to reach the caller.
 */

jest.mock('../services/device');
jest.mock('../services/library');
jest.mock('react-toastify', () => ({
    toast: Object.assign(jest.fn(() => 'toast-id'), {
        update: jest.fn(),
        error: jest.fn(),
        dismiss: jest.fn(),
        TYPE: { INFO: 'info', SUCCESS: 'success', ERROR: 'error', WARNING: 'warning' }
    })
}));

const { toast } = require('react-toastify');
const libraryService = require('../services/library');
const { actionUploadToLibrary } = require('./index');

const t = key => key;

beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, 'log').mockImplementation(() => {});
    jest.spyOn(console, 'error').mockImplementation(() => {});
    libraryService.fetchLibraryInfos.mockResolvedValue({ path: '/lib' });
    libraryService.fetchLibraryPacks.mockResolvedValue([]);
});

afterEach(() => {
    console.log.mockRestore();
    console.error.mockRestore();
});

test('the progress stays below 1 until the server answers: a controlled bar at 1 closes its toast', async () => {
    let progressHandler;
    libraryService.uploadToLibrary.mockImplementation((uuid, path, data, onProgress) => {
        progressHandler = onProgress;
        return new Promise(() => {});
    });
    actionUploadToLibrary(null, 'a.zip', {}, t)(jest.fn());

    progressHandler({ lengthComputable: true, loaded: 50, total: 100 });
    progressHandler({ lengthComputable: true, loaded: 100, total: 100 });

    const progresses = toast.update.mock.calls.map(call => call[1].progress);
    expect(progresses).toEqual([0.5, 0.99]);
});

test('resolves with true when the library holds the pack', async () => {
    libraryService.uploadToLibrary.mockResolvedValue({ success: true });
    await expect(actionUploadToLibrary(null, 'a.zip', {}, t)(jest.fn())).resolves.toBe(true);
    expect(toast.update).toHaveBeenLastCalledWith('toast-id', expect.objectContaining({ type: 'success' }));
});

test('resolves with false, and says so, when the server refuses the pack', async () => {
    libraryService.uploadToLibrary.mockResolvedValue({ success: false });
    await expect(actionUploadToLibrary(null, 'a.zip', {}, t)(jest.fn())).resolves.toBe(false);
    expect(toast.update).toHaveBeenLastCalledWith('toast-id', expect.objectContaining({ type: 'error', autoClose: false }));
});

test('resolves with false, and shows the error, when the upload fails (400, STUdio stopped)', async () => {
    libraryService.uploadToLibrary.mockRejectedValue(new Error('Bad Request: invalid pack'));
    const dispatch = jest.fn();
    await expect(actionUploadToLibrary(null, 'a.zip', {}, t)(dispatch)).resolves.toBe(false);
    expect(toast.update).toHaveBeenLastCalledWith('toast-id', expect.objectContaining({ type: 'error', autoClose: false }));
    // No refresh of a library that did not change
    expect(dispatch).not.toHaveBeenCalled();
});
