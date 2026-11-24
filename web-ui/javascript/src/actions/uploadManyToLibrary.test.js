/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * actionUploadManyToLibrary: several pack files imported into the library in one go. Files go up
 * one after the other, a failed one does not stop the others, and the outcome names what failed.
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
const { actionUploadManyToLibrary } = require('./index');

/** Translation stub: the key and its options, so assertions pin the message and its values. */
const t = (key, options) => options ? `${key} ${JSON.stringify(options)}` : key;

const file = name => ({ name, type: 'application/zip' });

/** A promise with its resolve/reject exposed, to hold an upload in flight. */
function deferred() {
    let resolve, reject;
    const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
}

beforeEach(() => {
    jest.clearAllMocks();
    libraryService.fetchLibraryInfos.mockResolvedValue({ path: '/lib' });
    libraryService.fetchLibraryPacks.mockResolvedValue([]);
});

test('uploads the files one after the other, never two at once', async () => {
    const first = deferred();
    const second = deferred();
    libraryService.uploadToLibrary
        .mockImplementationOnce(() => first.promise)
        .mockImplementationOnce(() => second.promise);

    const done = actionUploadManyToLibrary([file('a.zip'), file('b.zip')], t)(jest.fn());
    await Promise.resolve();

    expect(libraryService.uploadToLibrary).toHaveBeenCalledTimes(1);
    expect(libraryService.uploadToLibrary.mock.calls[0][1]).toBe('a.zip');

    first.resolve({ success: true });
    await new Promise(r => setTimeout(r, 0));
    expect(libraryService.uploadToLibrary).toHaveBeenCalledTimes(2);
    expect(libraryService.uploadToLibrary.mock.calls[1][1]).toBe('b.zip');

    second.resolve({ success: true });
    await expect(done).resolves.toEqual({ total: 2, failed: [] });
});

test('reports success once, and refreshes the library once, at the end', async () => {
    libraryService.uploadToLibrary.mockResolvedValue({ success: true });
    const dispatch = jest.fn();

    await actionUploadManyToLibrary([file('a.zip'), file('b.zip'), file('c.pack')], t)(dispatch);

    expect(dispatch).toHaveBeenCalledTimes(1);
    expect(toast).toHaveBeenCalledTimes(1);
    const last = toast.update.mock.calls[toast.update.mock.calls.length - 1][1];
    expect(last.type).toBe('success');
    expect(last.render).toBe('toasts.library.uploadedMany {"count":3}');
});

test('goes on after a failed file and names every file that failed', async () => {
    libraryService.uploadToLibrary
        .mockRejectedValueOnce(new Error('HTTP 500'))
        .mockResolvedValueOnce({ success: true })
        .mockResolvedValueOnce({ success: false });

    const result = await actionUploadManyToLibrary([file('a.zip'), file('b.zip'), file('c.zip')], t)(jest.fn());

    expect(libraryService.uploadToLibrary).toHaveBeenCalledTimes(3);
    expect(result).toEqual({ total: 3, failed: ['a.zip', 'c.zip'] });
    const last = toast.update.mock.calls[toast.update.mock.calls.length - 1][1];
    expect(last.type).toBe('error');
    expect(last.autoClose).toBe(false);
    expect(last.render).toBe('toasts.library.uploadedManyPartial {"ok":1,"total":3,"failed":"a.zip, c.zip"}');
});

test('keeps the progress bar below 1, which would close the batch toast', async () => {
    libraryService.uploadToLibrary.mockImplementation((uuid, path, data, onProgress) => {
        onProgress({ lengthComputable: true, loaded: 100, total: 100 });
        return Promise.resolve({ success: true });
    });

    await actionUploadManyToLibrary([file('a.zip'), file('b.zip')], t)(jest.fn());

    const progresses = toast.update.mock.calls
        .map(call => call[1].progress)
        .filter(progress => typeof progress === 'number');
    expect(progresses.length).toBeGreaterThan(0);
    progresses.forEach(progress => expect(progress).toBeLessThan(1));
});
