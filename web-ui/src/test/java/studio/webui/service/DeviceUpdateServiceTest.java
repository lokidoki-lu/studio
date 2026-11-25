/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.metadata.DatabaseMetadataService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "update available" criterion: content origins, records per device, and the fallbacks. */
class DeviceUpdateServiceTest {

    private static final String U = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
    private static final String DEVICE = "SN0001";

    @TempDir
    Path root;

    private final Map<String, String> previous = new HashMap<>();
    private LibraryService library;
    private DeviceUpdateService updates;
    /** Library folders the fake device "holds" exactly. */
    private final Set<String> matching = new HashSet<>();

    private void setProperty(String key, String value) {
        previous.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("library"));
        Files.createDirectories(root.resolve("tmp"));
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, root.resolve("library") + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp") + File.separator);
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, root.resolve("unofficial.json").toString());
        setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP, root.resolve("conversions.json").toString());
        setProperty(DeviceUpdateService.DEVICE_PACKS_DB_PROP, root.resolve("device-packs.json").toString());
        library = new LibraryService(new DatabaseMetadataService(true));
        IStoryTellerService device = new IStoryTellerService() {
            @Override
            public CompletableFuture<Optional<JsonObject>> deviceInfos() {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<JsonArray> packs() {
                return CompletableFuture.completedFuture(new JsonArray());
            }

            @Override
            public CompletableFuture<Optional<String>> addPack(String uuid, File packFile) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Boolean> deletePack(String uuid) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Boolean> reorderPacks(List<String> uuids) {
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<Optional<String>> extractPack(String uuid, File destFile) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Void> dump(String outputPath) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public TransferRegistry transfers() {
                return new TransferRegistry();
            }

            @Override
            public CompletableFuture<JsonArray> orphanFolders() {
                return CompletableFuture.completedFuture(new JsonArray());
            }

            @Override
            public CompletableFuture<JsonArray> removeOrphanFolders(List<String> folderNames) {
                return CompletableFuture.completedFuture(new JsonArray());
            }

            @Override
            public CompletableFuture<Boolean> packContentMatches(String uuid, File packFolder) {
                return CompletableFuture.completedFuture(matching.contains(packFolder.getName()));
            }
        };
        updates = new DeviceUpdateService(device, library);
    }

    @AfterEach
    void restore() {
        previous.forEach((k, v) -> {
            if (v == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, v);
            }
        });
    }

    private void file(String name, String content) throws IOException {
        Files.write(root.resolve("library").resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private void folder(String name, String content) throws IOException {
        Path folder = root.resolve("library").resolve(name);
        Files.createDirectories(folder);
        Files.write(folder.resolve("ni"), content.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject artefact(String path, String format, int version) {
        return new JsonObject().put("uuid", U).put("path", path).put("format", format).put("version", version);
    }

    private static JsonArray groups(JsonObject... artefacts) {
        JsonArray packs = new JsonArray();
        for (JsonObject a : artefacts) {
            packs.add(a);
        }
        return new JsonArray().add(new JsonObject().put("uuid", U).put("packs", packs));
    }

    private static JsonArray device(int version) {
        return new JsonArray().add(new JsonObject().put("uuid", U).put("version", version));
    }

    private JsonObject status(JsonArray device, JsonArray groups) {
        JsonArray packs = updates.compute(DEVICE, device, groups).getJsonArray("packs");
        assertEquals(1, packs.size());
        return packs.getJsonObject(0);
    }

    @Test
    @DisplayName("no record, same version, nothing to compare: UNKNOWN, no badge")
    void unknown() throws IOException {
        file("a.zip", "v1");
        JsonObject s = status(device(1), groups(artefact("a.zip", "archive", 1)));
        assertEquals("UNKNOWN", s.getString("status"));
    }

    @Test
    @DisplayName("no record, higher library version: update available on the version")
    void byVersion() throws IOException {
        file("a.zip", "v2");
        JsonObject s = status(device(1), groups(artefact("a.zip", "archive", 2)));
        assertEquals("UPDATE_AVAILABLE", s.getString("status"));
        assertEquals("version", s.getString("basis"));
    }

    @Test
    @DisplayName("what was written is recorded: up to date, then a regenerated pack with the same uuid and version is an update")
    void recordThenRegenerated() throws IOException {
        folder("a.fs", "first");
        updates.onWritten(DEVICE, U, root.resolve("library").resolve("a.fs").toFile());
        assertEquals("UP_TO_DATE", status(device(1), groups(artefact("a.fs", "fs", 1))).getString("status"));

        // Regenerated: same uuid, same version 1, other content, listed first
        file("a.zip", "regenerated");
        JsonObject s = status(device(1), groups(artefact("a.zip", "archive", 1), artefact("a.fs", "fs", 1)));
        assertEquals("UPDATE_AVAILABLE", s.getString("status"));
        assertEquals("content", s.getString("basis"));
    }

    @Test
    @DisplayName("no record, but the card matches a library folder: recorded once, then compared on content")
    void bootstrapFromExactMatch() throws IOException {
        folder("a.fs", "old");
        file("a.zip", "new");
        matching.add("a.fs");
        JsonObject s = status(device(1), groups(artefact("a.zip", "archive", 1), artefact("a.fs", "fs", 1)));
        assertEquals("UPDATE_AVAILABLE", s.getString("status"));
        assertTrue(updates.recordedOrigin(DEVICE, U).isPresent());

        matching.clear();
        // The record answers now, without comparing the card again
        assertEquals("UP_TO_DATE", status(device(1), groups(artefact("a.fs", "fs", 1))).getString("status"));
    }

    @Test
    @DisplayName("no record, the current content is ready as a folder and the card is not it: update available")
    void currentFolderNotOnCard() throws IOException {
        folder("a.fs", "current");
        JsonObject s = status(device(1), groups(artefact("a.fs", "fs", 1)));
        assertEquals("UPDATE_AVAILABLE", s.getString("status"));
    }

    @Test
    @DisplayName("official packs are never offered, and a removal forgets the record")
    void officialAndRemoval() throws IOException {
        folder("a.fs", "x");
        JsonArray official = new JsonArray().add(new JsonObject().put("uuid", U).put("version", 1).put("official", true));
        assertEquals("OFFICIAL", status(official, groups(artefact("a.fs", "fs", 2))).getString("status"));

        updates.onWritten(DEVICE, U, root.resolve("library").resolve("a.fs").toFile());
        assertTrue(updates.recordedOrigin(DEVICE, U).isPresent());
        updates.onRemoved(DEVICE, U);
        assertFalse(updates.recordedOrigin(DEVICE, U).isPresent());
    }

    @Test
    @DisplayName("a pack the library does not hold is not listed")
    void notInLibrary() {
        JsonArray packs = updates.compute(DEVICE, device(1), new JsonArray()).getJsonArray("packs");
        assertTrue(packs.isEmpty());
    }
}
