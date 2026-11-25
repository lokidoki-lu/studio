/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The "from YouTube" dialog, without React: the form, the request it sends, and how a job's state
 * reads. The backend checks everything again (see API.md, "Packs from YouTube").
 */

export const BITRATES = [48, 64, 96, 128];

// The links pasted in the text area: one per line, blank lines ignored
export function parseLinks(text) {
    return (text || '')
        .split(/\r\n|\r|\n/)
        .map(l => l.trim())
        .filter(l => l.length > 0);
}

// Same defaults as Lunii Pack, or the backend's when it gives them
export function defaultForm(tools) {
    const t = tools || {};
    return {
        links: '',
        title: '',
        description: '',
        voice: t.defaultVoice || 'fr-FR-DeniseNeural',
        cleanTitles: true,
        sponsorBlock: true,
        seasons: true,
        autoplayNext: false,
        pauses: true,
        minMinutes: '',
        maxMinutes: '',
        menuSize: t.defaultMenuSize !== undefined ? t.defaultMenuSize : 10,
        bitrate: t.defaultBitrate || 96,
        maxVideos: t.defaultMaxVideos || 50
    };
}

function minutes(value) {
    if (value === '' || value === null || value === undefined) {
        return 0;
    }
    const n = Number(String(value).replace(',', '.'));
    return Number.isFinite(n) && n > 0 ? Math.round(n * 60) : 0;
}

function integer(value, fallback) {
    const n = parseInt(value, 10);
    return Number.isFinite(n) ? n : fallback;
}

// What is wrong with the form, as a translation key, or null
export function formProblem(form, maxLinks) {
    const links = parseLinks(form.links);
    if (links.length === 0) {
        return 'youtubePack.problems.noLink';
    }
    if (maxLinks && links.length > maxLinks) {
        return 'youtubePack.problems.tooManyLinks';
    }
    const min = minutes(form.minMinutes);
    const max = minutes(form.maxMinutes);
    if (max > 0 && min > max) {
        return 'youtubePack.problems.durations';
    }
    return null;
}

// The body of POST /api/youtube/jobs
export function buildRequest(form) {
    const body = {
        urls: parseLinks(form.links),
        voice: form.voice || undefined,
        cleanTitles: form.cleanTitles !== false,
        sponsorBlock: form.sponsorBlock !== false,
        seasons: form.seasons !== false,
        autoplayNext: form.autoplayNext === true,
        pauses: form.pauses !== false,
        minDuration: minutes(form.minMinutes),
        maxDuration: minutes(form.maxMinutes),
        menuSize: Math.max(0, integer(form.menuSize, 10)),
        bitrate: BITRATES.includes(integer(form.bitrate, 96)) ? integer(form.bitrate, 96) : 96,
        maxVideos: Math.min(200, Math.max(1, integer(form.maxVideos, 50)))
    };
    const title = (form.title || '').trim();
    if (title) {
        body.title = title;
    }
    const description = (form.description || '').trim();
    if (description) {
        body.description = description;
    }
    return body;
}

export function isRunning(job) {
    return !!job && job.state === 'RUNNING';
}

export function isFinished(job) {
    return !!job && ['DONE', 'FAILED', 'CANCELLED'].includes(job.state);
}

// 0 to 100
export function progressPercent(job) {
    if (!job) {
        return 0;
    }
    if (job.state === 'DONE') {
        return 100;
    }
    const p = Number(job.progress);
    return Number.isFinite(p) ? Math.max(0, Math.min(100, Math.round(p * 100))) : 0;
}

// The last lines of the job's log, newest last
export function lastLines(job, count) {
    const log = job && Array.isArray(job.log) ? job.log : [];
    return log.slice(Math.max(0, log.length - (count || 8)));
}
