/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import React from 'react';
import DeviceLoading from './DeviceLoading';
import { connect } from 'react-redux';
import {withTranslation} from "react-i18next";

import {PackLibrary, mapStateToProps, mapDispatchToProps} from "./PackLibrary";
import Modal from "./Modal";
import YoutubePack from "./YoutubePack";
import DeviceUpdateButton from "./DeviceUpdateButton";
import {simpleItems, movedOrder, storagePercent, packTitle, SIMPLE_SORTS} from "../utils/simpleLibrary";
import {sizeUnit} from "../utils/purge";

import './PackLibrarySimple.css';

/*
 * The simplified library, after Lunii Pack's: one card per pack, with its cover, title, description and
 * size, and one main button, "Send to my Lunii" (or "Remove from my Lunii" when it is already there).
 * Formats, conversions and file names stay out of sight: the decision of what to send, and whether to
 * convert first, is the full library's own (sendToDevice), as are its dialogs and its import.
 *
 * It extends the full library's component rather than duplicating its logic: only render() differs.
 */
export class PackLibrarySimple extends PackLibrary {

    constructor(props) {
        super(props);
        this.state = {
            ...this.state,
            query: '',
            sort: 'title',
            notOnDevice: false,
            removingGroup: null,
            // uuid -> true while its "Send to my Lunii" is being started (conversion, verification)
            sending: {}
        };
    }

    formatSize = (bytes) => {
        const { t } = this.props;
        const unit = sizeUnit(bytes);
        return t(`libraryPurge.units.${unit.key}`, { value: unit.value });
    };

    onSendToDevice = (item) => () => {
        // Official packs are not sent, as in the full library (isPackDraggable); one sending at a time
        if (!this.state.device.metadata || !this.isPackDraggable(item.group.packs[0] || {}) || this.state.sending[item.uuid]) {
            return;
        }
        this.setState(state => ({sending: {...state.sending, [item.uuid]: true}}));
        const done = () => {
            if (!this.unmounted) {
                this.setState(state => {
                    const sending = {...state.sending};
                    delete sending[item.uuid];
                    return {sending};
                });
            }
        };
        let started;
        try {
            started = Promise.resolve(this.sendToDevice(item.group));
        } catch (e) {
            started = Promise.reject(e);
        }
        return started.catch(e => console.error('failed to send the pack to the device', e)).then(done);
    };

    /** "Update on my Lunii": the full library's in-place update, one at a time per pack. */
    onUpdateOnDevice = (uuid) => () => {
        if (!this.state.device.metadata || this.state.sending[uuid]) {
            return Promise.resolve();
        }
        this.setState(state => ({sending: {...state.sending, [uuid]: true}}));
        const done = () => {
            if (!this.unmounted) {
                this.setState(state => {
                    const sending = {...state.sending};
                    delete sending[uuid];
                    return {sending};
                });
            }
        };
        let started;
        try {
            started = Promise.resolve(this.updatePackOnDevice(uuid));
        } catch (e) {
            started = Promise.reject(e);
        }
        return started.catch(e => console.error('failed to update the pack on the device', e)).then(done);
    };

    componentWillUnmount() {
        this.unmounted = true;
        if (super.componentWillUnmount) {
            super.componentWillUnmount();
        }
    }

    /** The full library's preview (PackPreview), of the editable archive when there is one. */
    onPreview = (item) => () => {
        this.setState({previewing: {...(item.editable || item.group.packs[0]), uuid: item.uuid}});
    };

    onMoveOnDevice = (index, delta) => () => {
        const uuids = movedOrder(this.state.device.packs, index, delta);
        if (uuids) {
            this.props.reorderOnDevice(uuids);
        }
    };

    onSaveToLibrary = (uuid) => () => {
        this.props.addToLibrary(uuid, this.state.device.metadata.driver, this.context);
    };

    /** Removing a pack, in this view, removes every file of it: the source and its conversions. */
    onRemoveGroup = (item) => () => {
        this.setState({removingGroup: item});
    };

    dismissRemoveGroup = () => {
        this.setState({removingGroup: null});
    };

    doRemoveGroup = () => {
        const item = this.state.removingGroup;
        this.setState({removingGroup: null});
        if (!item) {
            return Promise.resolve();
        }
        // One toast and one refresh of the library for all the files
        return this.props.removeManyFromLibrary(item.group.packs.map(pack => pack.path));
    };

    renderDevice() {
        const { t } = this.props;
        const device = this.state.device;
        // Uuids compared regardless of case: the device and story.json may not write them alike
        const libraryUuids = (this.state.library.packs || []).map(g => String(g.uuid).toLowerCase());
        if (!device.metadata) {
            return <section className="simple-device simple-device-unplugged" aria-label={t('simpleLibrary.device.title')}>
                <h2>{t('simpleLibrary.device.title')}</h2>
                <p className="simple-note">{t('simpleLibrary.device.unplugged')}</p>
            </section>;
        }
        const percent = storagePercent(device.metadata);
        const storage = device.metadata.storage || {};
        return <section className="simple-device" aria-label={t('simpleLibrary.device.title')}>
            <h2>{t('simpleLibrary.device.title')} <span className="simple-count">({device.packs.length})</span></h2>
            {percent !== null && <>
                <p className="simple-note">{t('simpleLibrary.device.storage', { free: this.formatSize(storage.free != null ? storage.free : storage.size - storage.taken), total: this.formatSize(storage.size) })}</p>
                <div className={`simple-storage ${percent > 90 ? 'critical' : percent > 75 ? 'warning' : ''}`}
                     role="progressbar" aria-valuenow={percent} aria-valuemin="0" aria-valuemax="100"
                     aria-label={t('simpleLibrary.device.used', { percent })}>
                    <div style={{width: `${percent}%`}}/>
                </div>
            </>}
            {device.metadata.error && <p className="simple-note simple-error">{t('simpleLibrary.device.error')}</p>}
            {this.renderDeviceRepair('simple-device-repair')}
            {device.packsLoading && <DeviceLoading />}
            {!device.packsLoading && device.packs.length === 0 && <p className="simple-empty">{t('library.device.empty')}</p>}
            {!device.packsLoading && device.packs.length > 0 && <ol className="simple-device-list">
                {device.packs.map((pack, idx) => {
                    const title = packTitle(pack, pack.uuid);
                    return <li key={pack.uuid} className="simple-device-pack">
                        {pack.image ? <img src={pack.image} alt="" width="48" height="48"/> : <span className="simple-thumb-none" aria-hidden="true">♪</span>}
                        <span className="simple-device-title" title={title}>{title}</span>
                        {!pack.official && <DeviceUpdateButton status={this.deviceUpdateOf(pack.uuid)} badgeOnly />}
                        <span className="simple-device-actions">
                            <button type="button" className="ui-btn ui-btn-icon" disabled={idx === 0}
                                    onClick={this.onMoveOnDevice(idx, -1)}
                                    aria-label={t('simpleLibrary.device.moveUp', { title })} title={t('simpleLibrary.device.moveUp', { title })}>↑</button>
                            <button type="button" className="ui-btn ui-btn-icon" disabled={idx === device.packs.length - 1}
                                    onClick={this.onMoveOnDevice(idx, 1)}
                                    aria-label={t('simpleLibrary.device.moveDown', { title })} title={t('simpleLibrary.device.moveDown', { title })}>↓</button>
                            {!pack.official && libraryUuids.indexOf(String(pack.uuid).toLowerCase()) === -1 &&
                            <button type="button" className="ui-btn ui-btn-icon" onClick={this.onSaveToLibrary(pack.uuid)}
                                    aria-label={t('simpleLibrary.device.save', { title })} title={t('simpleLibrary.device.save', { title })}>
                                <span className="glyphicon glyphicon-save" aria-hidden="true"/>
                            </button>}
                            <button type="button" className="ui-btn ui-btn-icon ui-btn-danger" onClick={this.onRemovePackFromDevice(pack.uuid)}
                                    aria-label={t('simpleLibrary.device.remove', { title })} title={t('simpleLibrary.device.remove', { title })}>
                                <span className="glyphicon glyphicon-trash" aria-hidden="true"/>
                            </button>
                        </span>
                    </li>;
                })}
            </ol>}
        </section>;
    }

    renderCard(item) {
        const { t } = this.props;
        const plugged = !!this.state.device.metadata;
        const sending = this.state.sending[item.uuid] === true;
        const sendable = this.isPackDraggable(item.group.packs[0] || {});
        const meta = [item.size !== null ? this.formatSize(item.size) : null,
            item.files > 1 ? t('simpleLibrary.card.files', { count: item.files }) : null].filter(Boolean).join(' · ');
        return <li key={item.uuid} className={`simple-card ${item.onDevice ? 'on-device' : ''} ${this.sendQueue.isSelected(item.uuid) ? 'send-selected' : ''}`} title={item.uuid}>
            <div className="simple-cover">
                <span className="simple-select">{this.renderSelectBox(item.uuid, item.title)}</span>
                {item.image ? <img src={item.image} alt="" loading="lazy" draggable={false}/> : <span aria-hidden="true">♪</span>}
            </div>
            <div className="simple-body">
                <h3 className="simple-title">{item.title}</h3>
                {item.description && <p className="simple-desc">{item.description}</p>}
                <p className="simple-meta">{meta}</p>
                <p className="simple-badges">
                    {item.onDevice && <span className="simple-badge ok">{t('simpleLibrary.card.onDevice')}</span>}
                    {item.onDevice && !item.official && <DeviceUpdateButton status={this.deviceUpdateOf(item.uuid)} badgeOnly />}
                    {item.official && <span className="simple-badge">{t('library.official')}</span>}
                    {item.nightMode && <span className="simple-badge">{t('library.nightMode')}</span>}
                </p>
                <div className="simple-actions">
                    {item.onDevice && !item.official && plugged &&
                        <DeviceUpdateButton status={this.deviceUpdateOf(item.uuid)} onUpdate={this.onUpdateOnDevice(item.uuid)} busy={sending} buttonOnly />}
                    {item.onDevice
                        ? <button type="button" className="ui-btn" onClick={this.onRemovePackFromDevice(item.uuid)} disabled={!plugged}>
                            {t('simpleLibrary.card.removeFromDevice')}
                          </button>
                        : sendable && <button type="button" className="ui-btn ui-btn-primary" onClick={this.onSendToDevice(item)} disabled={!plugged || sending}
                                  aria-busy={sending} title={plugged ? undefined : t('simpleLibrary.card.plugFirst')}>
                            {t(sending ? 'simpleLibrary.card.sending' : 'simpleLibrary.card.send')}
                          </button>}
                    <button type="button" className="ui-btn ui-btn-icon" onClick={this.onPreview(item)}
                            aria-label={t('simpleLibrary.card.preview', { title: item.title })} title={t('simpleLibrary.card.preview', { title: item.title })}>
                        <span className="glyphicon glyphicon-eye-open" aria-hidden="true"/>
                    </button>
                    {item.editable && <button type="button" className="ui-btn ui-btn-icon" onClick={this.onEditLibraryPack(item.editable)}
                                              aria-label={t('simpleLibrary.card.edit', { title: item.title })} title={t('simpleLibrary.card.edit', { title: item.title })}>
                        <span className="glyphicon glyphicon-edit" aria-hidden="true"/>
                    </button>}
                    <button type="button" className="ui-btn ui-btn-icon ui-btn-danger" onClick={this.onRemoveGroup(item)}
                            aria-label={t('simpleLibrary.card.remove', { title: item.title })} title={t('simpleLibrary.card.remove', { title: item.title })}>
                        <span className="glyphicon glyphicon-trash" aria-hidden="true"/>
                    </button>
                </div>
            </div>
        </li>;
    }

    render() {
        const { t } = this.props;
        const groups = (this.state.library && this.state.library.packs) || [];
        const libraryLoading = !!(this.state.library && this.state.library.loading);
        const items = simpleItems(groups, this.state.device.packs, {
            query: this.state.query, sort: this.state.sort, notOnDevice: this.state.notOnDevice,
            locale: this.props.i18n && this.props.i18n.language
        });
        const total = groups.reduce((sum, g) => sum + ((g.packs || []).map(p => p.size).filter(s => s >= 0)[0] || 0), 0);
        const removing = this.state.removingGroup;
        return (
            <div className="pack-library pack-library-simple">

                {this.renderDialogs()}
                {removing &&
                <Modal id="confirm-simple-pack-remove"
                       title={t('dialogs.library.removeFromLibrary.title')}
                       content={<p>{t(removing.files > 1 ? 'simpleLibrary.removeDialog.contentMany' : 'simpleLibrary.removeDialog.content',
                           { title: removing.title, count: removing.files })}</p>}
                       buttons={[
                           { label: t('dialogs.shared.no'), onClick: this.dismissRemoveGroup},
                           { label: t('dialogs.shared.yes'), onClick: this.doRemoveGroup}
                       ]}
                       onClose={this.dismissRemoveGroup}
                />}

                {this.renderDevice()}

                <section className="simple-library" aria-label={t('simpleLibrary.library.title')}
                         onDrop={this.onDropPackIntoLibrary}
                         onDragOver={event => { event.preventDefault(); }}>
                    <div className="header simple-header">
                        <h2>{t('simpleLibrary.library.title')} <span className="simple-count">
                            ({items.length !== groups.length ? `${items.length} / ${groups.length}` : groups.length})
                        </span>{groups.length > 0 && <span className="simple-total">{this.formatSize(total)}</span>}</h2>
                        <input type="file" id="upload" multiple accept=".zip,.pack" style={{visibility: 'hidden', position: 'absolute'}} onChange={this.packAddFileSelected} />
                        <div className="library-search">
                            <input type="search" value={this.state.query} placeholder={t('simpleLibrary.library.search')}
                                   aria-label={t('simpleLibrary.library.search')} onChange={e => this.setState({query: e.target.value})} />
                        </div>
                        <select className="simple-sort" value={this.state.sort} aria-label={t('simpleLibrary.library.sort')}
                                onChange={e => this.setState({sort: e.target.value})}>
                            {SIMPLE_SORTS.map(sort => <option key={sort} value={sort}>{t(`simpleLibrary.library.sorts.${sort}`)}</option>)}
                        </select>
                        <label className="simple-filter">
                            <input type="checkbox" checked={this.state.notOnDevice} onChange={e => this.setState({notOnDevice: e.target.checked})}/>
                            {' '}{t('simpleLibrary.library.notOnDevice')}
                        </label>
                        <span className="simple-header-actions">
                            <button type="button" className="btn btn-primary ui-btn ui-btn-primary library-import" onClick={this.showAddFileSelector}>
                                <span className="glyphicon glyphicon-import" aria-hidden="true"/> {t('library.local.import')}
                            </button>
                            <YoutubePack />
                        </span>
                    </div>
                    {this.renderSendQueue(items.map(item => item.uuid))}
                    <div className="simple-scroll">
                        {libraryLoading && <DeviceLoading message="library.local.loading" />}
                        {!libraryLoading && groups.length === 0 && <p className="simple-empty">{t('simpleLibrary.library.empty')}</p>}
                        {groups.length > 0 && items.length === 0 && <p className="simple-empty">{t('library.local.noMatch')}</p>}
                        {items.length > 0 && <ul className="simple-grid">{items.map(item => this.renderCard(item))}</ul>}
                    </div>
                </section>
            </div>
        );
    }
}

export default withTranslation()(
    connect(
        mapStateToProps,
        mapDispatchToProps
    )(PackLibrarySimple)
)
