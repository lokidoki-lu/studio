/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.driver.StoryTellerException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Specifications for the fixes to the pack index and content folder bookkeeping of the firmware
 * 2.x / 3.x driver: duplicates, canonical UUIDs, a residual {@code .pi.new}, folders shared by two
 * UUIDs, and the partition changing under an operation.
 */
class FsDriverReviewFixesTest {

    @TempDir
    Path partition;

    @TempDir
    Path library;

    private static final UUID PACK_A = UUID.fromString("11111111-2222-3333-4444-5555aaaabbbb");
    private static final UUID PACK_B = UUID.fromString("66666666-7777-8888-9999-aaaaccccdddd");
    /** Same last 8 hex digits as PACK_B, so the same {@code .content} folder. */
    private static final UUID PACK_B_TWIN = UUID.fromString("00000000-0000-0000-0000-0000ccccdddd");

    // ------------------------------------------------------------------ fixtures

    private void writeDeviceMetadataV7() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(7);
        out.write(0);
        out.write('3');
        out.write('.');
        out.write('3');
        out.write(new byte[21], 0, 21);
        byte[] serial = new byte[24];
        System.arraycopy("SN0123456789ABCDEF012345".getBytes(StandardCharsets.US_ASCII), 0, serial, 0, 24);
        out.write(serial, 0, 24);
        out.write(new byte[14], 0, 14);
        byte[] keyMaterial = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyMaterial[i] = (byte) (0xC0 + i);
        }
        out.write(keyMaterial, 0, 32);
        Files.write(partition.resolve(".md"), out.toByteArray());
    }

    /** Metadata format 1 with firmware 0.0: readable, but refused by the copy once it has started. */
    private void writeDeviceMetadataUnsupportedFirmware() throws IOException {
        byte[] md = new byte[512];
        md[0] = 1;
        Files.write(partition.resolve(".md"), md);
    }

    private void writeIndexFile(UUID... uuids) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(uuids.length * 16);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        Files.write(partition.resolve(".pi"), buffer.array());
    }

    private byte[] indexBytes() throws IOException {
        return Files.readAllBytes(partition.resolve(".pi"));
    }

    private Path sourcePack() throws IOException {
        Path pack = library.resolve("source-pack");
        Files.createDirectories(pack);
        Files.write(pack.resolve(".cleartext"), new byte[0]);
        Files.write(pack.resolve("ni"), filler(200, 'n'));
        Files.write(pack.resolve("ri"), filler(100, 'r'));
        Files.write(pack.resolve("si"), filler(50, 's'));
        Files.write(pack.resolve("li"), filler(30, 'l'));
        return pack;
    }

    private static byte[] filler(int length, char seed) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) seed);
        return data;
    }

    private FsStoryTellerAsyncDriver driver() {
        return DriverTestSupport.pluggedDriverMountedOn(partition);
    }

    private Path contentFolderFor(UUID uuid) {
        String hex = uuid.toString().replace("-", "");
        return partition.resolve(".content").resolve(hex.substring(hex.length() - 8).toUpperCase());
    }

    @SuppressWarnings("unchecked")
    private static List<UUID> readPackIndex(FsStoryTellerAsyncDriver driver) {
        try {
            return ((CompletableFuture<List<UUID>>) DriverTestSupport.invokePrivate(driver, "readPackIndex")).join();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static StoryTellerException.Reason reasonOf(Throwable t) {
        return StoryTellerException.reasonOf(t);
    }

    @BeforeEach
    void prepareDevice() throws IOException {
        writeDeviceMetadataV7();
    }

    /* ================================================================== 3: duplicates */

    @Nested
    @DisplayName("duplicate index entries")
    class Duplicates {

        @Test
        @DisplayName("an upload of a pack already listed (content missing) restores the folder without a second entry")
        void uploadDoesNotDuplicateAnIndexedUuid() throws Exception {
            writeIndexFile(PACK_A, PACK_B);
            FsStoryTellerAsyncDriver driver = driver();

            driver.uploadPack(PACK_B.toString(), sourcePack().toString(), null).join();

            assertEquals(List.of(PACK_A, PACK_B), readPackIndex(driver), "PACK_B is listed exactly once");
            assertTrue(Files.isRegularFile(contentFolderFor(PACK_B).resolve("ni")), "and its content is back");
        }

        @Test
        @DisplayName("a delete removes every occurrence of the pack from the index")
        void deleteRemovesAllOccurrences() throws Exception {
            writeIndexFile(PACK_B, PACK_A, PACK_B);
            Files.createDirectories(contentFolderFor(PACK_B));
            FsStoryTellerAsyncDriver driver = driver();

            assertTrue(driver.deletePack(PACK_B.toString()).join());

            assertEquals(List.of(PACK_A), readPackIndex(driver));
            assertFalse(Files.exists(contentFolderFor(PACK_B)));
        }
    }

    /* ================================================================== 4: canonical UUIDs */

    @Nested
    @DisplayName("canonical UUIDs at the driver's entry points")
    class CanonicalUuids {

        /** Parsed by UUID.fromString as 00000001-0002-0003-0004-000000000005. */
        private static final String LENIENT = "1-2-3-4-5";

        @Test
        @DisplayName("a non-canonical spelling is refused by every operation, before anything is touched")
        void nonCanonicalIsRefusedEverywhere() throws Exception {
            writeIndexFile(PACK_A);
            byte[] before = indexBytes();
            FsStoryTellerAsyncDriver driver = driver();
            Path source = sourcePack();
            Path output = library.resolve("out");

            List<CompletableFuture<?>> attempts = List.of(
                    driver.uploadPack(LENIENT, source.toString(), null),
                    driver.deletePack(LENIENT),
                    driver.reorderPacks(List.of(LENIENT)),
                    driver.downloadPack(LENIENT, output.toString(), null),
                    driver.uploadPack("not-a-uuid", source.toString(), null),
                    driver.deletePack(null));

            for (CompletableFuture<?> attempt : attempts) {
                CompletionException raised = assertThrows(CompletionException.class, attempt::join);
                assertEquals(StoryTellerException.Reason.INVALID_REQUEST, reasonOf(raised));
            }
            assertArrayEquals(before, indexBytes(), "the index is untouched");
            assertFalse(Files.exists(partition.resolve(".content")), "no folder was created");
            assertFalse(Files.exists(output), "nothing was extracted");
        }

        @Test
        @DisplayName("an upper-case spelling is accepted and names the folder from the parsed UUID")
        void upperCaseIsAccepted() throws Exception {
            writeIndexFile();
            FsStoryTellerAsyncDriver driver = driver();

            driver.uploadPack(PACK_B.toString().toUpperCase(), sourcePack().toString(), null).join();

            assertEquals(List.of(PACK_B), readPackIndex(driver));
            assertTrue(Files.isDirectory(contentFolderFor(PACK_B)));

            driver.reorderPacks(List.of(PACK_B.toString().toUpperCase())).join();
            assertTrue(driver.deletePack(PACK_B.toString().toUpperCase()).join());
            assertEquals(List.of(), readPackIndex(driver));
            assertFalse(Files.exists(contentFolderFor(PACK_B)));
        }

        @Test
        @DisplayName("reorder compares UUIDs, so an upper-case request actually reorders")
        void reorderWithUpperCase() throws Exception {
            writeIndexFile(PACK_A, PACK_B);
            FsStoryTellerAsyncDriver driver = driver();

            driver.reorderPacks(List.of(PACK_B.toString().toUpperCase(), PACK_A.toString().toUpperCase())).join();

            assertEquals(List.of(PACK_B, PACK_A), readPackIndex(driver));
        }

        @Test
        @DisplayName("an extraction writes into a folder named after the canonical UUID")
        void downloadUsesCanonicalName() throws Exception {
            writeIndexFile();
            FsStoryTellerAsyncDriver driver = driver();
            driver.uploadPack(PACK_B.toString(), sourcePack().toString(), null).join();
            Path output = library.resolve("out");

            driver.downloadPack(PACK_B.toString().toUpperCase(), output.toString(), null).join();

            assertTrue(Files.isRegularFile(output.resolve(PACK_B.toString()).resolve("ni")));
        }

        @Test
        @DisplayName("canonicalPackUuid accepts any case and refuses lenient spellings")
        void canonicalPackUuid() {
            assertEquals(PACK_A, FsStoryTellerAsyncDriver.canonicalPackUuid(PACK_A.toString().toUpperCase()));
            StoryTellerException refused = assertThrows(StoryTellerException.class,
                    () -> FsStoryTellerAsyncDriver.canonicalPackUuid("0001-2-3-4-5"));
            assertEquals(StoryTellerException.Reason.INVALID_REQUEST, refused.getReason());
        }
    }

    /* ================================================================== 6: .pi.new and cleanup */

    @Nested
    @DisplayName("upload: a residual .pi.new, and a failed upload's own folder")
    class UploadCleanup {

        @Test
        @DisplayName("a residual .pi.new refuses the upload before any folder is reserved or file copied")
        void residualTemporaryRefusesEarly() throws Exception {
            writeIndexFile(PACK_A);
            Files.write(partition.resolve(".pi.new"), filler(16, 'T'));
            byte[] before = indexBytes();
            FsStoryTellerAsyncDriver driver = driver();

            CompletableFuture<?> upload = driver.uploadPack(PACK_B.toString(), sourcePack().toString(), null);

            assertTrue(upload.isCompletedExceptionally());
            assertThrows(CompletionException.class, upload::join);
            assertFalse(Files.exists(contentFolderFor(PACK_B)), "no destination folder was reserved");
            assertArrayEquals(before, indexBytes());
            assertArrayEquals(filler(16, 'T'), Files.readAllBytes(partition.resolve(".pi.new")),
                    "and the temporary is left exactly as found");
        }

        @Test
        @DisplayName("a copy that fails removes the folder the upload created")
        void failedCopyRemovesOwnFolder() throws Exception {
            writeIndexFile(PACK_A);
            writeDeviceMetadataUnsupportedFirmware();
            FsStoryTellerAsyncDriver driver = driver();

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), sourcePack().toString(), null).join());

            assertFalse(Files.exists(contentFolderFor(PACK_B)), "the folder this upload created is gone");
            assertTrue(Files.isDirectory(partition.resolve(".content")), "the shared .content folder stays");
            assertEquals(List.of(PACK_A), readPackIndex(driver));
        }

        @Test
        @DisplayName("an index write that fails removes the folder the upload created")
        void failedIndexWriteRemovesOwnFolder() throws Exception {
            writeIndexFile(PACK_A);
            PackIndexWriter failing = (path, uuids) -> {
                throw new IOException("simulated index write failure");
            };
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition, failing);

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), sourcePack().toString(), null).join());

            assertTrue(DriverTestSupport.rootCause(raised).getMessage().contains("simulated"));
            assertFalse(Files.exists(contentFolderFor(PACK_B)));
            assertEquals(List.of(PACK_A), readPackIndex(driver));
        }

        @Test
        @DisplayName("a folder the upload did NOT create is never removed")
        void preexistingFolderIsKept() throws Exception {
            writeIndexFile();
            Files.createDirectories(contentFolderFor(PACK_B));
            Files.write(contentFolderFor(PACK_B).resolve("keep"), filler(3, 'k'));
            FsStoryTellerAsyncDriver driver = driver();

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), sourcePack().toString(), null).join());

            assertTrue(Files.isRegularFile(contentFolderFor(PACK_B).resolve("keep")));
        }
    }

    /* ================================================================== 8: shared folder */

    @Nested
    @DisplayName("delete: a folder shared by two indexed UUIDs")
    class SharedFolder {

        @Test
        @DisplayName("the index is updated but the folder stays while another entry still names it")
        void folderSharedWithARemainingEntryIsKept() throws Exception {
            writeIndexFile(PACK_A, PACK_B, PACK_B_TWIN);
            Files.createDirectories(contentFolderFor(PACK_B));
            Files.write(contentFolderFor(PACK_B).resolve("ni"), filler(4, 'n'));
            FsStoryTellerAsyncDriver driver = driver();

            assertTrue(driver.deletePack(PACK_B.toString()).join());

            assertEquals(List.of(PACK_A, PACK_B_TWIN), readPackIndex(driver));
            assertTrue(Files.isRegularFile(contentFolderFor(PACK_B_TWIN).resolve("ni")),
                    "the folder is still the content of PACK_B_TWIN");
        }

        @Test
        @DisplayName("once the last entry naming it goes, the folder goes too")
        void lastEntryRemovesTheFolder() throws Exception {
            writeIndexFile(PACK_A, PACK_B, PACK_B_TWIN);
            Files.createDirectories(contentFolderFor(PACK_B));
            FsStoryTellerAsyncDriver driver = driver();

            driver.deletePack(PACK_B.toString()).join();
            driver.deletePack(PACK_B_TWIN.toString()).join();

            assertEquals(List.of(PACK_A), readPackIndex(driver));
            assertFalse(Files.exists(contentFolderFor(PACK_B)));
        }
    }

    /* ================================================================== 9: frozen mount point */

    @Nested
    @DisplayName("an operation stays on the partition it started on")
    class FrozenMountPoint {

        @TempDir
        Path otherPartition;

        @Test
        @DisplayName("the index is not written if the mount point changed, and the writer is never called")
        void indexWriteRefusedWhenMountPointChanged() throws Throwable {
            writeIndexFile(PACK_A);
            AtomicInteger writes = new AtomicInteger();
            PackIndexWriter counting = (path, uuids) -> writes.incrementAndGet();
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition, counting);
            FsStoryTellerAsyncDriver.PartitionSession started = driver.currentPartition();
            DriverTestSupport.setField(driver, "partitionMountPoint", otherPartition.toString());

            CompletableFuture<?> result = invokeWritePackIndex(driver, started, List.of(PACK_A, PACK_B));

            CompletionException raised = assertThrows(CompletionException.class, result::join);
            assertEquals(StoryTellerException.Reason.NO_DEVICE, reasonOf(raised));
            assertEquals(0, writes.get());
        }

        @Test
        @DisplayName("a delete whose partition changes during the index write does not remove a folder elsewhere")
        void deleteDoesNotFollowAChangedMountPoint() throws Exception {
            writeIndexFile(PACK_A, PACK_B);
            Files.createDirectories(contentFolderFor(PACK_B));
            Path elsewhere = otherPartition.resolve(".content").resolve(contentFolderFor(PACK_B).getFileName());
            Files.createDirectories(elsewhere);
            List<FsStoryTellerAsyncDriver> self = new ArrayList<>();
            PackIndexWriter switching = (path, uuids) -> {
                new TemporaryFilePackIndexWriter().write(path, uuids);
                // The device is replaced while the operation runs.
                DriverTestSupport.setField(self.get(0), "partitionMountPoint", otherPartition.toString());
            };
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition, switching);
            self.add(driver);

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.deletePack(PACK_B.toString()).join());

            assertEquals(StoryTellerException.Reason.NO_DEVICE, reasonOf(raised));
            assertTrue(Files.isDirectory(elsewhere), "nothing is removed on the other partition");
            assertTrue(Files.isDirectory(contentFolderFor(PACK_B)),
                    "nor on the original one, which is no longer known to be the device");
        }
    }

    /* ================================================================== pack without images */

    @Test
    @DisplayName("a pack without images (empty ri) and without .cleartext uploads instead of failing on the ri check")
    void uploadWithEmptyRiAndNoCleartextMarker() throws Exception {
        writeIndexFile(PACK_A);
        Path pack = sourcePack();
        Files.delete(pack.resolve(".cleartext"));
        Files.write(pack.resolve("ri"), new byte[0]);
        FsStoryTellerAsyncDriver driver = driver();

        driver.uploadPack(PACK_B.toString(), pack.toString(), null).join();

        assertEquals(List.of(PACK_A, PACK_B), readPackIndex(driver));
        assertEquals(0L, Files.size(contentFolderFor(PACK_B).resolve("ri")));
        assertFalse(Files.exists(pack.resolve(".cleartext")), "an empty ri is not taken for a cleartext one");
    }

    /* ================================================================== same path, other card */

    /**
     * Card A is unplugged and card B is mounted on the very same path while an operation started on
     * A is still running. The mount point string is identical, so only the plug generation can tell
     * the two apart.
     */
    @Nested
    @DisplayName("an operation does not carry over to another card mounted on the same path")
    class SamePathOtherCard {

        /** Simulates unplugging the current card and plugging another one on the same path. */
        private void replugOnSamePath(FsStoryTellerAsyncDriver driver) {
            DriverTestSupport.setField(driver, "listeners", new ArrayList<>());
            DriverTestSupport.setField(driver, "partitionLocator", new DevicePartitionLocator(
                    () -> List.of(partition.toString()), path -> true, millis -> true, 0L, 1));
            // The device currently published (an inert Device compares equal to another inert one):
            // an unplug of any other device is ignored by the driver.
            driver.handleDeviceUnplugged(DriverTestSupport.newInertDevice());
            driver.handleDevicePlugged(DriverTestSupport.newInertDevice());
            assertEquals(partition.toString(), driver.getPartitionMountPoint(), "B is on the same path as A");
        }

        @Test
        @DisplayName("every plug and unplug moves the generation, even when the path is unchanged")
        void generationMovesOnReplug() {
            FsStoryTellerAsyncDriver driver = driver();
            FsStoryTellerAsyncDriver.PartitionSession before = driver.currentPartition();

            replugOnSamePath(driver);
            FsStoryTellerAsyncDriver.PartitionSession after = driver.currentPartition();

            assertEquals(before.mountPoint, after.mountPoint);
            assertEquals(before.generation + 2, after.generation, "one for the unplug, one for the plug");
        }

        @Test
        @DisplayName("the index is not written on card B with a session frozen on card A")
        void indexWriteRefusedAfterReplugOnSamePath() throws Throwable {
            writeIndexFile(PACK_A);
            AtomicInteger writes = new AtomicInteger();
            PackIndexWriter counting = (path, uuids) -> writes.incrementAndGet();
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition, counting);
            FsStoryTellerAsyncDriver.PartitionSession onCardA = driver.currentPartition();

            replugOnSamePath(driver);

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> invokeWritePackIndex(driver, onCardA, List.of(PACK_A, PACK_B)).join());
            assertEquals(StoryTellerException.Reason.NO_DEVICE, reasonOf(raised));
            assertEquals(0, writes.get());
            assertArrayEquals(indexBytesOf(PACK_A), indexBytes());
        }

        @Test
        @DisplayName("a failed upload does not remove its folder name on card B")
        void failedUploadCleanupSkippedAfterReplugOnSamePath() throws Exception {
            writeIndexFile(PACK_A);
            List<FsStoryTellerAsyncDriver> self = new ArrayList<>();
            PackIndexWriter replugThenFail = (path, uuids) -> {
                // Card A goes away during the upload and card B shows up on the same path, holding
                // a folder of the same name that this upload did not create.
                replugOnSamePath(self.get(0));
                throw new IOException("card removed during the index write");
            };
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition, replugThenFail);
            self.add(driver);

            assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), sourcePack().toString(), null).join());

            assertTrue(Files.isDirectory(contentFolderFor(PACK_B)),
                    "the folder is left in place: on this card, it is not known to be ours");
        }

        @Test
        @DisplayName("a delete does not remove a folder on card B after its index write on card A")
        void deleteDoesNotRemoveOnReplugOnSamePath() throws Exception {
            writeIndexFile(PACK_A, PACK_B);
            Files.createDirectories(contentFolderFor(PACK_B));
            List<FsStoryTellerAsyncDriver> self = new ArrayList<>();
            PackIndexWriter replugAfterWrite = (path, uuids) -> {
                new TemporaryFilePackIndexWriter().write(path, uuids);
                replugOnSamePath(self.get(0));
            };
            FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition, replugAfterWrite);
            self.add(driver);

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.deletePack(PACK_B.toString()).join());

            assertEquals(StoryTellerException.Reason.NO_DEVICE, reasonOf(raised));
            assertTrue(Files.isDirectory(contentFolderFor(PACK_B)), "nothing is removed on card B");
        }
    }

    private static CompletableFuture<?> invokeWritePackIndex(FsStoryTellerAsyncDriver driver,
                                                             FsStoryTellerAsyncDriver.PartitionSession session,
                                                             List<UUID> uuids) throws Throwable {
        Method write = FsStoryTellerAsyncDriver.class.getDeclaredMethod("writePackIndex",
                FsStoryTellerAsyncDriver.PartitionSession.class, List.class);
        write.setAccessible(true);
        try {
            return (CompletableFuture<?>) write.invoke(driver, session, uuids);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static byte[] indexBytesOf(UUID... uuids) {
        ByteBuffer buffer = ByteBuffer.allocate(uuids.length * 16);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        return buffer.array();
    }

    /* ================================================================== 2: syncing */

    @Nested
    @DisplayName("syncing to the card")
    class Syncing {

        @Test
        @DisplayName("a directory that cannot be forced does not fail an index that was installed")
        void directorySyncFailureIsIgnored() throws Exception {
            writeIndexFile(PACK_A);
            TemporaryFilePackIndexWriter writer = new TemporaryFilePackIndexWriter() {
                @Override
                void syncDirectory(Path directory) throws IOException {
                    throw new IOException("not supported on this platform");
                }
            };

            writer.write(partition.resolve(".pi").toString(), List.of(PACK_A, PACK_B));

            assertEquals(32, indexBytes().length);
            assertFalse(Files.exists(partition.resolve(".pi.new")));
        }
    }

    /* ================================================================== 5: several .md volumes */

    @Nested
    @DisplayName("partition detection with several candidate volumes")
    class SeveralCandidates {

        @Test
        @DisplayName("two volumes carrying a .md are refused rather than picking the first")
        void ambiguousIsRefused() {
            DevicePartitionLocator locator = new DevicePartitionLocator(
                    () -> Arrays.asList("D:\\", "E:\\"), path -> true, millis -> true, 0L, 3);

            assertEquals(Optional.empty(), locator.awaitPartition(() -> true));
        }

        @Test
        @DisplayName("the search resumes once only one remains")
        void resolvedWhenOneRemains() {
            AtomicInteger tick = new AtomicInteger();
            DevicePartitionLocator locator = new DevicePartitionLocator(
                    () -> tick.incrementAndGet() < 3 ? Arrays.asList("D:\\", "E:\\") : Arrays.asList("E:\\"),
                    path -> true, millis -> true, 0L, 10);

            assertEquals(Optional.of("E:\\"), locator.awaitPartition(() -> true));
        }

        @Test
        @DisplayName("one volume listed under two names (symlink, path spelled twice) counts once")
        void sameVolumeUnderTwoNamesIsOneCandidate() throws IOException {
            Path alias = library.resolve("alias");
            try {
                Files.createSymbolicLink(alias, partition);
            } catch (UnsupportedOperationException | IOException noSymlinks) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links not available here");
            }
            String spelledTwice = partition.resolve(".").toString();
            DevicePartitionLocator locator = new DevicePartitionLocator(
                    () -> Arrays.asList(partition.toString(), alias.toString(), spelledTwice),
                    path -> true, millis -> true, 0L, 1);

            assertEquals(Optional.of(partition.toString()), locator.awaitPartition(() -> true),
                    "the first name listed is kept");
        }

        @Test
        @DisplayName("two distinct real volumes are still refused")
        void twoRealVolumesStillRefused() {
            DevicePartitionLocator locator = new DevicePartitionLocator(
                    () -> Arrays.asList(partition.toString(), library.toString()),
                    path -> true, millis -> true, 0L, 1);

            assertEquals(Optional.empty(), locator.awaitPartition(() -> true));
        }
    }

    /* ================================================================== 7: XXTEA tail */

    @Nested
    @DisplayName("V2 XXTEA on a short, unaligned block")
    class XxteaTail {

        private byte[] pattern(int length) {
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                data[i] = (byte) (0x80 + i % 97);
            }
            return data;
        }

        @Test
        @DisplayName("the 1-3 bytes past the last whole word are copied verbatim, not zeroed")
        void unalignedTailIsKept() {
            for (int length : new int[]{101, 102, 103, 9, 511}) {
                byte[] source = pattern(length);
                int aligned = length / 4 * 4;

                byte[] ciphered = CipherUtils.cipherFirstBlockCommonKey(source);

                assertEquals(length, ciphered.length);
                assertArrayEquals(Arrays.copyOfRange(source, aligned, length),
                        Arrays.copyOfRange(ciphered, aligned, length), "tail of a " + length + "-byte input");
                assertArrayEquals(CipherUtils.cipherFirstBlockCommonKey(Arrays.copyOf(source, aligned)),
                        Arrays.copyOf(ciphered, aligned), "the ciphered words are those of the aligned prefix");
                assertArrayEquals(source, CipherUtils.decipherFirstBlockCommonKey(ciphered), "round trip");
            }
        }

        @Test
        @DisplayName("aligned and long inputs are ciphered exactly as before")
        void alignedUnchanged() {
            byte[] source = pattern(700);
            byte[] ciphered = CipherUtils.cipherFirstBlockCommonKey(source);
            assertArrayEquals(Arrays.copyOfRange(source, 512, 700), Arrays.copyOfRange(ciphered, 512, 700));
            assertFalse(Arrays.equals(Arrays.copyOf(source, 512), Arrays.copyOf(ciphered, 512)));
        }
    }
}
