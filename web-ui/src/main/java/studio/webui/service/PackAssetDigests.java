/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import studio.core.v1.model.Asset;
import studio.core.v1.model.StageNode;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.core.v1.writer.fs.FsStoryPackWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * SHA-256 digests of the image and audio assets of a pack: what {@link DeviceSourcedPackStore}
 * keeps of a pack that may come from a Lunii, so that an archive holding its content under another
 * UUID (the editor renews UUIDs) is recognised when it enters the library.
 *
 * <p>Taken only when a pack is marked or an archive enters the library, never at each check. The
 * archive's assets and the FS pack's (cleartext) assets are the same bytes when one is converted
 * from the other, which is what is compared. Raw packs are not read: their assets are re-encoded by
 * any conversion, which is digested then.
 *
 * <p>Trivial assets are left out, so that two packs of the user's own never meet through them: the
 * silent sound the FS writer puts on nodes without audio, and anything under {@link #MIN_SIZE}.
 */
final class PackAssetDigests {

    /** Assets smaller than this (a blank image, a click) are too generic to tell packs apart. */
    static final int MIN_SIZE = 2048;

    private static final String BLANK_AUDIO = digest(FsStoryPackWriter.blankAudio());

    private PackAssetDigests() {
    }

    /** The digests of the assets of a library entry: an archive or an FS folder; empty otherwise. */
    static Set<String> of(Path entry) throws IOException {
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            return ofFsFolder(entry);
        }
        if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && entry.getFileName().toString().endsWith(".zip")) {
            return ofArchive(entry);
        }
        return new HashSet<>();
    }

    static Set<String> ofArchive(Path zip) throws IOException {
        Set<String> digests = new HashSet<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.toLowerCase().startsWith("assets/")) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    add(digests, in);
                }
            }
        }
        return digests;
    }

    static Set<String> ofFsFolder(Path folder) throws IOException {
        Set<String> digests = new HashSet<>();
        FsStoryPackReader.OpenedFsPack pack = new FsStoryPackReader().open(folder);
        Set<String> seen = new HashSet<>();
        for (StageNode node : pack.getPack().getStageNodes()) {
            Asset image = node.getImage();
            if (image != null && seen.add("i" + image.getName())) {
                try (InputStream in = pack.openImage(image)) {
                    add(digests, in);
                }
            }
            Asset audio = node.getAudio();
            if (audio != null && seen.add("a" + audio.getName())) {
                try (InputStream in = pack.openAudio(audio)) {
                    add(digests, in);
                }
            }
        }
        return digests;
    }

    private static void add(Set<String> digests, InputStream in) throws IOException {
        MessageDigest md = newDigest();
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            md.update(buffer, 0, n);
            size += n;
        }
        String digest = hex(md.digest());
        if (size >= MIN_SIZE && !digest.equals(BLANK_AUDIO)) {
            digests.add(digest);
        }
    }

    static String digest(byte[] bytes) {
        return hex(newDigest().digest(bytes));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
