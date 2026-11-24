/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which hosts, origins and POST content types the API accepts. */
class RequestOriginPolicyTest {

    @Test
    @DisplayName("only the loopback names are accepted as Host, which defeats DNS rebinding")
    void hosts() {
        for (String ok : List.of("localhost:8080", "127.0.0.1:8080", "LOCALHOST:8080", "localhost", "127.0.0.1")) {
            assertTrue(MainVerticle.isAllowedHost(ok, false), ok);
        }
        for (String ko : List.of("evil.example:8080", "localhost.evil.example:8080", "localhost:3000", "0.0.0.0:8080", "")) {
            assertFalse(MainVerticle.isAllowedHost(ko, false), ko);
        }
        assertFalse(MainVerticle.isAllowedHost(null, false));
        assertTrue(MainVerticle.isAllowedHost("localhost:3000", true));
        assertFalse(MainVerticle.isAllowedHost("evil.example:3000", true));
    }

    @Test
    @DisplayName("only the application's own origin is trusted outside dev mode")
    void origins() {
        String prod = MainVerticle.allowedOriginPattern(false);
        assertTrue("http://localhost:8080".matches(prod));
        assertTrue("http://127.0.0.1:8080".matches(prod));
        assertFalse("http://localhost:3000".matches(prod));
        assertFalse("http://localhost:8080.evil.example".matches(prod));
        assertFalse("https://evil.example".matches(prod));
        assertTrue("http://localhost:3000".matches(MainVerticle.allowedOriginPattern(true)));
    }

    @Test
    @DisplayName("a POST must carry JSON, or a form for an upload only")
    void contentTypes() {
        assertTrue(MainVerticle.isAcceptedPostContentType("application/json", "/api/device/reorder"));
        assertTrue(MainVerticle.isAcceptedPostContentType("application/json; charset=UTF-8", "/api/device/reorder"));
        assertTrue(MainVerticle.isAcceptedPostContentType("multipart/form-data; boundary=x", "/api/library/upload"));
        assertFalse(MainVerticle.isAcceptedPostContentType("multipart/form-data; boundary=x", "/api/library/remove"));
        assertFalse(MainVerticle.isAcceptedPostContentType("text/plain", "/api/library/remove"));
        assertFalse(MainVerticle.isAcceptedPostContentType(null, "/api/library/remove"));
        // An upload takes a form only: a JSON body would be held in memory up to the upload limit
        assertFalse(MainVerticle.isAcceptedPostContentType("application/json", "/api/library/upload"));
    }
}
