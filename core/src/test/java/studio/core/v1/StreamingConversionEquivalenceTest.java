/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.legacy.LegacyArchiveStoryPackReader;
import studio.core.v1.legacy.LegacyArchiveStoryPackWriter;
import studio.core.v1.legacy.LegacyFsStoryPackReader;
import studio.core.v1.legacy.LegacyFsStoryPackWriter;
import studio.core.v1.legacy.LegacyPackAssetsCompression;
import studio.core.v1.model.StoryPack;
import studio.core.v1.utils.BytesUtils;
import studio.core.v1.utils.StreamingPackConversion;
import studio.core.v1.utils.XXTEACipher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming conversions produce exactly what the in-memory ones produced.
 *
 * <p>The reference is the code as it was before the streaming work, kept frozen in
 * {@code studio.core.v1.legacy}: each pack is converted by both, and the outputs are compared file
 * by file, byte for byte (FS packs) or entry by entry (archives, whose story.json holds random
 * identifiers on both sides and is compared once those are normalized).
 */
class StreamingConversionEquivalenceTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ archive -> FS

    @Test
    @DisplayName("archive to FS: same files, same bytes, on a pack using every kind of asset")
    void archiveToFsRichPack() throws Exception {
        assertArchiveToFsEquivalent(StreamingFixtures.richArchive(false), "rich");
    }

    @Test
    @DisplayName("archive to FS: night mode and stored (uncompressed) entries")
    void archiveToFsNightModeStored() throws Exception {
        StreamingFixtures.Archive archive = StreamingFixtures.richArchive(true);
        archive.stored = true;
        Path fs = assertArchiveToFsEquivalent(archive, "night");
        assertTrue(Files.exists(fs.resolve("nm")));
    }

    @Test
    @DisplayName("archive to FS: a pack without any asset gets the same blank sound")
    void archiveToFsMinimal() throws Exception {
        Path fs = assertArchiveToFsEquivalent(StreamingFixtures.minimalArchive(), "minimal");
        assertFalse(Files.exists(fs.resolve("rf")), "no image, no rf folder, as before");
    }

    @Test
    @DisplayName("archive to FS: an archive without central directory is read in full, as before")
    void archiveToFsWithoutCentralDirectory() throws Exception {
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        StreamingFixtures.richArchive(false).writeTo(zip);
        byte[] bytes = zip.toByteArray();
        // Bytes appended after the end record, more than a zip comment can hold: the central
        // directory can no longer be found, the streaming reader still reads every entry.
        byte[] truncated = Arrays.copyOf(bytes, bytes.length + 70_000);
        Path archive = tmp.resolve("truncated.zip");
        Files.write(archive, truncated);
        try (studio.core.v1.reader.archive.ArchiveStoryPackReader.OpenedArchive opened = new studio.core.v1.reader.archive.ArchiveStoryPackReader().open(archive)) {
            assertTrue(opened.isInMemory(), "the fallback is the case under test");
        }

        Path legacy = legacyArchiveToFs(archive, Files.createDirectories(tmp.resolve("truncated-legacy")));
        Path streaming = StreamingPackConversion.archiveToFs(archive, Files.createDirectories(tmp.resolve("truncated-streaming"))).getProduced();
        assertSameTree(legacy, streaming);
    }

    @Test
    @DisplayName("archive to FS: a reference to a missing asset is refused as before")
    void archiveToFsMissingAsset() throws Exception {
        StreamingFixtures.Archive archive = StreamingFixtures.richArchive(false);
        archive.entries.remove("assets/story2.mp3");
        Path zip = tmp.resolve("missing.zip");
        archive.writeTo(zip);

        IOException legacy = assertThrows(IOException.class, () -> legacyArchiveToFs(zip, Files.createDirectories(tmp.resolve("m1"))));
        IOException streaming = assertThrows(IOException.class, () -> StreamingPackConversion.archiveToFs(zip, Files.createDirectories(tmp.resolve("m2"))));
        assertEquals(legacy.getMessage(), streaming.getMessage());
    }

    @Test
    @DisplayName("archive to FS: an image the FS format cannot hold is refused as before")
    void archiveToFsWrongImageSize() throws Exception {
        StreamingFixtures.Archive archive = StreamingFixtures.richArchive(false);
        // An already RLE-encoded BMP is kept as is, so a wrong size reaches the writer's check
        java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(32, 24, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(small, "png", png);
        archive.put("assets/q3.bmp", studio.core.v1.utils.ImageConversion.anyToRLECompressedBitmap(png.toByteArray()));
        Path zip = tmp.resolve("small.zip");
        archive.writeTo(zip);

        Exception legacy = assertThrows(IllegalArgumentException.class, () -> legacyArchiveToFs(zip, Files.createDirectories(tmp.resolve("s1"))));
        Exception streaming = assertThrows(IllegalArgumentException.class, () -> StreamingPackConversion.archiveToFs(zip, Files.createDirectories(tmp.resolve("s2"))));
        assertEquals(legacy.getMessage(), streaming.getMessage());
    }

    private Path assertArchiveToFsEquivalent(StreamingFixtures.Archive archive, String name) throws Exception {
        Path zip = tmp.resolve(name + ".zip");
        archive.writeTo(zip);
        try (studio.core.v1.reader.archive.ArchiveStoryPackReader.OpenedArchive opened = new studio.core.v1.reader.archive.ArchiveStoryPackReader().open(zip)) {
            assertFalse(opened.isInMemory(), "read through the central directory, one asset at a time");
        }
        Path legacy = legacyArchiveToFs(zip, Files.createDirectories(tmp.resolve(name + "-legacy")));
        StreamingPackConversion.Result result = StreamingPackConversion.archiveToFs(zip, Files.createDirectories(tmp.resolve(name + "-streaming")));
        assertEquals(StreamingFixtures.COVER, result.getUuid());
        assertSameTree(legacy, result.getProduced());
        return result.getProduced();
    }

    static Path legacyArchiveToFs(Path zip, Path out) throws Exception {
        StoryPack pack;
        try (InputStream in = Files.newInputStream(zip)) {
            pack = new LegacyArchiveStoryPackReader().read(in);
        }
        pack = LegacyPackAssetsCompression.withPreparedAssetsFirmware2dot4(pack);
        return new LegacyFsStoryPackWriter().write(pack, out);
    }

    /** Same relative paths, same bytes, for every file under the two folders (and same folder name). */
    static void assertSameTree(Path expected, Path actual) throws IOException {
        assertEquals(expected.getFileName(), actual.getFileName(), "pack folder name");
        Map<String, byte[]> e = files(expected);
        Map<String, byte[]> a = files(actual);
        assertEquals(e.keySet(), a.keySet(), "files of the pack");
        assertTrue(e.size() >= 5, "a pack has at least ni, li, ri, si, .cleartext and a sound");
        for (String file : e.keySet()) {
            assertArrayEquals(e.get(file), a.get(file), "content of " + file);
        }
    }

    static Map<String, byte[]> files(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                files.put(root.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return files;
    }

    // ------------------------------------------------------------------ FS -> archive

    @Test
    @DisplayName("FS to archive: same entries and contents, from a cleartext pack")
    void fsToArchiveCleartext() throws Exception {
        Path fs = fsPack("clear", false);
        assertFsToArchiveEquivalent(fs);
    }

    @Test
    @DisplayName("FS to archive: same entries and contents, from a pack stored ciphered")
    void fsToArchiveCiphered() throws Exception {
        Path fs = fsPack("ciphered", true);
        assertFalse(Files.exists(fs.resolve(".cleartext")));
        assertFsToArchiveEquivalent(fs);
    }

    @Test
    @DisplayName("FS to archive: assets shorter than one cipher block")
    void fsToArchiveTinyAssets() throws Exception {
        Path fs = fsPack("tiny", true);
        // Odd sizes around the 512-byte block: shorter, unaligned, exactly one block, one more
        int[] sizes = {3, 101, 512, 513};
        List<Path> sounds;
        try (Stream<Path> walk = Files.walk(fs.resolve("sf"))) {
            sounds = walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        for (int i = 0; i < sounds.size() && i < sizes.length; i++) {
            byte[] content = new byte[sizes[i]];
            for (int b = 0; b < content.length; b++) {
                content[b] = (byte) (b * 7 + i);
            }
            Files.write(sounds.get(i), cipherFirstBlock(content));
        }
        assertFsToArchiveEquivalent(fs);
    }

    /** An FS pack converted from the rich archive by the legacy code, optionally ciphered as a v2 library pack. */
    private Path fsPack(String name, boolean ciphered) throws Exception {
        Path zip = tmp.resolve(name + "-source.zip");
        StreamingFixtures.richArchive(true).writeTo(zip);
        Path fs = legacyArchiveToFs(zip, Files.createDirectories(tmp.resolve(name + "-fs")));
        if (ciphered) {
            Files.delete(fs.resolve(".cleartext"));
            try (Stream<Path> walk = Files.walk(fs)) {
                for (Path p : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    String file = p.getFileName().toString();
                    if (!file.equals("ni") && !file.equals("nm")) {
                        Files.write(p, cipherFirstBlock(Files.readAllBytes(p)));
                    }
                }
            }
        }
        return fs;
    }

    static byte[] cipherFirstBlock(byte[] data) {
        int blockLength = Math.min(512, data.length);
        int[] words = BytesUtils.toIntArray(Arrays.copyOf(data, blockLength), ByteOrder.LITTLE_ENDIAN);
        int n = blockLength / 4;
        int[] ciphered = XXTEACipher.btea(words, n, BytesUtils.toIntArray(XXTEACipher.COMMON_KEY, ByteOrder.BIG_ENDIAN));
        byte[] block = BytesUtils.toByteArray(ciphered, ByteOrder.LITTLE_ENDIAN);
        byte[] out = Arrays.copyOf(data, data.length);
        System.arraycopy(block, 0, out, 0, block.length);
        return out;
    }

    private void assertFsToArchiveEquivalent(Path fs) throws Exception {
        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        StoryPack pack = new LegacyFsStoryPackReader().read(fs);
        new LegacyArchiveStoryPackWriter().write(pack, legacy);

        Path streamingZip = tmp.resolve(fs.getFileName() + "-streaming.zip");
        String uuid;
        try (OutputStream out = Files.newOutputStream(streamingZip)) {
            uuid = StreamingPackConversion.fsToArchive(fs, out);
        }
        assertEquals(pack.getUuid(), uuid);

        Map<String, byte[]> expected = zipEntries(new ByteArrayInputStream(legacy.toByteArray()));
        Map<String, byte[]> actual;
        try (InputStream in = Files.newInputStream(streamingZip)) {
            actual = zipEntries(in);
        }
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()), "entries, in order");
        for (String entry : expected.keySet()) {
            if (entry.equals("story.json")) {
                assertEquals(canonicalStory(expected.get(entry)), canonicalStory(actual.get(entry)), "story.json");
            } else {
                assertArrayEquals(expected.get(entry), actual.get(entry), "content of " + entry);
            }
        }
        assertTrue(expected.size() > 5, "the pack has assets");
    }

    /** Entries of a zip, in their order, with their uncompressed content. */
    static Map<String, byte[]> zipEntries(InputStream in) throws IOException {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    /**
     * story.json with its random identifiers replaced: stage nodes (random but the first, on both
     * sides, when read from an FS pack) by their position, action nodes (random, listed in hash
     * order) by the positions of their options; the action nodes are then sorted.
     */
    static String canonicalStory(byte[] json) {
        JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray stages = root.getAsJsonArray("stageNodes");
        Map<String, String> stageIds = new TreeMap<>();
        for (int i = 0; i < stages.size(); i++) {
            stageIds.put(stages.get(i).getAsJsonObject().get("uuid").getAsString(), "stage-" + i);
        }
        Map<String, String> actionIds = new TreeMap<>();
        List<String> actions = new ArrayList<>();
        for (JsonElement a : root.getAsJsonArray("actionNodes")) {
            JsonObject action = a.getAsJsonObject();
            List<String> options = new ArrayList<>();
            for (JsonElement o : action.getAsJsonArray("options")) {
                options.add(stageIds.get(o.getAsString()));
            }
            actionIds.put(action.get("id").getAsString(), "action" + options);
            actions.add("action" + options);
        }
        actions.sort(null);
        for (JsonElement s : stages) {
            JsonObject stage = s.getAsJsonObject();
            stage.addProperty("uuid", stageIds.get(stage.get("uuid").getAsString()));
            for (String t : new String[]{"okTransition", "homeTransition"}) {
                if (stage.get(t).isJsonObject()) {
                    JsonObject transition = stage.getAsJsonObject(t);
                    transition.addProperty("actionNode", actionIds.get(transition.get("actionNode").getAsString()));
                }
            }
        }
        root.remove("actionNodes");
        return root + " actions=" + actions;
    }
}
