/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

export function handleJsonOrError(response) {
    if(!response.ok) {
        return response.text()
            .then(errorText => {
                const error = new Error(response.statusText + ': ' + errorMessage(errorText));
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
    try {
        const body = JSON.parse(errorText);
        if (body && typeof body.message === 'string' && body.message.trim().length > 0) {
            return body.message;
        }
    } catch (e) {
        // Not JSON: the generic error handler's text, kept as it is.
    }
    return errorText;
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
