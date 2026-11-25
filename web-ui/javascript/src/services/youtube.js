/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {apiFetch, handleJsonOrError} from "../utils/fetch";

/*
 * Packs made from YouTube links (see API.md, "Packs from YouTube"). The creation runs in the
 * backend; the dialog starts it, then polls its state.
 */

// {ready, ytDlp, ytDlpVersion, ffmpeg, jsRuntime, message, voices, defaultVoice, bitrates, ...}
export const fetchYoutubeTools = (refresh) => {
    return apiFetch('/api/youtube/tools' + (refresh ? '?refresh=true' : ''))
        .then(handleJsonOrError);
};

export const startYoutubePack = (body) => {
    return apiFetch('/api/youtube/jobs', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(body)
    })
        .then(handleJsonOrError);
};

export const fetchYoutubeJob = (id) => {
    return apiFetch('/api/youtube/jobs/' + encodeURIComponent(id))
        .then(handleJsonOrError);
};

// The running creation or the last one, or null when there has been none
export const fetchCurrentYoutubeJob = () => {
    return apiFetch('/api/youtube/jobs/current')
        // 204: no creation yet (404 from an older server)
        .then(response => response.status === 204 || response.status === 404 ? null : handleJsonOrError(response));
};

export const cancelYoutubePack = (id) => {
    return apiFetch('/api/youtube/jobs/' + encodeURIComponent(id) + '/cancel', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: "{}"
    })
        .then(handleJsonOrError);
};
