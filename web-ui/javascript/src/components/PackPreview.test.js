/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The preview of a library pack, rendered in jsdom with the library services replaced: what it reads
 * (story.json and the assets of the node shown, nothing more), what it shows, and what it releases.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act} from 'react-dom/test-utils';

import {PackPreview} from './PackPreview';

jest.mock('react-i18next', () => {
    const mockReact = require('react');
    const mockT = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;
    return {
        withTranslation: () => (Component) => (props) => mockReact.createElement(Component, {t: mockT, ...props})
    };
});

const t = (key, values) => values ? `${key} ${JSON.stringify(values)}` : key;
const CONTROLS = {wheel: true, ok: true, home: true, pause: false, autoplay: false};

function story() {
    return {
        title: 'Les tests',
        stageNodes: [
            {uuid: 'cover', squareOne: true, name: 'Couverture', image: 'cover.png', audio: 'cover.mp3', controlSettings: CONTROLS,
                okTransition: {actionNode: 'choices', optionIndex: 0}, homeTransition: null},
            {uuid: 'one', name: 'Un', image: 'one.png', audio: 'one.mp3', controlSettings: CONTROLS,
                okTransition: null, homeTransition: null},
            {uuid: 'two', name: 'Deux', image: 'two.png', audio: 'two.mp3', controlSettings: {...CONTROLS, pause: true},
                okTransition: null, homeTransition: null}
        ],
        actionNodes: [{id: 'choices', options: ['one', 'two']}]
    };
}

const ARCHIVE = {path: 'tests.zip', format: 'archive', uuid: 'uuid-1', title: 'Les tests', description: 'Une description',
    image: 'data:image/png;base64,AAAA', version: 3, nightModeAvailable: false};

const flush = () => act(() => new Promise(resolve => setTimeout(resolve, 0)));

let container;
let created;
let revoked;

beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
    created = [];
    revoked = [];
    let n = 0;
    global.URL.createObjectURL = jest.fn(() => {
        const url = `blob:test/${++n}`;
        created.push(url);
        return url;
    });
    global.URL.revokeObjectURL = jest.fn(url => revoked.push(url));
    // jsdom has no media playback
    window.HTMLMediaElement.prototype.pause = () => {};
    window.HTMLMediaElement.prototype.play = () => Promise.resolve();
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
});

function services(json = story(), details = {path: 'tests.zip', format: 'archive', size: 2500000,
    audio: {seconds: 754, measured: 3, estimated: 1, unmeasured: 0}}) {
    const requested = [];
    return {
        requested,
        fetchDetails: jest.fn(() => Promise.resolve(details)),
        fetchEntry: jest.fn((path, entry) => {
            requested.push(entry);
            if (entry === 'story.json') {
                return Promise.resolve(new Blob([JSON.stringify(json)]));
            }
            return Promise.resolve(new Blob([entry]));
        })
    };
}

async function render(pack, svc, onClose = () => {}) {
    await act(async () => {
        ReactDOM.render(<PackPreview t={t} pack={pack} onClose={onClose}
                                     fetchDetails={svc.fetchDetails} fetchEntry={svc.fetchEntry}/>, container);
    });
    await flush();
    await flush();
}

function click(element) {
    act(() => {
        element.dispatchEvent(new MouseEvent('click', {bubbles: true}));
    });
}

function button(label) {
    return Array.from(container.querySelectorAll('.pack-preview-controls button'))
        .find(b => b.textContent === label || b.getAttribute('aria-label') === label);
}

function screenImage() {
    const image = container.querySelector('image#asset');
    return image && image.getAttribute('xlink:href');
}

describe('PackPreview', () => {

    it('shows the metadata of the listing and the details read by the backend', async () => {
        const svc = services();
        await render(ARCHIVE, svc);

        const text = container.textContent;
        expect(text).toContain('Les tests');
        expect(text).toContain('Une description');
        expect(text).toContain('v3');
        expect(text).toContain('library.format.archive');
        expect(text).toContain('libraryPurge.units.mb {"value":"3"}');
        // 3 stage nodes, 1 action node, 1 story (pause enabled), from story.json
        expect(text).toContain('preview.info.nodesValue {"stages":3,"actions":1}');
        expect(container.querySelector('.pack-preview-facts').textContent).toMatch(/preview\.info\.stories1/);
        // One asset estimated from its bitrate: approximate
        expect(text).toContain('preview.info.approximately');
        expect(text).toContain('preview.duration.minutes {\\"minutes\\":12,\\"seconds\\":\\"34\\"}');
        expect(svc.fetchDetails).toHaveBeenCalledWith('tests.zip');
    });

    it('reads story.json and the first image only, until a button is pressed', async () => {
        const svc = services();
        await render(ARCHIVE, svc);

        expect(svc.requested).toEqual(['story.json', 'assets/cover.png']);
        expect(screenImage()).toBe(created[0]);
        expect(container.querySelector('audio')).toBeNull();
    });

    it('starts on the first button, then follows the transitions and loads each node on demand', async () => {
        const svc = services();
        await render(ARCHIVE, svc);

        // Start: the first node's audio
        click(button('preview.simulation.ok'));
        await flush();
        expect(svc.requested).toContain('assets/cover.mp3');
        expect(container.querySelector('audio')).not.toBeNull();

        // OK: option 0 of the action node, and its neighbour's image ahead of time
        click(button('preview.simulation.ok'));
        await flush();
        expect(container.querySelector('.pack-preview-node').textContent).toBe('Un');
        expect(svc.requested).toEqual(expect.arrayContaining(['assets/one.png', 'assets/one.mp3', 'assets/two.png']));
        expect(svc.requested).not.toContain('assets/two.mp3');

        // Wheel right: the next option
        click(button('preview.simulation.right'));
        await flush();
        expect(container.querySelector('.pack-preview-node').textContent).toBe('Deux');

        // Assets shared by several visits are read once
        expect(svc.requested.filter(e => e === 'assets/two.png')).toHaveLength(1);
    });

    it('goes back to the first node at the end of a story, and says so', async () => {
        const svc = services();
        await render(ARCHIVE, svc);
        click(button('preview.simulation.ok'));
        await flush();
        click(button('preview.simulation.ok'));
        await flush();
        click(button('preview.simulation.ok'));
        await flush();

        expect(container.querySelector('.pack-preview-node').textContent).toBe('Couverture');
        expect(container.textContent).toContain('preview.simulation.end');
    });

    it('drives the simulation from the keyboard, and closes on Escape', async () => {
        const svc = services();
        const onClose = jest.fn();
        await render(ARCHIVE, svc, onClose);

        const key = (k) => act(() => {
            document.body.dispatchEvent(new KeyboardEvent('keydown', {key: k, bubbles: true}));
        });
        key('Enter');
        await flush();
        key('Enter');
        await flush();
        expect(container.querySelector('.pack-preview-node').textContent).toBe('Un');
        key('ArrowLeft');
        await flush();
        expect(container.querySelector('.pack-preview-node').textContent).toBe('Deux');

        key('Escape');
        expect(onClose).toHaveBeenCalled();
    });

    it('releases every object URL when closed, including those still loading', async () => {
        const svc = services();
        let resolveLate;
        const fetchEntry = svc.fetchEntry;
        svc.fetchEntry = jest.fn((path, entry) => entry === 'assets/cover.mp3'
            ? new Promise(resolve => { resolveLate = () => resolve(new Blob(['late'])); })
            : fetchEntry(path, entry));
        await render(ARCHIVE, svc);
        click(button('preview.simulation.ok'));
        await flush();

        act(() => {
            ReactDOM.unmountComponentAtNode(container);
        });
        expect(revoked).toEqual(created);

        await act(async () => {
            resolveLate();
        });
        await flush();
        expect(created.length).toBeGreaterThan(1);
        expect(revoked).toEqual(created);
    });

    it('shows the details of a raw pack, and why it cannot be simulated, without reading entries', async () => {
        const svc = services(story(), {path: 'tests.pack', format: 'raw', size: 1500, stageNodes: 12, stories: 4});
        await render({...ARCHIVE, path: 'tests.pack', format: 'raw'}, svc);

        const text = container.textContent;
        expect(text).toContain('preview.simulation.unavailableTitle');
        expect(text).toContain('preview.info.stagesValue {"stages":12}');
        expect(text).toContain('preview.info.durationUnavailable');
        expect(text).toContain('libraryPurge.units.kb {"value":"2"}');
        expect(svc.fetchEntry).not.toHaveBeenCalled();
    });

    it('explains a pack whose story.json cannot be read', async () => {
        const svc = services();
        svc.fetchEntry = jest.fn(() => Promise.resolve(null));
        await render(ARCHIVE, svc);

        expect(container.textContent).toContain('preview.simulation.errorTitle');
        expect(container.textContent).toContain('preview.info.unknown');
    });
});
