/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a conversion leaves behind, and how its output reaches the library.
 *
 * <p>The temporary folder must be empty after every conversion, successful or not: an FS conversion
 * used to leave its {@code createTempDirectory} folder behind on success, and a failed conversion its
 * whole temporary. And installing a folder into a library on another filesystem must work rather than
 * fail on a non-atomic directory move.
 */
class ConversionInstallTest {

    private static final String PACK_UUID = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path root;

    private Path library;
    private Path workFolder;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = Files.createDirectories(root.resolve("library"));
        workFolder = Files.createDirectories(root.resolve("work"));
        Files.createDirectories(root.resolve("db"));
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, workFolder + File.separator);
        setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP, root.resolve("db").resolve("conversions.json").toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, root.resolve("db").resolve("unofficial.json").toString());
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

    private LibraryService service() {
        return new LibraryService(new DatabaseMetadataService(true));
    }

    @Test
    @DisplayName("an FS conversion leaves the temporary folder empty")
    void fsConversionCleansUp() throws Exception {
        writeArchive("pack.zip", PACK_UUID);

        Optional<Path> converted = service().addConvertedFsPackFile("pack.zip", false);

        assertTrue(converted.isPresent());
        assertTrue(Files.isDirectory(library.resolve(converted.get())), "the FS pack is installed");
        assertEquals(Collections.emptyList(), entries(workFolder), "nothing left in the temporary folder");
    }

    @Test
    @DisplayName("a raw conversion leaves the temporary folder empty")
    void rawConversionCleansUp() throws Exception {
        writeArchive("pack.zip", PACK_UUID);

        assertTrue(service().addConvertedRawPackFile("pack.zip", false).isPresent());
        assertEquals(Collections.emptyList(), entries(workFolder));
    }

    @Test
    @DisplayName("a failed conversion leaves neither a temporary nor anything in the library")
    void failedConversionCleansUp() throws Exception {
        writeArchive("pack.zip", "not-a-uuid-at-all");

        assertThrows(RuntimeException.class, () -> service().addConvertedRawPackFile("pack.zip", false));

        assertEquals(Collections.emptyList(), entries(workFolder), "the temporary is removed");
        assertEquals(Collections.singletonList("pack.zip"), entries(library), "only the source");
    }

    @Test
    @DisplayName("installs a folder into a library on another filesystem, through a hidden staging entry")
    void installsAcrossFilesystems() throws Exception {
        Path shm = Paths.get("/dev/shm");
        assumeTrue(Files.isDirectory(shm) && Files.isWritable(shm), "needs /dev/shm");
        assumeTrue(!Files.getFileStore(shm).equals(Files.getFileStore(library)), "needs two filesystems");
        Path otherVolume = Files.createTempDirectory(shm, "studio-test-");
        try {
            Path produced = Files.createDirectories(otherVolume.resolve("ABCDEF12"));
            Files.createDirectories(produced.resolve("rf").resolve("000"));
            Files.write(produced.resolve("ni"), new byte[]{1, 2, 3});
            Files.write(produced.resolve("rf").resolve("000").resolve("00000000"), new byte[]{4, 5});
            Path destination = library.resolve(PACK_UUID + ".converted_1");

            service().installIntoLibrary(produced, destination);

            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(destination.resolve("ni")));
            assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(destination.resolve("rf").resolve("000").resolve("00000000")));
            assertFalse(Files.exists(produced), "the source of the copy is removed");
            assertEquals(Collections.singletonList(destination.getFileName().toString()), entries(library),
                    "no staging entry left behind");
        } finally {
            org.apache.commons.io.FileUtils.deleteQuietly(otherVolume.toFile());
        }
    }

    @Test
    @DisplayName("refuses to install over an existing entry")
    void refusesExistingDestination() throws Exception {
        Path produced = Files.write(workFolder.resolve("x.pack"), new byte[]{1});
        Path destination = Files.write(library.resolve(PACK_UUID + ".converted_1.pack"), new byte[]{2});

        assertThrows(IOException.class, () -> service().installIntoLibrary(produced, destination));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(destination));
    }

    @Test
    @DisplayName("the listing does not show staging entries")
    void listingSkipsStagingEntries() throws Exception {
        writeArchive("pack.zip", PACK_UUID);
        writeArchive(LibraryService.STAGING_PREFIX + "pack.zip.123", PACK_UUID);
        writeArchive(LibraryService.STAGING_PREFIX + "half-copied.zip", PACK_UUID);

        JsonArray packs = service().packs();

        assertEquals(1, packs.size(), "one uuid");
        assertEquals(1, packs.getJsonObject(0).getJsonArray("packs").size(), "one pack: the staging ones are hidden");
        assertEquals("pack.zip", packs.getJsonObject(0).getJsonArray("packs").getJsonObject(0).getString("path"));
    }

    // ---------------------------------------------------------------- helpers

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private static List<String> entries(Path folder) throws IOException {
        try (Stream<Path> paths = Files.list(folder)) {
            return paths.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private void writeArchive(String name, String uuid) throws Exception {
        StageNode node = new StageNode(uuid, null, null, null, null,
                new ControlSettings(false, false, false, false, false), null);
        StoryPack pack = new StoryPack(uuid, false, (short) 1, Collections.singletonList(node), null, false);
        try (OutputStream out = Files.newOutputStream(library.resolve(name))) {
            new ArchiveStoryPackWriter().write(pack, out);
        }
    }
}
