/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * Opening a pack of the local library in the editor. It used to download the whole archive (up to a
 * gigabyte) and unzip it in the browser, turning every audio file into a base64 data URL: more than a
 * minute and several gigabytes of JavaScript heap for a large pack. readFromLibrary reads story.json,
 * then each asset once, as an entry served by the library, and keeps the assets as object URLs.
 */

import JSZip from 'jszip';

import {readFromArchive, readFromLibrary, releaseLibraryAssets, adoptLibraryAssets, discardLibraryAssets} from "./reader";

const STAGE_UUID = '00000000-0000-0000-0000-000000000001';
const SECOND_UUID = '00000000-0000-0000-0000-000000000002';
const CONTROLS = {wheel: false, ok: true, home: false, pause: false, autoplay: true};

function story() {
    return {
        title: 'Été',
        version: 2,
        description: 'Une description',
        nightModeAvailable: true,
        actionNodes: [],
        stageNodes: [
            {uuid: STAGE_UUID, type: 'stage', squareOne: true, name: 'Départ', image: 'cover.png', audio: 'shared.mp3',
                okTransition: null, homeTransition: null, controlSettings: CONTROLS, position: {x: 10, y: 20}},
            {uuid: SECOND_UUID, type: 'stage', name: 'Suite', image: null, audio: 'shared.mp3',
                okTransition: null, homeTransition: null, controlSettings: CONTROLS, position: {x: 30, y: 40}}
        ]
    };
}

function libraryOf(entries) {
    const requested = [];
    const loadEntry = (name) => {
        requested.push(name);
        return Promise.resolve(name in entries ? new Blob([entries[name]]) : null);
    };
    return {loadEntry, requested};
}

describe('readFromLibrary', () => {
    let created;
    let revoked;

    beforeEach(() => {
        created = [];
        revoked = [];
        let counter = 0;
        URL.createObjectURL = jest.fn(blob => {
            const url = 'blob:test/' + (++counter);
            created.push({url, type: blob.type});
            return url;
        });
        URL.revokeObjectURL = jest.fn(url => revoked.push(url));
        jest.spyOn(console, 'log').mockImplementation(() => {});
    });

    afterEach(() => {
        console.log.mockRestore();
    });

    it('builds the model from story.json, with assets as object URLs, each loaded once', async () => {
        const {loadEntry, requested} = libraryOf({
            'story.json': JSON.stringify(story()),
            'thumbnail.png': 'png bytes',
            'assets/cover.png': 'image bytes',
            'assets/shared.mp3': 'audio bytes'
        });

        const model = await readFromLibrary(loadEntry);

        expect(model.title).toBe('Été');
        expect(model.version).toBe(2);
        expect(model.nightModeAvailable).toBe(true);
        expect(model.thumbnail).toMatch(/^data:/);
        const stage = model.getNodes().find(node => node.getUuid && node.getUuid() === STAGE_UUID);
        const second = model.getNodes().find(node => node.getUuid && node.getUuid() === SECOND_UUID);
        expect(stage.getImage()).toMatch(/^blob:/);
        expect(stage.getAudio()).toMatch(/^blob:/);
        expect(second.getAudio()).toBe(stage.getAudio());
        expect(requested.filter(name => name === 'assets/shared.mp3')).toHaveLength(1);
        expect(created.map(c => c.type).sort()).toEqual(['audio/mpeg', 'image/png']);
    });

    it('does without a thumbnail', async () => {
        const {loadEntry} = libraryOf({
            'story.json': JSON.stringify(story()),
            'assets/cover.png': 'image bytes',
            'assets/shared.mp3': 'audio bytes'
        });

        const model = await readFromLibrary(loadEntry);

        expect(model.thumbnail).toBeNull();
    });

    it('fails on a missing asset, and releases the object URLs it made', async () => {
        const {loadEntry} = libraryOf({
            'story.json': JSON.stringify(story()),
            'assets/shared.mp3': 'audio bytes'
        });

        await expect(readFromLibrary(loadEntry)).rejects.toThrow('Missing asset: cover.png');
        expect(revoked.sort()).toEqual(created.map(c => c.url).sort());
    });

    it('releases the assets of the pack shown, and tells which model they belonged to', async () => {
        const model = await readFromLibrary(libraryOf({
            'story.json': JSON.stringify(story()),
            'assets/cover.png': 'image bytes',
            'assets/shared.mp3': 'audio bytes'
        }).loadEntry);
        adoptLibraryAssets(model);

        expect(revoked).toEqual([]);
        expect(releaseLibraryAssets()).toBe(model);
        expect(revoked.sort()).toEqual(created.map(c => c.url).sort());
        expect(releaseLibraryAssets()).toBeNull();
    });

    const pack = () => libraryOf({
        'story.json': JSON.stringify(story()),
        'assets/cover.png': 'image bytes',
        'assets/shared.mp3': 'audio bytes'
    }).loadEntry;

    it('keeps the pack shown until the next one is read, then releases it', async () => {
        const first = await readFromLibrary(pack());
        adoptLibraryAssets(first);
        const firstUrls = created.map(c => c.url);

        const second = await readFromLibrary(pack());
        expect(revoked).toEqual([]);
        expect(adoptLibraryAssets(second)).toBe(first);
        expect(revoked.sort()).toEqual(firstUrls.sort());
        releaseLibraryAssets();
    });

    it('a failed read leaves the pack shown untouched', async () => {
        const shown = await readFromLibrary(pack());
        adoptLibraryAssets(shown);

        await expect(readFromLibrary(libraryOf({}).loadEntry)).rejects.toThrow('Missing story.json');

        expect(revoked).toEqual([]);
        expect(releaseLibraryAssets()).toBe(shown);
    });

    it('a read that is not shown lets go of its assets', async () => {
        const unused = await readFromLibrary(pack());

        discardLibraryAssets(unused);

        expect(revoked.sort()).toEqual(created.map(c => c.url).sort());
        expect(releaseLibraryAssets()).toBeNull();
    });

    it('releases the assets that arrive after the read has failed', async () => {
        let deliverLate;
        const late = new Promise(resolve => { deliverLate = resolve; });
        const loadEntry = (name) => {
            if (name === 'story.json') return Promise.resolve(new Blob([JSON.stringify(story())]));
            if (name === 'assets/cover.png') return late;
            return Promise.resolve(null);   // shared.mp3 missing: the read fails
        };

        await expect(readFromLibrary(loadEntry)).rejects.toThrow('Missing asset');
        deliverLate(new Blob(['image bytes']));
        await new Promise(resolve => setTimeout(resolve, 0));

        expect(created).toHaveLength(1);
        expect(revoked).toEqual([created[0].url]);
    });
});

describe('readFromArchive', () => {
    beforeEach(() => {
        jest.spyOn(console, 'log').mockImplementation(() => {});
    });

    afterEach(() => {
        console.log.mockRestore();
    });

    it('rejects a file that is not a readable ZIP, instead of resolving with nothing', async () => {
        const notAZip = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]);
        await expect(readFromArchive(notAZip)).rejects.toBeDefined();
    });

    it('rejects a ZIP without story.json', async () => {
        const zip = new JSZip();
        zip.file('readme.txt', 'nothing here');
        const bytes = await zip.generateAsync({type: 'uint8array'});
        await expect(readFromArchive(bytes)).rejects.toThrow(/story\.json/);
    });

    it('reads a pack archive into a model', async () => {
        const zip = new JSZip();
        zip.file('story.json', JSON.stringify(story()));
        zip.file('assets/cover.png', 'image bytes');
        zip.file('assets/shared.mp3', 'audio bytes');
        const bytes = await zip.generateAsync({type: 'uint8array'});
        const model = await readFromArchive(bytes);
        expect(model).toBeDefined();
        expect(model.title).toBe('Été');
    });
});
