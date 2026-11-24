/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import studio.driver.StoryTellerException;

/**
 * Runs device operations one at a time, in the order they were submitted.
 *
 * <p>Every write to a device reads the pack index, changes it and writes it back. Two of them
 * running together each write the index they read, and the second silently drops what the first
 * added: a pack whose content is on the card but which the device no longer lists. The web UI
 * avoided this with a client-side mutex, which protects nothing from any other client of the API.
 * The serialization therefore lives here, on the server, for every caller.
 *
 * <p>An operation that fails does not stop the queue: the next one starts once it has settled,
 * whatever its outcome.
 *
 * <p>An operation that never settles (a frozen USB volume, a card that stopped answering) must not
 * hold the queue for ever. Its caller is failed once {@code timeoutMillis} have passed, and the
 * queue is then marked stuck: further submissions are refused at once instead of waiting behind it.
 * The next operation is <em>not</em> simply let through, since the stuck one may still write the
 * index afterwards and drop what the next one added. The queue recovers by itself if the stuck
 * operation eventually settles, and {@link #reset()} (called when the device is unplugged) abandons
 * it together with everything still waiting.
 */
public class DeviceOperationQueue {

    /** Default upper bound for one operation: generous, a large pack over a slow USB link included. */
    public static final long DEFAULT_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(
            Long.getLong("studio.device.operationTimeoutMinutes", 30L));

    private final long timeoutMillis;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> stuck;
    private long generation;
    /** What callers hold for operations not yet settled, so that {@link #reset()} can fail them. */
    private final Set<CompletableFuture<?>> unsettled = Collections.newSetFromMap(new IdentityHashMap<>());

    public DeviceOperationQueue() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    public DeviceOperationQueue(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Queues an operation. The supplier is invoked only once every operation submitted before it
     * has completed, never on the calling thread; whatever it throws becomes the failure of the
     * returned future.
     */
    public synchronized <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> operation) {
        if (stuck != null && !stuck.isDone()) {
            return CompletableFuture.failedFuture(notResponding());
        }
        final long submittedIn = generation;
        CompletableFuture<T> underlying = tail
                .handle((ignored, error) -> null)
                // Async: the supplier may block (file system, USB). It must neither run on the
                // caller's thread, possibly a Vert.x event loop, nor under this object's monitor.
                .thenComposeAsync(ignored -> {
                    synchronized (DeviceOperationQueue.this) {
                        if (submittedIn != generation) {
                            return CompletableFuture.<T>failedFuture(new StoryTellerException(
                                    StoryTellerException.Reason.NO_DEVICE,
                                    "Abandoned: the device was unplugged before this operation could start"));
                        }
                    }
                    try {
                        CompletableFuture<T> started = operation.get();
                        return started != null ? started : CompletableFuture.<T>completedFuture(null);
                    } catch (Throwable t) {
                        return CompletableFuture.<T>failedFuture(t);
                    }
                });
        // The queue waits for the operation's real end, the caller only up to the timeout.
        tail = underlying;
        CompletableFuture<T> forCaller = underlying.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
        CompletableFuture<T> result = new CompletableFuture<>();
        unsettled.add(result);
        result.whenComplete((value, error) -> {
            synchronized (DeviceOperationQueue.this) {
                unsettled.remove(result);
            }
        });
        forCaller.whenComplete((value, error) -> {
            synchronized (DeviceOperationQueue.this) {
                if (isTimeout(error) && submittedIn == generation && !underlying.isDone()) {
                    stuck = underlying;
                }
            }
            if (error == null) {
                result.complete(value);
            } else if (isTimeout(error)) {
                result.completeExceptionally(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE,
                        "The device did not answer within " + TimeUnit.MILLISECONDS.toSeconds(timeoutMillis)
                                + " s; unplug and plug it back in", error));
            } else {
                result.completeExceptionally(error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error);
            }
        });
        return result;
    }

    /**
     * Abandons the running operation, if any, and every operation still waiting: those fail without
     * being started. Called when the device goes away, so that plugging it back in starts afresh.
     */
    public synchronized void reset() {
        generation++;
        tail = CompletableFuture.completedFuture(null);
        stuck = null;
        StoryTellerException abandoned = new StoryTellerException(StoryTellerException.Reason.NO_DEVICE,
                "Abandoned: the device was unplugged");
        // Completing a future runs its dependants; copy first, they remove themselves from the set.
        for (CompletableFuture<?> f : new java.util.ArrayList<>(unsettled)) {
            f.completeExceptionally(abandoned);
        }
    }

    /** True once an operation has overrun its timeout and has not settled since. */
    public synchronized boolean isStuck() {
        return stuck != null && !stuck.isDone();
    }

    /** Operations submitted and not yet settled for their callers, the running one included. */
    public synchronized int pending() {
        return unsettled.size();
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable c = error; c != null; c = c.getCause()) {
            if (c instanceof TimeoutException) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    private static StoryTellerException notResponding() {
        return new StoryTellerException(StoryTellerException.Reason.NO_DEVICE,
                "A previous device operation is not answering; unplug and plug the device back in");
    }
}
