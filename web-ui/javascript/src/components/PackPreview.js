/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import PropTypes from "prop-types";
import {withTranslation} from "react-i18next";

import Modal from "./Modal";
import PackViewer from "./viewer/PackViewer";
import {fetchLibraryPackDetails, fetchLibraryPackEntry} from "../services/library";
import {mimeType} from "../utils/reader";
import {createSimulation, storyStats, splitDuration} from "../utils/storySimulator";
import {sizeUnit} from "../utils/purge";

import './PackPreview.css';

const AUTOPLAY_WITHOUT_AUDIO_MILLIS = 1500;

function blobText(blob) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = () => reject(reader.error);
        reader.readAsText(blob, 'UTF-8');
    });
}

/*
 * A preview of a library pack: its metadata, and a simulation of the story teller.
 *
 * Opens on what the library listing already holds (title, thumbnail, description, version), then reads
 * story.json and asks the backend for the rest (size, audio duration) at the same time. The simulation
 * loads the assets of the node shown, entry by entry, as object URLs released when the preview closes:
 * a pack of a gigabyte opens as fast as a small one.
 *
 * Only archives are simulated. A raw or FS pack holds its assets in device formats (sector-addressed,
 * enciphered for FS) that the browser cannot read entry by entry: the backend would have to decode the
 * whole pack first, which is what a conversion to archive does.
 */
export class PackPreview extends React.Component {

    constructor(props) {
        super(props);
        this.state = {
            details: null,
            detailsError: false,
            story: null,
            storyError: null,
            simulation: null,
            simState: null,
            started: false,
            moves: 0,
            image: null,
            audio: null,
            assetError: false,
            message: null,
            options: {translucent: false, overlay: true, autoplay: true}
        };
        // Asset name -> Promise of its object URL; URLs to release on close
        this.assets = new Map();
        this.urls = [];
        this.closed = false;
        // Moves on from a node with autoplay and no audio, as the device does
        this.autoplayTimer = null;
        this.dialogRef = React.createRef();
    }

    componentDidMount() {
        document.addEventListener('keydown', this.onKeyDown);
        const {pack} = this.props;
        const fetchDetails = this.props.fetchDetails || fetchLibraryPackDetails;
        fetchDetails(pack.path)
            .then(details => !this.closed && this.setState({details}))
            .catch(() => !this.closed && this.setState({detailsError: true}));
        if (pack.format === 'archive') {
            this.loadStory();
        }
        if (this.dialogRef.current) {
            this.dialogRef.current.focus();
        }
    }

    componentWillUnmount() {
        this.closed = true;
        clearTimeout(this.autoplayTimer);
        document.removeEventListener('keydown', this.onKeyDown);
        this.urls.forEach(url => URL.revokeObjectURL(url));
        this.urls = [];
        this.assets.clear();
    }

    loadEntry = (entry) => {
        const fetchEntry = this.props.fetchEntry || fetchLibraryPackEntry;
        return fetchEntry(this.props.pack.path, entry);
    };

    loadStory = () => {
        this.loadEntry('story.json')
            .then(blob => {
                if (!blob) {
                    throw Object.assign(new Error('story.json'), {code: 'NO_STORY'});
                }
                return blobText(blob);
            })
            .then(text => {
                let story;
                try {
                    story = JSON.parse(text);
                } catch (e) {
                    throw Object.assign(new Error(e.message), {code: 'INVALID_STORY'});
                }
                const simulation = createSimulation(story);
                if (this.closed) {
                    return;
                }
                const simState = simulation.start();
                this.setState({story, simulation, simState}, () => this.showAssets(simState, false));
            })
            .catch(e => !this.closed && this.setState({storyError: this.storyErrorText(e)}));
    };

    // The simulator's and the reader's refusals have a translation; others (network, backend) a message
    storyErrorText = (e) => {
        const {t} = this.props;
        if (e && e.code) {
            return t(`preview.simulation.errors.${e.code}`);
        }
        return (e && e.message) || String(e);
    };

    /** The object URL of an asset of the pack, loaded once, or null if the pack has none by that name. */
    asset = (name) => {
        if (!name) {
            return Promise.resolve(null);
        }
        if (!this.assets.has(name)) {
            this.assets.set(name, this.loadEntry('assets/' + name).then(blob => {
                if (!blob) {
                    return null;
                }
                const url = URL.createObjectURL(new Blob([blob], {type: mimeType(name)}));
                if (this.closed) {
                    URL.revokeObjectURL(url);
                    return null;
                }
                this.urls.push(url);
                return url;
            }).catch(e => {
                // Not kept: a later visit of the node tries again
                this.assets.delete(name);
                throw e;
            }));
        }
        return this.assets.get(name);
    };

    /** Loads the image (and the audio once started) of the node of `simState`, if it is still shown. */
    showAssets = (simState, withAudio) => {
        const {simulation} = this.state;
        const names = simulation.assets(simState);
        Promise.all([this.asset(names.image), withAudio ? this.asset(names.audio) : Promise.resolve(null)])
            .then(([image, audio]) => {
                if (this.closed || this.state.simState !== simState) {
                    return;
                }
                this.setState({image, audio, assetError: false});
                if (withAudio && !audio) {
                    this.autoplayWithoutAudio(simState);
                }
                // The wheel's neighbours, ahead of time: images are small
                simulation.neighbourImages(simState).forEach(name => this.asset(name).catch(() => {}));
            })
            .catch(() => !this.closed && this.state.simState === simState && this.setState({assetError: true}));
    };

    autoplayWithoutAudio = (simState) => {
        const {simulation, options} = this.state;
        if (!simulation.controls(simState).autoplay || !options.autoplay) {
            return;
        }
        clearTimeout(this.autoplayTimer);
        // A short wait shows the node, and keeps a loop of such nodes from spinning
        this.autoplayTimer = setTimeout(() => {
            if (!this.closed && this.state.simState === simState && this.state.started && this.state.options.autoplay) {
                this.onAdvance();
            }
        }, this.props.autoplayDelay !== undefined ? this.props.autoplayDelay : AUTOPLAY_WITHOUT_AUDIO_MILLIS);
    };

    move = (result) => {
        const {t} = this.props;
        const messages = {
            end: t('preview.simulation.end'),
            exit: t('preview.simulation.exit'),
            broken: t('preview.simulation.broken')
        };
        if (result.event === 'none') {
            return;
        }
        if (result.state === this.state.simState) {
            this.setState({message: messages[result.event] || null});
            return;
        }
        this.setState(state => ({
            simState: result.state,
            moves: state.moves + 1,
            message: messages[result.event] || null,
            image: null,
            audio: null
        }), () => this.showAssets(result.state, true));
    };

    /** The first button pressed starts the simulation: the first node's audio plays from then on. */
    act = (action) => {
        const {simulation, simState, started} = this.state;
        if (!simulation) {
            return;
        }
        if (!started) {
            this.setState(state => ({started: true, moves: state.moves + 1}), () => this.showAssets(simState, true));
            return;
        }
        this.move(action(simulation, simState));
    };

    // The end of the audio of an autoplay node, or OK in the viewer, which checks the controls itself
    onAdvance = () => this.act((sim, state) => sim.ok(state));
    // OK pressed: only when the node accepts it (before the start, every button starts the simulation)
    onOk = () => {
        const {simulation, simState, started, options} = this.state;
        if (simulation && started) {
            if (!simulation.canOk(simState, options.autoplay)) {
                return;
            }
            // In place of autoplay (OK disabled), as in the viewer: once the audio has ended
            const player = !simulation.controls(simState).ok && this.dialogRef.current
                && this.dialogRef.current.querySelector('audio');
            if (player && !player.ended) {
                return;
            }
        }
        this.onAdvance();
    };
    onHome = () => this.act((sim, state) => sim.home(state));
    onWheelLeft = () => this.act((sim, state) => sim.wheel(state, -1));
    onWheelRight = () => this.act((sim, state) => sim.wheel(state, 1));
    onRestart = () => {
        const {simulation} = this.state;
        if (simulation) {
            this.move({state: simulation.start(), event: 'moved'});
        }
    };

    onOptionToggled = (name) => {
        this.setState(state => ({options: {...state.options, [name]: !state.options[name]}}));
    };

    onKeyDown = (e) => {
        // Escape closes the preview through its Modal
        if (e.key === 'Escape' || e.key === 'Esc') {
            return;
        }
        // Keys typed in a control are that control's: Enter on a focused button or link presses it;
        // the arrows still turn the wheel after a click on a button, except in a field or the player
        const target = e.target;
        const arrow = e.key === 'ArrowLeft' || e.key === 'ArrowRight';
        const controls = arrow ? 'audio, input, select, textarea' : 'button, a, audio, input, select, textarea';
        if (target && target.closest && target.closest(controls)) {
            return;
        }
        const keys = {
            ArrowLeft: this.onWheelLeft,
            ArrowRight: this.onWheelRight,
            Enter: this.onOk,
            Home: this.onHome,
            Backspace: this.onHome
        };
        if (keys[e.key] && this.state.simulation) {
            e.preventDefault();
            keys[e.key]();
        }
    };

    formatSize = (bytes) => {
        const {t} = this.props;
        const unit = sizeUnit(bytes);
        return t(`libraryPurge.units.${unit.key}`, {value: unit.value});
    };

    formatDuration = (seconds) => {
        const {t} = this.props;
        const d = splitDuration(seconds);
        if (d.hours > 0) {
            return t('preview.duration.hours', {hours: d.hours, minutes: String(d.minutes).padStart(2, '0')});
        }
        if (d.minutes > 0) {
            return t('preview.duration.minutes', {minutes: d.minutes, seconds: String(d.seconds).padStart(2, '0')});
        }
        return t('preview.duration.seconds', {seconds: d.seconds});
    };

    renderAudioDuration() {
        const {t, pack} = this.props;
        const {details, detailsError} = this.state;
        if (pack.format !== 'archive') {
            return <span className="pack-preview-muted">{t('preview.info.durationUnavailable')}</span>;
        }
        if (detailsError) {
            return <span className="pack-preview-muted">{t('preview.info.unknown')}</span>;
        }
        if (!details) {
            return <span className="pack-preview-muted">{t('preview.info.loading')}</span>;
        }
        const audio = details.audio;
        if (!audio || audio.measured === 0) {
            return <span className="pack-preview-muted">{t('preview.info.unknown')}</span>;
        }
        const approximate = audio.estimated > 0 || audio.unmeasured > 0;
        return <span>
            {approximate ? t('preview.info.approximately', {value: this.formatDuration(audio.seconds)}) : this.formatDuration(audio.seconds)}
            {audio.unmeasured > 0 && <span className="pack-preview-muted"> {t('preview.info.unmeasured', {count: audio.unmeasured})}</span>}
        </span>;
    }

    renderCounts() {
        const {t, pack} = this.props;
        const {story, storyError, details, detailsError} = this.state;
        let stats = null;
        if (story) {
            stats = storyStats(story);
        } else if (details && details.stageNodes !== undefined) {
            stats = {stageNodes: details.stageNodes, stories: details.stories};
        }
        const pending = <span className="pack-preview-muted">{t('preview.info.loading')}</span>;
        const unknown = <span className="pack-preview-muted">{t('preview.info.unknown')}</span>;
        const failed = pack.format === 'archive' ? storyError : (detailsError || (details && details.stageNodes === undefined));
        const nodes = stats
            ? (stats.actionNodes !== undefined
                ? t('preview.info.nodesValue', {stages: stats.stageNodes, actions: stats.actionNodes})
                : t('preview.info.stagesValue', {stages: stats.stageNodes}))
            : null;
        return <>
            <dt>{t('preview.info.nodes')}</dt>
            <dd>{nodes || (failed ? unknown : pending)}</dd>
            <dt>{t('preview.info.stories')}</dt>
            <dd>{stats ? stats.stories : (failed ? unknown : pending)}</dd>
        </>;
    }

    renderInfo() {
        const {t, pack} = this.props;
        const {details, detailsError} = this.state;
        const title = pack.title && pack.title !== 'MISSING_PACK_TITLE' ? pack.title : pack.uuid;
        return <div className="pack-preview-info">
            <div className="pack-preview-thumb">
                {pack.image
                    ? <img src={pack.image} alt="" width="128" height="128"/>
                    : <span className="glyphicon glyphicon-picture" aria-hidden="true"/>}
            </div>
            <div className="pack-preview-text">
                <h5 className="pack-preview-title">{title}</h5>
                {pack.description
                    ? <p className="pack-preview-description">{pack.description}</p>
                    : <p className="pack-preview-description pack-preview-muted">{t('preview.info.noDescription')}</p>}
                <dl className="pack-preview-facts">
                    <dt>{t('preview.info.format')}</dt>
                    <dd><span className={`pack-preview-format pack-preview-format-${pack.format}`}>{t(`library.format.${pack.format}`)}</span></dd>
                    <dt>{t('preview.info.version')}</dt>
                    <dd>{`v${pack.version}`}</dd>
                    <dt>{t('preview.info.size')}</dt>
                    <dd>{details ? this.formatSize(details.size) : <span className="pack-preview-muted">{t(detailsError ? 'preview.info.unknown' : 'preview.info.loading')}</span>}</dd>
                    {this.renderCounts()}
                    <dt>{t('preview.info.duration')}</dt>
                    <dd>{this.renderAudioDuration()}</dd>
                    <dt>{t('preview.info.file')}</dt>
                    <dd className="pack-preview-path">{pack.path}</dd>
                    {pack.nightModeAvailable && <>
                        <dt>{t('preview.info.nightMode')}</dt>
                        <dd>{t('dialogs.shared.yes')}</dd>
                    </>}
                </dl>
            </div>
        </div>;
    }

    renderSimulation() {
        const {t, pack} = this.props;
        const {simulation, simState, storyError, started, moves, image, audio, assetError, message, options} = this.state;
        if (pack.format !== 'archive') {
            return <div className="pack-preview-unavailable" role="note">
                <p><strong>{t('preview.simulation.unavailableTitle')}</strong></p>
                <p>{t('preview.simulation.unavailable')}</p>
            </div>;
        }
        if (storyError) {
            return <div className="pack-preview-unavailable" role="alert">
                <p><strong>{t('preview.simulation.errorTitle')}</strong></p>
                <p>{t('preview.simulation.error', {error: storyError})}</p>
            </div>;
        }
        if (!simulation) {
            return <div className="pack-preview-loading" role="status">{t('preview.simulation.loading')}</div>;
        }
        const node = simulation.stage(simState);
        return <div className={`pack-preview-simulation ${options.translucent ? 'translucent' : ''}`}>
            <PackViewer
                key={moves}
                image={image}
                audio={started ? audio : null}
                wheelClickedLeft={this.onWheelLeft}
                wheelClickedRight={this.onWheelRight}
                homeClicked={this.onHome}
                okClicked={this.onAdvance}
                closeClicked={this.props.onClose}
                // Before the first button press, every button starts the simulation
                controls={started ? simulation.controls(simState) : {wheel: true, ok: true, home: true, pause: false, autoplay: false}}
                options={options}
                optionToggled={this.onOptionToggled}
            />
            <div className="pack-preview-status" aria-live="polite">
                {!started && <span>{t('preview.simulation.start')}</span>}
                {started && <span className="pack-preview-node">{(node && node.name) || ''}</span>}
                {message && <span className="pack-preview-message">{message}</span>}
                {assetError && <span className="pack-preview-message">{t('preview.simulation.assetError')}</span>}
            </div>
            <div className="pack-preview-controls">
                <button type="button" className="btn ui-btn" onClick={this.onWheelLeft} aria-label={t('preview.simulation.left')} title={t('preview.simulation.left')}>
                    <span className="glyphicon glyphicon-chevron-left" aria-hidden="true"/>
                </button>
                <button type="button" className="btn ui-btn ui-btn-primary" onClick={this.onOk}>{t('preview.simulation.ok')}</button>
                <button type="button" className="btn ui-btn" onClick={this.onWheelRight} aria-label={t('preview.simulation.right')} title={t('preview.simulation.right')}>
                    <span className="glyphicon glyphicon-chevron-right" aria-hidden="true"/>
                </button>
                <button type="button" className="btn ui-btn" onClick={this.onHome} aria-label={t('preview.simulation.home')} title={t('preview.simulation.home')}>
                    <span className="glyphicon glyphicon-home" aria-hidden="true"/>
                </button>
                <button type="button" className="btn ui-btn" onClick={this.onRestart} disabled={!started}>{t('preview.simulation.restart')}</button>
            </div>
            <p className="pack-preview-keys pack-preview-muted">{t('preview.simulation.keys')}</p>
        </div>;
    }

    render() {
        const {t, pack} = this.props;
        const title = pack.title && pack.title !== 'MISSING_PACK_TITLE' ? pack.title : pack.uuid;
        return <div ref={this.dialogRef} tabIndex="-1" className="pack-preview-root" aria-label={t('preview.title', {title})}>
            <Modal id="pack-preview"
                   className="pack-preview-modal"
                   title={t('preview.title', {title})}
                   content={<div className="pack-preview">
                       {this.renderInfo()}
                       {this.renderSimulation()}
                   </div>}
                   buttons={[{label: t('preview.close'), onClick: this.props.onClose}]}
                   onClose={this.props.onClose}
            />
        </div>;
    }
}

PackPreview.propTypes = {
    pack: PropTypes.shape({
        path: PropTypes.string.isRequired,
        format: PropTypes.string.isRequired,
        uuid: PropTypes.string,
        title: PropTypes.string,
        description: PropTypes.string,
        image: PropTypes.string,
        version: PropTypes.number,
        nightModeAvailable: PropTypes.bool
    }).isRequired,
    onClose: PropTypes.func.isRequired,
    // For tests: the library services
    fetchDetails: PropTypes.func,
    fetchEntry: PropTypes.func,
    // For tests: the wait before leaving a node with autoplay and no audio
    autoplayDelay: PropTypes.number
};

export default withTranslation()(PackPreview);
