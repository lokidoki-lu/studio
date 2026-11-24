/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Requests that written data be pushed from the OS cache to the storage device.
 *
 * <p>As in {@link TemporaryFilePackIndexWriter}, {@code force(true)} is a <strong>request</strong>:
 * the card's controller may acknowledge before the bytes reach the NAND, and nothing here can
 * observe that. What it does rule out is installing an index that points at files the OS has not
 * even started to write.
 */
final class FsSync {

    private static final Logger LOGGER = Logger.getLogger(FsSync.class.getName());

    private FsSync() {
    }

    /** Forces a regular file's data and metadata. A failure is reported: the file may not be on the card. */
    static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /** Forces a directory. Not supported everywhere — notably not on Windows — hence a separate call. */
    static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    /** {@link #forceDirectory}, ignoring a platform that does not support it. */
    static void forceDirectoryBestEffort(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            forceDirectory(directory);
        } catch (IOException | RuntimeException unsupported) {
            LOGGER.log(Level.FINEST, "Could not force directory " + directory, unsupported);
        }
    }

    /** Every directory under {@code root}, deepest first, then {@code root} itself; best effort. */
    static void forceDirectoryTreeBestEffort(Path root) {
        List<Path> directories = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isDirectory).forEach(directories::add);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINEST, "Could not list directories under " + root, e);
            return;
        }
        Collections.reverse(directories);
        directories.forEach(FsSync::forceDirectoryBestEffort);
    }
}
