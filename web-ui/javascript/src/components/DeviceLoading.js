/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import {withTranslation} from 'react-i18next';

import './DeviceLoading.css';

/**
 * Shown in place of a story list while it is being read (the Lunii just plugged, the library at start), so
 * neither says it holds nothing before it has been read. message: the translation key of the text shown.
 */
export const DeviceLoading = ({t, message = 'library.device.loading'}) =>
    <div className="device-loading" role="status" aria-live="polite">
        <span className="device-loading-spinner" aria-hidden="true"/>
        <span>{t(message)}</span>
    </div>;

export default withTranslation()(DeviceLoading);
