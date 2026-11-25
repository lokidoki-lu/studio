/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.metadata.StoryPackMetadata;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The archive written for YouTube stories is a pack STUdio reads, with device-sized images. */
class YoutubePackBuilderTest {

    @TempDir
    Path tmp;

    static BufferedImage picture(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private List<YoutubePackBuilder.Story> stories(int n, String group) throws Exception {
        List<YoutubePackBuilder.Story> res = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Path audio = tmp.resolve("a" + i + ".mp3");
            Files.write(audio, ("mp3-" + i).getBytes(StandardCharsets.UTF_8));
            res.add(new YoutubePackBuilder.Story("Histoire " + i, "yt:id" + i, group, audio,
                    i % 2 == 0 ? picture(1280, 720, new Color(200, 10 * i, 0)) : null));
        }
        return res;
    }

    private static JsonObject storyJson(Path pack) throws Exception {
        try (ZipFile z = new ZipFile(pack.toFile())) {
            try (InputStream in = z.getInputStream(z.getEntry("story.json"))) {
                return new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void writesAPackTheReaderAccepts() throws Exception {
        Path out = tmp.resolve("pack.zip");
        byte[] wave = TitleVoiceServiceTest.wave(32000, 1);
        YoutubePackBuilder.Result r = YoutubePackBuilder.build(stories(3, null),
                new YoutubePackBuilder.Settings("Contes \u00e9t\u00e9", "Trois contes", "contes-ete", 10, false),
                text -> wave, out);
        assertEquals(3, r.stories);
        assertEquals(0, r.silentTitles);

        StoryPack pack;
        try (InputStream in = Files.newInputStream(out)) {
            pack = new ArchiveStoryPackReader().read(in);
        }
        assertEquals("Contes \u00e9t\u00e9", pack.getEnriched().getTitle());
        assertEquals("Trois contes", pack.getEnriched().getDescription());
        // cover + (title + story) per video
        assertEquals(7, pack.getStageNodes().size());
        StageNode cover = pack.getStageNodes().get(0);
        assertEquals(r.uuid, cover.getUuid());
        assertNotNull(cover.getAudio());
        for (StageNode s : pack.getStageNodes()) {
            if (s.getImage() != null) {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(s.getImage().getRawData()));
                assertEquals(320, img.getWidth());
                assertEquals(240, img.getHeight());
                assertTrue(s.getImage().getName().endsWith(".png"));
            }
        }
        StoryPackMetadata meta = new ArchiveStoryPackReader().readMetadata(out);
        assertEquals(r.uuid, meta.getUuid());
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(meta.getThumbnail()));
        assertEquals(320, thumb.getWidth());
        assertEquals(240, thumb.getHeight());

        // Story nodes: audio only, autoplay, back to their menu entry at the end
        JsonObject json = storyJson(out);
        JsonArray stages = json.getJsonArray("stageNodes");
        JsonObject story = stages.getJsonObject(2);
        assertEquals("Histoire - Histoire 0", story.getString("name"));
        assertNull(story.getValue("image"));
        assertTrue(story.getString("audio").endsWith(".mp3"));
        assertTrue(story.getJsonObject("controlSettings").getBoolean("autoplay"));
        assertEquals(0, story.getJsonObject("okTransition").getInteger("optionIndex"));
        assertTrue(stages.getJsonObject(1).getString("audio").endsWith(".wav"));
    }

    @Test
    void sameIdentifiersWhenBuiltAgain() throws Exception {
        List<YoutubePackBuilder.Story> s = stories(2, null);
        YoutubePackBuilder.Settings settings = new YoutubePackBuilder.Settings("Pack", "", "pack", 10, false);
        Path a = tmp.resolve("a.zip");
        Path b = tmp.resolve("b.zip");
        YoutubePackBuilder.build(s, settings, text -> null, a);
        YoutubePackBuilder.build(s, settings, text -> null, b);
        assertEquals(storyJson(a).getJsonArray("stageNodes").stream().map(o -> ((JsonObject) o).getString("uuid")).collect(java.util.stream.Collectors.toList()),
                storyJson(b).getJsonArray("stageNodes").stream().map(o -> ((JsonObject) o).getString("uuid")).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void subMenusAndChainedStories() throws Exception {
        List<YoutubePackBuilder.Story> s = new ArrayList<>(stories(4, "Saison 1"));
        s.addAll(stories(3, "Saison 2"));
        Path out = tmp.resolve("menus.zip");
        YoutubePackBuilder.Result r = YoutubePackBuilder.build(s,
                new YoutubePackBuilder.Settings("S\u00e9rie", "", "serie", 5, true), text -> null, out);
        assertEquals(7 + 2 + 1, r.silentTitles); // stories, two groups, cover
        StoryPack pack;
        try (InputStream in = Files.newInputStream(out)) {
            pack = new ArchiveStoryPackReader().read(in);
        }
        // cover + 2 group stages + 7 x 2
        assertEquals(17, pack.getStageNodes().size());
        JsonObject json = storyJson(out);
        List<String> names = new ArrayList<>();
        json.getJsonArray("actionNodes").forEach(o -> names.add(((JsonObject) o).getString("name")));
        assertTrue(names.contains("Menu principal"));
        assertTrue(names.contains("Menu - Saison 1"));
        assertTrue(names.contains("Menu - Saison 2"));
        // The last story of a menu goes on to the first one of the next menu
        JsonObject lastOfFirst = json.getJsonArray("stageNodes").stream().map(o -> (JsonObject) o)
                .filter(o -> o.getString("name").equals("Histoire - Histoire 3")).findFirst().get();
        assertEquals(0, lastOfFirst.getJsonObject("okTransition").getInteger("optionIndex"));
        // Without a voice there is no audio, and the pack is still valid
        assertNull(json.getJsonArray("stageNodes").getJsonObject(0).getValue("audio"));
        try (ZipFile z = new ZipFile(out.toFile())) {
            ZipEntry e = z.getEntry("thumbnail.png");
            assertNotNull(e);
        }
    }

    @Test
    void imageStyleAppliesToEveryPicture() throws Exception {
        List<YoutubePackBuilder.Story> s = new ArrayList<>(stories(3, "Saison 1"));
        s.addAll(stories(2, "Saison 2"));
        Path out = tmp.resolve("gris.zip");
        YoutubePackBuilder.build(s, new YoutubePackBuilder.Settings("Gris", "", "gris", 5, false, YoutubeImageStyle.GRAY),
                text -> null, out);
        int pictures = 0;
        try (ZipFile z = new ZipFile(out.toFile())) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                if (!e.getName().endsWith(".png")) {
                    continue;
                }
                pictures++;
                BufferedImage img;
                try (InputStream in = z.getInputStream(e)) {
                    img = ImageIO.read(in);
                }
                for (int y = 0; y < img.getHeight(); y += 7) {
                    for (int x = 0; x < img.getWidth(); x += 7) {
                        int p = img.getRGB(x, y);
                        assertEquals((p >> 16) & 0xff, p & 0xff, e.getName() + " at " + x + "," + y);
                        assertEquals((p >> 8) & 0xff, p & 0xff, e.getName() + " at " + x + "," + y);
                    }
                }
            }
        }
        // stories (some identical text pictures share a file), cover, groups, thumbnail.png
        assertTrue(pictures >= 5, "pictures: " + pictures);
    }

    @Test
    void imagesAreCroppedAndLabelled() {
        BufferedImage wide = YoutubePackBuilder.fit(picture(1280, 720, Color.RED), 320, 240);
        assertEquals(320, wide.getWidth());
        assertEquals(240, wide.getHeight());
        assertEquals(Color.RED.getRGB(), wide.getRGB(5, 5));
        BufferedImage text = YoutubePackBuilder.textImage("Un titre bien trop long pour tenir sur deux lignes de texte, vraiment beaucoup trop long");
        assertEquals(320, text.getWidth());
        BufferedImage empty = YoutubePackBuilder.mosaic(new ArrayList<>(), "Titre");
        assertEquals(240, empty.getHeight());
    }

    @Test
    void wrappedLinesNeverSplitACharacter() {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 22));
        java.awt.FontMetrics fm = g.getFontMetrics();
        g.dispose();
        // One word far too long for a line, made of characters outside the BMP, then cut to two lines
        List<String> lines = YoutubePackBuilder.wrap("\ud835\udc00".repeat(60) + " fin", fm, 100, 2);
        assertEquals(2, lines.size());
        for (String l : lines) {
            for (int i = 0; i < l.length(); i++) {
                char c = l.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertTrue(i + 1 < l.length() && Character.isLowSurrogate(l.charAt(i + 1)), "lone surrogate in " + l);
                    i++;
                } else {
                    assertTrue(!Character.isLowSurrogate(c), "lone surrogate in " + l);
                }
            }
        }
    }
}
