/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Tells, for each pack on a 2.x+ Lunii that the library also holds, whether the library has another
 * version of it — and remembers what STUdio wrote on which Lunii, which is what makes that answer
 * reliable.
 *
 * <h2>The criterion</h2>
 *
 * <p>Neither the UUID nor the version number can say it: a pack regenerated from YouTube keeps both
 * (same UUID, version 1). Neither can a modification time. So the comparison is made on content:
 *
 * <ul>
 *     <li>Every library artefact stands for an <em>origin</em>: the SHA-256 of the source it was
 *     converted from when the provenance ledger proves the conversion (the artefact is still exactly
 *     what was recorded), otherwise its own SHA-256 (see {@link LibraryService#originOf}).</li>
 *     <li>Each time STUdio writes a pack on a Lunii (add or update), it records here, for that Lunii
 *     (its serial number) and that UUID, the origin of what it sent.</li>
 *     <li>The library's current content is the origin of the group's most recent artefact, the one
 *     the library lists first and sends.</li>
 *     <li>Update available ⟺ that origin differs from the one recorded for the Lunii.</li>
 * </ul>
 *
 * <p>For a pack STUdio has no record of (sent before this existed, or by another tool), the pack on
 * the card is compared, file by file and byte by byte once transformed for that Lunii, with each
 * folder artefact of the library: an exact match tells what the card holds, and is recorded so that
 * the comparison, which reads the whole pack, is made once. Without a match, a higher library
 * version, or a folder artefact of the current content that the card does not match, still says the
 * library has something else; otherwise the answer is {@code UNKNOWN} and no badge is shown.
 */
public class DeviceUpdateService implements IStoryTellerService.DevicePackListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceUpdateService.class);

    public static final String DEVICE_PACKS_DB_PROP = "studio.db.devicePacks";
    public static final String DEVICE_PACKS_DB_JSON_PATH = "/.studio/db/device-packs.json";

    private final IStoryTellerService storyTellerService;
    private final LibraryService libraryService;
    private CompletableFuture<JsonObject> inFlight;

    public DeviceUpdateService(IStoryTellerService storyTellerService, LibraryService libraryService) {
        this.storyTellerService = storyTellerService;
        this.libraryService = libraryService;
    }

    // ------------------------------------------------------------------ records

    private static Path storePath() {
        return Paths.get(System.getProperty(DEVICE_PACKS_DB_PROP, System.getProperty("user.home") + DEVICE_PACKS_DB_JSON_PATH));
    }

    private synchronized JsonObject readStore() {
        Path path = storePath();
        try {
            if (Files.isRegularFile(path)) {
                JsonObject root = new JsonObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                if (root.getValue("devices") instanceof JsonObject) {
                    return root;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Unreadable record of the packs written to devices: " + path, e);
        }
        return new JsonObject().put("schemaVersion", 1).put("devices", new JsonObject());
    }

    private synchronized void writeStore(JsonObject root) {
        Path path = storePath();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".new");
            Files.write(tmp, root.encodePrettily().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not record the packs written to devices in " + path, e);
        }
    }

    synchronized Optional<String> recordedOrigin(String deviceKey, String uuid) {
        JsonObject device = readStore().getJsonObject("devices").getJsonObject(deviceKey);
        JsonObject pack = device == null ? null : device.getJsonObject(uuid.toLowerCase());
        return Optional.ofNullable(pack == null ? null : pack.getString("origin"));
    }

    synchronized void record(String deviceKey, String uuid, String origin) {
        JsonObject root = readStore();
        JsonObject devices = root.getJsonObject("devices");
        JsonObject device = devices.getJsonObject(deviceKey);
        if (device == null) {
            device = new JsonObject();
            devices.put(deviceKey, device);
        }
        device.put(uuid.toLowerCase(), new JsonObject().put("origin", origin).put("recordedAt", System.currentTimeMillis()));
        writeStore(root);
    }

    synchronized void forget(String deviceKey, String uuid) {
        JsonObject root = readStore();
        JsonObject device = root.getJsonObject("devices").getJsonObject(deviceKey);
        if (device != null && device.remove(uuid.toLowerCase()) != null) {
            writeStore(root);
        }
    }

    @Override
    public void onWritten(String deviceKey, String uuid, File packFile) {
        Optional<String> origin = libraryService.originOf(packFile.getName());
        if (origin.isPresent()) {
            record(deviceKey, uuid, origin.get());
        } else {
            // Not knowing what was sent is not knowing: an older record would now be wrong.
            forget(deviceKey, uuid);
        }
    }

    @Override
    public void onRemoved(String deviceKey, String uuid) {
        forget(deviceKey, uuid);
    }

    // ------------------------------------------------------------------ statuses

    /**
     * The update status of every pack of the plugged device that the library holds. Only one
     * computation runs at a time; a request made meanwhile shares its answer.
     */
    public synchronized CompletableFuture<JsonObject> statuses() {
        if (inFlight != null && !inFlight.isDone()) {
            return inFlight;
        }
        inFlight = storyTellerService.deviceInfos().thenCompose(infos -> {
            if (!infos.isPresent()) {
                return CompletableFuture.completedFuture(new JsonObject().put("supported", false).put("packs", new JsonArray()));
            }
            if (!"fs".equals(infos.get().getString("driver"))) {
                return CompletableFuture.completedFuture(new JsonObject().put("supported", false)
                        .put("driver", infos.get().getString("driver")).put("packs", new JsonArray()));
            }
            return storyTellerService.fsDeviceKey().thenCompose(key -> storyTellerService.packs()
                    .thenApply(devicePacks -> compute(key.orElse(null), devicePacks, libraryService.packs())));
        });
        return inFlight;
    }

    JsonObject compute(String deviceKey, JsonArray devicePacks, JsonArray libraryGroups) {
        Map<String, JsonArray> library = new HashMap<>();
        for (Object g : libraryGroups) {
            JsonObject group = (JsonObject) g;
            library.put(String.valueOf(group.getString("uuid")).toLowerCase(), group.getJsonArray("packs"));
        }
        JsonArray result = new JsonArray();
        for (Object p : devicePacks) {
            JsonObject pack = (JsonObject) p;
            String uuid = String.valueOf(pack.getString("uuid")).toLowerCase();
            JsonArray artefacts = library.get(uuid);
            if (artefacts == null || artefacts.isEmpty()) {
                continue;
            }
            JsonObject latest = artefacts.getJsonObject(0);
            boolean official = Boolean.TRUE.equals(pack.getBoolean("official")) || Boolean.TRUE.equals(latest.getBoolean("official"));
            JsonObject status = new JsonObject()
                    .put("uuid", uuid)
                    .put("deviceVersion", pack.getValue("version"))
                    .put("libraryVersion", latest.getValue("version"))
                    .put("path", latest.getString("path"));
            if (official) {
                // Official packs are never sent by STUdio, so there is nothing to offer.
                result.add(status.put("status", "OFFICIAL"));
                continue;
            }
            result.add(status.mergeIn(statusOf(deviceKey, uuid, pack, artefacts)));
        }
        return new JsonObject().put("supported", true).put("packs", result);
    }

    private JsonObject statusOf(String deviceKey, String uuid, JsonObject devicePack, JsonArray artefacts) {
        JsonObject latest = artefacts.getJsonObject(0);
        Optional<String> latestOrigin = libraryService.originOf(latest.getString("path"));
        Optional<String> recorded = deviceKey == null ? Optional.empty() : recordedOrigin(deviceKey, uuid);

        boolean currentFolderExists = false;
        if (!recorded.isPresent()) {
            // No record: compare the card with each folder artefact, most recent first.
            for (Object a : artefacts) {
                JsonObject artefact = (JsonObject) a;
                if (!"fs".equals(artefact.getString("format"))) {
                    continue;
                }
                Optional<String> origin = libraryService.originOf(artefact.getString("path"));
                if (origin.isPresent() && origin.equals(latestOrigin)) {
                    currentFolderExists = true;
                }
                Optional<File> folder = libraryService.libraryEntry(artefact.getString("path")).map(Path::toFile);
                if (origin.isPresent() && folder.isPresent()
                        && storyTellerService.packContentMatches(uuid, folder.get()).join()) {
                    recorded = origin;
                    if (deviceKey != null) {
                        record(deviceKey, uuid, origin.get());
                    }
                    break;
                }
            }
        }
        if (recorded.isPresent() && latestOrigin.isPresent()) {
            return new JsonObject().put("basis", "content")
                    .put("status", recorded.get().equals(latestOrigin.get()) ? "UP_TO_DATE" : "UPDATE_AVAILABLE");
        }
        if (currentFolderExists) {
            // The library holds the current content ready for this Lunii, and the card is not it.
            return new JsonObject().put("basis", "content").put("status", "UPDATE_AVAILABLE");
        }
        Integer deviceVersion = asInt(devicePack.getValue("version"));
        Integer libraryVersion = asInt(latest.getValue("version"));
        if (deviceVersion != null && libraryVersion != null && libraryVersion > deviceVersion) {
            return new JsonObject().put("basis", "version").put("status", "UPDATE_AVAILABLE");
        }
        return new JsonObject().put("basis", "none").put("status", "UNKNOWN");
    }

    private static Integer asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }
}
