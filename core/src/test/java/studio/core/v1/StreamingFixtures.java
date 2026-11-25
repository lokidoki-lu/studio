/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import studio.core.v1.utils.AudioConversion;
import studio.core.v1.utils.ImageConversion;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Archive packs built in code with real assets (decodable images, MP3 the audio stack parses), for
 * the tests comparing the streaming conversions with the in-memory ones.
 */
public final class StreamingFixtures {

    private StreamingFixtures() {
    }

    // ------------------------------------------------------------------ assets

    /** A 320x240 image with a pattern depending on {@code seed}, encoded in {@code format} (png, jpg, bmp). */
    public static byte[] image(int seed, String format) throws IOException {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color((seed * 53) % 256, (seed * 101) % 256, (seed * 151) % 256));
        g.fillRect(0, 0, 320, 240);
        g.setColor(Color.WHITE);
        g.fillOval(20 + seed * 7 % 200, 30, 80, 60);
        g.setColor(Color.BLACK);
        g.drawString("pack " + seed, 40, 200);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, format, out)) {
            throw new IOException("No writer for " + format);
        }
        return out.toByteArray();
    }

    /** An image already in the format of firmware 2.4 (4-bits RLE BMP), which the conversion keeps as is. */
    public static byte[] rleBitmap(int seed) throws IOException {
        return ImageConversion.anyToRLECompressedBitmap(image(seed, "png"));
    }

    /** A WAV of a short tone (mono 16-bit at {@code sampleRate}). */
    public static byte[] wav(int seed, float sampleRate, int millis) throws IOException {
        int samples = (int) (sampleRate * millis / 1000);
        byte[] pcm = new byte[samples * 2];
        double frequency = 220 + seed * 55;
        for (int i = 0; i < samples; i++) {
            short v = (short) (Math.sin(2 * Math.PI * frequency * i / sampleRate) * 8000);
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), format, samples), AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }

    /** A mono 44100 Hz MP3, as firmware 2.4 wants it. */
    public static byte[] mp3(int seed) throws Exception {
        return AudioConversion.anyToMp3(wav(seed, 44100f, 400));
    }

    /** {@code mp3} with an ID3v2 tag in front (10-byte header plus a {@code bodySize}-byte body). */
    public static byte[] withId3v2(byte[] mp3, int bodySize) {
        byte[] tag = new byte[10 + bodySize];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 3;
        tag[6] = (byte) ((bodySize >> 21) & 0x7f);
        tag[7] = (byte) ((bodySize >> 14) & 0x7f);
        tag[8] = (byte) ((bodySize >> 7) & 0x7f);
        tag[9] = (byte) (bodySize & 0x7f);
        byte[] text = "TIT2 title of the story".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, tag, 10, Math.min(text.length, bodySize));
        return concat(tag, mp3);
    }

    /** {@code mp3} with an ID3v1 tag at the end (128 bytes starting with "TAG"). */
    public static byte[] withId3v1(byte[] mp3) {
        byte[] tag = new byte[128];
        tag[0] = 'T';
        tag[1] = 'A';
        tag[2] = 'G';
        byte[] text = "A title".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, tag, 3, text.length);
        return concat(mp3, tag);
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ------------------------------------------------------------------ story.json

    public static JsonObject controls(boolean wheel, boolean ok, boolean home, boolean pause, boolean autoplay) {
        JsonObject c = new JsonObject();
        c.addProperty("wheel", wheel);
        c.addProperty("ok", ok);
        c.addProperty("home", home);
        c.addProperty("pause", pause);
        c.addProperty("autoplay", autoplay);
        return c;
    }

    public static JsonObject transition(String actionId, int optionIndex) {
        JsonObject t = new JsonObject();
        t.addProperty("actionNode", actionId);
        t.addProperty("optionIndex", optionIndex);
        return t;
    }

    public static JsonObject stage(String uuid, String image, String audio, JsonObject ok, JsonObject home, JsonObject controls) {
        JsonObject n = new JsonObject();
        n.addProperty("uuid", uuid);
        n.add("image", image == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(image));
        n.add("audio", audio == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(audio));
        n.add("okTransition", ok == null ? JsonNull.INSTANCE : ok);
        n.add("homeTransition", home == null ? JsonNull.INSTANCE : home);
        n.add("controlSettings", controls);
        return n;
    }

    public static JsonObject action(String id, String... options) {
        JsonObject a = new JsonObject();
        a.addProperty("id", id);
        JsonArray opts = new JsonArray();
        for (String o : options) {
            opts.add(o);
        }
        a.add("options", opts);
        return a;
    }

    // ------------------------------------------------------------------ archives

    /** Entries of an archive, in order: name to bytes. */
    public static final class Archive {
        public final Map<String, byte[]> entries = new LinkedHashMap<>();
        public boolean stored;

        public Archive put(String name, byte[] bytes) {
            entries.put(name, bytes);
            return this;
        }

        public void writeTo(Path path) throws IOException {
            try (OutputStream out = Files.newOutputStream(path)) {
                writeTo(out);
            }
        }

        public void writeTo(OutputStream out) throws IOException {
            ZipOutputStream zos = new ZipOutputStream(out);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                if (stored) {
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(e.getValue().length);
                    entry.setCompressedSize(e.getValue().length);
                    CRC32 crc = new CRC32();
                    crc.update(e.getValue());
                    entry.setCrc(crc.getValue());
                }
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
            zos.finish();
        }
    }

    public static final String COVER = "00000000-0000-4000-8000-000000000001";

    /**
     * A pack exercising what the FS conversion does to assets: a cover with a menu of three
     * choices, each leading to a story, then back home. It has PNG, JPEG, 24-bit BMP and already
     * prepared RLE BMP images; MP3 with an ID3v2 tag, an ID3v1 tag, both or none; a WAV to
     * re-encode; one file used by several nodes; two files with the same content; two MP3 that are
     * the same once their tags are removed; a node without image; nodes without audio; an asset
     * nobody references; an entry stored with a Windows separator.
     */
    public static Archive richArchive(boolean nightMode) throws Exception {
        byte[] coverPng = image(1, "png");
        byte[] q1Jpg = image(2, "jpg");
        byte[] q2Bmp = image(3, "bmp");
        byte[] q3Rle = rleBitmap(4);
        byte[] titleMp3 = mp3(1);
        byte[] q1Mp3 = mp3(2);
        byte[] q2Mp3 = mp3(3);
        byte[] storyMp3 = mp3(4);
        byte[] story2Mp3 = mp3(5);
        byte[] q3Wav = wav(6, 32000f, 300);

        String cover = COVER;
        String q1 = "00000000-0000-4000-8000-000000000011";
        String q2 = "00000000-0000-4000-8000-000000000012";
        String q3 = "00000000-0000-4000-8000-000000000013";
        String s1 = "00000000-0000-4000-8000-000000000021";
        String s2 = "00000000-0000-4000-8000-000000000022";
        String s3 = "00000000-0000-4000-8000-000000000023";
        String s4 = "00000000-0000-4000-8000-000000000024";

        JsonObject root = new JsonObject();
        root.addProperty("format", "v1");
        root.addProperty("title", "Pack de test é");
        root.addProperty("description", "Un pack pour comparer les conversions");
        root.addProperty("version", 3);
        root.addProperty("nightModeAvailable", nightMode);
        JsonArray stages = new JsonArray();
        JsonObject coverNode = stage(cover, "cover.png", "title.mp3", transition("menu", 0), null, controls(true, true, false, false, false));
        coverNode.addProperty("squareOne", true);
        coverNode.addProperty("name", "Couverture");
        // Listed second, so that the reader has to move square one first
        stages.add(stage(q1, "q1.jpg", "q1_v1.mp3", transition("go1", 0), transition("menu", 0), controls(true, true, true, false, false)));
        stages.add(coverNode);
        stages.add(stage(q2, "q2.bmp", "q2_both.mp3", transition("go2", 0), transition("menu", 1), controls(true, true, true, false, false)));
        stages.add(stage(q3, "q3.bmp", "q3.wav", transition("go3", 0), transition("menu", 2), controls(true, true, true, false, false)));
        stages.add(stage(s1, "cover.png", "story.mp3", transition("menu", -1), transition("menu", 0), controls(false, false, true, true, true)));
        stages.add(stage(s2, null, "story_copy.mp3", transition("menu", 1), transition("menu", 0), controls(false, false, true, true, true)));
        stages.add(stage(s3, "cover_copy.png", "story.mp3", transition("go4", 0), null, controls(false, false, true, true, true)));
        stages.add(stage(s4, null, null, null, transition("menu", 2), controls(false, false, true, true, true)));
        // Same sound as s1 once the tags are removed, under its own name
        stages.add(stage("00000000-0000-4000-8000-000000000025", "q3.bmp", "story_tagged.mp3", null, transition("menu", 0), controls(false, false, true, true, true)));
        stages.add(stage("00000000-0000-4000-8000-000000000026", "q1.jpg", "story2.mp3", null, null, controls(false, false, true, true, true)));
        root.add("stageNodes", stages);
        JsonArray actions = new JsonArray();
        actions.add(action("menu", q1, q2, q3));
        actions.add(action("go1", s1));
        actions.add(action("go2", s2, s3));
        actions.add(action("go3", s3));
        actions.add(action("go4", s4));
        root.add("actionNodes", actions);

        Archive archive = new Archive();
        archive.put("story.json", root.toString().getBytes(StandardCharsets.UTF_8));
        archive.put("assets/", new byte[0]);
        archive.put("assets/cover.png", coverPng);
        archive.put("assets/cover_copy.png", coverPng.clone());
        archive.put("assets\\q1.jpg", q1Jpg);
        archive.put("assets/q2.bmp", q2Bmp);
        archive.put("assets/q3.bmp", q3Rle);
        archive.put("assets/title.mp3", withId3v2(titleMp3, 300));
        archive.put("assets/q1_v1.mp3", withId3v1(q1Mp3));
        archive.put("assets/q2_both.mp3", withId3v1(withId3v2(q2Mp3, 64)));
        archive.put("assets/q3.wav", q3Wav);
        archive.put("assets/story.mp3", storyMp3);
        archive.put("assets/story_copy.mp3", storyMp3.clone());
        archive.put("assets/story_tagged.mp3", withId3v2(storyMp3, 120));
        archive.put("assets/story2.mp3", story2Mp3);
        archive.put("assets/unused.mp3", mp3(7));
        archive.put("thumbnail.png", image(9, "png"));
        return archive;
    }

    /** The smallest pack: one node, no asset at all (the FS writer adds the blank sound). */
    public static Archive minimalArchive() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonArray stages = new JsonArray();
        JsonObject node = stage(COVER, null, null, null, null, controls(false, true, false, false, false));
        node.addProperty("squareOne", true);
        stages.add(node);
        root.add("stageNodes", stages);
        root.add("actionNodes", new JsonArray());
        return new Archive().put("story.json", root.toString().getBytes(StandardCharsets.UTF_8));
    }
}
