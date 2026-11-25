/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The packs whose content may come from a Lunii rather than from the user: packs copied from a Lunii
 * into the library, converted by STUdio from a raw or FS pack, found as raw / FS packs in the
 * library, or archives sharing an asset with one of those. Stored in
 * {@code ~/.studio/db/device-sourced.json}: their UUIDs, and the digests of their assets
 * ({@link PackAssetDigests}).
 *
 * <p>It marks the UUID, not a file: an archive of such a pack is no proof that the pack is the
 * user's, whatever it is named (downloaded and uploaded again, saved under another name by the
 * editor). And it marks the content: an archive holding the same assets under a new UUID (the editor
 * renews UUIDs) is marked when it enters the library. Only the digests of marked packs are kept, so
 * assets shared by packs of the user's own never mark anything.
 *
 * <p>A mark is never removed. Which is why a UUID is marked only when it is not proven to be the
 * user's at that moment, except by the library check at startup (see LibraryService).
 *
 * <p>Fail-closed: a file that cannot be understood counts as marking every UUID, and is never
 * overwritten (move or delete it to start a new one). Reads and replacements are retried a few times
 * (Windows reports a file being replaced or scanned as access denied for a moment).
 */
public class DeviceSourcedPackStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceSourcedPackStore.class);

    public static final String DEVICE_SOURCED_DB_PROP = "studio.db.devicesourced";
    public static final String DEVICE_SOURCED_DB_JSON_PATH = "/.studio/db/device-sourced.json";

    static final int SCHEMA_VERSION = 1;
    private static final String UUIDS_FIELD = "uuids";
    private static final String ASSETS_FIELD = "assets";
    private static final String SCANNED_FIELD = "scanned";
    private static final int ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 50;

    /** Serialises read-modify-write cycles: every instance writes the same file. */
    private static final Object WRITE_LOCK = new Object();

    /** Why a UUID was marked. */
    public enum Reason {
        EXTRACTED, CONVERTED, LIBRARY_CHECK, SAME_CONTENT, UPLOADED_RAW
    }

    /** Last parse, reused while the file keeps its size and modification time (guarded by this). */
    private JsonObject cached;
    private long cachedSize = -1;
    private long cachedTime = -1;

    /**
     * Path may be overridden by system property {@code studio.db.devicesourced}. By default, beside
     * the provenance database ({@code ~/.studio/db}, unless {@code studio.db.conversions} moves it).
     */
    static Path storePath() {
        String configured = System.getProperty(DEVICE_SOURCED_DB_PROP);
        if (configured != null) {
            return Paths.get(configured);
        }
        Path beside = ConversionProvenanceStore.storePath().toAbsolutePath().getParent();
        return beside != null ? beside.resolve("device-sourced.json")
                : Paths.get(System.getProperty("user.home") + DEVICE_SOURCED_DB_JSON_PATH);
    }

    private static JsonObject newRoot() {
        return new JsonObject().put("schemaVersion", SCHEMA_VERSION).put(UUIDS_FIELD, new JsonObject())
                .put(ASSETS_FIELD, new JsonObject()).put(SCANNED_FIELD, new JsonObject());
    }

    /** The store, never to be modified by the caller, or empty if it cannot be understood. */
    private synchronized Optional<JsonObject> read() {
        Path path = storePath();
        for (int attempt = 1; ; attempt++) {
            try {
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(path, BasicFileAttributes.class);
                } catch (NoSuchFileException e) {
                    return Optional.of(newRoot());
                }
                if (cached != null && attributes.size() == cachedSize && attributes.lastModifiedTime().toMillis() == cachedTime) {
                    return Optional.of(cached);
                }
                JsonObject root = new JsonObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                if (root.getInteger("schemaVersion", -1) != SCHEMA_VERSION || !(root.getValue(UUIDS_FIELD) instanceof JsonObject)) {
                    LOGGER.error("Unknown format for " + path + ": every pack counts as possibly coming from a Lunii");
                    return Optional.empty();
                }
                for (String field : new String[]{ASSETS_FIELD, SCANNED_FIELD}) {
                    if (!(root.getValue(field) instanceof JsonObject)) {
                        root.put(field, new JsonObject());
                    }
                }
                cached = root;
                cachedSize = attributes.size();
                cachedTime = attributes.lastModifiedTime().toMillis();
                return Optional.of(root);
            } catch (FileSystemException e) {
                // AccessDeniedException and the like: Windows, while the file is being replaced
                if (attempt >= ATTEMPTS) {
                    LOGGER.error("Cannot read " + path + ": every pack counts as possibly coming from a Lunii", e);
                    return Optional.empty();
                }
                pause();
            } catch (IOException | DecodeException | ClassCastException e) {
                LOGGER.error("Cannot read " + path + ": every pack counts as possibly coming from a Lunii", e);
                return Optional.empty();
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Whether the store can be read (if not, every UUID counts as marked). */
    public boolean isReadable() {
        return read().isPresent();
    }

    /** Whether {@code uuid} is marked, or the marks cannot be read (fail-closed). */
    public boolean isMarkedOrUnreadable(String uuid) {
        Optional<JsonObject> root = read();
        return !root.isPresent() || root.get().getJsonObject(UUIDS_FIELD).containsKey(uuid.toLowerCase(Locale.ROOT));
    }

    /** Whether one of {@code digests} is an asset of a marked pack (false if the store cannot be read). */
    public boolean sharesAssetWithMarked(Set<String> digests) {
        Optional<JsonObject> root = read();
        if (!root.isPresent()) {
            return false;
        }
        JsonObject assets = root.get().getJsonObject(ASSETS_FIELD);
        return digests.stream().anyMatch(assets::containsKey);
    }

    /** Whether any asset digest is stored: if not, no archive can share content with a marked pack. */
    public boolean hasMarkedAssets() {
        return read().map(root -> !root.getJsonObject(ASSETS_FIELD).isEmpty()).orElse(true);
    }

    /** The signature of a library entry when the library check last digested it, if it did. */
    public Optional<String> scannedSignature(String entryName) {
        return read().map(root -> root.getJsonObject(SCANNED_FIELD).getString(entryName));
    }

    /**
     * Marks {@code uuid} and the digests of its assets.
     *
     * @param scannedEntry the library entry digested (with its {@code signature}), or null
     * @throws IOException if the mark could not be stored: the caller must then not go on with
     *                     what it was about to do
     */
    public void mark(String uuid, Reason reason, Collection<String> assetDigests, String scannedEntry, String signature) throws IOException {
        synchronized (WRITE_LOCK) {
            Path path = storePath().toAbsolutePath();
            Optional<JsonObject> current = read();
            if (!current.isPresent()) {
                throw new IOException("Refusing to write over " + path + ", which could not be read");
            }
            JsonObject root = current.get().copy();
            String key = uuid.toLowerCase(Locale.ROOT);
            JsonObject uuids = root.getJsonObject(UUIDS_FIELD);
            JsonObject assets = root.getJsonObject(ASSETS_FIELD);
            boolean changed = false;
            if (!uuids.containsKey(key)) {
                uuids.put(key, new JsonObject().put("reason", reason.name()).put("at", System.currentTimeMillis()));
                changed = true;
            }
            for (String digest : assetDigests) {
                if (!assets.containsKey(digest)) {
                    assets.put(digest, key);
                    changed = true;
                }
            }
            if (scannedEntry != null && !signature.equals(root.getJsonObject(SCANNED_FIELD).getString(scannedEntry))) {
                root.getJsonObject(SCANNED_FIELD).put(scannedEntry, signature);
                changed = true;
            }
            if (!changed) {
                return;
            }
            write(path, root);
            LOGGER.info("Pack " + key + " marked as possibly coming from a Lunii (" + reason + ", "
                    + assetDigests.size() + " asset(s))");
        }
    }

    /** Marks {@code uuid} alone. See {@link #mark(String, Reason, Collection, String, String)}. */
    public void mark(String uuid, Reason reason) throws IOException {
        mark(uuid, reason, Collections.emptySet(), null, null);
    }

    private static void write(Path path, JsonObject root) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, root.encodePrettily().getBytes(StandardCharsets.UTF_8));
            for (int attempt = 1; ; attempt++) {
                try {
                    try {
                        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                    }
                    break;
                } catch (FileSystemException e) {
                    // AccessDeniedException: Windows, while another process (indexer, antivirus) holds the file
                    if (attempt >= ATTEMPTS) {
                        throw e;
                    }
                    pause();
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
