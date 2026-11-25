/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes an archive pack (story.json, thumbnail.png, assets/) from stories whose audio is ready,
 * with the same structure as Lunii Pack: a cover, a menu (or a main menu of sub-menus), and for
 * each story a title stage (image + spoken title) followed by the story stage (audio, autoplay).
 *
 * <p>Node identifiers are derived from the pack identifier and each story's key, so building the
 * same pack again gives the same uuids and the device sees an update, not a new pack.
 */
public final class YoutubePackBuilder {

    public static final int WIDTH = 320;
    public static final int HEIGHT = 240;
    private static final Color BACKGROUND = new Color(30, 40, 80);

    /** One story of the pack. */
    public static final class Story {
        final String title;
        /** Stable identity (the video id). */
        final String key;
        final String group;
        /** Story audio, MP3. */
        final Path audio;
        /** Source image (thumbnail), or null: a text image is drawn instead. */
        final BufferedImage image;

        public Story(String title, String key, String group, Path audio, BufferedImage image) {
            this.title = title;
            this.key = key;
            this.group = group;
            this.audio = audio;
            this.image = image;
        }
    }

    /** Pack-level settings. */
    public static final class Settings {
        final String title;
        final String description;
        final String packId;
        final int menuSize;
        final boolean autoplayNext;

        public Settings(String title, String description, String packId, int menuSize, boolean autoplayNext) {
            this.title = title;
            this.description = description == null ? "" : description;
            this.packId = packId;
            this.menuSize = menuSize;
            this.autoplayNext = autoplayNext;
        }
    }

    /** What was written. */
    public static final class Result {
        public final String uuid;
        public final int stories;
        public final int silentTitles;

        Result(String uuid, int stories, int silentTitles) {
            this.uuid = uuid;
            this.stories = stories;
            this.silentTitles = silentTitles;
        }
    }

    private YoutubePackBuilder() {
    }

    /** Asset files of the pack, named by their SHA-1 like STUdio does. */
    private static final class Assets {
        final Map<String, Object> files = new LinkedHashMap<>();

        String bytes(byte[] data, String ext) {
            String name = sha1(data) + ext;
            files.putIfAbsent(name, data);
            return name;
        }

        String file(Path p, String ext) throws IOException {
            String name = sha1(p) + ext;
            files.putIfAbsent(name, p);
            return name;
        }

        String image(BufferedImage img) throws IOException {
            return bytes(png(img), ".png");
        }
    }

    /**
     * Writes the pack to {@code out}.
     *
     * @param speaker spoken audio (WAVE) for a text, or null when no voice could be produced
     */
    public static Result build(List<Story> stories, Settings settings, Function<String, byte[]> speaker, Path out) throws IOException {
        if (stories.isEmpty()) {
            throw new IOException("No story to put in the pack");
        }
        Assets assets = new Assets();
        int[] silent = {0};
        Function<String, String> voice = text -> {
            byte[] wave = speaker.apply(text);
            if (wave == null || wave.length == 0) {
                silent[0]++;
                return null;
            }
            return assets.bytes(wave, ".wav");
        };

        // 1) Stories: audio, image, spoken title
        List<String> storyAudio = new ArrayList<>();
        List<String> storyImage = new ArrayList<>();
        List<String> storyVoice = new ArrayList<>();
        for (Story s : stories) {
            storyAudio.add(assets.file(s.audio, ".mp3"));
            storyImage.add(assets.image(s.image != null ? fit(s.image, WIDTH, HEIGHT) : textImage(s.title)));
            storyVoice.add(voice.apply(s.title));
        }

        // 2) Cover
        List<BufferedImage> thumbs = new ArrayList<>();
        stories.forEach(s -> thumbs.add(s.image));
        BufferedImage cover = mosaic(thumbs, settings.title);
        byte[] coverPng = png(cover);
        String coverImage = assets.bytes(coverPng, ".png");
        String coverAudio = voice.apply(settings.title);

        // 3) Menus
        List<String> groups = new ArrayList<>();
        stories.forEach(s -> groups.add(s.group));
        List<YoutubeTitles.Menu> menus = YoutubeTitles.menus(groups, settings.menuSize);
        boolean subMenus = menus.get(0).name != null;
        Function<String, String> id = key -> UUID.nameUUIDFromBytes(
                ("studio-youtube:" + settings.packId + "/" + key).getBytes(StandardCharsets.UTF_8)).toString();
        String rootId = id.apply("menu");
        String coverUuid = id.apply("cover");

        JsonArray stages = new JsonArray();
        JsonArray actions = new JsonArray();
        stages.add(stage(coverUuid, "Couverture", coverImage, coverAudio, transition(rootId, 0), null,
                true, true, false, false, false, 0, 0).put("squareOne", true));
        List<String> menuIds = new ArrayList<>();
        for (YoutubeTitles.Menu m : menus) {
            menuIds.add(subMenus ? id.apply("menu/" + m.name) : rootId);
        }
        JsonArray rootOptions = new JsonArray();
        Map<String, Integer> seenKeys = new LinkedHashMap<>();
        int y = 0;
        for (int gi = 0; gi < menus.size(); gi++) {
            YoutubeTitles.Menu m = menus.get(gi);
            String menuId = menuIds.get(gi);
            if (subMenus) {
                String groupUuid = id.apply("group/" + m.name);
                List<BufferedImage> groupThumbs = new ArrayList<>();
                m.stories.forEach(i -> groupThumbs.add(stories.get(i).image));
                String groupImage = assets.image(mosaic(groupThumbs, m.name));
                stages.add(stage(groupUuid, m.name, groupImage, voice.apply(m.name), transition(menuId, 0), null,
                        true, true, true, false, false, 300, y));
                rootOptions.add(groupUuid);
            }
            JsonArray menuOptions = new JsonArray();
            for (int pos = 0; pos < m.stories.size(); pos++) {
                int i = m.stories.get(pos);
                Story s = stories.get(i);
                int n = seenKeys.merge(s.key, 1, Integer::sum);
                String key = s.key + "#" + n;
                String titleUuid = id.apply("title/" + key);
                String storyUuid = id.apply("story/" + key);
                String actionId = id.apply("action/" + key);
                JsonObject next;
                if (settings.autoplayNext) {
                    next = pos + 1 < m.stories.size() ? transition(menuId, pos + 1)
                            : transition(menuIds.get((gi + 1) % menus.size()), 0);
                } else {
                    next = transition(menuId, pos);
                }
                stages.add(stage(titleUuid, s.title, storyImage.get(i), storyVoice.get(i), transition(actionId, 0),
                        subMenus ? transition(rootId, gi) : null, true, true, true, false, false, 600, y));
                stages.add(stage(storyUuid, "Histoire - " + s.title, null, storyAudio.get(i), next, transition(menuId, pos),
                        false, false, true, true, true, 1000, y));
                actions.add(action(actionId, "Action - " + s.title, 800, y, new JsonArray().add(storyUuid)));
                menuOptions.add(titleUuid);
                y += 150;
            }
            actions.add(action(menuId, subMenus ? "Menu - " + m.name : "Menu", subMenus ? 450 : 150, y, menuOptions));
        }
        if (subMenus) {
            actions.add(action(rootId, "Menu principal", 150, 0, rootOptions));
        }
        JsonObject story = new JsonObject()
                .put("format", "v1")
                .put("title", settings.title)
                .put("description", settings.description)
                .put("version", 1)
                .put("nightModeAvailable", false)
                .put("stageNodes", stages)
                .put("actionNodes", actions);

        // 4) Archive
        try (OutputStream os = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            zip.putNextEntry(new ZipEntry("story.json"));
            zip.write(story.encodePrettily().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("thumbnail.png"));
            zip.write(coverPng);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("assets/"));
            zip.closeEntry();
            for (Map.Entry<String, Object> a : assets.files.entrySet()) {
                // Already compressed audio gains nothing from deflate: stored as fast as possible
                zip.setLevel(a.getKey().endsWith(".mp3") ? Deflater.NO_COMPRESSION : Deflater.DEFAULT_COMPRESSION);
                zip.putNextEntry(new ZipEntry("assets/" + a.getKey()));
                if (a.getValue() instanceof byte[]) {
                    zip.write((byte[]) a.getValue());
                } else {
                    Files.copy((Path) a.getValue(), zip);
                }
                zip.closeEntry();
            }
        }
        return new Result(coverUuid, stories.size(), silent[0]);
    }

    private static JsonObject stage(String uuid, String name, String image, String audio, JsonObject ok, JsonObject home,
                                    boolean wheel, boolean okButton, boolean homeButton, boolean pause, boolean autoplay,
                                    int x, int y) {
        return new JsonObject()
                .put("uuid", uuid)
                .put("name", name)
                .put("type", "stage")
                .put("position", new JsonObject().put("x", x).put("y", y))
                .put("image", image)
                .put("audio", audio)
                .put("okTransition", ok)
                .put("homeTransition", home)
                .put("controlSettings", new JsonObject()
                        .put("wheel", wheel).put("ok", okButton).put("home", homeButton)
                        .put("pause", pause).put("autoplay", autoplay));
    }

    private static JsonObject action(String id, String name, int x, int y, JsonArray options) {
        return new JsonObject()
                .put("id", id)
                .put("name", name)
                .put("type", "action")
                .put("position", new JsonObject().put("x", x).put("y", y))
                .put("options", options);
    }

    private static JsonObject transition(String actionNode, int index) {
        return new JsonObject().put("actionNode", actionNode).put("optionIndex", index);
    }

    // -- images

    /** Centre crop to the w:h ratio, then scaled to w x h. */
    public static BufferedImage fit(BufferedImage src, int w, int h) {
        double scale = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
        int cw = (int) Math.round(w / scale);
        int ch = (int) Math.round(h / scale);
        int cx = Math.max(0, (src.getWidth() - cw) / 2);
        int cy = Math.max(0, (src.getHeight() - ch) / 2);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, cx, cy, Math.min(src.getWidth(), cx + cw), Math.min(src.getHeight(), cy + ch), null);
        g.dispose();
        return out;
    }

    /** The text on a plain background, for a story without a thumbnail. */
    public static BufferedImage textImage(String text) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.dispose();
        band(img, text);
        return img;
    }

    /** Up to four thumbnails side by side, with the title across the middle. */
    public static BufferedImage mosaic(List<BufferedImage> images, String title) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BACKGROUND);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        List<BufferedImage> thumbs = new ArrayList<>();
        for (BufferedImage i : images) {
            if (i != null && thumbs.size() < 4) {
                thumbs.add(i);
            }
        }
        int w = WIDTH / 2;
        int h = HEIGHT / 2;
        int[][] layout;
        switch (thumbs.size()) {
            case 1: layout = new int[][]{{0, 0, WIDTH, HEIGHT}}; break;
            case 2: layout = new int[][]{{0, 0, w, HEIGHT}, {w, 0, w, HEIGHT}}; break;
            case 3: layout = new int[][]{{0, 0, w, HEIGHT}, {w, 0, w, h}, {w, h, w, h}}; break;
            case 4: layout = new int[][]{{0, 0, w, h}, {w, 0, w, h}, {0, h, w, h}, {w, h, w, h}}; break;
            default: layout = new int[0][];
        }
        for (int k = 0; k < layout.length; k++) {
            int[] r = layout[k];
            g.drawImage(fit(thumbs.get(k), r[2], r[3]), r[0], r[1], null);
        }
        g.dispose();
        band(img, title);
        return img;
    }

    /** A translucent band with the text, at most two lines, centred vertically. */
    static void band(BufferedImage img, String text) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 22);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(text == null ? "" : text, fm, WIDTH - 20, 2);
        int lineHeight = 28;
        int h = lineHeight * lines.size() + 12;
        int y0 = (HEIGHT - h) / 2;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, y0, WIDTH, h);
        g.setColor(Color.WHITE);
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            int x = (WIDTH - fm.stringWidth(l)) / 2;
            g.drawString(l, x, y0 + 6 + i * lineHeight + fm.getAscent());
        }
        g.dispose();
    }

    static List<String> wrap(String text, FontMetrics fm, int maxWidth, int maxLines) {
        List<String> words = new ArrayList<>();
        for (String w : text.trim().split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (fm.stringWidth(w) <= maxWidth) {
                words.add(w);
                continue;
            }
            // A word too long for a line is cut
            StringBuilder cur = new StringBuilder();
            for (char c : w.toCharArray()) {
                if (cur.length() > 0 && fm.stringWidth(cur.toString() + c + "-") > maxWidth) {
                    words.add(cur + "-");
                    cur.setLength(0);
                }
                cur.append(c);
            }
            words.add(cur.toString());
        }
        List<String> lines = new ArrayList<>();
        String cur = "";
        for (String w : words) {
            String attempt = cur.isEmpty() ? w : cur + " " + w;
            if (fm.stringWidth(attempt) <= maxWidth) {
                cur = attempt;
            } else {
                if (!cur.isEmpty()) {
                    lines.add(cur);
                }
                cur = w;
            }
        }
        if (!cur.isEmpty()) {
            lines.add(cur);
        }
        if (lines.size() > maxLines) {
            lines = new ArrayList<>(lines.subList(0, maxLines));
            String last = YoutubeTitles.strip(lines.get(maxLines - 1), ".,;:- ");
            while (!last.isEmpty() && fm.stringWidth(last + "\u2026") > maxWidth) {
                last = last.substring(0, last.length() - 1);
            }
            lines.set(maxLines - 1, last + "\u2026");
        }
        return lines;
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", out)) {
            throw new IOException("No PNG writer");
        }
        return out.toByteArray();
    }

    // -- digests

    static String sha1(byte[] data) {
        MessageDigest md = sha1();
        return hex(md.digest(data));
    }

    static String sha1(Path p) throws IOException {
        MessageDigest md = sha1();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return hex(md.digest());
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
