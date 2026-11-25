/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Sending one pack whose device-format artefact is a converted folder. The server sends such a folder
 * only once verifyConversion answers MATCH: nothing is offered nor tried that it would refuse.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act} from 'react-dom/test-utils';

import {PackLibrarySimple} from './PackLibrarySimple';

jest.mock('react-i18next', () => {
    const mockReact = require('react');
    const mockT = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;
    return {
        withTranslation: () => (Component) => (props) => mockReact.createElement(Component, {t: mockT, ...props})
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
jest.mock('./YoutubePack', () => () => null);
jest.mock('./LibraryPurge', () => () => null);
jest.mock('./PackPreview', () => () => null);

const {toast} = require('react-toastify');

const t = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;

const device = {metadata: {uuid: 'dev', driver: 'fs', storage: {size: 4e9, taken: 1e9, free: 3e9}}, packs: []};

function actions(verdict = 'UNKNOWN') {
    return {
        addFromLibrary: jest.fn(),
        updateOnDevice: jest.fn(),
        removeFromDevice: jest.fn(() => Promise.resolve()),
        reorderOnDevice: jest.fn(),
        addToLibrary: jest.fn(),
        loadLibraryPackInEditor: jest.fn(),
        convertPackInLibrary: jest.fn((uuid) => Promise.resolve(uuid + '.converted_9')),
        removeFromLibrary: jest.fn(() => Promise.resolve()),
        removeManyFromLibrary: jest.fn(() => Promise.resolve()),
        uploadPackToLibrary: jest.fn(),
        uploadPacksToLibrary: jest.fn(),
        verifyConversion: jest.fn(() => Promise.resolve({verdict})),
        estimateOnDevice: jest.fn(() => Promise.reject(new Error('no estimate'))),
        createPackInEditor: jest.fn(),
        loadSampleInEditor: jest.fn(),
        setAllowEnriched: jest.fn()
    };
}

let container;

beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    jest.spyOn(console, 'log').mockImplementation(() => {});
    container = document.createElement('div');
    document.body.appendChild(container);
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
    container = null;
    console.log.mockRestore();
});

const flush = async (times = 8) => {
    for (let i = 0; i < times; i++) {
        await act(() => new Promise(resolve => setTimeout(resolve, 0)));
    }
};
const card = (title) => Array.from(container.querySelectorAll('.simple-card')).find(c => c.querySelector('.simple-title').textContent === title);
const button = (root, text) => Array.from(root.querySelectorAll('button')).find(b => b.textContent.trim() === text);
const modal = (id) => document.getElementById('modal-' + id);

function render(packs, props) {
    act(() => {
        ReactDOM.render(<PackLibrarySimple t={t} settings={{allowEnriched: false}} device={device}
                                           library={{metadata: {path: '/library'}, packs: [{uuid: 'u-a', packs}]}} {...props}/>, container);
    });
}

const SOURCE = {uuid: 'u-a', path: 'u-a.zip', format: 'archive', title: 'Alpha', size: 1, version: 1};
const CONVERTED = {uuid: 'u-a', path: 'u-a.converted_1', format: 'fs', title: 'Alpha', size: 1, version: 1};

describe('a converted folder', () => {

    it('whose source archive is gone is not sent: the user is told to put the archive back', async () => {
        const props = actions();
        render([CONVERTED], props);
        act(() => { button(card('Alpha'), 'simpleLibrary.card.send').click(); });
        await flush();
        expect(props.addFromLibrary).not.toHaveBeenCalled();
        expect(props.convertPackInLibrary).not.toHaveBeenCalled();
        expect(toast.error).toHaveBeenCalledWith('toasts.library.convertedSourceMissing', expect.anything());
    });

    it('that cannot be linked to its archive: only "convert again" or "cancel", with the reason', async () => {
        const props = actions('UNKNOWN');
        render([CONVERTED, SOURCE], props);
        act(() => { button(card('Alpha'), 'simpleLibrary.card.send').click(); });
        await flush();
        const ask = modal('ask-confirm-conversion');
        expect(ask).not.toBeNull();
        expect(Array.from(ask.querySelectorAll('.modal-footer button')).map(b => b.textContent.trim()))
            .toEqual(['dialogs.library.askConfirmConversion.cancel', 'dialogs.library.askConfirmConversion.convert']);
        expect(ask.textContent).toContain('dialogs.library.askConfirmConversion.content');

        act(() => { button(ask, 'dialogs.library.askConfirmConversion.convert').click(); });
        await flush();
        expect(props.convertPackInLibrary).toHaveBeenCalledWith('u-a', 'u-a.zip', 'fs', false, expect.anything());
        expect(props.addFromLibrary).toHaveBeenCalledWith('u-a', 'u-a.converted_9', 'fs', 'fs', expect.anything());
    });

    it('known not to match: the same two choices, and cancel sends nothing', async () => {
        const props = actions('MISMATCH');
        render([CONVERTED, SOURCE], props);
        act(() => { button(card('Alpha'), 'simpleLibrary.card.send').click(); });
        await flush();
        const ask = modal('ask-confirm-conversion');
        expect(ask.textContent).toContain('dialogs.library.askConfirmConversion.contentMismatch');
        expect(button(ask, 'dialogs.library.askConfirmConversion.transfer')).toBeUndefined();
        act(() => { button(ask, 'dialogs.library.askConfirmConversion.cancel').click(); });
        await flush();
        expect(props.addFromLibrary).not.toHaveBeenCalled();
        expect(props.convertPackInLibrary).not.toHaveBeenCalled();
    });

    it('proven to match its archive is sent as it is', async () => {
        const props = actions('MATCH');
        render([CONVERTED, SOURCE], props);
        act(() => { button(card('Alpha'), 'simpleLibrary.card.send').click(); });
        await flush();
        expect(modal('ask-confirm-conversion')).toBeNull();
        expect(props.addFromLibrary).toHaveBeenCalledWith('u-a', 'u-a.converted_1', 'fs', 'fs', expect.anything());
    });
});
