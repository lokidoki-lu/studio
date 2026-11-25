/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {apiFetch, handleJsonOrError} from "../utils/fetch";

export const fetchEvergreenInfos = () => {
    return apiFetch('/api/evergreen/infos')
        .then(handleJsonOrError);
};

export const fetchEvergreenLatestRelease = () => {
    return apiFetch('/api/evergreen/latest')
        .then(handleJsonOrError);
};

export const fetchEvergreenAnnounce = () => {
    return apiFetch('/api/evergreen/announce')
        .then(handleJsonOrError);
};
