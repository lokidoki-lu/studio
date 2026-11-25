/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.metadata.DatabaseMetadataService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library preview reads a few headers of a pack, never the whole of it: size, node and story
 * counts of the raw and FS formats, audio duration of an archive.
 */
class PackDetailsTest {

    @TempDir
    Path root;

    private Path library;
    private LibraryService service;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = root.resolve("library");
        Files.createDirectories(library);
        Files.createDirectories(root.resolve("db"));
        Path work = Files.createTempDirectory("studio-details-work");
        work.toFile().deleteOnExit();
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, work + File.separator);
        setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP,
                root.resolve("db").resolve("conversions.json").toString());
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

    // --------------------------------------------------------------------------------- audio

    @Test
    @DisplayName("a constant bitrate MP3 is estimated from its bitrate, past its ID3 tag")
    void cbrMp3IsEstimated() throws IOException {
        // 128 kbps: 16 000 bytes per second, 2 seconds of frames after a 100-byte ID3 tag
        byte[] mp3 = mp3(100, 32_000, -1);
        PackDetails.Duration duration = PackDetails.mp3Duration(new ByteArrayInputStream(mp3), mp3.length);
        assertNotNull(duration);
        assertTrue(duration.estimated);
        assertEquals(2.0, duration.seconds, 0.01);
    }

    @Test
    @DisplayName("an MP3 with a Xing header gets its exact duration from the frame count")
    void xingMp3IsExact() throws IOException {
        // 383 frames of 1152 samples at 44.1 kHz: 10.004 s
        byte[] mp3 = mp3(0, 5_000, 383);
        PackDetails.Duration duration = PackDetails.mp3Duration(new ByteArrayInputStream(mp3), mp3.length);
        assertNotNull(duration);
        assertFalse(duration.estimated);
        assertEquals(383 * 1152 / 44100.0, duration.seconds, 0.001);
    }

    @Test
    @DisplayName("data that holds no MP3 frame has no duration")
    void notAnMp3() throws IOException {
        byte[] noise = "not an mp3 at all".getBytes(StandardCharsets.US_ASCII);
        assertNull(PackDetails.mp3Duration(new ByteArrayInputStream(noise), noise.length));
    }

    @Test
    @DisplayName("a WAV gets its duration from its byte rate and data chunk, past other chunks")
    void wavIsExact() throws IOException {
        PackDetails.Duration duration = PackDetails.wavDuration(new ByteArrayInputStream(wav(64_000, 64_000 * 3)), -1);
        assertNotNull(duration);
        assertFalse(duration.estimated);
        assertEquals(3.0, duration.seconds, 0.001);
    }

    @Test
    @DisplayName("S3: a WAV declaring a placeholder data size (0xFFFFFFFF) is capped at the entry size")
    void wavPlaceholderDataSizeIsCapped() throws IOException {
        byte[] wav = wav(64_000, 64_000 * 2);
        // The data size is the last int of the header
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(wav.length - 64_000 * 2 - 4, 0xFFFFFFFF);
        PackDetails.Duration duration = PackDetails.wavDuration(new ByteArrayInputStream(wav), wav.length);
        assertNotNull(duration);
        assertEquals(2.0, duration.seconds, 0.001);
    }

    @Test
    @DisplayName("R5: an asset present twice in the archive is measured once, as the readers keep the last")
    void duplicateEntriesAreCountedOnce() throws IOException {
        Path pack = library.resolve("dup.zip");
        try (org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zip =
                     new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(pack.toFile())) {
            for (byte[] bytes : new byte[][]{"{}".getBytes(StandardCharsets.UTF_8), wav(64_000, 64_000), wav(64_000, 64_000 * 3)}) {
                String name = bytes.length == 2 ? "story.json" : "assets/a.wav";
                zip.putArchiveEntry(new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(name));
                zip.write(bytes);
                zip.closeArchiveEntry();
            }
        }

        JsonObject audio = service.packDetails("dup.zip").orElseThrow().getJsonObject("audio");
        assertEquals(1, audio.getInteger("measured"));
        assertEquals(3.0, audio.getDouble("seconds"), 0.05);
    }

    @Test
    @DisplayName("an Ogg Vorbis gets its duration from the granule position of its last page")
    void oggIsExact() {
        byte[] head = oggHead(44_100);
        byte[] tail = oggPage(44_100L * 7);
        PackDetails.Duration duration = PackDetails.oggDuration(head, tail);
        assertNotNull(duration);
        assertEquals(7.0, duration.seconds, 0.001);
    }

    @Test
    @DisplayName("an archive's audio is summed; a deflated Ogg is counted as unmeasured")
    void archiveAudioIsSummed() throws IOException {
        Path pack = library.resolve("pack.zip");
        try (OutputStream out = Files.newOutputStream(pack); ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, "story.json", "{}".getBytes(StandardCharsets.UTF_8), false);
            entry(zip, "assets/a.mp3", mp3(0, 16_000, -1), true);        // 1 s, estimated
            entry(zip, "assets/b.wav", wav(64_000, 64_000), false);      // 1 s
            ByteArrayOutputStream ogg = new ByteArrayOutputStream();
            ogg.write(oggHead(44_100));
            ogg.write(new byte[1000]);
            ogg.write(oggPage(44_100L * 2));
            entry(zip, "assets/c.ogg", ogg.toByteArray(), true);         // 2 s, stored
            entry(zip, "assets/d.ogg", ogg.toByteArray(), false);        // deflated: unmeasured
            entry(zip, "assets/e.png", new byte[10], false);             // not audio
        }

        JsonObject details = service.packDetails("pack.zip").orElseThrow();
        assertEquals("archive", details.getString("format"));
        assertEquals(Files.size(pack), details.getLong("size"));
        JsonObject audio = details.getJsonObject("audio");
        assertEquals(4.0, audio.getDouble("seconds"), 0.05);
        assertEquals(3, audio.getInteger("measured"));
        assertEquals(1, audio.getInteger("estimated"));
        assertEquals(1, audio.getInteger("unmeasured"));
    }

    // ---------------------------------------------------------------------------- raw and FS

    @Test
    @DisplayName("a raw pack's stage nodes and stories are counted from its node sectors")
    void rawStats() throws IOException {
        int stages = 4;
        ByteBuffer pack = ByteBuffer.allocate(512 * (stages + 3)).order(ByteOrder.BIG_ENDIAN);
        pack.putShort(0, (short) stages);
        // Nodes 1 and 3 enable pause
        pack.putShort(512 * 2 + 50, (short) 1);
        pack.putShort(512 * 4 + 50, (short) 1);
        Files.write(library.resolve("pack.pack"), pack.array());

        JsonObject details = service.packDetails("pack.pack").orElseThrow();
        assertEquals("raw", details.getString("format"));
        assertEquals(4, details.getInteger("stageNodes"));
        assertEquals(2, details.getInteger("stories"));
        assertFalse(details.containsKey("audio"));
    }

    @Test
    @DisplayName("an FS pack's stage nodes and stories are counted from its ni file, its size summed")
    void fsStats() throws IOException {
        Path pack = Files.createDirectories(library.resolve("ABCDEF01"));
        int stages = 3;
        ByteBuffer ni = ByteBuffer.allocate(512 + 44 * stages).order(ByteOrder.LITTLE_ENDIAN);
        ni.putShort(0, (short) 1);
        ni.putInt(4, 512);
        ni.putInt(8, 44);
        ni.putInt(12, stages);
        ni.putShort(512 + 44 * 2 + 38, (short) 1);
        Files.write(pack.resolve("ni"), ni.array());
        Files.createDirectories(pack.resolve("sf").resolve("000"));
        Files.write(pack.resolve("sf").resolve("000").resolve("00000000"), new byte[1000]);

        JsonObject details = service.packDetails("ABCDEF01").orElseThrow();
        assertEquals("fs", details.getString("format"));
        assertEquals(3, details.getInteger("stageNodes"));
        assertEquals(1, details.getInteger("stories"));
        assertEquals(ni.capacity() + 1000L, details.getLong("size"));
    }

    @Test
    @DisplayName("a broken pack still gets its size; a missing or outside pack gets nothing")
    void brokenOrMissing() throws IOException {
        Files.write(library.resolve("broken.pack"), new byte[]{0});
        JsonObject details = service.packDetails("broken.pack").orElseThrow();
        assertEquals(1L, details.getLong("size"));
        assertFalse(details.containsKey("stageNodes"));

        Files.write(root.resolve("outside.pack"), new byte[600]);
        assertFalse(service.packDetails("../outside.pack").isPresent());
        assertFalse(service.packDetails("missing.zip").isPresent());
        assertFalse(service.packDetails(null).isPresent());
    }

    // ------------------------------------------------------------------------------- helpers

    /** An MPEG 1 Layer III stream at 128 kbps, 44.1 kHz stereo; with a Xing frame count unless -1. */
    private static byte[] mp3(int id3Size, int audioBytes, int xingFrames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (id3Size > 0) {
            int tag = id3Size - 10;
            out.writeBytes(new byte[]{'I', 'D', '3', 3, 0, 0,
                    (byte) ((tag >> 21) & 0x7F), (byte) ((tag >> 14) & 0x7F), (byte) ((tag >> 7) & 0x7F), (byte) (tag & 0x7F)});
            out.writeBytes(new byte[tag]);
        }
        byte[] audio = new byte[audioBytes];
        audio[0] = (byte) 0xFF;
        audio[1] = (byte) 0xFB;
        audio[2] = (byte) 0x90;
        audio[3] = 0x00;
        if (xingFrames >= 0) {
            int xing = 4 + 32;
            System.arraycopy("Xing".getBytes(StandardCharsets.US_ASCII), 0, audio, xing, 4);
            audio[xing + 7] = 0x01;
            ByteBuffer.wrap(audio, xing + 8, 4).order(ByteOrder.BIG_ENDIAN).putInt(xingFrames);
        }
        out.writeBytes(audio);
        return out.toByteArray();
    }

    private static byte[] wav(int byteRate, int dataBytes) {
        ByteBuffer bb = ByteBuffer.allocate(12 + 8 + 4 + 8 + 16 + 8 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(bb.capacity() - 8).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("LIST".getBytes(StandardCharsets.US_ASCII)).putInt(4).put("INFO".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(byteRate / 2).putInt(byteRate)
                .putShort((short) 2).putShort((short) 16);
        bb.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        return bb.array();
    }

    private static byte[] oggHead(int sampleRate) {
        ByteBuffer bb = ByteBuffer.allocate(27 + 1 + 30).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("OggS".getBytes(StandardCharsets.US_ASCII));
        bb.position(26);
        bb.put((byte) 1).put((byte) 30);
        bb.put((byte) 1).put("vorbis".getBytes(StandardCharsets.US_ASCII)).putInt(0).put((byte) 1).putInt(sampleRate);
        return bb.array();
    }

    private static byte[] oggPage(long granule) {
        ByteBuffer bb = ByteBuffer.allocate(27 + 10).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) 4).putLong(granule);
        return bb.array();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes, boolean stored) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private void setProperty(String key, String value) {
        previousProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }
}
