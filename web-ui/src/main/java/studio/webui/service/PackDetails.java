/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonObject;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the library preview shows of a pack beyond its metadata, read at low cost: never the whole
 * pack, only headers.
 *
 * <ul>
 *     <li>size on disk, for every format (measured by {@code LibraryService.diskSize});</li>
 *     <li>stage nodes and stories (stage nodes where pause is enabled, as on the device) of the raw and
 *     FS formats, from their node index: the browser counts those of an archive from its story.json;</li>
 *     <li>total audio duration of an archive, from the header of each audio asset: exact for WAV and
 *     for MP3 with a Xing/Info header, estimated from the bitrate for other MP3, exact for a stored
 *     (not deflated) Ogg, and left out (counted in {@code unmeasured}) otherwise. The raw and FS
 *     formats are not measured: their audio is spread across the pack, enciphered for FS.</li>
 * </ul>
 */
final class PackDetails {

    /** Bytes read at the start of an audio entry, past its ID3v2 tag, to find its first frame. */
    static final int AUDIO_HEADER_BYTES = 8 * 1024;
    /** Bytes read at the end of a stored Ogg entry, to find its last page. */
    static final int OGG_TAIL_BYTES = 64 * 1024;
    private static final int SECTOR_SIZE = 512;

    private PackDetails() {
    }

    // ---------------------------------------------------------------------------- raw and FS

    /** Stage nodes and stories of a raw pack: one 512-byte sector per stage node, after the first. */
    static JsonObject rawStats(Path pack) throws IOException {
        try (FileChannel channel = FileChannel.open(pack, StandardOpenOption.READ)) {
            ByteBuffer header = readAt(channel, 0, 2).order(ByteOrder.BIG_ENDIAN);
            if (header.remaining() < 2) {
                throw new IOException("Raw pack too short");
            }
            int stages = header.getShort();
            if (stages <= 0) {
                throw new IOException("Invalid raw pack: " + stages + " stage nodes");
            }
            int stories = 0;
            for (int i = 0; i < stages; i++) {
                // uuid (16), image (8), audio (8), ok (6), home (6), then wheel, ok, home, pause, autojump
                ByteBuffer pause = readAt(channel, (long) (i + 1) * SECTOR_SIZE + 50, 2).order(ByteOrder.BIG_ENDIAN);
                if (pause.remaining() < 2) {
                    throw new IOException("Raw pack ends before stage node " + i);
                }
                if (pause.getShort() == 1) {
                    stories++;
                }
            }
            return new JsonObject().put("stageNodes", stages).put("stories", stories);
        }
    }

    /** Stage nodes and stories of an FS pack, from its node index file `ni`. */
    static JsonObject fsStats(Path pack) throws IOException {
        byte[] ni = Files.readAllBytes(pack.resolve("ni"));
        if (ni.length < 25) {
            throw new IOException("Invalid FS pack: ni file is too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(ni).order(ByteOrder.LITTLE_ENDIAN);
        int nodesList = bb.getInt(4);
        int nodeSize = bb.getInt(8);
        int stages = bb.getInt(12);
        if (stages <= 0 || nodeSize < 42 || nodesList < 0) {
            throw new IOException("Invalid FS pack: " + stages + " stage nodes of " + nodeSize + " bytes");
        }
        int stories = 0;
        for (int i = 0; i < stages; i++) {
            // image, sound, ok (3), home (3) as ints, then wheel, ok, home, pause, autoplay as shorts
            long offset = nodesList + (long) i * nodeSize + 38;
            if (offset + 2 > ni.length) {
                throw new IOException("Invalid FS pack: ni file ends before stage node " + i);
            }
            if (bb.getShort((int) offset) != 0) {
                stories++;
            }
        }
        return new JsonObject().put("stageNodes", stages).put("stories", stories);
    }

    private static ByteBuffer readAt(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                break;
            }
        }
        buffer.flip();
        return buffer;
    }

    // ------------------------------------------------------------------------- archive audio

    /**
     * Total duration of the audio assets of an archive: {seconds, measured, estimated, unmeasured},
     * where {@code estimated} counts the assets whose duration comes from their bitrate alone.
     */
    static JsonObject archiveAudio(Path pack) throws IOException {
        double seconds = 0;
        int measured = 0;
        int estimated = 0;
        int unmeasured = 0;
        try (ZipFile zip = ZipFile.builder().setPath(pack).get();
             FileChannel channel = FileChannel.open(pack, StandardOpenOption.READ)) {
            // An archive may hold several entries of the same name: the pack readers take the last
            // one, so only that one is measured (counting each would add the same asset twice).
            Map<String, ZipArchiveEntry> assets = new LinkedHashMap<>();
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.toLowerCase(Locale.ROOT).startsWith("assets/")) {
                    continue;
                }
                assets.remove(name);
                assets.put(name, entry);
            }
            for (Map.Entry<String, ZipArchiveEntry> asset : assets.entrySet()) {
                ZipArchiveEntry entry = asset.getValue();
                String name = asset.getKey().toLowerCase(Locale.ROOT);
                Duration duration = null;
                try {
                    if (name.endsWith(".mp3")) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            duration = in == null ? null : mp3Duration(in, entry.getSize());
                        }
                    } else if (name.endsWith(".wav")) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            duration = in == null ? null : wavDuration(in, entry.getSize());
                        }
                    } else if (name.endsWith(".ogg") || name.endsWith(".oga")) {
                        duration = storedOggDuration(zip, entry, channel);
                    } else {
                        continue;
                    }
                } catch (IOException | RuntimeException e) {
                    duration = null;
                }
                if (duration == null) {
                    unmeasured++;
                } else {
                    seconds += duration.seconds;
                    measured++;
                    if (duration.estimated) {
                        estimated++;
                    }
                }
            }
        }
        return new JsonObject()
                .put("seconds", Math.round(seconds * 10) / 10.0)
                .put("measured", measured)
                .put("estimated", estimated)
                .put("unmeasured", unmeasured);
    }

    static final class Duration {
        final double seconds;
        final boolean estimated;
        /** Of an MP3, as its first frame says (0 when unknown): whether it can go to the device as is. */
        final int sampleRate;
        final boolean mono;

        Duration(double seconds, boolean estimated) {
            this(seconds, estimated, 0, false);
        }

        Duration(double seconds, boolean estimated, int sampleRate, boolean mono) {
            this.seconds = seconds;
            this.estimated = estimated;
            this.sampleRate = sampleRate;
            this.mono = mono;
        }
    }

    // MPEG audio tables: [version index][bitrate index], kbps; version index 0 = MPEG 1, 1 = MPEG 2 / 2.5
    private static final int[][] LAYER3_BITRATES = {
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0}
    };
    // [version bits][sample rate index]: version bits 0 = MPEG 2.5, 2 = MPEG 2, 3 = MPEG 1
    private static final int[][] SAMPLE_RATES = {
            {11025, 12000, 8000},
            null,
            {22050, 24000, 16000},
            {44100, 48000, 32000}
    };

    /**
     * Duration of an MP3 stream of {@code size} bytes (-1 if unknown), from its first frame: exact with
     * a Xing/Info header (frame count), estimated from the bitrate otherwise. Null if no Layer III frame
     * is found at the start.
     */
    static Duration mp3Duration(InputStream in, long size) throws IOException {
        long skipped = 0;
        byte[] head = in.readNBytes(10);
        if (head.length == 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            long tagSize = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14) | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
            if ((head[5] & 0x10) != 0) {
                tagSize += 10; // footer
            }
            skipFully(in, tagSize);
            skipped = 10 + tagSize;
            head = new byte[0];
        }
        byte[] rest = in.readNBytes(AUDIO_HEADER_BYTES);
        byte[] buf = new byte[head.length + rest.length];
        System.arraycopy(head, 0, buf, 0, head.length);
        System.arraycopy(rest, 0, buf, head.length, rest.length);

        for (int i = 0; i + 4 <= buf.length; i++) {
            if ((buf[i] & 0xFF) != 0xFF || (buf[i + 1] & 0xE0) != 0xE0) {
                continue;
            }
            int versionBits = (buf[i + 1] >> 3) & 0x03;
            int layerBits = (buf[i + 1] >> 1) & 0x03;
            int bitrateIndex = (buf[i + 2] >> 4) & 0x0F;
            int sampleRateIndex = (buf[i + 2] >> 2) & 0x03;
            int channelMode = (buf[i + 3] >> 6) & 0x03;
            // Layer III only (bits 01), valid version, bitrate and sample rate
            if (layerBits != 1 || versionBits == 1 || bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
                continue;
            }
            boolean mpeg1 = versionBits == 3;
            int bitrate = LAYER3_BITRATES[mpeg1 ? 0 : 1][bitrateIndex] * 1000;
            int sampleRate = SAMPLE_RATES[versionBits][sampleRateIndex];
            int samplesPerFrame = mpeg1 ? 1152 : 576;

            // Xing / Info header, right after the side information
            int sideInfo = mpeg1 ? (channelMode == 3 ? 17 : 32) : (channelMode == 3 ? 9 : 17);
            int xing = i + 4 + sideInfo;
            if (xing + 12 <= buf.length) {
                String tag = new String(buf, xing, 4, StandardCharsets.ISO_8859_1);
                if ((tag.equals("Xing") || tag.equals("Info")) && (buf[xing + 7] & 0x01) != 0) {
                    long frames = ByteBuffer.wrap(buf, xing + 8, 4).order(ByteOrder.BIG_ENDIAN).getInt() & 0xFFFFFFFFL;
                    if (frames > 0) {
                        return new Duration((double) frames * samplesPerFrame / sampleRate, false, sampleRate, channelMode == 3);
                    }
                }
            }
            if (size <= 0) {
                return null;
            }
            long audioBytes = size - skipped - i;
            return new Duration(audioBytes * 8.0 / bitrate, true, sampleRate, channelMode == 3);
        }
        return null;
    }

    /**
     * Duration of a WAV stream of {@code size} bytes (-1 if unknown), from the byte rate of its fmt
     * chunk and the size of its data chunk. That size is capped at what the stream can hold after the
     * chunk header: a WAV written to a pipe declares 0xFFFFFFFF (or any placeholder), which read as
     * such made a few seconds of audio last hours.
     */
    static Duration wavDuration(InputStream in, long size) throws IOException {
        byte[] riff = in.readNBytes(12);
        if (riff.length < 12 || !new String(riff, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")
                || !new String(riff, 8, 4, StandardCharsets.ISO_8859_1).equals("WAVE")) {
            return null;
        }
        long position = 12;
        long byteRate = 0;
        for (int chunks = 0; chunks < 32; chunks++) {
            byte[] chunk = in.readNBytes(8);
            if (chunk.length < 8) {
                return null;
            }
            position += 8;
            String id = new String(chunk, 0, 4, StandardCharsets.ISO_8859_1);
            long chunkSize = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            if (id.equals("fmt ")) {
                byte[] fmt = in.readNBytes((int) Math.min(chunkSize, 64));
                if (fmt.length < 12) {
                    return null;
                }
                byteRate = ByteBuffer.wrap(fmt, 8, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
                skipFully(in, chunkSize - fmt.length + (chunkSize & 1));
                position += chunkSize + (chunkSize & 1);
            } else if (id.equals("data")) {
                long dataSize = size > 0 ? Math.min(chunkSize, Math.max(size - position, 0)) : chunkSize;
                return byteRate > 0 ? new Duration((double) dataSize / byteRate, false) : null;
            } else {
                skipFully(in, chunkSize + (chunkSize & 1));
                position += chunkSize + (chunkSize & 1);
            }
        }
        return null;
    }

    /**
     * Duration of a stored (not deflated) Ogg Vorbis entry: granule position of its last page, over the
     * sample rate of its identification header. A deflated one would have to be inflated in full: null.
     */
    static Duration storedOggDuration(ZipFile zip, ZipArchiveEntry entry, FileChannel channel) throws IOException {
        if (entry.getMethod() != ZipArchiveEntry.STORED || entry.getSize() <= 0) {
            return null;
        }
        long dataOffset = entry.getDataOffset();
        ByteBuffer head = readAt(channel, dataOffset, (int) Math.min(entry.getSize(), 64));
        byte[] headBytes = new byte[head.remaining()];
        head.get(headBytes);
        int tailLength = (int) Math.min(entry.getSize(), OGG_TAIL_BYTES);
        ByteBuffer tail = readAt(channel, dataOffset + entry.getSize() - tailLength, tailLength);
        byte[] tailBytes = new byte[tail.remaining()];
        tail.get(tailBytes);
        return oggDuration(headBytes, tailBytes);
    }

    /** Duration of an Ogg Vorbis stream from its first bytes and its last bytes. */
    static Duration oggDuration(byte[] head, byte[] tail) {
        // First page: 27-byte header, segment table, then the identification packet "\1vorbis"
        if (head.length < 28 || !new String(head, 0, 4, StandardCharsets.ISO_8859_1).equals("OggS")) {
            return null;
        }
        int segments = head[26] & 0xFF;
        int packet = 27 + segments;
        if (packet + 16 > head.length || head[packet] != 1
                || !new String(head, packet + 1, 6, StandardCharsets.ISO_8859_1).equals("vorbis")) {
            return null;
        }
        long sampleRate = ByteBuffer.wrap(head, packet + 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        if (sampleRate <= 0) {
            return null;
        }
        for (int i = tail.length - 14; i >= 0; i--) {
            if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S') {
                long granule = ByteBuffer.wrap(tail, i + 6, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                if (granule > 0) {
                    return new Duration((double) granule / sampleRate, false);
                }
            }
        }
        return null;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }
}
