/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import PropTypes from "prop-types";
import {withTranslation} from "react-i18next";

import Modal from "./Modal";
import {
    fetchDiagnosis, resolvePendingIndex, resolveInterruptedUpdate, removeOrphanFolders, removeMissingContent,
    startIntegrityCheck, fetchIntegrityCheck
} from "../services/deviceRepair";
import {onOpenDeviceRepair} from "../utils/deviceRepairEvents";
import {sizeUnit} from "../utils/purge";

import './DeviceRepair.css';

/** How often a running integrity check is read again. */
export const INTEGRITY_POLL_MS = 1000;

/*
 * The "Check / repair the Lunii" button of the device panel, and its window: what the device check
 * found, the repairs it allows (each confirmed first), and the integrity check of the packs.
 *
 * Every repair is the backend's: it reads the card again before acting, in the device queue, and
 * refuses if what it finds is no longer what was shown here.
 */
export class DeviceRepair extends React.Component {

    constructor(props) {
        super(props);
        this.state = {
            open: false,
            loading: false,
            diagnosis: null,
            error: null,
            busy: false,
            confirm: null,
            message: null,
            selectedOrphans: {},
            integrity: null
        };
    }

    componentDidMount() {
        this.unsubscribe = onOpenDeviceRepair(this.onOpen);
    }

    componentWillUnmount() {
        this.unmounted = true;
        if (this.unsubscribe) {
            this.unsubscribe();
        }
        this.stopPolling();
    }

    stopPolling() {
        if (this.poll) {
            clearInterval(this.poll);
            this.poll = null;
        }
    }

    safeSetState(update) {
        if (!this.unmounted) {
            this.setState(update);
        }
    }

    onOpen = () => {
        // A fresh window: no integrity check left over from a previous opening
        this.integrityGeneration = (this.integrityGeneration || 0) + 1;
        this.stopPolling();
        this.setState({open: true, confirm: null, message: null, integrity: null});
        return this.refresh();
    };

    onClose = () => {
        // Answers still in flight (start or progress of an integrity check) belong to this window only
        this.integrityGeneration = (this.integrityGeneration || 0) + 1;
        this.stopPolling();
        this.setState({open: false, confirm: null, diagnosis: null, integrity: null, error: null, message: null});
    };

    refresh = () => {
        if (this.props.driver === 'raw') {
            this.safeSetState({loading: false, diagnosis: {supported: false, driver: 'raw'}});
            return Promise.resolve();
        }
        this.safeSetState({loading: true, error: null});
        return fetchDiagnosis()
            .then(diagnosis => this.safeSetState({loading: false, diagnosis, selectedOrphans: {}}))
            .catch(e => this.safeSetState({loading: false, error: e && e.message ? e.message : String(e)}));
    };

    formatSize = (bytes) => {
        const unit = sizeUnit(bytes);
        return this.props.t(`libraryPurge.units.${unit.key}`, { value: unit.value });
    };

    /**
     * Asks first; runs `run` (a promise) on confirmation, then checks the device again. `doneText`
     * replaces "repair done" when the action only starts something (a transfer).
     */
    ask = (message, run, doneText) => () => {
        this.setState({confirm: {message, run, doneText}});
    };

    doConfirm = () => {
        const confirm = this.state.confirm;
        if (!confirm || this.state.busy) {
            return Promise.resolve();
        }
        const { t } = this.props;
        this.setState({confirm: null, busy: true, message: null});
        return Promise.resolve()
            .then(confirm.run)
            .then(() => this.safeSetState({message: {type: 'ok', text: confirm.doneText || t('deviceRepair.done')}}))
            .catch(e => this.safeSetState({message: {type: 'error', text: t('deviceRepair.failed', {message: e && e.message ? e.message : String(e)})}}))
            .finally(() => {
                this.safeSetState({busy: false});
                if (this.props.onChanged) {
                    this.props.onChanged();
                }
                return this.refresh();
            });
    };

    onStartIntegrity = () => {
        const { t } = this.props;
        if (this.state.integrity && !this.state.integrity.done) {
            return Promise.resolve();
        }
        this.setState({integrity: {done: false, checked: 0, total: 0}});
        // Every answer is checked against this: the window closed (or reopened) since makes it stale
        const generation = this.integrityGeneration = (this.integrityGeneration || 0) + 1;
        const current = () => generation === this.integrityGeneration;
        return startIntegrityCheck()
            .then(resp => {
                if (!current()) {
                    return;
                }
                const id = resp.checkId;
                const read = () => fetchIntegrityCheck(id)
                    .then(state => {
                        if (!current()) {
                            return;
                        }
                        this.safeSetState({integrity: state});
                        if (state.done) {
                            this.stopPolling();
                        }
                    })
                    .catch(e => {
                        if (!current()) {
                            return;
                        }
                        this.stopPolling();
                        this.safeSetState({integrity: {done: true, success: false, message: e && e.message}});
                    });
                this.stopPolling();
                this.poll = setInterval(read, INTEGRITY_POLL_MS);
                return read();
            })
            .catch(e => {
                if (current()) {
                    this.safeSetState({integrity: {done: true, success: false, message: (e && e.message) || t('deviceRepair.failed', {message: ''})}});
                }
            });
    };

    toggleOrphan = (folder) => (event) => {
        const checked = event.target.checked;
        this.setState(state => ({selectedOrphans: {...state.selectedOrphans, [folder]: checked}}));
    };

    /** What adopting the pending index asks: the titles it drops from the Lunii, when it drops any. */
    adoptConfirmation(p) {
        const { t } = this.props;
        const removed = p.removed || [];
        if (removed.length === 0) {
            return t('deviceRepair.pendingIndex.confirmAdopt');
        }
        return t('deviceRepair.pendingIndex.confirmAdoptRemoving', {titles: removed.map(u => this.packLabel(u)).join(', ')});
    }

    renderPendingIndex(p, token, indexMissing) {
        const { t } = this.props;
        const kind = `deviceRepair.pendingIndex.kinds.${p.kind}`;
        const details = { added: (p.added || []).length, removed: (p.removed || []).length, current: p.currentCount, pending: p.pendingCount };
        return <section className="device-repair-section">
            <h5>{t('deviceRepair.pendingIndex.title')}</h5>
            {indexMissing
                ? <p className="device-repair-warning">{t('deviceRepair.pendingIndex.indexMissing')}</p>
                : <p>{t(kind, details)}</p>}
            {indexMissing
                ? (p.canAdopt
                    ? <button type="button" className="ui-btn" disabled={this.state.busy}
                              onClick={this.ask(t('deviceRepair.pendingIndex.confirmAdoptIndexMissing'), () => resolvePendingIndex('adopt', p.fingerprint, token))}>
                        {t('deviceRepair.pendingIndex.adopt')}
                    </button>
                    : <>
                        <p className="device-repair-hint">{t('deviceRepair.pendingIndex.indexMissingCannotAdopt')}</p>
                        {(p.unlistedCompleteFolders || []).length > 0 && <p className="device-repair-hint">
                            {t('deviceRepair.pendingIndex.indexMissingUnlisted', {folders: p.unlistedCompleteFolders.join(', ')})}
                        </p>}
                    </>)
                : p.safeToDiscard
                ? <>
                    <p className="device-repair-safe">{t('deviceRepair.pendingIndex.safe')}</p>
                    <button type="button" className="ui-btn" disabled={this.state.busy}
                            onClick={this.ask(t('deviceRepair.pendingIndex.confirmDiscard'), () => resolvePendingIndex('discard', p.fingerprint, token))}>
                        {t('deviceRepair.pendingIndex.discard')}
                    </button>
                </>
                : <>
                    <p>{t('deviceRepair.pendingIndex.choice')}</p>
                    <div className="device-repair-choice">
                        <div>
                            <button type="button" className="ui-btn" disabled={this.state.busy}
                                    onClick={this.ask(t('deviceRepair.pendingIndex.confirmKeep'), () => resolvePendingIndex('discard', p.fingerprint, token))}>
                                {t('deviceRepair.pendingIndex.keep')}
                            </button>
                            <p className="device-repair-hint">{t(`deviceRepair.pendingIndex.keepMeans.${p.kind}`, details)}</p>
                        </div>
                        {p.canAdopt && <div>
                            <button type="button" className="ui-btn" disabled={this.state.busy}
                                    onClick={this.ask(this.adoptConfirmation(p), () => resolvePendingIndex('adopt', p.fingerprint, token))}>
                                {t('deviceRepair.pendingIndex.adopt')}
                            </button>
                            <p className="device-repair-hint">{t(`deviceRepair.pendingIndex.adoptMeans.${p.kind}`, details)}</p>
                        </div>}
                    </div>
                    {!p.canAdopt && <p className="device-repair-hint">{t('deviceRepair.pendingIndex.cannotAdopt', {count: (p.incomplete || []).length})}</p>}
                </>}
        </section>;
    }

    renderDiagnosis(d) {
        const { t } = this.props;
        if (d.supported === false) {
            return <p>{t('deviceRepair.unsupported')}</p>;
        }
        const orphans = d.orphans || [];
        const missing = d.missingContent || [];
        const interrupted = d.interruptedUpdates || [];
        const selected = orphans.filter(o => this.state.selectedOrphans[o.folder] && !o.referencedByPendingIndex).map(o => o.folder);
        const token = d.deviceToken;
        // Without an installed index nothing else can be judged: every folder would look orphaned
        const indexMissing = d.indexMissing === true;
        return <>
            <p className={d.problems > 0 ? 'device-repair-warning' : 'device-repair-safe'}>
                {d.problems > 0 ? t('deviceRepair.problems', {count: d.problems}) : t('deviceRepair.noProblem', {count: d.indexedPacks})}
            </p>
            {d.pendingIndex && this.renderPendingIndex(d.pendingIndex, token, indexMissing)}
            {!indexMissing && interrupted.length > 0 && <section className="device-repair-section">
                <h5>{t('deviceRepair.interrupted.title')}</h5>
                <ul>
                    {interrupted.map(u => <li key={u.folder}>
                        <span>{t(`deviceRepair.interrupted.states.${u.state}`, {folder: u.folder})}</span>{' '}
                        {/* No repair is offered when STUdio cannot tell which copy holds the story */}
                        {u.state !== 'CONFLICT' && <button type="button" className="ui-btn" disabled={this.state.busy}
                                onClick={this.ask(t(`deviceRepair.interrupted.confirm.${u.state}`, {folder: u.folder}), () => resolveInterruptedUpdate(u.folder, u.state, token))}>
                            {t(`deviceRepair.interrupted.actions.${u.state}`)}
                        </button>}
                    </li>)}
                </ul>
            </section>}
            {!indexMissing && orphans.length > 0 && <section className="device-repair-section">
                <h5>{t('deviceRepair.orphans.title')}</h5>
                <p className="device-repair-hint">{t('deviceRepair.orphans.intro')}</p>
                <ul className="device-repair-list">
                    {orphans.map(o => <li key={o.folder}>
                        <label>
                            <input type="checkbox" checked={this.state.selectedOrphans[o.folder] === true && !o.referencedByPendingIndex}
                                   disabled={o.referencedByPendingIndex === true} onChange={this.toggleOrphan(o.folder)} />
                            {' '}<code>{o.folder}</code> — {this.formatSize(o.sizeInBytes)} — {t(o.complete ? 'deviceRepair.orphans.complete' : 'deviceRepair.orphans.partial')}
                            {o.referencedByPendingIndex && <> — {t('deviceRepair.orphans.pending')}</>}
                        </label>
                    </li>)}
                </ul>
                <button type="button" className="ui-btn ui-btn-danger" disabled={this.state.busy || selected.length === 0}
                        onClick={this.ask(t('deviceRepair.orphans.confirm', {count: selected.length}), () => removeOrphanFolders(selected, token))}>
                    {t('deviceRepair.orphans.remove', {count: selected.length})}
                </button>
            </section>}
            {!indexMissing && missing.length > 0 && <section className="device-repair-section">
                <h5>{t('deviceRepair.missing.title')}</h5>
                <ul>
                    {missing.map(m => <li key={m.uuid}>
                        <span title={m.uuid}>{this.packLabel(m.uuid)}</span>{' '}
                        <button type="button" className="ui-btn" disabled={this.state.busy}
                                onClick={this.ask(t('deviceRepair.missing.confirm'), () => removeMissingContent(m.uuid, token))}>
                            {t('deviceRepair.missing.remove')}
                        </button>
                    </li>)}
                </ul>
            </section>}
        </>;
    }

    packLabel(uuid, title) {
        if (title && title !== 'MISSING_PACK_TITLE') {
            return title;
        }
        const titles = this.props.titles || {};
        return titles[String(uuid).toLowerCase()] || uuid;
    }

    /** "Send again from the library", asked first with the versions it replaces (L9). */
    resend = (p) => {
        const { t } = this.props;
        const title = this.packLabel(p.uuid, p.title);
        const status = this.props.updateStatusOf ? this.props.updateStatusOf(p.uuid) : null;
        const known = status && status.deviceVersion !== undefined && status.deviceVersion !== null
            && status.libraryVersion !== undefined && status.libraryVersion !== null;
        const message = known
            ? t('deviceRepair.integrity.confirmResend', {title, device: status.deviceVersion, library: status.libraryVersion})
            : t('deviceRepair.integrity.confirmResendNoVersion', {title});
        return this.ask(message, () => this.props.onResend(p.uuid), t('deviceRepair.integrity.resendStarted'));
    };

    renderIntegrity() {
        const { t } = this.props;
        const integrity = this.state.integrity;
        const supported = !(this.state.diagnosis && this.state.diagnosis.supported === false);
        if (!supported) {
            return null;
        }
        const running = integrity && !integrity.done;
        const percent = integrity && integrity.total > 0 ? Math.min(100, Math.round(100 * integrity.checked / integrity.total)) : 0;
        const packs = (integrity && integrity.packs) || [];
        const damaged = packs.filter(p => !p.ok);
        const library = (this.props.libraryUuids || []).map(u => String(u).toLowerCase());
        return <section className="device-repair-section">
            <h5>{t('deviceRepair.integrity.title')}</h5>
            <p className="device-repair-hint">{t('deviceRepair.integrity.intro')}</p>
            <button type="button" className="ui-btn" disabled={running || this.state.busy} onClick={this.onStartIntegrity}>
                {t(running ? 'deviceRepair.integrity.running' : 'deviceRepair.integrity.start')}
            </button>
            {running && <div className="device-repair-progress" role="progressbar" aria-valuenow={percent} aria-valuemin="0" aria-valuemax="100"
                             aria-label={t('deviceRepair.integrity.progress', {checked: integrity.checked, total: integrity.total})}>
                <div style={{width: `${percent}%`}}/>
                <span>{t('deviceRepair.integrity.progress', {checked: integrity.checked, total: integrity.total})}</span>
            </div>}
            {integrity && integrity.done && integrity.success === false &&
                <p className="device-repair-warning">{t('deviceRepair.failed', {message: integrity.message || ''})}</p>}
            {integrity && integrity.done && integrity.success && <>
                <p className={damaged.length > 0 ? 'device-repair-warning' : 'device-repair-safe'}>
                    {damaged.length > 0
                        ? t('deviceRepair.integrity.damaged', {count: damaged.length, total: packs.length})
                        : t('deviceRepair.integrity.allOk', {count: packs.length})}
                </p>
                {damaged.length > 0 && <ul className="device-repair-list">
                    {damaged.map(p => <li key={p.uuid} className="device-repair-damaged">
                        <strong title={p.uuid}>{this.packLabel(p.uuid, p.title)}</strong>
                        <ul>
                            {(p.problems || []).map((problem, idx) => <li key={idx}>
                                {t(`deviceRepair.integrity.codes.${problem.code}`)} {problem.detail && <code>{problem.detail}</code>}
                            </li>)}
                        </ul>
                        {p.official
                            ? <p className="device-repair-hint">{t('deviceRepair.integrity.official')}</p>
                            // An update replaces a folder that is there: a missing one is removed from the index first
                            : (p.problems || []).some(problem => problem.code === 'FOLDER_MISSING')
                                ? <p className="device-repair-hint">{t('deviceRepair.integrity.folderMissing')}</p>
                                : library.indexOf(String(p.uuid).toLowerCase()) !== -1 && this.props.onResend
                                    ? <button type="button" className="ui-btn ui-btn-primary" disabled={this.state.busy}
                                              onClick={this.resend(p)}>
                                        {t('deviceRepair.integrity.resend')}
                                      </button>
                                    : <p className="device-repair-hint">{t('deviceRepair.integrity.notInLibrary')}</p>}
                        {p.structureOnly && <p className="device-repair-hint">{t('deviceRepair.integrity.structureOnly')}</p>}
                    </li>)}
                </ul>}
            </>}
        </section>;
    }

    renderContent() {
        const { t } = this.props;
        if (this.state.confirm) {
            return <div className="device-repair-confirm"><p>{this.state.confirm.message}</p></div>;
        }
        return <div className="device-repair">
            {this.state.message && <p className={this.state.message.type === 'ok' ? 'device-repair-safe' : 'device-repair-warning'}>{this.state.message.text}</p>}
            {this.state.loading && <p>{t('deviceRepair.checking')}</p>}
            {this.state.error && <p className="device-repair-warning">{t('deviceRepair.failed', {message: this.state.error})}</p>}
            {!this.state.loading && this.state.diagnosis && this.renderDiagnosis(this.state.diagnosis)}
            {this.renderIntegrity()}
        </div>;
    }

    render() {
        const { t } = this.props;
        const buttons = this.state.confirm
            ? [
                { label: t('dialogs.shared.no'), onClick: () => this.setState({confirm: null}) },
                { label: t('deviceRepair.confirm'), onClick: this.doConfirm, disabled: this.state.busy }
            ]
            : [
                { label: t('deviceRepair.recheck'), onClick: this.refresh, disabled: this.state.loading || this.state.busy },
                { label: t('dialogs.shared.close'), onClick: this.onClose }
            ];
        return <>
            <button type="button" className={`ui-btn device-repair-button ${this.props.className || ''}`} onClick={this.onOpen}>
                <span className="glyphicon glyphicon-wrench" aria-hidden="true"/> {t('deviceRepair.button')}
            </button>
            {this.state.open &&
            <Modal id="device-repair"
                   className="device-repair-modal"
                   title={t('deviceRepair.title')}
                   content={this.renderContent()}
                   buttons={buttons}
                   onClose={this.state.confirm ? () => this.setState({confirm: null}) : this.onClose}
            />}
        </>;
    }
}

DeviceRepair.propTypes = {
    driver: PropTypes.string,
    // Lower-case uuids the library holds: a damaged pack among them can be sent again
    libraryUuids: PropTypes.array,
    // Titles by lower-case uuid, for packs the check names
    titles: PropTypes.object,
    onResend: PropTypes.func,
    // The update status of a pack (GET /api/device/updates), for the versions a resend replaces
    updateStatusOf: PropTypes.func,
    onChanged: PropTypes.func,
    className: PropTypes.string,
    t: PropTypes.func
};

export default withTranslation()(DeviceRepair);
