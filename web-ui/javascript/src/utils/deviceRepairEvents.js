/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Lets a failure toast, raised far from any component, open the "Check / repair the Lunii" window
 * that the device panel mounts.
 */

const listeners = new Set();

/** Error codes for which the device check knows what to do. */
const REPAIRABLE = ['CONTENT_FOLDER_EXISTS', 'PACK_INDEX_PENDING'];

export function needsRepairOffer(code) {
    return REPAIRABLE.indexOf(code) !== -1;
}

export function openDeviceRepair() {
    listeners.forEach(listener => listener());
    return listeners.size > 0;
}

/** Returns the function that unsubscribes. */
export function onOpenDeviceRepair(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
}

/** Update statuses by lower-case uuid, from GET /api/device/updates. */
export function updateStatuses(response) {
    const statuses = {};
    (response && Array.isArray(response.packs) ? response.packs : []).forEach(p => {
        if (p && p.uuid) {
            statuses[String(p.uuid).toLowerCase()] = p;
        }
    });
    return statuses;
}

/** Whether the library holds another version of this device pack, worth offering. */
export function isUpdateAvailable(statuses, uuid) {
    const status = statuses && uuid ? statuses[String(uuid).toLowerCase()] : null;
    return !!status && status.status === 'UPDATE_AVAILABLE';
}
