/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.model;

/** An image or a sound of a stage node: its mime type, its bytes and its name in the source pack. */
public interface Asset {

    String getMimeType();

    /** The bytes of the asset, or {@code null} for a pack read without them (streaming conversions). */
    byte[] getRawData();

    String getName();
}
