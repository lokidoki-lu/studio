/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * The dialogs of the library: Escape closes the one on top, and the close button is labelled in the
 * language of the interface.
 */

import React from 'react';
import ReactDOM from 'react-dom';
import {act} from 'react-dom/test-utils';

import {Modal} from './Modal';

const t = (key) => key === 'dialogs.shared.close' ? 'Fermer' : key;

let container;

beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
});

afterEach(() => {
    ReactDOM.unmountComponentAtNode(container);
    container.remove();
});

const escape = () => act(() => {
    document.body.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
});

function dialog(id, onClose) {
    return <Modal key={id} id={id} t={t} title={id} content={<p>{id}</p>} buttons={[]} onClose={onClose}/>;
}

it('closes on Escape', () => {
    const onClose = jest.fn();
    act(() => { ReactDOM.render(dialog('one', onClose), container); });
    escape();
    expect(onClose).toHaveBeenCalledTimes(1);
});

it('closes only the dialog on top', () => {
    const below = jest.fn();
    const top = jest.fn();
    act(() => { ReactDOM.render(<>{dialog('below', below)}{dialog('top', top)}</>, container); });
    escape();
    expect(top).toHaveBeenCalledTimes(1);
    expect(below).not.toHaveBeenCalled();
});

it('no longer listens once closed', () => {
    const onClose = jest.fn();
    act(() => { ReactDOM.render(dialog('one', onClose), container); });
    act(() => { ReactDOM.unmountComponentAtNode(container); });
    escape();
    expect(onClose).not.toHaveBeenCalled();
});

it('labels its close button in the language of the interface', () => {
    act(() => { ReactDOM.render(dialog('one', () => {}), container); });
    expect(container.querySelector('.modal-header .close').getAttribute('aria-label')).toBe('Fermer');
});
