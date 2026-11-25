/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

public interface IStoryTellerService {


    CompletableFuture<Optional<JsonObject>> deviceInfos();

    CompletableFuture<JsonArray> packs();

    CompletableFuture<Optional<String>> addPack(String uuid, File packFile);

    CompletableFuture<Boolean> deletePack(String uuid);

    CompletableFuture<Boolean> reorderPacks(List<String> uuids);

    CompletableFuture<Optional<String>> extractPack(String uuid, File destFile);

    CompletableFuture<Void> dump(String outputPath);

    /** The state of recent transfers, readable over HTTP. */
    TransferRegistry transfers();

    /** Names of the pack content folders on the device that the pack index does not reference. */
    CompletableFuture<JsonArray> orphanFolders();

    /** Removes the named unreferenced pack content folders, and reports what happened to each. */
    CompletableFuture<JsonArray> removeOrphanFolders(List<String> folderNames);

    /**
     * The block size the plugged device's partition reports (V2/V3 only), from which the library
     * estimate derives the cluster to round to. Empty when there is none or it cannot be read.
     */
    default OptionalLong deviceBlockSize() {
        return OptionalLong.empty();
    }

    // ------------------------------------------------------------ device check, repairs, updates
    // Defaults refuse, so that stand-ins (the mock service, test fakes) need not implement them.

    /** What the device check found: see API.md, "Device check and repairs". */
    default CompletableFuture<JsonObject> diagnosis() {
        return CompletableFuture.failedFuture(unsupported());
    }

    /**
     * Discards ({@code adopt == false}) or adopts a pending {@code .pi.new}, if still as checked.
     * {@code deviceToken} is the one the check answered with: refused if the Lunii changed since.
     */
    default CompletableFuture<Boolean> resolvePendingIndex(boolean adopt, String fingerprint, String deviceToken) {
        return CompletableFuture.failedFuture(unsupported());
    }

    /**
     * Finishes or cleans up an interrupted in-place update of the pack folder {@code folder}, if its
     * leftovers are still in {@code state}, on the Lunii {@code deviceToken} names.
     */
    default CompletableFuture<Boolean> resolveInterruptedUpdate(String folder, String state, String deviceToken) {
        return CompletableFuture.failedFuture(unsupported());
    }

    /** As {@code removeOrphanFolders(folderNames)}, refused if {@code deviceToken} no longer names the Lunii. */
    default CompletableFuture<JsonArray> removeOrphanFolders(List<String> folderNames, String deviceToken) {
        return removeOrphanFolders(folderNames);
    }

    /** Removes from the index a pack whose content is missing, only if it still is. */
    default CompletableFuture<Boolean> removeMissingContentEntry(String uuid, String deviceToken) {
        return CompletableFuture.failedFuture(unsupported());
    }

    /** Starts a read-only integrity check of every pack on the device; answers with its id. */
    default CompletableFuture<String> startIntegrityCheck() {
        return CompletableFuture.failedFuture(unsupported());
    }

    /** The state of an integrity check, with its results once done. */
    default Optional<JsonObject> integrityCheck(String id) {
        return Optional.empty();
    }

    /** Queues the in-place replacement of a pack already on the device; answers with a transfer id. */
    default CompletableFuture<Optional<String>> updatePack(String uuid, File packFile) {
        return CompletableFuture.failedFuture(unsupported());
    }

    /** Whether the pack on the device holds exactly what sending {@code packFolder} would write. */
    default CompletableFuture<Boolean> packContentMatches(String uuid, File packFolder) {
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Whether device operations are queued or running: a comparison of the card with the library
     * ({@link #packContentMatches}) is then not made, rather than read a pack being written.
     */
    default boolean deviceBusy() {
        return false;
    }

    /** Identifies the plugged device across sessions (its serial number), if a 2.x+ device is plugged. */
    default CompletableFuture<Optional<String>> fsDeviceKey() {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /** Told after a pack was written to, or removed from, a 2.x+ device. */
    interface DevicePackListener {
        void onWritten(String deviceKey, String uuid, File packFile);

        void onRemoved(String deviceKey, String uuid);
    }

    default void setDevicePackListener(DevicePackListener listener) {
    }

    private static studio.driver.StoryTellerException unsupported() {
        return new studio.driver.StoryTellerException(studio.driver.StoryTellerException.Reason.UNSUPPORTED_ON_DEVICE,
                "Not available with this device service");
    }
}
