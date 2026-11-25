/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import {parseLinks, defaultForm, formProblem, buildRequest, isRunning, isFinished, progressPercent, lastLines} from './youtube';

describe('YouTube dialog', () => {

    it('reads one link per line', () => {
        expect(parseLinks(' https://youtu.be/a \r\n\n  https://www.youtube.com/playlist?list=PL\r')).toEqual([
            'https://youtu.be/a', 'https://www.youtube.com/playlist?list=PL'
        ]);
        expect(parseLinks(undefined)).toEqual([]);
    });

    it('starts from the backend defaults', () => {
        const form = defaultForm({defaultVoice: 'fr-FR-HenriNeural', defaultMenuSize: 0, defaultBitrate: 64, defaultMaxVideos: 20});
        expect(form.voice).toBe('fr-FR-HenriNeural');
        expect(form.menuSize).toBe(0);
        expect(form.bitrate).toBe(64);
        expect(form.maxVideos).toBe(20);
        expect(defaultForm(null)).toMatchObject({voice: 'fr-FR-DeniseNeural', menuSize: 10, bitrate: 96, cleanTitles: true,
            sponsorBlock: true, seasons: true, autoplayNext: false, pauses: true});
    });

    it('says what is wrong with the form', () => {
        const form = defaultForm(null);
        expect(formProblem(form)).toBe('youtubePack.problems.noLink');
        expect(formProblem({...form, links: 'a\nb\nc'}, 2)).toBe('youtubePack.problems.tooManyLinks');
        expect(formProblem({...form, links: 'a', minMinutes: '10', maxMinutes: '5'})).toBe('youtubePack.problems.durations');
        expect(formProblem({...form, links: 'a', minMinutes: '5', maxMinutes: ''})).toBeNull();
    });

    it('sends durations in seconds and only the fields that are set', () => {
        const body = buildRequest({...defaultForm(null), links: 'https://youtu.be/a\n', title: '  Mes contes ',
            minMinutes: '1,5', maxMinutes: '30', menuSize: '8', bitrate: '128', maxVideos: '500', autoplayNext: true});
        expect(body).toEqual({
            urls: ['https://youtu.be/a'], title: 'Mes contes', voice: 'fr-FR-DeniseNeural',
            cleanTitles: true, sponsorBlock: true, seasons: true, autoplayNext: true, pauses: true,
            minDuration: 90, maxDuration: 1800, menuSize: 8, bitrate: 128, maxVideos: 200
        });
        const plain = buildRequest({...defaultForm(null), links: 'x', bitrate: '100', menuSize: 'abc', minMinutes: '-3'});
        expect(plain.title).toBeUndefined();
        expect(plain.description).toBeUndefined();
        expect(plain.bitrate).toBe(96);
        expect(plain.menuSize).toBe(10);
        expect(plain.minDuration).toBe(0);
    });

    it('reads a job state', () => {
        expect(isRunning({state: 'RUNNING'})).toBe(true);
        expect(isRunning(null)).toBe(false);
        expect(isFinished({state: 'CANCELLED'})).toBe(true);
        expect(isFinished({state: 'RUNNING'})).toBe(false);
        expect(progressPercent({state: 'RUNNING', progress: 0.456})).toBe(46);
        expect(progressPercent({state: 'RUNNING', progress: 3})).toBe(100);
        expect(progressPercent({state: 'DONE', progress: 0.9})).toBe(100);
        expect(progressPercent({state: 'RUNNING'})).toBe(0);
        expect(lastLines({log: ['1', '2', '3']}, 2)).toEqual(['2', '3']);
        expect(lastLines({}, 2)).toEqual([]);
    });
});
