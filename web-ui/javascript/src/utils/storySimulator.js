/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The story teller, as the library preview simulates it: straight from a pack's story.json, without
 * building the editor's diagram model (which loads every asset of the pack before showing anything).
 *
 * A state is {stage, action, index}: the uuid of the stage node shown, and the action node (id) and
 * option index it was reached through, or null for the pack's first node. Every move returns
 * {state, event}, event being:
 *  - 'moved': a new stage node is shown;
 *  - 'end': the story ended (no OK transition) and the device went back to the first node;
 *  - 'exit': home without a transition, which leaves the pack on the device: back to the first node;
 *  - 'none': the button does nothing here (disabled control, no option to turn the wheel to);
 *  - 'broken': the transition names a node the pack does not have.
 */

const NO_CONTROLS = {wheel: false, ok: false, home: false, pause: false, autoplay: false};

/** Node and story counts, as the preview shows them. A story is a stage node where pause is enabled. */
export function storyStats(story) {
    const stageNodes = (story && Array.isArray(story.stageNodes)) ? story.stageNodes : [];
    const actionNodes = (story && Array.isArray(story.actionNodes)) ? story.actionNodes : [];
    return {
        stageNodes: stageNodes.length,
        actionNodes: actionNodes.length,
        stories: stageNodes.filter(node => node && node.controlSettings && node.controlSettings.pause === true).length
    };
}

export function createSimulation(story, random = Math.random) {
    const stageNodes = (story && Array.isArray(story.stageNodes)) ? story.stageNodes : [];
    const actionNodes = (story && Array.isArray(story.actionNodes)) ? story.actionNodes : [];
    // A uuid given to several stage nodes names the first of them
    const stages = new Map();
    stageNodes.forEach(node => {
        if (node && node.uuid && !stages.has(node.uuid)) {
            stages.set(node.uuid, node);
        }
    });
    const actions = new Map(actionNodes.filter(node => node && node.id).map(node => [node.id, node]));
    const first = stageNodes.find(node => node && node.squareOne) || stageNodes[0];
    if (!first) {
        const error = new Error('NO_STAGE_NODE');
        // For the preview's message, translated (preview.simulation.errors.NO_STAGE_NODE)
        error.code = 'NO_STAGE_NODE';
        throw error;
    }
    const startState = {stage: first.uuid, action: null, index: null};

    const stage = (state) => stages.get(state.stage) || null;
    const controls = (state) => {
        const node = stage(state);
        return {...NO_CONTROLS, ...((node && node.controlSettings) || {})};
    };

    // Where a transition leads: the option it names of its action node, or a random one (index -1)
    const follow = (transition) => {
        const action = actions.get(transition.actionNode);
        const options = action && Array.isArray(action.options) ? action.options : [];
        if (options.length === 0) {
            return {state: null, event: 'broken'};
        }
        // Some packs give the index as a string ("1")
        let index = transition.optionIndex;
        if (index === undefined || index === null || index === '') {
            index = -1;
        }
        index = Number(index);
        if (index === -1) {
            index = Math.min(options.length - 1, Math.floor(random() * options.length));
        }
        if (!Number.isInteger(index) || index < 0 || index >= options.length || !stages.has(options[index])) {
            return {state: null, event: 'broken'};
        }
        return {state: {stage: options[index], action: action.id, index}, event: 'moved'};
    };

    const stay = (state, event) => ({state, event});

    return {
        start: () => ({...startState}),
        stage,
        controls,

        /** The image and audio asset names of the node shown. */
        assets: (state) => {
            const node = stage(state);
            return {image: (node && node.image) || null, audio: (node && node.audio) || null};
        },

        /** Image assets of the options next to the one shown, worth loading ahead of the wheel. */
        neighbourImages: (state) => {
            const action = state.action && actions.get(state.action);
            if (!action || !Array.isArray(action.options) || action.options.length < 2) {
                return [];
            }
            const n = action.options.length;
            return [(state.index + 1) % n, (state.index - 1 + n) % n]
                .map(i => stages.get(action.options[i]))
                .filter(node => node && node.image)
                .map(node => node.image)
                .filter((image, i, all) => all.indexOf(image) === i);
        },

        /**
         * Whether OK does something on the node shown: when the OK button is enabled, or, as on the
         * device and in the editor's viewer, to replace autoplay when the autoplay option is off.
         */
        canOk: (state, autoplayOption) => {
            const c = controls(state);
            return c.ok === true || (c.autoplay === true && !autoplayOption);
        },

        /** OK, or the end of the audio of a node with autoplay (callers check canOk for a button press). */
        ok: (state) => {
            const node = stage(state);
            if (!node) {
                return stay(state, 'broken');
            }
            if (!node.okTransition) {
                return {state: {...startState}, event: 'end'};
            }
            const next = follow(node.okTransition);
            return next.state ? next : stay(state, next.event);
        },

        home: (state) => {
            const node = stage(state);
            if (!node || !controls(state).home) {
                return stay(state, 'none');
            }
            if (!node.homeTransition) {
                return {state: {...startState}, event: 'exit'};
            }
            const next = follow(node.homeTransition);
            return next.state ? next : stay(state, next.event);
        },

        /** Turns the wheel by one option (delta -1 or 1), wrapping around as the device does. */
        wheel: (state, delta) => {
            if (!controls(state).wheel || !state.action) {
                return stay(state, 'none');
            }
            const action = actions.get(state.action);
            const options = action && Array.isArray(action.options) ? action.options : [];
            if (options.length < 2) {
                return stay(state, 'none');
            }
            const index = ((state.index + delta) % options.length + options.length) % options.length;
            if (!stages.has(options[index])) {
                return stay(state, 'broken');
            }
            return {state: {stage: options[index], action: action.id, index}, event: 'moved'};
        }
    };
}

/** A duration in seconds as {hours, minutes, seconds}, rounded to the second. */
export function splitDuration(totalSeconds) {
    const total = Math.max(0, Math.round(totalSeconds || 0));
    return {hours: Math.floor(total / 3600), minutes: Math.floor((total % 3600) / 60), seconds: total % 60};
}
