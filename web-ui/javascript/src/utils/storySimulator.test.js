/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {createSimulation, storyStats, splitDuration} from "./storySimulator";

const MENU = {wheel: true, ok: true, home: true, pause: false, autoplay: false};
const STORY = {wheel: false, ok: false, home: true, pause: true, autoplay: true};

/*
 * cover --ok--> menu (action "choices": story A, story B, story C)
 * story A --ok--> back to the choices, option 1; home --> null
 * story B --ok--> null (end); home disabled
 * story C --ok--> random option of "choices"
 */
function story() {
    return {
        title: 'Test',
        stageNodes: [
            {uuid: 'menuA', name: 'Choix A', image: 'a.png', audio: 'a-title.mp3', controlSettings: MENU,
                okTransition: {actionNode: 'storyA', optionIndex: 0}, homeTransition: {actionNode: 'cover', optionIndex: 0}},
            {uuid: 'cover', squareOne: true, name: 'Couverture', image: 'cover.png', audio: 'cover.mp3',
                controlSettings: {wheel: true, ok: true, home: false, pause: false, autoplay: false},
                okTransition: {actionNode: 'choices', optionIndex: 0}, homeTransition: null},
            {uuid: 'menuB', name: 'Choix B', image: 'b.png', audio: 'b-title.mp3', controlSettings: MENU,
                okTransition: {actionNode: 'storyB', optionIndex: 0}, homeTransition: null},
            {uuid: 'menuC', name: 'Choix C', image: null, audio: null, controlSettings: MENU,
                okTransition: {actionNode: 'missing', optionIndex: 0}, homeTransition: null},
            {uuid: 'A', name: 'Histoire A', image: null, audio: 'a.mp3', controlSettings: STORY,
                okTransition: {actionNode: 'choices', optionIndex: 1}, homeTransition: null},
            {uuid: 'B', name: 'Histoire B', image: null, audio: 'b.mp3', controlSettings: {...STORY, home: false},
                okTransition: null, homeTransition: null}
        ],
        actionNodes: [
            {id: 'choices', options: ['menuA', 'menuB', 'menuC']},
            {id: 'storyA', options: ['A']},
            {id: 'storyB', options: ['B']},
            {id: 'cover', options: ['cover']}
        ]
    };
}

describe('storyStats', () => {
    it('counts stage nodes, action nodes, and stories as the stage nodes where pause is enabled', () => {
        expect(storyStats(story())).toEqual({stageNodes: 6, actionNodes: 4, stories: 2});
    });

    it('copes with a story.json without nodes', () => {
        expect(storyStats({})).toEqual({stageNodes: 0, actionNodes: 0, stories: 0});
    });
});

describe('createSimulation', () => {
    it('starts on the square one node, whatever its place in the list', () => {
        const sim = createSimulation(story());
        const state = sim.start();
        expect(state).toEqual({stage: 'cover', action: null, index: null});
        expect(sim.assets(state)).toEqual({image: 'cover.png', audio: 'cover.mp3'});
    });

    it('starts on the first node when none is square one, and refuses a pack without nodes', () => {
        const json = story();
        json.stageNodes.forEach(node => delete node.squareOne);
        expect(createSimulation(json).start().stage).toBe('menuA');
        expect(() => createSimulation({stageNodes: [], actionNodes: []})).toThrow();
    });

    it('follows OK transitions through action nodes', () => {
        const sim = createSimulation(story());
        let {state, event} = sim.ok(sim.start());
        expect(event).toBe('moved');
        expect(state).toEqual({stage: 'menuA', action: 'choices', index: 0});
        ({state, event} = sim.ok(state));
        expect(state.stage).toBe('A');
        // End of story A: back to the choices, on option 1
        ({state, event} = sim.ok(state));
        expect(state).toEqual({stage: 'menuB', action: 'choices', index: 1});
    });

    it('turns the wheel among the options of the action node, wrapping around', () => {
        const sim = createSimulation(story());
        const menu = sim.ok(sim.start()).state;
        expect(sim.wheel(menu, 1).state.stage).toBe('menuB');
        expect(sim.wheel(menu, -1).state.stage).toBe('menuC');
        expect(sim.wheel(sim.wheel(menu, -1).state, 1).state.stage).toBe('menuA');
    });

    it('leaves the wheel without effect where it is disabled or there is nothing to turn to', () => {
        const sim = createSimulation(story());
        // The first node has the wheel enabled, but was not reached through an action node
        expect(sim.wheel(sim.start(), 1).event).toBe('none');
        const storyA = sim.ok(sim.ok(sim.start()).state).state;
        expect(sim.wheel(storyA, 1)).toEqual({state: storyA, event: 'none'});
    });

    it('goes back to the first node at the end of a story without OK transition', () => {
        const sim = createSimulation(story());
        const menuB = sim.wheel(sim.ok(sim.start()).state, 1).state;
        const storyB = sim.ok(menuB).state;
        expect(storyB.stage).toBe('B');
        expect(sim.ok(storyB)).toEqual({state: {stage: 'cover', action: null, index: null}, event: 'end'});
    });

    it('follows home transitions, leaves the pack without one, and ignores a disabled home', () => {
        const sim = createSimulation(story());
        const menuA = sim.ok(sim.start()).state;
        expect(sim.home(menuA).state).toEqual({stage: 'cover', action: 'cover', index: 0});
        const storyA = sim.ok(menuA).state;
        expect(sim.home(storyA)).toEqual({state: sim.start(), event: 'exit'});
        expect(sim.home(sim.start()).event).toBe('none');
        const storyB = sim.ok(sim.wheel(menuA, 1).state).state;
        expect(sim.home(storyB).event).toBe('none');
    });

    it('stays put on a transition to a node the pack does not have', () => {
        const sim = createSimulation(story());
        const menuC = sim.wheel(sim.ok(sim.start()).state, -1).state;
        expect(sim.ok(menuC)).toEqual({state: menuC, event: 'broken'});
    });

    it('picks a random option for option index -1', () => {
        const json = story();
        json.stageNodes[0].okTransition = {actionNode: 'choices', optionIndex: -1};
        const sim = createSimulation(json, () => 0.99);
        const menuA = {stage: 'menuA', action: 'choices', index: 0};
        expect(sim.ok(menuA).state).toEqual({stage: 'menuC', action: 'choices', index: 2});
        expect(createSimulation(json, () => 0).ok(menuA).state.stage).toBe('menuA');
    });

    it('names the images of the neighbouring options, once each', () => {
        const sim = createSimulation(story());
        const menu = sim.ok(sim.start()).state;
        // menuB on the right, menuC (no image) on the left
        expect(sim.neighbourImages(menu)).toEqual(['b.png']);
        expect(sim.neighbourImages(sim.start())).toEqual([]);
    });

    it('fills in missing control settings as disabled', () => {
        const sim = createSimulation({stageNodes: [{uuid: 'x', squareOne: true}], actionNodes: []});
        expect(sim.controls(sim.start())).toEqual({wheel: false, ok: false, home: false, pause: false, autoplay: false});
        expect(sim.ok(sim.start()).event).toBe('end');
    });

    it('refuses a pack without nodes with a code the preview translates', () => {
        let error = null;
        try {
            createSimulation({stageNodes: [], actionNodes: []});
        } catch (e) {
            error = e;
        }
        expect(error.code).toBe('NO_STAGE_NODE');
    });

    it('reads an option index given as a string as a number', () => {
        const json = story();
        json.stageNodes[1].okTransition.optionIndex = '1';
        const sim = createSimulation(json);
        const moved = sim.ok(sim.start());
        expect(moved.state).toEqual({stage: 'menuB', action: 'choices', index: 1});
        // The wheel then counts from 1, not from "1" + 1 = "11"
        expect(sim.wheel(moved.state, 1).state.stage).toBe('menuC');
        json.stageNodes[1].okTransition.optionIndex = 'x';
        expect(createSimulation(json).ok(sim.start()).event).toBe('broken');
    });

    it('keeps the first of the stage nodes sharing a uuid', () => {
        const json = story();
        json.stageNodes.push({...json.stageNodes[0], name: 'Doublon'});
        const sim = createSimulation(json);
        expect(sim.stage({stage: 'menuA'}).name).toBe('Choix A');
    });

    it('accepts OK where it is enabled, or in place of autoplay when the autoplay option is off', () => {
        const sim = createSimulation(story());
        expect(sim.canOk({stage: 'cover'}, true)).toBe(true);
        // A story: OK disabled, autoplay enabled
        expect(sim.canOk({stage: 'A'}, true)).toBe(false);
        expect(sim.canOk({stage: 'A'}, false)).toBe(true);
        const bare = createSimulation({stageNodes: [{uuid: 'x', squareOne: true}], actionNodes: []});
        expect(bare.canOk(bare.start(), false)).toBe(false);
    });
});

describe('splitDuration', () => {
    it('splits seconds into hours, minutes and seconds', () => {
        expect(splitDuration(3725.4)).toEqual({hours: 1, minutes: 2, seconds: 5});
        expect(splitDuration(59.6)).toEqual({hours: 0, minutes: 1, seconds: 0});
        expect(splitDuration(null)).toEqual({hours: 0, minutes: 0, seconds: 0});
    });
});
