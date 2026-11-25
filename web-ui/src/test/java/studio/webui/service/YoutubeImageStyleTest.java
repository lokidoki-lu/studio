/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeImageStyleTest {

    /** A red square on a green background: same-ish brightness, different colours. */
    private static BufferedImage picture() {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 160, 40));
        g.fillRect(0, 0, 320, 240);
        g.setColor(new Color(220, 40, 40));
        g.fillRect(100, 60, 120, 120);
        g.dispose();
        return img;
    }

    private static boolean isGrey(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                if (r != g || g != b) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int grey(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xff;
    }

    @Test
    void ids() {
        assertEquals(Optional.of(YoutubeImageStyle.DRAWING), YoutubeImageStyle.fromId(" Drawing "));
        assertEquals(Optional.empty(), YoutubeImageStyle.fromId("sepia"));
        assertEquals(Optional.empty(), YoutubeImageStyle.fromId(null));
        assertEquals(YoutubeImageStyle.COLOR, YoutubeImageStyle.DEFAULT);
    }

    @Test
    void colorKeepsThePicture() {
        BufferedImage src = picture();
        BufferedImage out = YoutubeImageStyle.COLOR.apply(src);
        assertNotSame(src, out);
        assertEquals(src.getRGB(150, 100), out.getRGB(150, 100));
        assertEquals(src.getRGB(10, 10), out.getRGB(10, 10));
    }

    @Test
    void grayAndNegative() {
        BufferedImage src = picture();
        BufferedImage gray = YoutubeImageStyle.GRAY.apply(src);
        BufferedImage negative = YoutubeImageStyle.NEGATIVE.apply(src);
        assertTrue(isGrey(gray));
        assertTrue(isGrey(negative));
        // Contrast is stretched: the two colours end at the two ends
        assertNotEquals(grey(gray, 150, 100), grey(gray, 10, 10));
        assertEquals(255, Math.max(grey(gray, 150, 100), grey(gray, 10, 10)));
        assertEquals(0, Math.min(grey(gray, 150, 100), grey(gray, 10, 10)));
        // The negative swaps them
        assertEquals(grey(gray, 150, 100), 255 - grey(negative, 150, 100));
        // The source is untouched
        assertEquals(new Color(220, 40, 40).getRGB(), src.getRGB(150, 100));
    }

    @Test
    void drawingIsWhiteOutlinesOnBlack() {
        BufferedImage out = YoutubeImageStyle.DRAWING.apply(picture());
        assertTrue(isGrey(out));
        int white = 0;
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 320; x++) {
                int v = grey(out, x, y);
                assertTrue(v == 0 || v == 255);
                if (v == 255) {
                    white++;
                    // Only around the edges of the square
                    boolean nearEdge = Math.abs(x - 100) <= 2 || Math.abs(x - 219) <= 2
                            || Math.abs(y - 60) <= 2 || Math.abs(y - 179) <= 2;
                    assertTrue(nearEdge, "white at " + x + "," + y);
                }
            }
        }
        assertTrue(white > 400, "outline drawn: " + white);
        // Inside and outside the square are black, and so is the frame
        assertEquals(0, grey(out, 160, 120));
        assertEquals(0, grey(out, 20, 20));
        assertEquals(0, grey(out, 0, 0));
    }

    @Test
    void drawingOfASoftPictureIsNotNoise() {
        // A sky gradient with a little noise (as JPEG leaves), and a pale disc: no clear outline
        java.util.Random random = new java.util.Random(1);
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 320; x++) {
                int base = 120 + y / 4 + random.nextInt(5) - 2;
                boolean disc = (x - 160) * (x - 160) + (y - 120) * (y - 120) < 50 * 50;
                int v = Math.min(255, base + (disc ? 6 : 0));
                img.setRGB(x, y, v << 16 | v << 8 | Math.min(255, v + 40));
            }
        }
        BufferedImage out = YoutubeImageStyle.DRAWING.apply(img);
        int white = 0;
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 320; x++) {
                if (grey(out, x, y) == 255) {
                    white++;
                }
            }
        }
        assertTrue(white < 320 * 240 * 0.15, "white pixels: " + white);
    }

    @Test
    void percentile() {
        int[] v = new int[100];
        for (int i = 0; i < 100; i++) {
            v[i] = i;
        }
        assertEquals(97, YoutubeImageStyle.percentile(v, 0.98));
        assertEquals(0, YoutubeImageStyle.percentile(new int[]{0, 0, 0}, 0.98));
    }

    @Test
    void letterboxBarsAreTrimmed() {
        BufferedImage img = new BufferedImage(480, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 100, 50));
        g.fillRect(0, 45, 480, 270);
        g.dispose();
        BufferedImage trimmed = YoutubePackBuilder.trimBars(img);
        assertEquals(480, trimmed.getWidth());
        assertEquals(270, trimmed.getHeight());
        // A dark picture is not a letterbox
        BufferedImage dark = new BufferedImage(480, 360, BufferedImage.TYPE_INT_RGB);
        dark.setRGB(10, 180, Color.WHITE.getRGB());
        assertEquals(360, YoutubePackBuilder.trimBars(dark).getHeight());
        BufferedImage plain = picture();
        assertEquals(240, YoutubePackBuilder.trimBars(plain).getHeight());
    }

    @Test
    void flatPictures() {
        BufferedImage flat = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        for (YoutubeImageStyle s : YoutubeImageStyle.values()) {
            BufferedImage out = s.apply(flat);
            assertEquals(40, out.getWidth());
            assertEquals(30, out.getHeight());
        }
        assertEquals(0, grey(YoutubeImageStyle.DRAWING.apply(flat), 20, 15));
        assertArrayEquals(new int[]{7, 7, 7}, YoutubeImageStyle.stretch(new int[]{7, 7, 7}, 0.01));
    }

    @Test
    void samplesAndBackgrounds() {
        for (YoutubeImageStyle s : YoutubeImageStyle.values()) {
            BufferedImage sample = s.sample("Titre");
            assertEquals(YoutubePackBuilder.WIDTH, sample.getWidth());
            assertEquals(YoutubePackBuilder.HEIGHT, sample.getHeight());
            if (s != YoutubeImageStyle.COLOR) {
                assertEquals(Color.BLACK, s.background());
            }
        }
        assertNotEquals(Color.BLACK, YoutubeImageStyle.COLOR.background());
        // Text pictures and mosaics take the style's background
        BufferedImage text = YoutubePackBuilder.textImage("Titre", YoutubeImageStyle.DRAWING);
        assertEquals(Color.BLACK.getRGB(), text.getRGB(2, 2));
        List<BufferedImage> thumbs = new ArrayList<>();
        thumbs.add(picture());
        BufferedImage mosaic = YoutubePackBuilder.mosaic(thumbs, "Titre", YoutubeImageStyle.GRAY);
        int p = mosaic.getRGB(5, 5);
        assertEquals((p >> 16) & 0xff, p & 0xff);
    }
}
