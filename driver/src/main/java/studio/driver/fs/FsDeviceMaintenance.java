/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.driver.model.fs.FsDeviceDiagnosis;
import studio.driver.model.fs.FsDeviceDiagnosis.InterruptedUpdateState;
import studio.driver.model.fs.FsDeviceDiagnosis.PendingIndexKind;
import studio.driver.model.fs.FsDeviceInfos;
import studio.driver.model.fs.FsPackIntegrity;
import studio.driver.model.fs.FsPackIntegrity.ProblemCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The read-only half of device maintenance: what is wrong with the bookkeeping of a firmware 2.x /
 * 3.x partition, and whether the packs it holds are whole. Everything here reads; nothing writes,
 * creates or removes anything on the card. The driver owns the partition session and the writes.
 */
final class FsDeviceMaintenance {

    /** A new copy of a pack being written by an in-place update, next to the pack folder. */
    static final String STAGING_NEW_PREFIX = ".studio-new-";
    /** The previous content of a pack, under this name between the two renames of an update. */
    static final String STAGING_OLD_PREFIX = ".studio-old-";

    static final Pattern PACK_FOLDER_NAME = Pattern.compile("[0-9A-Fa-f]{8}");

    /** Files every pack folder written by STUdio holds. The boot file is written last of all. */
    static final List<String> PACK_FILES = List.of(FsStoryPackReader.NODE_INDEX_FILENAME,
            FsStoryPackReader.IMAGE_INDEX_FILENAME, FsStoryPackReader.SOUND_INDEX_FILENAME,
            FsStoryPackReader.LIST_INDEX_FILENAME, CipherUtils.BOOT_FILENAME);

    private static final int ASSET_ENTRY_SIZE = 12;
    private static final Pattern ASSET_PATH = Pattern.compile("[A-Za-z0-9_-]+[\\\\/][A-Za-z0-9_-]+");
    private static final int HEADER_BLOCK = 512;

    private FsDeviceMaintenance() {
    }

    /** Whether every file STUdio writes is there. A copy cut short never has its boot file. */
    static boolean isCompletePackFolder(Path folder) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        for (String name : PACK_FILES) {
            if (!Files.isRegularFile(folder.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
        }
        return true;
    }

    static String folderNameOf(UUID uuid) {
        String hex = uuid.toString().replace("-", "");
        return hex.substring(hex.length() - 8).toUpperCase(Locale.ROOT);
    }

    static List<UUID> parseIndex(byte[] bytes) {
        List<UUID> uuids = new ArrayList<>();
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        while (bb.remaining() >= 16) {
            uuids.add(new UUID(bb.getLong(), bb.getLong()));
        }
        return uuids;
    }

    // ------------------------------------------------------------------ diagnosis

    /**
     * Inspects the partition's bookkeeping.
     *
     * @param indexBytes the content of {@code .pi}, already validated by the caller
     */
    static FsDeviceDiagnosis diagnose(Path mountPoint, byte[] indexBytes) throws IOException {
        List<UUID> index = parseIndex(indexBytes);
        FsDeviceDiagnosis diagnosis = new FsDeviceDiagnosis();
        diagnosis.setIndexedPacks(index.size());
        Path content = mountPoint.resolve(".content");

        Map<String, UUID> referenced = new HashMap<>();
        for (UUID uuid : index) {
            referenced.putIfAbsent(folderNameOf(uuid), uuid);
        }

        // What .content holds, by upper-case name (FAT32 does not distinguish case).
        Map<String, Path> folders = new TreeMap<>();
        Map<String, List<String>> staging = new TreeMap<>();
        if (Files.isDirectory(content)) {
            try (Stream<Path> entries = Files.list(content)) {
                for (Path entry : (Iterable<Path>) entries::iterator) {
                    if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    String name = entry.getFileName().toString();
                    String hex = stagingHex(name);
                    if (hex != null) {
                        staging.computeIfAbsent(hex, k -> new ArrayList<>()).add(name);
                    } else {
                        folders.put(name.toUpperCase(Locale.ROOT), entry);
                    }
                }
            }
        }

        for (Map.Entry<String, Path> folder : folders.entrySet()) {
            if (!referenced.containsKey(folder.getKey()) && !staging.containsKey(folder.getKey())) {
                Path path = folder.getValue();
                diagnosis.getOrphans().add(new FsDeviceDiagnosis.OrphanFolder(path.getFileName().toString(),
                        FileUtils.getFolderSize(path.toString()), isCompletePackFolder(path)));
            }
        }

        Set<UUID> seen = new LinkedHashSet<>(index);
        for (UUID uuid : seen) {
            String name = folderNameOf(uuid);
            if (staging.containsKey(name)) {
                // Reported as an interrupted update below, with what to do about it.
                continue;
            }
            Path folder = folders.get(name);
            if (folder == null || !Files.isRegularFile(folder.resolve(FsStoryPackReader.NODE_INDEX_FILENAME))) {
                diagnosis.getMissingContent().add(new FsDeviceDiagnosis.MissingContent(uuid, name, folder != null));
            }
        }

        for (Map.Entry<String, List<String>> leftovers : staging.entrySet()) {
            String hex = leftovers.getKey();
            boolean hasNew = leftovers.getValue().stream().anyMatch(n -> n.startsWith(STAGING_NEW_PREFIX));
            boolean hasOld = leftovers.getValue().stream().anyMatch(n -> n.startsWith(STAGING_OLD_PREFIX));
            boolean hasFolder = folders.containsKey(hex);
            UUID uuid = referenced.get(hex);
            diagnosis.getInterruptedUpdates().add(new FsDeviceDiagnosis.InterruptedUpdate(hex, uuid,
                    interruptedState(uuid != null, hasFolder, hasNew, hasOld), leftovers.getValue()));
        }

        Path pending = mountPoint.resolve(".pi" + TemporaryFilePackIndexWriter.TEMPORARY_SUFFIX);
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            diagnosis.setPendingIndex(analysePendingIndex(indexBytes, Files.readAllBytes(pending), content));
        }
        return diagnosis;
    }

    /** The 8 hex characters a staging folder name ends with, or {@code null} if it is not one. */
    static String stagingHex(String name) {
        for (String prefix : List.of(STAGING_NEW_PREFIX, STAGING_OLD_PREFIX)) {
            if (name.regionMatches(true, 0, prefix, 0, prefix.length())) {
                String hex = name.substring(prefix.length());
                if (PACK_FOLDER_NAME.matcher(hex).matches()) {
                    return hex.toUpperCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    static InterruptedUpdateState interruptedState(boolean referenced, boolean hasFolder, boolean hasNew, boolean hasOld) {
        if (!referenced) {
            return InterruptedUpdateState.UNREFERENCED;
        }
        if (hasFolder) {
            return hasNew ? InterruptedUpdateState.COPY_INTERRUPTED : InterruptedUpdateState.CLEANUP_INTERRUPTED;
        }
        if (hasNew && hasOld) {
            return InterruptedUpdateState.SWAP_INTERRUPTED;
        }
        if (hasOld) {
            return InterruptedUpdateState.PREVIOUS_ONLY;
        }
        // Only a new copy and no pack folder: the copy cannot have been completed and swapped, since
        // the swap moves the previous folder aside first. Treated like an interrupted copy whose
        // pack folder had gone missing beforehand: the leftover is removed, nothing is promoted.
        return InterruptedUpdateState.COPY_INTERRUPTED;
    }

    /**
     * Classifies a {@code .pi.new} against the installed {@code .pi}.
     *
     * <p>Safe to discard: torn (the move onto {@code .pi} never happened, so {@code .pi} is what the
     * interrupted operation left), identical, or {@code .pi} plus packs whose content never finished
     * copying. Everything else is a real choice between two indexes, which only the user can make.
     */
    static FsDeviceDiagnosis.PendingIndex analysePendingIndex(byte[] currentBytes, byte[] pendingBytes, Path content) {
        String fingerprint = fingerprint(currentBytes, pendingBytes);
        List<UUID> current = parseIndex(currentBytes);
        if (pendingBytes.length % 16 != 0) {
            return new FsDeviceDiagnosis.PendingIndex(PendingIndexKind.TORN, true, fingerprint, current.size(), -1,
                    List.of(), List.of());
        }
        List<UUID> pending = parseIndex(pendingBytes);
        List<UUID> added = new ArrayList<>(new LinkedHashSet<>(pending));
        added.removeAll(current);
        List<UUID> removed = new ArrayList<>(new LinkedHashSet<>(current));
        removed.removeAll(pending);

        PendingIndexKind kind;
        boolean safe;
        if (pending.equals(current)) {
            kind = PendingIndexKind.IDENTICAL;
            safe = true;
        } else if (added.isEmpty() && removed.isEmpty()) {
            kind = PendingIndexKind.REORDER;
            safe = false;
        } else if (removed.isEmpty() && pending.size() > current.size()
                && pending.subList(0, current.size()).equals(current)) {
            boolean allIncomplete = added.stream()
                    .allMatch(u -> !isCompletePackFolder(content.resolve(folderNameOf(u))));
            kind = allIncomplete ? PendingIndexKind.ADD_INCOMPLETE : PendingIndexKind.ADD;
            safe = allIncomplete;
        } else if (added.isEmpty() && isOrderedSubsequence(pending, current)) {
            kind = PendingIndexKind.REMOVE;
            safe = false;
        } else {
            kind = PendingIndexKind.OTHER;
            safe = false;
        }
        return new FsDeviceDiagnosis.PendingIndex(kind, safe, fingerprint, current.size(), pending.size(), added, removed);
    }

    private static boolean isOrderedSubsequence(List<UUID> sub, List<UUID> list) {
        int i = 0;
        for (UUID u : list) {
            if (i < sub.size() && sub.get(i).equals(u)) {
                i++;
            }
        }
        return i == sub.size();
    }

    static String fingerprint(byte[] current, byte[] pending) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(8).putLong(current.length).array());
            digest.update(current);
            digest.update(pending);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ integrity

    /** How many files {@link #checkPack} will look at, for the progress of a whole-device check. */
    static int filesToCheck(Path folder) {
        // The index files plus one per asset; a cheap upper bound, read from the directory listing.
        return PACK_FILES.size() + countFiles(folder.resolve("rf")) + countFiles(folder.resolve("sf"));
    }

    private static int countFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return (int) files.filter(Files::isRegularFile).count();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * Checks one pack, reading only its index files in full and the first block of each asset.
     *
     * @param progress told about every file looked at (may be {@code null})
     */
    static FsPackIntegrity checkPack(UUID uuid, Path folder, FsDeviceInfos infos, IntConsumer progress) {
        FsPackIntegrity result = new FsPackIntegrity(uuid, folder.getFileName().toString());
        int[] checked = {0};
        IntConsumer tick = n -> {
            checked[0] += n;
            if (progress != null) {
                progress.accept(n);
            }
        };
        try {
            check(result, folder, infos, tick);
        } catch (RuntimeException e) {
            result.add(ProblemCode.FILE_UNREADABLE, String.valueOf(e.getMessage()));
        }
        result.setFilesChecked(checked[0]);
        return result;
    }

    private static void check(FsPackIntegrity result, Path folder, FsDeviceInfos infos, IntConsumer tick) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            result.add(ProblemCode.FOLDER_MISSING, folder.getFileName().toString());
            return;
        }
        Map<String, byte[]> raw = new HashMap<>();
        for (String name : PACK_FILES) {
            Path file = folder.resolve(name);
            tick.accept(1);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                result.add(ProblemCode.FILE_MISSING, name);
                continue;
            }
            try {
                raw.put(name, Files.readAllBytes(file));
            } catch (IOException e) {
                result.add(ProblemCode.FILE_UNREADABLE, name + ": " + e.getMessage());
            }
        }
        byte[] ni = raw.get(FsStoryPackReader.NODE_INDEX_FILENAME);
        byte[] riRaw = raw.get(FsStoryPackReader.IMAGE_INDEX_FILENAME);
        byte[] siRaw = raw.get(FsStoryPackReader.SOUND_INDEX_FILENAME);
        byte[] liRaw = raw.get(FsStoryPackReader.LIST_INDEX_FILENAME);
        byte[] bt = raw.get(CipherUtils.BOOT_FILENAME);

        // Assets stored in clear on the card are accepted, as the reader does; otherwise the first
        // block deciphers with the key of this Lunii.
        boolean cleartext = riRaw != null && riRaw.length >= FsStoryPackReader.CLEARTEXT_RI_BEGINNING.length
                && Arrays.equals(riRaw, 0, FsStoryPackReader.CLEARTEXT_RI_BEGINNING.length,
                FsStoryPackReader.CLEARTEXT_RI_BEGINNING, 0, FsStoryPackReader.CLEARTEXT_RI_BEGINNING.length);
        List<String> images = riRaw == null ? null
                : assetEntries(result, FsStoryPackReader.IMAGE_INDEX_FILENAME, decipher(riRaw, cleartext, infos));
        List<String> sounds = siRaw == null ? null
                : assetEntries(result, FsStoryPackReader.SOUND_INDEX_FILENAME, decipher(siRaw, cleartext, infos));
        // Not trimmed: a menu may legitimately end with an entry for node 0, and the zeros a V3 cipher
        // pads with read as node 0, which exists. Only the bounds are checked.
        byte[] li = liRaw == null ? null : decipher(liRaw, cleartext, infos);
        if (li != null && li.length % 4 != 0) {
            result.add(ProblemCode.LIST_INDEX_INVALID, "li: " + li.length + " bytes is not a whole number of entries");
            li = null;
        }
        if (images != null) {
            result.setImages(images.size());
        }
        if (sounds != null) {
            result.setSounds(sounds.size());
        }

        if (ni != null) {
            checkNodes(result, ni, images, sounds, li);
        }
        if (images != null) {
            checkAssets(result, folder, "rf", images, true, cleartext, infos, tick);
        }
        if (sounds != null) {
            checkAssets(result, folder, "sf", sounds, false, cleartext, infos, tick);
        }
        if (bt != null && riRaw != null) {
            checkBootFile(result, bt, riRaw, infos);
        }
    }

    private static byte[] decipher(byte[] data, boolean cleartext, FsDeviceInfos infos) {
        if (cleartext || data.length == 0) {
            return data;
        }
        if (infos.getFirmwareMajor() == 2) {
            return CipherUtils.decipherFirstBlockCommonKey(data);
        }
        return CipherUtils.decipherFirstBlockSpecificKeyV3(data, infos.getDeviceKeyV3());
    }

    /** Drops the zero bytes a V3 cipher pads its first block with, in whole entries. */
    private static byte[] trimZeroPadding(byte[] data, int entrySize) {
        int end = data.length;
        while (end >= entrySize && isZero(data, end - entrySize, end)) {
            end -= entrySize;
        }
        // A partial trailing entry made of zeros is padding too.
        int partial = end % entrySize;
        if (partial != 0 && isZero(data, end - partial, end)) {
            end -= partial;
        }
        return end == data.length ? data : Arrays.copyOf(data, end);
    }

    private static boolean isZero(byte[] data, int from, int to) {
        for (int i = from; i < to; i++) {
            if (data[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static List<String> assetEntries(FsPackIntegrity result, String indexName, byte[] deciphered) {
        byte[] content = trimZeroPadding(deciphered, ASSET_ENTRY_SIZE);
        if (content.length % ASSET_ENTRY_SIZE != 0) {
            result.add(ProblemCode.ASSET_INDEX_INVALID, indexName + ": " + content.length
                    + " bytes is not a whole number of entries");
            return null;
        }
        List<String> entries = new ArrayList<>();
        for (int offset = 0; offset < content.length; offset += ASSET_ENTRY_SIZE) {
            String path = new String(content, offset, ASSET_ENTRY_SIZE, StandardCharsets.UTF_8);
            if (!ASSET_PATH.matcher(path).matches()) {
                result.add(ProblemCode.ASSET_INDEX_INVALID, indexName + ": entry " + (offset / ASSET_ENTRY_SIZE)
                        + (offset == 0 ? " does not decipher with the key of this Lunii" : " is not an asset path"));
                return null;
            }
            entries.add(path);
        }
        return entries;
    }

    private static void checkNodes(FsPackIntegrity result, byte[] ni, List<String> images, List<String> sounds, byte[] li) {
        if (ni.length < 25) {
            result.add(ProblemCode.NODE_INDEX_INVALID, "ni: " + ni.length + " bytes, too short for its header");
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(ni).order(ByteOrder.LITTLE_ENDIAN);
        int nodeSize = bb.getInt(8);
        int nodeCount = bb.getInt(12);
        int imageCount = bb.getInt(16);
        int soundCount = bb.getInt(20);
        if (nodeCount <= 0 || nodeSize < 42) {
            result.add(ProblemCode.NODE_INDEX_INVALID, "ni: " + nodeCount + " stage nodes of " + nodeSize + " bytes");
            return;
        }
        if ((long) HEADER_BLOCK + (long) nodeCount * nodeSize > ni.length) {
            result.add(ProblemCode.NODE_INDEX_INVALID, "ni: " + ni.length + " bytes, too short for " + nodeCount
                    + " stage nodes");
            return;
        }
        result.setStageNodes(nodeCount);
        if (images != null && imageCount != images.size()) {
            result.add(ProblemCode.COUNT_MISMATCH, "ni announces " + imageCount + " images, ri lists " + images.size());
        }
        if (sounds != null && soundCount != sounds.size()) {
            result.add(ProblemCode.COUNT_MISMATCH, "ni announces " + soundCount + " sounds, si lists " + sounds.size());
        }
        int liEntries = li == null ? -1 : li.length / 4;
        ByteBuffer liBb = li == null ? null : ByteBuffer.wrap(li).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < nodeCount; i++) {
            int base = HEADER_BLOCK + i * nodeSize;
            int image = bb.getInt(base);
            int sound = bb.getInt(base + 4);
            if (image != -1 && (image < 0 || (images != null && image >= images.size()))) {
                result.add(ProblemCode.ASSET_REFERENCE_OUT_OF_RANGE, "node " + i + ": image " + image);
            }
            if (sound != -1 && (sound < 0 || (sounds != null && sound >= sounds.size()))) {
                result.add(ProblemCode.ASSET_REFERENCE_OUT_OF_RANGE, "node " + i + ": sound " + sound);
            }
            checkTransition(result, "node " + i + " (ok)", bb.getInt(base + 8), bb.getInt(base + 12), bb.getInt(base + 16),
                    liEntries, liBb, nodeCount);
            checkTransition(result, "node " + i + " (home)", bb.getInt(base + 20), bb.getInt(base + 24), bb.getInt(base + 28),
                    liEntries, liBb, nodeCount);
        }
    }

    private static void checkTransition(FsPackIntegrity result, String where, int action, int options, int selected,
                                        int liEntries, ByteBuffer li, int nodeCount) {
        if (action == -1 && options == -1 && selected == -1) {
            return;
        }
        if (action < 0 || options <= 0 || selected < 0 || selected >= options) {
            result.add(ProblemCode.TRANSITION_OUT_OF_RANGE, where + ": menu " + action + ", option " + selected + " of " + options);
            return;
        }
        if (li == null) {
            return;
        }
        if ((long) action + options > liEntries) {
            result.add(ProblemCode.TRANSITION_OUT_OF_RANGE, where + ": menu " + action + " with " + options
                    + " option(s) does not fit in li (" + liEntries + " entries)");
            return;
        }
        for (int k = 0; k < options; k++) {
            int target = li.getInt((action + k) * 4);
            if (target < 0 || target >= nodeCount) {
                result.add(ProblemCode.TRANSITION_OUT_OF_RANGE, where + ": option " + k + " leads to node " + target
                        + ", which does not exist (" + nodeCount + " nodes)");
            }
        }
    }

    private static void checkAssets(FsPackIntegrity result, Path folder, String directory, List<String> entries,
                                    boolean image, boolean cleartext, FsDeviceInfos infos, IntConsumer tick) {
        Path root = folder.resolve(directory);
        for (String entry : entries) {
            tick.accept(1);
            String relative = directory + "/" + entry.replace('\\', '/');
            Path file = root.resolve(entry.replace('\\', '/'));
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                result.add(ProblemCode.ASSET_MISSING, relative);
                continue;
            }
            byte[] head;
            long size;
            try {
                size = Files.size(file);
                try (InputStream in = Files.newInputStream(file)) {
                    head = in.readNBytes(HEADER_BLOCK);
                }
            } catch (IOException e) {
                result.add(ProblemCode.FILE_UNREADABLE, relative + ": " + e.getMessage());
                continue;
            }
            if (size == 0) {
                result.add(ProblemCode.ASSET_EMPTY, relative);
                continue;
            }
            byte[] clear = decipher(head, cleartext, infos);
            if (!(image ? looksLikeBmp(clear) : looksLikeMp3(clear))) {
                result.add(ProblemCode.ASSET_HEADER_INVALID, relative);
            }
        }
    }

    static boolean looksLikeBmp(byte[] head) {
        return head.length >= 2 && head[0] == 'B' && head[1] == 'M';
    }

    static boolean looksLikeMp3(byte[] head) {
        if (head.length >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            return true;
        }
        return head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
    }

    private static void checkBootFile(FsPackIntegrity result, byte[] bt, byte[] riRaw, FsDeviceInfos infos) {
        byte[] expected;
        if (infos.getFirmwareMajor() == 2) {
            if (infos.getUuid() == null) {
                return;
            }
            expected = CipherUtils.expectedBootFileV2(riRaw, infos.getUuid());
        } else {
            if (infos.getDeviceKeyV3() == null) {
                return;
            }
            expected = infos.getDeviceKeyV3().getBt();
        }
        if (!Arrays.equals(bt, expected)) {
            result.add(ProblemCode.BOOT_FILE_INVALID, "bt does not match this Lunii");
        }
    }
}
