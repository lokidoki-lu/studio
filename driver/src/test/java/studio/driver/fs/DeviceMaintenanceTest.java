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
import org.objenesis.ObjenesisStd;
import studio.driver.StoryTellerException;
import studio.driver.model.fs.FsDeviceDiagnosis;
import studio.driver.model.fs.FsDeviceDiagnosis.InterruptedUpdateState;
import studio.driver.model.fs.FsDeviceDiagnosis.PendingIndexKind;
import studio.driver.model.fs.FsDeviceInfos;
import studio.driver.model.fs.FsPackIntegrity;
import studio.driver.model.fs.FsPackIntegrity.ProblemCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The device check, its repairs, the integrity check and the in-place update, on a simulated
 * firmware 3.x partition (a plain folder with {@code .md}, {@code .pi} and {@code .content}).
 */
class DeviceMaintenanceTest {

    @TempDir
    Path partition;

    @TempDir
    Path library;

    private static final UUID PACK_A = UUID.fromString("11111111-2222-3333-4444-5555aaaabbbb");
    private static final UUID PACK_B = UUID.fromString("66666666-7777-8888-9999-aaaaccccdddd");
    private static final UUID PACK_C = UUID.fromString("0c0c0c0c-0000-0000-0000-0000eeeeffff");

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

    private void writeIndex(Path file, UUID... uuids) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(uuids.length * 16);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        Files.write(file, buffer.array());
    }

    private Path index() {
        return partition.resolve(".pi");
    }

    private Path pendingIndex() {
        return partition.resolve(".pi.new");
    }

    private Path folderOf(UUID uuid) {
        return partition.resolve(".content").resolve(FsDeviceMaintenance.folderNameOf(uuid));
    }

    /**
     * A small but complete cleartext FS pack: two stage nodes, one image, one sound; node 0's OK
     * leads to node 1 through menu 0, node 1's home back to node 0 through menu 1.
     */
    private Path sourcePack(String name, byte soundSeed) throws IOException {
        Path pack = library.resolve(name);
        Files.createDirectories(pack.resolve("rf").resolve("000"));
        Files.createDirectories(pack.resolve("sf").resolve("000"));
        Files.write(pack.resolve(".cleartext"), new byte[0]);
        ByteBuffer ni = ByteBuffer.allocate(512 + 2 * 44).order(ByteOrder.LITTLE_ENDIAN);
        ni.putShort((short) 1).putShort((short) 1).putInt(512).putInt(44).putInt(2).putInt(1).putInt(1).put((byte) 0);
        ni.position(512);
        // node 0: image 0, sound 0, ok -> menu 0 (1 option, option 0), no home
        ni.putInt(0).putInt(0).putInt(0).putInt(1).putInt(0).putInt(-1).putInt(-1).putInt(-1);
        ni.putShort((short) 1).putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        ni.position(512 + 44);
        // node 1: no image, sound 0, no ok, home -> menu 1
        ni.putInt(-1).putInt(0).putInt(-1).putInt(-1).putInt(-1).putInt(1).putInt(1).putInt(0);
        ni.putShort((short) 0).putShort((short) 0).putShort((short) 1).putShort((short) 1).putShort((short) 1);
        Files.write(pack.resolve("ni"), ni.array());
        Files.write(pack.resolve("ri"), "000\\00000001".getBytes(StandardCharsets.US_ASCII));
        Files.write(pack.resolve("si"), "000\\00000001".getBytes(StandardCharsets.US_ASCII));
        ByteBuffer li = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(0);
        Files.write(pack.resolve("li"), li.array());
        byte[] bmp = new byte[700];
        bmp[0] = 'B';
        bmp[1] = 'M';
        Arrays.fill(bmp, 2, bmp.length, (byte) 7);
        Files.write(pack.resolve("rf").resolve("000").resolve("00000001"), bmp);
        byte[] mp3 = new byte[900];
        mp3[0] = 'I';
        mp3[1] = 'D';
        mp3[2] = '3';
        Arrays.fill(mp3, 3, mp3.length, soundSeed);
        Files.write(pack.resolve("sf").resolve("000").resolve("00000001"), mp3);
        return pack;
    }

    private FsStoryTellerAsyncDriver driver() {
        return DriverTestSupport.pluggedDriverMountedOn(partition);
    }

    private FsDeviceInfos infos(FsStoryTellerAsyncDriver driver) {
        return driver.getDeviceInfos().join();
    }

    private static StoryTellerException.Reason reasonOf(Throwable t) {
        return StoryTellerException.reasonOf(t);
    }

    @BeforeEach
    void prepareDevice() throws IOException {
        writeDeviceMetadataV7();
        writeIndex(index());
        Files.createDirectories(partition.resolve(".content"));
    }

    /** Uploads a fresh source pack for {@code uuid}, as STUdio would. */
    private Path upload(FsStoryTellerAsyncDriver driver, UUID uuid, String name, byte seed) throws IOException {
        Path source = sourcePack(name, seed);
        driver.uploadPack(uuid.toString(), source.toString(), null).join();
        return source;
    }

    // ------------------------------------------------------------------ diagnosis

    @Nested
    @DisplayName("diagnosis")
    class Diagnosis {

        @Test
        @DisplayName("a clean device has no problem")
        void clean() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            upload(driver, PACK_A, "a", (byte) 1);
            FsDeviceDiagnosis d = driver.diagnose().join();
            assertEquals(0, d.problemCount());
            assertEquals(1, d.getIndexedPacks());
            assertNull(d.getPendingIndex());
        }

        @Test
        @DisplayName("orphans are listed with their size and whether the copy completed; missing content too")
        void orphansAndMissing() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            upload(driver, PACK_A, "a", (byte) 1);
            Path partial = partition.resolve(".content").resolve("0A0B0C0D");
            Files.createDirectories(partial);
            Files.write(partial.resolve("ni"), new byte[100]);
            writeIndex(index(), PACK_A, PACK_B);

            FsDeviceDiagnosis d = driver.diagnose().join();
            assertEquals(1, d.getOrphans().size());
            assertEquals("0A0B0C0D", d.getOrphans().get(0).getFolder());
            assertEquals(100, d.getOrphans().get(0).getSizeInBytes());
            assertFalse(d.getOrphans().get(0).isComplete());
            assertEquals(1, d.getMissingContent().size());
            assertEquals(PACK_B, d.getMissingContent().get(0).getUuid());
            assertFalse(d.getMissingContent().get(0).isFolderPresent());
            // Nothing was written by the check
            assertTrue(Files.exists(partial.resolve("ni")));
        }
    }

    // ------------------------------------------------------------------ pending index

    @Nested
    @DisplayName("a pending .pi.new")
    class PendingIndex {

        @Test
        @DisplayName("identical to .pi: safe, and discarding it removes only it")
        void identical() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            upload(driver, PACK_A, "a", (byte) 1);
            Files.copy(index(), pendingIndex());
            byte[] before = Files.readAllBytes(index());

            FsDeviceDiagnosis.PendingIndex p = driver.diagnose().join().getPendingIndex();
            assertEquals(PendingIndexKind.IDENTICAL, p.getKind());
            assertTrue(p.isSafeToDiscard());
            assertTrue(driver.resolvePendingIndex(false, p.getFingerprint()).join());
            assertFalse(Files.exists(pendingIndex()));
            assertArrayEquals(before, Files.readAllBytes(index()));
        }

        @Test
        @DisplayName("torn: safe to discard, cannot be adopted")
        void torn() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            Files.write(pendingIndex(), new byte[20]);
            FsDeviceDiagnosis.PendingIndex p = driver.diagnose().join().getPendingIndex();
            assertEquals(PendingIndexKind.TORN, p.getKind());
            assertTrue(p.isSafeToDiscard());
            CompletionException e = assertThrows(CompletionException.class, () -> driver.resolvePendingIndex(true, p.getFingerprint()).join());
            assertEquals(StoryTellerException.Reason.INVALID_REQUEST, reasonOf(e));
            assertTrue(Files.exists(pendingIndex()));
        }

        @Test
        @DisplayName(".pi plus a pack whose copy did not finish: safe to discard, adopting refused")
        void addIncomplete() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            upload(driver, PACK_A, "a", (byte) 1);
            Files.createDirectories(folderOf(PACK_B));
            Files.write(folderOf(PACK_B).resolve("ni"), new byte[10]);
            writeIndex(pendingIndex(), PACK_A, PACK_B);

            FsDeviceDiagnosis.PendingIndex p = driver.diagnose().join().getPendingIndex();
            assertEquals(PendingIndexKind.ADD_INCOMPLETE, p.getKind());
            assertTrue(p.isSafeToDiscard());
            assertEquals(List.of(PACK_B), p.getAdded());
            assertThrows(CompletionException.class, () -> driver.resolvePendingIndex(true, p.getFingerprint()).join());
            assertTrue(driver.resolvePendingIndex(false, p.getFingerprint()).join());
            assertFalse(Files.exists(pendingIndex()));
            assertEquals(List.of(PACK_A), FsDeviceMaintenance.parseIndex(Files.readAllBytes(index())));
        }

        @Test
        @DisplayName(".pi plus a complete pack: a choice; adopting installs it in .pi")
        void addCompleteAdopted() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            upload(driver, PACK_A, "a", (byte) 1);
            upload(driver, PACK_B, "b", (byte) 2);
            // As if the index write adding B had been cut after .pi.new was written
            writeIndex(pendingIndex(), PACK_A, PACK_B);
            writeIndex(index(), PACK_A);

            FsDeviceDiagnosis d = driver.diagnose().join();
            FsDeviceDiagnosis.PendingIndex p = d.getPendingIndex();
            assertEquals(PendingIndexKind.ADD, p.getKind());
            assertFalse(p.isSafeToDiscard());
            assertTrue(driver.resolvePendingIndex(true, p.getFingerprint()).join());
            assertFalse(Files.exists(pendingIndex()));
            assertEquals(List.of(PACK_A, PACK_B), FsDeviceMaintenance.parseIndex(Files.readAllBytes(index())));
            assertEquals(0, driver.diagnose().join().problemCount());
        }

        @Test
        @DisplayName("an interrupted removal and an interrupted reordering are choices, not safe")
        void removeAndReorder() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            upload(driver, PACK_A, "a", (byte) 1);
            upload(driver, PACK_B, "b", (byte) 2);
            writeIndex(pendingIndex(), PACK_B);
            FsDeviceDiagnosis.PendingIndex remove = driver.diagnose().join().getPendingIndex();
            assertEquals(PendingIndexKind.REMOVE, remove.getKind());
            assertFalse(remove.isSafeToDiscard());
            assertEquals(List.of(PACK_A), remove.getRemoved());

            writeIndex(pendingIndex(), PACK_B, PACK_A);
            FsDeviceDiagnosis.PendingIndex reorder = driver.diagnose().join().getPendingIndex();
            assertEquals(PendingIndexKind.REORDER, reorder.getKind());
            // Discarding keeps the order the device has
            assertTrue(driver.resolvePendingIndex(false, reorder.getFingerprint()).join());
            assertEquals(List.of(PACK_A, PACK_B), FsDeviceMaintenance.parseIndex(Files.readAllBytes(index())));
        }

        @Test
        @DisplayName("nothing is done if the files changed since they were checked")
        void stale() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            Files.copy(index(), pendingIndex());
            String fingerprint = driver.diagnose().join().getPendingIndex().getFingerprint();
            writeIndex(pendingIndex(), PACK_C);
            CompletionException e = assertThrows(CompletionException.class, () -> driver.resolvePendingIndex(false, fingerprint).join());
            assertEquals(StoryTellerException.Reason.DEVICE_STATE_CHANGED, reasonOf(e));
            assertTrue(Files.exists(pendingIndex()));
        }

        @Test
        @DisplayName("an upload and a removal refused because of it say so with PACK_INDEX_PENDING")
        void refusalsCarryTheReason() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            upload(driver, PACK_A, "a", (byte) 1);
            Files.copy(index(), pendingIndex());
            Path source = sourcePack("b", (byte) 2);
            CompletionException upload = assertThrows(CompletionException.class,
                    () -> driver.uploadPack(PACK_B.toString(), source.toString(), null).join());
            assertEquals(StoryTellerException.Reason.PACK_INDEX_PENDING, reasonOf(upload));
            CompletionException delete = assertThrows(CompletionException.class, () -> driver.deletePack(PACK_A.toString()).join());
            assertEquals(StoryTellerException.Reason.PACK_INDEX_PENDING, reasonOf(delete));
        }
    }

    // ------------------------------------------------------------------ in-place update

    /** A driver whose renames and removals can be interrupted, as an unplug would. */
    static class SeamDriver extends FsStoryTellerAsyncDriver {
        int failRenameAt = -1;
        boolean failRemove;
        final AtomicInteger renames = new AtomicInteger();

        SeamDriver() {
            super(null, null);
        }

        @Override
        void renameFolder(Path from, Path to) throws IOException {
            int n = renames.incrementAndGet();
            if (failRenameAt >= 0 && n >= failRenameAt) {
                unplug();
                throw new IllegalStateException("simulated unplug");
            }
            super.renameFolder(from, to);
        }

        @Override
        void removeFolder(Path folder) throws IOException {
            if (failRemove) {
                throw new IOException("simulated failure while removing " + folder);
            }
            super.removeFolder(folder);
        }

        void unplug() {
            try {
                Field generation = FsStoryTellerAsyncDriver.class.getDeclaredField("partitionGeneration");
                generation.setAccessible(true);
                generation.setLong(this, generation.getLong(this) + 1);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private SeamDriver seamDriver() {
        SeamDriver driver = new ObjenesisStd().newInstance(SeamDriver.class);
        setSuperField(driver, "partitionMountPoint", partition.toString());
        setSuperField(driver, "packIndexWriter", new TemporaryFilePackIndexWriter());
        setSuperField(driver, "device", DriverTestSupport.newInertDevice());
        try {
            Field renames = SeamDriver.class.getDeclaredField("renames");
            renames.setAccessible(true);
            renames.set(driver, new AtomicInteger());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        driver.failRenameAt = -1;
        return driver;
    }

    private static void setSuperField(Object target, String name, Object value) {
        try {
            Field field = FsStoryTellerAsyncDriver.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] deviceSound(UUID uuid) throws IOException {
        return Files.readAllBytes(folderOf(uuid).resolve("sf").resolve("000").resolve("00000001"));
    }

    @Nested
    @DisplayName("in-place update")
    class Update {

        @Test
        @DisplayName("replaces the content, keeps the pack's place (the index is not rewritten), leaves nothing behind")
        void replaces() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            upload(driver, PACK_A, "a", (byte) 1);
            Path oldB = upload(driver, PACK_B, "b", (byte) 2);
            upload(driver, PACK_C, "c", (byte) 3);
            byte[] indexBefore = Files.readAllBytes(index());
            byte[] soundBefore = deviceSound(PACK_B);
            Path newB = sourcePack("b2", (byte) 9);
            assertTrue(driver.packContentMatches(PACK_B.toString(), oldB.toString()).join());
            assertFalse(driver.packContentMatches(PACK_B.toString(), newB.toString()).join());

            driver.updatePack(PACK_B.toString(), newB.toString(), null).join();

            assertArrayEquals(indexBefore, Files.readAllBytes(index()));
            assertFalse(Arrays.equals(soundBefore, deviceSound(PACK_B)));
            assertTrue(driver.packContentMatches(PACK_B.toString(), newB.toString()).join());
            assertEquals(0, driver.diagnose().join().problemCount());
            assertTrue(driver.checkIntegrity(null).join().stream().allMatch(FsPackIntegrity::isOk));
        }

        @Test
        @DisplayName("a pack that is not on the device is refused")
        void notOnDevice() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            Path source = sourcePack("b", (byte) 2);
            CompletionException e = assertThrows(CompletionException.class,
                    () -> driver.updatePack(PACK_B.toString(), source.toString(), null).join());
            assertEquals(StoryTellerException.Reason.PACK_NOT_ON_DEVICE, reasonOf(e));
        }

        @Test
        @DisplayName("unplugged between the two renames: the check finds it, and finishing it installs the new version")
        void interruptedBetweenRenames() throws IOException {
            SeamDriver driver = seamDriver();
            upload(driver, PACK_A, "a", (byte) 1);
            upload(driver, PACK_B, "b", (byte) 2);
            byte[] indexBefore = Files.readAllBytes(index());
            Path newB = sourcePack("b2", (byte) 9);
            driver.failRenameAt = 2;

            assertThrows(CompletionException.class, () -> driver.updatePack(PACK_B.toString(), newB.toString(), null).join());

            String hex = FsDeviceMaintenance.folderNameOf(PACK_B);
            Path content = partition.resolve(".content");
            assertFalse(Files.exists(folderOf(PACK_B)));
            assertTrue(Files.isDirectory(content.resolve(".studio-new-" + hex)));
            assertTrue(Files.isDirectory(content.resolve(".studio-old-" + hex)));
            assertArrayEquals(indexBefore, Files.readAllBytes(index()));

            // Plugged back in
            FsStoryTellerAsyncDriver fresh = driver();
            FsDeviceDiagnosis d = fresh.diagnose().join();
            assertEquals(1, d.getInterruptedUpdates().size());
            assertEquals(InterruptedUpdateState.SWAP_INTERRUPTED, d.getInterruptedUpdates().get(0).getState());
            assertEquals(PACK_B, d.getInterruptedUpdates().get(0).getUuid());
            assertTrue(d.getMissingContent().isEmpty(), "reported once, as an interrupted update");
            assertTrue(d.getOrphans().isEmpty());

            assertTrue(fresh.resolveInterruptedUpdate(hex).join());
            assertTrue(fresh.packContentMatches(PACK_B.toString(), newB.toString()).join());
            assertEquals(0, fresh.diagnose().join().problemCount());
            assertArrayEquals(indexBefore, Files.readAllBytes(index()));
        }

        @Test
        @DisplayName("unplugged while the new copy was written: the pack is intact, the leftover is removed")
        void interruptedDuringCopy() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            Path oldA = upload(driver, PACK_A, "a", (byte) 1);
            String hex = FsDeviceMaintenance.folderNameOf(PACK_A);
            Path leftover = partition.resolve(".content").resolve(".studio-new-" + hex);
            Files.createDirectories(leftover);
            Files.write(leftover.resolve("ni"), new byte[50]);

            // A new update is refused until it is resolved
            Path newA = sourcePack("a2", (byte) 8);
            CompletionException refused = assertThrows(CompletionException.class,
                    () -> driver.updatePack(PACK_A.toString(), newA.toString(), null).join());
            assertEquals(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS, reasonOf(refused));

            FsDeviceDiagnosis d = driver.diagnose().join();
            assertEquals(InterruptedUpdateState.COPY_INTERRUPTED, d.getInterruptedUpdates().get(0).getState());
            assertTrue(driver.resolveInterruptedUpdate(hex).join());
            assertFalse(Files.exists(leftover));
            assertTrue(driver.packContentMatches(PACK_A.toString(), oldA.toString()).join());
            assertEquals(0, driver.diagnose().join().problemCount());
        }

        @Test
        @DisplayName("the previous version could not be removed: the pack is updated, the leftover is cleaned up later")
        void interruptedDuringCleanup() throws IOException {
            SeamDriver driver = seamDriver();
            upload(driver, PACK_A, "a", (byte) 1);
            Path newA = sourcePack("a2", (byte) 8);
            driver.failRemove = true;

            driver.updatePack(PACK_A.toString(), newA.toString(), null).join();

            assertTrue(driver.packContentMatches(PACK_A.toString(), newA.toString()).join());
            FsDeviceDiagnosis d = driver.diagnose().join();
            assertEquals(InterruptedUpdateState.CLEANUP_INTERRUPTED, d.getInterruptedUpdates().get(0).getState());
            driver.failRemove = false;
            assertTrue(driver.resolveInterruptedUpdate(FsDeviceMaintenance.folderNameOf(PACK_A)).join());
            assertEquals(0, driver.diagnose().join().problemCount());
            assertTrue(driver.packContentMatches(PACK_A.toString(), newA.toString()).join());
        }

        @Test
        @DisplayName("only the previous version left: restoring it puts the pack back")
        void previousOnly() throws IOException {
            FsStoryTellerAsyncDriver driver = driver();
            Path oldA = upload(driver, PACK_A, "a", (byte) 1);
            String hex = FsDeviceMaintenance.folderNameOf(PACK_A);
            Files.move(folderOf(PACK_A), partition.resolve(".content").resolve(".studio-old-" + hex));
            assertEquals(InterruptedUpdateState.PREVIOUS_ONLY, driver.diagnose().join().getInterruptedUpdates().get(0).getState());
            assertTrue(driver.resolveInterruptedUpdate(hex).join());
            assertTrue(driver.packContentMatches(PACK_A.toString(), oldA.toString()).join());
        }
    }

    // ------------------------------------------------------------------ integrity

    @Nested
    @DisplayName("integrity check")
    class Integrity {

        private FsStoryTellerAsyncDriver driver;

        @BeforeEach
        void uploadOne() throws IOException {
            driver = driver();
            upload(driver, PACK_A, "a", (byte) 1);
        }

        private FsPackIntegrity check() {
            List<FsPackIntegrity> results = driver.checkIntegrity(null).join();
            assertEquals(1, results.size());
            return results.get(0);
        }

        /** Rewrites a device file as an upload would write {@code clear} on this V3 Lunii. */
        private void writeCiphered(String name, byte[] clear) throws IOException {
            Files.write(folderOf(PACK_A).resolve(name), CipherUtils.cipherFirstBlockSpecificKeyV3(clear, infos(driver).getDeviceKeyV3()));
        }

        @Test
        @DisplayName("a healthy pack is OK, and the check reports its progress")
        void healthy() {
            AtomicInteger last = new AtomicInteger();
            List<FsPackIntegrity> results = driver.checkIntegrity((checked, total) -> last.set(checked)).join();
            FsPackIntegrity r = results.get(0);
            assertTrue(r.isOk(), () -> r.getProblems().toString());
            assertEquals(2, r.getStageNodes());
            assertEquals(1, r.getImages());
            assertEquals(1, r.getSounds());
            assertTrue(last.get() > 0);
        }

        @Test
        @DisplayName("a missing sf file")
        void missingSound() throws IOException {
            Files.delete(folderOf(PACK_A).resolve("sf").resolve("000").resolve("00000001"));
            FsPackIntegrity r = check();
            assertTrue(r.has(ProblemCode.ASSET_MISSING), () -> r.getProblems().toString());
        }

        @Test
        @DisplayName("an empty rf file")
        void emptyImage() throws IOException {
            Files.write(folderOf(PACK_A).resolve("rf").resolve("000").resolve("00000001"), new byte[0]);
            FsPackIntegrity r = check();
            assertTrue(r.has(ProblemCode.ASSET_EMPTY), () -> r.getProblems().toString());
        }

        @Test
        @DisplayName("an si that does not match the node index")
        void inconsistentSi() throws IOException {
            writeCiphered("si", "000\\00000001000\\00000002".getBytes(StandardCharsets.US_ASCII));
            FsPackIntegrity r = check();
            assertTrue(r.has(ProblemCode.COUNT_MISMATCH), () -> r.getProblems().toString());
            assertTrue(r.has(ProblemCode.ASSET_MISSING), "the extra entry has no file");
        }

        @Test
        @DisplayName("a menu that leads to a node that does not exist")
        void transitionToMissingNode() throws IOException {
            writeCiphered("li", ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(5).putInt(0).array());
            FsPackIntegrity r = check();
            assertTrue(r.has(ProblemCode.TRANSITION_OUT_OF_RANGE), () -> r.getProblems().toString());
        }

        @Test
        @DisplayName("ciphered headers that do not decipher with the key of this Lunii")
        void invalidCipheredHeader() throws IOException {
            Path image = folderOf(PACK_A).resolve("rf").resolve("000").resolve("00000001");
            byte[] bytes = Files.readAllBytes(image);
            for (int i = 0; i < 16; i++) {
                bytes[i] ^= 0x5A;
            }
            Files.write(image, bytes);
            FsPackIntegrity r = check();
            assertTrue(r.has(ProblemCode.ASSET_HEADER_INVALID), () -> r.getProblems().toString());

            byte[] ri = Files.readAllBytes(folderOf(PACK_A).resolve("ri"));
            ri[0] ^= 0x33;
            Files.write(folderOf(PACK_A).resolve("ri"), ri);
            FsPackIntegrity r2 = check();
            assertTrue(r2.has(ProblemCode.ASSET_INDEX_INVALID), () -> r2.getProblems().toString());
        }

        @Test
        @DisplayName("a boot file made for another Lunii, and a missing pack folder")
        void bootFileAndMissingFolder() throws IOException {
            Files.write(folderOf(PACK_A).resolve("bt"), new byte[32]);
            assertTrue(check().has(ProblemCode.BOOT_FILE_INVALID));
            writeIndex(index(), PACK_A, PACK_B);
            List<FsPackIntegrity> results = driver.checkIntegrity(null).join();
            assertNotNull(results.get(1));
            assertTrue(results.get(1).has(ProblemCode.FOLDER_MISSING));
        }

        @Test
        @DisplayName("the check writes nothing")
        void readOnly() throws IOException {
            long before = Files.walk(partition).count();
            byte[] indexBefore = Files.readAllBytes(index());
            check();
            assertEquals(before, Files.walk(partition).count());
            assertArrayEquals(indexBefore, Files.readAllBytes(index()));
        }
    }
}
