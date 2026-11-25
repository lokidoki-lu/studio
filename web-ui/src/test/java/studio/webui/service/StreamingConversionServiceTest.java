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
import studio.core.v1.model.AudioAsset;
import studio.core.v1.model.ControlSettings;
import studio.core.v1.model.ImageAsset;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.core.v1.utils.AudioConversion;
import studio.core.v1.utils.PackAssetsCompression;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;
import studio.core.v1.writer.fs.FsStoryPackWriter;
import studio.metadata.DatabaseMetadataService;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library's archive to FS and FS to archive conversions go through the streaming code and give
 * what the in-memory reader / transform / writer chain gives. (The byte-for-byte comparison with
 * the code as it was before is in core, StreamingConversionEquivalenceTest; this checks the wiring.)
 */
class StreamingConversionServiceTest {

    private static final String PACK_UUID = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path root;

    private Path library;
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        library = Files.createDirectories(root.resolve("library"));
        Path workFolder = Files.createDirectories(root.resolve("work"));
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

    private void setProperty(String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    @Test
    @DisplayName("archive to FS, then FS to archive: same output as the in-memory chain")
    void archiveToFsAndBack() throws Exception {
        writeArchive(library.resolve("pack.zip"));
        LibraryService service = new LibraryService(new DatabaseMetadataService(true));

        Optional<Path> fs = service.addConvertedFsPackFile("pack.zip", false);
        assertTrue(fs.isPresent());
        Path installed = library.resolve(fs.get());

        StoryPack inMemory;
        try (InputStream in = Files.newInputStream(library.resolve("pack.zip"))) {
            inMemory = new ArchiveStoryPackReader().read(in);
        }
        Path expected = new FsStoryPackWriter().write(PackAssetsCompression.withPreparedAssetsFirmware2dot4(inMemory),
                Files.createDirectories(root.resolve("expected")));
        Map<String, byte[]> expectedFiles = files(expected);
        Map<String, byte[]> actualFiles = files(installed);
        assertEquals(expectedFiles.keySet(), actualFiles.keySet());
        for (String file : expectedFiles.keySet()) {
            assertArrayEquals(expectedFiles.get(file), actualFiles.get(file), file);
        }

        Optional<Path> archive = service.addConvertedArchivePackFile(fs.get().toString());
        assertTrue(archive.isPresent());
        ByteArrayOutputStream expectedZip = new ByteArrayOutputStream();
        new ArchiveStoryPackWriter().write(new FsStoryPackReader().read(installed), expectedZip);
        Map<String, byte[]> expectedEntries = zipEntries(new ByteArrayInputStream(expectedZip.toByteArray()));
        Map<String, byte[]> actualEntries;
        try (InputStream in = Files.newInputStream(library.resolve(archive.get()))) {
            actualEntries = zipEntries(in);
        }
        assertEquals(new ArrayList<>(expectedEntries.keySet()), new ArrayList<>(actualEntries.keySet()));
        for (String entry : expectedEntries.keySet()) {
            if (!entry.equals("story.json")) { // random action node ids
                assertArrayEquals(expectedEntries.get(entry), actualEntries.get(entry), entry);
            }
        }
    }

    /** Two nodes sharing a PNG, each with an MP3 (one with an ID3v2 tag), and a node without assets. */
    private static void writeArchive(Path path) throws Exception {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        img.getGraphics().fillRect(40, 40, 100, 80);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        byte[] mp3 = AudioConversion.anyToMp3(wav(330));
        byte[] tag = new byte[10 + 20];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 3;
        tag[9] = 20;
        byte[] tagged = new byte[tag.length + mp3.length];
        System.arraycopy(tag, 0, tagged, 0, tag.length);
        System.arraycopy(mp3, 0, tagged, tag.length, mp3.length);
        byte[] other = AudioConversion.anyToMp3(wav(550));

        ControlSettings controls = new ControlSettings(true, true, true, false, false);
        List<StageNode> nodes = new ArrayList<>();
        nodes.add(new StageNode(PACK_UUID, new ImageAsset("image/png", png.toByteArray(), "a.png"),
                new AudioAsset("audio/mpeg", tagged, "a.mp3"), null, null, controls, null));
        nodes.add(new StageNode("22222222-2222-3333-4444-555555555555", new ImageAsset("image/png", png.toByteArray(), "b.png"),
                new AudioAsset("audio/mpeg", other, "b.mp3"), null, null, controls, null));
        nodes.add(new StageNode("33333333-2222-3333-4444-555555555555", null, null, null, null, controls, null));
        StoryPack pack = new StoryPack(PACK_UUID, false, (short) 2, nodes, null, true);
        try (OutputStream out = Files.newOutputStream(path)) {
            new ArchiveStoryPackWriter().write(pack, out);
        }
    }

    private static byte[] wav(double frequency) throws IOException {
        int samples = 44100 / 3;
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short v = (short) (Math.sin(2 * Math.PI * frequency * i / 44100) * 8000);
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), new AudioFormat(44100f, 16, 1, true, false), samples),
                AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }

    private static Map<String, byte[]> files(Path dir) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
                files.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return files;
    }

    private static Map<String, byte[]> zipEntries(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }
}
