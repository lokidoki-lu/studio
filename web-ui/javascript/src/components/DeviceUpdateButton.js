/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import PropTypes from "prop-types";
import {withTranslation} from "react-i18next";

import './DeviceRepair.css';

/*
 * "Update available" and "Update on my Lunii", for a device pack the library holds in another
 * version (GET /api/device/updates). Nothing is shown otherwise.
 */
export function DeviceUpdateButton({t, status, onUpdate, busy, badgeOnly, buttonOnly}) {
    if (!status || status.status !== 'UPDATE_AVAILABLE') {
        return null;
    }
    const why = t(`deviceUpdate.basis.${status.basis === 'version' ? 'version' : 'content'}`,
        {device: status.deviceVersion, library: status.libraryVersion});
    return <>
        {!buttonOnly && <span className="device-update-badge" title={why}>{t('deviceUpdate.available')}</span>}
        {!badgeOnly && onUpdate && <button type="button" className="ui-btn device-update-button" onClick={onUpdate} disabled={busy}
                                           aria-busy={busy} title={why}>
            {t(busy ? 'deviceUpdate.updating' : 'deviceUpdate.update')}
        </button>}
    </>;
}

DeviceUpdateButton.propTypes = {
    t: PropTypes.func.isRequired,
    status: PropTypes.object,
    onUpdate: PropTypes.func,
    busy: PropTypes.bool,
    badgeOnly: PropTypes.bool,
    buttonOnly: PropTypes.bool
};

export default withTranslation()(DeviceUpdateButton);
