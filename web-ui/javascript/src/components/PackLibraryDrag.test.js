/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Dragging over the Lunii zone of the full library: leaving the zone restores the device order only for
 * a reordering in progress. A library pack dragged over it and out again leaves the device list as it is.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act, Simulate} from 'react-dom/test-utils';

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
        {uuid: 'u-lib', packs: [{uuid: 'u-lib', path: 'lib.zip', format: 'archive', title: 'Bibliothèque', size: 1e6, version: 1, timestamp: 1}]}
    ]
};

const pack = (uuid) => ({uuid, title: uuid, version: 1, format: 'fs'});
const deviceWith = (...uuids) => ({
    metadata: {uuid: 'dev', driver: 'fs', storage: {size: 4e9, taken: 1e9, free: 3e9}},
    packs: uuids.map(pack)
});

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
    jest.spyOn(console, 'log').mockImplementation(() => {});
    repair.fetchDeviceUpdates.mockResolvedValue({supported: true, packs: []});
    container = document.createElement('div');
    document.body.appendChild(container);
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
    container = null;
    console.log.mockRestore();
});

const flush = () => act(() => new Promise(resolve => setTimeout(resolve, 0)));

async function render(props) {
    act(() => {
        ReactDOM.render(<PackLibrary t={t} settings={{allowEnriched: false}} library={LIBRARY} {...props}/>, container);
    });
    await flush();
}

const deviceOrder = () => Array.from(container.querySelectorAll('.device-dropzone .pack-tile .pack-title span[title]')).map(s => s.getAttribute('title'));
const deviceTiles = () => Array.from(container.querySelectorAll('.device-dropzone .pack-tile'));
const zone = () => container.querySelector('.device-dropzone');
const dataTransfer = () => ({setData: jest.fn(), getData: jest.fn(() => ''), setDragImage: jest.fn()});
// jsdom lays nothing out: the zone is a 0x0 rectangle, so any pointer position is outside it
const leaveZone = () => act(() => { Simulate.dragLeave(zone(), {clientX: 50, clientY: 50}); });

function dragLibraryPackOverDeviceAndOut() {
    const tile = container.querySelector('.local-library .pack-tile');
    act(() => { Simulate.dragStart(tile, {dataTransfer: dataTransfer()}); });
    leaveZone();
    act(() => { Simulate.dragEnd(tile, {dataTransfer: dataTransfer()}); });
}

function reorder(from, to) {
    act(() => { Simulate.dragStart(deviceTiles()[from], {dataTransfer: dataTransfer()}); });
    act(() => { Simulate.dragEnter(deviceTiles()[to], {dataTransfer: dataTransfer()}); });
    act(() => { Simulate.dragEnd(deviceTiles()[to], {dataTransfer: dataTransfer()}); });
}

describe('device zone drag leave', () => {

    it('a library pack dragged over the Lunii and out, before any reordering, changes nothing and throws nothing', async () => {
        await render({device: deviceWith('A', 'B', 'C'), ...actions()});
        expect(() => dragLibraryPackOverDeviceAndOut()).not.toThrow();
        expect(deviceOrder()).toEqual(['A', 'B', 'C']);
    });

    it('after a reordering and a send, a library pack dragged over and out does not bring back the old list', async () => {
        const props = actions();
        await render({device: deviceWith('A', 'B', 'C'), ...props});
        reorder(0, 2);
        expect(props.reorderOnDevice).toHaveBeenCalledWith(['B', 'C', 'A']);
        // The device reports its new list, with the pack just sent
        await render({device: deviceWith('B', 'C', 'A', 'D'), ...props});

        dragLibraryPackOverDeviceAndOut();
        expect(deviceOrder()).toEqual(['B', 'C', 'A', 'D']);

        // A later reordering starts from the real list, and sends every pack
        props.reorderOnDevice.mockClear();
        reorder(1, 0);
        expect(props.reorderOnDevice).toHaveBeenCalledWith(['C', 'B', 'A', 'D']);
    });

    it('a reordering in progress goes back to its starting order when the pointer leaves the zone', async () => {
        const props = actions();
        await render({device: deviceWith('A', 'B', 'C'), ...props});
        act(() => { Simulate.dragStart(deviceTiles()[0], {dataTransfer: dataTransfer()}); });
        act(() => { Simulate.dragEnter(deviceTiles()[2], {dataTransfer: dataTransfer()}); });
        expect(deviceOrder()).toEqual(['B', 'C', 'A']);
        leaveZone();
        expect(deviceOrder()).toEqual(['A', 'B', 'C']);
        act(() => { Simulate.dragEnd(deviceTiles()[0], {dataTransfer: dataTransfer()}); });
        expect(props.reorderOnDevice).not.toHaveBeenCalled();
    });
});
