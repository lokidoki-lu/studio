/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import studio.driver.StoryTellerException;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Keeps the state of every recent transfer, so that it can be asked for over plain HTTP.
 *
 * <p>The event bus remains the push channel for the web UI. But an event published while nobody
 * listens is lost, and a script driving STUdio has no SockJS client: the only way it had to learn
 * the outcome of a transfer was to poll the device's pack list until the pack appeared, and to
 * conclude it had failed when a timeout expired. This registry is the state those events describe,
 * kept long enough to be read after the fact.
 *
 * <p>Finished transfers are kept up to {@link #MAX_FINISHED}, oldest dropped first. Running and
 * queued ones are never dropped.
 */
public class TransferRegistry {

    public enum State { QUEUED, RUNNING, SUCCEEDED, FAILED }

    public static final int MAX_FINISHED = 200;

    private static final class Transfer {
        final String id;
        final String uuid;
        final String operation;
        final long createdAt = System.currentTimeMillis();
        State state = State.QUEUED;
        long startedAt;
        long endedAt;
        long transferred;
        long total;
        String error;
        String reason;
        String message;

        Transfer(String id, String uuid, String operation) {
            this.id = id;
            this.uuid = uuid;
            this.operation = operation;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject()
                    .put("transferId", id)
                    .put("uuid", uuid)
                    .put("operation", operation)
                    .put("state", state.name())
                    .put("done", state == State.SUCCEEDED || state == State.FAILED)
                    .put("success", state == State.SUCCEEDED)
                    .put("transferred", transferred)
                    .put("total", total)
                    .put("progress", progress())
                    .put("createdAt", createdAt);
            if (startedAt > 0) {
                json.put("startedAt", startedAt);
            }
            if (endedAt > 0) {
                json.put("endedAt", endedAt);
            }
            if (error != null) {
                json.put("error", error).put("reason", reason).put("message", message);
            }
            return json;
        }

        double progress() {
            if (state == State.SUCCEEDED) {
                return 1.0;
            }
            if (total <= 0) {
                return 0.0;
            }
            return Math.min(1.0, (double) transferred / (double) total);
        }
    }

    private final Map<String, Transfer> transfers = new LinkedHashMap<>();

    /** Records a new transfer, queued, and returns its id. */
    public synchronized String create(String uuid, String operation) {
        String id = UUID.randomUUID().toString();
        transfers.put(id, new Transfer(id, uuid, operation));
        prune();
        return id;
    }

    public synchronized void started(String id) {
        Transfer t = transfers.get(id);
        if (t != null && t.state == State.QUEUED) {
            t.state = State.RUNNING;
            t.startedAt = System.currentTimeMillis();
        }
    }

    /**
     * Records progress. Progress callbacks may arrive out of order, so a report never moves the
     * counter backwards, and a finished transfer is left as it is.
     */
    public synchronized boolean progress(String id, long transferred, long total) {
        Transfer t = transfers.get(id);
        if (t == null || t.state == State.SUCCEEDED || t.state == State.FAILED) {
            return false;
        }
        if (t.state == State.QUEUED) {
            t.state = State.RUNNING;
            t.startedAt = System.currentTimeMillis();
        }
        if (total > 0) {
            t.total = total;
        }
        t.transferred = Math.max(t.transferred, transferred);
        return true;
    }

    /** Marks a transfer succeeded; false if it was unknown or had already ended. */
    public synchronized boolean succeeded(String id) {
        Transfer t = transfers.get(id);
        boolean changed = false;
        if (t != null && t.state != State.FAILED && t.state != State.SUCCEEDED) {
            t.state = State.SUCCEEDED;
            t.endedAt = System.currentTimeMillis();
            if (t.total > 0) {
                t.transferred = t.total;
            }
            changed = true;
        }
        prune();
        return changed;
    }

    /** Marks a transfer failed; false if it was unknown or had already ended. */
    public synchronized boolean failed(String id, Throwable cause) {
        Transfer t = transfers.get(id);
        boolean changed = false;
        if (t != null && t.state != State.FAILED && t.state != State.SUCCEEDED) {
            t.state = State.FAILED;
            t.endedAt = System.currentTimeMillis();
            StoryTellerException.Reason reason = StoryTellerException.reasonOf(cause);
            t.reason = reason.name();
            t.error = ApiErrors.codeOf(reason);
            t.message = messageOf(cause);
            changed = true;
        }
        prune();
        return changed;
    }

    /** Ids of the transfers still queued or running. */
    public synchronized java.util.List<String> unfinished() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        transfers.values().stream()
                .filter(t -> t.state == State.QUEUED || t.state == State.RUNNING)
                .forEach(t -> ids.add(t.id));
        return ids;
    }

    public synchronized Optional<JsonObject> get(String id) {
        Transfer t = id == null ? null : transfers.get(id);
        return Optional.ofNullable(t).map(Transfer::toJson);
    }

    /** Every known transfer, oldest first. */
    public synchronized JsonArray list() {
        JsonArray array = new JsonArray();
        transfers.values().forEach(t -> array.add(t.toJson()));
        return array;
    }

    private void prune() {
        long finished = transfers.values().stream()
                .filter(t -> t.state == State.SUCCEEDED || t.state == State.FAILED)
                .count();
        Iterator<Transfer> it = transfers.values().iterator();
        while (finished > MAX_FINISHED && it.hasNext()) {
            Transfer t = it.next();
            if (t.state == State.SUCCEEDED || t.state == State.FAILED) {
                it.remove();
                finished--;
            }
        }
    }

    /**
     * The most useful message along the cause chain: wrappers added by futures carry the inner
     * exception's class name and nothing more, so they are skipped.
     */
    static String messageOf(Throwable cause) {
        Throwable c = cause;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        if (c == null) {
            return "Unknown error";
        }
        StringBuilder sb = new StringBuilder(c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName());
        Throwable inner = c.getCause();
        int depth = 0;
        while (inner != null && inner != c && depth++ < 3) {
            if (inner.getMessage() != null && !sb.toString().contains(inner.getMessage())) {
                sb.append(": ").append(inner.getMessage());
            }
            c = inner;
            inner = inner.getCause();
        }
        return sb.toString();
    }
}
