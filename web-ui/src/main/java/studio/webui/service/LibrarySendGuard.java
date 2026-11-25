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
 * pack already there), or a Lunii pack copied into the library, whatever the client decided:
 * official packs are never transferred, and the pack written must be the one the request names.
 * The UUID and the path of a request are independent fields: without this,
 * {@code {uuid: A, path: <folder of B>}} would replace A's content by B's.
 *
 * <p>Fail-closed: a pack is transferred only if it is known to be one of the user's own
 * ({@link LibraryService#isKnownHomemadePack}), not merely because the official database does not
 * list it — that database may be empty (offline) or older than the pack.
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
        Optional<StoryTellerException> notReady = notReady(library, uuid, "par précaution, STUdio ne l'envoie pas sur la Lunii");
        if (notReady.isPresent()) {
            return notReady;
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
        if (!library.isKnownHomemadePack(declared.get())) {
            return Optional.of(notProven(declared.get(), library,
                    "par précaution, STUdio ne l'envoie pas sur la Lunii"));
        }
        // A raw or FS pack only if STUdio converted it from the archive that proves the pack is the
        // user's: a folder renamed by hand takes the UUID of its new name
        if (!library.isProvenSendSource(path, declared.get())) {
            return Optional.of(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                    "L'entrée " + path + " de la bibliothèque n'est pas reliée à l'archive (.zip) du pack " + declared.get()
                            + " par une conversion faite par STUdio : par précaution, elle n'est pas envoyée sur la Lunii."
                            + " Envoyez l'archive elle-même, ou reconvertissez-la."));
        }
        return Optional.empty();
    }

    /**
     * The refusal of copying the pack {@code uuid} from a Lunii into the library, or empty if it may
     * be copied: only one of the user's own packs, never an official one.
     */
    public static Optional<StoryTellerException> extractionRefusal(LibraryService library, String uuid) {
        if (library.isOfficialPack(uuid)) {
            return Optional.of(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                    "Le pack " + uuid + " est un pack officiel (official pack) : STUdio ne copie pas les packs officiels depuis la Lunii"));
        }
        String consequence = "par précaution, STUdio ne le copie pas dans la bibliothèque";
        Optional<StoryTellerException> notReady = notReady(library, uuid, consequence);
        if (notReady.isPresent()) {
            return notReady;
        }
        if (!library.isKnownHomemadePack(uuid)) {
            return Optional.of(notProven(uuid, library, consequence));
        }
        return Optional.empty();
    }

    /**
     * Refused while nothing can be decided: the library check at startup not done, the official
     * database not downloaded yet, a database of proofs unreadable.
     */
    private static Optional<StoryTellerException> notReady(LibraryService library, String uuid, String consequence) {
        Optional<String> check = library.libraryCheckProblem();
        if (check.isPresent()) {
            return Optional.of(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, check.get()));
        }
        if (!library.isOfficialDatabaseAvailable()) {
            return Optional.of(notProven(uuid, library, consequence));
        }
        return Optional.empty();
    }

    private static StoryTellerException notProven(String uuid, LibraryService library, String consequence) {
        Optional<String> problem = library.proofDatabaseProblem();
        if (problem.isPresent()) {
            return new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                    problem.get() + " Le pack " + uuid + " n'est donc pas transféré.");
        }
        return new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                "Le pack " + uuid + " n'est pas reconnu comme un de vos packs : la bibliothèque n'en a pas"
                        + " d'archive (.zip) créée avec STUdio. Les packs officiels ne sont jamais transférés,"
                        + " " + consequence + "."
                        + (library.isOfficialDatabaseAvailable() ? ""
                        : " (La base des packs officiels n'a pas encore pu être téléchargée.)"));
    }

    private static StoryTellerException official(String uuid) {
        return new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                "The pack " + uuid + " is an official pack: STUdio does not send official packs to the Lunii");
    }
}
