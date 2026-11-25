/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.api;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import studio.core.v1.Constants;
import studio.driver.StoryTellerException;
import studio.webui.service.ApiErrors;
import studio.webui.service.LibraryService;
import studio.webui.service.ProvenanceVerdict;
import studio.webui.service.PurgeCandidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

public class LibraryController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LibraryController.class);
    
    public static Router apiRouter(Vertx vertx, LibraryService libraryService) {
        return apiRouter(vertx, libraryService, OptionalLong::empty);
    }

    /**
     * @param deviceBlockSize the block size of the plugged device's partition, if any: the cluster
     *                        the space estimate rounds to (see /estimate)
     */
    public static Router apiRouter(Vertx vertx, LibraryService libraryService, Supplier<OptionalLong> deviceBlockSize) {
        Router router = Router.router(vertx);

        /*
         * Ordering. A blockingHandler is ordered by default, and every ordered blocking handler of
         * the verticle shares one FIFO queue (those of DeviceController included): a conversion or a
         * purge listing used to hold back the library listing, a download and the update check for
         * as long as they ran.
         *
         * Reads are therefore unordered (`false`), like /entry and /details already were. They only
         * read files and go through thread-safe state (the Caffeine pack cache, the synchronized
         * metadata database, the provenance ledger read as a whole file that is replaced atomically).
         *
         * Writes (/upload, /convert, /purge, /remove) stay ordered on purpose: they keep being run one
         * at a time, in the order received, and still after the device transfers queued before them,
         * exactly as before. That is the serialisation they were written against (a purge re-checks
         * its candidates against the library as it is when it runs; a conversion and a removal of the
         * same pack never overlap). An explicit lock would add a second mechanism without removing
         * the first one, which device writes rely on.
         */

        // Local library device metadata
        router.get("/infos").blockingHandler(ctx -> {
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(Json.encode(libraryService.libraryInfos()));
        }, false);

        // Local library packs list
        router.get("/packs").blockingHandler(ctx -> {
            JsonArray libraryPacks = libraryService.packs();
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .end(Json.encode(libraryPacks));
        }, false);

        // Local library pack download
        router.post("/download").blockingHandler(ctx -> {
            JsonObject body = DeviceController.jsonBody(ctx);
            if (body == null) {
                return;
            }
            String packPath = DeviceController.stringField(body, "path");
            libraryService.getRawPackFile(packPath)
                    .ifPresentOrElse(
                            file -> ctx.response()
                                    .putHeader("Content-Length", "" + file.length())
                                    .sendFile(file.getAbsolutePath()),
                            () -> {
                                LOGGER.error("Failed to download pack from library");
                                ctx.fail(500);
                            }
                    );
        }, false);

        // One entry of an archive pack of the library (story.json, thumbnail.png, assets/...). The
        // editor opens a library pack entry by entry, rather than downloading the whole archive and
        // unzipping it in the browser. Unordered: the browser asks for several entries at once, and
        // an ordered blocking handler would serve them one after the other.
        router.post("/entry").blockingHandler(ctx -> {
            JsonObject body = DeviceController.jsonBody(ctx);
            if (body == null) {
                return;
            }
            String packPath = DeviceController.stringField(body, "path");
            String entryName = DeviceController.stringField(body, "entry");
            if (packPath == null || entryName == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                        "`path` and `entry` are required strings"));
                return;
            }
            // The editor opens a pack through this route, entry by entry: never an official pack, or
            // one that may come from a Lunii, which it would save again under a new UUID. The library
            // preview reads entries too, and says so (purpose: preview).
            Optional<String> editorRefusal = "preview".equals(body.getValue("purpose"))
                    ? Optional.empty() : libraryService.editorRefusal(packPath);
            if (editorRefusal.isPresent()) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, editorRefusal.get()));
                return;
            }
            Optional<byte[]> entry;
            try {
                entry = libraryService.readArchivePackEntry(packPath, entryName);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Failed to read entry " + entryName + " of library pack " + packPath, e);
                ApiErrors.respond(ctx, new StoryTellerException("Failed to read pack entry", e));
                return;
            }
            entry.ifPresentOrElse(
                    bytes -> ctx.response()
                            .putHeader("content-type", "application/octet-stream")
                            // The same entry name may hold other content once the pack is saved again
                            .putHeader("Cache-Control", "no-store")
                            .end(Buffer.buffer(bytes)),
                    () -> ctx.response().setStatusCode(404).end()
            );
        }, false);

        // What the library preview shows of a pack beyond its metadata (size, node counts, audio
        // duration), read from headers only. Unordered, like /entry: the preview asks for it while
        // it reads entries.
        router.post("/details").blockingHandler(ctx -> {
            JsonObject body = DeviceController.jsonBody(ctx);
            if (body == null) {
                return;
            }
            String packPath = DeviceController.stringField(body, "path");
            if (packPath == null) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                        "`path` is a required string"));
                return;
            }
            Optional<JsonObject> details;
            try {
                details = libraryService.packDetails(packPath);
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Failed to read the details of library pack " + packPath, e);
                ApiErrors.respond(ctx, new StoryTellerException("Failed to read pack details", e));
                return;
            }
            details.ifPresentOrElse(
                    json -> ctx.response()
                            .putHeader("content-type", "application/json")
                            .putHeader("Cache-Control", "no-store")
                            .end(Json.encode(json)),
                    () -> ctx.response().setStatusCode(404).end()
            );
        }, false);

        // The space packs will take on the device, once in its format (fs or raw), estimated from
        // headers without converting anything. Unordered, like /details: it only reads.
        router.post("/estimate").blockingHandler(ctx -> {
            JsonObject body = DeviceController.jsonBody(ctx);
            if (body == null) {
                return;
            }
            List<String> paths = new ArrayList<>();
            Object many = body.getValue("paths");
            if (many instanceof JsonArray) {
                for (Object path : (JsonArray) many) {
                    if (!(path instanceof String)) {
                        paths = null;
                        break;
                    }
                    paths.add((String) path);
                }
            } else if (DeviceController.stringField(body, "path") != null) {
                paths.add(DeviceController.stringField(body, "path"));
            }
            String format = DeviceController.stringField(body, "format");
            if (paths == null || paths.isEmpty() || paths.size() > 500
                    || !(Constants.PACK_FORMAT_FS.equalsIgnoreCase(format) || Constants.PACK_FORMAT_RAW.equalsIgnoreCase(format))) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                        "`paths` (1 to 500 strings, or `path`) and `format` (fs or raw) are required"));
                return;
            }
            OptionalLong blockSize;
            try {
                blockSize = deviceBlockSize.get();
            } catch (RuntimeException e) {
                blockSize = OptionalLong.empty();
            }
            JsonObject estimate = libraryService.estimateOnDevice(paths, format.toLowerCase(java.util.Locale.ROOT), blockSize);
            ctx.response()
                    .putHeader("content-type", "application/json")
                    .putHeader("Cache-Control", "no-store")
                    .end(Json.encode(estimate));
        }, false);

        // Local library pack upload
        router.post("/upload").blockingHandler(ctx -> {
            String packPath = ctx.request().getFormAttribute("path");
            if (ctx.fileUploads().isEmpty()) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                        "A multipart/form-data body with the pack file is required"));
                return;
            }
            boolean added;
            try {
                added = libraryService.addPackFile(packPath, ctx.fileUploads().iterator().next().uploadedFileName());
            } catch (StoryTellerException e) {
                // Refused (an archive that may hold a pack from a Lunii): said as such, not a 500
                LOGGER.error("Pack was not added to library", e);
                ApiErrors.respond(ctx, e);
                return;
            }
            if (added) {
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", true)));
            } else {
                LOGGER.error("Pack was not added to library");
                ctx.fail(500);
            }
        });

        // Local library pack conversion
        router.post("/convert").blockingHandler(ctx -> {
            JsonObject body = DeviceController.jsonBody(ctx);
            if (body == null) {
                return;
            }
            String packPath = DeviceController.stringField(body, "path");
            boolean allowEnriched = Boolean.parseBoolean(String.valueOf(body.getValue("allowEnriched")));
            String format = DeviceController.stringField(body, "format");
            // The conversion runs right here, on the worker thread the blocking handler already
            // provides. It used to be handed to a new java.util.Timer per request, after a one
            // second delay: each Timer kept a thread alive for the rest of the session, and an
            // exception thrown by the conversion killed that thread before the answer was sent, so
            // the request never completed and the client waited until its own timeout.
            Optional<Path> converted;
            String failureMessage;
            try {
                if (Constants.PACK_FORMAT_RAW.equalsIgnoreCase(format)) {
                    failureMessage = "Failed to read or convert pack to raw format";
                    converted = libraryService.addConvertedRawPackFile(packPath, allowEnriched);
                } else if (Constants.PACK_FORMAT_FS.equalsIgnoreCase(format)) {
                    failureMessage = "Failed to read or convert pack to folder format";
                    converted = libraryService.addConvertedFsPackFile(packPath, allowEnriched);
                } else if (Constants.PACK_FORMAT_ARCHIVE.equalsIgnoreCase(format)) {
                    failureMessage = "Failed to read or convert pack to archive format";
                    converted = libraryService.addConvertedArchivePackFile(packPath);
                } else {
                    ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Unknown pack format: " + format));
                    return;
                }
            } catch (IllegalArgumentException e) {
                // A path outside the library: a malformed request, refused as such.
                LOGGER.error("Refused a conversion request", e);
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, e.getMessage(), e));
                return;
            } catch (RuntimeException e) {
                LOGGER.error("Failed to read or convert pack", e);
                ApiErrors.respond(ctx, e);
                return;
            }
            if (converted.isPresent()) {
                // Return path to converted file within library
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject()
                                .put("success", true)
                                .put("path", converted.get().toString())
                        ));
            } else {
                LOGGER.error("Failed to read or convert pack");
                ApiErrors.respond(ctx, new StoryTellerException(failureMessage));
            }
        });

        // Whether a converted pack still corresponds to the source it was made from. Only ever
        // asked when a pack is dropped on a device, never while listing: it reads both artefacts.
        // The answer is a verdict and nothing else — no digest, no record, no path — because the
        // client has no use for them and no business deciding this for itself.
        router.post("/verify-conversion").blockingHandler(ctx -> {
            JsonObject body = DeviceController.jsonBody(ctx);
            if (body == null) {
                return;
            }
            String sourcePath = DeviceController.stringField(body, "sourcePath");
            String convertedPath = DeviceController.stringField(body, "convertedPath");
            try {
                ProvenanceVerdict verdict = libraryService.verifyConversion(sourcePath, convertedPath);
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("verdict", verdict.name())));
            } catch (IllegalArgumentException e) {
                // Naming something outside the library is a malformed request, not an uncertainty.
                // Answering UNKNOWN here would quietly accept a question that should be refused.
                LOGGER.error("Refused a conversion verification request", e);
                ctx.fail(400, e);
            } catch (RuntimeException e) {
                LOGGER.error("Failed to verify a conversion", e);
                ApiErrors.respond(ctx, e);
            }
        }, false);

        // Library entries that serve no purpose any more (see LibraryService.purgeCandidates).
        // Read-only. Reads artefacts in full when provenance or duplicates have to be established.
        router.get("/purge/candidates").blockingHandler(ctx -> {
            try {
                List<PurgeCandidate> candidates = libraryService.purgeCandidates();
                JsonArray list = new JsonArray();
                long total = 0;
                for (PurgeCandidate candidate : candidates) {
                    list.add(candidate.toJson());
                    total += Math.max(candidate.getSize(), 0);
                }
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("candidates", list).put("totalSize", total)));
            } catch (RuntimeException e) {
                LOGGER.error("Failed to list purge candidates", e);
                ApiErrors.respond(ctx, e);
            }
        }, false);

        // Remove purge candidates, named explicitly: {"paths": ["<library entry>", ...]}. Each one is
        // checked again against the candidates as they are now; anything else is left alone.
        router.post("/purge").blockingHandler(ctx -> {
            JsonObject body = DeviceController.jsonBody(ctx);
            if (body == null) {
                return;
            }
            JsonArray paths = body.getValue("paths") instanceof JsonArray ? body.getJsonArray("paths") : null;
            if (paths == null || paths.isEmpty()) {
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "'paths' must list at least one library entry"));
                return;
            }
            List<String> names = new ArrayList<>();
            for (Object o : paths) {
                if (!(o instanceof String)) {
                    ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "'paths' must only contain library entry names"));
                    return;
                }
                names.add((String) o);
            }
            try {
                List<LibraryService.PurgeResult> results = libraryService.purge(names);
                JsonArray list = new JsonArray();
                long freed = 0;
                for (LibraryService.PurgeResult result : results) {
                    list.add(result.toJson());
                    freed += result.getSize();
                }
                boolean all = results.stream().allMatch(LibraryService.PurgeResult::isRemoved);
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", all).put("freed", freed).put("results", list)));
            } catch (IllegalArgumentException e) {
                // A name outside the library: refused before anything is removed.
                LOGGER.error("Refused a purge request", e);
                ApiErrors.respond(ctx, new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, e.getMessage(), e));
            } catch (RuntimeException e) {
                LOGGER.error("Failed to purge the library", e);
                ApiErrors.respond(ctx, e);
            }
        });

        // Remove pack from device
        router.post("/remove").blockingHandler(ctx -> {
            JsonObject body = DeviceController.jsonBody(ctx);
            if (body == null) {
                return;
            }
            String packPath = DeviceController.stringField(body, "path");
            boolean removed = libraryService.deletePack(packPath);
            if (removed) {
                ctx.response()
                        .putHeader("content-type", "application/json")
                        .end(Json.encode(new JsonObject().put("success", true)));
            } else {
                LOGGER.error("Pack was not removed from library");
                ctx.fail(500);
            }
        });

        return router;
    }
}
