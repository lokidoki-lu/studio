/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {planSendQueue, createSendQueue, retryWhileBusy, summarize, BUSY_RETRY_DELAY_MS, BUSY_RETRIES} from './sendQueue';

const group = (uuid, title, extra = {}) => ({uuid, packs: [{uuid, title, path: uuid + '.zip', format: 'archive', ...extra}]});

/** Lets pending promise callbacks run, with fake timers in place. */
const settle = async (times = 5) => {
    for (let i = 0; i < times; i++) {
        await Promise.resolve();
    }
};

describe('planSendQueue', () => {
    it('skips official packs and those already on the device (whatever the case), and keeps the order', () => {
        const groups = [group('a', 'A'), group('OFF', 'Officiel', {official: true}), group('B', 'B'), group('c', 'C')];
        const {toSend, skipped} = planSendQueue(groups, [{uuid: 'b'}]);
        expect(toSend.map(g => g.uuid)).toEqual(['a', 'c']);
        expect(skipped).toEqual([
            {uuid: 'OFF', title: 'Officiel', status: 'skipped', reason: 'official'},
            {uuid: 'B', title: 'B', status: 'skipped', reason: 'onDevice'}
        ]);
    });

    it('accepts a missing device list', () => {
        expect(planSendQueue([group('a', 'A')], undefined).toSend).toHaveLength(1);
    });
});

describe('createSendQueue', () => {
    beforeEach(() => jest.useFakeTimers());
    afterEach(() => jest.useRealTimers());

    it('sends one pack at a time, reports n/N and the current progress, and sums up', async () => {
        const pending = [];
        const sendOne = jest.fn((g, options) => new Promise(resolve => pending.push({g, options, resolve})));
        const updates = [];
        const queue = createSendQueue({
            groups: [group('a', 'A'), group('b', 'B'), group('c', 'C')],
            skipped: [{uuid: 'x', title: 'X', status: 'skipped', reason: 'official'}],
            sendOne,
            onUpdate: state => updates.push(state)
        });
        const done = queue.run();
        await settle();
        // Only the first one is under way
        expect(sendOne).toHaveBeenCalledTimes(1);
        pending[0].options.onPhase('transferring');
        pending[0].options.onProgress(0.5);
        let last = updates[updates.length - 1];
        expect(last).toMatchObject({index: 0, total: 3, title: 'A', phase: 'transferring', progress: 0.5});
        expect(last.overall).toBeCloseTo(0.5 / 3);

        pending[0].resolve({status: 'sent'});
        await settle();
        expect(sendOne).toHaveBeenCalledTimes(2);
        expect(updates[updates.length - 1]).toMatchObject({index: 1, title: 'B', progress: 0});
        pending[1].resolve({status: 'failed', reason: 'conversion'});
        await settle();
        pending[2].resolve({status: 'skipped', reason: 'declined'});
        const summary = await done;

        expect(summary.sent.map(r => r.uuid)).toEqual(['a']);
        expect(summary.failed).toEqual([{uuid: 'b', title: 'B', status: 'failed', reason: 'conversion'}]);
        expect(summary.skipped.map(r => r.reason)).toEqual(['official', 'declined']);
        last = updates[updates.length - 1];
        expect(last.running).toBe(false);
        expect(last.overall).toBe(1);
    });

    it('shares the answers between the packs, so that a question is asked once', async () => {
        const seen = [];
        const queue = createSendQueue({
            groups: [group('a', 'A'), group('b', 'B')],
            sendOne: (g, options) => {
                seen.push({...options.answers});
                options.answers.enriched = true;
                return Promise.resolve({status: 'sent'});
            }
        });
        await queue.run();
        expect(seen).toEqual([{}, {enriched: true}]);
    });

    it('cancelling lets the current pack finish and skips the rest', async () => {
        let finish;
        const sendOne = jest.fn(() => new Promise(resolve => { finish = resolve; }));
        const queue = createSendQueue({groups: [group('a', 'A'), group('b', 'B'), group('c', 'C')], sendOne});
        const done = queue.run();
        await settle();
        queue.cancel();
        finish({status: 'sent'});
        const summary = await done;
        expect(sendOne).toHaveBeenCalledTimes(1);
        expect(summary.sent.map(r => r.uuid)).toEqual(['a']);
        expect(summary.skipped).toEqual([
            {uuid: 'b', title: 'B', status: 'skipped', reason: 'cancelled'},
            {uuid: 'c', title: 'C', status: 'skipped', reason: 'cancelled'}
        ]);
    });

    it('a pack that throws, or answers nothing, is a failure and the queue goes on', async () => {
        const sendOne = jest.fn()
            .mockImplementationOnce(() => Promise.reject(new Error('boom')))
            .mockImplementationOnce(() => Promise.resolve(undefined))
            .mockImplementationOnce(() => Promise.resolve({status: 'sent'}));
        const queue = createSendQueue({groups: [group('a', 'A'), group('b', 'B'), group('c', 'C')], sendOne});
        const summary = await queue.run();
        expect(summary.failed.map(r => [r.uuid, r.reason, r.message])).toEqual([['a', 'error', 'boom'], ['b', 'unknown', undefined]]);
        expect(summary.sent.map(r => r.uuid)).toEqual(['c']);
    });
});

describe('retryWhileBusy', () => {
    beforeEach(() => jest.useFakeTimers());
    afterEach(() => jest.useRealTimers());

    it('tries again after a delay while the device is busy', async () => {
        const attempt = jest.fn()
            .mockResolvedValueOnce({status: 'failed', reason: 'busy'})
            .mockResolvedValueOnce({status: 'failed', reason: 'busy'})
            .mockResolvedValueOnce({status: 'sent'});
        const result = retryWhileBusy(attempt);
        await settle();
        expect(attempt).toHaveBeenCalledTimes(1);
        jest.advanceTimersByTime(BUSY_RETRY_DELAY_MS - 1);
        await settle();
        expect(attempt).toHaveBeenCalledTimes(1);
        jest.advanceTimersByTime(1);
        await settle();
        expect(attempt).toHaveBeenCalledTimes(2);
        jest.advanceTimersByTime(BUSY_RETRY_DELAY_MS);
        await settle();
        await expect(result).resolves.toEqual({status: 'sent'});
    });

    it('gives up after a few attempts, and at once when cancelled', async () => {
        const attempt = jest.fn().mockResolvedValue({status: 'failed', reason: 'busy'});
        const result = retryWhileBusy(attempt);
        for (let i = 0; i < BUSY_RETRIES; i++) {
            await settle();
            jest.advanceTimersByTime(BUSY_RETRY_DELAY_MS);
        }
        await settle();
        await expect(result).resolves.toEqual({status: 'failed', reason: 'busy'});
        expect(attempt).toHaveBeenCalledTimes(BUSY_RETRIES + 1);

        const once = jest.fn().mockResolvedValue({status: 'failed', reason: 'busy'});
        await expect(retryWhileBusy(once, {isCancelled: () => true})).resolves.toEqual({status: 'skipped', reason: 'cancelled'});
        expect(once).toHaveBeenCalledTimes(1);
    });

    it('B4: cancelled while waiting to try again, the pack is cancelled (not failed as busy), and not tried again', async () => {
        let cancelled = false;
        const attempt = jest.fn().mockResolvedValue({status: 'failed', reason: 'busy'});
        const result = retryWhileBusy(attempt, {isCancelled: () => cancelled});
        await settle();
        expect(attempt).toHaveBeenCalledTimes(1);
        cancelled = true;
        jest.advanceTimersByTime(BUSY_RETRY_DELAY_MS);
        await settle();
        await expect(result).resolves.toEqual({status: 'skipped', reason: 'cancelled'});
        expect(attempt).toHaveBeenCalledTimes(1);
    });

    it('B4: a cancellation does not change the outcome of an attempt that went through', async () => {
        let cancelled = false;
        const attempt = jest.fn()
            .mockResolvedValueOnce({status: 'failed', reason: 'busy'})
            .mockImplementationOnce(() => { cancelled = true; return Promise.resolve({status: 'sent'}); });
        const result = retryWhileBusy(attempt, {isCancelled: () => cancelled});
        await settle();
        jest.advanceTimersByTime(BUSY_RETRY_DELAY_MS);
        await settle();
        await expect(result).resolves.toEqual({status: 'sent'});
    });

    it('does not retry any other failure', async () => {
        const attempt = jest.fn().mockResolvedValue({status: 'failed', reason: 'transfer'});
        await expect(retryWhileBusy(attempt)).resolves.toEqual({status: 'failed', reason: 'transfer'});
        expect(attempt).toHaveBeenCalledTimes(1);
    });
});

describe('summarize', () => {
    it('puts every status that is neither sent nor skipped among the failures', () => {
        const summary = summarize([{status: 'sent'}, {status: 'skipped'}, {status: 'failed'}, {status: 'odd'}]);
        expect([summary.sent.length, summary.skipped.length, summary.failed.length]).toEqual([1, 1, 2]);
    });
});
