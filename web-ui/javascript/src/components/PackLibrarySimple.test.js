/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The simplified library and the mode switch, rendered in jsdom with the Redux actions replaced by
 * mocks: what is shown, and which existing action each button reaches.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act} from 'react-dom/test-utils';

import {PackLibrarySimple} from './PackLibrarySimple';
import {LibraryView} from './LibraryView';

jest.mock('react-i18next', () => {
    const mockReact = require('react');
    const mockT = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;
    return {
        withTranslation: () => (Component) => (props) => mockReact.createElement(Component, {t: mockT, ...props})
    };
});
jest.mock('./YoutubePack', () => () => null);
jest.mock('./LibraryPurge', () => () => null);
jest.mock('./PackPreview', () => {
    const mockReact = require('react');
    return (props) => mockReact.createElement('div', {className: 'mock-preview', 'data-path': props.pack.path, 'data-uuid': props.pack.uuid},
        mockReact.createElement('button', {onClick: props.onClose}, 'close'));
});
jest.mock('./PackLibrary', () => {
    const actual = jest.requireActual('./PackLibrary');
    const mockReact = require('react');
    return {...actual, __esModule: true, default: () => mockReact.createElement('div', {className: 'mock-full'})};
});

const t = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;

const LIBRARY = {
    metadata: {path: '/library'},
    packs: [
        {uuid: 'u-raw', packs: [{uuid: 'u-raw', path: 'raw.pack', format: 'raw', title: 'Les Dalton', description: 'Des bandits', size: 5e6, version: 1, timestamp: 2}]},
        {uuid: 'u-zip', packs: [
            {uuid: 'u-zip', path: 'zip.zip', format: 'archive', title: 'Bambi', size: 3e6, version: 1, timestamp: 1},
            {uuid: 'u-zip', path: 'zip.fs.pack', format: 'fs', title: 'Bambi', size: 2e6, version: 1, timestamp: 1}
        ]},
        {uuid: 'u-on', packs: [{uuid: 'u-on', path: 'on.pack', format: 'raw', title: 'Aladdin', size: 1e6, version: 1, timestamp: 3}]}
    ]
};

const DEVICE = {
    metadata: {uuid: 'dev', driver: 'raw', storage: {size: 4e9, taken: 1e9, free: 3e9}},
    packs: [
        {uuid: 'u-on', title: 'Aladdin', version: 1, format: 'raw'},
        {uuid: 'u-other', title: 'Officiel', version: 1, format: 'raw', official: true},
        {uuid: 'u-mine', title: 'Le mien', version: 1, format: 'raw'}
    ]
};

function actions() {
    return {
        addFromLibrary: jest.fn(),
        removeFromDevice: jest.fn(() => Promise.resolve()),
        reorderOnDevice: jest.fn(),
        addToLibrary: jest.fn(),
        loadLibraryPackInEditor: jest.fn(),
        convertPackInLibrary: jest.fn(() => Promise.resolve('converted.pack')),
        removeFromLibrary: jest.fn(() => Promise.resolve()),
        uploadPackToLibrary: jest.fn(),
        uploadPacksToLibrary: jest.fn(),
        verifyConversion: jest.fn(() => Promise.resolve({verdict: 'UNKNOWN'})),
        createPackInEditor: jest.fn(),
        loadSampleInEditor: jest.fn(),
        setAllowEnriched: jest.fn()
    };
}

let container;

beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
    container = null;
});

function renderSimple(props) {
    act(() => {
        ReactDOM.render(<PackLibrarySimple t={t} settings={{allowEnriched: false}} {...props}/>, container);
    });
}

const cards = () => Array.from(container.querySelectorAll('.simple-card'));
const card = (title) => cards().find(c => c.querySelector('.simple-title').textContent === title);
const button = (root, text) => Array.from(root.querySelectorAll('button')).find(b => b.textContent.trim() === text);
const byLabel = (label) => container.querySelector(`[aria-label="${label.replace(/"/g, '\\"')}"]`);
const flush = () => act(() => new Promise(resolve => setTimeout(resolve, 0)));
const change = (element, value, property = 'value') => {
    const proto = Object.getPrototypeOf(element);
    Object.getOwnPropertyDescriptor(proto, property).set.call(element, value);
    element.dispatchEvent(new Event(property === 'checked' ? 'click' : 'input', {bubbles: true}));
};

describe('PackLibrarySimple', () => {

    it('shows one card per pack, sorted by title, with its description and size', () => {
        renderSimple({device: DEVICE, library: LIBRARY, ...actions()});
        expect(cards().map(c => c.querySelector('.simple-title').textContent)).toEqual(['Aladdin', 'Bambi', 'Les Dalton']);
        const dalton = card('Les Dalton');
        expect(dalton.querySelector('.simple-desc').textContent).toBe('Des bandits');
        expect(dalton.querySelector('.simple-meta').textContent).toContain('libraryPurge.units.mb');
        expect(card('Bambi').querySelector('.simple-meta').textContent).toContain('simpleLibrary.card.files {"count":2}');
        // No file names nor formats in this view
        expect(container.textContent).not.toContain('raw.pack');
        expect(container.querySelector('.pack-format-badge')).toBeNull();
    });

    it('marks the packs already on the Lunii and offers to remove them from it', () => {
        const props = actions();
        renderSimple({device: DEVICE, library: LIBRARY, ...props});
        const aladdin = card('Aladdin');
        expect(aladdin.classList.contains('on-device')).toBe(true);
        expect(aladdin.querySelector('.simple-badge.ok')).not.toBeNull();
        act(() => { button(aladdin, 'simpleLibrary.card.removeFromDevice').click(); });
        // The full library's own confirmation dialog
        expect(document.getElementById('modal-confirm-device-pack-remove')).not.toBeNull();
    });

    it('sends a pack the device can read straight away, through the existing transfer action', () => {
        const props = actions();
        renderSimple({device: DEVICE, library: LIBRARY, ...props});
        act(() => { button(card('Les Dalton'), 'simpleLibrary.card.send').click(); });
        expect(props.addFromLibrary).toHaveBeenCalledWith('u-raw', 'raw.pack', 'raw', 'raw', expect.anything());
    });

    it('converts first when the device cannot read the pack, as the full library does', async () => {
        const props = actions();
        const device = {...DEVICE, metadata: {...DEVICE.metadata, driver: 'fs'}, packs: []};
        const library = {...LIBRARY, packs: [{uuid: 'u-arc', packs: [{uuid: 'u-arc', path: 'arc.zip', format: 'archive', title: 'Archive', size: 1}]}]};
        renderSimple({device, library, ...props});
        act(() => { button(card('Archive'), 'simpleLibrary.card.send').click(); });
        expect(props.convertPackInLibrary).toHaveBeenCalledWith('u-arc', 'arc.zip', 'fs', false, expect.anything());
        await flush();
        expect(props.addFromLibrary).toHaveBeenCalledWith('u-arc', 'converted.pack', 'fs', 'fs', expect.anything());
    });

    it('cannot send without a Lunii, and says so', () => {
        const props = actions();
        renderSimple({device: {metadata: null, packs: []}, library: LIBRARY, ...props});
        expect(container.textContent).toContain('simpleLibrary.device.unplugged');
        const send = button(card('Les Dalton'), 'simpleLibrary.card.send');
        expect(send.disabled).toBe(true);
        expect(send.title).toBe('simpleLibrary.card.plugFirst');
    });

    it('deletes every file of a pack after confirmation', async () => {
        const props = actions();
        renderSimple({device: DEVICE, library: LIBRARY, ...props});
        act(() => { byLabel('simpleLibrary.card.remove {"title":"Bambi"}').click(); });
        const dialog = document.getElementById('modal-confirm-simple-pack-remove');
        expect(dialog.textContent).toContain('simpleLibrary.removeDialog.contentMany');
        act(() => { button(dialog, 'dialogs.shared.yes').click(); });
        await flush();
        expect(props.removeFromLibrary.mock.calls).toEqual([['zip.zip'], ['zip.fs.pack']]);
        expect(document.getElementById('modal-confirm-simple-pack-remove')).toBeNull();
    });

    it('opens an archive in the editor, and offers no editor for a pack without one', () => {
        const props = actions();
        renderSimple({device: DEVICE, library: LIBRARY, ...props});
        act(() => { byLabel('simpleLibrary.card.edit {"title":"Bambi"}').click(); });
        expect(props.loadLibraryPackInEditor).toHaveBeenCalledWith('zip.zip');
        expect(byLabel('simpleLibrary.card.edit {"title":"Les Dalton"}')).toBeNull();
    });

    it('filters on the search and on "not on the Lunii yet"', () => {
        renderSimple({device: DEVICE, library: LIBRARY, ...actions()});
        const search = container.querySelector('input[type="search"]');
        act(() => { change(search, 'dalton'); });
        expect(cards().map(c => c.querySelector('.simple-title').textContent)).toEqual(['Les Dalton']);
        act(() => { change(search, ''); });
        act(() => { container.querySelector('.simple-filter input').click(); });
        expect(cards().map(c => c.querySelector('.simple-title').textContent)).toEqual(['Bambi', 'Les Dalton']);
    });

    it('reorders the Lunii with buttons, and copies a pack missing from the library', () => {
        const props = actions();
        renderSimple({device: DEVICE, library: LIBRARY, ...props});
        expect(byLabel('simpleLibrary.device.moveUp {"title":"Aladdin"}').disabled).toBe(true);
        act(() => { byLabel('simpleLibrary.device.moveDown {"title":"Aladdin"}').click(); });
        expect(props.reorderOnDevice).toHaveBeenCalledWith(['u-other', 'u-on', 'u-mine']);
        // Aladdin is in the library already, the official pack cannot be copied
        expect(byLabel('simpleLibrary.device.save {"title":"Aladdin"}')).toBeNull();
        expect(byLabel('simpleLibrary.device.save {"title":"Officiel"}')).toBeNull();
        act(() => { byLabel('simpleLibrary.device.save {"title":"Le mien"}').click(); });
        expect(props.addToLibrary).toHaveBeenCalledWith('u-mine', 'raw', expect.anything());
        expect(container.querySelector('[role="progressbar"]').getAttribute('aria-valuenow')).toBe('25');
    });

    it('opens the preview of the pack, its archive when there is one', () => {
        renderSimple({device: DEVICE, library: LIBRARY, ...actions()});
        act(() => { byLabel('simpleLibrary.card.preview {"title":"Bambi"}').click(); });
        const preview = container.querySelector('.mock-preview');
        expect(preview.getAttribute('data-path')).toBe('zip.zip');
        expect(preview.getAttribute('data-uuid')).toBe('u-zip');
        act(() => { button(preview, 'close').click(); });
        expect(container.querySelector('.mock-preview')).toBeNull();
    });

    it('says when the library is empty', () => {
        renderSimple({device: DEVICE, library: {metadata: null, packs: []}, ...actions()});
        expect(container.textContent).toContain('simpleLibrary.library.empty');
    });
});

describe('LibraryView', () => {

    function memoryStorage(initial = {}) {
        const values = {...initial};
        return {getItem: k => (k in values ? values[k] : null), setItem: (k, v) => { values[k] = v; }, values};
    }

    const Simple = () => <div className="fake-simple"/>;
    const Full = () => <div className="fake-full"/>;

    function renderView(storage) {
        act(() => {
            ReactDOM.render(<LibraryView t={t} storage={storage} simpleComponent={Simple} fullComponent={Full}/>, container);
        });
    }

    const option = (mode) => container.querySelector(`[data-mode="${mode}"]`);

    it('shows the full library by default', () => {
        renderView(memoryStorage());
        expect(container.querySelector('.fake-full')).not.toBeNull();
        expect(option('full').getAttribute('aria-pressed')).toBe('true');
        expect(option('simple').getAttribute('aria-pressed')).toBe('false');
    });

    it('switches to the simplified library and remembers it', () => {
        const storage = memoryStorage();
        renderView(storage);
        act(() => { option('simple').click(); });
        expect(container.querySelector('.fake-simple')).not.toBeNull();
        expect(container.querySelector('.fake-full')).toBeNull();
        expect(storage.values.libraryMode).toBe('simple');

        ReactDOM.unmountComponentAtNode(container);
        renderView(storage);
        expect(container.querySelector('.fake-simple')).not.toBeNull();
    });

    it('moves between the two modes with the arrow keys', () => {
        renderView(memoryStorage());
        const group = container.querySelector('[role="group"]');
        expect(group.getAttribute('aria-label')).toBe('libraryMode.label');
        act(() => {
            option('full').focus();
            option('full').dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowRight', bubbles: true}));
        });
        expect(container.querySelector('.fake-simple')).not.toBeNull();
        expect(document.activeElement).toBe(option('simple'));
    });

    it('still switches when the browser refuses storage', () => {
        const refusing = {getItem: () => { throw new Error('denied'); }, setItem: () => { throw new Error('denied'); }};
        renderView(refusing);
        act(() => { option('simple').click(); });
        expect(container.querySelector('.fake-simple')).not.toBeNull();
    });

    it('renders the real full library by default when no component is given', () => {
        act(() => { ReactDOM.render(<LibraryView t={t} storage={memoryStorage()}/>, container); });
        expect(container.querySelector('.mock-full')).not.toBeNull();
    });
});
