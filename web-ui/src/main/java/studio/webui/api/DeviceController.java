/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.api;

import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import studio.driver.StoryTellerException;
import studio.webui.service.ApiErrors;
import studio.webui.service.DeviceUpdateService;
import studio.webui.service.IStoryTellerService;
import studio.webui.service.LibrarySendGuard;
import studio.webui.service.LibraryService;

import io.vertx.ext.web.RoutingContext;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DeviceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceController.class);


    public static Router apiRouter(Vertx vertx, IStoryTellerService storyTellerService, LibraryService libraryService) {
        Router router = Router.router(vertx);

        // Plugged device metadata.
        // blockingHandler, not handler: reading device infos opens and reads `.md` straight off the
        // SD card (and, for a 1.x device, issues libusb transfers). Running that on the event loop
        // meant a slow or stalled volume froze every HTTP request the application served — the UI
        // sat on "Vérification de l'appareil..." with no error and no timeout, and only restarting
        // STUdio cleared it. The same reasoning applies to the three routes below.
        router.get("/infos").blockingHandler(ctx -> {
            storyTellerService.deviceInfos()
                    .whenComplete((maybeDeviceInfos, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to read device infos", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            maybeDeviceInfos.ifPresentOrElse(
                                    deviceInfos -> ctx.response()
                                            .putHeader("content-type", "application/json")
                                            .end(Json.encode(deviceInfos.put("plugged", true))),
                                    () -> ctx.response()
                                            .putHeader("content-type", "application/json")
                                            .end(Json.encode(new JsonObject().put("plugged", false)))
                            );
                        }
                    });
        }, false);

        // Plugged device packs list
        router.get("/packs").blockingHandler(ctx -> {
            storyTellerService.packs()
                    .whenComplete((devicePacks, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to read packs from device", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(Json.encode(devicePacks));
                        }
                    });
        }, false);

        // Add pack from library to device.
        // Answers at once with a transfer id; the transfer itself is queued behind any other device
        // operation. Its progress and outcome are on the event bus and at GET /transfer/:id.
        // Refusals known up front get a status and a JSON body (see ApiErrors): 409 for a pack
        // already on the device, 503 without a device, 404 for a library file that is not there.
        router.post("/addFromLibrary").blockingHandler(ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) {
                return;
            }
            String uuid = canonicalUuid(stringField(body, "uuid"));
            String packPath = stringField(body, "path");
            if (uuid == null || packPath == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Both 'uuid' (a pack UUID) and 'path' are required"));
                return;
            }
            // Only a direct entry of the library, never a path that climbs out of it: the file named
            // here is read and copied onto the device, wherever it is.
            File packFile = libraryService.libraryEntry(packPath).map(Path::toFile).orElse(null);
            if (packFile == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Not an entry of the local library: " + packPath));
                return;
            }
            // Never an official pack, and only the pack the uuid names
            Optional<StoryTellerException> refused = LibrarySendGuard.refusal(libraryService, uuid, packPath, packFile.toPath());
            if (refused.isPresent()) {
                ApiErrors.respond(ctx, refused.get());
                return;
            }
            // Start transfer to device
            storyTellerService.addPack(uuid, packFile)
                    .whenComplete((maybeTransferId, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to transfer pack to device", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            maybeTransferId
                                    .ifPresentOrElse(
                                            transferId ->
                                                    // Return the transfer id, which is used to monitor transfer progress
                                                    ctx.response()
                                                            .putHeader("content-type", "application/json")
                                                            .end(Json.encode(new JsonObject().put("transferId", transferId))),
                                            () -> {
                                                LOGGER.error("Failed to transfer pack to device");
                                                ApiErrors.respond(ctx, new StoryTellerException("Failed to transfer pack to device"));
                                            }
                                    );
                        }
                    });
        }, false);

        // State of one transfer: queued, running, succeeded or failed, with its progress and, on
        // failure, the reason. Kept after the transfer ends, so it can be read whenever the client
        // gets round to it.
        router.get("/transfer/:id").handler(ctx -> {
            String id = ctx.pathParam("id");
            storyTellerService.transfers().get(id).ifPresentOrElse(
                    transfer -> ctx.response()
                            .putHeader("content-type", "application/json")
                            .end(Json.encode(transfer)),
                    () -> ctx.response()
                            .setStatusCode(404)
                            .putHeader("content-type", "application/json")
                            .end(Json.encode(new JsonObject()
                                    .put("success", false)
                                    .put("error", "TRANSFER_NOT_FOUND")
                                    .put("message", "No transfer with id " + id))));
        });

        // Every recent transfer, oldest first
        router.get("/transfers").handler(ctx -> ctx.response()
                .putHeader("content-type", "application/json")
                .end(Json.encode(storyTellerService.transfers().list())));

        // Pack content folders the pack index does not reference, typically left by an upload that
        // failed part-way. Read-only.
        router.get("/orphans").blockingHandler(ctx -> {
            storyTellerService.orphanFolders()
                    .whenComplete((folders, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to list unreferenced pack folders", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(Json.encode(new JsonObject().put("folders", folders)));
                        }
                    });
        }, false);

        // Remove unreferenced pack content folders, named explicitly: {"folders": ["1A2B3C4D", ...]}.
        // A folder the index references is refused, whatever the request says.
        router.post("/orphans/remove").blockingHandler(ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) {
                return;
            }
            JsonArray folders = body.getValue("folders") instanceof JsonArray ? body.getJsonArray("folders") : null;
            if (folders == null || folders.isEmpty()) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "'folders' must list at least one folder name"));
                return;
            }
            List<String> names = new ArrayList<>();
            for (Object o : folders) {
                names.add(String.valueOf(o));
            }
            // From the device check, with its token: refused if the Lunii changed since
            String deviceToken = stringField(body, "deviceToken");
            if (deviceToken == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                        "The 'deviceToken' the device check answered with is required"));
                return;
            }
            storyTellerService.removeOrphanFolders(names, deviceToken)
                    .whenComplete((results, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to remove unreferenced pack folders", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            boolean all = results.stream().allMatch(r -> ((JsonObject) r).getBoolean("removed", false));
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(Json.encode(new JsonObject().put("success", all).put("results", results)));
                        }
                    });
        }, false);

        // Device check, repairs and in-place updates: see API.md, "Device check and repairs".
        mountMaintenanceRoutes(router, storyTellerService, libraryService);

        // Remove pack from device
        router.post("/removeFromDevice").blockingHandler(ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) {
                return;
            }
            String uuid = canonicalUuid(stringField(body, "uuid"));
            if (uuid == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "'uuid' must be a pack UUID"));
                return;
            }
            storyTellerService.deletePack(uuid)
                    .whenComplete((removed, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to remove pack from device", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            if (removed) {
                                ctx.response()
                                        .putHeader("content-type", "application/json")
                                        .end(Json.encode(new JsonObject().put("success", true)));
                            } else {
                                LOGGER.error("Pack was not removed from device");
                                ApiErrors.respond(ctx, new StoryTellerException("Pack was not removed from device"));
                            }
                        }
                    });
        }, false);

        // Reorder packs on device
        router.post("/reorder").blockingHandler(ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) {
                return;
            }
            JsonArray requested = body.getValue("uuids") instanceof JsonArray ? body.getJsonArray("uuids") : null;
            List<String> uuids = new ArrayList<>();
            if (requested != null) {
                for (Object o : requested) {
                    String u = o instanceof String ? canonicalUuid((String) o) : null;
                    if (u == null) {
                        requested = null;
                        break;
                    }
                    uuids.add(u);
                }
            }
            if (requested == null || uuids.isEmpty()) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "'uuids' must list pack UUIDs"));
                return;
            }
            storyTellerService.reorderPacks(uuids)
                    .whenComplete((reordered, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to reorder packs on device", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            if (reordered) {
                                ctx.response()
                                        .putHeader("content-type", "application/json")
                                        .end(Json.encode(new JsonObject().put("success", true)));
                            } else {
                                LOGGER.error("Failed to reorder packs on device");
                                ApiErrors.respond(ctx, new StoryTellerException("Failed to reorder packs on device"));
                            }
                        }
                    });
        }, false);

        // Add pack from device to library
        router.post("/addToLibrary").blockingHandler(ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) {
                return;
            }
            String uuid = canonicalUuid(stringField(body, "uuid"));
            String driver = stringField(body, "driver");
            if (uuid == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "'uuid' must be a pack UUID"));
                return;
            }
            // Transfer pack file to library file. The UUID is canonical by now, so the destination is
            // a direct entry of the library.
            File destination;
            if ("raw".equalsIgnoreCase(driver)) {
                destination = libraryService.libraryEntry(uuid + ".pack").map(Path::toFile).orElse(null);
            } else if ("fs".equalsIgnoreCase(driver)) {
                destination = new File(libraryService.libraryPath());
            } else {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "'driver' must be 'raw' or 'fs'"));
                return;
            }
            if (destination == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Not a usable library entry for " + uuid));
                return;
            }
            storyTellerService.extractPack(uuid, destination)
                    .whenComplete((maybeTransferId, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to transfer pack from device", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            maybeTransferId
                                    .ifPresentOrElse(
                                            transferId ->
                                                    // Return the transfer id, which is used to monitor transfer progress
                                                    ctx.response()
                                                            .putHeader("content-type", "application/json")
                                                            .end(Json.encode(new JsonObject().put("transferId", transferId))),
                                            () -> {
                                                LOGGER.error("Failed to transfer pack from device");
                                                ApiErrors.respond(ctx, new StoryTellerException("Failed to transfer pack from device"));
                                            }
                                    );
                        }
                    });
        }, false);

        // Dump important sectors of a 1.x device, for diagnosis. Written to a new folder under STUdio's
        // own temporary directory, whose path is returned: the client no longer chooses where files
        // are written on this computer.
        router.post("/dump").blockingHandler(ctx -> {
            String outputPath;
            try {
                java.nio.file.Path base = java.nio.file.Paths.get(System.getProperty("studio.tmpdir",
                        System.getProperty("user.home") + "/.studio/tmp/"));
                java.nio.file.Files.createDirectories(base);
                outputPath = java.nio.file.Files.createTempDirectory(base, "dump-").toString();
            } catch (java.io.IOException e) {
                ApiErrors.respond(ctx, new StoryTellerException("Could not create the dump folder", e));
                return;
            }
            storyTellerService.dump(outputPath)
                    .whenComplete((done, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to dump important sectors from device", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(Json.encode(new JsonObject().put("success", true).put("path", outputPath)));
                        }
                    });
        }, false);

        return router;
    }

    private static void sendJson(RoutingContext ctx, Object body) {
        ctx.response().putHeader("content-type", "application/json").end(Json.encode(body));
    }

    private static void mountMaintenanceRoutes(Router router, IStoryTellerService storyTellerService, LibraryService libraryService) {
        DeviceUpdateService updates = new DeviceUpdateService(storyTellerService, libraryService);
        storyTellerService.setDevicePackListener(updates);
        // The checks of addFromLibrary / updateFromLibrary, made again when the transfer starts
        storyTellerService.setSendGuard((uuid, packFile) ->
                LibrarySendGuard.refusal(libraryService, uuid, packFile.getName(), packFile.toPath()));

        // Read-only check of the device's bookkeeping. Runs in the device queue.
        router.get("/diagnosis").blockingHandler(ctx -> storyTellerService.diagnosis()
                .whenComplete((diagnosis, e) -> {
                    if (e != null) {
                        LOGGER.error("Failed to check the device", e);
                        ApiErrors.respond(ctx, e);
                    } else {
                        sendJson(ctx, diagnosis);
                    }
                }), false);

        // Discards or adopts a pending .pi.new: {"action": "discard"|"adopt", "fingerprint": "..."}.
        router.post("/repair/pendingIndex").blockingHandler(ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) {
                return;
            }
            String action = stringField(body, "action");
            String fingerprint = stringField(body, "fingerprint");
            String deviceToken = stringField(body, "deviceToken");
            if (!("discard".equals(action) || "adopt".equals(action)) || fingerprint == null || deviceToken == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                        "'action' must be 'discard' or 'adopt', and 'fingerprint' and 'deviceToken' the ones the check reported"));
                return;
            }
            storyTellerService.resolvePendingIndex("adopt".equals(action), fingerprint, deviceToken)
                    .whenComplete((done, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to resolve the pending pack index", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            sendJson(ctx, new JsonObject().put("success", Boolean.TRUE.equals(done)));
                        }
                    });
        }, false);

        // Finishes or cleans up an interrupted in-place update: {"folder": "1A2B3C4D"}.
        router.post("/repair/interruptedUpdate").blockingHandler(ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) {
                return;
            }
            String folder = stringField(body, "folder");
            String state = stringField(body, "state");
            String deviceToken = stringField(body, "deviceToken");
            if (folder == null || state == null || deviceToken == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                        "'folder', and the 'state' and 'deviceToken' the check reported, are required"));
                return;
            }
            storyTellerService.resolveInterruptedUpdate(folder, state, deviceToken)
                    .whenComplete((done, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to resolve the interrupted update", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            sendJson(ctx, new JsonObject().put("success", Boolean.TRUE.equals(done)));
                        }
                    });
        }, false);

        // Removes from the index a pack the check reported without content, if it still has none:
        // {"uuid", "deviceToken"}.
        router.post("/repair/missingContent").blockingHandler(ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) {
                return;
            }
            String uuid = canonicalUuid(stringField(body, "uuid"));
            String deviceToken = stringField(body, "deviceToken");
            if (uuid == null || deviceToken == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                        "'uuid' (a pack UUID) and the 'deviceToken' the check reported are required"));
                return;
            }
            storyTellerService.removeMissingContentEntry(uuid, deviceToken)
                    .whenComplete((done, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to remove the entry without content", e);
                            ApiErrors.respond(ctx, e);
                        } else {
                            sendJson(ctx, new JsonObject().put("success", Boolean.TRUE.equals(done)));
                        }
                    });
        }, false);

        // Starts a read-only integrity check of every pack; its progress and results at GET /integrity/:id.
        router.post("/integrity").blockingHandler(ctx -> storyTellerService.startIntegrityCheck()
                .whenComplete((id, e) -> {
                    if (e != null) {
                        ApiErrors.respond(ctx, e);
                    } else {
                        sendJson(ctx, new JsonObject().put("checkId", id));
                    }
                }), false);

        router.get("/integrity/:id").handler(ctx -> storyTellerService.integrityCheck(ctx.pathParam("id")).ifPresentOrElse(
                state -> sendJson(ctx, state),
                () -> ctx.response().setStatusCode(404).putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", false).put("error", "CHECK_NOT_FOUND")
                                .put("message", "No integrity check with id " + ctx.pathParam("id"))))));

        // Which packs of the device the library holds in another version.
        router.get("/updates").blockingHandler(ctx -> updates.statuses()
                .whenComplete((statuses, e) -> {
                    if (e != null) {
                        LOGGER.error("Failed to compare the device with the library", e);
                        ApiErrors.respond(ctx, e);
                    } else {
                        sendJson(ctx, statuses);
                    }
                }), false);

        // Replaces a pack already on the device by a library folder, keeping its place: {"uuid", "path"}.
        router.post("/updateFromLibrary").blockingHandler(ctx -> {
            JsonObject body = jsonBody(ctx);
            if (body == null) {
                return;
            }
            String uuid = canonicalUuid(stringField(body, "uuid"));
            String packPath = stringField(body, "path");
            if (uuid == null || packPath == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Both 'uuid' (a pack UUID) and 'path' are required"));
                return;
            }
            File packFile = libraryService.libraryEntry(packPath).map(Path::toFile).orElse(null);
            if (packFile == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Not an entry of the local library: " + packPath));
                return;
            }
            Optional<StoryTellerException> refused = LibrarySendGuard.refusal(libraryService, uuid, packPath, packFile.toPath());
            if (refused.isPresent()) {
                ApiErrors.respond(ctx, refused.get());
                return;
            }
            storyTellerService.updatePack(uuid, packFile)
                    .whenComplete((maybeTransferId, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to update pack on device", e);
                            ApiErrors.respond(ctx, e);
                        } else if (maybeTransferId.isPresent()) {
                            sendJson(ctx, new JsonObject().put("transferId", maybeTransferId.get()));
                        } else {
                            ApiErrors.respond(ctx, new StoryTellerException("Failed to update pack on device"));
                        }
                    });
        }, false);
    }

    /**
     * The request's JSON body, or {@code null} once a 400 has been sent for a missing or malformed
     * one: the routes used to throw on either, and answer a bare 500.
     */
    static JsonObject jsonBody(RoutingContext ctx) {
        JsonObject body;
        try {
            body = ctx.getBodyAsJson();
        } catch (RuntimeException e) {
            ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Malformed JSON body", e));
            return null;
        }
        if (body == null) {
            ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "A JSON body is required"));
        }
        return body;
    }

    /** A string field, or {@code null} if it is absent or of another type (a number, an object). */
    static String stringField(JsonObject body, String key) {
        Object value = body.getValue(key);
        return value instanceof String ? (String) value : null;
    }

    /**
     * The UUID in its canonical form, or {@code null} if it is not one. {@code UUID.fromString}
     * accepts shortened groups ("0-0-0-1-2") and reads them as a different UUID than their text: a
     * pack folder named from the text would then not be the folder of the pack the index names.
     */
    static String canonicalUuid(String uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            String canonical = UUID.fromString(uuid).toString();
            return canonical.equalsIgnoreCase(uuid) ? canonical : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
