/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {planSendQueue, createSendQueue, retryWhileBusy, summarize} from "../utils/sendQueue";
import {freeBytes, selectionPaths, selectionRequirement, checkFits} from "../utils/spaceEstimate";

/*
 * The selection of library packs, the space check and the send queue of a library view (full or
 * simplified: both are a PackLibrary). It keeps its state under `sendQueue` in the view's state, and
 * sends each pack through the view's own sendToDevice, so that the choice of file, the conversion
 * and the questions stay where they are.
 */
export default class LibrarySendQueue {

    static initialState() {
        return {selected: {}, anchor: null, estimate: null, estimating: false, run: null, summary: null, refusal: null};
    }

    constructor(view) {
        this.view = view;
        this.queue = null;
        this.estimateSequence = 0;
        this.disposed = false;
    }

    get state() {
        return this.view.state.sendQueue || LibrarySendQueue.initialState();
    }

    set(changes) {
        if (this.disposed) {
            return;
        }
        this.view.setState(state => ({sendQueue: {...(state.sendQueue || LibrarySendQueue.initialState()), ...changes}}));
    }

    /** The view is going away: whatever is left of a run is not sent. */
    dispose() {
        this.disposed = true;
        if (this.queue) {
            this.queue.cancel();
        }
    }

    // ----------------------------------------------------------------------------- selection

    groups() {
        return (this.view.state.library && this.view.state.library.packs) || [];
    }

    selectedGroups(selected = this.state.selected) {
        return this.groups().filter(group => selected[group.uuid] === true);
    }

    isSelected(uuid) {
        return this.state.selected[uuid] === true;
    }

    /** Selects or deselects some packs; the estimate follows. */
    select(uuids, value) {
        const selected = {...this.state.selected};
        uuids.forEach(uuid => {
            if (value) {
                selected[uuid] = true;
            } else {
                delete selected[uuid];
            }
        });
        this.set({selected, anchor: uuids.length === 1 ? uuids[0] : this.state.anchor});
        this.refreshEstimate(selected);
    }

    toggle(uuid) {
        this.select([uuid], !this.isSelected(uuid));
    }

    clear() {
        this.select(Object.keys(this.state.selected), false);
    }

    /**
     * A click on a pack of the full library: Ctrl (or Cmd) toggles it, Shift selects every pack shown
     * between the last one toggled and this one. Returns true when the click was a selection.
     */
    click(uuid, event, orderedUuids) {
        if (!event || !(event.ctrlKey || event.metaKey || event.shiftKey)) {
            return false;
        }
        const anchor = this.state.anchor;
        if (event.shiftKey && anchor && orderedUuids.indexOf(anchor) !== -1 && orderedUuids.indexOf(uuid) !== -1) {
            const [from, to] = [orderedUuids.indexOf(anchor), orderedUuids.indexOf(uuid)].sort((a, b) => a - b);
            const range = orderedUuids.slice(from, to + 1);
            const selected = {...this.state.selected};
            range.forEach(u => { selected[u] = true; });
            this.set({selected});
            this.refreshEstimate(selected);
        } else {
            this.toggle(uuid);
        }
        return true;
    }

    // ------------------------------------------------------------------------------ estimate

    canEstimate() {
        return typeof this.view.props.estimateOnDevice === 'function';
    }

    device() {
        return this.view.state.device || {};
    }

    /**
     * What `groups` need on the plugged device against what it has free: {required, free, fits,
     * missing, unknown, method}, or null when it cannot be told (no device, the backend did not answer).
     */
    estimate(groups) {
        const metadata = this.device().metadata;
        if (!this.canEstimate() || !metadata || !metadata.driver) {
            return Promise.resolve(null);
        }
        const free = freeBytes(metadata);
        const paths = selectionPaths(groups, metadata.driver);
        const answer = paths.length === 0 ? Promise.resolve({packs: []}) : this.view.props.estimateOnDevice(paths, metadata.driver);
        return Promise.resolve(answer)
            .then(estimate => {
                const requirement = selectionRequirement(groups, metadata.driver, estimate);
                return {...requirement, free, ...checkFits(requirement.required, free)};
            })
            .catch(e => {
                // No estimate is no reason to refuse: the transfer checks the space again itself
                console.warn('could not estimate the space the packs need on the device', e);
                return null;
            });
    }

    /** The estimate of the current selection (what would actually be sent), shown under the selection. */
    refreshEstimate(selected = this.state.selected) {
        const sequence = ++this.estimateSequence;
        const {toSend} = planSendQueue(this.selectedGroups(selected), this.device().packs);
        if (toSend.length === 0 || !this.canEstimate()) {
            this.set({estimate: null, estimating: false});
            return Promise.resolve(null);
        }
        this.set({estimating: true});
        return this.estimate(toSend).then(estimate => {
            if (sequence === this.estimateSequence) {
                this.set({estimate, estimating: false});
            }
            return estimate;
        });
    }

    /** Resolves true when `groups` fit (or the space cannot be told); otherwise says why, and false. */
    checkSpace(groups) {
        return this.estimate(groups).then(estimate => {
            if (!estimate || estimate.fits) {
                return true;
            }
            this.set({refusal: {...estimate, count: groups.length}});
            return false;
        });
    }

    dismissRefusal() {
        this.set({refusal: null});
    }

    // --------------------------------------------------------------------------------- queue

    running() {
        return this.queue !== null;
    }

    /** Sends the selection, one pack after the other, after checking that all of it fits. */
    start() {
        if (this.queue || !this.device().metadata) {
            return Promise.resolve(null);
        }
        const {toSend, skipped} = planSendQueue(this.selectedGroups(), this.device().packs);
        if (toSend.length === 0) {
            const summary = summarize(skipped);
            this.set({summary});
            return Promise.resolve(summary);
        }
        // Taken now, so that a second click while the space is checked starts nothing
        this.queue = createSendQueue({
            groups: toSend,
            skipped,
            sendOne: (group, options) => this.sendOne(group, options),
            onUpdate: run => this.set({run})
        });
        const queue = this.queue;
        return this.checkSpace(toSend).then(fits => {
            if (!fits) {
                this.queue = null;
                return null;
            }
            return queue.run().then(summary => {
                this.queue = null;
                this.set({run: null, summary, selected: {}, estimate: null});
                return summary;
            });
        });
    }

    cancel() {
        if (this.queue) {
            this.queue.cancel();
        }
    }

    dismissSummary() {
        this.set({summary: null});
    }

    /** One pack of the queue, through the view's own sending, waiting until its transfer is over. */
    sendOne(group, options) {
        if (!this.device().metadata) {
            return Promise.resolve({status: 'failed', reason: 'noDevice'});
        }
        return Promise.resolve(this.view.sendToDevice(group, {...options, queue: true}));
    }

    /**
     * The transfer of one pack of the queue: settled when the backend's verdict is known, and tried
     * again while the device is busy (the refresh that follows the previous transfer holds it).
     */
    transfer(pack, path, options) {
        if (options.onPhase) {
            options.onPhase('transferring');
        }
        const attempt = () => new Promise(resolve => {
            const metadata = this.device().metadata;
            if (!metadata) {
                resolve({status: 'failed', reason: 'noDevice'});
                return;
            }
            this.view.props.addFromLibrary(pack.uuid, path, pack.format, metadata.driver, this.view.context,
                {mode: options.mode, onProgress: options.onProgress, onSettled: resolve});
        });
        return retryWhileBusy(attempt, {isCancelled: options.isCancelled});
    }
}
