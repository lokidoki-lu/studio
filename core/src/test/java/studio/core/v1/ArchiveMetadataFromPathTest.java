/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.metadata.StoryPackMetadata;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ArchiveStoryPackReader#readMetadata(Path)} reads through the zip central directory, so the
 * library listing does not stream every pack in full. It must say exactly what the streaming
 * reader says about the same file.
 */
@DisplayName("Archive metadata read from a path")
class ArchiveMetadataFromPathTest {

    private static final String UUID = "11111111-1111-1111-1111-111111111111";
    private static final String TITLE = "Zoé à l'école";

    @TempDir
    Path dir;

    private static String story(String title) {
        return "{\"format\":\"v1\",\"version\":3,\"title\":\"" + title + "\",\"description\":\"Une histoire\","
                + "\"nightModeAvailable\":true,"
                + "\"stageNodes\":[{\"uuid\":\"" + UUID + "\",\"squareOne\":true,\"image\":null,\"audio\":null,"
                + "\"okTransition\":null,\"homeTransition\":null,"
                + "\"controlSettings\":{\"wheel\":true,\"ok\":true,\"home\":true,\"pause\":false,\"autoplay\":false}}],"
                + "\"actionNodes\":[]}";
    }

    private Path zip(String name, Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        Path file = dir.resolve(name);
        Files.write(file, out.toByteArray());
        return file;
    }

    private static StoryPackMetadata streamed(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return new ArchiveStoryPackReader().readMetadata(in);
        }
    }

    private static void assertSameMetadata(StoryPackMetadata expected, StoryPackMetadata actual) {
        assertNotNull(actual);
        assertEquals(expected.getUuid(), actual.getUuid());
        assertEquals(expected.getVersion(), actual.getVersion());
        assertEquals(expected.getTitle(), actual.getTitle());
        assertEquals(expected.getDescription(), actual.getDescription());
        assertEquals(expected.isNightModeAvailable(), actual.isNightModeAvailable());
        assertEquals(expected.getFormat(), actual.getFormat());
        assertArrayEquals(expected.getThumbnail(), actual.getThumbnail());
    }

    @Test
    @DisplayName("gives the streaming reader's metadata, thumbnail included")
    void sameAsStreaming() throws IOException {
        byte[] thumbnail = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("assets/a.mp3", new byte[10_000]);
        entries.put("story.json", story(TITLE).getBytes(StandardCharsets.UTF_8));
        entries.put("thumbnail.png", thumbnail);
        Path file = zip("pack.zip", entries);

        StoryPackMetadata read = new ArchiveStoryPackReader().readMetadata(file);

        assertEquals(UUID, read.getUuid());
        assertEquals(TITLE, read.getTitle());
        assertEquals(3, read.getVersion());
        assertTrue(read.isNightModeAvailable());
        assertArrayEquals(thumbnail, read.getThumbnail());
        assertSameMetadata(streamed(file), read);
    }

    @Test
    @DisplayName("matches names ignoring case and Windows separators, like the streaming reader")
    void namesNormalized() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("assets\\a.mp3", new byte[]{1});
        entries.put("Story.JSON", story(TITLE).getBytes(StandardCharsets.UTF_8));
        entries.put("THUMBNAIL.png", new byte[]{4, 5});
        Path file = zip("pack.zip", entries);

        assertSameMetadata(streamed(file), new ArchiveStoryPackReader().readMetadata(file));
    }

    @Test
    @DisplayName("returns null without story.json")
    void noStoryJson() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("thumbnail.png", new byte[]{1});
        Path file = zip("pack.zip", entries);

        assertNull(new ArchiveStoryPackReader().readMetadata(file));
    }

    @Test
    @DisplayName("keeps the per-entry size limit")
    void entryLimit() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("story.json", story(TITLE).getBytes(StandardCharsets.UTF_8));
        entries.put("thumbnail.png", new byte[5000]);
        Path file = zip("pack.zip", entries);

        IOException thrown = assertThrows(IOException.class,
                () -> new ArchiveStoryPackReader(4096, 1 << 20).readMetadata(file));
        assertTrue(thrown.getMessage().contains("too large"), thrown.getMessage());
    }

    @Test
    @DisplayName("a file without its central directory (copy in progress) is read as the streaming reader reads it")
    void truncatedFallsBackToStreaming() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("story.json", story(TITLE).getBytes(StandardCharsets.UTF_8));
        entries.put("thumbnail.png", new byte[]{7, 8});
        entries.put("assets/a.mp3", new byte[50_000]);
        Path complete = zip("complete.zip", entries);
        byte[] bytes = Files.readAllBytes(complete);
        Path truncated = dir.resolve("truncated.zip");
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length / 2));

        StoryPackMetadata expected;
        try {
            expected = streamed(truncated);
        } catch (IOException e) {
            assertThrows(IOException.class, () -> new ArchiveStoryPackReader().readMetadata(truncated));
            return;
        }
        StoryPackMetadata actual = new ArchiveStoryPackReader().readMetadata(truncated);
        if (expected == null) {
            assertNull(actual);
        } else {
            assertSameMetadata(expected, actual);
        }
    }
}
