/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import {withTranslation} from "react-i18next";

import {openDeviceRepair} from "../utils/deviceRepairEvents";

/** In a failure toast: opens the "Check / repair the Lunii" window, which can resolve the cause. */
export function DeviceRepairOffer({t}) {
    return <span className="device-repair-offer">
        {' '}{t('deviceRepair.offer.text')}{' '}
        <button type="button" className="ui-btn" onClick={() => openDeviceRepair()}>{t('deviceRepair.button')}</button>
    </span>;
}

export default withTranslation()(DeviceRepairOffer);
