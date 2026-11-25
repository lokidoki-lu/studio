/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The "from YouTube" button, rendered in jsdom with fake timers and the YouTube services replaced:
 * how it follows a creation, and what it does when the creation ends while it is not shown, when
 * the backend stops answering, or when two checks of the tools cross.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act} from 'react-dom/test-utils';
import {toast} from 'react-toastify';

import {YoutubePack, MAX_POLL_FAILURES} from './YoutubePack';
import {fetchYoutubeTools, fetchYoutubeJob, fetchCurrentYoutubeJob} from '../services/youtube';

jest.mock('react-toastify', () => ({toast: {success: jest.fn(), error: jest.fn(), info: jest.fn(), warning: jest.fn()}}));
jest.mock('../actions', () => ({actionRefreshLibrary: () => ({type: 'TEST'})}));
jest.mock('../services/youtube', () => ({
    fetchYoutubeTools: jest.fn(),
    startYoutubePack: jest.fn(),
    fetchYoutubeJob: jest.fn(),
    fetchCurrentYoutubeJob: jest.fn(),
    cancelYoutubePack: jest.fn()
}));

const t = (key, values) => (values && values.defaultValue !== undefined && !KNOWN.includes(key))
    ? values.defaultValue
    : (values ? `${key} ${JSON.stringify(values)}` : key);
// The translations the tests pretend to have
const KNOWN = ['youtubePack.errors.BUSY'];

const running = (id = 'job-1') => ({id, state: 'RUNNING', step: 'DOWNLOADING', progress: 0.5, current: 1, total: 2, log: []});
const done = (id = 'job-1') => ({id, state: 'DONE', step: 'FINISHED', progress: 1, log: [], result: {title: 'Pack', stories: 2}});

let container;

beforeEach(() => {
    jest.useFakeTimers();
    container = document.createElement('div');
    document.body.appendChild(container);
    window.sessionStorage.clear();
    jest.spyOn(console, 'error').mockImplementation(() => {});
    toast.success.mockClear();
    toast.error.mockClear();
    fetchYoutubeTools.mockReset();
    fetchYoutubeJob.mockReset();
    fetchCurrentYoutubeJob.mockReset();
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
    console.error.mockRestore();
    jest.useRealTimers();
});

const flush = () => act(async () => {
    for (let i = 0; i < 5; i++) {
        await Promise.resolve();
    }
});

async function tick() {
    act(() => { jest.advanceTimersByTime(1000); });
    await flush();
}

async function mount(props = {}) {
    const refreshLibrary = jest.fn();
    let ref;
    act(() => {
        ReactDOM.render(<YoutubePack t={t} refreshLibrary={refreshLibrary} ref={r => { ref = r; }} {...props}/>, container);
    });
    await flush();
    return {refreshLibrary, component: () => ref};
}

describe('YouTube button', () => {

    it('stops following the creation once it is gone, without touching its state', async () => {
        fetchCurrentYoutubeJob.mockResolvedValue(running());
        let answer;
        fetchYoutubeJob.mockImplementation(() => new Promise(resolve => { answer = resolve; }));
        await mount();
        await tick();
        expect(fetchYoutubeJob).toHaveBeenCalledTimes(1);

        act(() => { ReactDOM.unmountComponentAtNode(container); });
        answer(running());
        await flush();
        act(() => { jest.advanceTimersByTime(10000); });
        await flush();

        expect(fetchYoutubeJob).toHaveBeenCalledTimes(1);
        expect(console.error).not.toHaveBeenCalledWith(expect.stringContaining('unmounted component'), expect.anything(), expect.anything());
    });

    it('announces a creation that ended while the button was not shown, once', async () => {
        fetchCurrentYoutubeJob.mockResolvedValue(running());
        fetchYoutubeJob.mockResolvedValue(running());
        await mount();
        await tick();
        act(() => { ReactDOM.unmountComponentAtNode(container); });

        fetchCurrentYoutubeJob.mockResolvedValue(done());
        const first = await mount();
        expect(first.refreshLibrary).toHaveBeenCalledTimes(1);
        expect(toast.success).toHaveBeenCalledTimes(1);

        act(() => { ReactDOM.unmountComponentAtNode(container); });
        const second = await mount();
        expect(second.refreshLibrary).not.toHaveBeenCalled();
        expect(toast.success).toHaveBeenCalledTimes(1);
    });

    it('does not announce an old creation this tab did not follow', async () => {
        fetchCurrentYoutubeJob.mockResolvedValue(done('old'));
        const {refreshLibrary} = await mount();
        expect(refreshLibrary).not.toHaveBeenCalled();
        expect(toast.success).not.toHaveBeenCalled();
    });

    it('gives up after successive network failures, and says so', async () => {
        fetchCurrentYoutubeJob.mockResolvedValue(running());
        fetchYoutubeJob.mockRejectedValue(new TypeError('Failed to fetch'));
        const {component} = await mount();
        for (let i = 0; i < MAX_POLL_FAILURES + 3; i++) {
            await tick();
        }
        expect(fetchYoutubeJob).toHaveBeenCalledTimes(MAX_POLL_FAILURES);
        expect(component().state.job).toBeNull();
        expect(toast.error).toHaveBeenCalledWith('youtubePack.toasts.unreachable', expect.anything());
    });

    it('keeps following after a failure followed by an answer', async () => {
        fetchCurrentYoutubeJob.mockResolvedValue(running());
        let calls = 0;
        fetchYoutubeJob.mockImplementation(() => (++calls % (MAX_POLL_FAILURES - 1) === 0)
            ? Promise.resolve(running()) : Promise.reject(new TypeError('Failed to fetch')));
        const {component} = await mount();
        for (let i = 0; i < MAX_POLL_FAILURES * 2; i++) {
            await tick();
        }
        expect(component().state.job).not.toBeNull();
        expect(toast.error).not.toHaveBeenCalled();
    });

    it('says the creation was interrupted when the backend no longer knows it', async () => {
        fetchCurrentYoutubeJob.mockResolvedValue(running());
        fetchYoutubeJob.mockRejectedValue(Object.assign(new Error('gone'), {status: 404}));
        const {component} = await mount();
        await tick();
        expect(component().state.job).toBeNull();
        expect(toast.error).toHaveBeenCalledWith('youtubePack.toasts.interrupted', expect.anything());
        await tick();
        expect(fetchYoutubeJob).toHaveBeenCalledTimes(1);
    });

    it('shows only the answer to the last check of the tools', async () => {
        fetchCurrentYoutubeJob.mockResolvedValue(null);
        const answers = [];
        fetchYoutubeTools.mockImplementation(() => new Promise(resolve => answers.push(resolve)));
        const {component} = await mount();
        act(() => { component().checkTools(false); });
        act(() => { component().checkTools(true); });
        answers[1]({ready: true, maxLinks: 20});
        await flush();
        answers[0]({ready: false});
        await flush();
        expect(component().state.tools).toEqual({ready: true, maxLinks: 20});
        expect(component().state.checking).toBe(false);
    });

    it('offers to check again when the check of the tools failed', async () => {
        fetchCurrentYoutubeJob.mockResolvedValue(null);
        fetchYoutubeTools.mockRejectedValue(new Error('STUdio ne répond pas'));
        await mount();
        act(() => { container.querySelector('.youtube-pack-button').click(); });
        await flush();
        const recheck = Array.from(container.querySelectorAll('.youtube-pack-error button'))
            .find(b => b.textContent === 'youtubePack.recheck');
        expect(recheck).toBeDefined();
        fetchYoutubeTools.mockResolvedValue({ready: true});
        act(() => { recheck.click(); });
        await flush();
        expect(fetchYoutubeTools).toHaveBeenLastCalledWith(true);
        expect(container.querySelector('.youtube-pack-error')).toBeNull();
    });

    it('previews the chosen picture style and remembers it', async () => {
        window.localStorage.clear();
        fetchCurrentYoutubeJob.mockResolvedValue(null);
        fetchYoutubeTools.mockResolvedValue({ready: true, jsRuntime: true, imageStyles: ['color', 'gray', 'drawing', 'negative']});
        await mount();
        act(() => { container.querySelector('.youtube-pack-button').click(); });
        await flush();
        const select = document.getElementById('youtube-pack-image-style');
        const preview = () => document.querySelector('.youtube-pack-image-style img').getAttribute('src');
        expect(select.value).toBe('color');
        expect(preview()).toBe('/api/youtube/image-styles/color.png');
        expect(Array.from(select.options).map(o => o.textContent)).toContain('youtubePack.imageStyles.drawing');
        act(() => {
            select.value = 'drawing';
            select.dispatchEvent(new Event('change', {bubbles: true}));
        });
        expect(preview()).toBe('/api/youtube/image-styles/drawing.png');
        expect(window.localStorage.getItem('youtubeImageStyle')).toBe('drawing');
        window.localStorage.clear();
    });

    it('translates the refusals it knows, and shows the message of the others', async () => {
        fetchCurrentYoutubeJob.mockResolvedValue(null);
        const {component} = await mount();
        expect(component().errorOf(Object.assign(new Error('Un pack est déjà en cours'), {code: 'BUSY'})))
            .toContain('youtubePack.errors.BUSY');
        expect(component().errorOf(Object.assign(new Error('lien invalide'), {code: 'INVALID_REQUEST'})))
            .toBe('lien invalide');
    });
});
