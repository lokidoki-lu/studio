/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.driver.StoryTellerException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Specification of {@link FsStoryTellerAsyncDriver#removeUnreferencedPackContentFolder(String)}: it
 * removes exactly the named folder, and only when the index does not reference it.
 */
class UnreferencedPackFolderRemovalTest {

    @TempDir
    Path partition;

    private static final UUID INDEXED = UUID.fromString("11111111-2222-3333-4444-5555aaaabbbb");

    private void writeIndex(UUID... uuids) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(uuids.length * 16);
        for (UUID uuid : uuids) {
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
        }
        Files.write(partition.resolve(".pi"), buffer.array());
    }

    private Path folder(String name) throws IOException {
        Path folder = partition.resolve(".content").resolve(name);
        Files.createDirectories(folder.resolve("rf"));
        Files.write(folder.resolve("ni"), ("content of " + name).getBytes(StandardCharsets.US_ASCII));
        Files.write(folder.resolve("rf").resolve("000"), new byte[]{1, 2, 3});
        return folder;
    }

    private static StoryTellerException.Reason reasonOf(Runnable action) {
        CompletionException thrown = assertThrows(CompletionException.class, action::run);
        return StoryTellerException.reasonOf(thrown);
    }

    @Test
    @DisplayName("an unreferenced folder is removed, with everything in it, and nothing else is touched")
    void removesTheUnreferencedFolder() throws IOException {
        FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
        writeIndex(INDEXED);
        Path kept = folder(driver.computePackFolderName(INDEXED.toString()));
        Path orphan = folder("0A0B0C0D");
        byte[] indexBefore = Files.readAllBytes(partition.resolve(".pi"));

        assertTrue(driver.removeUnreferencedPackContentFolder("0A0B0C0D").join());

        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(kept.resolve("ni")));
        assertArrayEquals(indexBefore, Files.readAllBytes(partition.resolve(".pi")));
        assertEquals(List.of(), driver.getUnreferencedPackContentFolders().join());
    }

    @Test
    @DisplayName("the name is matched without regard to case, as FAT32 does")
    void matchesWithoutCase() throws IOException {
        FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
        writeIndex();
        Path orphan = folder("0A0B0C0D");

        assertTrue(driver.removeUnreferencedPackContentFolder("0a0b0c0d").join());
        assertFalse(Files.exists(orphan));
    }

    @Test
    @DisplayName("a folder the index references is refused and left intact")
    void refusesAReferencedFolder() throws IOException {
        FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
        writeIndex(INDEXED);
        String name = driver.computePackFolderName(INDEXED.toString());
        Path kept = folder(name);

        assertEquals(StoryTellerException.Reason.FOLDER_STILL_REFERENCED,
                reasonOf(() -> driver.removeUnreferencedPackContentFolder(name.toLowerCase()).join()));
        assertTrue(Files.exists(kept.resolve("rf").resolve("000")));
    }

    @Test
    @DisplayName("a name that is not a pack folder name is refused before anything is read")
    void refusesAnythingButAPackFolderName() throws IOException {
        FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
        writeIndex();
        folder("0A0B0C0D");
        Path outside = partition.resolve("keep.txt");
        Files.write(outside, new byte[]{42});

        for (String bad : new String[]{"..", "../keep.txt", "0A0B0C0", "0A0B0C0D0", "ZZZZZZZZ", "", null}) {
            assertThrows(CompletionException.class, () -> driver.removeUnreferencedPackContentFolder(bad).join(),
                    "should refuse " + bad);
        }
        assertTrue(Files.exists(outside));
        assertTrue(Files.exists(partition.resolve(".content").resolve("0A0B0C0D")));
    }

    @Test
    @DisplayName("a folder that is not there is reported, not silently accepted")
    void reportsAMissingFolder() throws IOException {
        FsStoryTellerAsyncDriver driver = DriverTestSupport.pluggedDriverMountedOn(partition);
        writeIndex();

        assertEquals(StoryTellerException.Reason.PACK_NOT_ON_DEVICE,
                reasonOf(() -> driver.removeUnreferencedPackContentFolder("0A0B0C0D").join()));
    }

    @Test
    @DisplayName("without a device, nothing is attempted")
    void refusesWithoutADevice() {
        FsStoryTellerAsyncDriver driver = DriverTestSupport.driverMountedOn(partition);

        assertEquals(StoryTellerException.Reason.NO_DEVICE,
                reasonOf(() -> driver.removeUnreferencedPackContentFolder("0A0B0C0D").join()));
    }
}
