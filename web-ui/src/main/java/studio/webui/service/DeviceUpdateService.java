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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /**
     * Comparisons of the card with a library folder that found a difference, so that a pack never
     * recognised is not read in full again at every listing. Keyed by what would change the answer:
     * the device, the pack, its size and version on the card, the library folder and its origin.
     */
    private final Set<String> knownMismatches = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 500;
        }
    });

    /**
     * How long a listing waits for one comparison of the card with a library folder. The comparison
     * never waits behind a transfer (it is only made when the device queue is idle); this bounds a
     * card that stops answering. Past it, the answer is unknown, and nothing is cached.
     */
    long comparisonTimeoutMillis = TimeUnit.SECONDS.toMillis(Long.getLong("studio.device.comparisonTimeoutSeconds", 60L));

    /** What one comparison of the card with a library folder found. */
    enum Comparison { SAME, DIFFERENT, UNKNOWN, BUSY }

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
        if (Files.isRegularFile(path)) {
            try {
                JsonObject root = new JsonObject(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                if (root.getValue("devices") instanceof JsonObject) {
                    return root;
                }
                throw new IllegalStateException("no 'devices' object");
            } catch (IOException | RuntimeException e) {
                // Set aside, not overwritten by the next record: what it holds may still be recovered.
                Path aside = path.resolveSibling(path.getFileName() + ".corrupt-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date()));
                LOGGER.warn("Unreadable record of the packs written to devices: " + path + ", set aside as " + aside, e);
                try {
                    Files.move(path, aside);
                } catch (IOException | RuntimeException moveFailure) {
                    LOGGER.warn("Could not set aside " + path, moveFailure);
                }
            }
        }
        return new JsonObject().put("schemaVersion", 1).put("devices", new JsonObject());
    }

    private synchronized void writeStore(JsonObject root) {
        Path path = storePath();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName() + ".new");
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(root.encodePrettily().getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                // On disk before it replaces the previous record
                channel.force(true);
            }
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

    /**
     * Records {@code origin} unless a record appeared meanwhile, and answers the record now in place.
     * A comparison of the card takes time: a pack written during it was recorded by {@link #onWritten},
     * and that record, not the older content the comparison read, is what the card holds.
     */
    synchronized String recordIfAbsent(String deviceKey, String uuid, String origin) {
        Optional<String> existing = recordedOrigin(deviceKey, uuid);
        if (existing.isPresent()) {
            return existing.get();
        }
        record(deviceKey, uuid, origin);
        return origin;
    }

    /** Forgets the records of a device's packs that are not on it any more. */
    synchronized void forgetAbsent(String deviceKey, Set<String> presentUuids) {
        JsonObject root = readStore();
        JsonObject device = root.getJsonObject("devices").getJsonObject(deviceKey);
        if (device == null) {
            return;
        }
        boolean changed = false;
        for (String uuid : new HashSet<>(device.fieldNames())) {
            if (!presentUuids.contains(uuid)) {
                device.remove(uuid);
                changed = true;
            }
        }
        if (changed) {
            writeStore(root);
        }
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
        if (deviceKey != null) {
            Set<String> present = new HashSet<>();
            for (Object p : devicePacks) {
                present.add(String.valueOf(((JsonObject) p).getString("uuid")).toLowerCase());
            }
            forgetAbsent(deviceKey, present);
        }
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

        // Only a comparison that found a difference says the card is not the current folder: one that
        // could not be made (device busy, error, timeout) says nothing.
        boolean currentFolderDiffers = false;
        if (!recorded.isPresent()) {
            // No record: compare the card with each folder artefact, most recent first — not while
            // the device is busy (a pack may be being written), and not again for a folder that was
            // already found different from the pack as it is on the card.
            boolean busy = storyTellerService.deviceBusy();
            for (Object a : artefacts) {
                JsonObject artefact = (JsonObject) a;
                if (!"fs".equals(artefact.getString("format"))) {
                    continue;
                }
                Optional<String> origin = libraryService.originOf(artefact.getString("path"));
                boolean current = origin.isPresent() && origin.equals(latestOrigin);
                Optional<File> folder = libraryService.libraryEntry(artefact.getString("path")).map(Path::toFile);
                if (!origin.isPresent() || !folder.isPresent() || busy) {
                    continue;
                }
                String mismatchKey = deviceKey + "|" + uuid + "|" + devicePack.getValue("sizeInBytes") + "|"
                        + devicePack.getValue("version") + "|" + artefact.getString("path") + "|" + origin.get();
                synchronized (knownMismatches) {
                    if (knownMismatches.contains(mismatchKey)) {
                        currentFolderDiffers |= current;
                        continue;
                    }
                }
                Comparison comparison = compare(uuid, folder.get());
                if (comparison == Comparison.SAME) {
                    recorded = deviceKey != null ? Optional.of(recordIfAbsent(deviceKey, uuid, origin.get())) : origin;
                    break;
                }
                if (comparison == Comparison.DIFFERENT) {
                    currentFolderDiffers |= current;
                    // Only a difference is remembered: an error or a timeout is tried again next time.
                    synchronized (knownMismatches) {
                        knownMismatches.add(mismatchKey);
                    }
                } else if (comparison == Comparison.BUSY) {
                    // A transfer came in since deviceBusy(): no more comparisons for this listing.
                    busy = true;
                }
            }
        }
        if (recorded.isPresent() && latestOrigin.isPresent()) {
            return new JsonObject().put("basis", "content")
                    .put("status", recorded.get().equals(latestOrigin.get()) ? "UP_TO_DATE" : "UPDATE_AVAILABLE");
        }
        if (currentFolderDiffers) {
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

    /**
     * Compares the card with a library folder, waiting at most {@link #comparisonTimeoutMillis}. The
     * comparison is refused at once rather than queued behind another device operation (BUSY); one
     * that is not answered in time is abandoned (it is not started if it has not begun) and UNKNOWN.
     */
    Comparison compare(String uuid, File folder) {
        CompletableFuture<Boolean> comparison;
        try {
            comparison = storyTellerService.packContentMatches(uuid, folder);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not compare the pack " + uuid + " on the device with " + folder, e);
            return Comparison.UNKNOWN;
        }
        try {
            Boolean same = comparison.get(comparisonTimeoutMillis, TimeUnit.MILLISECONDS);
            return same == null ? Comparison.UNKNOWN : same ? Comparison.SAME : Comparison.DIFFERENT;
        } catch (TimeoutException e) {
            comparison.cancel(false);
            LOGGER.warn("The comparison of the pack " + uuid + " on the device with " + folder + " took too long: abandoned");
            return Comparison.UNKNOWN;
        } catch (InterruptedException e) {
            comparison.cancel(false);
            Thread.currentThread().interrupt();
            return Comparison.UNKNOWN;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof DeviceOperationQueue.QueueBusyException) {
                return Comparison.BUSY;
            }
            LOGGER.warn("Could not compare the pack " + uuid + " on the device with " + folder + ": " + cause.getMessage());
            return Comparison.UNKNOWN;
        } catch (java.util.concurrent.CancellationException e) {
            return Comparison.UNKNOWN;
        }
    }

    private static Integer asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }
}
