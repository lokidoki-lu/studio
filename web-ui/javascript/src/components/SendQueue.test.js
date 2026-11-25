/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Selecting several packs and sending them in a row, in both library views, with the Redux actions
 * replaced by simulations: the space check, the queue through the library's own sendToDevice and its
 * dialogs (asked once for the whole selection), the progress and the summary.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act} from 'react-dom/test-utils';

import {PackLibrarySimple} from './PackLibrarySimple';
import {PackLibrary} from './PackLibrary';
import {LOCAL_STORAGE_ALLOW_ENRICHED_BINARY_FORMAT} from '../utils/storage';
import {BUSY_RETRY_DELAY_MS} from '../utils/sendQueue';

jest.mock('react-i18next', () => {
    const mockReact = require('react');
    const mockT = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;
    return {
        withTranslation: () => (Component) => (props) => mockReact.createElement(Component, {t: mockT, ...props})
    };
});
jest.mock('./YoutubePack', () => () => null);
jest.mock('./LibraryPurge', () => () => null);
jest.mock('./PackPreview', () => () => null);

const t = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;

const pack = (uuid, title, path, format, extra = {}) => ({uuid, packs: [{uuid, path, format, title, size: 1e6, version: 1, ...extra}]});

const LIBRARY = {
    metadata: {path: '/library'},
    packs: [
        pack('u-a', 'Alpha', 'a.zip', 'archive'),
        pack('u-b', 'Beta', 'u-b.converted_1', 'fs'),
        pack('u-off', 'Officiel', 'off.zip', 'archive', {official: true}),
        pack('u-on', 'Déjà là', 'on.zip', 'archive')
    ]
};

const device = (driver, free = 3e9) => ({
    metadata: {uuid: 'dev', driver, storage: {size: 4e9, taken: 4e9 - free, free}},
    packs: [{uuid: 'U-ON', title: 'Déjà là', version: 1, format: driver}]
});

/** Transfers that end on their own, a tick after they start, with some progress first. */
function actions({bytes = 1e6, transfer} = {}) {
    return {
        addFromLibrary: jest.fn(transfer || ((uuid, path, format, driver, context, options) => {
            setTimeout(() => {
                if (options) {
                    options.onProgress(0.5);
                    options.onSettled({status: 'sent'});
                }
            }, 0);
        })),
        removeFromDevice: jest.fn(() => Promise.resolve()),
        reorderOnDevice: jest.fn(),
        addToLibrary: jest.fn(),
        loadLibraryPackInEditor: jest.fn(),
        convertPackInLibrary: jest.fn((uuid) => Promise.resolve(uuid + '.converted_9')),
        removeFromLibrary: jest.fn(() => Promise.resolve()),
        removeManyFromLibrary: jest.fn(() => Promise.resolve()),
        uploadPackToLibrary: jest.fn(),
        uploadPacksToLibrary: jest.fn(),
        verifyConversion: jest.fn(() => Promise.resolve({verdict: 'UNKNOWN'})),
        estimateOnDevice: jest.fn((paths) => Promise.resolve({method: 'measured', packs: paths.map(path => ({path, bytes}))})),
        createPackInEditor: jest.fn(),
        loadSampleInEditor: jest.fn(),
        setAllowEnriched: jest.fn()
    };
}

let container;

beforeEach(() => {
    localStorage.clear();
    container = document.createElement('div');
    document.body.appendChild(container);
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
    container = null;
    jest.useRealTimers();
});

function render(Component, props) {
    act(() => {
        ReactDOM.render(<Component t={t} settings={{allowEnriched: false}} {...props}/>, container);
    });
}

const flush = async (times = 8) => {
    for (let i = 0; i < times; i++) {
        await act(() => new Promise(resolve => setTimeout(resolve, 0)));
    }
};
const card = (title) => Array.from(container.querySelectorAll('.simple-card')).find(c => c.querySelector('.simple-title').textContent === title);
const box = (title) => container.querySelector(`input[aria-label="${t('sendQueue.select', {title}).replace(/"/g, '\\"')}"]`);
const button = (root, text) => Array.from(root.querySelectorAll('button')).find(b => b.textContent.trim() === text);
const bar = () => container.querySelector('.send-queue-bar');
const modal = (id) => document.getElementById('modal-' + id);

describe('sending a selection (simplified view)', () => {

    it('selects with boxes, shows the space needed and free, sends one pack after the other and sums up', async () => {
        const props = actions();
        render(PackLibrarySimple, {device: device('fs'), library: LIBRARY, ...props});
        ['Alpha', 'Beta', 'Officiel', 'Déjà là'].forEach(title => act(() => { box(title).click(); }));
        await flush();

        expect(bar().textContent).toContain('sendQueue.selection {"count":4}');
        // Only what would be sent is estimated: Alpha (to convert) and Beta (already converted)
        expect(props.estimateOnDevice).toHaveBeenLastCalledWith(['a.zip', 'u-b.converted_1'], 'fs');
        expect(bar().textContent).toContain('spaceEstimate.summary {"required":"libraryPurge.units.mb {\\"value\\":\\"2\\"}"');
        expect(card('Alpha').classList.contains('send-selected')).toBe(true);

        act(() => { button(bar(), 'sendQueue.send').click(); });
        await flush(20);

        // Alpha converted then transferred, Beta transferred as it is, one at a time
        expect(props.convertPackInLibrary).toHaveBeenCalledTimes(1);
        expect(props.convertPackInLibrary).toHaveBeenCalledWith('u-a', 'a.zip', 'fs', false, expect.anything());
        expect(props.addFromLibrary.mock.calls.map(c => [c[0], c[1]])).toEqual([['u-a', 'u-a.converted_9'], ['u-b', 'u-b.converted_1']]);

        const summary = modal('send-queue-summary');
        expect(summary).not.toBeNull();
        expect(summary.textContent).toContain('sendQueue.summary.counts {"sent":2,"skipped":2,"failed":0}');
        expect(summary.textContent).toContain('sendQueue.reasons.official');
        expect(summary.textContent).toContain('sendQueue.reasons.onDevice');
        // The selection is emptied
        act(() => { button(summary, 'dialogs.shared.close').click(); });
        expect(bar()).toBeNull();
    });

    it('shows the progress (pack n/N, its part) and lets the rest be cancelled', async () => {
        const settles = [];
        const props = actions({transfer: (uuid, path, format, driver, context, options) => settles.push(options)});
        render(PackLibrarySimple, {device: device('fs'), library: LIBRARY, ...props});
        act(() => { box('Beta').click(); });
        act(() => { box('Alpha').click(); });
        await flush();
        act(() => { button(bar(), 'sendQueue.send').click(); });
        await flush();

        // Alpha first (library order: Alpha, Beta), converted then transferring
        expect(settles).toHaveLength(1);
        act(() => { settles[0].onProgress(0.4); });
        const progress = container.querySelector('.send-queue-progress');
        expect(progress.textContent).toContain('sendQueue.progress {"current":1,"total":2,"title":"Alpha"}');
        expect(progress.textContent).toContain('sendQueue.phases.transferring 40 %');
        expect(progress.querySelector('[role="progressbar"]').getAttribute('aria-valuenow')).toBe('20');

        act(() => { button(progress, 'sendQueue.cancel').click(); });
        act(() => { settles[0].onSettled({status: 'sent'}); });
        await flush();
        expect(settles).toHaveLength(1);
        expect(modal('send-queue-summary').textContent).toContain('sendQueue.summary.counts {"sent":1,"skipped":1,"failed":0}');
        expect(modal('send-queue-summary').textContent).toContain('sendQueue.reasons.cancelled');
    });

    it('asks once whether to use the enriched format, for the whole selection', async () => {
        const props = actions();
        const library = {...LIBRARY, packs: [pack('u-a', 'Alpha', 'a.zip', 'archive'), pack('u-c', 'Gamma', 'c.zip', 'archive')]};
        render(PackLibrarySimple, {device: device('raw'), library, ...props});
        act(() => { box('Alpha').click(); });
        act(() => { box('Gamma').click(); });
        await flush();
        act(() => { button(bar(), 'sendQueue.send').click(); });
        await flush();

        const ask = modal('ask-allow-enriched');
        expect(ask).not.toBeNull();
        expect(ask.textContent).toContain('sendQueue.appliesToAll');
        act(() => { button(ask, 'dialogs.shared.yes').click(); });
        await flush(20);

        expect(props.setAllowEnriched).toHaveBeenCalledTimes(1);
        expect(modal('ask-allow-enriched')).toBeNull();
        expect(props.convertPackInLibrary.mock.calls.map(c => [c[0], c[3]])).toEqual([['u-a', true], ['u-c', true]]);
        expect(modal('send-queue-summary').textContent).toContain('"sent":2');
    });

    it('applies the answer about an unproven conversion to the rest of the selection', async () => {
        const props = actions();
        const both = (uuid, title) => ({uuid, packs: [
            {uuid, path: uuid + '.converted_1', format: 'fs', title, size: 1},
            {uuid, path: uuid + '.zip', format: 'archive', title, size: 1}
        ]});
        render(PackLibrarySimple, {device: device('fs'), library: {...LIBRARY, packs: [both('u-a', 'Alpha'), both('u-c', 'Gamma')]}, ...props});
        act(() => { box('Alpha').click(); });
        act(() => { box('Gamma').click(); });
        await flush();
        act(() => { button(bar(), 'sendQueue.send').click(); });
        await flush();

        const ask = modal('ask-confirm-conversion');
        expect(ask.textContent).toContain('sendQueue.appliesToAll');
        act(() => { button(ask, 'dialogs.library.askConfirmConversion.transfer').click(); });
        await flush(20);

        expect(props.verifyConversion).toHaveBeenCalledTimes(2);
        expect(props.convertPackInLibrary).not.toHaveBeenCalled();
        expect(props.addFromLibrary.mock.calls.map(c => c[1])).toEqual(['u-a.converted_1', 'u-c.converted_1']);
    });

    it('refuses a selection that does not fit, before converting anything, and says by how much', async () => {
        const props = actions({bytes: 2e9});
        render(PackLibrarySimple, {device: device('fs', 3e9), library: LIBRARY, ...props});
        act(() => { box('Alpha').click(); });
        act(() => { box('Beta').click(); });
        await flush();

        expect(bar().querySelector('.send-queue-warning').textContent).toContain('spaceEstimate.tooBig {"missing":"libraryPurge.units.gb {\\"value\\":\\"1.0\\"}"}');
        expect(button(bar(), 'sendQueue.send').disabled).toBe(true);
        expect(props.convertPackInLibrary).not.toHaveBeenCalled();
    });

    it('refuses a single pack that does not fit, with an explanation, instead of converting it', async () => {
        const props = actions({bytes: 5e9});
        render(PackLibrarySimple, {device: device('fs', 3e9), library: LIBRARY, ...props});
        act(() => { button(card('Alpha'), 'simpleLibrary.card.send').click(); });
        await flush();

        const refusal = modal('space-refusal');
        expect(refusal).not.toBeNull();
        expect(refusal.textContent).toContain('spaceEstimate.refusal.content');
        expect(refusal.textContent).toContain('spaceEstimate.refusal.explain');
        expect(props.convertPackInLibrary).not.toHaveBeenCalled();
        expect(props.addFromLibrary).not.toHaveBeenCalled();
        // The button can be used again
        expect(button(card('Alpha'), 'simpleLibrary.card.send').disabled).toBe(false);
    });

    it('sends a single pack that fits as before, and sends anyway when the space cannot be estimated', async () => {
        const props = actions();
        props.estimateOnDevice = jest.fn(() => Promise.reject(new Error('down')));
        render(PackLibrarySimple, {device: device('fs'), library: LIBRARY, ...props});
        act(() => { button(card('Beta'), 'simpleLibrary.card.send').click(); });
        await flush();
        expect(modal('space-refusal')).toBeNull();
        expect(props.addFromLibrary).toHaveBeenCalledWith('u-b', 'u-b.converted_1', 'fs', 'fs', expect.anything());
    });

    it('tries a transfer again when the device is busy for a moment', async () => {
        jest.useFakeTimers();
        let calls = 0;
        const props = actions({transfer: (uuid, path, format, driver, context, options) => {
            calls++;
            options.onSettled(calls === 1 ? {status: 'failed', reason: 'busy'} : {status: 'sent'});
        }});
        render(PackLibrarySimple, {device: device('fs'), library: LIBRARY, ...props});
        act(() => { box('Beta').click(); });
        const tick = async (ms) => {
            await act(async () => {
                jest.advanceTimersByTime(ms);
                for (let i = 0; i < 10; i++) {
                    await Promise.resolve();
                }
            });
        };
        await tick(0);
        act(() => { button(bar(), 'sendQueue.send').click(); });
        await tick(0);
        expect(calls).toBe(1);
        await tick(BUSY_RETRY_DELAY_MS);
        await tick(0);
        expect(calls).toBe(2);
        expect(modal('send-queue-summary').textContent).toContain('"sent":1');
    });
});

describe('sending a selection (full view)', () => {

    const tile = (title) => Array.from(container.querySelectorAll('.local-library .pack-tile')).find(el => el.textContent.indexOf(title) !== -1);

    it('selects with Ctrl-click, a range with Shift-click, or with the boxes, and sends from the bar', async () => {
        const props = actions();
        render(PackLibrary, {device: device('fs'), library: LIBRARY, ...props});

        act(() => { tile('Alpha').dispatchEvent(new MouseEvent('click', {bubbles: true, ctrlKey: true})); });
        expect(tile('Alpha').classList.contains('send-selected')).toBe(true);
        act(() => { tile('Officiel').dispatchEvent(new MouseEvent('click', {bubbles: true, shiftKey: true})); });
        expect(['Alpha', 'Beta', 'Officiel'].map(title => tile(title).classList.contains('send-selected'))).toEqual([true, true, true]);
        // A plain click selects nothing
        act(() => { tile('Déjà là').dispatchEvent(new MouseEvent('click', {bubbles: true})); });
        expect(tile('Déjà là').classList.contains('send-selected')).toBe(false);
        act(() => { box('Déjà là').click(); });
        await flush();

        expect(bar().textContent).toContain('sendQueue.selection {"count":4}');
        act(() => { button(bar(), 'sendQueue.send').click(); });
        await flush(20);
        expect(props.addFromLibrary).toHaveBeenCalledTimes(2);
        expect(modal('send-queue-summary').textContent).toContain('sendQueue.summary.counts {"sent":2,"skipped":2,"failed":0}');
    });

    it('needs a plugged device to send', async () => {
        const props = actions();
        render(PackLibrary, {device: {metadata: null, packs: []}, library: LIBRARY, ...props});
        act(() => { box('Alpha').click(); });
        await flush();
        expect(bar().textContent).toContain('spaceEstimate.plugFirst');
        expect(button(bar(), 'sendQueue.send').disabled).toBe(true);
        expect(props.estimateOnDevice).not.toHaveBeenCalled();
    });

    it('keeps the enriched preference already chosen: no question', async () => {
        localStorage.setItem(LOCAL_STORAGE_ALLOW_ENRICHED_BINARY_FORMAT, 'false');
        const props = actions();
        render(PackLibrary, {device: device('raw'), library: LIBRARY, ...props});
        act(() => { box('Alpha').click(); });
        await flush();
        act(() => { button(bar(), 'sendQueue.send').click(); });
        await flush(20);
        expect(modal('ask-allow-enriched')).toBeNull();
        expect(props.convertPackInLibrary).toHaveBeenCalledWith('u-a', 'a.zip', 'raw', false, expect.anything());
    });
});
