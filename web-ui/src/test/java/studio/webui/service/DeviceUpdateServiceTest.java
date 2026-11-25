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
    /** How many times the card was compared with a library folder. */
    private int comparisons;
    private boolean busy;
    /** Run in the middle of a comparison, as a transfer finishing meanwhile would. */
    private Runnable duringComparison = () -> { };
    /** When set, what a comparison answers (an error, nothing, the queue busy) instead of the fake card. */
    private java.util.function.Supplier<CompletableFuture<Boolean>> comparisonAnswer;

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
            public CompletableFuture<JsonArray> removeOrphanFolders(List<String> folderNames, String deviceToken) {
                return CompletableFuture.completedFuture(new JsonArray());
            }

            @Override
            public CompletableFuture<Boolean> packContentMatches(String uuid, File packFolder) {
                comparisons++;
                if (comparisonAnswer != null) {
                    return comparisonAnswer.get();
                }
                boolean same = matching.contains(packFolder.getName());
                duringComparison.run();
                return CompletableFuture.completedFuture(same);
            }

            @Override
            public boolean deviceBusy() {
                return busy;
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
    @DisplayName("a comparison that ends after a pack was written does not overwrite what was written")
    void compareThenWriteKeepsTheNewerRecord() throws IOException {
        folder("a.fs", "old");
        folder("b.fs", "new");
        matching.add("a.fs");
        // While the card is compared with a.fs, b.fs is written to it and recorded
        duringComparison = () -> updates.onWritten(DEVICE, U, root.resolve("library").resolve("b.fs").toFile());
        status(device(1), groups(artefact("b.fs", "fs", 1), artefact("a.fs", "fs", 1)));
        assertEquals(library.originOf("b.fs"), updates.recordedOrigin(DEVICE, U));
        duringComparison = () -> { };
        assertEquals("UP_TO_DATE", status(device(1), groups(artefact("b.fs", "fs", 1))).getString("status"));
    }

    @Test
    @DisplayName("no comparison while the device is busy, and a folder found different is not read again")
    void comparisonsAreSparing() throws IOException {
        folder("a.fs", "current");
        busy = true;
        status(device(1), groups(artefact("a.fs", "fs", 1)));
        assertEquals(0, comparisons);
        busy = false;
        status(device(1), groups(artefact("a.fs", "fs", 1)));
        status(device(1), groups(artefact("a.fs", "fs", 1)));
        assertEquals(1, comparisons);
        // Another state of the card (another version on it): compared again
        status(device(2), groups(artefact("a.fs", "fs", 1)));
        assertEquals(2, comparisons);
    }

    @Test
    @DisplayName("P6: a comparison that fails is not taken for a difference: not remembered, no badge, tried again")
    void failedComparisonIsNotADifference() throws IOException {
        folder("a.fs", "current");
        comparisonAnswer = () -> CompletableFuture.failedFuture(new IOException("card read error"));
        JsonObject s = status(device(1), groups(artefact("a.fs", "fs", 1)));
        assertEquals("UNKNOWN", s.getString("status"));
        status(device(1), groups(artefact("a.fs", "fs", 1)));
        assertEquals(2, comparisons, "compared again: the error was not remembered as a difference");

        // A real difference is remembered
        comparisonAnswer = null;
        assertEquals("UPDATE_AVAILABLE", status(device(1), groups(artefact("a.fs", "fs", 1))).getString("status"));
        assertEquals("UPDATE_AVAILABLE", status(device(1), groups(artefact("a.fs", "fs", 1))).getString("status"));
        assertEquals(3, comparisons);
    }

    @Test
    @DisplayName("P6: a comparison not answered in time is abandoned and unknown; one refused because the queue got busy stops the others")
    void comparisonNeverBlocksTheListing() throws IOException {
        folder("a.fs", "current");
        folder("b.fs", "older");
        CompletableFuture<Boolean> never = new CompletableFuture<>();
        comparisonAnswer = () -> never;
        updates.comparisonTimeoutMillis = 50;
        long start = System.nanoTime();
        JsonObject s = status(device(1), groups(artefact("a.fs", "fs", 1), artefact("b.fs", "fs", 1)));
        assertTrue(System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        assertEquals("UNKNOWN", s.getString("status"));
        assertTrue(never.isCancelled(), "abandoned, so that it does not start later");

        // The queue got busy between deviceBusy() and the comparison: no more comparisons now
        comparisons = 0;
        comparisonAnswer = () -> CompletableFuture.failedFuture(new DeviceOperationQueue.QueueBusyException());
        assertEquals("UNKNOWN", status(device(1), groups(artefact("a.fs", "fs", 1), artefact("b.fs", "fs", 1))).getString("status"));
        assertEquals(1, comparisons);
        comparisonAnswer = null;
        status(device(1), groups(artefact("a.fs", "fs", 1), artefact("b.fs", "fs", 1)));
        assertEquals(3, comparisons, "nothing was remembered: both folders compared once the device is free");
    }

    @Test
    @DisplayName("an unreadable record file is set aside, not overwritten; records of packs gone from the Lunii are dropped")
    void unreadableStoreAndCleanup() throws IOException {
        Path store = root.resolve("device-packs.json");
        Files.write(store, "{ not json".getBytes(StandardCharsets.UTF_8));
        folder("a.fs", "x");
        updates.onWritten(DEVICE, U, root.resolve("library").resolve("a.fs").toFile());
        try (java.util.stream.Stream<Path> files = Files.list(root)) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("device-packs.json.corrupt-")));
        }
        assertTrue(updates.recordedOrigin(DEVICE, U).isPresent());

        // The pack is no longer on the Lunii: its record goes with the next listing
        updates.compute(DEVICE, new JsonArray(), groups(artefact("a.fs", "fs", 1)));
        assertFalse(updates.recordedOrigin(DEVICE, U).isPresent());
    }

    @Test
    @DisplayName("a pack the library does not hold is not listed")
    void notInLibrary() {
        JsonArray packs = updates.compute(DEVICE, device(1), new JsonArray()).getJsonArray("packs");
        assertTrue(packs.isEmpty());
    }
}
