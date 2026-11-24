/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.reader.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.StoryPack;
import studio.core.v1.utils.BytesUtils;
import studio.core.v1.utils.XXTEACipher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the FS reader refuses, and what it no longer does to the folder it reads.
 *
 * <p>The packs are written by hand, byte by byte, so that each case can break exactly one thing: a
 * single stage node with image 0 and sound 0, and an ok transition to an action node at li index 0
 * offering one option.
 */
@DisplayName("An FS pack reader")
class FsPackReaderValidationTest {

    private static final byte[] IMAGE = "not-really-a-bmp".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SOUND = "not-really-an-mp3".getBytes(StandardCharsets.US_ASCII);

    /** Writes the pack described above, with the given ri entry and li option. */
    private static Path writePack(Path parent, String riEntry, int liOption, int imageIndex) throws IOException {
        Path pack = Files.createDirectories(parent.resolve("12345678-1234-1234-1234-123456789abc"));
        ByteBuffer ni = ByteBuffer.allocate(512 + 44).order(ByteOrder.LITTLE_ENDIAN);
        ni.putShort((short) 1).putShort((short) 1).putInt(512).putInt(44).putInt(1).putInt(1).putInt(1).put((byte) 1);
        ni.position(512);
        ni.putInt(imageIndex).putInt(0)          // image, sound
                .putInt(0).putInt(1).putInt(0)   // ok: li 0, 1 option, option 0
                .putInt(-1).putInt(-1).putInt(-1) // no home transition
                .putShort((short) 1).putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        Files.write(pack.resolve("ni"), ni.array());
        Files.write(pack.resolve("li"), ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(liOption).array());
        Files.write(pack.resolve("ri"), riEntry.getBytes(StandardCharsets.US_ASCII));
        Files.write(pack.resolve("si"), "000\\00000000".getBytes(StandardCharsets.US_ASCII));
        Files.createDirectories(pack.resolve("rf").resolve("000"));
        Files.createDirectories(pack.resolve("sf").resolve("000"));
        Files.write(pack.resolve("rf").resolve("000").resolve("00000000"), IMAGE);
        Files.write(pack.resolve("sf").resolve("000").resolve("00000000"), SOUND);
        return pack;
    }

    private static Path writePack(Path parent) throws IOException {
        return writePack(parent, "000\\00000000", 0, 0);
    }

    private static List<String> listing(Path folder) throws IOException {
        try (Stream<Path> paths = Files.walk(folder)) {
            return paths.map(p -> folder.relativize(p).toString()).sorted().collect(Collectors.toList());
        }
    }

    @Test
    @DisplayName("reads a cleartext pack lacking its .cleartext marker without writing into the folder")
    void readDoesNotRepairTheFolder(@TempDir Path root) throws IOException {
        Path pack = writePack(root);
        List<String> before = listing(pack);

        StoryPack read = new FsStoryPackReader().read(pack);

        assertArrayEquals(IMAGE, read.getStageNodes().get(0).getImage().getRawData(),
                "detected as cleartext: the image is not deciphered");
        assertArrayEquals(SOUND, read.getStageNodes().get(0).getAudio().getRawData());
        assertFalse(Files.exists(pack.resolve(FsStoryPackReader.CLEARTEXT_FILENAME)), "no marker created by a read");
        assertEquals(before, listing(pack), "the folder is left exactly as it was");
    }

    @Test
    @DisplayName("still repairs the missing marker when asked to, explicitly")
    void explicitRepairStillWorks(@TempDir Path root) throws IOException {
        Path pack = writePack(root);

        assertFalse(new FsStoryPackReader().isCleartext(pack, false));
        assertFalse(Files.exists(pack.resolve(FsStoryPackReader.CLEARTEXT_FILENAME)));
        assertTrue(new FsStoryPackReader().isCleartext(pack, true));
        assertTrue(Files.exists(pack.resolve(FsStoryPackReader.CLEARTEXT_FILENAME)));
    }

    @Test
    @DisplayName("the explicit repair does not fail on an empty or short ri (a pack without images)")
    void explicitRepairOnEmptyRi(@TempDir Path root) throws IOException {
        for (String ri : new String[]{"", "00"}) {
            Path pack = writePack(root.resolve("ri-" + ri.length()), ri, 0, -1);

            assertFalse(new FsStoryPackReader().isCleartext(pack, true), "ri of " + ri.length() + " bytes");
            assertFalse(Files.exists(pack.resolve(FsStoryPackReader.CLEARTEXT_FILENAME)),
                    "no marker created on an ri that does not look cleartext");
        }
    }

    @Test
    @DisplayName("refuses an ri entry climbing out of its folder")
    void refusesDotDotInRi(@TempDir Path root) throws IOException {
        Path pack = writePack(root, "..\\000000000", 0, 0);
        Files.createFile(pack.resolve(FsStoryPackReader.CLEARTEXT_FILENAME));

        IOException thrown = assertThrows(IOException.class, () -> new FsStoryPackReader().read(pack));
        assertTrue(thrown.getMessage().contains("unexpected asset path"), thrown.getMessage());
    }

    @Test
    @DisplayName("refuses an ri entry with unexpected characters")
    void refusesOddRiEntry(@TempDir Path root) throws IOException {
        Path pack = writePack(root, "000\\0000:000", 0, 0);
        Files.createFile(pack.resolve(FsStoryPackReader.CLEARTEXT_FILENAME));

        assertThrows(IOException.class, () -> new FsStoryPackReader().read(pack));
    }

    @Test
    @DisplayName("refuses an image index beyond the ri file")
    void refusesImageIndexBeyondRi(@TempDir Path root) throws IOException {
        Path pack = writePack(root, "000\\00000000", 0, 3);

        assertThrows(IOException.class, () -> new FsStoryPackReader().read(pack));
    }

    @Test
    @DisplayName("refuses an action node option that is not a stage node")
    void refusesUnknownOption(@TempDir Path root) throws IOException {
        Path pack = writePack(root, "000\\00000000", 5, 0);

        IOException thrown = assertThrows(IOException.class, () -> new FsStoryPackReader().read(pack));
        assertTrue(thrown.getMessage().contains("unknown stage node"), thrown.getMessage());
    }

    @Test
    @DisplayName("keeps the unaligned tail of a short ciphered file instead of zeroing it")
    void decipherKeepsUnalignedTail() {
        // 10 bytes: two whole words ciphered, two trailing bytes left as they are (as the driver does).
        byte[] clear = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        int[] words = BytesUtils.toIntArray(Arrays.copyOf(clear, 8), ByteOrder.LITTLE_ENDIAN);
        int[] ciphered = XXTEACipher.btea(words, 2, BytesUtils.toIntArray(XXTEACipher.COMMON_KEY, ByteOrder.BIG_ENDIAN));
        byte[] stored = Arrays.copyOf(BytesUtils.toByteArray(ciphered, ByteOrder.LITTLE_ENDIAN), 10);
        stored[8] = 9;
        stored[9] = 10;

        assertArrayEquals(clear, FsStoryPackReader.decipherFirstBlockCommonKey(stored));
    }

    @Test
    @DisplayName("deciphers only the first 512 bytes of a longer file")
    void decipherLeavesTheRestAlone() {
        byte[] clear = new byte[600];
        for (int i = 0; i < clear.length; i++) {
            clear[i] = (byte) (i * 7);
        }
        int[] words = BytesUtils.toIntArray(Arrays.copyOf(clear, 512), ByteOrder.LITTLE_ENDIAN);
        int[] ciphered = XXTEACipher.btea(words, 128, BytesUtils.toIntArray(XXTEACipher.COMMON_KEY, ByteOrder.BIG_ENDIAN));
        byte[] stored = Arrays.copyOf(clear, 600);
        System.arraycopy(BytesUtils.toByteArray(ciphered, ByteOrder.LITTLE_ENDIAN), 0, stored, 0, 512);

        assertArrayEquals(clear, FsStoryPackReader.decipherFirstBlockCommonKey(stored));
    }
}
