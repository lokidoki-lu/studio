/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * actionRemoveManyFromLibrary: every file of a pack removed from the library in one go, with one
 * toast and one refresh of the library, a failed file not stopping the others.
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
const { actionRemoveManyFromLibrary } = require('./index');

const t = (key, options) => options ? `${key} ${JSON.stringify(options)}` : key;

beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, 'error').mockImplementation(() => {});
    libraryService.fetchLibraryInfos.mockResolvedValue({ path: '/lib' });
    libraryService.fetchLibraryPacks.mockResolvedValue([]);
});

afterEach(() => {
    console.error.mockRestore();
});

// The thunks dispatched (the refresh) are run, as redux-thunk would
function dispatcher() {
    const dispatch = jest.fn(action => typeof action === 'function' ? action(dispatch) : action);
    return dispatch;
}

test('removes the files one after the other, with one toast and one refresh', async () => {
    libraryService.removeFromLibrary.mockResolvedValue({ success: true });
    await actionRemoveManyFromLibrary(['a.zip', 'a.fs.pack'], t)(dispatcher());

    expect(libraryService.removeFromLibrary.mock.calls).toEqual([['a.zip'], ['a.fs.pack']]);
    expect(toast).toHaveBeenCalledTimes(1);
    expect(toast.update).toHaveBeenCalledTimes(1);
    expect(toast.update.mock.calls[0][1].type).toBe('success');
    expect(libraryService.fetchLibraryPacks).toHaveBeenCalledTimes(1);
});

test('goes on after a failed file, then says the removal failed', async () => {
    libraryService.removeFromLibrary
        .mockRejectedValueOnce(new Error('locked'))
        .mockResolvedValueOnce({ success: true });
    await actionRemoveManyFromLibrary(['a.zip', 'a.fs.pack'], t)(dispatcher());

    expect(libraryService.removeFromLibrary).toHaveBeenCalledTimes(2);
    expect(toast.update).toHaveBeenCalledTimes(1);
    expect(toast.update.mock.calls[0][1].type).toBe('error');
    expect(libraryService.fetchLibraryPacks).toHaveBeenCalledTimes(1);
});
