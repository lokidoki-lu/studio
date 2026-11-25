/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import i18n from 'i18next';

// Said when the browser gets no answer at all (STUdio stopped, or restarting)
const UNREACHABLE_FALLBACK = 'STUdio ne répond pas';

export function unreachableMessage() {
    const text = i18n && typeof i18n.t === 'function' && i18n.isInitialized ? i18n.t('toasts.unreachable') : null;
    return text && text !== 'toasts.unreachable' ? text : UNREACHABLE_FALLBACK;
}

/**
 * fetch() for STUdio's own API: a request that gets no answer (fetch rejects with a TypeError,
 * "Failed to fetch") rejects with a readable message, code UNREACHABLE, the original as cause.
 */
export function apiFetch(input, init) {
    return fetch(input, init).catch(e => {
        if (e instanceof TypeError) {
            const error = new Error(unreachableMessage());
            error.code = 'UNREACHABLE';
            error.cause = e;
            throw error;
        }
        throw e;
    });
}

/**
 * The message of a refused request: the backend's own message when it gave one in JSON (the status
 * stays in error.status), the status text and the body otherwise.
 */
export function httpErrorMessage(statusText, errorText) {
    const message = jsonMessage(errorText);
    if (message !== undefined) {
        return message;
    }
    return [statusText, errorText].filter(part => part && String(part).length > 0).join(': ');
}

export function handleJsonOrError(response) {
    if(!response.ok) {
        return response.text()
            .then(errorText => {
                const error = new Error(httpErrorMessage(response.statusText, errorText));
                // Kept so that callers can tell refusals apart (TRANSFER_NOT_FOUND, NOT_ENOUGH_SPACE…)
                // without parsing the message, which is meant for people.
                error.status = response.status;
                const code = errorCode(errorText);
                if (code) {
                    error.code = code;
                }
                return Promise.reject(error);
            });
    }
    else {
        return response.json();
    }
}

// The device API answers a refusal with {"success": false, "error": CODE, "message": text}. The
// message is what a person can read; anything else is passed through unchanged.
export function errorMessage(errorText) {
    const message = jsonMessage(errorText);
    return message !== undefined ? message : errorText;
}

// The message of a JSON refusal, or undefined
function jsonMessage(errorText) {
    try {
        const body = JSON.parse(errorText);
        if (body && typeof body.message === 'string' && body.message.trim().length > 0) {
            return body.message;
        }
    } catch (e) {
        // Not JSON: the generic error handler's text, kept as it is.
    }
    return undefined;
}

// The machine-readable code of a JSON refusal ({"error": CODE, ...}), or undefined.
export function errorCode(errorText) {
    try {
        const body = JSON.parse(errorText);
        if (body && typeof body.error === 'string' && body.error.trim().length > 0) {
            return body.error;
        }
    } catch (e) {
        // Not JSON: no code.
    }
    return undefined;
}
