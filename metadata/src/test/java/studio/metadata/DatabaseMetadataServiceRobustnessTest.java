/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.metadata;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unofficial database survives a corrupt file, is not rewritten when nothing changed, follows
 * changes made to the file by someone else, and is never left half written.
 *
 * <p>Offline: the official database is an empty file, so the network fetch is never reached.
 */
class DatabaseMetadataServiceRobustnessTest {

    private static final String UUID_A = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path root;

    private Path unofficialDatabase;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        Path officialDatabase = root.resolve("official.json");
        Files.write(officialDatabase, "{}".getBytes(StandardCharsets.UTF_8));
        unofficialDatabase = root.resolve("unofficial.json");
        setProperty(DatabaseMetadataService.OFFICIAL_DB_PROP, officialDatabase.toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, unofficialDatabase.toString());
    }

    @AfterEach
    void tearDown() {
        for (Map.Entry<String, String> entry : previousProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    @DisplayName("a truncated unofficial database is moved aside at startup instead of preventing it")
    void corruptDatabaseIsQuarantined() throws IOException {
        Files.write(unofficialDatabase, "{\"abc\": {\"uuid\": \"ab".getBytes(StandardCharsets.UTF_8));

        DatabaseMetadataService service = new DatabaseMetadataService(false);

        List<String> corrupt = siblings().stream()
                .filter(name -> name.startsWith("unofficial.json.corrupt-")).collect(Collectors.toList());
        assertEquals(1, corrupt.size(), "the corrupt file must be kept aside: " + siblings());
        assertEquals("{}", read(unofficialDatabase));
        // And the service is usable
        service.refreshUnofficialMetadata(metadata("Title"));
        assertEquals("Title", service.getUnofficialMetadata(UUID_A).get().getTitle());
    }

    @Test
    @DisplayName("refreshing a pack with unchanged metadata does not rewrite the file")
    void unchangedMetadataIsNotRewritten() throws IOException {
        DatabaseMetadataService service = new DatabaseMetadataService(false);
        service.refreshUnofficialMetadata(metadata("Title"));
        FileTime past = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(unofficialDatabase, past);

        service.refreshUnofficialMetadata(metadata("Title"));

        assertEquals(past, Files.getLastModifiedTime(unofficialDatabase));
    }

    @Test
    @DisplayName("a change made to the file by another process is seen")
    void externalChangeIsReloaded() throws IOException {
        DatabaseMetadataService service = new DatabaseMetadataService(false);
        service.refreshUnofficialMetadata(metadata("Title"));
        assertEquals("Title", service.getUnofficialMetadata(UUID_A).get().getTitle());

        Files.write(unofficialDatabase, ("{\"" + UUID_A + "\": {\"uuid\": \"" + UUID_A
                + "\", \"title\": \"Edited elsewhere\"}}").getBytes(StandardCharsets.UTF_8));

        assertEquals("Edited elsewhere", service.getUnofficialMetadata(UUID_A).get().getTitle());
    }

    @Test
    @DisplayName("writes leave no temporary file behind")
    void writesLeaveNoTemporaryFile() throws IOException {
        DatabaseMetadataService service = new DatabaseMetadataService(false);
        service.refreshUnofficialMetadata(metadata("One"));
        service.refreshUnofficialMetadata(metadata("Two"));

        assertEquals(2 + 0, siblings().size(), "only official.json and unofficial.json: " + siblings());
        assertTrue(read(unofficialDatabase).contains("Two"));
    }

    @Test
    @DisplayName("the agent does not move a corrupt file, it just sees nothing")
    void agentLeavesCorruptFileAlone() throws IOException {
        Files.write(unofficialDatabase, "not json at all {".getBytes(StandardCharsets.UTF_8));

        DatabaseMetadataService service = new DatabaseMetadataService(true);

        assertFalse(service.getUnofficialMetadata(UUID_A).isPresent());
        assertEquals("not json at all {", read(unofficialDatabase));
    }

    private List<String> siblings() throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toList());
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private static DatabasePackMetadata metadata(String title) {
        return new DatabasePackMetadata(UUID_A, title, "A description", null, false);
    }
}
