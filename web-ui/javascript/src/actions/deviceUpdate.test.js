/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * actionAddFromLibrary in 'update' mode (in-place replacement), and the repair offer of failure toasts.
 */

jest.mock('../services/device');
jest.mock('../services/library');
jest.mock('../services/deviceRepair');
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
const repairService = require('../services/deviceRepair');
const { actionAddFromLibrary } = require('./index');
const { SUBSCRIBED } = require('../services/eventBusChannel');
const DeviceRepairOffer = require('../components/DeviceRepairOffer').default;

const t = key => key;
const flush = () => new Promise(resolve => setImmediate(resolve));

function channel() {
    const handlers = new Map();
    return {
        isOpen: () => true,
        subscribe: jest.fn((address, cb) => { handlers.set(address, cb); return SUBSCRIBED; }),
        unsubscribe: jest.fn(address => handlers.delete(address)),
        emit: (address, body) => handlers.get(address)(null, { body })
    };
}

/** Whether an element tree contains the repair offer. */
function containsOffer(node) {
    if (!node || typeof node !== 'object') {
        return false;
    }
    if (Array.isArray(node)) {
        return node.some(containsOffer);
    }
    if (node.type === DeviceRepairOffer) {
        return true;
    }
    return !!node.props && (containsOffer(node.props.children) || containsOffer(node.props.content));
}

const lastRender = () => {
    const calls = toast.update.mock.calls;
    return calls[calls.length - 1][1].render;
};

let counter = 0;

async function run(ch, mode) {
    // The refresh dispatched after a verdict is not run: it would hold the action mutex
    const dispatch = jest.fn();
    // A pack of its own per run: a transfer still followed would make the next one refused as busy
    const uuid = 'uuid-' + (++counter);
    await actionAddFromLibrary(uuid, 'pack.fs', 'fs', 'fs', { channel: ch }, t, { mode })(dispatch);
    await flush();
    return dispatch;
}

beforeEach(() => {
    jest.clearAllMocks();
    deviceService.fetchTransfer.mockReturnValue(new Promise(() => {}));
    deviceService.fetchDeviceInfos.mockReturnValue(new Promise(() => {}));
    deviceService.fetchDevicePacks.mockReturnValue(new Promise(() => {}));
});

describe('in-place update', () => {

    it('goes to the update route, not the add route, and says it updated', async () => {
        const ch = channel();
        repairService.updateFromLibrary.mockResolvedValue({ transferId: 'tid' });
        await run(ch, 'update');
        expect(repairService.updateFromLibrary).toHaveBeenCalledWith(expect.stringMatching(/^uuid-/), 'pack.fs');
        expect(deviceService.addFromLibrary).not.toHaveBeenCalled();
        expect(toast).toHaveBeenCalledWith('deviceUpdate.toasts.updating', expect.anything());
        ch.emit('storyteller.transfer.tid.done', { success: true });
        expect(lastRender()).toBe('deviceUpdate.toasts.updated');
    });

    it('the default mode still adds', async () => {
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid2' });
        await run(channel());
        expect(deviceService.addFromLibrary).toHaveBeenCalled();
        expect(repairService.updateFromLibrary).not.toHaveBeenCalled();
    });
});

describe('repair offer', () => {

    it('a transfer failing on an existing folder offers to open the device check', async () => {
        const ch = channel();
        deviceService.addFromLibrary.mockResolvedValue({ transferId: 'tid3' });
        await run(ch);
        ch.emit('storyteller.transfer.tid3.done', { success: false, error: 'CONTENT_FOLDER_EXISTS', message: 'exists' });
        expect(containsOffer(lastRender())).toBe(true);
    });

    it('a request refused because of a pending index offers it too, another failure does not', async () => {
        const pending = Object.assign(new Error('pending'), { code: 'PACK_INDEX_PENDING' });
        repairService.updateFromLibrary.mockRejectedValue(pending);
        await run(channel(), 'update');
        expect(containsOffer(lastRender())).toBe(true);

        const full = Object.assign(new Error('full'), { code: 'NOT_ENOUGH_SPACE' });
        repairService.updateFromLibrary.mockRejectedValue(full);
        await run(channel(), 'update');
        expect(containsOffer(lastRender())).toBe(false);
    });
});
