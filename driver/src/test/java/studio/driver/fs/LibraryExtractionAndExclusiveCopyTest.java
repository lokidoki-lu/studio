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
import studio.driver.event.TransferProgressListener;
import studio.driver.model.TransferStatus;
import studio.driver.model.fs.FsDeviceInfos;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Extraction of a pack into the library through a staging folder, and the exclusive writes of the
 * copy itself.
 */
class LibraryExtractionAndExclusiveCopyTest {

    @TempDir
    Path partition;

    @TempDir
    Path sources;

    @TempDir
    Path library;

    private static final UUID PACK = UUID.fromString("66666666-7777-8888-9999-aaaaccccdddd");

    private FsStoryTellerAsyncDriver driver;

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

    private Path sourcePack(String name) throws IOException {
        Path pack = sources.resolve(name);
        Files.createDirectories(pack);
        Files.write(pack.resolve(".cleartext"), new byte[0]);
        Files.write(pack.resolve("ni"), filler(200, 'n'));
        // Whole AES blocks: V3 pads a ciphered block with zeros, and keeps the padding on the way back.
        Files.write(pack.resolve("ri"), filler(96, 'r'));
        Files.write(pack.resolve("si"), filler(48, 's'));
        Files.write(pack.resolve("li"), filler(32, 'l'));
        return pack;
    }

    private static byte[] filler(int length, char seed) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) seed);
        return data;
    }

    private Path deviceFolder() {
        return partition.resolve(".content").resolve(driver.computePackFolderName(PACK.toString()));
    }

    private List<String> libraryEntries() throws IOException {
        try (Stream<Path> entries = Files.list(library)) {
            return entries.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private static List<String> fileNames(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }

    private static boolean hasCause(Throwable thrown, Class<? extends Throwable> type) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /** Runs the private copy directly, with a chosen session and destination. */
    private TransferStatus copyPackFolder(FsStoryTellerAsyncDriver.PartitionSession session, Path source, Path destination,
                                          boolean isUpload) throws Throwable {
        FsDeviceInfos infos = driver.getDeviceInfos().join();
        Method copy = FsStoryTellerAsyncDriver.class.getDeclaredMethod("copyPackFolder",
                FsStoryTellerAsyncDriver.PartitionSession.class, String.class, File.class, FsDeviceInfos.class,
                boolean.class, TransferProgressListener.class);
        copy.setAccessible(true);
        try {
            return (TransferStatus) copy.invoke(driver, session, source.toString(), destination.toFile(), infos, isUpload, null);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @BeforeEach
    void putAPackOnTheDevice() throws IOException {
        writeDeviceMetadataV7();
        Files.write(partition.resolve(".pi"), new byte[0]);
        driver = DriverTestSupport.pluggedDriverMountedOn(partition);
        driver.uploadPack(PACK.toString(), sourcePack("pack").toString(), null).join();
    }

    /* ================================================================== 1: staging */

    @Nested
    @DisplayName("extracting a pack into the library")
    class Extraction {

        @Test
        @DisplayName("lands as <library>/<uuid>, complete, with no staging folder left")
        void nominalExtraction() throws IOException {
            AtomicBoolean destinationExistedAtCompletion = new AtomicBoolean(false);
            TransferProgressListener listener = new TransferProgressListener() {
                @Override
                public void onProgress(TransferStatus status) {
                }

                @Override
                public void onComplete(TransferStatus status) {
                    destinationExistedAtCompletion.set(Files.isDirectory(library.resolve(PACK.toString())));
                }
            };

            driver.downloadPack(PACK.toString(), library.toString(), listener).join();

            assertEquals(List.of(PACK.toString()), libraryEntries(), "only the pack, no staging folder");
            assertEquals(List.of(".cleartext", "bt", "li", "ni", "ri", "si"), fileNames(library.resolve(PACK.toString())));
            assertArrayEquals(filler(96, 'r'), Files.readAllBytes(library.resolve(PACK.toString()).resolve("ri")),
                    "the assets are deciphered back to what was uploaded");
            assertTrue(destinationExistedAtCompletion.get(), "completion is reported once the pack is in place");
        }

        @Test
        @DisplayName("a failure during the copy leaves neither the pack nor a staging folder")
        void failureMidCopyLeavesNothing() throws IOException {
            // Shorter than the AES block it is deciphered into: the decipher overflows, inside the walk.
            Files.write(deviceFolder().resolve("ri"), filler(5, 'X'));

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.downloadPack(PACK.toString(), library.toString(), null).join());

            assertTrue(hasCause(raised, java.nio.BufferOverflowException.class), "got: " + raised);
            assertEquals(List.of(), libraryEntries(), "the library is left exactly as it was");
        }

        @Test
        @DisplayName("an unsupported firmware is refused without leaving a staging folder")
        void unsupportedFirmwareLeavesNothing() throws IOException {
            byte[] md = new byte[512];
            md[0] = 1;   // metadata format 1, firmware 0.0
            Files.write(partition.resolve(".md"), md);

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.downloadPack(PACK.toString(), library.toString(), null).join());

            assertTrue(unwrap(raised).getMessage().contains("unsupported firmware"), "got: " + raised);
            assertEquals(List.of(), libraryEntries());
        }

        @Test
        @DisplayName("an existing <uuid> folder is refused and left untouched")
        void existingDestinationIsRefused() throws IOException {
            Path existing = library.resolve(PACK.toString());
            Files.createDirectory(existing);
            Files.write(existing.resolve("ni"), filler(7, 'Z'));

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.downloadPack(PACK.toString(), library.toString(), null).join());

            assertEquals(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS, StoryTellerException.reasonOf(raised));
            assertEquals(List.of("ni"), fileNames(existing), "nothing was written into it");
            assertArrayEquals(filler(7, 'Z'), Files.readAllBytes(existing.resolve("ni")));
            assertEquals(List.of(PACK.toString()), libraryEntries(), "and no staging folder is left");
        }

        @Test
        @DisplayName("an existing empty <uuid> folder is refused too, not replaced by the rename")
        void existingEmptyDestinationIsRefused() throws IOException {
            Path existing = library.resolve(PACK.toString());
            Files.createDirectory(existing);

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.downloadPack(PACK.toString(), library.toString(), null).join());

            assertEquals(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS, StoryTellerException.reasonOf(raised));
            assertEquals(List.of(), fileNames(existing));
            assertEquals(List.of(PACK.toString()), libraryEntries());
        }

        @Test
        @DisplayName("a link at <uuid> is refused, neither followed nor replaced, even when dangling")
        void linkAtDestinationIsRefused() throws IOException {
            Path target = sources.resolve("elsewhere");
            Files.createDirectory(target);
            Path link = library.resolve(PACK.toString());
            try {
                Files.createSymbolicLink(link, target);
            } catch (UnsupportedOperationException | IOException e) {
                assumeTrue(false, "symbolic links are not available here: " + e);
            }
            Files.delete(target);   // dangling: Files.exists without NOFOLLOW_LINKS would say "absent"

            CompletionException raised = assertThrows(CompletionException.class,
                    () -> driver.downloadPack(PACK.toString(), library.toString(), null).join());

            assertEquals(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS, StoryTellerException.reasonOf(raised));
            assertTrue(Files.isSymbolicLink(link), "the link is left as it was");
            assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS), "and nothing was created through it");
            assertEquals(List.of(PACK.toString()), libraryEntries());
        }

        @Test
        @DisplayName("a pack extracted from the device, bt included, uploads again")
        void extractedPackUploadsAgain() throws IOException {
            driver.downloadPack(PACK.toString(), library.toString(), null).join();
            Path extracted = library.resolve(PACK.toString());
            assertTrue(Files.exists(extracted.resolve("bt")), "precondition: the extracted pack carries a bt");
            UUID other = UUID.fromString("00000000-0000-0000-0000-000011112222");

            driver.uploadPack(other.toString(), extracted.toString(), null).join();

            Path onDevice = partition.resolve(".content").resolve(driver.computePackFolderName(other.toString()));
            assertEquals(List.of("bt", "li", "ni", "ri", "si"), fileNames(onDevice));
            assertArrayEquals(Files.readAllBytes(deviceFolder().resolve("bt")), Files.readAllBytes(onDevice.resolve("bt")),
                    "the bt is the one generated for the device, not the copied one");
        }
    }

    /* ================================================================== 2: exclusive copy */

    @Nested
    @DisplayName("the copy writes every file exclusively")
    class ExclusiveCopy {

        @Test
        @DisplayName("a ciphered file already in the destination is not truncated: the copy fails")
        void existingCipheredFileIsNotOverwritten() throws Throwable {
            Path destination = Files.createDirectory(partition.resolve("dest"));
            Files.write(destination.resolve("si"), filler(9, 'Q'));

            Throwable raised = assertThrows(Throwable.class,
                    () -> copyPackFolder(driver.currentPartition(), sourcePack("again"), destination, true));

            assertTrue(hasCause(raised, FileAlreadyExistsException.class), "got: " + raised);
            assertArrayEquals(filler(9, 'Q'), Files.readAllBytes(destination.resolve("si")));
        }

        @Test
        @DisplayName("a bt already in the destination is not replaced: the copy fails")
        void existingBootFileIsNotOverwritten() throws Throwable {
            Path destination = Files.createDirectory(partition.resolve("dest"));
            Files.write(destination.resolve("bt"), filler(3, 'B'));

            Throwable raised = assertThrows(Throwable.class,
                    () -> copyPackFolder(driver.currentPartition(), sourcePack("again"), destination, true));

            assertTrue(hasCause(raised, FileAlreadyExistsException.class), "got: " + raised);
            assertArrayEquals(filler(3, 'B'), Files.readAllBytes(destination.resolve("bt")));
        }

        @Test
        @DisplayName("a session that is no longer current writes no file at all")
        void staleSessionWritesNothing() throws Throwable {
            Path destination = Files.createDirectory(partition.resolve("dest"));
            FsStoryTellerAsyncDriver.PartitionSession current = driver.currentPartition();
            FsStoryTellerAsyncDriver.PartitionSession stale =
                    new FsStoryTellerAsyncDriver.PartitionSession(current.mountPoint, current.generation - 1);

            Throwable raised = assertThrows(Throwable.class,
                    () -> copyPackFolder(stale, sourcePack("again"), destination, true));

            assertEquals(StoryTellerException.Reason.NO_DEVICE, StoryTellerException.reasonOf(raised), "got: " + raised);
            assertEquals(List.of(), fileNames(destination), "not a single file was written");
        }
    }
}
