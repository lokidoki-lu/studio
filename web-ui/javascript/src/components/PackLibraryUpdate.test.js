/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * "Update on my Lunii" in both library modes, and the "Check / repair" button in both device panels.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act} from 'react-dom/test-utils';

import {PackLibrarySimple} from './PackLibrarySimple';
import {PackLibrary} from './PackLibrary';

jest.mock('react-i18next', () => {
    const mockReact = require('react');
    const mockT = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;
    return {
        withTranslation: () => (Component) => (props) => mockReact.createElement(Component, {t: mockT, ...props})
    };
});
jest.mock('./YoutubePack', () => () => null);
jest.mock('./LibraryPurge', () => () => null);
jest.mock('../services/deviceRepair');

const repair = require('../services/deviceRepair');

const t = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;

const LIBRARY = {
    metadata: {path: '/library'},
    packs: [
        {uuid: 'u-yt', packs: [{uuid: 'u-yt', path: 'yt.fs', format: 'fs', title: 'Ma chaîne', size: 5e6, version: 1, timestamp: 2}]},
        {uuid: 'u-same', packs: [{uuid: 'u-same', path: 'same.fs', format: 'fs', title: 'Pareil', size: 1e6, version: 1, timestamp: 1}]},
        {uuid: 'u-off', packs: [{uuid: 'u-off', path: 'off.fs', format: 'fs', title: 'Officiel', size: 1e6, version: 2, timestamp: 1, official: true}]}
    ]
};

const DEVICE = {
    metadata: {uuid: 'dev', driver: 'fs', storage: {size: 4e9, taken: 1e9, free: 3e9}},
    packs: [
        {uuid: 'u-yt', title: 'Ma chaîne', version: 1, format: 'fs'},
        {uuid: 'u-same', title: 'Pareil', version: 1, format: 'fs'},
        {uuid: 'u-off', title: 'Officiel', version: 1, format: 'fs', official: true}
    ]
};

const UPDATES = {supported: true, packs: [
    {uuid: 'u-yt', status: 'UPDATE_AVAILABLE', basis: 'content', deviceVersion: 1, libraryVersion: 1},
    {uuid: 'u-same', status: 'UP_TO_DATE', basis: 'content'},
    {uuid: 'u-off', status: 'OFFICIAL'}
]};

function actions() {
    return {
        addFromLibrary: jest.fn(),
        updateOnDevice: jest.fn(),
        refreshDevice: jest.fn(),
        removeFromDevice: jest.fn(() => Promise.resolve()),
        reorderOnDevice: jest.fn(),
        addToLibrary: jest.fn(),
        loadLibraryPackInEditor: jest.fn(),
        convertPackInLibrary: jest.fn(() => Promise.resolve('converted')),
        removeFromLibrary: jest.fn(() => Promise.resolve()),
        removeManyFromLibrary: jest.fn(() => Promise.resolve()),
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
    jest.resetAllMocks();
    repair.fetchDeviceUpdates.mockResolvedValue(UPDATES);
    container = document.createElement('div');
    document.body.appendChild(container);
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
    container = null;
});

const flush = () => act(() => new Promise(resolve => setTimeout(resolve, 0)));
const buttons = (root, text) => Array.from(root.querySelectorAll('button')).filter(b => b.textContent.trim() === text);

async function render(Component, props) {
    act(() => {
        ReactDOM.render(<Component t={t} settings={{allowEnriched: false}} {...props}/>, container);
    });
    await flush();
}

describe('in-place update', () => {

    it('simplified library: a badge and a button for the pack the library holds in another version, none otherwise', async () => {
        const props = actions();
        await render(PackLibrarySimple, {device: DEVICE, library: LIBRARY, ...props});
        expect(repair.fetchDeviceUpdates).toHaveBeenCalled();
        const card = (title) => Array.from(container.querySelectorAll('.simple-card')).find(c => c.querySelector('.simple-title').textContent === title);
        expect(card('Ma chaîne').querySelector('.device-update-badge')).not.toBeNull();
        expect(card('Pareil').querySelector('.device-update-badge')).toBeNull();
        expect(card('Officiel').querySelector('.device-update-badge')).toBeNull();
        // The device list shows the badge too
        expect(container.querySelector('.simple-device-list .device-update-badge')).not.toBeNull();

        const update = buttons(card('Ma chaîne'), 'deviceUpdate.update');
        expect(update).toHaveLength(1);
        act(() => { update[0].click(); });
        await flush();
        expect(props.updateOnDevice).toHaveBeenCalledWith('u-yt', 'yt.fs', 'fs', 'fs', expect.anything());
        expect(props.addFromLibrary).not.toHaveBeenCalled();
    });

    it('full library: the device tile offers the update, through the same decision as sending', async () => {
        const props = actions();
        await render(PackLibrary, {device: DEVICE, library: LIBRARY, ...props});
        const tiles = Array.from(container.querySelectorAll('.plugged-device .pack-tile'));
        expect(tiles).toHaveLength(3);
        const update = buttons(tiles[0], 'deviceUpdate.update');
        expect(update).toHaveLength(1);
        expect(buttons(tiles[1], 'deviceUpdate.update')).toHaveLength(0);
        act(() => { update[0].click(); });
        await flush();
        expect(props.updateOnDevice).toHaveBeenCalledWith('u-yt', 'yt.fs', 'fs', 'fs', expect.anything());
    });

    it('a 1.x Lunii is not asked for updates', async () => {
        const props = actions();
        await render(PackLibrarySimple, {device: {...DEVICE, metadata: {...DEVICE.metadata, driver: 'raw'}}, library: LIBRARY, ...props});
        expect(repair.fetchDeviceUpdates).not.toHaveBeenCalled();
        expect(container.querySelector('.device-update-badge')).toBeNull();
    });

    it('both device panels have the "Check / repair" button', async () => {
        await render(PackLibrarySimple, {device: DEVICE, library: LIBRARY, ...actions()});
        expect(buttons(container, 'deviceRepair.button')).toHaveLength(1);
        ReactDOM.unmountComponentAtNode(container);
        await render(PackLibrary, {device: DEVICE, library: LIBRARY, ...actions()});
        expect(buttons(container, 'deviceRepair.button')).toHaveLength(1);
    });
});
