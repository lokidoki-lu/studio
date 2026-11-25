/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import studio.driver.fs.PackTransferSizeEstimator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The space a library pack will take on the device once in the device's format, estimated without
 * converting it: from the sizes of its files and the headers of its assets (as {@link PackDetails}
 * reads them), never by decoding audio.
 *
 * <ul>
 *     <li>FS (V2/V3): MP3 audio and 4-bit RLE BMP images, every file rounded up to the FAT32 cluster
 *     ({@link PackTransferSizeEstimator}, which the transfer's own preflight uses as well);</li>
 *     <li>raw (V1): 32 kHz 16-bit mono WAV (about 230 MB an hour) and 24-bit BMP images, every asset
 *     and node rounded up to the 512-byte sector.</li>
 * </ul>
 *
 * <p>Each figure says how it was obtained: {@code measured} (the pack is already in that format, or
 * all its sizes are known), {@code estimated} (some assets are to be converted, their size predicted
 * from their duration), {@code rough} (predicted from the pack's size alone). The predictions err on
 * the high side: refusing a transfer that would have fitted is better than running out of space part
 * way.
 */
final class DeviceSpaceEstimate {

    static final String MEASURED = "measured";
    static final String ESTIMATED = "estimated";
    static final String ROUGH = "rough";

    /** MP3 written by the conversion (LAME VBR, mono, 44.1 kHz), bounded at 128 kbps. */
    static final long MP3_BYTES_PER_SECOND = 16_000;
    /** WAV of a raw pack: 32 kHz, 16-bit, mono. */
    static final long WAV_BYTES_PER_SECOND = 64_000;
    static final long WAV_HEADER_BYTES = 44;
    /** A 320x240 image as a 4-bit RLE BMP, bounded by its uncompressed size plus slack for the RLE. */
    static final long FS_IMAGE_BYTES = 320L * 240L / 2L + 118L + 1024L;
    /** A 320x240 image as a 24-bit BMP. */
    static final long RAW_IMAGE_BYTES = 320L * 240L * 3L + 54L;
    /** Stage node record of the FS node index, after its 512-byte header. */
    static final long FS_NODE_BYTES = 44;
    static final long FS_INDEX_HEADER_BYTES = 512;
    /** The pack folder, rf, rf/000, sf and sf/000. */
    static final long FS_FOLDERS = 5;
    static final long SECTOR = 512;
    /** An MP3 assumed at 64 kbps when its own header cannot be read, so that its WAV is not under-counted. */
    static final long MP3_TO_WAV_FACTOR = WAV_BYTES_PER_SECOND / 8_000;

    private DeviceSpaceEstimate() {
    }

    static final class Result {
        final long bytes;
        final String method;

        Result(long bytes, String method) {
            this.bytes = bytes;
            this.method = method;
        }
    }

    /**
     * @param pack         a library entry: a .zip archive, a .pack raw file or an FS folder
     * @param targetFormat {@code fs} or {@code raw}
     * @param clusterBytes the cluster to round to (FS only)
     */
    static Result estimate(Path pack, String targetFormat, long clusterBytes) throws IOException {
        boolean toFs = "fs".equals(targetFormat);
        String name = pack.getFileName().toString().toLowerCase(Locale.ROOT);
        if (Files.isDirectory(pack)) {
            return toFs
                    ? new Result(PackTransferSizeEstimator.allocatedBytesForUpload(pack, clusterBytes), MEASURED)
                    : fsToRaw(pack);
        }
        if (name.endsWith(".pack")) {
            return toFs ? rawToFs(pack, clusterBytes) : new Result(roundUp(Files.size(pack), SECTOR), MEASURED);
        }
        if (name.endsWith(".zip")) {
            return archive(pack, toFs, clusterBytes);
        }
        throw new IOException("Not a pack: " + pack.getFileName());
    }

    // ------------------------------------------------------------------------------- archive

    private static Result archive(Path pack, boolean toFs, long clusterBytes) throws IOException {
        Method method = new Method();
        try (ZipFile zip = ZipFile.builder().setPath(pack).get();
             FileChannel channel = FileChannel.open(pack, StandardOpenOption.READ)) {
            Story story = Story.read(zip);
            List<Long> files = new ArrayList<>();
            long assetBytes = 0;
            for (String image : story.images) {
                ZipArchiveEntry entry = LibraryService.findEntry(zip, "assets/" + image);
                if (entry != null) {
                    long size = imageBytes(zip, entry, toFs, method);
                    files.add(size);
                    assetBytes += roundUp(size, SECTOR);
                }
            }
            for (String audio : story.audios) {
                ZipArchiveEntry entry = LibraryService.findEntry(zip, "assets/" + audio);
                if (entry != null) {
                    long size = audioBytes(zip, entry, channel, toFs, method);
                    files.add(size);
                    assetBytes += roundUp(size, SECTOR);
                }
            }
            if (!story.known) {
                method.at(ESTIMATED);
            }
            if (toFs) {
                files.add(FS_INDEX_HEADER_BYTES + FS_NODE_BYTES * story.stageNodes); // ni
                files.add(4L * story.options);                                      // li
                files.add(12L * story.images.size());                               // ri
                files.add(12L * story.audios.size());                               // si
                return new Result(PackTransferSizeEstimator.allocatedBytesForFiles(files, FS_FOLDERS, clusterBytes), method.value);
            }
            // Raw: a header sector, one sector per stage node and per action node, then the assets
            long sectors = 1 + story.stageNodes + story.actionNodes;
            return new Result(Math.addExact(sectors * SECTOR, assetBytes), method.value);
        }
    }

    private static long imageBytes(ZipFile zip, ZipArchiveEntry entry, boolean toFs, Method method) throws IOException {
        String name = entry.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".bmp") && entry.getSize() > 0) {
            byte[] head;
            try (InputStream in = zip.getInputStream(entry)) {
                head = in == null ? new byte[0] : in.readNBytes(32);
            }
            boolean rle4 = head.length >= 31 && head[28] == 0x04 && head[30] == 0x02;
            // The FS conversion keeps a 4-bit RLE BMP as it is; the raw one keeps an uncompressed BMP
            if (toFs == rle4) {
                return entry.getSize();
            }
        }
        method.at(ESTIMATED);
        return toFs ? FS_IMAGE_BYTES : RAW_IMAGE_BYTES;
    }

    private static long audioBytes(ZipFile zip, ZipArchiveEntry entry, FileChannel channel, boolean toFs, Method method) throws IOException {
        String name = entry.getName().toLowerCase(Locale.ROOT);
        long size = Math.max(entry.getSize(), 0);
        PackDetails.Duration duration = null;
        try {
            if (name.endsWith(".mp3")) {
                try (InputStream in = zip.getInputStream(entry)) {
                    duration = in == null ? null : PackDetails.mp3Duration(in, entry.getSize());
                }
                if (toFs && duration != null && duration.mono && duration.sampleRate == 44100) {
                    // Sent as it is, ID3 tags removed: never larger than the entry
                    return size;
                }
            } else if (name.endsWith(".wav")) {
                try (InputStream in = zip.getInputStream(entry)) {
                    duration = in == null ? null : PackDetails.wavDuration(in, entry.getSize());
                }
            } else if (name.endsWith(".ogg") || name.endsWith(".oga")) {
                duration = PackDetails.storedOggDuration(zip, entry, channel);
            }
        } catch (IOException | RuntimeException e) {
            duration = null;
        }
        if (duration != null) {
            method.at(ESTIMATED);
            double seconds = Math.max(duration.seconds, 0);
            return toFs
                    ? (long) Math.ceil(seconds * MP3_BYTES_PER_SECOND)
                    : (long) Math.ceil(seconds * WAV_BYTES_PER_SECOND) + WAV_HEADER_BYTES;
        }
        // No readable header (a deflated Ogg, an unknown type): from its size, generously
        method.at(ROUGH);
        if (name.endsWith(".wav")) {
            return toFs ? size / 2 : size;
        }
        return toFs ? size * 2 : size * MP3_TO_WAV_FACTOR;
    }

    /** What story.json says of a pack: its nodes and the assets they use, each counted once. */
    static final class Story {
        final boolean known;
        final long stageNodes;
        final long actionNodes;
        final long options;
        final Set<String> images;
        final Set<String> audios;

        private Story(boolean known, long stageNodes, long actionNodes, long options, Set<String> images, Set<String> audios) {
            this.known = known;
            this.stageNodes = stageNodes;
            this.actionNodes = actionNodes;
            this.options = options;
            this.images = images;
            this.audios = audios;
        }

        static Story read(ZipFile zip) {
            try {
                ZipArchiveEntry entry = LibraryService.findEntry(zip, "story.json");
                if (entry != null) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        if (in != null) {
                            return fromJson(new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // falls back on the assets of the archive below
            }
            return fromAssets(zip);
        }

        static Story fromJson(JsonObject story) {
            Set<String> images = new LinkedHashSet<>();
            Set<String> audios = new LinkedHashSet<>();
            JsonArray stages = story.getJsonArray("stageNodes", new JsonArray());
            for (int i = 0; i < stages.size(); i++) {
                JsonObject stage = stages.getJsonObject(i);
                if (stage.getValue("image") instanceof String) {
                    images.add(stage.getString("image"));
                }
                if (stage.getValue("audio") instanceof String) {
                    audios.add(stage.getString("audio"));
                }
            }
            JsonArray actions = story.getJsonArray("actionNodes", new JsonArray());
            long options = 0;
            for (int i = 0; i < actions.size(); i++) {
                options += actions.getJsonObject(i).getJsonArray("options", new JsonArray()).size();
            }
            return new Story(true, stages.size(), actions.size(), options, images, audios);
        }

        /** Without a readable story.json: every asset, and as many stage nodes as sounds. */
        static Story fromAssets(ZipFile zip) {
            Set<String> images = new LinkedHashSet<>();
            Set<String> audios = new LinkedHashSet<>();
            zip.getEntries().asIterator().forEachRemaining(entry -> {
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                String lower = name.toLowerCase(Locale.ROOT);
                if (entry.isDirectory() || !lower.startsWith("assets/")) {
                    return;
                }
                String asset = name.substring("assets/".length());
                if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".oga")) {
                    audios.add(asset);
                } else {
                    images.add(asset);
                }
            });
            long stages = Math.max(audios.size(), images.size()) + 1;
            return new Story(false, stages, stages, stages, images, audios);
        }
    }

    // ------------------------------------------------------------------- raw and FS sources

    /** A raw pack holds WAV and 24-bit BMP: their MP3 and RLE versions are about a quarter of it. */
    private static Result rawToFs(Path pack, long clusterBytes) throws IOException {
        long size = Files.size(pack);
        long stages = PackDetails.rawStats(pack).getInteger("stageNodes");
        List<Long> files = new ArrayList<>();
        files.add(size / 4);
        files.add(FS_INDEX_HEADER_BYTES + FS_NODE_BYTES * stages);
        files.add(4L * stages);
        files.add(12L * stages);
        files.add(12L * stages);
        // Slack: up to one image and one sound per stage node, each ending on a partial cluster
        long slack = Math.multiplyExact(2L * stages, clusterBytes);
        return new Result(Math.addExact(PackTransferSizeEstimator.allocatedBytesForFiles(files, FS_FOLDERS, clusterBytes), slack), ROUGH);
    }

    /** An FS pack decoded to WAV: its audio is enciphered, so only its size is known. */
    private static Result fsToRaw(Path pack) throws IOException {
        long audio = sizeOf(pack.resolve("sf"));
        long images = countOf(pack.resolve("rf"));
        long stages;
        try {
            stages = PackDetails.fsStats(pack).getInteger("stageNodes");
        } catch (IOException | RuntimeException e) {
            stages = countOf(pack.resolve("sf")) + 1;
        }
        long bytes = roundUp(Math.multiplyExact(audio, MP3_TO_WAV_FACTOR), SECTOR)
                + images * roundUp(RAW_IMAGE_BYTES, SECTOR)
                + (1 + 2 * stages) * SECTOR;
        return new Result(bytes, ROUGH);
    }

    private static long sizeOf(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    private static long countOf(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    static long roundUp(long bytes, long unit) {
        return PackTransferSizeEstimator.allocated(bytes, unit);
    }

    /** The least precise of the methods met so far. */
    private static final class Method {
        String value = MEASURED;

        void at(String method) {
            if (rank(method) > rank(value)) {
                value = method;
            }
        }

        static int rank(String method) {
            return ROUGH.equals(method) ? 2 : ESTIMATED.equals(method) ? 1 : 0;
        }
    }

    static String leastPrecise(String a, String b) {
        return Method.rank(a) >= Method.rank(b) ? a : b;
    }
}
