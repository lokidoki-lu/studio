/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The editor toolbar: "saved" only once the library holds the pack, import of a .zip whatever its MIME
 * type, a cancelled file dialog, and a confirmation before the whole pack is cleared.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act, Simulate} from 'react-dom/test-utils';

import {PackEditor} from './PackEditor';
import {writeToArchive} from '../../utils/writer';

jest.mock('react-i18next', () => {
    const mockReact = require('react');
    const mockT = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;
    return {
        withTranslation: () => (Component) => (props) => mockReact.createElement(Component, {t: mockT, ...props})
    };
});
jest.mock('react-toastify', () => ({
    toast: Object.assign(jest.fn(() => 'editor-toast'), {
        update: jest.fn(),
        error: jest.fn(),
        dismiss: jest.fn(),
        TYPE: {INFO: 'info', SUCCESS: 'success', ERROR: 'error', WARNING: 'warning'}
    })
}));
jest.mock('./widgets/PackDiagramWidget', () => () => null);
jest.mock('./TitleVoiceControls', () => () => null);
jest.mock('../../utils/writer', () => ({writeToArchive: jest.fn()}));

const {toast} = require('react-toastify');
const PackDiagramModel = require('./models/PackDiagramModel').default;

const t = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;

let container;

beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, 'log').mockImplementation(() => {});
    jest.spyOn(console, 'error').mockImplementation(() => {});
    container = document.createElement('div');
    document.body.appendChild(container);
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
    container = null;
    console.log.mockRestore();
    console.error.mockRestore();
});

function render(props = {}) {
    const all = {
        t,
        editor: {diagram: null, filename: 'pack.zip'},
        library: {packs: []},
        createPackInEditor: jest.fn(),
        loadPackInEditor: jest.fn(),
        uploadPackToLibrary: jest.fn(),
        ...props
    };
    let instance;
    act(() => {
        ReactDOM.render(<PackEditor ref={r => { instance = r; }} {...all}/>, container);
    });
    return {props: all, instance};
}

const button = (text) => Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === text);
const fileInput = () => container.querySelector('#upload');

function selectFiles(files) {
    const input = fileInput();
    Object.defineProperty(input, 'files', {value: files, configurable: true});
    act(() => { Simulate.change(input); });
    return input;
}

function modelWithEntryPoint() {
    const model = new PackDiagramModel();
    model.getEntryPoint = () => ({getUuid: () => 'uuid-1'});
    return model;
}

describe('saving to the library', () => {

    it('says "saved" when the library holds the pack', async () => {
        writeToArchive.mockResolvedValue(new Blob(['zip']));
        const {instance, props} = render({uploadPackToLibrary: jest.fn(() => Promise.resolve(true))});
        instance.state.engine.setModel(modelWithEntryPoint());
        await act(() => instance.doSavePackToLibrary());
        expect(props.uploadPackToLibrary).toHaveBeenCalled();
        expect(toast.update).toHaveBeenLastCalledWith('editor-toast', expect.objectContaining({type: 'success', render: 'toasts.editor.saved'}));
    });

    it('never says "saved" when the upload failed: only the upload error stays', async () => {
        writeToArchive.mockResolvedValue(new Blob(['zip']));
        const {instance} = render({uploadPackToLibrary: jest.fn(() => Promise.resolve(false))});
        instance.state.engine.setModel(modelWithEntryPoint());
        await act(() => instance.doSavePackToLibrary());
        expect(toast.update).not.toHaveBeenCalledWith('editor-toast', expect.objectContaining({render: 'toasts.editor.saved'}));
        expect(toast.dismiss).toHaveBeenCalledWith('editor-toast');
    });
});

describe('import', () => {

    it('accepts a .zip whose MIME type the browser left empty (Windows)', () => {
        const {props} = render();
        const file = {name: 'Pack.ZIP', type: ''};
        const input = selectFiles([file]);
        expect(props.loadPackInEditor).toHaveBeenCalledWith(file, 'Pack.ZIP');
        expect(toast.error).not.toHaveBeenCalled();
        // Emptied: the same file can be chosen again
        expect(input.value).toBe('');
    });

    it('still refuses a file that is not a ZIP archive', () => {
        const {props} = render();
        selectFiles([{name: 'notes.txt', type: 'text/plain'}]);
        expect(props.loadPackInEditor).not.toHaveBeenCalled();
        expect(toast.error).toHaveBeenCalledWith('toasts.editor.loadingWrongType');
    });

    it('does nothing when the dialog is cancelled (empty selection)', () => {
        const {props} = render();
        expect(() => selectFiles([])).not.toThrow();
        expect(props.loadPackInEditor).not.toHaveBeenCalled();
        expect(toast.error).not.toHaveBeenCalled();
    });
});

describe('clearing', () => {

    it('asks first, and clears only on yes', () => {
        const {props} = render();
        const trash = container.querySelector('.glyphicon-trash');
        act(() => { trash.click(); });
        expect(props.createPackInEditor).not.toHaveBeenCalled();
        expect(document.getElementById('modal-confirm-editor-clear')).not.toBeNull();
        expect(document.body.textContent).toContain('dialogs.editor.clear.content');

        act(() => { button('dialogs.shared.no').click(); });
        expect(props.createPackInEditor).not.toHaveBeenCalled();
        expect(document.getElementById('modal-confirm-editor-clear')).toBeNull();

        act(() => { trash.click(); });
        act(() => { button('dialogs.shared.yes').click(); });
        expect(props.createPackInEditor).toHaveBeenCalledTimes(1);
        expect(document.getElementById('modal-confirm-editor-clear')).toBeNull();
    });
});
