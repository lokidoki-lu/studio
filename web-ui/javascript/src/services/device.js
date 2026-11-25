/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {apiFetch, handleJsonOrError} from "../utils/fetch";

export const fetchDeviceInfos = () => {
    return apiFetch('/api/device/infos')
        .then(handleJsonOrError);
};

export const fetchDevicePacks = () => {
    return apiFetch('/api/device/packs')
        .then(handleJsonOrError);
};

export const addFromLibrary = (uuid, path) => {
    return apiFetch('/api/device/addFromLibrary', {
        method: "POST",
        headers: { "Content-Type" : "application/json" },
        body: JSON.stringify({uuid, path})
    })
        .then(handleJsonOrError);
};

export const removeFromDevice = (uuid) => {
    return apiFetch('/api/device/removeFromDevice', {
        method: "POST",
        headers: { "Content-Type" : "application/json" },
        body: JSON.stringify({uuid})
    })
        .then(handleJsonOrError);
};

export const reorderPacks = (uuids) => {
    return apiFetch('/api/device/reorder', {
        method: "POST",
        headers: { "Content-Type" : "application/json" },
        body: JSON.stringify({uuids})
    })
        .then(handleJsonOrError);
};

export const addToLibrary = (uuid, driver) => {
    return apiFetch('/api/device/addToLibrary', {
        method: "POST",
        headers: { "Content-Type" : "application/json" },
        body: JSON.stringify({uuid, driver})
    })
        .then(handleJsonOrError);
};

/*
 * The state of one transfer, as the backend's transfer registry knows it: {transferId, state, done,
 * success, progress, error?, message?, ...}. Rejects with err.code === 'TRANSFER_NOT_FOUND' (and
 * err.status === 404) when the backend has no record of it.
 */
export const fetchTransfer = (transferId) => {
    return apiFetch('/api/device/transfer/' + encodeURIComponent(transferId))
        .then(handleJsonOrError);
};
