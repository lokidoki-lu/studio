/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import studio.driver.model.fs.FsDeviceDiagnosis;
import studio.driver.model.fs.FsPackIntegrity;
import studio.metadata.DatabaseMetadataService;

import java.util.UUID;

/** The JSON shapes of the device check and of the integrity check (see API.md). */
final class DeviceRepairJson {

    private DeviceRepairJson() {
    }

    static JsonObject diagnosis(FsDeviceDiagnosis d) {
        JsonArray orphans = new JsonArray();
        d.getOrphans().forEach(o -> orphans.add(new JsonObject()
                .put("folder", o.getFolder())
                .put("sizeInBytes", o.getSizeInBytes())
                .put("complete", o.isComplete())));
        JsonArray missing = new JsonArray();
        d.getMissingContent().forEach(m -> missing.add(new JsonObject()
                .put("uuid", m.getUuid().toString())
                .put("folder", m.getFolder())
                .put("folderPresent", m.isFolderPresent())));
        JsonArray interrupted = new JsonArray();
        d.getInterruptedUpdates().forEach(u -> interrupted.add(new JsonObject()
                .put("folder", u.getFolder())
                .put("uuid", u.getUuid() == null ? null : u.getUuid().toString())
                .put("state", u.getState().name())
                .put("leftovers", new JsonArray(u.getLeftovers()))));
        JsonObject json = new JsonObject()
                .put("supported", true)
                .put("driver", "fs")
                .put("indexedPacks", d.getIndexedPacks())
                .put("problems", d.problemCount())
                .put("orphans", orphans)
                .put("missingContent", missing)
                .put("interruptedUpdates", interrupted);
        FsDeviceDiagnosis.PendingIndex p = d.getPendingIndex();
        if (p == null) {
            json.putNull("pendingIndex");
        } else {
            json.put("pendingIndex", new JsonObject()
                    .put("kind", p.getKind().name())
                    .put("safeToDiscard", p.isSafeToDiscard())
                    .put("canAdopt", p.getKind() != FsDeviceDiagnosis.PendingIndexKind.TORN
                            && p.getKind() != FsDeviceDiagnosis.PendingIndexKind.ADD_INCOMPLETE)
                    .put("fingerprint", p.getFingerprint())
                    .put("currentCount", p.getCurrentCount())
                    .put("pendingCount", p.getPendingCount())
                    .put("added", uuids(p.getAdded()))
                    .put("removed", uuids(p.getRemoved())));
        }
        return json;
    }

    private static JsonArray uuids(java.util.List<UUID> uuids) {
        JsonArray array = new JsonArray();
        uuids.forEach(u -> array.add(u.toString()));
        return array;
    }

    static JsonObject integrity(FsPackIntegrity r, DatabaseMetadataService metadata) {
        JsonArray problems = new JsonArray();
        r.getProblems().forEach(p -> problems.add(new JsonObject().put("code", p.getCode().name()).put("detail", p.getDetail())));
        JsonObject json = new JsonObject()
                .put("uuid", r.getUuid().toString())
                .put("folder", r.getFolder())
                .put("ok", r.isOk())
                .put("stageNodes", r.getStageNodes())
                .put("images", r.getImages())
                .put("sounds", r.getSounds())
                .put("filesChecked", r.getFilesChecked())
                .put("problems", problems);
        if (metadata != null) {
            metadata.getPackMetadata(r.getUuid().toString()).ifPresent(m -> json
                    .put("title", m.getTitle())
                    .put("official", m.isOfficial()));
        }
        return json;
    }
}
