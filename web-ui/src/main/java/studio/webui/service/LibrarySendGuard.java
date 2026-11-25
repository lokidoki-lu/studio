/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import studio.driver.StoryTellerException;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * What the server checks itself before a library pack is written to a Lunii (added, or replacing the
 * pack already there), whatever the client decided: official packs are never sent, and the pack
 * written must be the one the request names. The UUID and the path of a request are independent
 * fields: without this, {@code {uuid: A, path: <folder of B>}} would replace A's content by B's.
 */
public final class LibrarySendGuard {

    private LibrarySendGuard() {
    }

    /**
     * @return the refusal, or empty if the pack may be sent. An entry that does not exist is left to
     * the transfer, which refuses it with {@code LIBRARY_PACK_NOT_FOUND}.
     */
    public static Optional<StoryTellerException> refusal(LibraryService library, String uuid, String path, Path entry) {
        if (library.isOfficialPack(uuid)) {
            return Optional.of(official(uuid));
        }
        if (entry == null || !Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Optional<String> declared = library.declaredUuid(path);
        if (declared.isEmpty()) {
            return Optional.of(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                    "The library entry " + path + " could not be read as a pack: its UUID cannot be checked, nothing was sent"));
        }
        if (!declared.get().toLowerCase(Locale.ROOT).equals(uuid.toLowerCase(Locale.ROOT))) {
            return Optional.of(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                    "The library entry " + path + " is the pack " + declared.get() + ", not " + uuid + ": nothing was sent"));
        }
        if (library.isOfficialPack(declared.get())) {
            return Optional.of(official(declared.get()));
        }
        return Optional.empty();
    }

    private static StoryTellerException official(String uuid) {
        return new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                "The pack " + uuid + " is an official pack: STUdio does not send official packs to the Lunii");
    }
}
