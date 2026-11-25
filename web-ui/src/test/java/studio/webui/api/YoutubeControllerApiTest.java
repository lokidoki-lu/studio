/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.api;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.webui.service.YoutubePackService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /api/youtube} as the library dialog sees it, with stand-in programs. */
class YoutubeControllerApiTest {

    @TempDir
    Path tmp;

    private Vertx vertx;
    private HttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private boolean ffmpegInstalled = true;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws Exception {
        YoutubePackService.CommandRunner runner = (cmd, sink, timeout, cancelled) -> {
            if (cmd.contains("-c")) {
                sink.line("2026.11.23", false);
                return 0;
            }
            if (cmd.get(0).equals("ffmpeg") && cmd.contains("-version")) {
                return ffmpegInstalled ? 0 : 1;
            }
            if (cmd.contains("-J")) {
                // Held until the test lets it go, so that the job is seen running
                while (!cancelled.getAsBoolean() && !release.await(50, TimeUnit.MILLISECONDS)) {
                    // waiting
                }
                if (cancelled.getAsBoolean()) {
                    throw new studio.webui.service.YoutubePackException(studio.webui.service.YoutubePackException.Code.CANCELLED, "annul\u00e9e");
                }
                sink.line(new JsonObject().put("id", "abcdefghijk").put("title", "Une vid\u00e9o").put("duration", 60).encode(), false);
                return 0;
            }
            if (cmd.contains("--write-thumbnail")) {
                Path dir = Paths.get(cmd.get(cmd.indexOf("-o") + 1)).getParent();
                Files.write(dir.resolve("media.m4a"), new byte[]{1, 2, 3});
                return 0;
            }
            Files.write(Paths.get(cmd.get(cmd.size() - 1)), new byte[]{4, 5, 6});
            return 0;
        };
        Path library = Files.createDirectories(tmp.resolve("library"));
        YoutubePackService service = new YoutubePackService(runner, List.of("python"), id -> List.of(), (t, e, v) -> null,
                new YoutubePackService.Library() {
                    @Override
                    public Path directory() {
                        return library;
                    }

                    @Override
                    public boolean add(String name, Path file) {
                        return file.toFile().renameTo(library.resolve(name).toFile());
                    }
                }, tmp.resolve("work"), (ms, c) -> { }, List.of());
        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create().setBodyLimit(1024 * 1024));
        router.mountSubRouter("/api/youtube", YoutubeController.apiRouter(vertx, service));
        CompletableFuture<HttpServer> started = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1", ar -> {
            if (ar.succeeded()) {
                started.complete(ar.result());
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        server = started.get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        release.countDown();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close(ar -> closed.complete(null));
        closed.get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.actualPort() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.actualPort() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private JsonObject waitFor(String id, String state) throws Exception {
        long end = System.currentTimeMillis() + 15_000;
        JsonObject job;
        do {
            job = new JsonObject(get("/api/youtube/jobs/" + id).body());
            if (state.equals(job.getString("state"))) {
                return job;
            }
            Thread.sleep(30);
        } while (System.currentTimeMillis() < end);
        throw new AssertionError("job never reached " + state + ": " + job.encodePrettily());
    }

    @Test
    void toolsAndDefaults() throws Exception {
        HttpResponse<String> r = get("/api/youtube/tools");
        assertEquals(200, r.statusCode());
        JsonObject tools = new JsonObject(r.body());
        assertTrue(tools.getBoolean("ready"));
        assertEquals(96, tools.getInteger("defaultBitrate"));
        assertTrue(tools.getJsonArray("voices").contains("fr-FR-DeniseNeural"));
    }

    @Test
    void missingToolsRefuseTheCreation() throws Exception {
        ffmpegInstalled = false;
        HttpResponse<String> r = post("/api/youtube/jobs", "{\"urls\": [\"https://youtu.be/abcdefghijk\"]}");
        assertEquals(503, r.statusCode());
        JsonObject body = new JsonObject(r.body());
        assertEquals("TOOLS_MISSING", body.getString("error"));
        assertTrue(body.getString("message").contains("ffmpeg"));
        assertFalse(new JsonObject(get("/api/youtube/tools?refresh=true").body()).getBoolean("ready"));
    }

    @Test
    void invalidRequests() throws Exception {
        assertEquals("INVALID_REQUEST", new JsonObject(post("/api/youtube/jobs", "[]").body()).getString("error"));
        HttpResponse<String> notYoutube = post("/api/youtube/jobs", "{\"urls\": \"https://example.com/watch?v=abcdefghijk\"}");
        assertEquals(400, notYoutube.statusCode());
        assertTrue(new JsonObject(notYoutube.body()).getString("message").contains("YouTube"));
        assertEquals(400, post("/api/youtube/jobs", "{\"urls\": \"https://youtu.be/abcdefghijk\", \"bitrate\": 100}").statusCode());
        assertEquals(400, post("/api/youtube/jobs", "{}").statusCode());
        assertEquals(404, get("/api/youtube/jobs/nope").statusCode());
        assertEquals(404, get("/api/youtube/jobs/current").statusCode());
        assertEquals(404, post("/api/youtube/jobs/nope/cancel", "{}").statusCode());
    }

    @Test
    void startFollowAndFinish() throws Exception {
        HttpResponse<String> r = post("/api/youtube/jobs", new JsonObject()
                .put("urls", new JsonArray().add("https://www.youtube.com/watch?v=abcdefghijk")).put("title", "Essai").encode());
        assertEquals(202, r.statusCode(), r.body());
        String id = new JsonObject(r.body()).getString("id");
        assertEquals("RUNNING", new JsonObject(get("/api/youtube/jobs/current").body()).getString("state"));
        assertEquals(409, post("/api/youtube/jobs", "{\"urls\": \"abcdefghijk\"}").statusCode());
        release.countDown();
        JsonObject done = waitFor(id, "DONE");
        assertEquals("Essai.zip", done.getJsonObject("result").getString("path"));
        assertEquals(1, done.getJsonObject("result").getInteger("stories"));
        assertTrue(Files.isRegularFile(tmp.resolve("library").resolve("Essai.zip")));
        assertEquals(id, new JsonObject(get("/api/youtube/jobs/current").body()).getString("id"));
    }

    @Test
    void cancel() throws Exception {
        String id = new JsonObject(post("/api/youtube/jobs", "{\"urls\": \"abcdefghijk\"}").body()).getString("id");
        JsonObject asked = new JsonObject(post("/api/youtube/jobs/" + id + "/cancel", "{}").body());
        assertTrue(asked.getBoolean("cancelRequested"));
        JsonObject cancelled = waitFor(id, "CANCELLED");
        assertEquals("CANCELLED", cancelled.getString("error"));
    }
}
