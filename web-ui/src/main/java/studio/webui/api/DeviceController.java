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
import studio.webui.service.IStoryTellerService;
import studio.webui.service.LibraryService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
                            ctx.fail(500, e);
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
        });

        // Plugged device packs list
        router.get("/packs").blockingHandler(ctx -> {
            storyTellerService.packs()
                    .whenComplete((devicePacks, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to read packs from device", e);
                            ctx.fail(500, e);
                        } else {
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(Json.encode(devicePacks));
                        }
                    });
        });

        // Add pack from library to device.
        // Answers at once with a transfer id; the transfer itself is queued behind any other device
        // operation. Its progress and outcome are on the event bus and at GET /transfer/:id.
        // Refusals known up front get a status and a JSON body (see ApiErrors): 409 for a pack
        // already on the device, 503 without a device, 404 for a library file that is not there.
        router.post("/addFromLibrary").blockingHandler(ctx -> {
            JsonObject body = ctx.getBodyAsJson();
            String uuid = body == null ? null : body.getString("uuid");
            String packPath = body == null ? null : body.getString("path");
            if (uuid == null || packPath == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Both 'uuid' and 'path' are required"));
                return;
            }
            File packFile = new File(libraryService.libraryPath() + packPath);
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
        });

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
        });

        // Remove unreferenced pack content folders, named explicitly: {"folders": ["1A2B3C4D", ...]}.
        // A folder the index references is refused, whatever the request says.
        router.post("/orphans/remove").blockingHandler(ctx -> {
            JsonObject body = ctx.getBodyAsJson();
            JsonArray folders = body == null ? null : body.getJsonArray("folders");
            if (folders == null || folders.isEmpty()) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "'folders' must list at least one folder name"));
                return;
            }
            List<String> names = new ArrayList<>();
            for (Object o : folders) {
                names.add(String.valueOf(o));
            }
            storyTellerService.removeOrphanFolders(names)
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
        });

        // Remove pack from device
        router.post("/removeFromDevice").blockingHandler(ctx -> {
            String uuid = ctx.getBodyAsJson().getString("uuid");
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
        });

        // Reorder packs on device
        router.post("/reorder").blockingHandler(ctx -> {
            List<String> uuids = ctx.getBodyAsJson().getJsonArray("uuids").getList();
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
        });

        // Add pack from device to library
        router.post("/addToLibrary").handler(ctx -> {
            String uuid = ctx.getBodyAsJson().getString("uuid");
            String driver = ctx.getBodyAsJson().getString("driver");
            // Transfer pack file to library file
            String path = null;
            if ("raw".equalsIgnoreCase(driver)) {
                path = libraryService.libraryPath() + uuid + ".pack";
            } else if ("fs".equalsIgnoreCase(driver)) {
                path = libraryService.libraryPath();
            } else {
                ctx.fail(400);
                return;
            }
            storyTellerService.extractPack(uuid, new File(path))
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
                                                ctx.fail(500);
                                            }
                                    );
                        }
                    });
        });

        // Dump important sectors
        router.post("/dump").handler(ctx -> {
            String outputPath = ctx.getBodyAsJson().getString("outputPath");
            // Dump important sector into outputPath
            storyTellerService.dump(outputPath)
                    .whenComplete((done, e) -> {
                        if (e != null) {
                            LOGGER.error("Failed to dump important sectors from device", e);
                            ctx.fail(500, e);
                        } else {
                            ctx.response()
                                    .putHeader("content-type", "application/json")
                                    .end(Json.encode(new JsonObject().put("success", true)));
                        }
                    });
        });

        return router;
    }
}
