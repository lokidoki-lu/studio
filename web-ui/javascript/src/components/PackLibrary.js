/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import { connect } from 'react-redux';
import {withTranslation} from "react-i18next";
import {toast} from "react-toastify";

import {
    actionAddFromLibrary,
    actionRemoveFromDevice,
    actionReorderOnDevice,
    actionAddToLibrary,
    actionLoadLibraryPackInEditor,
    actionConvertInLibrary,
    actionRemoveFromLibrary,
    actionRemoveManyFromLibrary,
    actionUploadToLibrary,
    actionUploadManyToLibrary,
    actionCreatePackInEditor,
    actionLoadSampleInEditor,
    actionRefreshDevice,
    setAllowEnriched
} from "../actions";
import DeviceRepair from "./DeviceRepair";
import DeviceUpdateButton from "./DeviceUpdateButton";
import {fetchDeviceUpdates} from "../services/deviceRepair";
import {updateStatuses} from "../utils/deviceRepairEvents";
import {AppContext} from "../AppContext";
import Modal from "./Modal";
import LibraryPurge from "./LibraryPurge";
import YoutubePack from "./YoutubePack";
import PackPreview from "./PackPreview";
import {
    LOCAL_STORAGE_ALLOW_ENRICHED_BINARY_FORMAT
} from "../utils/storage";
import {chooseDropAction, applyProvenanceVerdict} from "../utils/packs";
import {verifyConversion} from "../services/library";
import {matchingGroups} from "../utils/librarySearch";
import {sizeUnit} from "../utils/purge";
import {estimateOnDevice} from "../services/spaceEstimate";
import LibrarySendQueue from "./LibrarySendQueue";
import SendQueue from "./SendQueue";

import './PackLibrary.css';

/**
 * A file the library can import: a ZIP archive or a binary .pack. Judged on the extension as well as
 * the MIME type, which Windows often leaves empty or sets to something else for a .zip.
 */
export const isPackFile = (file) => {
    const name = (file && file.name ? file.name : '').toLowerCase();
    return ['application/zip', 'application/x-zip-compressed'].indexOf(file && file.type) !== -1
        || name.endsWith('.zip') || name.endsWith('.pack');
};


export class PackLibrary extends React.Component {

    constructor(props) {
        super(props);
        this.state = {
            device: {
                metadata: null,
                packs: []
            },
            library: {
                metadata: null,
                packs: []
            },
            showRemoveFromLibraryConfirmDialog: false,
            removingFromLibrary: null,
            showRemoveFromDeviceConfirmDialog: false,
            removingFromDevice: null,
            dragging: null,
            reordering: null,
            beforeReordering: null,
            allowEnrichedDialog: {
                show: false,
                data: null
            },
            confirmConversionDialog: {
                show: false,
                data: null
            },
            // Update status of each device pack the library holds, by lower-case uuid (GET /api/device/updates)
            deviceUpdates: {},
            // Selection of packs, space estimate and send queue (LibrarySendQueue)
            sendQueue: LibrarySendQueue.initialState()
        };
        this.sendQueue = new LibrarySendQueue(this);
    }

    componentWillUnmount() {
        this.unmountedLibrary = true;
        this.sendQueue.dispose();
    }

    componentDidUpdate(prevProps) {
        const device = this.props.device || {};
        const before = prevProps.device || {};
        const library = this.props.library || {};
        if (device.packs !== before.packs || device.metadata !== before.metadata || library.packs !== (prevProps.library || {}).packs) {
            this.refreshDeviceUpdates();
        }
    }

    /** Asks the backend which device packs the library holds in another version. 2.x+ devices only. */
    refreshDeviceUpdates = () => {
        const metadata = this.props.device && this.props.device.metadata;
        if (!metadata || metadata.driver !== 'fs') {
            if (Object.keys(this.state.deviceUpdates).length > 0) {
                this.setState({deviceUpdates: {}});
            }
            return Promise.resolve();
        }
        const request = this.deviceUpdatesRequest = (this.deviceUpdatesRequest || 0) + 1;
        return Promise.resolve()
            .then(() => fetchDeviceUpdates())
            .then(response => {
                // Only the answer to the latest request is shown
                if (request === this.deviceUpdatesRequest && !this.unmountedLibrary) {
                    this.setState({deviceUpdates: updateStatuses(response)});
                }
            })
            .catch(e => console.warn('could not compare the device with the library', e));
    };

    deviceUpdateOf = (uuid) => this.state.deviceUpdates[String(uuid).toLowerCase()];

    /**
     * Replaces a pack already on the device by the library's version, keeping its place: the same
     * decision (and conversion, if needed) as sending it, then an in-place update.
     */
    updatePackOnDevice = (uuid) => {
        const group = ((this.state.library && this.state.library.packs) || [])
            .find(g => String(g.uuid).toLowerCase() === String(uuid).toLowerCase());
        if (!group || !this.state.device.metadata || !this.isPackDraggable(group.packs[0] || {})) {
            return Promise.resolve();
        }
        return Promise.resolve(this.sendToDevice(group, {mode: 'update'}));
    };

    /** Lower-case uuids and titles of the library, for the device check window. */
    libraryIndex() {
        const groups = (this.state.library && this.state.library.packs) || [];
        const titles = {};
        (this.state.device.packs || []).forEach(p => { if (p.title) { titles[String(p.uuid).toLowerCase()] = p.title; } });
        groups.forEach(g => { if (g.packs[0] && g.packs[0].title) { titles[String(g.uuid).toLowerCase()] = g.packs[0].title; } });
        return {uuids: groups.map(g => String(g.uuid).toLowerCase()), titles};
    }

    renderDeviceRepair(className) {
        const index = this.libraryIndex();
        return <DeviceRepair driver={this.state.device.metadata && this.state.device.metadata.driver}
                             libraryUuids={index.uuids} titles={index.titles}
                             onResend={this.updatePackOnDevice}
                             updateStatusOf={this.deviceUpdateOf}
                             onChanged={this.props.refreshDevice}
                             className={className} />;
    }

    componentDidMount() {
        this.setState({
            device: this.props.device,
            library: this.props.library
        });
        this.refreshDeviceUpdates();
    }

    componentWillReceiveProps(nextProps, nextContext) {
        this.setState({
            device: nextProps.device,
            library: nextProps.library
        });
    }

    onDropPackIntoDevice = (event) => {
        event.preventDefault();
        let packData = event.dataTransfer.getData("local-library-pack");
        if (!packData) {
            // Ignore missing node data
            return;
        }
        this.sendToDevice(JSON.parse(packData));
    };

    /**
     * Sends a library group ({uuid, packs}) to the plugged device, converting it first if needed.
     * Returns a promise settled once the transfer is started, or a dialog asks what to do. The space
     * it will take is checked first (LibrarySendQueue.checkSpace): a pack that does not fit is not
     * converted nor sent, and the user is told why.
     *
     * With options.queue (the send queue), the promise settles with the outcome of the whole
     * sending ({status, reason?}), once the transfer is over or the question asked is answered;
     * options.answers holds the answers already given for the selection, which are not asked again.
     */
    sendToDevice = (data, options = {}) => {
        if (!options.queue && !options.spaceChecked && this.sendQueue.canEstimate()) {
            return this.sendQueue.checkSpace([data]).then(fits => fits
                ? this.sendToDevice(data, {...options, spaceChecked: true})
                : {status: 'skipped', reason: 'space'});
        }
        if (!this.state.device.metadata) {
            // Unplugged meanwhile (during the space check)
            return Promise.resolve({status: 'failed', reason: 'noDevice'});
        }
        const answers = options.answers || {};
        const driverFormat = this.state.device.metadata.driver;
        // Which artefact goes to the device is decided in utils/packs. It compares no timestamps and
        // selects on more than format: nothing records which source a conversion output came from,
        // and a conversion output can itself be archive, raw or fs.
        const decision = chooseDropAction(data.packs, driverFormat);
        const {action, source, cached} = decision;
        console.log('drop action: %s (source: %o, cached: %o)', action, source, cached);

        if (action === 'none') {
            // Nothing to transfer
            return Promise.resolve({status: 'skipped', reason: 'nothing'});
        }

        if (action === 'transfer') {
            // No identifiable non-converted source is currently in the library, so there is no
            // current source to compare this against and none to offer for re-conversion
            console.log('OK, transferring pack: %o', cached);
            return this.doAddToDevice(cached, cached.path, options);
        }

        if (action === 'convert') {   // No compatible pack: convert the source
            console.log('pack must be converted for driver: %s', driverFormat);
            // Ask for enriched raw format preference
            if (driverFormat === 'raw' && localStorage.getItem(LOCAL_STORAGE_ALLOW_ENRICHED_BINARY_FORMAT) === null
                && answers.enriched === undefined) {
                return this.askInDialog('allowEnrichedDialog',
                    { pack: {...source, format: driverFormat}, format: driverFormat, addToDevice: true }, options);
            }
            // Pack is converted and stored in the local library, then transferred to the device
            return this.convertThenSend({...source, format: driverFormat}, driverFormat, this.allowEnrichedFor(options), options);
        }

        // A device-compatible pack exists, and so does a candidate that could be converted into one.
        // The backend is the only thing that can say whether they correspond: it holds what was
        // recorded when the conversion was made, and it reads both artefacts to compare. A verdict
        // it cannot give, for any reason at all, leaves the choice with the user.
        if (options.onPhase) {
            options.onPhase('verifying');
        }
        return this.props.verifyConversion(source.path, cached.path)
            .then(response => response && response.verdict)
            .catch(e => {
                console.error('could not verify the conversion, asking rather than assuming', e);
                return undefined;
            })
            .then(verdict => {
                const verified = applyProvenanceVerdict(decision, verdict);
                if (verified.action === 'transfer') {
                    console.log('conversion proven to match the source, transferring: %o', cached);
                    return this.doAddToDevice(cached, cached.path, options);
                }
                const question = {
                    pack: {...source, format: driverFormat},
                    format: driverFormat,
                    cached,
                    verdict: verified.verdict
                };
                // Answered per verdict: a "transfer" given for an unprovable conversion is no answer
                // for one known not to match its source
                const answered = answers.confirm && answers.confirm[question.verdict];
                if (answered) {
                    // Already answered for this selection
                    return this.answerConfirmConversion(answered, question, options);
                }
                console.log('conversion not proven (%s), asking', verified.verdict);
                return this.askInDialog('confirmConversionDialog', question, options);
            });
    };

    /**
     * Shows a question. Outside the send queue, as before: nothing waits for the answer. In the
     * queue, the promise settles with the outcome of what the answer starts (see the dismiss handlers).
     */
    askInDialog = (name, data, options = {}) => {
        if (!options.queue) {
            // The options go with the question (options.mode: an update stays an update once answered)
            this.setState({[name]: {show: true, data: {...data, options}}});
            return undefined;
        }
        return new Promise(settle => this.setState({[name]: {show: true, data: {...data, options, settle}}}));
    };

    allowEnrichedFor = (options = {}) => {
        return options.answers && options.answers.enriched !== undefined ? options.answers.enriched : this.props.settings.allowEnriched;
    };

    /** Converts `pack` (stored in the library), then transfers the conversion. */
    convertThenSend = (pack, format, allowEnriched, options = {}) => {
        if (options.onPhase) {
            options.onPhase('converting');
        }
        return this.props.convertPackInLibrary(pack.uuid, pack.path, format, allowEnriched, this.context)
            .then(path => {
                // A failed conversion has already been reported, and yields no path
                if (path) {
                    return this.doAddToDevice({...pack, format}, path, options);
                }
                return {status: 'failed', reason: 'conversion'};
            });
    };

    answerConfirmConversion = (answer, data, options = {}) => {
        if (answer === 'convert') {
            return this.convertThenSend(data.pack, data.format, this.allowEnrichedFor(options), options);
        }
        if (answer === 'transfer') {
            // The user decides the existing pack is the one they want. STUdio cannot tell.
            return this.doAddToDevice(data.cached, data.cached.path, options);
        }
        return Promise.resolve({status: 'skipped', reason: 'declined'});
    };

    doAddToDevice = (data, path, options = {}) => {
        if (options.queue) {
            // Settled with the verdict of the transfer, tried again while the device is busy
            return this.sendQueue.transfer(data, path, options);
        }
        // Transfer pack and show progress; 'update' replaces the pack already on the device, in place
        if (options.mode === 'update') {
            return this.props.updateOnDevice(data.uuid, path, data.format, this.state.device.metadata.driver, this.context);
        }
        return this.props.addFromLibrary(data.uuid, path, data.format, this.state.device.metadata.driver, this.context);
    };

    // Échap or ×: the dialog is closed and nothing is converted nor sent
    closeEnrichedDialog = () => {
        const data = this.state.allowEnrichedDialog.data;
        this.setState({allowEnrichedDialog: {show: false, data: null}});
        if (data && data.settle) {
            data.settle({status: 'skipped', reason: 'declined'});
        }
    };

    dismissEnrichedDialog = (allow) => {
        return () => {
            const data = this.state.allowEnrichedDialog.data;
            if (!data) {
                // Already dismissed (double click): the conversion is under way
                return;
            }
            // Close the dialog first, so a second click cannot start a second conversion
            this.setState({
                allowEnrichedDialog: {
                    show: false,
                    data: null
                }
            });
            this.props.setAllowEnriched(allow);
            if (data.options && data.options.answers) {
                // One question for the whole selection
                data.options.answers.enriched = allow;
            }
            // Pack is converted and stored in the local library, then transferred to the device
            const result = data.addToDevice
                ? this.convertThenSend(data.pack, data.format, allow, data.options)
                : this.props.convertPackInLibrary(data.pack.uuid, data.pack.path, data.format, allow, this.context);
            if (data.settle) {
                data.settle(result);
            }
        }
    };

    // Échap or ×: only this pack is left out; the answer is not kept for the rest of the selection
    dismissConfirmConversionDialog = (answer, remember = true) => {
        return () => {
            const data = this.state.confirmConversionDialog.data;
            // Close the dialog first, so a second click cannot start a second operation
            this.setState({
                confirmConversionDialog: {
                    show: false,
                    data: null
                }
            });
            if (!data) {
                // Already dismissed (double click)
                return;
            }
            if (remember && data.options && data.options.answers) {
                // Applied to the rest of the selection as well, as the dialog says (for the same verdict)
                data.options.answers.confirm = {...data.options.answers.confirm, [data.verdict]: answer};
            }
            const result = this.answerConfirmConversion(answer, data, data.options);
            if (data.settle) {
                data.settle(result);
            }
        }
    };

    onRemovePackFromDevice = (uuid) => {
        return () => {
            this.setState({removingFromDevice: uuid});
            this.showRemoveFromDeviceConfirmDialog();
        }
    };

    doRemovePackFromDevice = () => {
        this.props.removeFromDevice(this.state.removingFromDevice)
            .finally(() => {
                // Always hide confirmation dialog
                this.dismissRemoveFromDeviceConfirmDialog();
            });
    };

    showRemoveFromDeviceConfirmDialog = () => {
        this.setState({showRemoveFromDeviceConfirmDialog: true});
    };

    dismissRemoveFromDeviceConfirmDialog = () => {
        this.setState({showRemoveFromDeviceConfirmDialog: false});
    };

    /** Every file dropped (non-file items, such as dragged text, are ignored). */
    getDroppedFiles = (event) => {
        if (event.dataTransfer.items) {
            return Array.from(event.dataTransfer.items)
                .filter(item => item.kind === 'file')
                .map(item => item.getAsFile())
                .filter(file => !!file);
        }
        return Array.from(event.dataTransfer.files || []);
    };

    onDropPackIntoLibrary = (event) => {
        event.preventDefault();
        let packData = event.dataTransfer.getData("device-pack");
        if (!packData) {
            // Handle dropped files
            if (event.dataTransfer.items || event.dataTransfer.files) {
                this.addPacksToLibrary(this.getDroppedFiles(event));
            }
            // Otherwise ignore missing node data / file
            return;
        }
        var data = JSON.parse(packData);
        
        // Transfer pack and show progress
        this.props.addToLibrary(data.uuid, this.state.device.metadata.driver, this.context);
    };

    showAddFileSelector = () => {
        document.getElementById('upload').click();
    };

    packAddFileSelected = (event) => {
        const files = Array.from(event.target.files || []);
        // Let the same files be chosen again later: onChange only fires when the value changes
        event.target.value = '';
        this.addPacksToLibrary(files);
    };

    /** One file goes through the single upload as before; several are imported one after the other. */
    addPacksToLibrary = (files) => {
        const { t } = this.props;
        const accepted = files.filter(isPackFile);
        const rejected = files.filter(file => !isPackFile(file));
        if (rejected.length > 0) {
            toast.error(accepted.length === 0 && rejected.length === 1
                ? t('toasts.library.packFileWrongType')
                : t('toasts.library.packFilesWrongType', {names: rejected.map(file => file.name).join(', ')}));
        }
        if (accepted.length === 1) {
            this.props.uploadPackToLibrary(accepted[0].name, accepted[0]);
        } else if (accepted.length > 1) {
            this.props.uploadPacksToLibrary(accepted);
        }
    };

    onConvertLibraryPack = (pack, format) => {
        return () => {
            if (format === 'raw' && localStorage.getItem(LOCAL_STORAGE_ALLOW_ENRICHED_BINARY_FORMAT) === null) {
                // Ask for enriched raw format preference
                this.setState({
                    allowEnrichedDialog: {
                        show: true,
                        data: { pack, format, addToDevice: false }
                    }
                });
                return;
            }
            // Pack is converted and stored in the local library
            this.props.convertPackInLibrary(pack.uuid, pack.path, format, this.props.settings.allowEnriched, this.context);
        }
    };

    onEditLibraryPack = (pack) => {
        return () => {
            // Read from the library entry by entry, rather than downloading the whole archive
            this.props.loadLibraryPackInEditor(pack.path);
        }
    };

    onRemovePackFromLibrary = (path) => {
        return () => {
            this.setState({removingFromLibrary: path});
            this.showRemoveFromLibraryConfirmDialog();
        }
    };

    doRemovePackFromLibrary = () => {
        this.props.removeFromLibrary(this.state.removingFromLibrary)
            .finally(() => {
                // Always hide confirmation dialog
                this.dismissRemoveFromLibraryConfirmDialog();
            });
    };

    showRemoveFromLibraryConfirmDialog = () => {
        this.setState({showRemoveFromLibraryConfirmDialog: true});
    };

    dismissRemoveFromLibraryConfirmDialog = () => {
        this.setState({showRemoveFromLibraryConfirmDialog: false});
    };

    isPackDraggable = (pack) => {
        return !pack.official;
    };

    onCreateNewPackInEditor = (e) => {
        e.preventDefault();
        this.props.createPackInEditor();
    };

    onOpenSamplePackInEditor = (e) => {
        e.preventDefault();
        this.props.loadSampleInEditor();
    };

    /** The selection bar, the progress of a send queue and its dialogs (both views). */
    renderSendQueue(visibleUuids) {
        return <SendQueue t={this.props.t} queue={this.sendQueue} state={this.state.sendQueue}
                          device={this.state.device} visible={visibleUuids} />;
    }

    /** The selection box of a pack, for the send queue (both views). */
    renderSelectBox(uuid, title) {
        const { t } = this.props;
        return <label className="send-select" title={t('sendQueue.select', { title })} onClick={e => e.stopPropagation()}>
            <input type="checkbox" checked={this.sendQueue.isSelected(uuid)} onChange={() => this.sendQueue.toggle(uuid)}
                   aria-label={t('sendQueue.select', { title })} />
        </label>;
    }

    /** The confirmation dialogs, shared with the simplified library (PackLibrarySimple). */
    renderDialogs() {
        const { t } = this.props;
        return <>
            {this.state.previewing && <PackPreview pack={this.state.previewing} onClose={() => this.setState({previewing: null})} />}

            {this.state.showRemoveFromDeviceConfirmDialog &&
            <Modal id="confirm-device-pack-remove"
                   title={t('dialogs.library.removeFromDevice.title')}
                   content={<p>{t('dialogs.library.removeFromDevice.content')}</p>}
                   buttons={[
                       { label: t('dialogs.shared.no'), onClick: this.dismissRemoveFromDeviceConfirmDialog},
                       { label: t('dialogs.shared.yes'), onClick: this.doRemovePackFromDevice}
                   ]}
                   onClose={this.dismissRemoveFromDeviceConfirmDialog}
            />}
            {this.state.showRemoveFromLibraryConfirmDialog &&
            <Modal id="confirm-library-pack-remove"
                   title={t('dialogs.library.removeFromLibrary.title')}
                   content={<p>{t('dialogs.library.removeFromLibrary.content')}</p>}
                   buttons={[
                       { label: t('dialogs.shared.no'), onClick: this.dismissRemoveFromLibraryConfirmDialog},
                       { label: t('dialogs.shared.yes'), onClick: this.doRemovePackFromLibrary}
                   ]}
                   onClose={this.dismissRemoveFromLibraryConfirmDialog}
            />}
            {this.state.allowEnrichedDialog.show &&
            <Modal id="ask-allow-enriched"
                   title={t('dialogs.library.askAllowEnriched.title')}
                   content={<div><div dangerouslySetInnerHTML={{__html: t('dialogs.library.askAllowEnriched.content')}} ></div>
                       {this.state.allowEnrichedDialog.data && this.state.allowEnrichedDialog.data.options && this.state.allowEnrichedDialog.data.options.queue && <p className="send-queue-note">{t('sendQueue.appliesToAll')}</p>}</div>}
                   buttons={[
                       { label: t('dialogs.shared.no'), onClick: this.dismissEnrichedDialog(false)},
                       { label: t('dialogs.shared.yes'), onClick: this.dismissEnrichedDialog(true)}
                   ]}
                   onClose={this.closeEnrichedDialog}
            />}
            {this.state.confirmConversionDialog.show &&
            <Modal id="ask-confirm-conversion"
                   title={t('dialogs.library.askConfirmConversion.title')}
                   content={<div><div dangerouslySetInnerHTML={{__html: t(this.state.confirmConversionDialog.data.verdict === 'MISMATCH'
                       ? 'dialogs.library.askConfirmConversion.contentMismatch'
                       : 'dialogs.library.askConfirmConversion.content')}} ></div>
                       {this.state.confirmConversionDialog.data.options && this.state.confirmConversionDialog.data.options.queue && <p className="send-queue-note">{t('sendQueue.appliesToAll')}</p>}</div>}
                   buttons={[
                       { label: t('dialogs.library.askConfirmConversion.cancel'), onClick: this.dismissConfirmConversionDialog('cancel')},
                       { label: t('dialogs.library.askConfirmConversion.transfer'), onClick: this.dismissConfirmConversionDialog('transfer')},
                       { label: t('dialogs.library.askConfirmConversion.convert'), onClick: this.dismissConfirmConversionDialog('convert')}
                   ]}
                   onClose={this.dismissConfirmConversionDialog('cancel', false)}
            />}
        </>;
    }

    render() {
        const { t } = this.props;
        let storagePercentage = null;
        let storageStatus = null;
        if (this.state.device.metadata) {
            storagePercentage = (100.00 * this.state.device.metadata.storage.taken / this.state.device.metadata.storage.size).toFixed(0) ;
            if ( storagePercentage > 90 ) {
                storageStatus = "critical";
            } else if (storagePercentage > 75 ) {
                storageStatus = "warning";
            } else { storageStatus = ""; }
            storagePercentage = storagePercentage + '%';
        }
        let defaultImage = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAQAAAAEACAYAAABccqhmAAABhGlDQ1BJQ0MgcHJvZmlsZQAAKJF9kT1Iw0AcxV9TtSIVwXYo4pChOlkQFXHUKhShQqgVWnUwufQLmjQkKS6OgmvBwY/FqoOLs64OroIg+AHi4uqk6CIl/i8ptIj14Lgf7+497t4BQr3MNKtrHNB020wl4mImuyoGXtEDP0KIYFBmljEnSUl0HF/38PH1LsazOp/7c/SrOYsBPpF4lhmmTbxBPL1pG5z3icOsKKvE58RjJl2Q+JHrisdvnAsuCzwzbKZT88RhYrHQxkobs6KpEU8RR1VNp3wh47HKeYuzVq6y5j35C4M5fWWZ6zSHkcAiliBBhIIqSijDRoxWnRQLKdqPd/APuX6JXAq5SmDkWEAFGmTXD/4Hv7u18pMTXlIwDnS/OM7HCBDYBRo1x/k+dpzGCeB/Bq70lr9SB2Y+Sa+1tOgRMLANXFy3NGUPuNwBIk+GbMqu5Kcp5PPA+xl9UxYI3QJ9a15vzX2cPgBp6ip5AxwcAqMFyl7v8O7e9t7+PdPs7wcWhHKC4Zy1VwAAAAZiS0dEAGIAZgBpYoXxPAAAAAlwSFlzAAAuIwAALiMBeKU/dgAAAAd0SU1FB+MGAxMZOGyTlW0AAAAZdEVYdENvbW1lbnQAQ3JlYXRlZCB3aXRoIEdJTVBXgQ4XAAARZklEQVR42u3deXCUdZ7H8U+nO01CQxJycoaQQA6EJKDIIQxgQBEd0QHxAlFUBtQZLbe2tnZra4+q+WN3aqu2yioXBXEQvEDkBhkEucQAwVzcCQQi5CQHOTvpI71/bK07tTvrqOR5+mnyfv1NPb8n36f7TR/P87Rt+YpVAQHok8IYAUAAABAAAAQAAAEAQAAAEAAABAAAAQBAAAAQAAAEAAABAEAAABAAAAQAAAEAQAAAEAAABAAAAQBAAAAQAAAEAAABAEAAABAAAAQAAAEAQAAAEAAABAAAAQBAAAD8GQ5GEHoS4uOUOipFcXFxiooaqIEDBmrAAJdcLpf69+8vZ3i47A6H7GF2ORx2hYWFyW63y2azKRAIqKenRz09PfJ6ffJ6PfJ4POrq7lZHR4fa2trV1taq+psNqqqu1tWrlerq7mboBABmGxQTrZzx4zRixAglxMcrNi5Wg2JiFBER8bO3abPZFBb2Xy/8nE6npP4/+O8DgYBaWlpUV1+vmppalZWXq7C4VF6vlwN0B7AtX7EqwBisISszXVkZGRo5MllDhwzVoEExstlslttPn8+n6poaXSorU37+SVVev8HBIwD4qWKiozTl3knKysrUqJQUuVyukPw76urqVVhUpANfHdKtllYOLAHA/2fEsKH6xYzpykhP15Ahg79/OX4n8Pl8OnP2rHbt3surAj4DwH9LTIjXzBnTlT1+nIYMGWLJl/W98mByODQhN1c52dm6cPGi9uzdp0vll3kAEIC+xx4WptkzZ2jqlClKTh5xR/1P/5eEhYXprrFjNTYrS5fKyvTxp5tVVV3Dg4K3AHe+pMQEzXtgjibk5mrgwIEMRJLX69Wxr49r8+fb+PaAVwB3psz0MXp4/jxlpKfLbrczkD8RHh6u+2fPUm5Ojj7ftl0nThUwFAJwZxg3NkvzH3pQ6WPG3LHv7XtLbOwgvbT8eWVlZmj9xo8UCPDikwCEsL9+83VlZmQwiJ/yntNm0/T7pil5xAitXrNW9TcbGEoQcS3A7QwvjPH9XMnJI/T3f/s3ys0ezzAIQGgqLCpmCLfB5XLp1y+/qEl3T2QYBCD0HDl2XN1cKHNbnE6nXnxhme6bMplhEIDQ4vF4VFZeziBuU3h4uJ5b+iwRIACh51TBaYbQCxwOh5595imlpY5iGAQgdOSfLFB7ezuD6AX9+vXTypdfUkx0FMMgAKEhEAjowsVLDKKXxMYO0muvrGQQBCB0fJN/giH0olEpKVr0+AIGQQBCQ+nZc2pqamYQvSjv/tlKHj6MQRCA0HDu/DmG0IucTqeWLV3CIAzGqcC95Oix45oxfbrh6wQCAbW1tamxqUlNjU1qaW1VS2urmpub1dTULLfbra7ubnW63ers7FRYmF39IyMUGRmpAS6XEhLiFRsbq7jYQRqcNFgJiQmKiY625HUMKSkjNTdvtr48eIgHGAGwtoprlaqtrdXgwYN7dbvNzc2qrq7R1cprqqi4prLyy3J3df2ELfjl8Xi+v1VX+ZWK//MvBsVEa/K9k5SVkaG0tDRFRkZYZq55s2cRAAIQIp8FnDl72wHo7OzU1avXdPb8eRUWFauhscnw/W6+1aJ9+w9o3/4Dcjqdyps1U1MmT9Lw4cODPtOEhATlzZ6pg4eO8AAzADcE6UVJiQn63T//40++SKi1tVXnzl9QYVGxikpKLXOZ7NTJkzT/oXkaOmRIUPejrq5ef/cP/8QDjFcA1lZXf1PXr9/QyJHJf/Hfdnd3q6ysXPknT+pkwbeW/HvyTxYo/2SBFj72qObOyVN4eHhwwpqUqOxxY1V69jwPMgJgbcUlJT8YgIaGBuWfOKl9+w+EzC/ufL59pwqLirVyxUuKj48Pyj5MmzqFABAA6zt09Jgenv+QHI7/GW0gENCVigodPnJU+SdD83ZYVyu/0+/+5fd647VXlZIy0vT1szIzv/9pM/Qe+4S7J/Hmqhd5PB6NzcpQfFycAoGAysrLtX7Dh9q2c7duVFWH/N+Wf/KUxo3NUkxMjKlrO51O1dXVh/wMCUAf0M8ZroiICK3fsFE7du1RQ2PjHfO39fT06PS3hZqQk236XY89Ho8Ki0t4gPUizgQ0wFdHjulf/+3fdeFi2R3593W63Xr7nTXq7Ow0dd1gvPUgAMCfUVNbp02fbTF1zaTERA0Y4GL4BABW8PU3J3T+/AXzHqxhYZqYm8PgCQCs4qNPN8nj8Zj3NuBHnGMBAgCT1NbVq7jEvA/mevtaCwIA3KZde76Q3+83Za3EhAQGTgBgJdU1tbp2rdKUtaKjo+Vw8NuLBACWUnrmjDkP2LAwDQvyxUkEAPhfjh7/Rj09PaasNYTPAQgArKW1tU2NJty7QJLi4+MYOAGA1VRVV5myjsvFyUAEAJZz06Sf+nYG6b4EBAD4oQA0mBOAcKeTYRMAWE1DgzlXPfI1IAGABbndblPW8Xp9DJsAwGo6TQpAd3cXwyYAsBp7mDkPp66uboZNAGA1Zt0hqKuLVwAEAJbjcvU3ZZ2bDY0MmwDAaqKjo01Z50pFBcMmALCaYUOHGr6G2+1WU/Mthk0AYDVJSYmGr9Hc3MygCQCsyIybddyoqmLQBABWkz46zZTPAM6e4+fBCAAsZ/Kkewxfw+v1quDbIoZNAGA1mZmZprz8N/MOxAQA+BEm5mZr8OAkw9f5lv/9CQCsZ25enuFrdHR06sChwwybAMBKUkelaPToNMPXKSkpkdfrZeAEAFayeNGvFGbwRUB+v1/7vjzAsAkArGTKpHs0ZvRow9cpLilRVXUNAycAsAqHw67HFvzS8HW8Xq+2bN3OwAkArOSZJxcrwYQz/woKTqvepJuNEgDgR8hMH6Pp900zfJ329nZt+nwrAycAsAqn06nnlj4ru934G3Pu3fdHtbd3MHQCAKtYsfx5JSUaf9VfZeV3+uOXBxk4AYBVPDg3TxMm5Bq+jtfr1UefbmLgBABWkT1urB5f8Kgpax04+JWuVFxl6AQAVjB0yGC9+MLzCjfhJ7muXavUlm07GDoBgBUMHDhAv3l1lQYMGGD4Wm63W+vWf8DQCQCsIKJfP/3VG7815U4/PT09+uTTzaquqWXwBADB5nDY9eYbv9GI4cNNWe/I0WM6fuIkgycACDabzabXX3tFaamppqxXXn5ZH37Cp/4EAJbw6q9f1tisLFPWqqur01tvr2boBABW8OLzz5nyXb8ktbW16a23V5v2o6IgAPgBzy99RtOmTjFlre7ubr373jrV1tUzeAIAKzz5Z0yfbspaPp9P6zds1IWLZQyeAKAvPfl7enr0yabNOnW6kMETAATbsiXmPfkDgYC27dipw0e/ZvAEAFZ48v9ihnlP/h07d2nvvv0MngAg2JY+/aSpT/6du3Zr1959DN5iHIyg73nqiYWaNWumae/5d+3eo517vmDwBADBtujxBZo7J8+Utfx+vz7ftp0bexAAWMGCR+Zr3oMPmLKWz+fTx59u0pFjxxk8AUCwPTzvAf3ykYdls9kMX8vj8eiDDR/qRMFpBk8AEGz3z5yhxxY8asqT3+3u0tp176vkzFkGTwAQbNnjxmrxE4sM//kuSWrv6NDqd9boYlk5gycACLZhQ4eYdiuv9vZ2vfX2au7lF2I4D+AONWCAS6+9stKUW3nx5CcAsBCbzabXX33FlFt58eQnALCYVS+/qNTUUYav43a79R/vrOHJTwBgFY/Mn6e7755o+Doej0dr1/1Bl8ovM3QCACvITB+jR+Y/ZPg6Pr9fH2z4kK/6CACson9kpJa/sMzwT/wDgYC2btvOST4EAFayfNlSxcXGGr7Owa8OcW4/AYCVTJ50t3Jzcwxfp7CoSJ9s3sLACQCs9NJ/8aKFhp/me+PGDb373vsMnADASp5+8gnFxMQYukZ7e7vefmeNfD4/AycAsIqRI4br3kn3GLqG3+/X+g0bVX+zgYETAFjJk08slMNh7OUcR44eU1HJGYZNAGAlE3OzlZGRYegaVVVV+njTZwybAMBqHpr3oKHb93g8WveHDxQIBBg2AYCVjL8rS6mjjD3X//CRo6q8foNhEwBYzfx58wzdfv3Nm9qydTuDJgCwmqTEBI0enWbY9gOBgD7bslX+nh6GTQBgNXmzZxl6e6/zFy6osLiEQRMAWFFuTrZh2/b7/dq6bQdDJgCwoqyMdMXFxRm2/ZKSUl377jqDJgCwouzx4wzbts/n02dbtzFkAgCrMvI2XxcvXeJ0XwIAq7LZbBo+bJhh2z90+ChDJgCwqpTkEYqIiDBk27W1dSou5Xx/AgDLSk4eYdi2S0pLGTABgJUNTkoybNunC4sYMAGAlcXHG/P1X0tLiyquXmPABABW5nK5DNnuVZ78BADWZ9Ttviu/+47hEgBYndOgANyoqmK4BAB99RVA+ZUKhksAYHVG3Pa7vb1dbW3tDJcAwOqMuD6/o6ODwRIAhEQA/L1/T36P18tgCQBCgcfjCYltggDAAF3uLgIAAtBXdXR29vo2fT4fgyUACAUtLS0MAQSgr2pobGQIIAB9VU1Nbe8f/DAOPwFASCi7fEU9vXwugNPpZLAEAKHA4/Ho1q1bvbrN6KgoBtvH2ZavWMWvPwK8AgBAAAAQAAAEAAABAEAAABAAAAQAAAEAQAAAEAAABAAAAQBAAAAQAAAEAAABAEAAABAAAAQAAAEAQAAAEAAABABAL3MwgtAWEx2lqZMnKzU1RYmJiYqKilJkRITsdrskyev1qrOzUy0traquqVFZWbnyT52Sz+dneOCXgULVxNwczZ1zv9JSU79/sv9YXV1dOnf+vHbt3qvrVdUMkwAgVAwbOkRLnnlK6WPG3Pa2/H6/ThUU6KNPNsvd1cVweQsAK/vFfVP15OInFBER0Svbs9vtmjplikanjdaa99ap4lolQ+5j+BAwRDw87wEtXfJsrz35/1RCQrzefOO3GpuZwaAJAKxmzuyZevyxBQoLM+5wRUZG6pWVKzRqZDIDJwCwiqzMdC1a+CvZbDbD14qMjNSqlSvk6t+fwRMABJvT6dSyJUsUHh5u2ppxsbFavmwpwycACLbFCx9XQkK86evm5uZoYm4OB4AAIFgGxURr2tQpQVt/waOPcBAIAIJl/rwH1a9fv6CtP3zYME3IGc+BIAAIhtyc7KDvw/Rp0zgQBABmG5OWqtjY2ODvx5jRHAwCALONH3eXJfbD5XIpdVQKB4QAwEzDhg61zL6kj07jgBAAmCkmJsYy+xIfH88BIQAwkxHn+/9c/ftHckAIAMzkcNgtsy/hjnAOCAGAmax0tx6vz8sBIQAwU5eFbs7R2enmgBAAmOnWrVuW2ZeGhgYOCAGAmapraiyzL+VXKjggBABmOnP2nCX2o6OjQ1cqrnJACADMVHb5ipqamoP/v//lyxwMAoBgKCktDfo+HP8mnwNBABAMe77YJ4/HE7T1q6qqVFhcyoEgAAiG5lst+ib/RNDW37FrDweBACCYNm3ZqobGRvPffpSU6tuiYg4AAUAweTwebdj4kbxe887Ga2pq1vsbNjJ8AgArOHfhorZu265AwPhfcHO7u7R6zVq1t3cweAIAq9h/8JB27tptaATc7i69u3atKq5eY+B9CL8NGCJ27vlCrW1tWrxoYa/fLLShsVFr3nufk34IAKzs8NGvdeVKhZY8+7RGp93+nXp6enpUcPq0Pvx4kzrdXPTTF/Hz4CHqnom5mpN3v1JHjZLd/tPuH9DV1aXz5y9o994vVHn9BsPkFQBCzenCYp0uLNagmGhNnTJZqaNSlJiQqKioKEVE9JPD4VAgEJDP51NnZ6daWlpVU1ujS2Xlyj9ZYOo3CyAAMEjzrRbt3befQeBn4VsAgAAAIAAACAAAAgCAAAAgAAAIAAACAIAAACAAAAgAAAIAgAAAIAAACAAAAgCAAAAgAAAIAAACAIAAACAAAAgAAAIAgAAAIAAACAAAAgCAAAD4qf4TGBBAorj2/ywAAAAASUVORK5CYII=';
        return (
            <div className="pack-library">

                {this.renderDialogs()}

                {/* Device view, if plugged */}
                {this.state.device.metadata && <div className="plugged-device">
                    <div className="header">
                        <h4>{t('library.device.title')}</h4>
                        <div className="header-uuid" title={this.state.device.metadata.uuid}><strong>{t('library.device.uuid')}</strong> {this.state.device.metadata.uuid}</div>
                        <div><strong>{t('library.device.serial')}</strong> {this.state.device.metadata.serial || '-'}</div>
                        <div><strong>{t('library.device.firmware')}</strong> {this.state.device.metadata.firmware || '-'}</div>
                        <div><strong>{t('library.device.sdcardsize')}</strong> { (this.state.device.metadata.storage.size / 1073741824 ).toFixed(1) || '-'} {t('library.device.sdcardunit')}</div>
                        <div><strong>{t('library.device.packs.length')}</strong> { this.state.device.packs.length || '-' }</div>
                        {this.state.device.metadata.error && <p><strong>DEVICE HAS ERRORS</strong></p>}
                        {this.renderDeviceRepair()}
                        <div className="progress">
                            <div className={`progress-bar ${storageStatus}`} role="progressbar" style={{width: storagePercentage}} aria-valuenow={this.state.device.metadata.storage.taken} aria-valuemin="0" aria-valuemax={this.state.device.metadata.storage.size}>{storagePercentage}</div>
                        </div>
                    </div>
                    <div className={`device-dropzone ${this.state.dragging !== null ? 'highlighted-dropzone' : ''}`}
                         onDrop={this.onDropPackIntoDevice}
                         onDragOver={event => { event.preventDefault(); }}
                         onDragLeave={event => {
                             // Get the location of the dropzone
                             var rect = event.target.closest('.device-dropzone').getBoundingClientRect();
                             // Check whether the mouse coordinates are outside the dropzone rectangle
                             if(event.clientX > rect.left + rect.width || event.clientX < rect.left || event.clientY > rect.top + rect.height || event.clientY < rect.top) {
                                 console.log("Reset reorder to beforeReordering: %o", this.state.beforeReordering);
                                 this.setState({
                                     device: {
                                         ...this.state.device,
                                         packs: [...this.state.beforeReordering]
                                     }
                                 });
                             }
                         }}>
                        {this.state.device.packs.length === 0 && <div className="empty">{t('library.device.empty')}</div>}
                        {this.state.device.packs.length > 0 && <div className="pack-grid">
                            {this.state.device.packs.map((pack,idx) =>
                                <div key={pack.uuid}
                                     draggable={true}
                                     className={`pack-tile pack-${pack.format} pack-draggable ${pack.nightModeAvailable && 'pack-night-mode'}`}
                                     onDragStart={event => {
                                         event.dataTransfer.setData("device-pack", JSON.stringify(pack));
                                         this.setState({dragging: "device-pack", reordering: pack, beforeReordering: [...this.state.device.packs]});
                                     }}
                                     onDragEnter={event => {
                                         let data = this.state.reordering;
                                         if (data && data.uuid !== pack.uuid) {
                                             // A copy: this.state.device.packs is the Redux store's own array
                                             let reordered = [...this.state.device.packs];
                                             let draggedIndex = reordered.findIndex(p => p.uuid === data.uuid);
                                             if (draggedIndex < idx) {
                                                 // Going down, place dragged item right after the dragged-over pack
                                                 reordered.splice(idx + 1, 0, reordered[draggedIndex]);
                                                 reordered.splice(draggedIndex, 1);
                                             }
                                             if (draggedIndex > idx) {
                                                 // Going up, place dragged item right before the dragged-over pack
                                                 reordered.splice(idx, 0, reordered[draggedIndex]);
                                                 reordered.splice(++draggedIndex, 1);
                                             }
                                             this.setState({
                                                 device: {
                                                     ...this.state.device,
                                                     packs: reordered
                                                 }
                                             });
                                         }
                                     }}
                                     onDragEnd={event => {
                                         // Reorder on device only if order changed
                                         if (this.state.beforeReordering.reduce((acc,p)=>acc+','+p.uuid, '') !== this.state.device.packs.reduce((acc,p)=>acc+','+p.uuid, '')) {
                                             console.log("Order changed, reordering...");
                                             let uuids = this.state.device.packs.map(p => p.uuid);
                                             this.props.reorderOnDevice(uuids);
                                         }
                                         this.setState({dragging: null,  reordering: null});
                                     }}>
                                    <div className="pack-format">
                                        <span>{t(`library.format.${pack.format}`)}</span>
                                    </div>
                                    <div className="pack-thumb" title={pack.nightModeAvailable && t('library.nightMode')}>
                                        <img src={pack.image || defaultImage} alt="" width="128" height="128" draggable={false} loading="lazy" decoding="async" />
                                        <div className="pack-version"><span>{`v${pack.version}`}</span></div>
                                        {pack.official && <div className="pack-ribbon"><span>{t('library.official')}</span></div>}
                                    </div>
                                    <div className="pack-title">
                                        <span title={pack.uuid}>{pack.title && pack.title !== "MISSING_PACK_TITLE" ? pack.title : pack.uuid}</span>&nbsp;
                                    </div>
                                    {!pack.official && <div className="pack-update">
                                        <DeviceUpdateButton status={this.deviceUpdateOf(pack.uuid)} onUpdate={() => this.updatePackOnDevice(pack.uuid)} />
                                    </div>}
                                    <div className="pack-actions">
                                        <button className="pack-action" onClick={this.onRemovePackFromDevice(pack.uuid)}>
                                            <span className="glyphicon glyphicon-trash"
                                                  title={t('library.device.removePack')} />
                                        </button>
                                    </div>
                                </div>
                            )}
                        </div>}
                    </div>
                </div>}
                {/* Local pack library */}
                {this.state.library && <div className="local-library">
                    <div className="header">
                        <h4>{t('library.local.title')}</h4>
                        {this.state.library.metadata && <div><strong>{t('library.local.path')}</strong> {this.state.library.metadata.path}</div>}
                        <div><strong>{t('library.local.packs.length')}</strong> { this.state.library.packs.length || '-' }</div>
                        <input type="file" id="upload" multiple accept=".zip,.pack" style={{visibility: 'hidden', position: 'absolute'}} onChange={this.packAddFileSelected} />
                        <div className="library-search">
                            <input type="search" value={this.state.search || ''} placeholder={t('library.local.search')} aria-label={t('library.local.search')} onChange={e => this.setState({search: e.target.value})} />
                        </div>
                        <button type="button" title={t('library.local.addPack')} className="btn btn-primary ui-btn ui-btn-primary library-import" onClick={this.showAddFileSelector}><span className="glyphicon glyphicon-import"/> {t('library.local.import')}</button>
                        <YoutubePack />
                        <LibraryPurge />
                        <div className="format-legend">
                            <span className="legend-archive">{t('library.format.archive')}</span>
                            <span className="legend-raw">{t('library.format.raw')}</span>
                            <span className="legend-fs">{t('library.format.fs')}</span>
                        </div>
                        <div className="editor-actions">
                            <p><button className="library-action" onClick={this.onCreateNewPackInEditor}>{t('library.local.empty.link1')}</button> <button className="library-action" onClick={this.onOpenSamplePackInEditor}>{t('library.local.empty.link2')}</button> {t('library.local.empty.suffix')}</p>
                        </div>
                    </div>
                    {this.renderSendQueue(matchingGroups(this.state.library.packs, this.state.search).map(g => g.uuid))}
                    <div className={`library-dropzone ${this.state.dragging === 'device-pack' ? 'highlighted-dropzone' : ''}`}
                         onDrop={this.onDropPackIntoLibrary}
                         onDragOver={event => { event.preventDefault(); }}>
                        {this.state.library.packs.length === 0 && <div className="empty">
                            <p>{t('library.local.empty.header')}</p>
                        </div>}
                        {this.state.library.packs.length > 0 && matchingGroups(this.state.library.packs, this.state.search).length === 0 && <div className="empty empty-search">
                            <p>{t('library.local.noMatch')}</p>
                        </div>}
                        {this.state.library.packs.length > 0 && <div className="pack-grid">
                            {matchingGroups(this.state.library.packs, this.state.search).map(group =>
                                <div key={group.uuid}
                                     title={group.uuid}
                                     draggable={this.isPackDraggable(group.packs[0])}
                                     className={`pack-tile ${this.isPackDraggable(group.packs[0]) ? 'pack-draggable' : 'pack-not-draggable'} ${group.packs[0].nightModeAvailable && 'pack-night-mode'} ${this.sendQueue.isSelected(group.uuid) ? 'send-selected' : ''}`}
                                     onClick={event => this.sendQueue.click(group.uuid, event, matchingGroups(this.state.library.packs, this.state.search).map(g => g.uuid))}
                                     onDragStart={event => {
                                         // Drag first pack
                                         event.dataTransfer.setDragImage(event.target.querySelector('.pack-entry'), 0, 0);
                                         event.dataTransfer.setData("local-library-pack", JSON.stringify(group));
                                         this.setState({dragging: "local-library-pack"});
                                     }}
                                     onDragEnd={event => {
                                         this.setState({dragging: null});
                                     }}>
                                    <div className="pack-left">
                                        <div className="pack-title">
                                            {this.renderSelectBox(group.uuid, group.packs[0].title && group.packs[0].title !== "MISSING_PACK_TITLE" ? group.packs[0].title : group.uuid)}{' '}
                                            <span>{group.packs[0].title && group.packs[0].title !== "MISSING_PACK_TITLE" ? group.packs[0].title : group.uuid}</span>&nbsp;
                                        </div>
                                        <div className="pack-thumb" title={group.packs[0].nightModeAvailable && t('library.nightMode')}>
                                            <img src={group.packs[0].image || defaultImage} alt="" width="128" height="128" draggable={false} loading="lazy" decoding="async" />
                                            {group.packs[0].official && <div className="pack-ribbon"><span>{t('library.official')}</span></div>}
                                        </div>
                                    </div>
                                    <div className="pack-right">
                                        {group.packs.map((p,idx) => {
                                            return <div key={p.path} title={p.path} className={`pack-entry pack-${p.format} ${idx === 0 && 'latest'}`}>
                                                <div className="pack-filename">
                                                    {p.format === 'archive' && <span role="img" aria-label="archive" title={t('library.format.archive')}>&#x1f5dc;</span>}
                                                    {p.format === 'raw' && <span role="img" aria-label="raw" title={t('library.format.raw')}>&#x1f4e6;</span>}
                                                    {p.format === 'fs' && <span role="img" aria-label="fs" title={t('library.format.fs')}>&#x1f4c2;</span>}
                                                    {p.path}
                                                </div>
                                                <div className="pack-format-badge">{t(`library.format.${p.format}`)}</div>
                                                <div className="pack-version"><span>{`v${p.version}`}</span></div>
                                                {p.size >= 0 && <div className="pack-size" title={t('library.local.size')}>{t(`libraryPurge.units.${sizeUnit(p.size).key}`, { value: sizeUnit(p.size).value })}</div>}
                                                <div className="pack-actions">
                                                    <button className="pack-action" onClick={() => this.setState({previewing: {...p, uuid: group.uuid}})} aria-label={t('library.local.previewPack')}>
                                                        <span className="glyphicon glyphicon-eye-open" title={t('library.local.previewPack')} />
                                                    </button>
                                                    {p.format !== 'archive' && <button className="pack-action" onClick={this.onConvertLibraryPack(p, 'archive')}>
                                                        <span role="img" aria-label="to archive" title={t('library.local.convertPackToArchive')}>&#10132;&#x1f5dc;</span>
                                                    </button>}
                                                    {p.format === 'archive' && <>
                                                        <button className="pack-action" onClick={this.onEditLibraryPack(p)}>
                                                            <span className="glyphicon glyphicon-edit" title={t('library.local.editPack')} />
                                                        </button>
                                                        <button className="pack-action" onClick={this.onConvertLibraryPack(p, 'raw')}>
                                                            <span role="img" aria-label="to raw" title={t('library.local.convertPackToRaw')}>&#10132;&#x1f4e6;</span>
                                                        </button>
                                                        <button className="pack-action" onClick={this.onConvertLibraryPack(p, 'fs')}>
                                                            <span role="img" aria-label="to fs" title={t('library.local.convertPackToFs')}>&#10132;&#x1f4c2;</span>
                                                        </button>
                                                    </>}
                                                    <button className="pack-action" onClick={this.onRemovePackFromLibrary(p.path)}>
                                                        <span className="glyphicon glyphicon-trash" title={t('library.local.removePack')} />
                                                    </button>
                                                </div>
                                            </div>;
                                        })}
                                    </div>
                                </div>
                            )}
                        </div>}
                    </div>
                </div>}
            </div>
        );
    }
}

PackLibrary.contextType = AppContext;

export const mapStateToProps = (state, ownProps) => ({
    device: state.device,
    library: state.library,
    settings: state.settings
});

export const mapDispatchToProps = (dispatch, ownProps) => ({
    addFromLibrary: (uuid, path, format, driver, context, options) => dispatch(actionAddFromLibrary(uuid, path, format, driver, context, ownProps.t, options)),
    updateOnDevice: (uuid, path, format, driver, context, options) => dispatch(actionAddFromLibrary(uuid, path, format, driver, context, ownProps.t, {...options, mode: 'update'})),
    refreshDevice: () => dispatch(actionRefreshDevice(ownProps.t)),
    removeFromDevice: (uuid) => dispatch(actionRemoveFromDevice(uuid, ownProps.t)),
    reorderOnDevice: (uuids) => dispatch(actionReorderOnDevice(uuids, ownProps.t)),
    addToLibrary: (uuid, driver, context) => dispatch(actionAddToLibrary(uuid, driver, context, ownProps.t)),
    loadLibraryPackInEditor: (path) => dispatch(actionLoadLibraryPackInEditor(path, ownProps.t)),
    convertPackInLibrary: (uuid, path, format, allowEnriched, context) => dispatch(actionConvertInLibrary(uuid, path, format, allowEnriched, context, ownProps.t)),
    removeFromLibrary: (path) => dispatch(actionRemoveFromLibrary(path, ownProps.t)),
    removeManyFromLibrary: (paths) => dispatch(actionRemoveManyFromLibrary(paths, ownProps.t)),
    uploadPackToLibrary: (path, packData) => dispatch(actionUploadToLibrary(null, path, packData, ownProps.t)),
    uploadPacksToLibrary: (files) => dispatch(actionUploadManyToLibrary(files, ownProps.t)),
    verifyConversion: (sourcePath, convertedPath) => verifyConversion(sourcePath, convertedPath),
    estimateOnDevice: (paths, format) => estimateOnDevice(paths, format),
    createPackInEditor: () => dispatch(actionCreatePackInEditor(ownProps.t)),
    loadSampleInEditor: () => dispatch(actionLoadSampleInEditor(ownProps.t)),
    setAllowEnriched: (allowEnriched) => dispatch(setAllowEnriched(allowEnriched))
});

export default withTranslation()(
    connect(
        mapStateToProps,
        mapDispatchToProps
    )(PackLibrary)
)
