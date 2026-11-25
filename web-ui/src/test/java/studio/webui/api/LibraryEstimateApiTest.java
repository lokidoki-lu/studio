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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** POST /api/library/estimate as the browser sees it: shape, cluster source, typed errors. */
class LibraryEstimateApiTest {

    private static final String U = "d86a08cd-3fff-5141-94a4-1b6f53a2c1a5";

    @TempDir
    Path root;

    private Path library;
    private Vertx vertx;
    private HttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> previousProperties = new HashMap<>();
    private final AtomicReference<OptionalLong> blockSize = new AtomicReference<>(OptionalLong.empty());

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
        router.route().consumes("application/json");
        router.route().produces("application/json");
        router.mountSubRouter("/api/library", LibraryController.apiRouter(vertx, service, blockSize::get));
        CompletableFuture<HttpServer> started = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1", ar -> {
            if (ar.succeeded()) {
                started.complete(ar.result());
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        server = started.get(10, TimeUnit.SECONDS);

        StageNode node = new StageNode(U, null, null, null, null,
                new ControlSettings(false, false, false, false, false), null);
        StoryPack pack = new StoryPack(U, false, (short) 1, Collections.singletonList(node), null, false);
        try (OutputStream out = Files.newOutputStream(library.resolve("pack.zip"))) {
            new ArchiveStoryPackWriter().write(pack, out);
        }
        Files.write(library.resolve("other.pack"), new byte[3000]);
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
    @DisplayName("several packs for an FS device: one figure each, a total, and the cluster the device reports")
    void fsWithDeviceCluster() throws Exception {
        blockSize.set(OptionalLong.of(16 * 1024));
        HttpResponse<String> response = post(new JsonObject()
                .put("paths", new JsonArray().add("pack.zip").add("nope.zip")).put("format", "fs"));

        assertEquals(200, response.statusCode());
        JsonObject body = new JsonObject(response.body());
        assertEquals("fs", body.getString("format"));
        assertEquals(16 * 1024, body.getLong("clusterSize"));
        assertEquals("device", body.getString("clusterSizeSource"));
        assertEquals(1, body.getJsonArray("packs").size());
        JsonObject pack = body.getJsonArray("packs").getJsonObject(0);
        assertEquals("pack.zip", pack.getString("path"));
        assertEquals(pack.getLong("bytes"), body.getLong("total"));
        // Whole clusters only
        assertEquals(0, pack.getLong("bytes") % (16 * 1024));
        assertEquals("nope.zip", body.getJsonArray("errors").getJsonObject(0).getString("path"));
    }

    @Test
    @DisplayName("one pack for a raw device: sectors, whatever the partition reports")
    void raw() throws Exception {
        HttpResponse<String> response = post(new JsonObject().put("path", "other.pack").put("format", "raw"));

        assertEquals(200, response.statusCode());
        JsonObject body = new JsonObject(response.body());
        assertEquals(512, body.getLong("clusterSize"));
        assertEquals(3072, body.getLong("total"));
        assertEquals("measured", body.getString("method"));
    }

    @Test
    @DisplayName("without a device cluster, 32 KiB is assumed and said so")
    void defaultCluster() throws Exception {
        HttpResponse<String> response = post(new JsonObject().put("paths", new JsonArray().add("pack.zip")).put("format", "fs"));

        JsonObject body = new JsonObject(response.body());
        assertEquals(32 * 1024, body.getLong("clusterSize"));
        assertEquals("default", body.getString("clusterSizeSource"));
        assertTrue(body.getLong("total") >= 32 * 1024);
    }

    @Test
    @DisplayName("a request without paths, with a non-string path or with an unknown format is refused")
    void invalid() throws Exception {
        for (JsonObject body : new JsonObject[]{
                new JsonObject().put("format", "fs"),
                new JsonObject().put("paths", new JsonArray()).put("format", "fs"),
                new JsonObject().put("paths", new JsonArray().add(3)).put("format", "fs"),
                new JsonObject().put("paths", new JsonArray().add("pack.zip")).put("format", "archive"),
                new JsonObject().put("paths", new JsonArray().add("pack.zip"))}) {
            HttpResponse<String> response = post(body);
            assertEquals(400, response.statusCode(), "for " + body.encode());
            assertEquals("INVALID_REQUEST", new JsonObject(response.body()).getString("error"));
        }
    }

    private void setProperty(String key, String value) {
        previousProperties.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private HttpResponse<String> post(JsonObject body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.actualPort() + "/api/library/estimate"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.encode())).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
