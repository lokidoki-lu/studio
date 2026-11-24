/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.ControlSettings;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;
import studio.metadata.DatabaseMetadataService;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which library entries the purge offers, and that it removes nothing else.
 *
 * <p>Everything happens in a temporary library. The real {@code ~/.studio/library} is never named.
 */
class LibraryPurgeTest {

    /** The UUID of the case that prompted this: « Les daltons ». */
    private static final String DALTONS = "d86a08cd-3fff-5141-94a4-1b6f53a2c1a5";
    private static final String ARCHIVE = "Les daltons.zip";

    @TempDir
    Path root;

    private Path library;
    private Path store;
    private LibraryService service;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = root.resolve("library");
        Files.createDirectories(library);
        Files.createDirectories(root.resolve("tmp"));
        Files.createDirectories(root.resolve("db"));
        store = root.resolve("db").resolve("conversions.json");

        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp") + File.separator);
        setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP, store.toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP,
                root.resolve("db").resolve("unofficial.json").toString());

        service = new LibraryService(new DatabaseMetadataService(true));
    }

    @AfterEach
    void tearDown() {
        previousProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    // ------------------------------------------------- the reported case

    /**
     * The archive was converted on 22/11, then replaced by a new version on 23/11 and converted
     * again. Returns {old conversion, new conversion}.
     */
    private String[] daltons() throws Exception {
        writeArchive(ARCHIVE, DALTONS, "first version");
        String old = convert(ARCHIVE);
        Thread.sleep(5);
        writeArchive(ARCHIVE, DALTONS, "second, longer version of the pack");
        String current = convert(ARCHIVE);

        touch(old, at(2026, 11, 22, 21, 40));
        touch(ARCHIVE, at(2026, 11, 23, 23, 2));
        touch(current, at(2026, 11, 23, 23, 5));
        return new String[]{old, current};
    }

    @Test
    @DisplayName("Les daltons: only the conversion made from the previous archive is offered")
    void daltonsCase() throws Exception {
        String[] conversions = daltons();

        List<PurgeCandidate> candidates = service.purgeCandidates();

        assertEquals(1, candidates.size(), "candidates: " + paths(candidates));
        PurgeCandidate candidate = candidates.get(0);
        assertEquals(conversions[0], candidate.getPath());
        assertEquals(DALTONS, candidate.getUuid());
        assertEquals("fs", candidate.getFormat());
        assertEquals(PurgeCandidate.Reason.SUPERSEDED, candidate.getReason());
        assertTrue(candidate.toJson().getBoolean("selected"));
        assertEquals(conversions[1], candidate.getKeptPath());
        assertEquals(at(2026, 11, 22, 21, 40), candidate.getTimestamp());
        assertTrue(candidate.getSize() > 0);
    }

    @Test
    @DisplayName("Les daltons without any provenance ledger: the same single candidate")
    void daltonsCaseWithoutLedger() throws Exception {
        String[] conversions = daltons();
        Files.delete(store);

        assertEquals(Collections.singletonList(conversions[0]), paths(service.purgeCandidates()));
    }

    @Test
    @DisplayName("purging the Daltons candidate removes it and keeps the archive and the current conversion")
    void daltonsPurge() throws Exception {
        String[] conversions = daltons();

        List<LibraryService.PurgeResult> results = service.purge(Collections.singletonList(conversions[0]));

        assertEquals(1, results.size());
        assertTrue(results.get(0).isRemoved());
        assertNull(results.get(0).getError());
        assertTrue(results.get(0).getSize() > 0);
        assertFalse(Files.exists(library.resolve(conversions[0])));
        assertTrue(Files.exists(library.resolve(ARCHIVE)));
        assertTrue(Files.exists(library.resolve(conversions[1])));
        assertTrue(service.purgeCandidates().isEmpty());
        // The listing no longer shows it
        assertEquals(2, service.packs().getJsonObject(0).getJsonArray("packs").size());
    }

    // ------------------------------------------------- provenance

    @Test
    @DisplayName("the only conversion, provably made from an archive since replaced, is offered")
    void provenStaleConversionIsOffered() throws Exception {
        writeArchive(ARCHIVE, DALTONS, "first version");
        String conversion = convert(ARCHIVE);
        writeArchive(ARCHIVE, DALTONS, "second, longer version of the pack");

        List<PurgeCandidate> candidates = service.purgeCandidates();

        assertEquals(Collections.singletonList(conversion), paths(candidates));
        assertEquals(PurgeCandidate.Reason.SOURCE_CHANGED, candidates.get(0).getReason());
        assertTrue(candidates.get(0).toJson().getBoolean("selected"));
        assertEquals(ARCHIVE, candidates.get(0).getKeptPath());
    }

    @Test
    @DisplayName("a conversion still matching the archive is not offered")
    void matchingConversionIsKept() throws Exception {
        writeArchive(ARCHIVE, DALTONS, "first version");
        convert(ARCHIVE);

        assertTrue(service.purgeCandidates().isEmpty());
    }

    @Test
    @DisplayName("unknown provenance is never a reason to purge, even after the archive changed")
    void unknownProvenanceIsKept() throws Exception {
        writeArchive(ARCHIVE, DALTONS, "first version");
        convert(ARCHIVE);
        writeArchive(ARCHIVE, DALTONS, "second, longer version of the pack");
        // A conversion made before provenance was recorded
        Files.delete(store);

        assertTrue(service.purgeCandidates().isEmpty());
    }

    @Test
    @DisplayName("a record that no longer describes the conversion on disk proves nothing")
    void tamperedConversionIsKept() throws Exception {
        writeArchive(ARCHIVE, DALTONS, "first version");
        String conversion = convert(ARCHIVE);
        writeArchive(ARCHIVE, DALTONS, "second, longer version of the pack");
        Files.write(library.resolve(conversion).resolve("extra"), "x".getBytes(StandardCharsets.UTF_8));

        assertTrue(service.purgeCandidates().isEmpty());
    }

    @Test
    @DisplayName("a stale conversion that is the last copy of its pack is never offered")
    void lastCopyIsKept() throws Exception {
        writeArchive(ARCHIVE, DALTONS, "first version");
        convert(ARCHIVE);
        Files.delete(library.resolve(ARCHIVE));

        assertTrue(service.purgeCandidates().isEmpty());
    }

    @Test
    @DisplayName("when modification times and conversion stamps disagree, nothing is offered")
    void ambiguousOrderIsKept() throws Exception {
        String[] conversions = daltons();
        // The older conversion now looks more recent to the listing than the newer one
        touch(conversions[0], at(2026, 11, 24, 8, 0));
        Files.delete(store);

        assertTrue(service.purgeCandidates().isEmpty());
    }

    // ------------------------------------------------- several archives

    @Test
    @DisplayName("an older archive identical to the current one is listed, unselected")
    void duplicateArchiveIsOfferedUnselected() throws Exception {
        writeArchive(ARCHIVE, DALTONS, "first version");
        Files.copy(library.resolve(ARCHIVE), library.resolve("copie.zip"));
        touch("copie.zip", at(2026, 11, 1, 10, 0));
        touch(ARCHIVE, at(2026, 11, 23, 23, 2));

        List<PurgeCandidate> candidates = service.purgeCandidates();

        assertEquals(Collections.singletonList("copie.zip"), paths(candidates));
        assertEquals(PurgeCandidate.Reason.DUPLICATE_SOURCE, candidates.get(0).getReason());
        assertFalse(candidates.get(0).toJson().getBoolean("selected"));
        assertEquals(ARCHIVE, candidates.get(0).getKeptPath());
    }

    @Test
    @DisplayName("an older archive with different content is never offered")
    void olderDifferentArchiveIsKept() throws Exception {
        writeArchive("ancienne.zip", DALTONS, "first version");
        touch("ancienne.zip", at(2026, 11, 1, 10, 0));
        writeArchive(ARCHIVE, DALTONS, "second, longer version of the pack");

        assertTrue(service.purgeCandidates().isEmpty());
    }

    // ------------------------------------------------- the purge checks again

    @Test
    @DisplayName("a stale list removes nothing that is no longer a candidate")
    void staleListIsRechecked() throws Exception {
        String[] conversions = daltons();
        assertEquals(1, service.purgeCandidates().size());
        // Between the listing and the purge, the current conversion disappears: the old one is now
        // the only conversion, and no longer a candidate.
        deleteRecursively(library.resolve(conversions[1]));
        Files.delete(store);

        List<LibraryService.PurgeResult> results = service.purge(Collections.singletonList(conversions[0]));

        assertFalse(results.get(0).isRemoved());
        assertEquals("NOT_A_CANDIDATE", results.get(0).getError());
        assertTrue(Files.exists(library.resolve(conversions[0])));
    }

    @Test
    @DisplayName("names that were never candidates are left alone, the others are removed")
    void nonCandidatesAreRefused() throws Exception {
        String[] conversions = daltons();

        List<LibraryService.PurgeResult> results = service.purge(
                Arrays.asList(ARCHIVE, conversions[0], conversions[1], "absent.zip"));

        Map<String, LibraryService.PurgeResult> byPath = results.stream()
                .collect(Collectors.toMap(LibraryService.PurgeResult::getPath, r -> r));
        assertTrue(byPath.get(conversions[0]).isRemoved());
        assertEquals("NOT_A_CANDIDATE", byPath.get(ARCHIVE).getError());
        assertEquals("NOT_A_CANDIDATE", byPath.get(conversions[1]).getError());
        assertEquals("NOT_A_CANDIDATE", byPath.get("absent.zip").getError());
        assertTrue(Files.exists(library.resolve(ARCHIVE)));
        assertTrue(Files.exists(library.resolve(conversions[1])));
    }

    @Test
    @DisplayName("a name outside the library refuses the whole request before anything is removed")
    void confinement() throws Exception {
        String[] conversions = daltons();
        Path outside = root.resolve("outside.zip");
        Files.write(outside, "not yours".getBytes(StandardCharsets.UTF_8));

        for (String bad : Arrays.asList("../outside.zip", outside.toString(), "sub/x.zip", "", "..")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.purge(Arrays.asList(conversions[0], bad)), "accepted: " + bad);
        }
        assertTrue(Files.exists(outside));
        assertTrue(Files.exists(library.resolve(conversions[0])), "nothing may be removed from a refused request");
    }

    // ------------------------------------------------- helpers

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private static List<String> paths(List<PurgeCandidate> candidates) {
        return candidates.stream().map(PurgeCandidate::getPath).collect(Collectors.toList());
    }

    private String convert(String sourceName) {
        Optional<Path> converted = service.addConvertedFsPackFile(sourceName, false);
        assertTrue(converted.isPresent(), "expected the fixture conversion to succeed");
        return converted.get().getFileName().toString();
    }

    private void touch(String name, long millis) throws IOException {
        Files.setLastModifiedTime(library.resolve(name), FileTime.fromMillis(millis));
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** The description varies the content, so two fixtures of one UUID differ by their bytes. */
    private Path writeArchive(String name, String uuid, String description) throws IOException {
        StageNode node = new StageNode(uuid, null, null, null, null,
                new ControlSettings(false, false, false, false, false), null);
        StoryPack pack = new StoryPack(uuid, false, (short) description.length(),
                Collections.singletonList(node), null, false);
        Path file = library.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            new ArchiveStoryPackWriter().write(pack, out);
        }
        return file;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
