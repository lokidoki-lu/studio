/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * uploadToLibrary goes through XMLHttpRequest (for upload progress), not fetch, so it does not get
 * handleJsonOrError for free. Every way the request can end must settle the promise: an upload
 * that is refused, cut off or answered with something unreadable used to leave it pending forever
 * (or throw inside onload), and the toast spinning.
 */

import {uploadToLibrary} from './library';

let lastXhr;

class FakeXhr {
    constructor() {
        this.upload = {};
        this.status = 0;
        this.statusText = '';
        this.responseText = '';
        lastXhr = this;
    }
    open(method, url) {
        this.method = method;
        this.url = url;
    }
    send(body) {
        this.body = body;
    }
    // --- test controls ---
    respond(status, statusText, responseText) {
        this.status = status;
        this.statusText = statusText;
        this.responseText = responseText;
        this.onload && this.onload();
    }
}

const realXhr = global.XMLHttpRequest;
beforeEach(() => {
    global.XMLHttpRequest = FakeXhr;
    lastXhr = null;
});
afterEach(() => {
    global.XMLHttpRequest = realXhr;
});

const start = () => uploadToLibrary(null, 'pack.zip', new Blob(['x']), () => {});

describe('uploadToLibrary', () => {
    it('resolves with the parsed body of a 2xx answer', async () => {
        const upload = start();
        lastXhr.respond(200, 'OK', '{"success":true}');
        await expect(upload).resolves.toEqual({success: true});
        expect(lastXhr.url).toBe('/api/library/upload');
    });

    it('rejects a non-2xx answer with the backend message, code and status', async () => {
        const upload = start();
        lastXhr.respond(409, 'Conflict', '{"success":false,"error":"PACK_EXISTS","message":"The pack is already in the library"}');
        const error = await upload.catch(e => e);
        expect(error).toBeInstanceOf(Error);
        expect(error.message).toBe('Conflict: The pack is already in the library');
        expect(error.status).toBe(409);
        expect(error.code).toBe('PACK_EXISTS');
    });

    it('rejects a non-JSON error page rather than throwing', async () => {
        const upload = start();
        lastXhr.respond(500, 'Internal Server Error', '<html>boom</html>');
        const error = await upload.catch(e => e);
        expect(error.message).toBe('Internal Server Error: <html>boom</html>');
        expect(error.status).toBe(500);
        expect(error.code).toBeUndefined();
    });

    it('rejects a 2xx answer whose body is not JSON', async () => {
        const upload = start();
        lastXhr.respond(200, 'OK', 'not json');
        await expect(upload).rejects.toThrow('Invalid response');
    });

    it('rejects on a network error', async () => {
        const upload = start();
        lastXhr.onerror();
        await expect(upload).rejects.toThrow('Network error');
    });

    it('rejects when the upload is aborted', async () => {
        const upload = start();
        lastXhr.onabort();
        await expect(upload).rejects.toThrow('aborted');
    });
});
