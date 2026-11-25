/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.core.v1.utils.BytesUtils;
import studio.core.v1.utils.XXTEACipher;
import studio.driver.model.fs.FsDeviceKeyV3;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

/*
On firmware V2:
    The first 512 bytes of most files are ciphered (XXTEA) with a common key. The bt file ciphers (XXTEA) the first
    64 bytes of ri file using a device-specific key.
On firmware V3:
    The first 512 bytes of most files are ciphered (AES/CBC) with a device-specific key. The bt file contains part of
    this device-specific key.
 */
public class CipherUtils {

    private static final int CIPHER_BLOCK_SIZE_BOOT_V2 = 64;
    private static final int CIPHER_BLOCK_SIZE_ASSETS_V2 = 512;
    private static final int CIPHER_BLOCK_SIZE_ASSETS_V3 = 512;

    static final String BOOT_FILENAME = "bt";

    private static final List<String> CLEAR_FILES = List.of(FsStoryPackReader.NODE_INDEX_FILENAME, FsStoryPackReader.NIGHT_MODE_FILENAME, FsStoryPackReader.CLEARTEXT_FILENAME);
    private static final List<String> NO_COPY_FILES = List.of(FsStoryPackReader.CLEARTEXT_FILENAME);

    static boolean shouldBeCopied(Path filePath) {
        return !NO_COPY_FILES.contains(filePath.getFileName().toString());
    }

    static boolean shouldBeCiphered(Path filePath) {
        return !CLEAR_FILES.contains(filePath.getFileName().toString());
    }

    static void addBootFileV2(Path packFolder, byte[] deviceUuid) throws IOException {
        addBootFileV2(packFolder, deviceUuid, new OpenOption[0]);
    }

    /**
     * Same, writing {@code bt} with the given options (none: create or replace, as
     * {@code FileOutputStream} did). The driver passes {@code CREATE_NEW, WRITE} for a pack folder it
     * created itself, where no {@code bt} may exist yet.
     */
    static void addBootFileV2(Path packFolder, byte[] deviceUuid, OpenOption... btOptions) throws IOException {
        // Compute specific key
        byte[] specificKey = computeSpecificKeyV2FromUUID(deviceUuid);
        // Read ciphered block of ri file
        // Both streams are closed by try-with-resources: a handle left open by an exception would keep the
        // file locked on Windows, and the pack folder could then neither be completed nor removed. NIO
        // streams, so that the handles also let a concurrent delete through on Windows.
        byte[] riCipheredBlock;
        try (InputStream riFis = Files.newInputStream(packFolder.resolve(FsStoryPackReader.IMAGE_INDEX_FILENAME))) {
            riCipheredBlock = riFis.readNBytes(CIPHER_BLOCK_SIZE_BOOT_V2);
        }
        // Add boot file: bt
        try (OutputStream btFos = Files.newOutputStream(packFolder.resolve(BOOT_FILENAME), btOptions)) {
            // The first **scrambled** 64 bytes of 'ri' file must be ciphered with the device-specific key into 'bt' file
            byte[] btCiphered = cipherFirstBlockSpecificKeyV2(riCipheredBlock, specificKey);
            btFos.write(btCiphered);
        }
    }

    static void addBootFileV3(Path packFolder, FsDeviceKeyV3 deviceKeyV3) throws IOException {
        addBootFileV3(packFolder, deviceKeyV3, new OpenOption[0]);
    }

    /** Same, writing {@code bt} with the given options (none: create or replace). */
    static void addBootFileV3(Path packFolder, FsDeviceKeyV3 deviceKeyV3, OpenOption... btOptions) throws IOException {
        // Add boot file: bt
        Files.write(packFolder.resolve(BOOT_FILENAME), deviceKeyV3.getBt(), btOptions);
    }

    /**
     * Bytes of a file the transforms below may change: the first block of every format (XXTEA for
     * firmware 2, AES for firmware 3) fits in it, and nothing after it is ever touched.
     */
    static final int TRANSFORMED_HEAD_SIZE = Math.max(CIPHER_BLOCK_SIZE_ASSETS_V2, CIPHER_BLOCK_SIZE_ASSETS_V3);

    /**
     * Writes to {@code target} what {@code transform} applied to the whole of {@code source} would
     * give, without reading the file into memory.
     *
     * <p>Every first-block transform of this class ({@code cipherFirstBlock...},
     * {@code decipherFirstBlock...}, and chains of them) changes at most the first
     * {@link #TRANSFORMED_HEAD_SIZE} bytes, and copies every following byte verbatim: applying it to
     * that head alone and appending the rest of the file gives the same bytes. The head is read and
     * transformed in memory; the rest is copied from channel to channel ({@code transferTo}), which
     * the system may do without bringing it into the heap at all.
     *
     * @param options how {@code target} is opened (must allow writing)
     */
    static void transformFirstBlock(Path source, Path target, UnaryOperator<byte[]> transform, OpenOption... options) throws IOException {
        OpenOption[] targetOptions = options.length == 0
                ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE}
                : options;
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ)) {
            long size = in.size();
            ByteBuffer head = ByteBuffer.allocate((int) Math.min(TRANSFORMED_HEAD_SIZE, size));
            while (head.hasRemaining()) {
                if (in.read(head) < 0) {
                    throw new IOException("File shrank while being read: " + source);
                }
            }
            // Transformed before the target is created: a transform that fails leaves no file
            // behind, as when the whole file was transformed before being written.
            ByteBuffer transformed = ByteBuffer.wrap(transform.apply(head.array()));
            try (FileChannel out = FileChannel.open(target, targetOptions)) {
                while (transformed.hasRemaining()) {
                    out.write(transformed);
                }
                long position = head.capacity();
                while (position < size) {
                    long copied = in.transferTo(position, size - position, out);
                    if (copied <= 0) {
                        throw new IOException("Could not copy " + source + " past byte " + position);
                    }
                    position += copied;
                }
            }
        }
    }

    static byte[] cipherFirstBlockCommonKey(byte[] data) {
        return xxteaFirstBlock(data, CIPHER_BLOCK_SIZE_ASSETS_V2, XXTEACipher.COMMON_KEY, true);
    }
    static byte[] decipherFirstBlockCommonKey(byte[] data) {
        return xxteaFirstBlock(data, CIPHER_BLOCK_SIZE_ASSETS_V2, XXTEACipher.COMMON_KEY, false);
    }

    /**
     * XXTEA over the first {@code min(blockSize, data.length) / 4} 32-bit little-endian words of
     * {@code data}; every other byte is copied verbatim and the length is preserved.
     *
     * <p>Only whole words are ever ciphered: {@code btea} is called with {@code n = len / 4}, so the
     * one to three bytes of a block shorter than {@code blockSize} that do not fill a word are not
     * part of the cipher on either side. They used to be <em>lost</em> rather than left alone:
     * {@code BytesUtils.toIntArray} drops them, the buffer was then allocated at the full length and
     * only the ciphered words were put back, so they came out as zero bytes. They are now copied as
     * they are, which is what a reader that deciphers the same {@code len / 4} words expects. Inputs
     * whose block is a multiple of four bytes — every case the previous code got right — produce
     * exactly the same output as before.
     */
    private static byte[] xxteaFirstBlock(byte[] data, int blockSize, byte[] key, boolean encrypt) {
        int blockLength = Math.min(blockSize, data.length);
        byte[] block = Arrays.copyOfRange(data, 0, blockLength);
        int[] dataInt = BytesUtils.toIntArray(block, ByteOrder.LITTLE_ENDIAN);
        int words = blockLength / 4;
        int[] resultInt = XXTEACipher.btea(dataInt, encrypt ? words : -words, BytesUtils.toIntArray(key, ByteOrder.BIG_ENDIAN));
        byte[] resultWords = BytesUtils.toByteArray(resultInt, ByteOrder.LITTLE_ENDIAN);
        // Start from a verbatim copy, so the unaligned tail of the block and everything past the
        // block survive unchanged, then overwrite the words that were actually ciphered.
        byte[] out = Arrays.copyOf(data, data.length);
        System.arraycopy(resultWords, 0, out, 0, resultWords.length);
        return out;
    }
    private static byte[] computeSpecificKeyV2FromUUID(byte[] uuid) {
        byte[] btKey = decipherFirstBlockCommonKey(uuid);
        byte[] reorderedBtKey = new byte[]{
                btKey[11], btKey[10], btKey[9], btKey[8],
                btKey[15], btKey[14], btKey[13], btKey[12],
                btKey[3], btKey[2], btKey[1], btKey[0],
                btKey[7], btKey[6], btKey[5], btKey[4]
        };
        return reorderedBtKey;
    }
    static byte[] cipherFirstBlockSpecificKeyV2(byte[] data, byte[] specificKey) {
        return xxteaFirstBlock(data, CIPHER_BLOCK_SIZE_BOOT_V2, specificKey, true);
    }

    static byte[] cipherFirstBlockSpecificKeyV3(byte[] data, FsDeviceKeyV3 deviceKeyV3) {
        byte[] block = Arrays.copyOfRange(data, 0, Math.min(CIPHER_BLOCK_SIZE_ASSETS_V3, data.length));
        byte[] encryptedBlock = AESCBCCipher.cipher(block, deviceKeyV3);
        int outputLength = encryptedBlock.length + Math.max(0, data.length - CIPHER_BLOCK_SIZE_ASSETS_V3);
        ByteBuffer bb = ByteBuffer.allocate(outputLength);
        bb.put(encryptedBlock);
        if (data.length > CIPHER_BLOCK_SIZE_ASSETS_V3) {
            bb.put(Arrays.copyOfRange(data, CIPHER_BLOCK_SIZE_ASSETS_V3, data.length));
        }
        return bb.array();
    }
    static byte[] decipherFirstBlockSpecificKeyV3(byte[] data, FsDeviceKeyV3 deviceKeyV3) {
        byte[] block = Arrays.copyOfRange(data, 0, Math.min(CIPHER_BLOCK_SIZE_ASSETS_V3, data.length));
        byte[] decryptedBlock = AESCBCCipher.decipher(block, deviceKeyV3);
        ByteBuffer bb = ByteBuffer.allocate(data.length);
        bb.put(decryptedBlock);
        if (data.length > CIPHER_BLOCK_SIZE_ASSETS_V3) {
            bb.put(Arrays.copyOfRange(data, CIPHER_BLOCK_SIZE_ASSETS_V3, data.length));
        }
        return bb.array();
    }

}
