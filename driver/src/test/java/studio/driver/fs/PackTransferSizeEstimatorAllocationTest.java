/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The allocated-space side of the estimator, used by the library before a pack is sent. */
class PackTransferSizeEstimatorAllocationTest {

    private static final long K32 = PackTransferSizeEstimator.DEFAULT_CLUSTER_BYTES;

    @TempDir
    Path source;

    @Test
    @DisplayName("the reported block size is trusted only when it can be a cluster")
    void clusterBytes() {
        assertEquals(K32, PackTransferSizeEstimator.clusterBytes(OptionalLong.empty()));
        assertEquals(K32, PackTransferSizeEstimator.clusterBytes(null));
        assertEquals(K32, PackTransferSizeEstimator.clusterBytes(OptionalLong.of(512)));   // the sector
        assertEquals(K32, PackTransferSizeEstimator.clusterBytes(OptionalLong.of(3000)));  // not a power of two
        assertEquals(K32, PackTransferSizeEstimator.clusterBytes(OptionalLong.of(1L << 30)));
        assertEquals(4096, PackTransferSizeEstimator.clusterBytes(OptionalLong.of(4096)));
        assertEquals(65536, PackTransferSizeEstimator.clusterBytes(OptionalLong.of(65536)));
    }

    @Test
    @DisplayName("a size is rounded up to whole clusters, an empty file takes none")
    void allocated() {
        assertEquals(0, PackTransferSizeEstimator.allocated(0, 4096));
        assertEquals(4096, PackTransferSizeEstimator.allocated(1, 4096));
        assertEquals(4096, PackTransferSizeEstimator.allocated(4096, 4096));
        assertEquals(8192, PackTransferSizeEstimator.allocated(4097, 4096));
        assertThrows(IllegalArgumentException.class, () -> PackTransferSizeEstimator.allocated(1, 0));
    }

    @Test
    @DisplayName("an FS folder: each copied file to the cluster, one cluster per folder, the boot file and the index temporary")
    void folder() throws IOException {
        Files.write(source.resolve(".cleartext"), new byte[0]);            // not copied
        Files.write(source.resolve("ni"), new byte[100]);                   // 1 cluster
        Files.createDirectories(source.resolve("sf").resolve("000"));
        Files.write(source.resolve("sf").resolve("000").resolve("00000000"), new byte[(int) K32 + 1]); // 2 clusters

        long allocated = PackTransferSizeEstimator.allocatedBytesForUpload(source, K32);

        // files 1 + 2, folders 3 (pack, sf, sf/000), boot file 1, index temporary 1
        assertEquals(8 * K32, allocated);
        // Never below the logical bound the transfer preflight checks
        assertTrue(allocated >= PackTransferSizeEstimator.additionalBytesForUpload(source, 0));
    }

    @Test
    @DisplayName("predicted files: the cipher padding then the cluster, plus the same overhead")
    void files() {
        long allocated = PackTransferSizeEstimator.allocatedBytesForFiles(Arrays.asList(10L, 4096L, 0L, null), 2, 4096);
        // 10 -> 16 -> 1 cluster, 4096 -> 1, 0 and null -> 0; 2 folders, bt, index
        assertEquals((1 + 1 + 2 + 1 + 1) * 4096L, allocated);
    }
}
