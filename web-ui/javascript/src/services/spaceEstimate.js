/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {apiFetch, handleJsonOrError} from "../utils/fetch";

/**
 * The space library packs will take on the device once in `format` (fs or raw), estimated by the
 * backend from headers, without converting anything. See API.md, "Space a pack takes on the device".
 */
export const estimateOnDevice = (paths, format) => {
    return apiFetch('/api/library/estimate', {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({paths, format})
    }).then(handleJsonOrError);
};
