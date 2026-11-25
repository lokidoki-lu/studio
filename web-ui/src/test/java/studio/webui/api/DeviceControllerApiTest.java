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
import studio.driver.StoryTellerException;
import studio.metadata.DatabaseMetadataService;
import studio.webui.service.IStoryTellerService;
import studio.webui.service.LibraryService;
import studio.webui.service.TransferRegistry;

import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private static final String U = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    @TempDir
    Path root;

    private Vertx vertx;
    private HttpServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> previousProperties = new HashMap<>();
    private final FakeService service = new FakeService();
    /** Packs the metadata database says are official. */
    private final Set<String> officialUuids = new java.util.HashSet<>();
    /** Whether the official database counts as downloaded. */
    private volatile boolean officialDatabaseAvailable = true;
    private LibraryService library;

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(root.resolve("library"));
        Files.createDirectories(root.resolve("tmp"));
        Path db = Files.createTempDirectory("studio-metadata-db");
        db.toFile().deleteOnExit();
        setProperty(LibraryService.LOCAL_LIBRARY_PROP, root.resolve("library") + File.separator);
        setProperty(LibraryService.TMP_DIR_PROP, root.resolve("tmp") + File.separator);
        setProperty(DatabaseMetadataService.UNOFFICIAL_DB_PROP, db.resolve("unofficial.json").toString());
        setProperty(studio.webui.service.ConversionProvenanceStore.CONVERSIONS_DB_PROP, db.resolve("conversions.json").toString());
        setProperty(studio.webui.service.DeviceSourcedPackStore.DEVICE_SOURCED_DB_PROP, db.resolve("device-sourced.json").toString());
        library = new LibraryService(new DatabaseMetadataService(true) {
            @Override
            public boolean isOfficialPack(String uuid) {
                return officialUuids.contains(uuid);
            }

            @Override
            public boolean isOfficialDatabaseAvailable() {
                return officialDatabaseAvailable;
            }
        });

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

        HttpResponse<String> response = post("/api/device/addFromLibrary", new JsonObject().put("uuid", U).put("path", "u.zip"));

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
        assertEquals(503, post("/api/device/addFromLibrary", new JsonObject().put("uuid", U).put("path", "u.zip")).statusCode());
        assertEquals(400, post("/api/device/addFromLibrary", new JsonObject().put("uuid", U)).statusCode());
        assertEquals(400, post("/api/device/orphans/remove", new JsonObject().put("folders", new JsonArray())).statusCode());
    }

    @Test
    @DisplayName("an accepted transfer can be followed over HTTP until it ends, failure reason included")
    void transferState() throws Exception {
        String id = service.registry.create(U, "add");
        service.addPack = () -> CompletableFuture.completedFuture(Optional.of(id));

        HttpResponse<String> accepted = post("/api/device/addFromLibrary", new JsonObject().put("uuid", U).put("path", "u.zip"));
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

        // Without the token of the device check: refused, nothing removed
        HttpResponse<String> noToken = post("/api/device/orphans/remove",
                new JsonObject().put("folders", new JsonArray().add("0A0B0C0D")));
        assertEquals(400, noToken.statusCode());
        assertEquals("INVALID_REQUEST", new JsonObject(noToken.body()).getString("error"));
        assertTrue(service.removedFolders.isEmpty());

        HttpResponse<String> removed = post("/api/device/orphans/remove",
                new JsonObject().put("folders", new JsonArray().add("0A0B0C0D")).put("deviceToken", "t"));
        assertEquals(200, removed.statusCode());
        JsonObject body = new JsonObject(removed.body());
        assertTrue(body.getBoolean("success"));
        assertEquals(List.of("0A0B0C0D"), service.removedFolders);
    }

    @Test
    @DisplayName("addFromLibrary refuses a path out of the library before anything is read")
    void addFromLibraryIsConfined() throws Exception {
        service.addPack = () -> CompletableFuture.completedFuture(Optional.of("t"));
        for (String path : List.of("../secret", "../../.ssh", "sub/x.zip", "/etc/passwd", "")) {
            HttpResponse<String> response = post("/api/device/addFromLibrary", new JsonObject().put("uuid", U).put("path", path));
            assertEquals(400, response.statusCode(), path);
            assertEquals("INVALID_REQUEST", new JsonObject(response.body()).getString("error"), path);
        }
        assertEquals(null, service.lastPackFile);

        assertEquals(200, post("/api/device/addFromLibrary", new JsonObject().put("uuid", U).put("path", "p.zip")).statusCode());
        assertEquals(root.resolve("library").resolve("p.zip").toAbsolutePath().normalize().toFile(), service.lastPackFile);
    }

    @Test
    @DisplayName("a UUID that is not canonical is refused: its folder would not be the indexed pack's")
    void uuidsMustBeCanonical() throws Exception {
        for (String uuid : List.of("u", "0-0-0-1-2", "../../../tmp/x", "00000000-0000-0000-0001-2")) {
            assertEquals(400, post("/api/device/addFromLibrary", new JsonObject().put("uuid", uuid).put("path", "p.zip")).statusCode(), uuid);
            assertEquals(400, post("/api/device/removeFromDevice", new JsonObject().put("uuid", uuid)).statusCode(), uuid);
            assertEquals(400, post("/api/device/addToLibrary", new JsonObject().put("uuid", uuid).put("driver", "raw")).statusCode(), uuid);
            assertEquals(400, post("/api/device/reorder", new JsonObject().put("uuids", new JsonArray().add(U).add(uuid))).statusCode(), uuid);
        }
        assertEquals(null, service.lastExtractDestination);
        assertEquals(null, service.lastReorder);
        assertEquals("0-0-0-1-2".length() > 0, DeviceController.canonicalUuid("0-0-0-1-2") == null);
        assertEquals(U, DeviceController.canonicalUuid(U.toUpperCase()));
    }

    @Test
    @DisplayName("addToLibrary writes a raw pack inside the library only")
    void addToLibraryDestination() throws Exception {
        libraryArchive(U, "mine.zip");
        officialDatabaseAvailable = true;
        post("/api/device/addToLibrary", new JsonObject().put("uuid", U).put("driver", "raw"));
        assertEquals(root.resolve("library").resolve(U + ".pack").toAbsolutePath().normalize().toFile(), service.lastExtractDestination);
        assertEquals(400, post("/api/device/addToLibrary", new JsonObject().put("uuid", U).put("driver", "zip")).statusCode());
    }

    @Test
    @DisplayName("a missing or malformed body is a 400 with a JSON error, not a 500")
    void malformedBodies() throws Exception {
        for (String route : List.of("removeFromDevice", "reorder", "addToLibrary", "addFromLibrary", "orphans/remove")) {
            HttpResponse<String> empty = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.actualPort() + "/api/device/" + route))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, empty.statusCode(), route);
            HttpResponse<String> broken = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.actualPort() + "/api/device/" + route))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{nope")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, broken.statusCode(), route);
            assertEquals("INVALID_REQUEST", new JsonObject(broken.body()).getString("error"), route);
        }
        // A field of the wrong type is a 400 too, not a ClassCastException answered 500
        assertEquals(400, post("/api/device/addFromLibrary", new JsonObject().put("uuid", 42).put("path", "p.zip")).statusCode());
        assertEquals(400, post("/api/device/removeFromDevice", new JsonObject().put("uuid", new JsonObject())).statusCode());
        assertEquals(400, post("/api/device/reorder", new JsonObject().put("uuids", "x")).statusCode());
        assertEquals(400, post("/api/device/orphans/remove", new JsonObject().put("folders", 1)).statusCode());
    }

    /** An archive made with STUdio: what proves a pack is one of the user's own. */
    private void libraryArchive(String uuid, String name) throws Exception {
        StageNode node = new StageNode(uuid, null, null, null, null,
                new ControlSettings(false, false, false, false, false), null);
        try (OutputStream out = Files.newOutputStream(root.resolve("library").resolve(name))) {
            new ArchiveStoryPackWriter().write(
                    new StoryPack(uuid, false, (short) 1, Collections.singletonList(node), null, false), out);
        }
    }

    @Test
    @DisplayName("addToLibrary refuses an official pack, and a pack not known to be the user's (fail-closed)")
    void addToLibraryRefusesOfficialAndUnknownPacks() throws Exception {
        for (String driver : List.of("raw", "fs")) {
            HttpResponse<String> unknown = post("/api/device/addToLibrary", new JsonObject().put("uuid", U).put("driver", driver));
            assertEquals(400, unknown.statusCode(), driver);
            assertEquals("INVALID_REQUEST", new JsonObject(unknown.body()).getString("error"), driver);
        }
        assertEquals(null, service.lastExtractDestination, "nothing extracted without proof");

        officialUuids.add(U);
        libraryArchive(U, "u.zip");
        HttpResponse<String> official = post("/api/device/addToLibrary", new JsonObject().put("uuid", U).put("driver", "fs"));
        assertEquals(400, official.statusCode());
        assertTrue(new JsonObject(official.body()).getString("message").contains("officiel"), official.body());
        assertEquals(null, service.lastExtractDestination);

        officialUuids.clear();
        officialDatabaseAvailable = false;
        assertEquals(400, post("/api/device/addToLibrary", new JsonObject().put("uuid", U).put("driver", "fs")).statusCode(),
                "nothing leaves the Lunii while the official database is empty");
        assertEquals(null, service.lastExtractDestination);
        officialDatabaseAvailable = true;
        post("/api/device/addToLibrary", new JsonObject().put("uuid", U).put("driver", "fs"));
        assertTrue(service.lastExtractDestination != null, "the user's own pack is extracted");
    }

    @Test
    @DisplayName("a library pack not known to be the user's is not sent, even absent from the official database")
    void unprovenPackIsNotSent() throws Exception {
        libraryFolder(U);
        service.addPack = () -> CompletableFuture.completedFuture(Optional.of("t"));
        for (String route : List.of("addFromLibrary", "updateFromLibrary")) {
            HttpResponse<String> response = post("/api/device/" + route, new JsonObject().put("uuid", U).put("path", U));
            assertEquals(400, response.statusCode(), route);
            assertEquals("INVALID_REQUEST", new JsonObject(response.body()).getString("error"), route);
        }
        assertEquals(null, service.lastPackFile);
        assertEquals(null, service.lastUpdate);

        // A folder named by hand after one of the user's packs is not that pack
        libraryArchive(U, "u.zip");
        assertEquals(400, post("/api/device/addFromLibrary", new JsonObject().put("uuid", U).put("path", U)).statusCode());
        assertEquals(null, service.lastPackFile);
        // Its conversion by STUdio is
        String converted = library.addConvertedFsPackFile("u.zip", false).orElseThrow().toString();
        assertEquals(200, post("/api/device/addFromLibrary", new JsonObject().put("uuid", U).put("path", converted)).statusCode());
        // Sending and updating wait for the official database
        officialDatabaseAvailable = false;
        HttpResponse<String> waiting = post("/api/device/updateFromLibrary", new JsonObject().put("uuid", U).put("path", converted));
        assertEquals(400, waiting.statusCode());
        assertTrue(new JsonObject(waiting.body()).getString("message").contains("base des packs officiels"), waiting.body());
    }

    /** A folder pack of the library, as the listing reads it: its name starts with its UUID. */
    private void libraryFolder(String name) throws Exception {
        Path folder = root.resolve("library").resolve(name);
        Files.createDirectories(folder);
        Files.write(folder.resolve("ni"), new byte[512]);
    }

    private static final String OTHER = "11111111-2222-3333-4444-555566667777";

    @Test
    @DisplayName("an official pack is refused by the server, for an add and for an update")
    void officialPacksRefused() throws Exception {
        officialUuids.add(U);
        libraryFolder(U);
        service.addPack = () -> CompletableFuture.completedFuture(Optional.of("t"));
        for (String route : List.of("addFromLibrary", "updateFromLibrary")) {
            HttpResponse<String> response = post("/api/device/" + route, new JsonObject().put("uuid", U).put("path", U));
            assertEquals(400, response.statusCode(), route);
            assertEquals("INVALID_REQUEST", new JsonObject(response.body()).getString("error"), route);
            assertTrue(new JsonObject(response.body()).getString("message").contains("official"), route);
        }
        assertEquals(null, service.lastPackFile);
        assertEquals(null, service.lastUpdate);
    }

    @Test
    @DisplayName("a path whose pack is not the uuid asked is refused: {uuid: A, path: B} never replaces A by B")
    void uuidMustMatchThePack() throws Exception {
        libraryFolder(OTHER);
        libraryFolder(U + ".converted_1");
        service.addPack = () -> CompletableFuture.completedFuture(Optional.of("t"));
        for (String route : List.of("addFromLibrary", "updateFromLibrary")) {
            HttpResponse<String> response = post("/api/device/" + route, new JsonObject().put("uuid", U).put("path", OTHER));
            assertEquals(400, response.statusCode(), route);
            assertTrue(new JsonObject(response.body()).getString("message").contains(OTHER), route);
        }
        assertEquals(null, service.lastPackFile);
        assertEquals(null, service.lastUpdate);

        // The pack named by the uuid goes through (one of the user's own: its archive is in the library)
        libraryArchive(U, "u.zip");
        String converted = library.addConvertedFsPackFile("u.zip", false).orElseThrow().toString();
        assertEquals(200, post("/api/device/updateFromLibrary", new JsonObject().put("uuid", U).put("path", converted)).statusCode());
        assertEquals(U, service.lastUpdate);
    }

    @Test
    @DisplayName("repairs require the device token (and the state) the check answered with")
    void repairsRequireTheToken() throws Exception {
        assertEquals(400, post("/api/device/repair/pendingIndex", new JsonObject().put("action", "discard").put("fingerprint", "f")).statusCode());
        assertEquals(400, post("/api/device/repair/interruptedUpdate", new JsonObject().put("folder", "AABBCCDD").put("deviceToken", "t")).statusCode());
        assertEquals(400, post("/api/device/repair/missingContent", new JsonObject().put("uuid", U)).statusCode());
        assertEquals(200, post("/api/device/repair/missingContent", new JsonObject().put("uuid", U).put("deviceToken", "t")).statusCode());
        assertEquals(List.of(U + "@t"), service.removedMissing);
        assertEquals(200, post("/api/device/orphans/remove", new JsonObject().put("folders", new JsonArray().add("0A0B0C0D")).put("deviceToken", "t")).statusCode());
        assertEquals("t", service.lastOrphanToken);
    }

    @Test
    @DisplayName("dump writes under STUdio's temporary folder, whatever path the client sends")
    void dumpIsConfined() throws Exception {
        HttpResponse<String> response = post("/api/device/dump", new JsonObject().put("outputPath", root.resolve("elsewhere").toString()));
        assertEquals(200, response.statusCode());
        Path written = Path.of(new JsonObject(response.body()).getString("path"));
        assertTrue(written.toAbsolutePath().normalize().startsWith(root.resolve("tmp").toAbsolutePath().normalize()), written.toString());
        assertFalse(Files.exists(root.resolve("elsewhere")));
    }

    private static final class FakeService implements IStoryTellerService {
        final TransferRegistry registry = new TransferRegistry();
        Supplier<CompletableFuture<Optional<String>>> addPack = () -> CompletableFuture.completedFuture(Optional.empty());
        final List<String> removedFolders = new ArrayList<>();
        File lastPackFile;
        File lastExtractDestination;
        List<String> lastReorder;
        String lastUpdate;
        String lastOrphanToken;
        final List<String> removedMissing = new ArrayList<>();

        @Override
        public CompletableFuture<Optional<String>> updatePack(String uuid, File packFile) {
            lastUpdate = uuid;
            return CompletableFuture.completedFuture(Optional.of("u"));
        }

        @Override
        public CompletableFuture<Boolean> removeMissingContentEntry(String uuid, String deviceToken) {
            removedMissing.add(uuid + "@" + deviceToken);
            return CompletableFuture.completedFuture(true);
        }

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
            lastPackFile = packFile;
            return addPack.get();
        }

        @Override
        public CompletableFuture<Boolean> deletePack(String uuid) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> reorderPacks(List<String> uuids) {
            lastReorder = uuids;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Optional<String>> extractPack(String uuid, File destFile) {
            lastExtractDestination = destFile;
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
        public CompletableFuture<JsonArray> removeOrphanFolders(List<String> folderNames, String deviceToken) {
            lastOrphanToken = deviceToken;
            removedFolders.addAll(folderNames);
            JsonArray results = new JsonArray();
            folderNames.forEach(n -> results.add(new JsonObject().put("folder", n).put("removed", true)));
            return CompletableFuture.completedFuture(results);
        }
    }
}
