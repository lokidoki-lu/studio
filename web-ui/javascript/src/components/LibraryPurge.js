/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import { connect } from 'react-redux';
import {withTranslation} from "react-i18next";
import {toast} from "react-toastify";

import {actionRefreshLibrary} from "../actions";
import {fetchPurgeCandidates, purgeLibrary} from "../services/library";
import {initialSelection, selectedPaths, selectedSize, sizeUnit, purgeSummary} from "../utils/purge";
import IssueReportToast from "./IssueReportToast";
import Modal from "./Modal";

import './LibraryPurge.css';

/*
 * The "remove old packs" button of the local library, and its confirmation dialog.
 *
 * What is old is the backend's decision (see API.md, "Old packs"); it checks each name again when
 * the purge is asked for, so a list that has gone stale while the dialog was open deletes nothing
 * it should not.
 */
class LibraryPurge extends React.Component {

    constructor(props) {
        super(props);
        this.state = {
            searching: false,
            candidates: null,
            selection: {}
        };
    }

    formatSize = (bytes) => {
        const { t } = this.props;
        const unit = sizeUnit(bytes);
        return t(`libraryPurge.units.${unit.key}`, { value: unit.value });
    };

    onSearch = () => {
        const { t } = this.props;
        if (this.state.searching) {
            return;
        }
        this.setState({searching: true});
        const toastId = toast(t('libraryPurge.toasts.searching'), { autoClose: false });
        fetchPurgeCandidates()
            .then(response => {
                const candidates = (response && Array.isArray(response.candidates)) ? response.candidates : [];
                if (candidates.length === 0) {
                    toast.update(toastId, { type: toast.TYPE.INFO, render: t('libraryPurge.toasts.nothing'), autoClose: 5000 });
                    this.setState({searching: false});
                    return;
                }
                toast.dismiss(toastId);
                this.setState({searching: false, candidates, selection: initialSelection(candidates)});
            })
            .catch(e => {
                console.error('failed to list old packs', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('libraryPurge.toasts.searchingFailed')}</>} error={e} />, autoClose: false });
                this.setState({searching: false});
            });
    };

    onToggle = (path) => {
        return (event) => {
            const checked = event.target.checked;
            this.setState(state => ({selection: {...state.selection, [path]: checked}}));
        };
    };

    dismiss = () => {
        this.setState({candidates: null, selection: {}});
    };

    onConfirm = () => {
        const { t } = this.props;
        const paths = selectedPaths(this.state.candidates, this.state.selection);
        if (paths.length === 0) {
            return;
        }
        // Close first, so a second click cannot send a second request
        this.dismiss();
        const toastId = toast(t('libraryPurge.toasts.purging'), { autoClose: false });
        purgeLibrary(paths)
            .then(response => {
                const summary = purgeSummary(response);
                const size = this.formatSize(summary.freed);
                if (summary.failed === 0) {
                    toast.update(toastId, { type: toast.TYPE.SUCCESS, render: t('libraryPurge.toasts.purged', { count: summary.removed, size }), autoClose: 5000 });
                } else {
                    toast.update(toastId, { type: toast.TYPE.WARNING, render: t('libraryPurge.toasts.partial', { count: summary.removed, size, failed: summary.failed }), autoClose: false });
                }
            })
            .catch(e => {
                console.error('failed to remove old packs', e);
                toast.update(toastId, { type: toast.TYPE.ERROR, render: <IssueReportToast content={<>{t('libraryPurge.toasts.purgingFailed')}</>} error={e} showDetails />, autoClose: false });
            })
            .finally(() => {
                // Whatever happened, show the library as it now is
                this.props.refreshLibrary();
            });
    };

    renderDialog() {
        const { t, i18n } = this.props;
        const { candidates, selection } = this.state;
        const count = selectedPaths(candidates, selection).length;
        const hasUnselectedByDefault = candidates.some(c => c.selected !== true);
        const content = <div className="library-purge">
            <p>{t('libraryPurge.intro')}</p>
            <p className="library-purge-warning"><strong>{t('libraryPurge.warning')}</strong></p>
            <div className="library-purge-list">
                <table className="table table-condensed">
                    <thead>
                        <tr>
                            <th/>
                            <th>{t('libraryPurge.columns.entry')}</th>
                            <th>{t('libraryPurge.columns.reason')}</th>
                            <th className="library-purge-size">{t('libraryPurge.columns.size')}</th>
                            <th>{t('libraryPurge.columns.date')}</th>
                        </tr>
                    </thead>
                    <tbody>
                        {candidates.map(c => <tr key={c.path}>
                            <td><input type="checkbox" id={`purge-${c.path}`} checked={selection[c.path] === true} onChange={this.onToggle(c.path)} /></td>
                            <td>
                                <label htmlFor={`purge-${c.path}`}>
                                    <span className="library-purge-title">{c.title && c.title !== "MISSING_PACK_TITLE" ? c.title : c.uuid}</span>
                                    <span className="library-purge-path" title={c.path}>{c.path} ({t(`library.format.${c.format}`)})</span>
                                </label>
                            </td>
                            <td>{t(`libraryPurge.reasons.${c.reason}`, { kept: c.keptPath || '-' })}</td>
                            <td className="library-purge-size">{c.size >= 0 ? this.formatSize(c.size) : '-'}</td>
                            <td>{new Date(c.timestamp).toLocaleString(i18n && i18n.language)}</td>
                        </tr>)}
                    </tbody>
                </table>
            </div>
            {hasUnselectedByDefault && <p className="library-purge-hint">{t('libraryPurge.unselectedHint')}</p>}
            <p><strong>{t('libraryPurge.total', { size: this.formatSize(selectedSize(candidates, selection)), count })}</strong></p>
        </div>;
        return <Modal id="library-purge"
                      className="library-purge-modal"
                      title={t('libraryPurge.title')}
                      content={content}
                      buttons={[
                          { label: t('libraryPurge.cancel'), onClick: this.dismiss },
                          { label: `${t('libraryPurge.confirm')} (${count})`, onClick: this.onConfirm }
                      ]}
                      onClose={this.dismiss}
        />;
    }

    render() {
        const { t } = this.props;
        return <>
            <span title={t('libraryPurge.button')}
                  className={`btn btn-default glyphicon glyphicon-erase library-purge-button ${this.state.searching ? 'disabled' : ''}`}
                  onClick={this.onSearch}/>
            {this.state.candidates && this.renderDialog()}
        </>;
    }
}

const mapDispatchToProps = (dispatch, ownProps) => ({
    refreshLibrary: () => dispatch(actionRefreshLibrary(ownProps.t))
});

export default withTranslation()(
    connect(
        null,
        mapDispatchToProps
    )(LibraryPurge)
);
