/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Sending several library packs to the device, one after the other. What sending one pack means
 * (convert or not, which file, which question to ask) stays the library's (PackLibrary.sendToDevice):
 * this only decides which packs are sent, runs them in turn, reports progress and sums up.
 *
 * An outcome, for one pack, is {status: 'sent' | 'skipped' | 'failed', reason?, message?}.
 */

/** Why a pack of the selection was not sent. */
export const REASONS = ['official', 'onDevice', 'cancelled', 'declined', 'nothing', 'space',
    'conversion', 'busy', 'incompatible', 'transfer', 'unknown', 'error', 'noDevice'];

/** How often, and how long apart, a transfer refused because the device was busy is tried again. */
export const BUSY_RETRIES = 5;
export const BUSY_RETRY_DELAY_MS = 1500;

const titleOf = (group) => {
    const pack = (group && group.packs && group.packs[0]) || {};
    return pack.title && pack.title !== 'MISSING_PACK_TITLE' ? pack.title : group.uuid;
};

/**
 * Splits a selection into the packs to send and those skipped with their reason: official packs
 * (never transferable) and packs already on the device (uuids compared regardless of case).
 */
export function planSendQueue(groups, devicePacks) {
    const onDevice = new Set((devicePacks || []).map(p => String(p.uuid).toLowerCase()));
    const toSend = [];
    const skipped = [];
    (groups || []).forEach(group => {
        const official = (group.packs || []).some(p => p.official);
        if (official) {
            skipped.push({uuid: group.uuid, title: titleOf(group), status: 'skipped', reason: 'official'});
        } else if (onDevice.has(String(group.uuid).toLowerCase())) {
            skipped.push({uuid: group.uuid, title: titleOf(group), status: 'skipped', reason: 'onDevice'});
        } else {
            toSend.push(group);
        }
    });
    return {toSend, skipped};
}

/** Resolves after `ms`, through setTimeout (and so through jest's fake timers). */
export const wait = (ms) => new Promise(resolve => setTimeout(resolve, ms));

/**
 * Runs attempt() again while it answers {reason: 'busy'}: the device lock is held for a moment by
 * the refresh that follows each transfer, and the next pack must not fail for that. Cancelled while
 * it waits to try again, the pack was not sent because of the cancellation: it is 'cancelled'.
 */
export async function retryWhileBusy(attempt, {retries = BUSY_RETRIES, delayMs = BUSY_RETRY_DELAY_MS, isCancelled} = {}) {
    const cancelled = () => !!(isCancelled && isCancelled());
    const busy = (outcome) => !!(outcome && outcome.reason === 'busy');
    let outcome = await attempt();
    for (let i = 0; i < retries && busy(outcome) && !cancelled(); i++) {
        await wait(delayMs);
        if (cancelled()) {
            break;
        }
        outcome = await attempt();
    }
    return busy(outcome) && cancelled() ? {status: 'skipped', reason: 'cancelled'} : outcome;
}

/**
 * A queue over `groups`. sendOne(group, {onPhase, onProgress, answers}) sends one pack and resolves
 * with its outcome; `answers` is shared by the whole run, so that a question answered for one pack
 * (enriched format, which file to send) is not asked again for the next ones.
 *
 * onUpdate(state) is called on every change, state being {running, index, total, title, phase,
 * progress, overall, results}; `overall` (0..1) counts the packs done plus the current one's part.
 *
 * Returns {run(): Promise<summary>, cancel()}. cancel() lets the current pack finish and skips the
 * rest. The summary is {sent, skipped, failed}, lists of {uuid, title, status, reason, message}.
 */
export function createSendQueue({groups, sendOne, onUpdate = () => {}, skipped = []}) {
    let cancelled = false;
    const answers = {};
    const results = [...skipped];
    const total = groups.length;
    let state = {running: false, index: 0, total, title: null, phase: null, progress: 0, overall: 0, results};

    const update = (changes) => {
        state = {...state, ...changes};
        state.overall = total === 0 ? 1 : Math.min((state.index + (state.running ? state.progress : 0)) / total, 1);
        onUpdate(state);
    };

    // The same callbacks for every pack: they act on the pack under way
    const packOptions = {
        answers,
        onPhase: phase => update({phase}),
        onProgress: progress => {
            if (typeof progress === 'number' && !isNaN(progress)) {
                update({progress: Math.max(0, Math.min(progress, 1))});
            }
        },
        isCancelled: () => cancelled
    };

    const run = async () => {
        update({running: true});
        for (let i = 0; i < total; i++) {
            const group = groups[i];
            const base = {uuid: group.uuid, title: titleOf(group)};
            if (cancelled) {
                results.push({...base, status: 'skipped', reason: 'cancelled'});
                continue;
            }
            update({index: i, title: base.title, phase: 'preparing', progress: 0});
            let outcome;
            try {
                outcome = await sendOne(group, packOptions);
            } catch (e) {
                console.error('failed to send pack ' + group.uuid, e);
                outcome = {status: 'failed', reason: 'error', message: e && e.message};
            }
            if (!outcome || !outcome.status) {
                outcome = {status: 'failed', reason: 'unknown'};
            }
            results.push({...base, ...outcome});
            update({index: i + 1, progress: 0});
        }
        update({running: false, phase: null, title: null, index: total});
        return summarize(results);
    };

    return {
        run,
        cancel: () => {
            cancelled = true;
            update({cancelling: true});
        },
        isCancelled: () => cancelled
    };
}

export function summarize(results) {
    return {
        sent: results.filter(r => r.status === 'sent'),
        skipped: results.filter(r => r.status === 'skipped'),
        failed: results.filter(r => r.status !== 'sent' && r.status !== 'skipped')
    };
}
