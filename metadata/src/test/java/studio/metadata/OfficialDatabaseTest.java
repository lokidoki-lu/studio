/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.metadata;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The official database: incomplete entries do not break a lookup, and the database is downloaded
 * in the background when missing or old, never by the constructor. Offline: the download is a stub.
 */
class OfficialDatabaseTest {

    private static final String COMPLETE = "00000000-0000-0000-0000-000000000001";
    private static final String NEW_PACK = "00000000-0000-0000-0000-000000000099";

    @TempDir
    Path root;

    private Path officialDatabase;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() {
        officialDatabase = root.resolve("official.json");
        setProperty(DatabaseMetadataService.OFFICIAL_DB_PROP, officialDatabase.toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, root.resolve("unofficial.json").toString());
        setProperty(DatabaseMetadataService.OFFICIAL_DB_REFRESH_PROP, "false");
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

    private void setProperty(String key, String value) {
        previousProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private void write(String json) throws IOException {
        Files.write(officialDatabase, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String entry(String uuid, String body) {
        return "\"k-" + uuid + "\": {\"uuid\": \"" + uuid + "\"" + body + "}";
    }

    /** A service whose downloads are answered from a queue (empty: the download fails). */
    private static final class StubbedService extends DatabaseMetadataService {
        final Deque<Optional<JsonObject>> answers = new ConcurrentLinkedDeque<>();
        volatile int downloads;

        StubbedService() {
            super(false);
        }

        @Override
        protected Optional<JsonObject> downloadOfficialDatabase() {
            downloads++;
            return answers.isEmpty() ? Optional.empty() : answers.poll();
        }
    }

    private static JsonObject database(String... uuids) {
        JsonObject json = new JsonObject();
        for (String uuid : uuids) {
            json.add("k-" + uuid, JsonParser.parseString("{\"uuid\": \"" + uuid + "\"}"));
        }
        return json;
    }

    // ------------------------------------------------- incomplete entries

    @Test
    @DisplayName("an incomplete official entry answers as an official pack, without throwing")
    void incompleteEntriesDoNotThrow() throws IOException {
        String noLocales = "00000000-0000-0000-0000-000000000002";
        String missingLocale = "00000000-0000-0000-0000-000000000003";
        String nullDescription = "00000000-0000-0000-0000-000000000004";
        String noInfosAtAll = "00000000-0000-0000-0000-000000000005";
        String noImage = "00000000-0000-0000-0000-000000000006";
        String withoutUuidField = "00000000-0000-0000-0000-000000000007";
        write("{\"response\": {"
                + entry(COMPLETE, ", \"locales_available\": {\"fr_FR\": {}}, \"localized_infos\": {\"fr_FR\": {\"title\": \"Titre\", \"description\": \"Desc\", \"image\": {\"image_url\": \"/img.png\"}}}") + ","
                + entry(noLocales, ", \"locales_available\": {}, \"localized_infos\": {\"en_US\": {\"title\": \"Title\"}}") + ","
                + entry(missingLocale, ", \"locales_available\": {\"de_DE\": {}}, \"localized_infos\": {}") + ","
                + entry(nullDescription, ", \"locales_available\": {\"fr_FR\": {}}, \"localized_infos\": {\"fr_FR\": {\"title\": \"T\", \"description\": null, \"image\": null}}") + ","
                + entry(noInfosAtAll, "") + ","
                + entry(noImage, ", \"localized_infos\": {\"fr_FR\": {\"title\": \"T\", \"image\": {}}}") + ","
                + "\"" + withoutUuidField + "\": {\"localized_infos\": 3},"
                + "\"not-an-object\": 42"
                + "}}");
        DatabaseMetadataService service = new DatabaseMetadataService(false);

        DatabasePackMetadata complete = service.getOfficialMetadata(COMPLETE).get();
        assertEquals("Titre", complete.getTitle());
        assertEquals("Desc", complete.getDescription());
        assertEquals(DatabaseMetadataService.THUMBNAILS_STORAGE_ROOT + "/img.png", complete.getThumbnail());
        assertTrue(complete.isOfficial());

        assertEquals("Title", service.getOfficialMetadata(noLocales).get().getTitle(), "first locale with infos");
        for (String uuid : new String[]{noLocales, missingLocale, nullDescription, noInfosAtAll, noImage, withoutUuidField}) {
            Optional<DatabasePackMetadata> metadata = assertDoesNotThrow(() -> service.getOfficialMetadata(uuid), uuid);
            assertTrue(metadata.isPresent(), uuid);
            assertTrue(metadata.get().isOfficial(), uuid + " is still official");
            assertDoesNotThrow(() -> service.getPackMetadata(uuid), uuid);
            assertTrue(service.isOfficialPack(uuid), uuid);
        }
        assertNull(service.getOfficialMetadata(nullDescription).get().getDescription());
        assertNull(service.getOfficialMetadata(noImage).get().getThumbnail());
        assertFalse(service.isOfficialPack("not-an-object"));
    }

    @Test
    @DisplayName("official UUIDs are matched ignoring case")
    void caseInsensitive() throws IOException {
        String upper = "ABCDEF00-0000-0000-0000-000000000001";
        write("{" + entry(upper, "") + "}");
        DatabaseMetadataService service = new DatabaseMetadataService(false);

        assertTrue(service.isOfficialPack(upper.toLowerCase()));
        assertTrue(service.isOfficialPack(upper));
        assertTrue(service.getOfficialMetadata(upper.toLowerCase()).isPresent());
    }

    // ------------------------------------------------- kept up to date

    @Test
    @DisplayName("the constructor never downloads, even without a database file (offline startup)")
    void constructorDoesNotDownload() {
        StubbedService service = new StubbedService();

        assertEquals(0, service.downloads);
        assertFalse(service.isOfficialDatabaseAvailable());
    }

    @Test
    @DisplayName("a missing database is downloaded, retried after a failure, and written to the file")
    void missingDatabaseIsDownloadedAndRetried() throws IOException {
        StubbedService service = new StubbedService();
        long now = System.currentTimeMillis();

        // Offline: the download fails, nothing changes, and it is not tried again at once
        assertTrue(service.refreshOfficialDatabaseIfStale(now));
        assertFalse(service.isOfficialDatabaseAvailable());
        assertFalse(service.refreshOfficialDatabaseIfStale(now + 1000));

        // Later, online
        service.answers.add(Optional.of(database(COMPLETE)));
        assertTrue(service.refreshOfficialDatabaseIfStale(now + DatabaseMetadataService.OFFICIAL_DB_RETRY_MS));
        assertTrue(service.isOfficialPack(COMPLETE));
        assertTrue(service.isOfficialDatabaseAvailable());
        assertTrue(new String(Files.readAllBytes(officialDatabase), StandardCharsets.UTF_8).contains(COMPLETE));
        assertEquals(2, service.downloads);

        // Fresh now: not downloaded again
        assertFalse(service.refreshOfficialDatabaseIfStale(now + 2 * DatabaseMetadataService.OFFICIAL_DB_RETRY_MS));
    }

    @Test
    @DisplayName("an old database is downloaded again, and the packs it no longer lists stay official")
    void oldDatabaseIsRefreshedAndMerged() throws IOException {
        write("{" + entry(COMPLETE, "") + "}");
        long twoDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2);
        Files.setLastModifiedTime(officialDatabase, FileTime.fromMillis(twoDaysAgo));
        StubbedService service = new StubbedService();
        assertTrue(service.isOfficialPack(COMPLETE));
        assertFalse(service.isOfficialPack(NEW_PACK), "a pack released after the download");

        service.answers.add(Optional.of(database(NEW_PACK)));
        assertTrue(service.refreshOfficialDatabaseIfStale(System.currentTimeMillis()));

        assertTrue(service.isOfficialPack(NEW_PACK), "known once the database is refreshed");
        assertTrue(service.isOfficialPack(COMPLETE), "a pack withdrawn from the store is still official");
        String file = new String(Files.readAllBytes(officialDatabase), StandardCharsets.UTF_8);
        assertTrue(file.contains(COMPLETE) && file.contains(NEW_PACK), file);

        // A new service reads both from the file
        assertTrue(new DatabaseMetadataService(false).isOfficialPack(COMPLETE));
        assertTrue(new DatabaseMetadataService(false).isOfficialPack(NEW_PACK));
    }

    @Test
    @DisplayName("a recent database is not downloaded again")
    void recentDatabaseIsKept() throws IOException {
        write("{" + entry(COMPLETE, "") + "}");
        StubbedService service = new StubbedService();

        assertFalse(service.refreshOfficialDatabaseIfStale(System.currentTimeMillis()));
        assertEquals(0, service.downloads);
    }

    @Test
    @DisplayName("the background refresh is not started when disabled, nor for the agent")
    void backgroundRefreshCanBeDisabled() throws Exception {
        StubbedService service = new StubbedService();
        service.startOfficialDatabaseRefresh();
        Thread.sleep(200);
        service.stopOfficialDatabaseRefresh();
        assertEquals(0, service.downloads);
    }

    @Test
    @DisplayName("the background refresh downloads a missing database without blocking the caller")
    void backgroundRefreshDownloads() throws Exception {
        System.clearProperty(DatabaseMetadataService.OFFICIAL_DB_REFRESH_PROP);
        StubbedService service = new StubbedService();
        service.answers.add(Optional.of(database(COMPLETE)));
        service.startOfficialDatabaseRefresh();
        try {
            long deadline = System.currentTimeMillis() + 10_000;
            while (!service.isOfficialPack(COMPLETE) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(service.isOfficialPack(COMPLETE));
        } finally {
            service.stopOfficialDatabaseRefresh();
        }
    }
}
