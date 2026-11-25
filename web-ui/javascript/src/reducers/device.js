/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

const initialState = {
    metadata: null,
    packs: [],
    // True from the moment a Lunii is plugged until its story list has been read (or failed to)
    packsLoading: false
};

const device = (state = initialState, action) => {
    switch (action.type) {
        case 'DEVICE_PLUGGED':
            return { ...state, metadata: action.metadata, packsLoading: action.loadingPacks === true };
        case 'DEVICE_UNPLUGGED':
            return { ...state, metadata: null, packs: [], packsLoading: false };
        case 'SET_DEVICE_PACKS':
            return { ...state, packs: action.packs, packsLoading: false };
        case 'DEVICE_PACKS_FAILED':
            return { ...state, packsLoading: false };
        default:
            return state
    }
};

export default device;
