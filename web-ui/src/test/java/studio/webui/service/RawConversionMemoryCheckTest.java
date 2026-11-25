/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.driver.StoryTellerException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M1: an archive whose decoded audio cannot fit in the heap is refused before it is converted to raw. */
class RawConversionMemoryCheckTest {

    private static final long MIB = 1024 * 1024;

    @Test
    @DisplayName("ten hours of audio (2.3 GB of WAV) are refused with a 1 GiB heap, with a message that says why")
    void tooLongIsRefused() {
        StoryTellerException e = assertThrows(StoryTellerException.class,
                () -> LibraryService.requireMemoryFor(10 * 3600, 300 * MIB, 1024 * MIB));
        assertTrue(e.getMessage().contains("600 minutes"), e.getMessage());
        assertTrue(e.getMessage().contains("-Xmx"), e.getMessage());
    }

    @Test
    @DisplayName("an hour of audio (230 MB of WAV) is converted with a 1 GiB heap")
    void reasonableIsAccepted() {
        assertDoesNotThrow(() -> LibraryService.requireMemoryFor(3600, 30 * MIB, 1024 * MIB));
    }
}
