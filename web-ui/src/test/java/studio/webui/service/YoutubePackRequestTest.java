/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubePackRequestTest {

    private static YoutubePackException.Code refusal(JsonObject body) {
        return assertThrows(YoutubePackException.class, () -> YoutubePackRequest.fromJson(body)).getCode();
    }

    @Test
    void defaultsAreLuniiPacks() {
        YoutubePackRequest r = YoutubePackRequest.fromJson(new JsonObject().put("urls", "https://youtu.be/abcdefghijk"));
        assertEquals(List.of("https://youtu.be/abcdefghijk"), r.urls);
        assertNull(r.title);
        assertTrue(r.cleanTitles);
        assertTrue(r.sponsorBlock);
        assertTrue(r.seasons);
        assertTrue(r.pauses);
        assertFalse(r.autoplayNext);
        assertEquals(96, r.bitrate);
        assertEquals(10, r.menuSize);
        assertEquals(50, r.maxVideos);
        assertEquals(YoutubeImageStyle.COLOR, r.imageStyle);
    }

    @Test
    void imageStyle() {
        JsonObject body = new JsonObject().put("urls", "https://youtu.be/abcdefghijk");
        assertEquals(YoutubeImageStyle.DRAWING, YoutubePackRequest.fromJson(body.copy().put("imageStyle", "drawing")).imageStyle);
        assertEquals(YoutubeImageStyle.NEGATIVE, YoutubePackRequest.fromJson(body.copy().put("imageStyle", "NEGATIVE")).imageStyle);
        assertEquals(YoutubeImageStyle.COLOR, YoutubePackRequest.fromJson(body.copy().put("imageStyle", "")).imageStyle);
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(body.copy().put("imageStyle", "sepia")));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(body.copy().put("imageStyle", 3)));
    }

    @Test
    void linksAreNormalisedAndDeduplicated() {
        YoutubePackRequest r = YoutubePackRequest.fromJson(new JsonObject().put("urls",
                "  abcdefghijk \n\nwww.youtube.com/playlist?list=PLx\r\nhttps://www.youtube.com/watch?v=abcdefghijk\n"));
        assertEquals(List.of("https://www.youtube.com/watch?v=abcdefghijk", "https://www.youtube.com/playlist?list=PLx"), r.urls);
        assertTrue(YoutubePackRequest.isPlaylistPage(r.urls.get(1)));
        assertFalse(YoutubePackRequest.isPlaylistPage("https://www.youtube.com/watch?v=abcdefghijk&list=PLx"));
    }

    @Test
    void refusals() {
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(null));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(new JsonObject().put("urls", "")));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(new JsonObject().put("urls", "https://evil.example/watch?v=abcdefghijk")));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(new JsonObject().put("urls", "https://user@www.youtube.com/watch?v=abcdefghijk")));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(new JsonObject().put("urls", "--exec calc")));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(new JsonObject().put("urls", new JsonArray().add(3))));
        JsonObject ok = new JsonObject().put("urls", "abcdefghijk");
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(ok.copy().put("bitrate", 100)));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(ok.copy().put("voice", "x; rm")));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(ok.copy().put("engine", "other")));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(ok.copy().put("menuSize", 2.5)));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(ok.copy().put("minDuration", 600).put("maxDuration", 60)));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(ok.copy().put("cleanTitles", "yes")));
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(ok.copy().put("title", "x".repeat(101))));
        JsonArray many = new JsonArray();
        for (int i = 0; i < 21; i++) {
            many.add("https://youtu.be/abcdefghi" + (10 + i));
        }
        assertEquals(YoutubePackException.Code.INVALID_REQUEST, refusal(new JsonObject().put("urls", many)));
    }
}
