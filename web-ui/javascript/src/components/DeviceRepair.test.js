/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The "Check / repair the Lunii" window and the update button, rendered in jsdom with the backend
 * services replaced by mocks: what is shown, what is asked first, and which request each button sends.
 */

import fs from 'fs';
import path from 'path';
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

const CLEAN = {supported: true, driver: 'fs', indexedPacks: 2, problems: 0, orphans: [], missingContent: [], interruptedUpdates: [], pendingIndex: null, deviceToken: 'tok'};

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
        expect(repair.resolvePendingIndex).toHaveBeenCalledWith('discard', 'fp', 'tok');
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
        expect(repair.resolvePendingIndex).toHaveBeenCalledWith('adopt', 'fp2', 'tok');
        expect(onChanged).toHaveBeenCalled();
    });

    it('adopting an index that drops packs names them before anything is done', async () => {
        await open({...CLEAN, problems: 1, pendingIndex: {kind: 'REMOVE', safeToDiscard: false, canAdopt: true, fingerprint: 'fp3', added: [], removed: ['U1', 'u2']}},
            {titles: {u1: 'Bambi'}});
        act(() => { button('deviceRepair.pendingIndex.adopt').click(); });
        expect(repair.resolvePendingIndex).not.toHaveBeenCalled();
        expect(document.body.textContent).toContain('deviceRepair.pendingIndex.confirmAdoptRemoving {"titles":"Bambi, u2"}');
    });

    it('without any installed index, only adopting the pending one is offered', async () => {
        await open({...CLEAN, problems: 1, indexMissing: true, pendingIndex: {kind: 'ADD', safeToDiscard: false, canAdopt: true, fingerprint: 'fpm',
            currentCount: -1, pendingCount: 2, added: ['a', 'b'], removed: []}});
        expect(document.body.textContent).toContain('deviceRepair.pendingIndex.indexMissing');
        expect(button('deviceRepair.pendingIndex.keep')).toBeUndefined();
        expect(button('deviceRepair.pendingIndex.discard')).toBeUndefined();
        expect(button('deviceRepair.pendingIndex.adopt')).toBeDefined();
        act(() => { button('deviceRepair.pendingIndex.adopt').click(); });
        expect(document.body.textContent).toContain('deviceRepair.pendingIndex.confirmAdoptIndexMissing');
    });

    it('without any installed index and an unusable pending one, nothing is offered', async () => {
        await open({...CLEAN, problems: 1, indexMissing: true,
            orphans: [{folder: 'B1B2B3B4', sizeInBytes: 10, complete: true}], missingContent: [{uuid: 'm1'}],
            interruptedUpdates: [{folder: 'C1C2C3C4', state: 'ROLL_BACK'}], pendingIndex: {kind: 'TORN', safeToDiscard: false, canAdopt: false, fingerprint: 'fpt', unlistedCompleteFolders: ['B1B2B3B4', 'C1C2C3C4'],
            currentCount: -1, pendingCount: 0, added: [], removed: []}});
        expect(button('deviceRepair.pendingIndex.adopt')).toBeUndefined();
        expect(button('deviceRepair.pendingIndex.keep')).toBeUndefined();
        expect(button('deviceRepair.pendingIndex.discard')).toBeUndefined();
        expect(document.body.textContent).toContain('deviceRepair.pendingIndex.indexMissingCannotAdopt');
        expect(document.body.textContent).toContain('deviceRepair.pendingIndex.indexMissingUnlisted {"folders":"B1B2B3B4, C1C2C3C4"}');
        expect(document.querySelector('input[type=checkbox]')).toBeNull();
        expect(button('deviceRepair.orphans.remove {"count":0}')).toBeUndefined();
        expect(document.body.textContent).not.toContain('deviceRepair.missing.title');
        expect(document.body.textContent).not.toContain('deviceRepair.interrupted.title');
    });

    it('an index that cannot be adopted offers only to keep the current one, and says why', async () => {
        await open({...CLEAN, problems: 1, pendingIndex: {kind: 'OTHER', safeToDiscard: false, canAdopt: false, fingerprint: 'fp4',
            added: ['z'], removed: ['a'], incomplete: ['z']}});
        expect(button('deviceRepair.pendingIndex.adopt')).toBeUndefined();
        expect(button('deviceRepair.pendingIndex.keep')).toBeDefined();
        expect(document.body.textContent).toContain('deviceRepair.pendingIndex.cannotAdopt {"count":1}');
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
        expect(repair.resolveInterruptedUpdate).toHaveBeenCalledWith('AABBCCDD', 'SWAP_INTERRUPTED', 'tok');
    });

    it('offers no repair for leftovers STUdio cannot sort out safely', async () => {
        await open({...CLEAN, problems: 1, interruptedUpdates: [{folder: 'AABBCCDD', state: 'CONFLICT', leftovers: []}]});
        expect(document.body.textContent).toContain('deviceRepair.interrupted.states.CONFLICT');
        expect(document.querySelectorAll('.device-repair-section button')).toHaveLength(1); // the integrity check only
    });

    it('an orphan the pending index names cannot be selected', async () => {
        await open({...CLEAN, problems: 2, orphans: [{folder: '0A0B0C0D', sizeInBytes: 10, complete: true, referencedByPendingIndex: true}]});
        const box = document.querySelector('.device-repair-list input[type=checkbox]');
        expect(box.disabled).toBe(true);
        expect(document.body.textContent).toContain('deviceRepair.orphans.pending');
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
        expect(repair.removeOrphanFolders).toHaveBeenCalledWith(['0A0B0C0D'], 'tok');

        repair.removeMissingContent.mockResolvedValue({success: true});
        act(() => { button('deviceRepair.missing.remove').click(); });
        act(() => { button('deviceRepair.confirm').click(); });
        await flush();
        // Through the repair route, which checks the content is still missing; not a plain removal
        expect(repair.removeMissingContent).toHaveBeenCalledWith('u-miss', 'tok');
        expect(device.removeFromDevice).not.toHaveBeenCalled();
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
        await open(CLEAN, {libraryUuids: ['u-lib', 'u-lib2'], onResend,
            updateStatusOf: (uuid) => uuid === 'u-lib' ? {deviceVersion: 1, libraryVersion: 3} : undefined});
        repair.startIntegrityCheck.mockResolvedValue({checkId: 'c1'});
        repair.fetchIntegrityCheck.mockResolvedValue({done: true, success: true, checked: 10, total: 10, packs: [
            {uuid: 'u-ok', ok: true, problems: []},
            {uuid: 'u-lib', ok: false, title: 'Bambi', problems: [{code: 'ASSET_MISSING', detail: 'sf/000/00000001'}]},
            {uuid: 'u-off', ok: false, official: true, problems: [{code: 'BOOT_FILE_INVALID', detail: 'bt'}]},
            {uuid: 'u-gone', ok: false, problems: [{code: 'ASSET_EMPTY', detail: 'rf/000/1'}]},
            {uuid: 'u-lib2', ok: false, problems: [{code: 'FOLDER_MISSING', detail: 'AAAA0000'}]}
        ]});
        act(() => { button('deviceRepair.integrity.start').click(); });
        await flush();
        await flush();
        expect(repair.fetchIntegrityCheck).toHaveBeenCalledWith('c1');
        const text = document.body.textContent;
        expect(text).toContain('deviceRepair.integrity.damaged {"count":4,"total":5}');
        // A missing folder cannot be replaced in place: no resend, the way out instead
        expect(text).toContain('deviceRepair.integrity.folderMissing');
        expect(text).toContain('deviceRepair.integrity.codes.ASSET_MISSING');
        expect(text).toContain('sf/000/00000001');
        expect(text).toContain('deviceRepair.integrity.official');
        expect(text).toContain('deviceRepair.integrity.notInLibrary');
        const resend = Array.from(document.querySelectorAll('button')).filter(b => b.textContent.trim() === 'deviceRepair.integrity.resend');
        expect(resend).toHaveLength(1);
        act(() => { resend[0].click(); });
        // Asked first, with the version replaced and the one installed
        expect(onResend).not.toHaveBeenCalled();
        expect(document.body.textContent).toContain('deviceRepair.integrity.confirmResend {"title":"Bambi","device":1,"library":3}');
        onResend.mockReturnValue(Promise.resolve());
        repair.fetchDiagnosis.mockResolvedValue(CLEAN);
        act(() => { button('deviceRepair.confirm').click(); });
        await flush();
        expect(onResend).toHaveBeenCalledWith('u-lib');
        expect(document.body.textContent).toContain('deviceRepair.integrity.resendStarted');
    });

    it('a progress read answering after the window is closed does not leave the check "running" on reopening', async () => {
        await open(CLEAN);
        repair.startIntegrityCheck.mockResolvedValue({checkId: 'c1'});
        let answer;
        repair.fetchIntegrityCheck.mockImplementation(() => new Promise(resolve => { answer = resolve; }));
        act(() => { button('deviceRepair.integrity.start').click(); });
        await flush();
        expect(repair.fetchIntegrityCheck).toHaveBeenCalledTimes(1);
        expect(button('deviceRepair.integrity.running')).toBeDefined();
        // Closed while the read is in flight, then the read answers
        act(() => { button('dialogs.shared.close').click(); });
        await act(async () => { answer({done: false, checked: 3, total: 10}); });
        await flush();
        expect(document.getElementById('modal-device-repair')).toBeNull();
        // Reopened: a check can be started again
        act(() => { startsWith('deviceRepair.button').click(); });
        await flush();
        const start = button('deviceRepair.integrity.start');
        expect(start).toBeDefined();
        expect(start.disabled).toBe(false);
        expect(button('deviceRepair.integrity.running')).toBeUndefined();
    });

    it('a check whose start answers after the window is closed does not begin polling', async () => {
        await open(CLEAN);
        jest.useFakeTimers();
        try {
            let started;
            repair.startIntegrityCheck.mockImplementation(() => new Promise(resolve => { started = resolve; }));
            repair.fetchIntegrityCheck.mockResolvedValue({done: false, checked: 1, total: 10});
            act(() => { button('deviceRepair.integrity.start').click(); });
            act(() => { button('dialogs.shared.close').click(); });
            await act(async () => { started({checkId: 'c1'}); });
            act(() => { jest.advanceTimersByTime(5000); });
            expect(repair.fetchIntegrityCheck).not.toHaveBeenCalled();
        } finally {
            jest.useRealTimers();
        }
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

    it('shows the badge and the button, which asks first, then starts the update', () => {
        const onUpdate = jest.fn();
        renderButton({status: {status: 'UPDATE_AVAILABLE', basis: 'version', deviceVersion: 1, libraryVersion: 2}, onUpdate});
        expect(container.querySelector('.device-update-badge').textContent).toBe('deviceUpdate.available');
        act(() => { container.querySelector('button').click(); });
        expect(onUpdate).not.toHaveBeenCalled();
        expect(document.body.textContent).toContain('deviceUpdate.confirm.text {"device":1,"library":2}');
        act(() => { button('deviceUpdate.confirm.yes').click(); });
        expect(onUpdate).toHaveBeenCalledTimes(1);
        expect(document.getElementById('modal-device-update-confirm')).toBeNull();
    });

    it('a cancelled update confirmation starts nothing', () => {
        const onUpdate = jest.fn();
        renderButton({status: {status: 'UPDATE_AVAILABLE', basis: 'content'}, onUpdate});
        act(() => { container.querySelector('button').click(); });
        expect(document.body.textContent).toContain('deviceUpdate.confirm.textNoVersion');
        act(() => { button('dialogs.shared.no').click(); });
        expect(onUpdate).not.toHaveBeenCalled();
        expect(document.getElementById('modal-device-update-confirm')).toBeNull();
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

describe('texts of the device check', () => {

    const locales = path.join(__dirname, '..', '..', '..', 'src', 'main', 'resources', 'webroot', 'locales');
    const read = (lang) => JSON.parse(fs.readFileSync(path.join(locales, lang, 'translation.json'), 'utf8'));
    const keys = (obj, prefix = '') => Object.keys(obj).reduce((all, k) => typeof obj[k] === 'object'
        ? all.concat(keys(obj[k], prefix + k + '.')) : all.concat(prefix + k), []);
    const STATES = ['COPY_INTERRUPTED', 'PROMOTE_NEW', 'SWAP_INTERRUPTED', 'CLEANUP_INTERRUPTED', 'RESTORE_PREVIOUS',
        'NEW_REPLACES_DAMAGED', 'PREVIOUS_REPLACES_DAMAGED', 'NO_COMPLETE_COPY',
        'PREVIOUS_ONLY', 'NEW_ONLY', 'ABANDONED_COPY', 'CONFLICT', 'UNREFERENCED'];

    it('has the same keys in French and in English', () => {
        const fr = read('fr');
        const en = read('en');
        expect(keys(fr.deviceRepair).sort()).toEqual(keys(en.deviceRepair).sort());
        expect(keys(fr.deviceUpdate).sort()).toEqual(keys(en.deviceUpdate).sort());
    });

    it('describes every state of an interrupted update, and offers an action for every state but CONFLICT', () => {
        ['fr', 'en'].forEach(lang => {
            const interrupted = read(lang).deviceRepair.interrupted;
            STATES.forEach(state => {
                expect(interrupted.states[state]).toBeTruthy();
                if (state === 'CONFLICT') {
                    expect(interrupted.actions[state]).toBeUndefined();
                } else {
                    expect(interrupted.actions[state]).toBeTruthy();
                    expect(interrupted.confirm[state]).toBeTruthy();
                }
            });
        });
    });

    it('no longer claims the story is intact, nor advises removing the folders an index drops', () => {
        const fr = read('fr').deviceRepair;
        expect(fr.interrupted.states.COPY_INTERRUPTED).not.toMatch(/intacte/);
        // A real conflict says how to get out of it; a damaged folder is only ever moved aside
        expect(fr.interrupted.states.CONFLICT).toMatch(/hors de \.content/);
        expect(fr.interrupted.confirm.NEW_REPLACES_DAMAGED).toMatch(/n'est pas supprimé/);
        expect(fr.interrupted.confirm.PREVIOUS_REPLACES_DAMAGED).toMatch(/n'est pas supprimé/);
        expect(fr.interrupted.actions.UNREFERENCED).not.toMatch(/Supprimer/);
        expect(fr.pendingIndex.keepMeans.ADD).not.toMatch(/supprimer ensuite/);
        expect(fr.pendingIndex.adoptMeans.REMOVE).not.toMatch(/supprimer ensuite/);
        expect(fr.pendingIndex.adoptMeans.REMOVE).toMatch(/\.pi\.bak/);
    });
});
