/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.driver.model.fs.FsDeviceKeyV3;

import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link CipherUtils#transformFirstBlock} writes exactly what the whole-file transforms produce,
 * for every transform the transfer uses and for file sizes around the block boundaries.
 */
class CipherUtilsStreamingTest {

    private static final OpenOption[] EXCLUSIVE = {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};

    @TempDir
    Path tmp;

    private static FsDeviceKeyV3 deviceKey() {
        byte[] aesKey = new byte[16];
        byte[] aesIv = new byte[16];
        byte[] bt = new byte[32];
        for (int i = 0; i < 16; i++) {
            aesKey[i] = (byte) (0x31 + i);
            aesIv[i] = (byte) (0xC0 + i);
        }
        return new FsDeviceKeyV3(aesKey, aesIv, bt);
    }

    /** The transforms of copyPackFolder, by direction and firmware. */
    private static Map<String, UnaryOperator<byte[]>> transforms() {
        FsDeviceKeyV3 key = deviceKey();
        Map<String, UnaryOperator<byte[]>> t = new LinkedHashMap<>();
        t.put("v2 upload (cipher)", CipherUtils::cipherFirstBlockCommonKey);
        t.put("v2 download (decipher)", CipherUtils::decipherFirstBlockCommonKey);
        t.put("v3 upload from cleartext", data -> CipherUtils.cipherFirstBlockSpecificKeyV3(data, key));
        t.put("v3 upload from ciphered library pack", data -> CipherUtils.cipherFirstBlockSpecificKeyV3(CipherUtils.decipherFirstBlockCommonKey(data), key));
        return t;
    }

    private static final int[] SIZES = {0, 1, 3, 4, 15, 16, 17, 100, 508, 511, 512, 513, 1000, 4096 + 7, 1024 * 1024 + 5};

    @Test
    @DisplayName("same bytes as the whole-file transform, for every transform and size")
    void sameBytesAsWholeFile() throws Exception {
        Random random = new Random(42);
        int n = 0;
        for (int size : SIZES) {
            byte[] content = new byte[size];
            random.nextBytes(content);
            Path source = tmp.resolve("source-" + size);
            Files.write(source, content);
            for (Map.Entry<String, UnaryOperator<byte[]>> transform : transforms().entrySet()) {
                Path target = tmp.resolve("target-" + n++);
                CipherUtils.transformFirstBlock(source, target, transform.getValue(), EXCLUSIVE);
                assertArrayEquals(transform.getValue().apply(content), Files.readAllBytes(target),
                        transform.getKey() + ", " + size + " bytes");
            }
        }
    }

    @Test
    @DisplayName("v3 download: same bytes as the whole-file decipher of what the device holds")
    void v3DownloadSameBytes() throws Exception {
        FsDeviceKeyV3 key = deviceKey();
        Random random = new Random(7);
        int n = 0;
        for (int size : SIZES) {
            if (size % 16 != 0 && size < 512) {
                continue; // what the device holds is block aligned (see the test below)
            }
            byte[] cleartext = new byte[size];
            random.nextBytes(cleartext);
            byte[] onDevice = CipherUtils.cipherFirstBlockSpecificKeyV3(cleartext, key);
            Path source = tmp.resolve("device-" + n);
            Files.write(source, onDevice);
            Path target = tmp.resolve("library-" + n++);
            CipherUtils.transformFirstBlock(source, target, data -> CipherUtils.decipherFirstBlockSpecificKeyV3(data, key), EXCLUSIVE);
            assertArrayEquals(CipherUtils.decipherFirstBlockSpecificKeyV3(onDevice, key), Files.readAllBytes(target), size + " bytes");
        }
    }

    @Test
    @DisplayName("a transform that fails fails the same way and leaves no file")
    void failingTransformLeavesNothing() throws Exception {
        FsDeviceKeyV3 key = deviceKey();
        byte[] unaligned = new byte[100];
        Path source = tmp.resolve("unaligned");
        Files.write(source, unaligned);
        Class<? extends Throwable> whole = assertThrows(RuntimeException.class,
                () -> CipherUtils.decipherFirstBlockSpecificKeyV3(unaligned, key)).getClass();
        Path target = tmp.resolve("never");
        assertThrows(whole, () -> CipherUtils.transformFirstBlock(source, target,
                data -> CipherUtils.decipherFirstBlockSpecificKeyV3(data, key), EXCLUSIVE));
        assertFalse(Files.exists(target));
    }

    @Test
    @DisplayName("an existing target is never replaced (CREATE_NEW)")
    void existingTargetIsKept() throws Exception {
        Path source = tmp.resolve("s");
        Files.write(source, new byte[600]);
        Path target = tmp.resolve("t");
        Files.write(target, new byte[]{1, 2, 3});
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> CipherUtils.transformFirstBlock(source, target, CipherUtils::cipherFirstBlockCommonKey, EXCLUSIVE));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target));
    }
}
