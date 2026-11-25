/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.utils.StreamingPackConversion;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The memory an archive to FS conversion needs no longer grows with the pack.
 *
 * <p>A synthetic pack of about 200 MB of MP3 (100 sounds of 2 MB) is converted in a separate JVM
 * limited to 64 MB of heap: the streaming conversion must succeed there, which it can only do if it
 * never holds more than a few assets at once. The in-memory conversion is run in the same
 * conditions and reported; it cannot hold 200 MB of assets in 64 MB and runs out of memory. The
 * peak heap the child observes is printed for information only, never asserted: it depends on the
 * collector and the machine.
 */
class StreamingConversionMemoryTest {

    private static final int SOUNDS = 100;
    private static final int SOUND_SIZE = 2 * 1024 * 1024;
    private static final String HEAP = "-Xmx64m";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("archive to FS: a 200 MB pack converts within 64 MB of heap")
    void streamingConversionFitsInSmallHeap() throws Exception {
        Path zip = tmp.resolve("big.zip");
        long assetBytes = writeBigArchive(zip);
        assertTrue(assetBytes >= 200L * 1024 * 1024);

        Child streaming = runChild("streaming", zip, Files.createDirectories(tmp.resolve("out-streaming")));
        System.out.println("[memory] pack of " + (assetBytes >> 20) + " MiB of assets, " + HEAP
                + ", streaming conversion: exit " + streaming.exit + ", " + streaming.output.trim());
        assertEquals(0, streaming.exit, "the streaming conversion must fit in " + HEAP + ": " + streaming.output);

        Child legacy = runChild("legacy", zip, Files.createDirectories(tmp.resolve("out-legacy")));
        System.out.println("[memory] same pack, " + HEAP + ", in-memory conversion (before): exit " + legacy.exit
                + ", " + legacy.output.trim());
        assertNotEquals(0, legacy.exit, "the in-memory conversion holds every asset at once: 200 MB cannot fit in " + HEAP);
    }

    /** One cover with an image, then {@link #SOUNDS} nodes each with its own 2 MB sound. */
    private static long writeBigArchive(Path zip) throws Exception {
        byte[] base = StreamingFixtures.mp3(1);
        byte[] image = StreamingFixtures.image(1, "png");
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray stages = new JsonArray();
        JsonArray actions = new JsonArray();
        List<String> uuids = new ArrayList<>();
        for (int i = 0; i < SOUNDS; i++) {
            uuids.add(String.format("00000000-0000-4000-8000-%012d", i + 1));
        }
        for (int i = 0; i < SOUNDS; i++) {
            JsonObject node = StreamingFixtures.stage(uuids.get(i), "cover.png", "sound" + i + ".mp3",
                    i + 1 < SOUNDS ? StreamingFixtures.transition("next" + i, 0) : null, null,
                    StreamingFixtures.controls(false, true, true, true, true));
            if (i == 0) {
                node.addProperty("squareOne", true);
            }
            stages.add(node);
            if (i + 1 < SOUNDS) {
                actions.add(StreamingFixtures.action("next" + i, uuids.get(i + 1)));
            }
        }
        root.add("stageNodes", stages);
        root.add("actionNodes", actions);

        long total = 0;
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            putStored(zos, "story.json", root.toString().getBytes(StandardCharsets.UTF_8));
            putStored(zos, "assets/cover.png", image);
            byte[] sound = new byte[SOUND_SIZE];
            for (int i = 0; i < SOUNDS; i++) {
                // The same MP3 repeated, then a few bytes making each sound distinct
                for (int pos = 0; pos < sound.length; pos += base.length) {
                    System.arraycopy(base, 0, sound, pos, Math.min(base.length, sound.length - pos));
                }
                byte[] marker = ("sound-" + i + "-end").getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(marker, 0, sound, sound.length - marker.length, marker.length);
                putStored(zos, "assets/sound" + i + ".mp3", sound);
                total += sound.length;
            }
        }
        return total;
    }

    private static void putStored(ZipOutputStream zos, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        CRC32 crc = new CRC32();
        crc.update(bytes);
        entry.setCrc(crc.getValue());
        zos.putNextEntry(entry);
        zos.write(bytes);
        zos.closeEntry();
    }

    private static final class Child {
        final int exit;
        final String output;

        Child(int exit, String output) {
            this.exit = exit;
            this.output = output;
        }
    }

    private static Child runChild(String mode, Path zip, Path out) throws Exception {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(java, HEAP, "-cp", System.getProperty("java.class.path"),
                Probe.class.getName(), mode, zip.toString(), out.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("conversion did not finish: " + output);
        }
        // Keep the last lines only: an OutOfMemoryError comes with a long stack trace
        String[] lines = output.split("\\R");
        StringBuilder tail = new StringBuilder();
        for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) {
            tail.append(lines[i]).append(' ');
        }
        return new Child(process.exitValue(), tail.toString());
    }

    /** Runs one conversion in its own JVM and prints the peak heap it observed. */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            AtomicLong peak = new AtomicLong();
            Thread sampler = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            sampler.setDaemon(true);
            sampler.start();
            long start = System.nanoTime();
            try {
                if ("streaming".equals(args[0])) {
                    StreamingPackConversion.archiveToFs(Path.of(args[1]), Path.of(args[2]));
                } else {
                    StreamingConversionEquivalenceTest.legacyArchiveToFs(Path.of(args[1]), Path.of(args[2]));
                }
            } catch (OutOfMemoryError e) {
                System.out.println("OutOfMemoryError after " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        + " ms, peak heap used " + (peak.get() >> 20) + " MiB");
                System.exit(3);
            }
            peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
            System.out.println("done in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    + " ms, peak heap used " + (peak.get() >> 20) + " MiB (max " + (Runtime.getRuntime().maxMemory() >> 20) + " MiB)");
        }
    }
}
