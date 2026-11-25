/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

const initialState = {
    metadata: null,
    packs: [],
    // True until the library has been read once (or failed to be): it is not empty, only not read yet
    loading: true
};

const library = (state = initialState, action) => {
    switch (action.type) {
        case 'SET_LIBRARY':
            return { ...state, metadata: action.metadata, packs: action.packs, loading: false };
        case 'LIBRARY_LOAD_FAILED':
            return { ...state, loading: false };
        default:
            return state
    }
};

export default library;
