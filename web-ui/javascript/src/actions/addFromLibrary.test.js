/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Behavioural contract of actionAddFromLibrary, the action that produced the false failure.
 *
 * The point of these tests is a single distinction: a transfer that the backend reported as failed
 * must be shown as a failure, and a transfer whose *monitoring channel* went away must not. Only
 * two collaborators are stubbed — the HTTP service and the toast library — so the action's own
 * control flow is exercised for real.
 */

jest.mock('../services/device');
jest.mock('../services/library');
jest.mock('react-toastify', () => ({
    toast: Object.assign(jest.fn(() => 'toast-id'), {
        update: jest.fn(),
        error: jest.fn(),
        dismiss: jest.fn(),
        TYPE: { INFO: 'info', SUCCESS: 'success', ERROR: 'error', WARNING: 'warning' }
    })
}));

const { toast } = require('react-toastify');
const deviceService = require('../services/device');
const libraryService = require('../services/library');
const { actionAddFromLibrary, actionAddToLibrary, actionRefreshDevice, TRANSFER_RECONCILE_INTERVAL_MS, TRANSFER_RECONCILE_MAX_FAILURES } = require('./index');
const { SUBSCRIBED, DEFERRED, CHANNEL_OPEN } = require('../services/eventBusChannel');

/** Translation stub: returns the key, so assertions pin the message identity, not its wording. */
const t = key => key;

/** Every fake channel of the current test, so that afterEach can settle what is left pending. */
const channels = [];

/**
 * Minimal stand-in for the event bus channel, with the same contract as the real one: a
 * subscription made while closed is recorded (and reported DEFERRED), and delivered once open.
 */
function fakeChannel({ open = true } = {}) {
    const handlers = new Map();
    const listeners = new Set();
    let isOpen = open;
    const channel = {
        isOpen: () => isOpen,
        subscribe: jest.fn((address, callback) => {
            handlers.set(address, callback);
            return isOpen ? SUBSCRIBED : DEFERRED;
        }),
        unsubscribe: jest.fn(address => handlers.delete(address)),
        addStateListener: jest.fn(listener => {
            listeners.add(listener);
            return () => listeners.delete(listener);
        }),
        emit: (address, body) => {
            const handler = handlers.get(address);
            if (!handler || !isOpen) {
                throw new Error('no handler registered for ' + address);
            }
            handler(null, { body });
        },
        /** The socket comes back: listeners hear about it, as from the real channel. */
        reopen: () => {
            isOpen = true;
            listeners.forEach(listener => listener(CHANNEL_OPEN));
        },
        /** Test cleanup: ends whatever transfer is still followed (its polling, its pack marked as in transfer). */
        settlePending: () => {
            handlers.forEach((handler, address) => {
                if (address.endsWith('.done')) {
                    handler(null, { body: { success: false } });
                }
            });
        },
        addressCount: () => handlers.size,
        listenerCount: () => listeners.size
    };
    channels.push(channel);
    return channel;
}

const flush = () => new Promise(resolve => setImmediate(resolve));

/** Renders whatever was handed to toast.update into something assertable. */
function lastRenderKey() {
    const calls = toast.update.mock.calls;
    for (let i = calls.length - 1; i >= 0; i--) {
        const render = calls[i][1] && calls[i][1].render;
        if (typeof render === 'string') {
            return render;
        }
        if (render && render.props) {
            // IssueReportToast wraps the message in a `content` element whose child is the key.
            const content = render.props.content;
            if (content && content.props && content.props.children) {
                return content.props.children;
            }
        }
    }
    return null;
}

/** The props handed to IssueReportToast on the last toast.update call, if any. */
function lastRenderProps() {
    const calls = toast.update.mock.calls;
    for (let i = calls.length - 1; i >= 0; i--) {
        const render = calls[i][1] && calls[i][1].render;
        if (render && render.props) {
            return render.props;
        }
    }
    return null;
}

async function runAddFromLibrary(channel) {
    const dispatch = jest.fn(action => (typeof action === 'function' ? action(dispatch) : action));
    await actionAddFromLibrary('uuid-1', 'pack.zip', 'fs', 'fs', { channel }, t)(dispatch);
    await flush();
    return dispatch;
}

async function runAddToLibrary(channel) {
    const dispatch = jest.fn(action => (typeof action === 'function' ? action(dispatch) : action));
    await actionAddToLibrary('uuid-1', 'fs', { channel }, t)(dispatch);
    await flush();
    return dispatch;
}

/** Whether the device mutex can be taken right now: a second action either runs or says "busy". */
async function mutexIsFree() {
    deviceService.addFromLibrary.mockClear();
    toast.error.mockClear();
    deviceService.addFromLibrary.mockRejectedValueOnce(new Error('probe'));
    await actionAddFromLibrary('uuid-2', 'probe.zip', 'fs', 'fs', { channel: fakeChannel() }, t)(jest.fn());
    await flush();
    const ran = deviceService.addFromLibrary.mock.calls.length === 1;
    const busy = toast.error.mock.calls.some(call => call[0] === 'toasts.device.busy');
    expect(ran).toBe(!busy);
    return ran;
}

/** Whether some toast.update call rendered this key (plain string renders only). */
function rendered(key) {
    return toast.update.mock.calls.some(call => call[1] && call[1].render === key);
}

/** Lets a chain of already-resolved promises run, under fake timers. */
const ticks = async () => { for (let i = 0; i < 20; i++) { await Promise.resolve(); } };

/** A transfer still running, as the registry reports it. */
const RUNNING = { transferId: 'tid', state: 'RUNNING', done: false, success: false, progress: 0.5, total: 10, transferred: 5 };

beforeEach(() => {
    jest.clearAllMocks();
    channels.length = 0;
    deviceService.addFromLibrary.mockReset();
    deviceService.addToLibrary.mockReset();
    deviceService.fetchTransfer.mockReset();
    deviceService.fetchTransfer.mockResolvedValue(RUNNING);
    deviceService.fetchDeviceInfos.mockResolvedValue({ plugged: true, driver: 'fs' });
    deviceService.fetchDevicePacks.mockResolvedValue([]);
    libraryService.fetchLibraryInfos.mockResolvedValue({});
    libraryService.fetchLibraryPacks.mockResolvedValue([]);
});

afterEach(async () => {
    // A transfer left unsettled keeps polling, and keeps its pack marked as in transfer.
    channels.forEach(channel => channel.settlePending());
    await flush();
});

describe('actionAddFromLibrary', () => {

    it('E6: a backend failure is still reported as a failed transfer', async () => {
        deviceService.addFromLibrary.mockRejectedValue(new Error('Internal Server Error: boom'));

        await runAddFromLibrary(fakeChannel({ open: true }));

        expect(lastRenderKey()).toBe('toasts.device.addingFailed');
    });

    it('E6b: a transfer the backend reports as unsuccessful is reported as a failure', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        channel.emit('storyteller.transfer.tid.done', { success: false });

        expect(lastRenderKey()).toBe('toasts.device.addingFailed');
    });


    it('C6d-6c: a request failure hands the backend explanation to the toast', async () => {
        // The driver refuses some uploads with a precise reason — a pre-existing destination folder,
        // for one. That text already arrives in the Error; what was missing is anything asking for it
        // to be shown. `showDetails` is that request, and it is opt-in: every other failure toast in
        // the application keeps its generic wording.
        const channel = fakeChannel({ open: true });
        const failure = new Error('Internal Server Error: A pack content folder already exists');
        deviceService.addFromLibrary.mockRejectedValue(failure);

        await runAddFromLibrary(channel);

        const props = lastRenderProps();
        expect(lastRenderKey()).toBe('toasts.device.addingFailed');
        expect(props.error).toBe(failure);
        expect(props.showDetails).toBe(true);
    });

    it('C6d-6c: a transfer the backend reports as unsuccessful shows no invented detail', async () => {
        // This branch has no Error to draw on: the backend simply published success=false. Asking for
        // details here would put an empty block under the message.
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        channel.emit('storyteller.transfer.tid.done', { success: false });

        const props = lastRenderProps();
        expect(lastRenderKey()).toBe('toasts.device.addingFailed');
        expect(props.error).toBeUndefined();
        expect(props.showDetails).toBeUndefined();
    });

    it('a transfer the backend reports as failed with a reason shows that reason', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        channel.emit('storyteller.transfer.tid.done', { success: false, error: 'NOT_ENOUGH_SPACE', message: 'Not enough free space on the device' });

        const props = lastRenderProps();
        expect(lastRenderKey()).toBe('toasts.device.addingFailed');
        expect(props.error.message).toBe('Not enough free space on the device');
        expect(props.showDetails).toBe(true);
    });

    it('C6d-6c: a successful transfer leaves no error detail behind', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        channel.emit('storyteller.transfer.tid.done', { success: true });

        const props = lastRenderProps();
        expect(props).toBeNull();
    });

    it('E5: losing the channel must NOT be reported as a failed transfer, nor as lost tracking', async () => {
        // The channel is down when the action tries to subscribe. This is precisely the field
        // scenario: the POST already succeeded, so the backend is writing the pack right now. The
        // registry is still polled, so the transfer is followed: the "adding" toast stays.
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(fakeChannel({ open: false }));

        expect(toast).toHaveBeenCalledWith('toasts.device.adding', expect.anything());
        expect(lastRenderKey()).not.toBe('toasts.device.addingFailed');
        expect(rendered('toasts.device.trackingLost')).toBe(false);
        expect(deviceService.fetchTransfer).toHaveBeenCalledWith('tid');
    });

    it('E5b: the transfer request is still issued even though monitoring is unavailable', async () => {
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(fakeChannel({ open: false }));

        expect(deviceService.addFromLibrary).toHaveBeenCalledWith('uuid-1', 'pack.zip');
    });

    it('a successful transfer is reported as success', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        channel.emit('storyteller.transfer.tid.done', { success: true });

        expect(lastRenderKey()).toBe('toasts.device.added');
    });

    it('E7: both subscriptions are released once the transfer completes', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        expect(channel.addressCount()).toBe(2);

        channel.emit('storyteller.transfer.tid.done', { success: true });

        expect(channel.unsubscribe).toHaveBeenCalledTimes(2);
        expect(channel.addressCount()).toBe(0);
    });

    it('subscribes to progress and done under the transfer id returned by the backend', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);

        const addresses = channel.subscribe.mock.calls.map(call => call[0]);
        expect(addresses).toEqual([
            'storyteller.transfer.tid.progress',
            'storyteller.transfer.tid.done'
        ]);
    });
});

describe('actionAddFromLibrary: reconciliation with the transfer registry', () => {

    it('a verdict already known when subscribing settles the transfer and frees the mutex', async () => {
        // The transfer ended before the subscription reached the wire: its done event is gone.
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
        deviceService.fetchTransfer.mockResolvedValue({ transferId: 'tid', state: 'SUCCEEDED', done: true, success: true, progress: 1 });

        await runAddFromLibrary(channel);

        expect(deviceService.fetchTransfer).toHaveBeenCalledWith('tid');
        expect(lastRenderKey()).toBe('toasts.device.added');
        expect(channel.addressCount()).toBe(0);
        expect(channel.listenerCount()).toBe(0);
        expect(await mutexIsFree()).toBe(true);
    });

    it('a failure already known when subscribing shows the backend reason', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
        deviceService.fetchTransfer.mockResolvedValue({ transferId: 'tid', state: 'FAILED', done: true, success: false, error: 'NOT_ENOUGH_SPACE', message: 'Not enough free space on the device' });

        await runAddFromLibrary(channel);

        const props = lastRenderProps();
        expect(lastRenderKey()).toBe('toasts.device.addingFailed');
        expect(props.error.message).toBe('Not enough free space on the device');
        expect(props.showDetails).toBe(true);
        expect(await mutexIsFree()).toBe(true);
    });

    it('a verdict reported by both the event bus and the registry is handled once', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
        let answer;
        deviceService.fetchTransfer.mockReturnValue(new Promise(resolve => { answer = resolve; }));

        const dispatch = await runAddFromLibrary(channel);
        channel.emit('storyteller.transfer.tid.done', { success: true });
        answer({ transferId: 'tid', state: 'SUCCEEDED', done: true, success: true });
        await flush();

        const successes = toast.update.mock.calls.filter(call => call[1] && call[1].render === 'toasts.device.added');
        expect(successes).toHaveLength(1);
        expect(channel.unsubscribe).toHaveBeenCalledTimes(2);
        // One refresh of the device, not two
        const refreshes = dispatch.mock.calls.filter(call => typeof call[0] === 'function');
        expect(refreshes).toHaveLength(1);
    });

    it('a lost channel does not hold the mutex, and the verdict read on reconnection settles the transfer', async () => {
        const channel = fakeChannel({ open: false });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        const dispatch = await runAddFromLibrary(channel);
        expect(rendered('toasts.device.trackingLost')).toBe(false);

        // Still running on the backend, which queues device operations itself: the UI stays usable.
        expect(await mutexIsFree()).toBe(true);

        // The verdict was published while the socket was down; the registry still has it.
        deviceService.fetchTransfer.mockResolvedValue({ transferId: 'tid', state: 'SUCCEEDED', done: true, success: true });
        channel.reopen();
        await flush();

        expect(lastRenderKey()).toBe('toasts.device.added');
        expect(channel.listenerCount()).toBe(0);
        // The device is re-read at the end
        expect(dispatch.mock.calls.some(call => typeof call[0] === 'function')).toBe(true);
    }, 10000);

    it('releases the mutex as soon as the backend accepts the transfer', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);

        // The transfer is still being followed...
        expect(channel.addressCount()).toBe(2);
        // ...yet another device action is not refused as "busy".
        expect(await mutexIsFree()).toBe(true);

        // And the verdict, when it comes, is still handled.
        channel.emit('storyteller.transfer.tid.done', { success: true });
        expect(lastRenderKey()).toBe('toasts.device.added');
    });

    it('a device refresh during a transfer (e.g. on reconnection) is not refused as busy', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
        await runAddFromLibrary(channel);
        toast.error.mockClear();
        deviceService.fetchDeviceInfos.mockClear();

        await actionRefreshDevice(t)(jest.fn());

        expect(toast.error).not.toHaveBeenCalledWith('toasts.device.busy');
        expect(deviceService.fetchDeviceInfos).toHaveBeenCalled();
    });

    it('the same pack dropped twice while its transfer is followed is sent once', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        toast.error.mockClear();
        await runAddFromLibrary(channel);

        expect(deviceService.addFromLibrary).toHaveBeenCalledTimes(1);
        expect(toast.error).toHaveBeenCalledWith('toasts.device.busy');

        // Once settled, the pack can be sent again.
        channel.emit('storyteller.transfer.tid.done', { success: false });
        await runAddFromLibrary(channel);
        expect(deviceService.addFromLibrary).toHaveBeenCalledTimes(2);
    });

    it('an unsettled transfer is re-read periodically until its verdict is known', async () => {
        jest.useFakeTimers();
        try {
            const channel = fakeChannel({ open: true });
            deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
            const dispatch = jest.fn(action => (typeof action === 'function' ? action(dispatch) : action));

            actionAddFromLibrary('uuid-1', 'pack.zip', 'fs', 'fs', { channel }, t)(dispatch);
            await ticks();
            expect(deviceService.fetchTransfer).toHaveBeenCalledTimes(1);
            expect(toast.update).toHaveBeenCalledWith('toast-id', { progress: 0.5, autoClose: false });

            deviceService.fetchTransfer.mockResolvedValue({ transferId: 'tid', state: 'SUCCEEDED', done: true, success: true });
            jest.advanceTimersByTime(TRANSFER_RECONCILE_INTERVAL_MS);
            await ticks();

            expect(deviceService.fetchTransfer).toHaveBeenCalledTimes(2);
            expect(lastRenderKey()).toBe('toasts.device.added');
            expect(channel.addressCount()).toBe(0);

            // Settled: the interval is gone
            jest.advanceTimersByTime(TRANSFER_RECONCILE_INTERVAL_MS * 3);
            await ticks();
            expect(deviceService.fetchTransfer).toHaveBeenCalledTimes(2);
            // Let the device refresh run and release the mutex
            jest.advanceTimersByTime(200);
            await ticks();
        } finally {
            jest.useRealTimers();
        }
    });

    it('any 404 from the registry settles the transfer as unknown, even without an error code', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
        deviceService.fetchTransfer.mockRejectedValue(Object.assign(new Error('Not Found'), { status: 404 }));

        const dispatch = await runAddFromLibrary(channel);

        expect(lastRenderKey()).toBe('toasts.device.trackingLost');
        expect(channel.addressCount()).toBe(0);
        expect(dispatch.mock.calls.some(call => typeof call[0] === 'function')).toBe(true);
    });

    it('gives up after too many failed registry reads in a row, and reports the outcome as unknown', async () => {
        jest.useFakeTimers();
        try {
            const channel = fakeChannel({ open: true });
            deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
            deviceService.fetchTransfer.mockRejectedValue(Object.assign(new Error('Bad Gateway'), { status: 502 }));
            const dispatch = jest.fn(action => (typeof action === 'function' ? action(dispatch) : action));

            actionAddFromLibrary('uuid-1', 'pack.zip', 'fs', 'fs', { channel }, t)(dispatch);
            await ticks();
            for (let i = 1; i < TRANSFER_RECONCILE_MAX_FAILURES - 1; i++) {
                jest.advanceTimersByTime(TRANSFER_RECONCILE_INTERVAL_MS);
                await ticks();
            }
            expect(deviceService.fetchTransfer).toHaveBeenCalledTimes(TRANSFER_RECONCILE_MAX_FAILURES - 1);
            expect(rendered('toasts.device.trackingLost')).toBe(false);
            expect(channel.addressCount()).toBe(2);

            jest.advanceTimersByTime(TRANSFER_RECONCILE_INTERVAL_MS);
            await ticks();

            expect(deviceService.fetchTransfer).toHaveBeenCalledTimes(TRANSFER_RECONCILE_MAX_FAILURES);
            expect(lastRenderKey()).toBe('toasts.device.trackingLost');
            expect(channel.addressCount()).toBe(0);
            expect(channel.listenerCount()).toBe(0);
            expect(dispatch.mock.calls.some(call => typeof call[0] === 'function')).toBe(true);

            // No more polling
            jest.advanceTimersByTime(TRANSFER_RECONCILE_INTERVAL_MS * 3);
            await ticks();
            expect(deviceService.fetchTransfer).toHaveBeenCalledTimes(TRANSFER_RECONCILE_MAX_FAILURES);
            jest.advanceTimersByTime(200);
            await ticks();
        } finally {
            jest.useRealTimers();
        }
    });

    it('a successful registry read resets the count of failed reads', async () => {
        jest.useFakeTimers();
        try {
            const channel = fakeChannel({ open: true });
            deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
            const badGateway = Object.assign(new Error('Bad Gateway'), { status: 502 });
            // Almost the limit, one good read, then almost the limit again
            for (let i = 0; i < TRANSFER_RECONCILE_MAX_FAILURES - 1; i++) {
                deviceService.fetchTransfer.mockRejectedValueOnce(badGateway);
            }
            deviceService.fetchTransfer.mockResolvedValueOnce(RUNNING);
            for (let i = 0; i < TRANSFER_RECONCILE_MAX_FAILURES - 1; i++) {
                deviceService.fetchTransfer.mockRejectedValueOnce(badGateway);
            }
            const dispatch = jest.fn(action => (typeof action === 'function' ? action(dispatch) : action));

            actionAddFromLibrary('uuid-1', 'pack.zip', 'fs', 'fs', { channel }, t)(dispatch);
            await ticks();
            for (let i = 1; i < 2 * TRANSFER_RECONCILE_MAX_FAILURES - 1; i++) {
                jest.advanceTimersByTime(TRANSFER_RECONCILE_INTERVAL_MS);
                await ticks();
            }

            expect(deviceService.fetchTransfer).toHaveBeenCalledTimes(2 * TRANSFER_RECONCILE_MAX_FAILURES - 1);
            expect(rendered('toasts.device.trackingLost')).toBe(false);
            expect(channel.addressCount()).toBe(2);
        } finally {
            jest.useRealTimers();
        }
    });

    it('does not pile registry reads up while one is still pending', async () => {
        jest.useFakeTimers();
        try {
            const channel = fakeChannel({ open: true });
            deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
            let answer;
            deviceService.fetchTransfer.mockReturnValue(new Promise(resolve => { answer = resolve; }));
            const dispatch = jest.fn(action => (typeof action === 'function' ? action(dispatch) : action));

            actionAddFromLibrary('uuid-1', 'pack.zip', 'fs', 'fs', { channel }, t)(dispatch);
            await ticks();
            expect(deviceService.fetchTransfer).toHaveBeenCalledTimes(1);

            // Ticks and a reconnection while the first read hangs: no new request
            jest.advanceTimersByTime(TRANSFER_RECONCILE_INTERVAL_MS * 5);
            channel.reopen();
            await ticks();
            expect(deviceService.fetchTransfer).toHaveBeenCalledTimes(1);

            // Once it answers, the next tick reads again
            answer(RUNNING);
            await ticks();
            deviceService.fetchTransfer.mockResolvedValue(RUNNING);
            jest.advanceTimersByTime(TRANSFER_RECONCILE_INTERVAL_MS);
            await ticks();
            expect(deviceService.fetchTransfer).toHaveBeenCalledTimes(2);
        } finally {
            jest.useRealTimers();
        }
    });

    it('never shows the progress going backwards', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
        // The registry answers 0.5 right after subscribing

        await runAddFromLibrary(channel);
        channel.emit('storyteller.transfer.tid.progress', { progress: 0.7 });
        // A late, older value from either source
        channel.emit('storyteller.transfer.tid.progress', { progress: 0.6 });

        const shown = toast.update.mock.calls
            .map(call => call[1] && call[1].progress)
            .filter(progress => typeof progress === 'number');
        expect(shown).toEqual([0.5, 0.7, 0.7]);
    });

    it('a transfer the backend no longer knows is not reported as failed, and frees the mutex', async () => {
        const channel = fakeChannel({ open: false });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
        const notFound = Object.assign(new Error('Not Found: No transfer with id tid'), { status: 404, code: 'TRANSFER_NOT_FOUND' });
        deviceService.fetchTransfer.mockRejectedValue(notFound);

        await runAddFromLibrary(channel);

        expect(lastRenderKey()).toBe('toasts.device.trackingLost');
        expect(await mutexIsFree()).toBe(true);
    });

    it('a registry read that fails for another reason is not a verdict', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });
        deviceService.fetchTransfer.mockRejectedValue(Object.assign(new Error('Bad Gateway'), { status: 502 }));

        await runAddFromLibrary(channel);

        expect(channel.addressCount()).toBe(2);
        channel.emit('storyteller.transfer.tid.done', { success: true });
        expect(lastRenderKey()).toBe('toasts.device.added');
    });

    it('ignores channel callbacks that carry an error or no message', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddFromLibrary(channel);
        const done = channel.subscribe.mock.calls.find(call => call[0].endsWith('.done'))[1];
        const progress = channel.subscribe.mock.calls.find(call => call[0].endsWith('.progress'))[1];

        expect(() => progress(new Error('boom'), undefined)).not.toThrow();
        expect(() => done(new Error('boom'), undefined)).not.toThrow();
        expect(() => done(null, undefined)).not.toThrow();
        expect(channel.addressCount()).toBe(2);
    });
});

describe('actionAddToLibrary', () => {

    it('a failed extraction shows the backend reason', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addToLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddToLibrary(channel);
        channel.emit('storyteller.transfer.tid.done', { success: false, error: 'READ_FAILED', message: 'Could not read the pack' });

        const props = lastRenderProps();
        expect(lastRenderKey()).toBe('toasts.library.addingFailed');
        expect(props.error.message).toBe('Could not read the pack');
        expect(props.showDetails).toBe(true);
    });

    it('a verdict already known when subscribing settles the extraction and frees the mutex', async () => {
        const channel = fakeChannel({ open: true });
        deviceService.addToLibrary.mockResolvedValue({ transferId: 'tid' });
        deviceService.fetchTransfer.mockResolvedValue({ transferId: 'tid', state: 'SUCCEEDED', done: true, success: true });

        await runAddToLibrary(channel);

        expect(lastRenderKey()).toBe('toasts.library.added');
        expect(libraryService.fetchLibraryInfos).toHaveBeenCalled();
        expect(await mutexIsFree()).toBe(true);
    });

    it('a lost channel neither claims lost tracking nor holds the mutex during the extraction', async () => {
        const channel = fakeChannel({ open: false });
        deviceService.addToLibrary.mockResolvedValue({ transferId: 'tid' });

        await runAddToLibrary(channel);

        expect(rendered('toasts.library.trackingLost')).toBe(false);
        expect(channel.addressCount()).toBe(2);
        expect(await mutexIsFree()).toBe(true);
    }, 10000);
});
