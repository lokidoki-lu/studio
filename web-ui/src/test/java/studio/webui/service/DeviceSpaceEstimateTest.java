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
import studio.driver.fs.PackTransferSizeEstimator;
import studio.metadata.DatabaseMetadataService;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The space a library pack takes on the device once converted, estimated without converting it. */
class DeviceSpaceEstimateTest {

    private static final long K32 = 32 * 1024;

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
        Files.createDirectories(root.resolve("tmp"));
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp") + File.separator);
        setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP, root.resolve("db").resolve("conversions.json").toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, root.resolve("db").resolve("unofficial.json").toString());
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

    @Test
    @DisplayName("an archive to FS: WAV re-encoded at the MP3 bound, a mono 44.1 kHz MP3 as is, images as RLE BMP, all rounded to the cluster")
    void archiveToFs() throws IOException {
        byte[] mono = monoMp3(20_000);
        writeArchive("pack.zip", wav(64_000, 64_000 * 60), mono);

        DeviceSpaceEstimate.Result result = DeviceSpaceEstimate.estimate(library.resolve("pack.zip"), "fs", K32);

        long expected = PackTransferSizeEstimator.allocatedBytesForFiles(Arrays.asList(
                DeviceSpaceEstimate.FS_IMAGE_BYTES,                  // cover.png
                60L * DeviceSpaceEstimate.MP3_BYTES_PER_SECOND,       // a minute of WAV, as MP3
                (long) mono.length,                                    // sent as it is
                512L + 44L * 2,                                        // ni: two stage nodes
                4L,                                                    // li: one option
                12L,                                                   // ri
                24L                                                    // si
        ), DeviceSpaceEstimate.FS_FOLDERS, K32);
        assertEquals(expected, result.bytes);
        assertEquals(DeviceSpaceEstimate.ESTIMATED, result.method);
        // Every file takes at least one cluster: 7 files, 5 folders, the boot file and the index
        assertTrue(result.bytes >= 14 * K32);
    }

    @Test
    @DisplayName("an archive to raw: WAV at 32 kHz (64 000 bytes a second), 24-bit BMP, all rounded to the sector")
    void archiveToRaw() throws IOException {
        writeArchive("pack.zip", wav(64_000, 64_000 * 60), monoMp3(16_000));

        DeviceSpaceEstimate.Result result = DeviceSpaceEstimate.estimate(library.resolve("pack.zip"), "raw", K32);

        long wav = 60L * 64_000 + 44;
        long mp3AsWav = (long) Math.ceil(16_000 * 8.0 / 128_000 * 64_000) + 44; // CBR 128 kbps: 1 s
        long expected = (1 + 2 + 1) * 512L
                + DeviceSpaceEstimate.roundUp(DeviceSpaceEstimate.RAW_IMAGE_BYTES, 512)
                + DeviceSpaceEstimate.roundUp(wav, 512)
                + DeviceSpaceEstimate.roundUp(mp3AsWav, 512);
        assertEquals(expected, result.bytes);
        // About 230 MB an hour of audio
        assertTrue(result.bytes > 60L * 64_000);
    }

    @Test
    @DisplayName("a pack already in the device's format is measured: FS folder file by file, raw file to the sector")
    void alreadyInFormat() throws IOException {
        Path fs = library.resolve("pack.fs");
        Files.createDirectories(fs.resolve("rf").resolve("000"));
        Files.write(fs.resolve("ni"), new byte[600]);
        Files.write(fs.resolve("rf").resolve("000").resolve("00000000"), new byte[40_000]);
        DeviceSpaceEstimate.Result folder = DeviceSpaceEstimate.estimate(fs, "fs", K32);
        assertEquals(PackTransferSizeEstimator.allocatedBytesForUpload(fs, K32), folder.bytes);
        // ni (1 cluster), the image (2), three folders, bt and the index temporary
        assertEquals((1 + 2 + 3 + 1 + 1) * K32, folder.bytes);
        assertEquals(DeviceSpaceEstimate.MEASURED, folder.method);

        Path raw = library.resolve("pack.pack");
        Files.write(raw, new byte[1000]);
        DeviceSpaceEstimate.Result file = DeviceSpaceEstimate.estimate(raw, "raw", K32);
        assertEquals(1024, file.bytes);
        assertEquals(DeviceSpaceEstimate.MEASURED, file.method);
    }

    @Test
    @DisplayName("B3: an FS pack to raw counts its enciphered MP3 at 32 kbps, so a low bit rate voice track is not under-counted")
    void fsToRawAssumesTheLowestMp3BitRate() throws IOException {
        Path fs = library.resolve("voice.fs");
        Files.createDirectories(fs.resolve("sf").resolve("000"));
        Files.createDirectories(fs.resolve("rf").resolve("000"));
        // One minute of a 32 kbps VBR voice track: 240 000 bytes, 3 840 000 bytes once in 32 kHz WAV
        long mp3Bytes = 60L * 32_000 / 8;
        Files.write(fs.resolve("sf").resolve("000").resolve("00000000"), new byte[(int) mp3Bytes]);
        Files.write(fs.resolve("ni"), new byte[512 + 44]);

        DeviceSpaceEstimate.Result result = DeviceSpaceEstimate.estimate(fs, "raw", K32);

        long wav = 60L * DeviceSpaceEstimate.WAV_BYTES_PER_SECOND;
        assertEquals(16, DeviceSpaceEstimate.MP3_TO_WAV_FACTOR);
        assertTrue(result.bytes >= wav, result.bytes + " < " + wav);
        assertEquals(DeviceSpaceEstimate.ROUGH, result.method);
    }

    @Test
    @DisplayName("the library answer: per pack, a total, the cluster from the device when it is plausible, and unknown names as errors")
    void libraryAnswer() throws IOException {
        writeArchive("a.zip", wav(64_000, 64_000), monoMp3(4_000));
        byte[] rawPack = new byte[2048];
        rawPack[1] = 1; // one stage node
        Files.write(library.resolve("b.pack"), rawPack);

        JsonObject withDevice = service.estimateOnDevice(List.of("a.zip", "b.pack", "missing.zip", "../escape.zip"), "fs", OptionalLong.of(4096));
        assertEquals(4096, withDevice.getLong("clusterSize"));
        assertEquals("device", withDevice.getString("clusterSizeSource"));
        JsonArray packs = withDevice.getJsonArray("packs");
        assertEquals(2, packs.size());
        assertEquals(packs.getJsonObject(0).getLong("bytes") + packs.getJsonObject(1).getLong("bytes"), withDevice.getLong("total"));
        assertEquals(DeviceSpaceEstimate.ROUGH, withDevice.getString("method")); // raw to FS is rough
        assertEquals(2, withDevice.getJsonArray("errors").size());

        // A sector-sized block (FAT32 on Windows) is not a cluster: 32 KiB is assumed instead
        JsonObject withSector = service.estimateOnDevice(List.of("a.zip"), "fs", OptionalLong.of(512));
        assertEquals(K32, withSector.getLong("clusterSize"));
        assertEquals("default", withSector.getString("clusterSizeSource"));
        assertTrue(withSector.getLong("total") > service.estimateOnDevice(List.of("a.zip"), "fs", OptionalLong.of(4096)).getLong("total"));
    }

    // ------------------------------------------------------------------------------- helpers

    /** Two stage nodes: the cover (image, audio a) and a story (audio b), one action with one option. */
    private void writeArchive(String name, byte[] audioA, byte[] audioB) throws IOException {
        JsonObject story = new JsonObject()
                .put("stageNodes", new JsonArray()
                        .add(new JsonObject().put("uuid", "1").put("image", "cover.png").put("audio", "a.wav"))
                        .add(new JsonObject().put("uuid", "2").putNull("image").put("audio", "b.mp3")))
                .put("actionNodes", new JsonArray()
                        .add(new JsonObject().put("id", "x").put("options", new JsonArray().add("2"))));
        try (OutputStream out = Files.newOutputStream(library.resolve(name));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, "story.json", story.encode().getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/cover.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            entry(zip, "assets/a.wav", audioA);
            entry(zip, "assets/b.mp3", audioB);
            entry(zip, "assets/unused.mp3", new byte[1_000_000]); // not referenced: not counted
        }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** An MPEG 1 Layer III stream at 128 kbps, 44.1 kHz, mono, without Xing header. */
    private static byte[] monoMp3(int bytes) {
        byte[] audio = new byte[bytes];
        audio[0] = (byte) 0xFF;
        audio[1] = (byte) 0xFB;
        audio[2] = (byte) 0x90;
        audio[3] = (byte) 0xC0;
        return audio;
    }

    private static byte[] wav(int byteRate, int dataBytes) {
        ByteBuffer bb = ByteBuffer.allocate(12 + 8 + 16 + 8 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(bb.capacity() - 8).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(byteRate / 2).putInt(byteRate)
                .putShort((short) 2).putShort((short) 16);
        bb.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        return bb.array();
    }

    private void setProperty(String key, String value) {
        previousProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }
}
