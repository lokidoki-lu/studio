/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.enriched.EnrichedPackMetadata;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the archive reader refuses, and the encoding it reads and writes story.json in. */
@DisplayName("An archive pack reader")
class ArchivePackReaderValidationTest {

    private static final String TITLE = "Les aventures de Zo\u00e9 \u00e0 l'\u00e9cole \u2014 \u00e9t\u00e9";

    /** A story.json with one stage node offering itself through one action node, parametrised. */
    private static String story(String actionRef, String option, int optionIndex, String image) {
        return "{\"format\":\"v1\",\"version\":1,\"title\":\"" + TITLE + "\","
                + "\"stageNodes\":[{\"uuid\":\"11111111-1111-1111-1111-111111111111\",\"squareOne\":true,"
                + "\"image\":" + (image == null ? "null" : "\"" + image + "\"") + ",\"audio\":null,"
                + "\"okTransition\":{\"actionNode\":\"" + actionRef + "\",\"optionIndex\":" + optionIndex + "},"
                + "\"homeTransition\":null,"
                + "\"controlSettings\":{\"wheel\":true,\"ok\":true,\"home\":true,\"pause\":false,\"autoplay\":false}}],"
                + "\"actionNodes\":[{\"id\":\"a1\",\"options\":[" + option + "]}]}";
    }

    private static String validStory() {
        return story("a1", "\"11111111-1111-1111-1111-111111111111\"", 0, null);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] zip(String storyJson, String assetName, byte[] asset) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("story.json", storyJson.getBytes(StandardCharsets.UTF_8));
        if (assetName != null) {
            entries.put("assets/" + assetName, asset);
        }
        return zip(entries);
    }

    private static StoryPack read(byte[] archive) throws IOException {
        return new ArchiveStoryPackReader().read(new ByteArrayInputStream(archive));
    }

    // ---------------------------------------------------------------- encoding

    @Test
    @DisplayName("writes story.json in UTF-8, whatever the platform charset")
    void writesUtf8() throws IOException {
        StoryPack pack = PackFixtures.twoStagePack();
        pack.setEnriched(new EnrichedPackMetadata(TITLE, null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ArchiveStoryPackWriter().write(pack, out);

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null && !entry.getName().equals("story.json")) {
                // skip
            }
            assertNotNull(entry, "story.json");
            String json = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains(TITLE), "the title is UTF-8 encoded: " + json);
        }
        assertEquals(TITLE, new ArchiveStoryPackReader().read(new ByteArrayInputStream(out.toByteArray()))
                .getEnriched().getTitle());
    }

    @Test
    @DisplayName("reads a UTF-8 story.json, as the editor writes it")
    void readsUtf8() throws IOException {
        StoryPack read = read(zip(validStory(), null, null));
        assertEquals(TITLE, read.getEnriched().getTitle());
        assertEquals(TITLE, new ArchiveStoryPackReader().readMetadata(
                new ByteArrayInputStream(zip(validStory(), null, null))).getTitle());
    }

    @Test
    @DisplayName("reads a windows-1252 story.json written by an older Java writer on Windows")
    void readsLegacyWindows1252() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        String legacyTitle = "Zo\u00e9 \u00e0 l'\u00e9cole";
        entries.put("story.json", validStory().replace(TITLE, legacyTitle).getBytes(Charset.forName("windows-1252")));

        assertEquals(legacyTitle, read(zip(entries)).getEnriched().getTitle());
    }

    // ---------------------------------------------------------------- references

    @Test
    @DisplayName("refuses a transition to an action node that does not exist")
    void refusesUnknownActionNode() throws IOException {
        byte[] archive = zip(story("missing", "\"11111111-1111-1111-1111-111111111111\"", 0, null), null, null);

        IOException thrown = assertThrows(IOException.class, () -> read(archive));
        assertTrue(thrown.getMessage().contains("unknown action node"), thrown.getMessage());
    }

    @Test
    @DisplayName("refuses an option that is not a stage node")
    void refusesUnknownOption() throws IOException {
        byte[] archive = zip(story("a1", "\"22222222-2222-2222-2222-222222222222\"", 0, null), null, null);

        IOException thrown = assertThrows(IOException.class, () -> read(archive));
        assertTrue(thrown.getMessage().contains("not a known stage node"), thrown.getMessage());
    }

    @Test
    @DisplayName("refuses an unconnected (null) option")
    void refusesNullOption() throws IOException {
        byte[] archive = zip(story("a1", "null", 0, null), null, null);

        assertThrows(IOException.class, () -> read(archive));
    }

    @Test
    @DisplayName("refuses an option index beyond the action node's options")
    void refusesOptionIndexOutOfBounds() throws IOException {
        byte[] archive = zip(story("a1", "\"11111111-1111-1111-1111-111111111111\"", 1, null), null, null);

        IOException thrown = assertThrows(IOException.class, () -> read(archive));
        assertTrue(thrown.getMessage().contains("option index"), thrown.getMessage());
    }

    @Test
    @DisplayName("accepts -1, the editor's random option")
    void acceptsRandomOption() throws IOException {
        StoryPack read = read(zip(story("a1", "\"11111111-1111-1111-1111-111111111111\"", -1, null), null, null));

        assertEquals(-1, read.getStageNodes().get(0).getOkTransition().getOptionIndex());
    }

    @Test
    @DisplayName("refuses an asset referenced by a stage node but absent from the archive")
    void refusesMissingAsset() throws IOException {
        byte[] archive = zip(story("a1", "\"11111111-1111-1111-1111-111111111111\"", 0, "abc.png"), null, null);

        IOException thrown = assertThrows(IOException.class, () -> read(archive));
        assertTrue(thrown.getMessage().contains("assets/abc.png"), thrown.getMessage());
    }

    @Test
    @DisplayName("still reads a node without audio: absence is not an error, only a dangling reference is")
    void nodeWithoutAudioIsFine() throws IOException {
        StoryPack read = read(zip(validStory(), null, null));
        assertNull(read.getStageNodes().get(0).getAudio());
    }

    @Test
    @DisplayName("ignores an unreferenced asset without extension")
    void ignoresUnreferencedAssetWithoutExtension() throws IOException {
        byte[] png = {1, 2, 3};
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("story.json", story("a1", "\"11111111-1111-1111-1111-111111111111\"", 0, "abc.png")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("assets/abc.png", png);
        entries.put("assets/README", new byte[]{9});

        StoryPack read = read(zip(entries));

        assertArrayEquals(png, read.getStageNodes().get(0).getImage().getRawData());
    }

    @Test
    @DisplayName("refuses an archive without story.json")
    void refusesMissingStoryJson() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("assets/abc.png", new byte[]{1});

        assertThrows(IOException.class, () -> read(zip(entries)));
    }

    // ---------------------------------------------------------------- limits

    @Test
    @DisplayName("refuses an entry larger than the per-entry limit")
    void refusesOversizedEntry() throws IOException {
        byte[] archive = zip(story("a1", "\"11111111-1111-1111-1111-111111111111\"", 0, "abc.png"),
                "abc.png", new byte[5000]);

        IOException thrown = assertThrows(IOException.class,
                () -> new ArchiveStoryPackReader(4096, 1 << 20).read(new ByteArrayInputStream(archive)));
        assertTrue(thrown.getMessage().contains("too large"), thrown.getMessage());
    }

    @Test
    @DisplayName("refuses an archive whose entries add up beyond the cumulated limit")
    void refusesOversizedTotal() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("story.json", validStory().getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 10; i++) {
            entries.put("assets/" + i + ".png", new byte[3000]);
        }
        byte[] archive = zip(entries);

        assertThrows(IOException.class,
                () -> new ArchiveStoryPackReader(4096, 20000).read(new ByteArrayInputStream(archive)));
    }

    @Test
    @DisplayName("the default per-entry limit is 256 MiB")
    void defaultLimit() {
        assertEquals(256L * 1024 * 1024, ArchiveStoryPackReader.DEFAULT_MAX_ENTRY_BYTES);
    }
}
