/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The "Check / repair the Lunii" window and the update button, rendered in jsdom with the backend
 * services replaced by mocks: what is shown, what is asked first, and which request each button sends.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act} from 'react-dom/test-utils';

import {DeviceRepair} from './DeviceRepair';
import {DeviceUpdateButton} from './DeviceUpdateButton';
import {openDeviceRepair, needsRepairOffer, updateStatuses, isUpdateAvailable} from '../utils/deviceRepairEvents';

jest.mock('react-i18next', () => {
    const mockReact = require('react');
    const mockT = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;
    return {
        withTranslation: () => (Component) => (props) => mockReact.createElement(Component, {t: mockT, ...props})
    };
});
jest.mock('../services/deviceRepair');
jest.mock('../services/device');

const repair = require('../services/deviceRepair');
const device = require('../services/device');

const t = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;

const CLEAN = {supported: true, driver: 'fs', indexedPacks: 2, problems: 0, orphans: [], missingContent: [], interruptedUpdates: [], pendingIndex: null};

let container;

beforeEach(() => {
    jest.resetAllMocks();
    container = document.createElement('div');
    document.body.appendChild(container);
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
    container = null;
});

const flush = () => act(() => new Promise(resolve => setTimeout(resolve, 0)));
const button = (text) => Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim() === text);
const startsWith = (text) => Array.from(document.querySelectorAll('button')).find(b => b.textContent.trim().startsWith(text));

function render(props) {
    act(() => {
        ReactDOM.render(<DeviceRepair t={t} driver="fs" {...props}/>, container);
    });
}

async function open(diagnosis, props) {
    repair.fetchDiagnosis.mockResolvedValue(diagnosis);
    render(props);
    act(() => { startsWith('deviceRepair.button').click(); });
    await flush();
}

describe('DeviceRepair', () => {

    it('checks the device when opened, and says when all is well', async () => {
        await open(CLEAN);
        expect(repair.fetchDiagnosis).toHaveBeenCalledTimes(1);
        expect(document.getElementById('modal-device-repair')).not.toBeNull();
        expect(document.body.textContent).toContain('deviceRepair.noProblem {"count":2}');
    });

    it('on a 1.x Lunii, says it is not supported and asks the backend nothing', async () => {
        render({driver: 'raw'});
        act(() => { startsWith('deviceRepair.button').click(); });
        await flush();
        expect(repair.fetchDiagnosis).not.toHaveBeenCalled();
        expect(document.body.textContent).toContain('deviceRepair.unsupported');
        expect(button('deviceRepair.integrity.start')).toBeUndefined();
    });

    it('discards a safe pending index only after confirmation, then checks again', async () => {
        await open({...CLEAN, problems: 1, pendingIndex: {kind: 'IDENTICAL', safeToDiscard: true, canAdopt: true, fingerprint: 'fp', added: [], removed: []}});
        repair.resolvePendingIndex.mockResolvedValue({success: true});
        act(() => { button('deviceRepair.pendingIndex.discard').click(); });
        expect(repair.resolvePendingIndex).not.toHaveBeenCalled();
        expect(document.body.textContent).toContain('deviceRepair.pendingIndex.confirmDiscard');
        repair.fetchDiagnosis.mockResolvedValue(CLEAN);
        act(() => { button('deviceRepair.confirm').click(); });
        await flush();
        expect(repair.resolvePendingIndex).toHaveBeenCalledWith('discard', 'fp');
        expect(repair.fetchDiagnosis).toHaveBeenCalledTimes(2);
        expect(document.body.textContent).toContain('deviceRepair.done');
    });

    it('offers the choice between the two indexes, with what each implies', async () => {
        const onChanged = jest.fn();
        await open({...CLEAN, problems: 1, pendingIndex: {kind: 'REMOVE', safeToDiscard: false, canAdopt: true, fingerprint: 'fp2', added: [], removed: ['u1']}}, {onChanged});
        expect(document.body.textContent).toContain('deviceRepair.pendingIndex.keepMeans.REMOVE');
        expect(document.body.textContent).toContain('deviceRepair.pendingIndex.adoptMeans.REMOVE');
        repair.resolvePendingIndex.mockResolvedValue({success: true});
        act(() => { button('deviceRepair.pendingIndex.adopt').click(); });
        act(() => { button('deviceRepair.confirm').click(); });
        await flush();
        expect(repair.resolvePendingIndex).toHaveBeenCalledWith('adopt', 'fp2');
        expect(onChanged).toHaveBeenCalled();
    });

    it('a cancelled confirmation sends nothing', async () => {
        await open({...CLEAN, problems: 1, interruptedUpdates: [{folder: 'AABBCCDD', state: 'SWAP_INTERRUPTED', leftovers: []}]});
        act(() => { button('deviceRepair.interrupted.actions.SWAP_INTERRUPTED').click(); });
        act(() => { button('dialogs.shared.no').click(); });
        expect(repair.resolveInterruptedUpdate).not.toHaveBeenCalled();
        repair.resolveInterruptedUpdate.mockResolvedValue({success: true});
        act(() => { button('deviceRepair.interrupted.actions.SWAP_INTERRUPTED').click(); });
        act(() => { button('deviceRepair.confirm').click(); });
        await flush();
        expect(repair.resolveInterruptedUpdate).toHaveBeenCalledWith('AABBCCDD');
    });

    it('removes only the orphan folders selected, and removes an entry without content from the index', async () => {
        await open({...CLEAN, problems: 3,
            orphans: [{folder: '0A0B0C0D', sizeInBytes: 2048, complete: false}, {folder: '0E0E0E0E', sizeInBytes: 10, complete: true}],
            missingContent: [{uuid: 'u-miss', folder: 'FFFF0000', folderPresent: false}]});
        const remove = startsWith('deviceRepair.orphans.remove');
        expect(remove.disabled).toBe(true);
        const box = document.querySelector('.device-repair-list input[type=checkbox]');
        act(() => { box.click(); });
        repair.removeOrphanFolders.mockResolvedValue({success: true});
        act(() => { startsWith('deviceRepair.orphans.remove').click(); });
        act(() => { button('deviceRepair.confirm').click(); });
        await flush();
        expect(repair.removeOrphanFolders).toHaveBeenCalledWith(['0A0B0C0D']);

        device.removeFromDevice.mockResolvedValue({success: true});
        act(() => { button('deviceRepair.missing.remove').click(); });
        act(() => { button('deviceRepair.confirm').click(); });
        await flush();
        expect(device.removeFromDevice).toHaveBeenCalledWith('u-miss');
    });

    it('shows a refused repair', async () => {
        await open({...CLEAN, problems: 1, pendingIndex: {kind: 'TORN', safeToDiscard: true, canAdopt: false, fingerprint: 'fp', added: [], removed: []}});
        repair.resolvePendingIndex.mockRejectedValue(new Error('changed'));
        act(() => { button('deviceRepair.pendingIndex.discard').click(); });
        act(() => { button('deviceRepair.confirm').click(); });
        await flush();
        expect(document.body.textContent).toContain('deviceRepair.failed {"message":"changed"}');
    });

    it('runs the integrity check, and offers to send a damaged pack again only when the library holds it', async () => {
        const onResend = jest.fn();
        await open(CLEAN, {libraryUuids: ['u-lib'], onResend});
        repair.startIntegrityCheck.mockResolvedValue({checkId: 'c1'});
        repair.fetchIntegrityCheck.mockResolvedValue({done: true, success: true, checked: 10, total: 10, packs: [
            {uuid: 'u-ok', ok: true, problems: []},
            {uuid: 'u-lib', ok: false, title: 'Bambi', problems: [{code: 'ASSET_MISSING', detail: 'sf/000/00000001'}]},
            {uuid: 'u-off', ok: false, official: true, problems: [{code: 'BOOT_FILE_INVALID', detail: 'bt'}]},
            {uuid: 'u-gone', ok: false, problems: [{code: 'ASSET_EMPTY', detail: 'rf/000/1'}]}
        ]});
        act(() => { button('deviceRepair.integrity.start').click(); });
        await flush();
        await flush();
        expect(repair.fetchIntegrityCheck).toHaveBeenCalledWith('c1');
        const text = document.body.textContent;
        expect(text).toContain('deviceRepair.integrity.damaged {"count":3,"total":4}');
        expect(text).toContain('deviceRepair.integrity.codes.ASSET_MISSING');
        expect(text).toContain('sf/000/00000001');
        expect(text).toContain('deviceRepair.integrity.official');
        expect(text).toContain('deviceRepair.integrity.notInLibrary');
        const resend = Array.from(document.querySelectorAll('button')).filter(b => b.textContent.trim() === 'deviceRepair.integrity.resend');
        expect(resend).toHaveLength(1);
        act(() => { resend[0].click(); });
        expect(onResend).toHaveBeenCalledWith('u-lib');
    });

    it('opens from a failure toast, through the shared event', async () => {
        repair.fetchDiagnosis.mockResolvedValue(CLEAN);
        render({});
        expect(document.getElementById('modal-device-repair')).toBeNull();
        act(() => { expect(openDeviceRepair()).toBe(true); });
        await flush();
        expect(document.getElementById('modal-device-repair')).not.toBeNull();
    });
});

describe('repair offer and update statuses', () => {

    it('offers the repair for the failures the check resolves only', () => {
        expect(needsRepairOffer('CONTENT_FOLDER_EXISTS')).toBe(true);
        expect(needsRepairOffer('PACK_INDEX_PENDING')).toBe(true);
        expect(needsRepairOffer('NOT_ENOUGH_SPACE')).toBe(false);
        expect(needsRepairOffer(undefined)).toBe(false);
    });

    it('indexes statuses by lower-case uuid', () => {
        const statuses = updateStatuses({packs: [{uuid: 'AB-CD', status: 'UPDATE_AVAILABLE'}, {uuid: 'x', status: 'UNKNOWN'}]});
        expect(isUpdateAvailable(statuses, 'ab-cd')).toBe(true);
        expect(isUpdateAvailable(statuses, 'X')).toBe(false);
        expect(isUpdateAvailable(updateStatuses(null), 'x')).toBe(false);
    });
});

describe('DeviceUpdateButton', () => {

    function renderButton(props) {
        act(() => {
            ReactDOM.render(<DeviceUpdateButton t={t} {...props}/>, container);
        });
    }

    it('shows nothing unless an update is available', () => {
        renderButton({status: {status: 'UP_TO_DATE'}, onUpdate: jest.fn()});
        expect(container.textContent).toBe('');
        renderButton({status: undefined, onUpdate: jest.fn()});
        expect(container.textContent).toBe('');
    });

    it('shows the badge and the button, which starts the update', () => {
        const onUpdate = jest.fn();
        renderButton({status: {status: 'UPDATE_AVAILABLE', basis: 'content'}, onUpdate});
        expect(container.querySelector('.device-update-badge').textContent).toBe('deviceUpdate.available');
        act(() => { container.querySelector('button').click(); });
        expect(onUpdate).toHaveBeenCalled();
    });

    it('badge only, or button only, and busy while updating', () => {
        renderButton({status: {status: 'UPDATE_AVAILABLE', basis: 'version', deviceVersion: 1, libraryVersion: 2}, onUpdate: jest.fn(), badgeOnly: true});
        expect(container.querySelector('button')).toBeNull();
        expect(container.querySelector('.device-update-badge').title).toContain('deviceUpdate.basis.version');
        renderButton({status: {status: 'UPDATE_AVAILABLE'}, onUpdate: jest.fn(), buttonOnly: true, busy: true});
        expect(container.querySelector('.device-update-badge')).toBeNull();
        expect(container.querySelector('button').disabled).toBe(true);
        expect(container.querySelector('button').textContent).toBe('deviceUpdate.updating');
    });
});
