/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The dialogs of the title generator, with the server replaced: nothing is attached once the dialog
 * is closed, Enter does not start a second request, and an engine list that cannot be read says so.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act} from 'react-dom/test-utils';

import {TitleAssetDialog, TitleVoiceSettingsDialog} from './TitleDialogs';
import {speakTitle, fetchVoiceEngines} from '../../utils/titleAssets';

jest.mock('react-i18next', () => {
    const mockT = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;
    return {useTranslation: () => ({t: mockT})};
});
jest.mock('react-toastify', () => ({toast: {warning: jest.fn(), error: jest.fn()}}));
jest.mock('../../utils/titleAssets', () => {
    const actual = jest.requireActual('../../utils/titleAssets');
    return {...actual, speakTitle: jest.fn(), fetchVoiceEngines: jest.fn(), renderTitleImage: jest.fn(() => 'data:image/jpeg;base64,AA==')};
});

let container;

beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    speakTitle.mockReset();
    fetchVoiceEngines.mockReset();
    fetchVoiceEngines.mockResolvedValue({edge: true, system: true});
    jest.spyOn(console, 'warn').mockImplementation(() => {});
    jest.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
    console.warn.mockRestore();
    console.error.mockRestore();
});

const flush = () => act(async () => {
    for (let i = 0; i < 5; i++) {
        await Promise.resolve();
    }
});

function deferred() {
    let resolve;
    const promise = new Promise(r => { resolve = r; });
    return {promise, resolve};
}

const attachButton = () => Array.from(document.querySelectorAll('.title-dialog button'))
    .find(b => b.textContent.trim() === 'editor.titleGenerator.attach');

describe('TitleAssetDialog', () => {

    it('attaches nothing once closed while the voice was being made', async () => {
        const answer = deferred();
        speakTitle.mockReturnValue(answer.promise);
        const onGenerated = jest.fn();
        const onClose = jest.fn(() => ReactDOM.unmountComponentAtNode(container));
        await act(async () => {
            ReactDOM.render(<TitleAssetDialog kind="audio" title="Le loup" onClose={onClose} onGenerated={onGenerated}/>, container);
        });
        act(() => { attachButton().click(); });
        // Cancelled: the parent closes the dialog
        act(() => { onClose(); });
        answer.resolve({dataUrl: 'data:audio/wav;base64,AA==', engine: 'edge'});
        await flush();
        expect(onGenerated).not.toHaveBeenCalled();
        expect(console.error).not.toHaveBeenCalledWith(expect.stringContaining('unmounted component'), expect.anything(), expect.anything());
    });

    it('does not start a second request on Enter while the first is under way', async () => {
        const answer = deferred();
        speakTitle.mockReturnValue(answer.promise);
        const onGenerated = jest.fn();
        await act(async () => {
            ReactDOM.render(<TitleAssetDialog kind="audio" title="Le loup" onClose={() => {}} onGenerated={onGenerated}/>, container);
        });
        const area = document.getElementById('title-dialog-text');
        act(() => { area.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true})); });
        act(() => { area.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', bubbles: true})); });
        expect(speakTitle).toHaveBeenCalledTimes(1);
        answer.resolve({dataUrl: 'data:audio/wav;base64,AA==', engine: 'edge'});
        await flush();
        expect(onGenerated).toHaveBeenCalledTimes(1);
    });

    it('labels its close button in the language of the interface', async () => {
        await act(async () => {
            ReactDOM.render(<TitleAssetDialog kind="image" title="Le loup" onClose={() => {}} onGenerated={() => {}}/>, container);
        });
        expect(document.querySelector('.title-dialog .close').getAttribute('aria-label')).toBe('dialogs.shared.close');
    });
});

describe('TitleVoiceSettingsDialog', () => {
    it('says the engines are unknown when the server cannot tell', async () => {
        fetchVoiceEngines.mockRejectedValue(new Error('STUdio ne répond pas'));
        await act(async () => {
            ReactDOM.render(<TitleVoiceSettingsDialog onClose={() => {}}/>, container);
        });
        await flush();
        const engines = document.querySelector('.title-dialog-engines').textContent;
        expect(engines).toContain('editor.titleVoice.unknown');
        expect(engines).not.toContain('…');
        // Nothing said to be missing
        expect(document.body.textContent).not.toContain('editor.titleVoice.onlineMissing');
    });
});
