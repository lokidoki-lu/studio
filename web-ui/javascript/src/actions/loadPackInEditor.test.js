/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Opening a pack in the editor from a ZIP archive: an unreadable archive says so and leaves the editor
 * alone, a library read still in flight cannot replace the imported pack, and the assets of the library
 * pack it replaces are let go.
 */

jest.mock('../services/device');
jest.mock('../services/library');
jest.mock('../utils/reader');
jest.mock('react-toastify', () => ({
    toast: Object.assign(jest.fn(() => 'toast-id'), {
        update: jest.fn(),
        error: jest.fn(),
        dismiss: jest.fn(),
        TYPE: { INFO: 'info', SUCCESS: 'success', ERROR: 'error', WARNING: 'warning' }
    })
}));

const { toast } = require('react-toastify');
const reader = require('../utils/reader');
const { actionLoadPackInEditor, actionLoadLibraryPackInEditor, actionCreatePackInEditor } = require('./index');

const t = key => key;

function deferred() {
    let resolve, reject;
    const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
}

beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
    console.error.mockRestore();
});

test('an unreadable archive shows the error, and neither installs a model nor renames the pack', async () => {
    reader.readFromArchive.mockRejectedValue(new Error('End of data reached'));
    const dispatched = [];
    await actionLoadPackInEditor({ name: 'broken.zip' }, 'broken.zip', t)(action => dispatched.push(action));
    expect(dispatched).toEqual([]);
    expect(toast.update).toHaveBeenLastCalledWith('toast-id', expect.objectContaining({ type: 'error' }));
    expect(toast.update).not.toHaveBeenCalledWith('toast-id', expect.objectContaining({ render: 'toasts.editor.loaded' }));
    expect(reader.releaseLibraryAssets).not.toHaveBeenCalled();
});

test('a read giving no model is a failure too, never an undefined diagram', async () => {
    reader.readFromArchive.mockResolvedValue(undefined);
    const dispatched = [];
    await actionLoadPackInEditor({ name: 'x.zip' }, 'x.zip', t)(action => dispatched.push(action));
    expect(dispatched).toEqual([]);
    expect(toast.update).toHaveBeenLastCalledWith('toast-id', expect.objectContaining({ type: 'error' }));
});

test('a readable archive is installed, and the assets of the previous library pack are released', async () => {
    const model = { title: 'Imported' };
    reader.readFromArchive.mockResolvedValue(model);
    const dispatched = [];
    await actionLoadPackInEditor({ name: 'ok.zip' }, 'ok.zip', t)(action => dispatched.push(action));
    expect(dispatched.map(a => a.type)).toEqual(['SET_EDITOR_DIAGRAM', 'SHOW_EDITOR']);
    expect(dispatched[0].diagram).toBe(model);
    expect(dispatched[0].filename).toBe('ok.zip');
    expect(reader.releaseLibraryAssets).toHaveBeenCalledTimes(1);
});

test('a library read started before an import, and finishing after it, does not replace the imported pack', async () => {
    const library = deferred();
    reader.readFromLibrary.mockImplementation(() => library.promise);
    const imported = { title: 'Imported' };
    reader.readFromArchive.mockResolvedValue(imported);
    const dispatched = [];
    const dispatch = action => dispatched.push(action);

    const libraryDone = actionLoadLibraryPackInEditor('big.zip', t)(dispatch);
    await actionLoadPackInEditor({ name: 'ok.zip' }, 'ok.zip', t)(dispatch);
    const fromLibrary = { title: 'From library' };
    library.resolve(fromLibrary);
    await libraryDone;

    const diagrams = dispatched.filter(a => a.type === 'SET_EDITOR_DIAGRAM');
    expect(diagrams).toHaveLength(1);
    expect(diagrams[0].diagram).toBe(imported);
    // The stale read lets go of its own assets
    expect(reader.discardLibraryAssets).toHaveBeenCalledWith(fromLibrary);
    expect(reader.adoptLibraryAssets).not.toHaveBeenCalled();
});

test('a new empty pack releases the assets of the library pack, and wins over a read in flight', async () => {
    const library = deferred();
    reader.readFromLibrary.mockImplementation(() => library.promise);
    const dispatched = [];
    const dispatch = action => dispatched.push(action);

    const libraryDone = actionLoadLibraryPackInEditor('big.zip', t)(dispatch);
    actionCreatePackInEditor(t)(dispatch);
    expect(reader.releaseLibraryAssets).toHaveBeenCalledTimes(1);
    library.resolve({ title: 'From library' });
    await libraryDone;

    const diagrams = dispatched.filter(a => a.type === 'SET_EDITOR_DIAGRAM');
    expect(diagrams).toHaveLength(1);
    expect(diagrams[0].diagram.title).not.toBe('From library');
});
