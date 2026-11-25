/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * How the pictures of a pack made from YouTube look: the thumbnails as they are, or turned into
 * something closer to the official packs (white drawings on black). Applied to each picture once
 * it has its final size, before the text band is drawn, so that the titles stay readable.
 */
public enum YoutubeImageStyle {

    /** The thumbnails as they are. */
    COLOR("color"),
    /** Shades of grey, contrast stretched. */
    GRAY("gray"),
    /** White outlines on black, like the drawings of the official packs. */
    DRAWING("drawing"),
    /** Shades of grey, inverted: dark backgrounds, light subjects. */
    NEGATIVE("negative");

    public static final YoutubeImageStyle DEFAULT = COLOR;

    /** Background of the text pictures and of the empty cells, in colour. */
    private static final Color COLOR_BACKGROUND = new Color(30, 40, 80);
    /**
     * Outline strength above which a pixel is drawn, as a share (out of 255) of the strong outlines
     * of the picture (its 98th percentile): adapts to soft and to contrasted pictures alike.
     */
    private static final int DRAWING_THRESHOLD = 70;
    /**
     * Outline strength (Laplacian, 0-255) below which a pixel is never drawn, however soft the
     * picture: the noise of a sky gradient or of the JPEG compression is not an outline.
     */
    private static final int DRAWING_MIN_EDGE = 48;

    public final String id;

    YoutubeImageStyle(String id) {
        this.id = id;
    }

    public static Optional<YoutubeImageStyle> fromId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(s -> s.id.equals(wanted)).findFirst();
    }

    /** Background of a picture without thumbnail (and behind a mosaic). */
    public Color background() {
        return this == COLOR ? COLOR_BACKGROUND : Color.BLACK;
    }

    /** The picture in this style, same size, as a new image (the source is left untouched). */
    public BufferedImage apply(BufferedImage src) {
        switch (this) {
            case GRAY:
                return toImage(stretch(luminance(src), 0.01), src.getWidth(), src.getHeight());
            case NEGATIVE: {
                int[] l = luminance(src);
                for (int i = 0; i < l.length; i++) {
                    l[i] = 255 - l[i];
                }
                return toImage(stretch(l, 0.01), src.getWidth(), src.getHeight());
            }
            case DRAWING:
                return drawing(src);
            default:
                return copy(src);
        }
    }

    // -- the styles

    /**
     * Outlines: a median filter removes the compression noise, then the strongest of the three
     * channels' edges (Laplacian, so that a red on green of the same brightness still shows),
     * thresholded relatively to the strong outlines of the picture but never below a floor, so that a
     * picture without clear outlines stays mostly black rather than turning into noise. The frame of
     * the picture is not an outline.
     */
    private static BufferedImage drawing(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] rgb = src.getRGB(0, 0, w, h, null, 0, w);
        int[] edges = new int[w * h];
        for (int shift = 0; shift <= 16; shift += 8) {
            int[] channel = new int[w * h];
            for (int i = 0; i < channel.length; i++) {
                channel[i] = (rgb[i] >> shift) & 0xff;
            }
            int[] smooth = median3(channel, w, h);
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = y * w + x;
                    int sum = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            sum += smooth[i + dy * w + dx];
                        }
                    }
                    int v = 9 * smooth[i] - sum; // 8 * centre - neighbours
                    edges[i] = Math.max(edges[i], Math.min(255, Math.max(0, v)));
                }
            }
        }
        int threshold = Math.max(DRAWING_MIN_EDGE, percentile(edges, 0.98) * DRAWING_THRESHOLD / 255);
        int[] lit = new int[edges.length];
        int frame = 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                boolean border = x < frame || y < frame || x >= w - frame || y >= h - frame;
                lit[i] = !border && edges[i] > threshold ? 255 : 0;
            }
        }
        // Lines two pixels wide, readable on the small screen: a pixel is lit when it or its left
        // or upper neighbour is (so a lit pixel spreads right and down, never onto the frame)
        int[] thick = new int[w * h];
        for (int y = frame; y < h - frame; y++) {
            for (int x = frame; x < w - frame; x++) {
                int i = y * w + x;
                thick[i] = lit[i] | lit[i - 1] | lit[i - w] | lit[i - w - 1];
            }
        }
        return toImage(thick, w, h);
    }

    /** The value below which the {@code share} of the values lie (values 0-255). */
    static int percentile(int[] v, double share) {
        int[] histogram = new int[256];
        for (int x : v) {
            histogram[x]++;
        }
        long wanted = (long) Math.ceil(v.length * share);
        long seen = 0;
        for (int i = 0; i < 256; i++) {
            seen += histogram[i];
            if (seen >= wanted) {
                return i;
            }
        }
        return 255;
    }

    private static int[] median3(int[] v, int w, int h) {
        int[] out = v.clone();
        int[] window = new int[9];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int k = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        window[k++] = v[(y + dy) * w + x + dx];
                    }
                }
                Arrays.sort(window);
                out[y * w + x] = window[4];
            }
        }
        return out;
    }

    private static int[] luminance(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] rgb = src.getRGB(0, 0, w, h, null, 0, w);
        int[] l = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int r = (rgb[i] >> 16) & 0xff;
            int g = (rgb[i] >> 8) & 0xff;
            int b = rgb[i] & 0xff;
            l[i] = (r * 299 + g * 587 + b * 114) / 1000;
        }
        return l;
    }

    /**
     * Contrast stretching: the darkest and lightest {@code cutoff} of the pixels are ignored, the
     * rest is spread over 0-255. A flat picture is left as it is.
     */
    static int[] stretch(int[] v, double cutoff) {
        int[] histogram = new int[256];
        for (int x : v) {
            histogram[x]++;
        }
        long skip = (long) (v.length * cutoff);
        int lo = 0;
        for (long seen = 0; lo < 255; lo++) {
            seen += histogram[lo];
            if (seen > skip) {
                break;
            }
        }
        int hi = 255;
        for (long seen = 0; hi > 0; hi--) {
            seen += histogram[hi];
            if (seen > skip) {
                break;
            }
        }
        int[] out = new int[v.length];
        if (hi <= lo) {
            System.arraycopy(v, 0, out, 0, v.length);
            return out;
        }
        for (int i = 0; i < v.length; i++) {
            int x = (v[i] - lo) * 255 / (hi - lo);
            out[i] = Math.min(255, Math.max(0, x));
        }
        return out;
    }

    private static BufferedImage toImage(int[] grey, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[grey.length];
        for (int i = 0; i < grey.length; i++) {
            rgb[i] = grey[i] << 16 | grey[i] << 8 | grey[i];
        }
        out.setRGB(0, 0, w, h, rgb, 0, w);
        return out;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    // -- preview

    /**
     * A sample picture for the dialog's preview: a little drawn landscape (no third-party picture
     * ships with STUdio), in this style, with the text band of a story title.
     */
    public BufferedImage sample(String title) {
        int w = YoutubePackBuilder.WIDTH;
        int h = YoutubePackBuilder.HEIGHT;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(110, 180, 240), 0, h * 0.7f, new Color(200, 230, 255)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 205, 60));
        g.fill(new Ellipse2D.Double(240, 22, 50, 50));
        g.setColor(new Color(90, 170, 80));
        g.fill(new Ellipse2D.Double(-80, 150, 300, 200));
        g.setColor(new Color(60, 140, 60));
        g.fill(new Ellipse2D.Double(140, 165, 280, 180));
        // House
        g.setColor(new Color(230, 220, 200));
        g.fillRect(40, 128, 90, 64);
        g.setColor(new Color(190, 60, 50));
        Path2D roof = new Path2D.Double();
        roof.moveTo(30, 130);
        roof.lineTo(85, 88);
        roof.lineTo(140, 130);
        roof.closePath();
        g.fill(roof);
        g.setColor(new Color(120, 80, 50));
        g.fillRect(74, 156, 22, 36);
        g.setColor(new Color(120, 190, 230));
        g.fillRect(50, 140, 18, 16);
        g.fillRect(104, 140, 18, 16);
        // Tree
        g.setColor(new Color(110, 75, 45));
        g.fillRect(232, 140, 14, 50);
        g.setColor(new Color(40, 120, 50));
        g.fill(new Ellipse2D.Double(205, 92, 68, 64));
        g.dispose();
        BufferedImage styled = apply(img);
        YoutubePackBuilder.band(styled, title);
        return styled;
    }
}
