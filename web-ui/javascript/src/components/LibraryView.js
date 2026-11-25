/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import {withTranslation} from "react-i18next";

import PackLibrary from "./PackLibrary";
import PackLibrarySimple from "./PackLibrarySimple";
import {LIBRARY_MODE_FULL, LIBRARY_MODE_SIMPLE, readLibraryMode, saveLibraryMode} from "../utils/simpleLibrary";

import './LibraryView.css';

const MODES = [LIBRARY_MODE_SIMPLE, LIBRARY_MODE_FULL];

/** The mode switch: two buttons, the arrow keys move from one to the other, as in a toolbar. */
export function LibraryModeSwitch({mode, onChange, t}) {
    const onKeyDown = (event) => {
        if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
            event.preventDefault();
            const next = MODES[(MODES.indexOf(mode) + 1) % MODES.length];
            onChange(next);
            const button = event.currentTarget.querySelector(`[data-mode="${next}"]`);
            if (button) {
                button.focus();
            }
        }
    };
    return <div className="library-mode-switch" role="group" aria-label={t('libraryMode.label')} onKeyDown={onKeyDown}>
        <span className="library-mode-label" aria-hidden="true">{t('libraryMode.label')}</span>
        {MODES.map(m =>
            <button key={m} type="button" data-mode={m}
                    className={`library-mode-option ${mode === m ? 'active' : ''}`}
                    aria-pressed={mode === m}
                    title={t(`libraryMode.${m}Title`)}
                    onClick={() => onChange(m)}>
                {t(`libraryMode.${m}`)}
            </button>
        )}
    </div>;
}

/** The library page: the switch, then the simplified or the full library, as last chosen. */
export class LibraryView extends React.Component {

    constructor(props) {
        super(props);
        this.state = {mode: readLibraryMode(props.storage)};
    }

    onModeChange = (mode) => {
        if (mode !== this.state.mode) {
            saveLibraryMode(mode, this.props.storage);
            this.setState({mode});
        }
    };

    render() {
        const { t } = this.props;
        const Simple = this.props.simpleComponent || PackLibrarySimple;
        const Full = this.props.fullComponent || PackLibrary;
        return <div className="library-view">
            <div className="library-mode-bar">
                <LibraryModeSwitch mode={this.state.mode} onChange={this.onModeChange} t={t}/>
            </div>
            {this.state.mode === LIBRARY_MODE_SIMPLE ? <Simple/> : <Full/>}
        </div>;
    }
}

export default withTranslation()(LibraryView);
