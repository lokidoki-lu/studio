/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {apiFetch, handleJsonOrError, httpErrorMessage, errorCode, unreachableMessage} from "../utils/fetch";

export const fetchLibraryInfos = () => {
    return apiFetch('/api/library/infos')
        .then(handleJsonOrError);
};

export const fetchLibraryPacks = () => {
    return apiFetch('/api/library/packs')
        .then(handleJsonOrError);
};

export const downloadFromLibrary = async (uuid, path) => {
    return await apiFetch('/api/library/download', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({uuid, path})
    });
};

// One entry of a library archive (story.json, thumbnail.png, assets/...), as a Blob, or null if the archive has none
// purpose 'preview' only shows the pack: the server lets it read packs it refuses to open in the editor
export const fetchLibraryPackEntry = async (path, entry, purpose) => {
    const resp = await apiFetch('/api/library/entry', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(purpose ? {path, entry, purpose} : {path, entry})
    });
    if (resp.status === 404) {
        return null;
    }
    if (!resp.ok) {
        const text = await resp.text().catch(() => '');
        const error = new Error(httpErrorMessage(resp.statusText, text));
        error.status = resp.status;
        throw error;
    }
    return resp.blob();
};

export const uploadToLibrary = async (uuid, path, packData, progressHandler) => {
    return new Promise((resolve, reject) => {
        let xhr = new XMLHttpRequest();
        if (xhr.upload) {
            xhr.upload.onprogress = progressHandler;
        }
        xhr.onload = () => {
            let body;
            try {
                body = JSON.parse(xhr.responseText);
            } catch (e) {
                body = undefined;
            }
            if (xhr.status < 200 || xhr.status >= 300) {
                // Same shape as handleJsonOrError, so the toast shows the backend's explanation.
                const text = xhr.responseText || '';
                const error = new Error(httpErrorMessage(xhr.statusText || ('HTTP ' + xhr.status), text));
                error.status = xhr.status;
                const code = errorCode(text);
                if (code) {
                    error.code = code;
                }
                reject(error);
                return;
            }
            if (body === undefined) {
                reject(new Error('Invalid response from the library upload: ' + xhr.responseText));
                return;
            }
            console.log('xhr upload complete: %o', body);
            resolve(body);
        };
        xhr.onerror = () => {
            // No answer: STUdio stopped (or the connection was cut)
            const error = new Error(unreachableMessage() + ' (network error while uploading the pack to the library)');
            error.code = 'UNREACHABLE';
            reject(error);
        };
        xhr.onabort = () => {
            reject(new Error('Upload to the library was aborted'));
        };
        xhr.open('post', '/api/library/upload', true);
        let formData = new FormData();
        formData.append("uuid", uuid);
        formData.append("path", path);
        formData.append("pack", packData);
        xhr.send(formData);
    });
};

export const convertInLibrary = async (uuid, path, format, allowEnriched) => {
    return await apiFetch('/api/library/convert', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({uuid, path, format, allowEnriched})
    })
        .then(handleJsonOrError);
};

export const removeFromLibrary = (path) => {
    return apiFetch('/api/library/remove', {
        method: "POST",
        headers: { "Content-Type" : "application/json" },
        body: JSON.stringify({path})
    })
        .then(handleJsonOrError);
};

/*
 * Whether a converted pack still corresponds to the source it was made from.
 *
 * Asked only when a pack is dropped on a device, never while listing: the backend reads both
 * artefacts in full to answer. It returns a verdict and nothing else — no digest, no record — so
 * this side has nothing to decide and nothing to get wrong.
 */
/*
 * Library entries that serve no purpose any more, as the backend sees them now:
 * {candidates: [{path, uuid, title, format, size, timestamp, reason, selected, keptPath}], totalSize}.
 */
export const fetchPurgeCandidates = () => {
    return apiFetch('/api/library/purge/candidates')
        .then(handleJsonOrError);
};

/*
 * Removes the named candidates. The backend checks each one again and leaves alone any that is no
 * longer a candidate: {success, freed, results: [{path, removed, size, error?}]}.
 */
export const purgeLibrary = (paths) => {
    return apiFetch('/api/library/purge', {
        method: "POST",
        headers: { "Content-Type" : "application/json" },
        body: JSON.stringify({paths})
    })
        .then(handleJsonOrError);
};

export const verifyConversion = async (sourcePath, convertedPath) => {
    return await apiFetch('/api/library/verify-conversion', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({sourcePath, convertedPath})
    })
        .then(handleJsonOrError);
};

/*
 * What the library preview shows of a pack beyond its listed metadata, read by the backend from headers
 * only: {path, format, size}, plus {stageNodes, stories} for raw and FS packs and
 * {audio: {seconds, measured, estimated, unmeasured}} for archives.
 */
export const fetchLibraryPackDetails = (path) => {
    return apiFetch('/api/library/details', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({path})
    })
        .then(handleJsonOrError);
};
