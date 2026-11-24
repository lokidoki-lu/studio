/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.driver.StoryTellerException;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferRegistryTest {

    @Test
    @DisplayName("a transfer goes from queued to running to succeeded, with its progress")
    void lifecycle() {
        TransferRegistry registry = new TransferRegistry();
        String id = registry.create("11111111-2222-3333-4444-555555555555", "add");

        JsonObject queued = registry.get(id).orElseThrow();
        assertEquals("QUEUED", queued.getString("state"));
        assertFalse(queued.getBoolean("done"));

        registry.started(id);
        registry.progress(id, 50, 200);
        JsonObject running = registry.get(id).orElseThrow();
        assertEquals("RUNNING", running.getString("state"));
        assertEquals(0.25, running.getDouble("progress"), 1e-9);
        assertEquals(50L, running.getLong("transferred"));

        registry.succeeded(id);
        JsonObject done = registry.get(id).orElseThrow();
        assertEquals("SUCCEEDED", done.getString("state"));
        assertTrue(done.getBoolean("done"));
        assertTrue(done.getBoolean("success"));
        assertEquals(1.0, done.getDouble("progress"), 1e-9);
    }

    @Test
    @DisplayName("progress reported out of order never moves backwards")
    void progressIsMonotonic() {
        TransferRegistry registry = new TransferRegistry();
        String id = registry.create("u", "add");
        registry.progress(id, 150, 200);
        registry.progress(id, 100, 200);
        assertEquals(150L, registry.get(id).orElseThrow().getLong("transferred"));
    }

    @Test
    @DisplayName("a failure keeps its reason and the driver's message, through future wrappers")
    void failureKeepsTheReason() {
        TransferRegistry registry = new TransferRegistry();
        String id = registry.create("u", "add");
        registry.failed(id, new CompletionException(new StoryTellerException("Failed to copy pack",
                new StoryTellerException(StoryTellerException.Reason.NOT_ENOUGH_SPACE, "Not enough free space on the device"))));

        JsonObject failed = registry.get(id).orElseThrow();
        assertEquals("FAILED", failed.getString("state"));
        assertFalse(failed.getBoolean("success"));
        assertEquals("NOT_ENOUGH_SPACE", failed.getString("error"));
        assertEquals("Failed to copy pack: Not enough free space on the device", failed.getString("message"));

        // A finished transfer is final: a late success report does not overwrite the failure.
        registry.succeeded(id);
        assertEquals("FAILED", registry.get(id).orElseThrow().getString("state"));
    }

    @Test
    @DisplayName("an unknown failure is reported as INTERNAL_ERROR, with a 500")
    void unknownFailure() {
        TransferRegistry registry = new TransferRegistry();
        String id = registry.create("u", "add");
        registry.failed(id, new IllegalStateException("disk vanished"));
        assertEquals("INTERNAL_ERROR", registry.get(id).orElseThrow().getString("error"));
        assertEquals(500, ApiErrors.statusOf(StoryTellerException.Reason.UNKNOWN));
    }

    @Test
    @DisplayName("finished transfers are pruned beyond the limit, running ones never")
    void pruning() {
        TransferRegistry registry = new TransferRegistry();
        String running = registry.create("running", "add");
        registry.started(running);
        String oldest = null;
        for (int i = 0; i < TransferRegistry.MAX_FINISHED + 10; i++) {
            String id = registry.create("u" + i, "add");
            if (oldest == null) {
                oldest = id;
            }
            registry.succeeded(id);
        }
        assertTrue(registry.get(running).isPresent());
        assertFalse(registry.get(oldest).isPresent());
        assertEquals(TransferRegistry.MAX_FINISHED + 1, registry.list().size());
    }

    @Test
    @DisplayName("each refusal reason maps to the HTTP status a client can act on")
    void statuses() {
        assertEquals(503, ApiErrors.statusOf(StoryTellerException.Reason.NO_DEVICE));
        assertEquals(409, ApiErrors.statusOf(StoryTellerException.Reason.PACK_ALREADY_ON_DEVICE));
        assertEquals(409, ApiErrors.statusOf(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS));
        assertEquals(409, ApiErrors.statusOf(StoryTellerException.Reason.FOLDER_STILL_REFERENCED));
        assertEquals(404, ApiErrors.statusOf(StoryTellerException.Reason.PACK_NOT_ON_DEVICE));
        assertEquals(404, ApiErrors.statusOf(StoryTellerException.Reason.LIBRARY_PACK_NOT_FOUND));
        assertEquals(507, ApiErrors.statusOf(StoryTellerException.Reason.NOT_ENOUGH_SPACE));
        assertEquals(400, ApiErrors.statusOf(StoryTellerException.Reason.INVALID_REQUEST));
    }
}
