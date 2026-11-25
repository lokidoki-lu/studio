/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What to make a pack from, and how: the options of Lunii Pack that make sense in STUdio. Built
 * from the JSON body of {@code POST /api/youtube/jobs}; every field is checked here.
 */
public final class YoutubePackRequest {

    public static final int MAX_LINKS = 20;
    public static final int MAX_TITLE = 100;
    public static final int MAX_DESCRIPTION = 500;
    public static final Set<Integer> BITRATES = Set.of(48, 64, 96, 128);
    public static final int DEFAULT_BITRATE = 96;
    public static final int DEFAULT_MENU_SIZE = 10;
    public static final int DEFAULT_MAX_VIDEOS = 50;
    public static final int MAX_VIDEOS = 200;

    private static final Set<String> HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
            "music.youtube.com", "youtu.be", "www.youtu.be");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    public final List<String> urls;
    /** Pack title, or null: the playlist (or video) title is used. */
    public final String title;
    public final String description;
    public final String voice;
    public final String engine;
    public final boolean cleanTitles;
    public final boolean sponsorBlock;
    public final boolean seasons;
    public final boolean autoplayNext;
    public final boolean pauses;
    /** Seconds, 0 = no bound. */
    public final int minDuration;
    public final int maxDuration;
    public final int menuSize;
    public final int bitrate;
    public final int maxVideos;

    private YoutubePackRequest(List<String> urls, String title, String description, String voice, String engine,
                               boolean cleanTitles, boolean sponsorBlock, boolean seasons, boolean autoplayNext,
                               boolean pauses, int minDuration, int maxDuration, int menuSize, int bitrate, int maxVideos) {
        this.urls = urls;
        this.title = title;
        this.description = description;
        this.voice = voice;
        this.engine = engine;
        this.cleanTitles = cleanTitles;
        this.sponsorBlock = sponsorBlock;
        this.seasons = seasons;
        this.autoplayNext = autoplayNext;
        this.pauses = pauses;
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
        this.menuSize = menuSize;
        this.bitrate = bitrate;
        this.maxVideos = maxVideos;
    }

    public static YoutubePackRequest fromJson(JsonObject body) {
        if (body == null) {
            throw invalid("Le corps de la requ\u00eate est vide");
        }
        Object rawUrls = body.getValue("urls");
        List<String> urls = new ArrayList<>();
        if (rawUrls instanceof String) {
            // One link per line, as pasted in the dialog
            for (String line : ((String) rawUrls).split("\\R")) {
                if (!line.isBlank()) {
                    urls.add(line.trim());
                }
            }
        } else if (rawUrls instanceof JsonArray) {
            for (Object o : (JsonArray) rawUrls) {
                if (!(o instanceof String)) {
                    throw invalid("urls doit contenir des cha\u00eenes");
                }
                if (!((String) o).isBlank()) {
                    urls.add(((String) o).trim());
                }
            }
        } else if (rawUrls != null) {
            throw invalid("urls doit \u00eatre une liste de liens");
        }
        if (urls.isEmpty()) {
            throw invalid("Indiquez au moins un lien YouTube");
        }
        if (urls.size() > MAX_LINKS) {
            throw invalid("Au plus " + MAX_LINKS + " liens \u00e0 la fois");
        }
        List<String> normalized = new ArrayList<>();
        for (String u : urls) {
            String n = normalizeUrl(u);
            if (!normalized.contains(n)) {
                normalized.add(n);
            }
        }

        String title = text(body, "title", MAX_TITLE);
        String description = text(body, "description", MAX_DESCRIPTION);
        String voice = text(body, "voice", 100);
        if (voice != null && !TitleVoiceService.isValidVoice(voice)) {
            throw invalid("Nom de voix invalide : " + voice);
        }
        String engine = text(body, "engine", 20);
        if (engine != null) {
            engine = engine.toLowerCase(Locale.ROOT);
            if (!TitleVoiceService.isKnownEngine(engine)) {
                throw invalid("Moteur de voix inconnu : " + engine);
            }
        }
        int minDuration = integer(body, "minDuration", 0, 0, 24 * 3600);
        int maxDuration = integer(body, "maxDuration", 0, 0, 24 * 3600);
        if (maxDuration > 0 && minDuration > maxDuration) {
            throw invalid("La dur\u00e9e minimale d\u00e9passe la dur\u00e9e maximale");
        }
        int bitrate = integer(body, "bitrate", DEFAULT_BITRATE, 0, 1000);
        if (!BITRATES.contains(bitrate)) {
            throw invalid("D\u00e9bit audio non propos\u00e9 : " + bitrate + " (48, 64, 96 ou 128)");
        }
        return new YoutubePackRequest(List.copyOf(normalized), title, description == null ? "" : description, voice, engine,
                bool(body, "cleanTitles", true), bool(body, "sponsorBlock", true), bool(body, "seasons", true),
                bool(body, "autoplayNext", false), bool(body, "pauses", true),
                minDuration, maxDuration, integer(body, "menuSize", DEFAULT_MENU_SIZE, 0, 50), bitrate,
                integer(body, "maxVideos", DEFAULT_MAX_VIDEOS, 1, MAX_VIDEOS));
    }

    /**
     * A YouTube link as yt-dlp will receive it: http(s), a YouTube host, nothing a command line
     * could take for an option. Short links and bare video ids are accepted.
     */
    static String normalizeUrl(String raw) {
        String u = raw.trim();
        if (VIDEO_ID.matcher(u).matches()) {
            return "https://www.youtube.com/watch?v=" + u;
        }
        if (!u.toLowerCase(Locale.ROOT).startsWith("http://") && !u.toLowerCase(Locale.ROOT).startsWith("https://")) {
            u = "https://" + u;
        }
        URI uri;
        try {
            uri = new URI(u);
        } catch (URISyntaxException e) {
            throw invalid("Lien invalide : " + raw);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!HOSTS.contains(host) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80)) {
            throw invalid("Ce lien n'est pas un lien YouTube : " + raw);
        }
        if (u.chars().anyMatch(c -> c <= ' ' || c == 0x7f)) {
            throw invalid("Lien invalide : " + raw);
        }
        return uri.toString();
    }

    /** Whether the link names a playlist page (and not a video that happens to be in one). */
    public static boolean isPlaylistPage(String url) {
        return url.contains("/playlist") || (url.contains("list=") && !url.contains("v=") && !url.contains("youtu.be/"));
    }

    private static String text(JsonObject body, String field, int max) {
        Object v = body.getValue(field);
        if (v == null) {
            return null;
        }
        if (!(v instanceof String)) {
            throw invalid(field + " doit \u00eatre une cha\u00eene");
        }
        String s = ((String) v).replaceAll("[\\p{Cntrl}]", " ").replaceAll(" {2,}", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.codePointCount(0, s.length()) > max) {
            throw invalid(field + " d\u00e9passe " + max + " caract\u00e8res");
        }
        return s;
    }

    private static boolean bool(JsonObject body, String field, boolean def) {
        Object v = body.getValue(field);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Boolean)) {
            throw invalid(field + " doit \u00eatre vrai ou faux");
        }
        return (Boolean) v;
    }

    private static int integer(JsonObject body, String field, int def, int min, int max) {
        Object v = body.getValue(field);
        if (v == null) {
            return def;
        }
        if (!(v instanceof Number) || ((Number) v).doubleValue() != Math.rint(((Number) v).doubleValue())) {
            throw invalid(field + " doit \u00eatre un nombre entier");
        }
        long n = ((Number) v).longValue();
        if (n < min || n > max) {
            throw invalid(field + " doit \u00eatre entre " + min + " et " + max);
        }
        return (int) n;
    }

    private static YoutubePackException invalid(String message) {
        return new YoutubePackException(YoutubePackException.Code.INVALID_REQUEST, message);
    }
}
