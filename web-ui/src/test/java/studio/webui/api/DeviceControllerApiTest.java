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
import studio.driver.StoryTellerException;
import studio.metadata.DatabaseMetadataService;
import studio.webui.service.IStoryTellerService;
import studio.webui.service.LibraryService;
import studio.webui.service.TransferRegistry;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The device API as a script sees it: statuses, JSON error bodies and the transfer state route.
 * The story teller service is a stand-in, so no device is involved.
 */
class DeviceControllerApiTest {

    @TempDir
    Path root;

    private Vertx vertx;
    private HttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> previousProperties = new HashMap<>();
    private final FakeService service = new FakeService();

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(root.resolve("library"));
        Files.createDirectories(root.resolve("tmp"));
        Path db = Files.createTempDirectory("studio-metadata-db");
        db.toFile().deleteOnExit();
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, root.resolve("library") + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp") + File.separator);
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, db.resolve("unofficial.json").toString());
        LibraryService library = new LibraryService(new DatabaseMetadataService(true));

        vertx = Vertx.vertx();
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        // As in MainVerticle.apiRouter()
        router.route().consumes("application/json");
        router.route().produces("application/json");
        router.mountSubRouter("/api/device", DeviceController.apiRouter(vertx, service, library));
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

    @Test
    @DisplayName("a pack already on the device is a 409 with a code, not a bare 500")
    void alreadyOnDevice() throws Exception {
        service.addPack = () -> CompletableFuture.failedFuture(new StoryTellerException(
                StoryTellerException.Reason.PACK_ALREADY_ON_DEVICE, "The pack is already on the device"));

        HttpResponse<String> response = post("/api/device/addFromLibrary", new JsonObject().put("uuid", "u").put("path", "u.zip"));

        assertEquals(409, response.statusCode());
        JsonObject body = new JsonObject(response.body());
        assertFalse(body.getBoolean("success"));
        assertEquals("PACK_ALREADY_ON_DEVICE", body.getString("error"));
        assertEquals("The pack is already on the device", body.getString("message"));
    }

    @Test
    @DisplayName("no device is a 503, and a missing field a 400")
    void otherRefusals() throws Exception {
        service.addPack = () -> CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE, "No device plugged"));
        assertEquals(503, post("/api/device/addFromLibrary", new JsonObject().put("uuid", "u").put("path", "u.zip")).statusCode());
        assertEquals(400, post("/api/device/addFromLibrary", new JsonObject().put("uuid", "u")).statusCode());
        assertEquals(400, post("/api/device/orphans/remove", new JsonObject().put("folders", new JsonArray())).statusCode());
    }

    @Test
    @DisplayName("an accepted transfer can be followed over HTTP until it ends, failure reason included")
    void transferState() throws Exception {
        String id = service.registry.create("u", "add");
        service.addPack = () -> CompletableFuture.completedFuture(Optional.of(id));

        HttpResponse<String> accepted = post("/api/device/addFromLibrary", new JsonObject().put("uuid", "u").put("path", "u.zip"));
        assertEquals(200, accepted.statusCode());
        assertEquals(id, new JsonObject(accepted.body()).getString("transferId"));

        assertEquals("QUEUED", new JsonObject(get("/api/device/transfer/" + id).body()).getString("state"));

        service.registry.progress(id, 10, 40);
        JsonObject running = new JsonObject(get("/api/device/transfer/" + id).body());
        assertEquals("RUNNING", running.getString("state"));
        assertEquals(0.25, running.getDouble("progress"), 1e-9);

        service.registry.failed(id, new StoryTellerException(StoryTellerException.Reason.NOT_ENOUGH_SPACE, "Not enough free space on the device"));
        JsonObject failed = new JsonObject(get("/api/device/transfer/" + id).body());
        assertTrue(failed.getBoolean("done"));
        assertEquals("NOT_ENOUGH_SPACE", failed.getString("error"));

        assertEquals(1, new JsonArray(get("/api/device/transfers").body()).size());
        HttpResponse<String> unknown = get("/api/device/transfer/nope");
        assertEquals(404, unknown.statusCode());
        assertEquals("TRANSFER_NOT_FOUND", new JsonObject(unknown.body()).getString("error"));
    }

    @Test
    @DisplayName("orphan folders are listed, and removal reports each folder")
    void orphans() throws Exception {
        assertEquals(new JsonArray().add("0A0B0C0D"),
                new JsonObject(get("/api/device/orphans").body()).getJsonArray("folders"));

        HttpResponse<String> removed = post("/api/device/orphans/remove",
                new JsonObject().put("folders", new JsonArray().add("0A0B0C0D")));
        assertEquals(200, removed.statusCode());
        JsonObject body = new JsonObject(removed.body());
        assertTrue(body.getBoolean("success"));
        assertEquals(List.of("0A0B0C0D"), service.removedFolders);
    }

    private static final class FakeService implements IStoryTellerService {
        final TransferRegistry registry = new TransferRegistry();
        Supplier<CompletableFuture<Optional<String>>> addPack = () -> CompletableFuture.completedFuture(Optional.empty());
        final List<String> removedFolders = new ArrayList<>();

        @Override
        public CompletableFuture<Optional<JsonObject>> deviceInfos() {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<JsonArray> packs() {
            return CompletableFuture.completedFuture(new JsonArray());
        }

        @Override
        public CompletableFuture<Optional<String>> addPack(String uuid, File packFile) {
            return addPack.get();
        }

        @Override
        public CompletableFuture<Boolean> deletePack(String uuid) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> reorderPacks(List<String> uuids) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Optional<String>> extractPack(String uuid, File destFile) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletableFuture<Void> dump(String outputPath) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public TransferRegistry transfers() {
            return registry;
        }

        @Override
        public CompletableFuture<JsonArray> orphanFolders() {
            return CompletableFuture.completedFuture(new JsonArray().add("0A0B0C0D"));
        }

        @Override
        public CompletableFuture<JsonArray> removeOrphanFolders(List<String> folderNames) {
            removedFolders.addAll(folderNames);
            JsonArray results = new JsonArray();
            folderNames.forEach(n -> results.add(new JsonObject().put("folder", n).put("removed", true)));
            return CompletableFuture.completedFuture(results);
        }
    }
}
