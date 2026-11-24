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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.ControlSettings;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;
import studio.metadata.DatabaseMetadataService;
import studio.webui.service.ConversionProvenanceStore;
import studio.webui.service.LibraryService;

import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two purge routes as a script sees them: shapes, statuses and typed errors. */
class LibraryPurgeApiTest {

    private static final String U = "d86a08cd-3fff-5141-94a4-1b6f53a2c1a5";

    @TempDir
    Path root;

    private Path library;
    private Vertx vertx;
    private HttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void start() throws Exception {
        library = root.resolve("library");
        Files.createDirectories(library);
        Files.createDirectories(root.resolve("tmp"));
        Files.createDirectories(root.resolve("db"));
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, library + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp") + File.separator);
        setProperty(ConversionProvenanceStore.CONVERSIONS_DB_PROP, root.resolve("db").resolve("conversions.json").toString());
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, root.resolve("db").resolve("unofficial.json").toString());
        LibraryService service = new LibraryService(new DatabaseMetadataService(true));

        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        // As in MainVerticle.apiRouter()
        router.route().consumes("application/json");
        router.route().produces("application/json");
        router.mountSubRouter("/api/library", LibraryController.apiRouter(vertx, service));
        CompletableFuture<HttpServer> started = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1", ar -> {
            if (ar.succeeded()) {
                started.complete(ar.result());
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        server = started.get(10, TimeUnit.SECONDS);

        // Two identical archives of one pack: the older is a (non-selected) candidate
        writeArchive("pack.zip");
        Files.copy(library.resolve("pack.zip"), library.resolve("copie.zip"));
        Files.setLastModifiedTime(library.resolve("copie.zip"), FileTime.fromMillis(1_700_000_000_000L));
        Files.setLastModifiedTime(library.resolve("pack.zip"), FileTime.fromMillis(1_800_000_000_000L));
    }

    @AfterEach
    void stop() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close(ar -> closed.complete(null));
        closed.get(10, TimeUnit.SECONDS);
        previousProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    @DisplayName("the candidates are listed with path, uuid, format, size, date, reason and default selection")
    void list() throws Exception {
        HttpResponse<String> response = get("/api/library/purge/candidates");

        assertEquals(200, response.statusCode());
        JsonObject body = new JsonObject(response.body());
        JsonArray candidates = body.getJsonArray("candidates");
        assertEquals(1, candidates.size());
        JsonObject candidate = candidates.getJsonObject(0);
        assertEquals("copie.zip", candidate.getString("path"));
        assertEquals(U, candidate.getString("uuid"));
        assertEquals("archive", candidate.getString("format"));
        assertEquals(Files.size(library.resolve("copie.zip")), candidate.getLong("size"));
        assertEquals(1_700_000_000_000L, candidate.getLong("timestamp"));
        assertEquals("DUPLICATE_SOURCE", candidate.getString("reason"));
        assertFalse(candidate.getBoolean("selected"));
        assertEquals("pack.zip", candidate.getString("keptPath"));
        assertEquals(candidate.getLong("size"), body.getLong("totalSize"));
    }

    @Test
    @DisplayName("a purge removes the candidates named, and reports the others")
    void purge() throws Exception {
        long size = Files.size(library.resolve("copie.zip"));

        HttpResponse<String> response = post("/api/library/purge",
                new JsonObject().put("paths", new JsonArray().add("copie.zip").add("pack.zip")));

        assertEquals(200, response.statusCode());
        JsonObject body = new JsonObject(response.body());
        assertFalse(body.getBoolean("success"), "pack.zip is not a candidate");
        assertEquals(size, body.getLong("freed"));
        JsonArray results = body.getJsonArray("results");
        assertTrue(results.getJsonObject(0).getBoolean("removed"));
        assertFalse(results.getJsonObject(1).getBoolean("removed"));
        assertEquals("NOT_A_CANDIDATE", results.getJsonObject(1).getString("error"));
        assertFalse(Files.exists(library.resolve("copie.zip")));
        assertTrue(Files.exists(library.resolve("pack.zip")));
    }

    @Test
    @DisplayName("malformed requests and names outside the library are 400 INVALID_REQUEST, and remove nothing")
    void refusals() throws Exception {
        for (JsonObject body : new JsonObject[]{
                new JsonObject(),
                new JsonObject().put("paths", new JsonArray()),
                new JsonObject().put("paths", "copie.zip"),
                new JsonObject().put("paths", new JsonArray().add(42)),
                new JsonObject().put("paths", new JsonArray().add("copie.zip").add("../outside.zip")),
                new JsonObject().put("paths", new JsonArray().add("copie.zip").add("sub/x.zip"))}) {
            HttpResponse<String> response = post("/api/library/purge", body);
            assertEquals(400, response.statusCode(), "for " + body.encode());
            assertEquals("INVALID_REQUEST", new JsonObject(response.body()).getString("error"));
        }
        assertTrue(Files.exists(library.resolve("copie.zip")));
    }

    private void setProperty(String key, String value) {
        previousProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.actualPort() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, JsonObject body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.actualPort() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.encode())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void writeArchive(String name) throws Exception {
        StageNode node = new StageNode(U, null, null, null, null,
                new ControlSettings(false, false, false, false, false), null);
        StoryPack pack = new StoryPack(U, false, (short) 1, Collections.singletonList(node), null, false);
        try (OutputStream out = Files.newOutputStream(library.resolve(name))) {
            new ArchiveStoryPackWriter().write(pack, out);
        }
    }
}
