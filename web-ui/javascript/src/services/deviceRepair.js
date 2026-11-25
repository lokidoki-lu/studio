/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {apiFetch, handleJsonOrError} from "../utils/fetch";

/* The device check, its repairs, the integrity check and in-place updates (API.md, "Device check and repairs"). */

const postJson = (url, body) => apiFetch(url, {
    method: "POST",
    headers: { "Content-Type" : "application/json" },
    body: JSON.stringify(body || {})
}).then(handleJsonOrError);

export const fetchDiagnosis = () => apiFetch('/api/device/diagnosis').then(handleJsonOrError);

/*
 * Every repair carries the deviceToken the check answered with: the backend refuses it if the Lunii
 * plugged now is not the one checked, or was plugged again since.
 */
export const resolvePendingIndex = (action, fingerprint, deviceToken) => postJson('/api/device/repair/pendingIndex', {action, fingerprint, deviceToken});

export const resolveInterruptedUpdate = (folder, state, deviceToken) => postJson('/api/device/repair/interruptedUpdate', {folder, state, deviceToken});

export const removeOrphanFolders = (folders, deviceToken) => postJson('/api/device/orphans/remove', {folders, deviceToken});

export const removeMissingContent = (uuid, deviceToken) => postJson('/api/device/repair/missingContent', {uuid, deviceToken});

export const startIntegrityCheck = () => postJson('/api/device/integrity');

export const fetchIntegrityCheck = (id) => apiFetch('/api/device/integrity/' + encodeURIComponent(id)).then(handleJsonOrError);

export const fetchDeviceUpdates = () => apiFetch('/api/device/updates').then(handleJsonOrError);

export const updateFromLibrary = (uuid, path) => postJson('/api/device/updateFromLibrary', {uuid, path});
