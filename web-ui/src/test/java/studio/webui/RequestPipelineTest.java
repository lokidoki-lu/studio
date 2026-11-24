/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The real router, deployed: Host check, Content-Type check and body handling in front of the API.
 * Deployed in dev mode (mock device), like {@link StaticResourceConfinementTest}, and skipped when
 * port 8080 is taken.
 */
class RequestPipelineTest {

    private static final List<String> TOUCHED_PROPERTIES = List.of(
            "studio.db.official", "studio.db.unofficial", "studio.library", "studio.tmpdir", "env", "studio.open");

    private final Map<String, String> savedProperties = new HashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path studioHome;

    private Vertx vertx;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(isFree(MainVerticle.LISTEN_PORT), "port " + MainVerticle.LISTEN_PORT + " is already in use");
        for (String property : TOUCHED_PROPERTIES) {
            savedProperties.put(property, System.getProperty(property));
        }
        Path officialDb = studioHome.resolve("official.json");
        Files.write(officialDb, "{}".getBytes(StandardCharsets.UTF_8));
        System.setProperty("studio.db.official", officialDb.toString());
        System.setProperty("studio.db.unofficial", studioHome.resolve("unofficial.json").toString());
        System.setProperty("studio.library", Files.createDirectories(studioHome.resolve("library")) + "/");
        System.setProperty("studio.tmpdir", Files.createDirectories(studioHome.resolve("tmp")) + "/");
        System.setProperty("env", "dev");
        System.setProperty("studio.open", "false");

        vertx = Vertx.vertx();
        CountDownLatch deployed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        vertx.deployVerticle(new MainVerticle(), ar -> {
            failure.set(ar.cause());
            deployed.countDown();
        });
        assertTrue(deployed.await(60, TimeUnit.SECONDS), "deploying MainVerticle timed out");
        if (failure.get() != null) {
            fail("deploying MainVerticle failed", failure.get());
        }
        waitUntilServing();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (vertx != null) {
            CountDownLatch closed = new CountDownLatch(1);
            vertx.close(ar -> closed.countDown());
            closed.await(30, TimeUnit.SECONDS);
        }
        savedProperties.forEach((property, value) -> {
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        });
    }

    private static URI uri(String path) {
        return URI.create("http://127.0.0.1:" + MainVerticle.LISTEN_PORT + path);
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("a JSON POST reaches its route with its body parsed")
    void jsonBodiesAreParsed() throws Exception {
        HttpResponse<String> response = post("/api/device/addFromLibrary", "application/json",
                new JsonObject().put("uuid", "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0").put("path", "../outside.zip").encode());
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("Not an entry of the local library"), response.body());
    }

    @Test
    @DisplayName("a POST that is neither JSON nor an upload form is refused with 415")
    void otherContentTypesAreRefused() throws Exception {
        assertEquals(415, post("/api/device/reorder", "text/plain", "{\"uuids\":[]}").statusCode());
        assertEquals(415, post("/api/library/remove", "application/x-www-form-urlencoded", "path=x").statusCode());
    }

    @Test
    @DisplayName("a JSON body over the JSON limit is refused")
    void largeJsonIsRefused() throws Exception {
        String big = "{\"path\":\"" + "x".repeat((int) MainVerticle.MAX_JSON_BODY_BYTES + 10) + "\"}";
        assertEquals(413, post("/api/library/remove", "application/json", big).statusCode());
    }

    @Test
    @DisplayName("an upload still reaches the library, and leaves nothing in the uploads folder")
    void uploadsStillWork() throws Exception {
        String boundary = "----studio-test";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"uuid\"\r\n\r\nu\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"path\"\r\n\r\nuploaded.zip\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"pack\"; filename=\"uploaded.zip\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\nPACK-BYTES\r\n"
                + "--" + boundary + "--\r\n";
        HttpResponse<String> response = post("/api/library/upload", "multipart/form-data; boundary=" + boundary, body);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("PACK-BYTES", Files.readString(studioHome.resolve("library").resolve("uploaded.zip")));

        // A refused upload: its temporary file is removed when the request ends
        post("/api/library/upload", "multipart/form-data; boundary=" + boundary, body.replace("uploaded.zip\r\n--", "../escape.zip\r\n--"));
        Thread.sleep(200);
        Path uploads = studioHome.resolve("tmp").resolve("file-uploads");
        if (Files.exists(uploads)) {
            try (Stream<Path> left = Files.list(uploads)) {
                assertEquals(0, left.count(), "upload temporaries left behind");
            }
        }
    }

    @Test
    @DisplayName("a request addressed to another host name is refused (DNS rebinding)")
    void otherHostsAreRefused() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(MainVerticle.listenHost(), MainVerticle.LISTEN_PORT), 5_000);
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write("GET /api/library/infos HTTP/1.0\r\nHost: evil.example:8080\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            String status = new String(socket.getInputStream().readNBytes(12), StandardCharsets.UTF_8);
            assertTrue(status.contains("403"), status);
        }
    }

    private static void waitUntilServing() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        do {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(MainVerticle.listenHost(), MainVerticle.LISTEN_PORT), 1_000);
                return;
            } catch (IOException notYet) {
                Thread.sleep(50);
            }
        } while (System.currentTimeMillis() < deadline);
        fail("the application did not start listening");
    }

    private static boolean isFree(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress(MainVerticle.listenHost(), port));
            return true;
        } catch (IOException inUse) {
            return false;
        }
    }
}
