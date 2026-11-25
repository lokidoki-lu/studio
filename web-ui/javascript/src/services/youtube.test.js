/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {fetchYoutubeTools, startYoutubePack, fetchCurrentYoutubeJob, cancelYoutubePack, fetchYoutubeJob} from './youtube';

function response(status, body) {
    const text = typeof body === 'string' ? body : JSON.stringify(body);
    return Promise.resolve({
        ok: status >= 200 && status < 300,
        status,
        statusText: status === 503 ? 'Service Unavailable' : 'OK',
        json: () => Promise.resolve(JSON.parse(text)),
        text: () => Promise.resolve(text)
    });
}

const realFetch = global.fetch;
let calls;
beforeEach(() => {
    calls = [];
});
afterEach(() => {
    global.fetch = realFetch;
});

function answer(status, body) {
    global.fetch = (url, options) => {
        calls.push({url, options});
        return response(status, body);
    };
}

describe('YouTube service', () => {

    it('asks for a fresh check of the tools only when told to', async () => {
        answer(200, {ready: true});
        await fetchYoutubeTools();
        await fetchYoutubeTools(true);
        expect(calls.map(c => c.url)).toEqual(['/api/youtube/tools', '/api/youtube/tools?refresh=true']);
    });

    it('posts the request as JSON', async () => {
        answer(202, {id: 'j1', state: 'RUNNING'});
        const job = await startYoutubePack({urls: ['https://youtu.be/a']});
        expect(job.id).toBe('j1');
        expect(calls[0].options.method).toBe('POST');
        expect(calls[0].options.headers['Content-Type']).toBe('application/json');
        expect(JSON.parse(calls[0].options.body)).toEqual({urls: ['https://youtu.be/a']});
    });

    it('keeps the backend explanation and code of a refusal', async () => {
        answer(503, {success: false, error: 'TOOLS_MISSING', message: 'Outils manquants : ffmpeg'});
        await expect(startYoutubePack({urls: ['x']})).rejects.toMatchObject({
            code: 'TOOLS_MISSING', status: 503, message: 'Service Unavailable: Outils manquants : ffmpeg'
        });
    });

    it('has no current job rather than an error when there has been none', async () => {
        answer(404, {success: false, error: 'NOT_FOUND'});
        await expect(fetchCurrentYoutubeJob()).resolves.toBeNull();
    });

    it('escapes the job id in the paths', async () => {
        answer(200, {id: 'a/b'});
        await fetchYoutubeJob('a/b');
        await cancelYoutubePack('a/b');
        expect(calls.map(c => c.url)).toEqual(['/api/youtube/jobs/a%2Fb', '/api/youtube/jobs/a%2Fb/cancel']);
        expect(calls[1].options.method).toBe('POST');
    });
});
