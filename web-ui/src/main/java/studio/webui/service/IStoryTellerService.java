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
}
