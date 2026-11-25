/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Generated assets for node titles: the spoken title (server side, /api/tts) and an image showing
// the title (drawn here, on a canvas). Self-contained on purpose: no dependency on other helpers.

import {apiFetch, handleJsonOrError} from "./fetch";

export const LOCAL_STORAGE_TITLE_VOICE = 'titleVoice';

/** Device screen size: the editor refuses any other image size. */
export const TITLE_IMAGE_WIDTH = 320;
export const TITLE_IMAGE_HEIGHT = 240;

/** Same limit as the server. */
export const TITLE_MAX_LENGTH = 500;

// -- voice

/**
 * The voice setting, stored in this browser: an online voice name (the computer voice answers if
 * it is unavailable), 'system' (computer voice only, nothing leaves the computer), or 'auto' (the
 * server's default voice). Denise by default, as in Lunii Pack.
 */
export const DEFAULT_TITLE_VOICE = 'fr-FR-DeniseNeural';

export function getTitleVoice() {
    try {
        return window.localStorage.getItem(LOCAL_STORAGE_TITLE_VOICE) || DEFAULT_TITLE_VOICE;
    } catch (e) {
        return DEFAULT_TITLE_VOICE;
    }
}

export function setTitleVoice(value) {
    try {
        window.localStorage.setItem(LOCAL_STORAGE_TITLE_VOICE, value);
    } catch (e) {
        // Not persisted: the default is used next time.
    }
}

/** The request body for a voice setting (see getTitleVoice). */
export function voiceRequest(text, setting) {
    if (!setting || setting === 'auto') {
        return {text, engine: 'auto'};
    }
    if (setting === 'system') {
        return {text, engine: 'system'};
    }
    // A chosen online voice: auto, so that the computer voice still answers when offline.
    return {text, engine: 'auto', voice: setting};
}

export async function fetchVoiceEngines() {
    return apiFetch('/api/tts/engines').then(handleJsonOrError);
}

/** The online voices Lunii Pack offers, in the same order; key names the label in translations. */
export const TITLE_VOICES = [
    {value: 'fr-FR-DeniseNeural', key: 'denise'},
    {value: 'fr-FR-HenriNeural', key: 'henri'},
    {value: 'fr-FR-EloiseNeural', key: 'eloise'},
    {value: 'fr-FR-VivienneMultilingualNeural', key: 'vivienne'},
    {value: 'fr-FR-RemyMultilingualNeural', key: 'remy'}
];

/** Sentence spoken by the "listen" button, as in Lunii Pack. */
export const VOICE_SAMPLE_TEXT = 'Bonjour, ceci est un essai.';

// -- pronunciations, as in Lunii Pack: "Dalton = Daltonne ; Luke = Louc" (written = spoken)

export const LOCAL_STORAGE_TITLE_PRONUNCIATION = 'titlePronunciation';

export function getTitlePronunciation() {
    try {
        return window.localStorage.getItem(LOCAL_STORAGE_TITLE_PRONUNCIATION) || '';
    } catch (e) {
        return '';
    }
}

export function setTitlePronunciation(value) {
    try {
        window.localStorage.setItem(LOCAL_STORAGE_TITLE_PRONUNCIATION, value || '');
    } catch (e) {
        // Not persisted
    }
}

/** "Dalton=Daltonne; Lucky Luke = Lucky Louc" → [[written, spoken], …], longest written first. */
export function parsePronunciations(text) {
    const pairs = [];
    String(text || '').split(/[;\n]/).forEach(piece => {
        const sep = piece.indexOf('=');
        if (sep > 0) {
            const written = piece.substring(0, sep).trim();
            if (written) {
                pairs.push([written, piece.substring(sep + 1).trim()]);
            }
        }
    });
    // "Lucky Luke" before "Luke"
    return pairs.sort((a, b) => b[0].length - a[0].length);
}

const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Replaces whole words, ignoring case; a plural follows ("Dalton=Daltonne" also turns "Daltons"
 * into "Daltonnes"). Same rule as Lunii Pack. One pass over the text, the longest written forms
 * tried first: a spoken form is never replaced again ("Dalton=Daltonne; Daltonne=Daltone").
 */
export function applyPronunciations(text, pairs) {
    const result = String(text || '');
    const sorted = (pairs || []).filter(([written]) => written).sort((a, b) => b[0].length - a[0].length);
    if (sorted.length === 0) {
        return result;
    }
    const spokenOf = new Map();
    sorted.forEach(([written, spoken]) => {
        const key = written.toLowerCase();
        if (!spokenOf.has(key)) {
            spokenOf.set(key, spoken);
        }
    });
    const alternatives = sorted.map(([written]) => escapeRegExp(written)).join('|');
    const re = new RegExp(`(^|[^\\p{L}\\p{N}_])(${alternatives})(s?)(?![\\p{L}\\p{N}_])`, 'giu');
    return result.replace(re, (m, before, written, plural) => {
        const key = written.toLowerCase();
        return spokenOf.has(key) ? before + spokenOf.get(key) + plural : m;
    });
}

/**
 * Speaks the text on the server, after the pronunciations are applied. Resolves to {dataUrl,
 * engine, voice, fallbackReason}; dataUrl is a device-native WAVE, attached to a node exactly as a
 * dropped file would be.
 */
export async function speakTitle(text, setting = getTitleVoice(), pronunciation = getTitlePronunciation()) {
    // The server refuses more than TITLE_MAX_LENGTH characters: a pronunciation may lengthen the text
    const spoken = (applyPronunciations(text, parsePronunciations(pronunciation)).trim() || text)
        .substring(0, TITLE_MAX_LENGTH);
    const answer = await apiFetch('/api/tts/speak', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(voiceRequest(spoken, setting))
    }).then(handleJsonOrError);
    return {
        dataUrl: 'data:' + (answer.mimeType || 'audio/wav') + ';base64,' + answer.data,
        engine: answer.engine,
        voice: answer.voice,
        fallbackReason: answer.fallbackReason
    };
}

// -- image

/**
 * Splits the text into lines no wider than maxWidth, as measured by measure(text) → width.
 * A word wider than a line on its own is cut between characters.
 */
export function wrapText(text, measure, maxWidth) {
    const words = String(text || '').trim().split(/\s+/).filter(w => w.length > 0);
    const lines = [];
    let current = '';
    const pushWord = (word) => {
        // Cut a word that cannot fit on any line
        while (measure(word) > maxWidth && word.length > 1) {
            let cut = word.length - 1;
            while (cut > 1 && measure(word.substring(0, cut)) > maxWidth) {
                cut--;
            }
            if (current) {
                lines.push(current);
                current = '';
            }
            lines.push(word.substring(0, cut));
            word = word.substring(cut);
        }
        return word;
    };
    words.forEach(w => {
        const word = pushWord(w);
        const candidate = current ? current + ' ' + word : word;
        if (current && measure(candidate) > maxWidth) {
            lines.push(current);
            current = word;
        } else {
            current = candidate;
        }
    });
    if (current) {
        lines.push(current);
    }
    return lines;
}

/**
 * The largest font size (from maxSize down to minSize) at which the wrapped text fits in the box.
 * measureAt(size) returns a measure function for that font size. Returns {size, lines, lineHeight}.
 */
export function layoutTitle(text, measureAt, width, height, {maxSize = 56, minSize = 14, margin = 18, lineSpacing = 1.2} = {}) {
    const innerWidth = width - 2 * margin;
    const innerHeight = height - 2 * margin;
    let best = null;
    for (let size = maxSize; size >= minSize; size -= 2) {
        const lines = wrapText(text, measureAt(size), innerWidth);
        const lineHeight = Math.round(size * lineSpacing);
        best = {size, lines, lineHeight};
        if (lines.length * lineHeight <= innerHeight) {
            return best;
        }
    }
    // Still too long at the smallest size: keep the lines that fit, the last one ending with "…"
    const maxLines = Math.max(1, Math.floor(innerHeight / best.lineHeight));
    if (best.lines.length > maxLines) {
        const measure = measureAt(best.size);
        const lines = best.lines.slice(0, maxLines);
        let last = lines[maxLines - 1] + '…';
        while (last.length > 1 && measure(last) > innerWidth) {
            last = last.substring(0, last.length - 2) + '…';
        }
        lines[maxLines - 1] = last;
        best = {...best, lines};
    }
    return best;
}

const TITLE_FONT = '"Trebuchet MS", "Segoe UI", Arial, sans-serif';

/**
 * Draws the title, centred and wrapped, light on dark for the device screen, and returns a JPEG
 * data URL of 320x240 (JPEG: no alpha channel, which the device does not take).
 */
export function renderTitleImage(text, {background = '#1d2b53', foreground = '#ffffff', accent = '#ffcc33'} = {}) {
    const canvas = document.createElement('canvas');
    canvas.width = TITLE_IMAGE_WIDTH;
    canvas.height = TITLE_IMAGE_HEIGHT;
    const ctx = canvas.getContext('2d');

    ctx.fillStyle = background;
    ctx.fillRect(0, 0, TITLE_IMAGE_WIDTH, TITLE_IMAGE_HEIGHT);
    // A thin frame, so that the screen edge does not cut the composition
    ctx.strokeStyle = accent;
    ctx.lineWidth = 4;
    ctx.strokeRect(8, 8, TITLE_IMAGE_WIDTH - 16, TITLE_IMAGE_HEIGHT - 16);

    const measureAt = (size) => {
        ctx.font = `bold ${size}px ${TITLE_FONT}`;
        return (s) => ctx.measureText(s).width;
    };
    const {size, lines, lineHeight} = layoutTitle(text, measureAt, TITLE_IMAGE_WIDTH, TITLE_IMAGE_HEIGHT, {margin: 22});

    ctx.font = `bold ${size}px ${TITLE_FONT}`;
    ctx.fillStyle = foreground;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    const top = (TITLE_IMAGE_HEIGHT - lines.length * lineHeight) / 2 + lineHeight / 2;
    lines.forEach((line, i) => ctx.fillText(line, TITLE_IMAGE_WIDTH / 2, top + i * lineHeight));

    return canvas.toDataURL('image/jpeg', 0.95);
}

// -- every missing title voice of a pack

/**
 * Names the editor gives new nodes, from the models (`… title`) and from the tray (`… node`): not
 * titles anyone wants spoken.
 */
const PLACEHOLDER_NAME = /^((Stage|Cover|Menu) (title|node)|Option #\d+)$/;

function isRealTitle(name) {
    return typeof name === 'string' && name.trim().length > 0 && !PLACEHOLDER_NAME.test(name.trim());
}

/**
 * The titles of the diagram that have no audio: cover and stage nodes, menu questions and menu
 * options. Story nodes are left out, their audio being the story itself, and so are nodes still
 * carrying the editor's placeholder name. Each entry is {text, isMissing(), apply(dataUrl)}:
 * isMissing() tells, when its turn comes, whether the title is still in the diagram (isInModel(node)),
 * still reads the same, and still has no audio; otherwise the batch leaves it alone. A menu option is
 * followed as such, not by its place: options removed or moved meanwhile do not shift the others.
 */
export function collectMissingTitleAudio(nodes, {isInModel = () => true} = {}) {
    const missing = [];
    const same = (current, text) => typeof current === 'string' && current.trim() === text;
    (nodes || []).forEach(node => {
        const type = typeof node.getType === 'function' ? node.getType() : undefined;
        if (type === 'cover' || type === 'stage') {
            if (!node.getAudio() && isRealTitle(node.getName())) {
                const text = node.getName().trim();
                missing.push({text, isMissing: () => isInModel(node) && same(node.getName(), text) && !node.getAudio(),
                    apply: (dataUrl) => node.setAudio(dataUrl)});
            }
        } else if (type === 'menu') {
            if (!node.getQuestionAudio() && isRealTitle(node.getName())) {
                const text = node.getName().trim();
                missing.push({text, isMissing: () => isInModel(node) && same(node.getName(), text) && !node.getQuestionAudio(),
                    apply: (dataUrl) => node.setQuestionAudio(dataUrl)});
            }
            node.optionsStages.forEach((option, idx) => {
                if (!node.getOptionAudio(idx) && isRealTitle(node.getOptionName(idx))) {
                    const text = node.getOptionName(idx).trim();
                    // The option's index now, or -1 once it is removed
                    const index = () => node.optionsStages.indexOf(option);
                    missing.push({text,
                        isMissing: () => {
                            const i = index();
                            return i !== -1 && isInModel(node) && same(node.getOptionName(i), text) && !node.getOptionAudio(i);
                        },
                        apply: (dataUrl) => {
                            const i = index();
                            if (i !== -1) {
                                node.setOptionAudio(i, dataUrl);
                            }
                        }});
                }
            });
        }
    });
    return missing;
}

/** A refusal of one title (too long, empty once spoken…): the batch goes on with the others. */
function isRefusedTitle(e) {
    return !!e && (e.code === 'INVALID_REQUEST' || e.status === 400);
}

/**
 * Speaks every entry, one at a time. onProgress(done, total) after each one. A title the server
 * refuses (INVALID_REQUEST) is skipped and counted; any other failure (the server or the voice is
 * down: the next ones would fail the same way) stops the batch, which rejects with that error,
 * error.generated telling how many were done and error.skipped how many were refused. Resolves to
 * {done, skipped, fallbackReason}: fallbackReason is the first reason the server gave for speaking
 * with another voice than the one chosen (the computer voice instead of the online one).
 *
 * The batch takes minutes, and the user keeps editing meanwhile: a title given an audio since the
 * list was made is left as the user set it, and once isCurrent() turns false (another pack opened
 * in the editor) the batch stops instead of writing into a diagram nobody sees.
 */
export async function generateMissingTitleAudio(missing, {speak = speakTitle, onProgress = () => {}, isCurrent = () => true} = {}) {
    let done = 0;
    let skipped = 0;
    let fallbackReason = null;
    for (const entry of missing) {
        try {
            if (!isCurrent()) {
                const stopped = new Error('The editor now shows another pack');
                stopped.code = 'PACK_CHANGED';
                throw stopped;
            }
            if (!entry.isMissing || entry.isMissing()) {
                let speech = null;
                try {
                    speech = await speak(entry.text);
                } catch (e) {
                    if (!isRefusedTitle(e)) {
                        throw e;
                    }
                    console.warn('title refused by the voice, skipped', entry.text, e);
                    skipped++;
                }
                if (!isCurrent()) {
                    const stopped = new Error('The editor now shows another pack');
                    stopped.code = 'PACK_CHANGED';
                    throw stopped;
                }
                if (speech) {
                    if (speech.fallbackReason && !fallbackReason) {
                        fallbackReason = speech.fallbackReason;
                    }
                    if (!entry.isMissing || entry.isMissing()) {
                        entry.apply(speech.dataUrl);
                    }
                }
            }
        } catch (e) {
            e.generated = done;
            e.skipped = skipped;
            throw e;
        }
        done++;
        onProgress(done, missing.length);
    }
    return {done, skipped, fallbackReason};
}
