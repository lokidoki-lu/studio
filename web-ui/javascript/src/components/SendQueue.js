/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';

import Modal from "./Modal";
import {sizeUnit} from "../utils/purge";

import './SendQueue.css';

/*
 * What the send queue shows, in both library views: under the selection, how many packs, the space
 * they need and the space free, and the button that sends them; while they are sent, which one and how
 * far; at the end, what was sent, skipped and failed. Also the refusal when packs do not fit.
 *
 * All of it is driven by a LibrarySendQueue (`queue`) and its state (`state`).
 */
export default class SendQueue extends React.Component {

    componentDidUpdate(prevProps) {
        // The device was plugged, changed or got more (or less) room: the estimate is re-read
        const before = prevProps.device || {};
        const now = this.props.device || {};
        if (keyOf(before) !== keyOf(now) && Object.keys(this.props.state.selected || {}).length > 0) {
            this.props.queue.refreshEstimate();
        }
    }

    formatSize = (bytes) => {
        const unit = sizeUnit(bytes);
        return this.props.t(`libraryPurge.units.${unit.key}`, {value: unit.value});
    };

    renderEstimate(estimate) {
        const {t} = this.props;
        if (!estimate) {
            return null;
        }
        const values = {required: this.formatSize(estimate.required), free: estimate.free === null ? '?' : this.formatSize(estimate.free)};
        return <>
            <span className="send-queue-space">
                {t('spaceEstimate.summary', values)}
                {estimate.method === 'rough' && <> {t('spaceEstimate.rough')}</>}
            </span>
            {!estimate.fits && <p className="send-queue-warning" role="alert">
                {t('spaceEstimate.tooBig', {missing: this.formatSize(estimate.missing)})}
            </p>}
        </>;
    }

    renderBar() {
        const {t, state, queue, device, visible} = this.props;
        const count = queue.selectedGroups().length;
        if (count === 0 || state.run) {
            return null;
        }
        const plugged = !!(device && device.metadata);
        const estimate = state.estimate;
        // Not disabled when the estimate says it does not fit: the refusal then offers to send anyway
        const blocked = !!(estimate && !estimate.fits);
        const visibleUnselected = (visible || []).filter(uuid => !queue.isSelected(uuid));
        return <div className="send-queue-bar" role="region" aria-label={t('sendQueue.selection', {count})}>
            <strong>{t('sendQueue.selection', {count})}</strong>
            {!plugged && <span className="send-queue-space">{t('spaceEstimate.plugFirst')}</span>}
            {plugged && state.estimating && <span className="send-queue-space">{t('spaceEstimate.estimating')}</span>}
            {plugged && !state.estimating && this.renderEstimate(estimate)}
            <span className="send-queue-actions">
                {visibleUnselected.length > 0 &&
                <button type="button" className="ui-btn" onClick={() => queue.select(visibleUnselected, true)}>{t('sendQueue.selectAll')}</button>}
                <button type="button" className="ui-btn" onClick={() => queue.clear()}>{t('sendQueue.clear')}</button>
                <button type="button" className="ui-btn ui-btn-primary send-queue-send" disabled={!plugged || queue.running()}
                        title={blocked ? t('spaceEstimate.blocked') : (plugged ? undefined : t('spaceEstimate.plugFirst'))}
                        onClick={() => queue.start()}>
                    {t('sendQueue.send')}
                </button>
            </span>
        </div>;
    }

    renderProgress() {
        const {t, state, queue} = this.props;
        const run = state.run;
        if (!run || !run.running) {
            return null;
        }
        const overall = Math.round(run.overall * 100);
        const current = Math.min(run.index + 1, run.total);
        const phase = run.phase ? t(`sendQueue.phases.${run.phase}`) : '';
        const packPercent = run.phase === 'transferring' ? ` ${Math.round(run.progress * 100)} %` : '';
        return <div className="send-queue-progress" role="status" aria-live="polite">
            <div className="send-queue-progress-text">
                {t('sendQueue.progress', {current, total: run.total, title: run.title || ''})} — {phase}{packPercent}
            </div>
            <div className="send-queue-meter" role="progressbar" aria-valuenow={overall} aria-valuemin="0" aria-valuemax="100"
                 aria-label={t('sendQueue.overall', {percent: overall})}>
                <div style={{width: `${overall}%`}}/>
            </div>
            <button type="button" className="ui-btn ui-btn-danger" disabled={run.cancelling === true} onClick={() => queue.cancel()}>
                {t(run.cancelling ? 'sendQueue.cancelling' : 'sendQueue.cancel')}
            </button>
        </div>;
    }

    renderSummary() {
        const {t, state, queue} = this.props;
        const summary = state.summary;
        if (!summary) {
            return null;
        }
        const line = (r) => <li key={r.uuid}>
            <strong>{r.title}</strong>{r.reason && <> — {t(`sendQueue.reasons.${r.reason}`)}</>}{r.message && <> ({r.message})</>}
        </li>;
        return <Modal id="send-queue-summary"
                      title={t('sendQueue.summary.title')}
                      content={<div className="send-queue-summary">
                          <p>{t('sendQueue.summary.counts', {sent: summary.sent.length, skipped: summary.skipped.length, failed: summary.failed.length})}</p>
                          {summary.sent.length > 0 && <><h5>{t('sendQueue.summary.sent')}</h5><ul>{summary.sent.map(r => <li key={r.uuid}>{r.title}</li>)}</ul></>}
                          {summary.skipped.length > 0 && <><h5>{t('sendQueue.summary.skipped')}</h5><ul>{summary.skipped.map(line)}</ul></>}
                          {summary.failed.length > 0 && <><h5>{t('sendQueue.summary.failed')}</h5><ul>{summary.failed.map(line)}</ul></>}
                      </div>}
                      buttons={[{label: t('dialogs.shared.close'), onClick: () => queue.dismissSummary()}]}
                      onClose={() => queue.dismissSummary()}
        />;
    }

    renderRefusal() {
        const {t, state, queue} = this.props;
        const refusal = state.refusal;
        if (!refusal) {
            return null;
        }
        return <Modal id="space-refusal"
                      title={t('spaceEstimate.refusal.title')}
                      content={<div className="space-refusal">
                          <p>{t('spaceEstimate.refusal.content', {count: refusal.count, required: this.formatSize(refusal.required),
                              free: this.formatSize(refusal.free), missing: this.formatSize(refusal.missing)})}</p>
                          <p>{t('spaceEstimate.refusal.explain')}</p>
                          {refusal.method === 'rough' && <p>{t('spaceEstimate.rough')}</p>}
                          <p>{t('spaceEstimate.refusal.sendAnywayExplain')}</p>
                      </div>}
                      buttons={[
                          {label: t('dialogs.shared.close'), onClick: () => queue.dismissRefusal()},
                          {label: t('spaceEstimate.refusal.sendAnyway'), onClick: () => queue.sendAnyway()}
                      ]}
                      onClose={() => queue.dismissRefusal()}
        />;
    }

    render() {
        return <>
            {this.renderRefusal()}
            {this.renderSummary()}
            {this.renderProgress()}
            {this.renderBar()}
        </>;
    }
}

function keyOf(device) {
    const metadata = device.metadata;
    if (!metadata) {
        return 'none';
    }
    const storage = metadata.storage || {};
    return [metadata.uuid, metadata.driver, storage.free, storage.taken, (device.packs || []).length].join('|');
}
