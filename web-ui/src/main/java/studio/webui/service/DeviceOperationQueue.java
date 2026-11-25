/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /**
     * Threads of the queue's own. The common fork-join pool is shared with every blocking read the
     * drivers make outside the queue; a frozen card can exhaust it, and the queue would then never
     * start an operation, arm a timeout or end a reset grace again.
     */
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "device-operation-queue");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * The failure a caller gets when its operation overran the timeout. The operation itself may
     * still be running and may still succeed.
     */
    public static final class OperationTimeoutException extends StoryTellerException {
        OperationTimeoutException(String message, Throwable cause) {
            super(Reason.NO_DEVICE, message, cause);
        }
    }

    /** The failure of {@link #submitIfIdle} when another operation is queued or running. */
    public static final class QueueBusyException extends StoryTellerException {
        QueueBusyException() {
            super(Reason.UNKNOWN, "Another device operation is queued or running: the operation was not queued");
        }
    }

    private final long timeoutMillis;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
    private CompletableFuture<?> stuck;
    private CompletableFuture<?> running;

    /** How long the first operation after a reset waits for an abandoned one still in flight. */
    static final long RESET_GRACE_MILLIS = 10_000;

    private final long resetGraceMillis;
    private long generation;
    /** What callers hold for operations not yet settled, so that {@link #reset()} can fail them. */
    private final Set<CompletableFuture<?>> unsettled = Collections.newSetFromMap(new IdentityHashMap<>());

    public DeviceOperationQueue() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    public DeviceOperationQueue(long timeoutMillis) {
        this(timeoutMillis, RESET_GRACE_MILLIS);
    }

    DeviceOperationQueue(long timeoutMillis, long resetGraceMillis) {
        this.timeoutMillis = timeoutMillis;
        this.resetGraceMillis = resetGraceMillis;
    }

    /**
     * Queues an operation. The supplier is invoked only once every operation submitted before it
     * has completed, never on the calling thread; whatever it throws becomes the failure of the
     * returned future.
     *
     * <p>The timeout runs from the moment the operation starts, not from its submission: waiting
     * behind other operations is not a sign that the device stopped answering.
     *
     * <p>An operation must not submit to this queue and wait for the result: the inner operation
     * would wait for the outer one, which waits for it.
     */
    public <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (this) {
            if (stuck != null && !stuck.isDone()) {
                return CompletableFuture.failedFuture(notResponding());
            }
            final long submittedIn = generation;
            CompletableFuture<T> underlying = tail
                    .handle((ignored, error) -> null)
                    // Async: the supplier may block (file system, USB). It must neither run on the
                    // caller's thread, possibly a Vert.x event loop, nor under this object's monitor.
                    .thenComposeAsync(ignored -> start(operation, submittedIn, result), EXECUTOR);
            // The queue waits for the operation's real end; the caller only up to the timeout.
            tail = underlying;
            unsettled.add(result);
            underlying.whenComplete((value, error) -> {
                if (error == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error);
                }
            });
        }
        result.whenComplete((value, error) -> {
            synchronized (DeviceOperationQueue.this) {
                unsettled.remove(result);
            }
        });
        return result;
    }

    /**
     * As {@link #submit}, but only when no other operation is queued or running — checked and
     * queued atomically. Otherwise fails at once with {@link QueueBusyException}, nothing queued: for
     * an optional read whose caller must not wait behind a transfer. Cancelling the returned future
     * before the operation starts means it never starts.
     */
    public <T> CompletableFuture<T> submitIfIdle(Supplier<CompletableFuture<T>> operation) {
        synchronized (this) {
            if (!tail.isDone() || !unsettled.isEmpty()) {
                return CompletableFuture.failedFuture(new QueueBusyException());
            }
            return submit(operation);
        }
    }

    private <T> CompletableFuture<T> start(Supplier<CompletableFuture<T>> operation, long submittedIn,
                                           CompletableFuture<T> result) {
        CompletableFuture<T> started;
        synchronized (this) {
            if (submittedIn != generation || result.isDone()) {
                return CompletableFuture.failedFuture(new StoryTellerException(
                        StoryTellerException.Reason.NO_DEVICE,
                        "Abandoned: the device was unplugged before this operation could start"));
            }
            // Registered as running before it starts, under the same lock as the generation check:
            // a reset from now on knows there is an operation of the old generation in flight.
            started = new CompletableFuture<>();
            running = started;
        }
        CompletableFuture<T> actual;
        try {
            actual = operation.get();
            if (actual == null) {
                actual = CompletableFuture.completedFuture(null);
            }
        } catch (Throwable t) {
            actual = CompletableFuture.failedFuture(t);
        }
        actual.whenComplete((value, error) -> {
            synchronized (DeviceOperationQueue.this) {
                if (running == started) {
                    running = null;
                }
            }
            if (error == null) {
                started.complete(value);
            } else {
                started.completeExceptionally(error);
            }
        });
        started.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenComplete((value, error) -> {
            if (!isTimeout(error) || started.isDone()) {
                return;
            }
            List<CompletableFuture<?>> waiting = new ArrayList<>();
            synchronized (DeviceOperationQueue.this) {
                if (submittedIn == generation) {
                    stuck = started;
                    // Those queued behind it would wait as long as it does: they fail now, and are
                    // not started even if it ends later (their callers have been answered).
                    for (CompletableFuture<?> f : unsettled) {
                        if (f != result) {
                            waiting.add(f);
                        }
                    }
                }
            }
            result.completeExceptionally(new OperationTimeoutException(
                    "The device operation is still running after " + TimeUnit.MILLISECONDS.toSeconds(timeoutMillis)
                            + " s. If the device no longer responds, unplug it and plug it back in", error));
            for (CompletableFuture<?> f : waiting) {
                f.completeExceptionally(notResponding());
            }
        });
        return started;
    }

    /**
     * Abandons the running operation, if any, and every operation still waiting: those fail without
     * being started. Called when the device goes away, so that plugging it back in starts afresh.
     *
     * <p>The abandoned operation may still be running. The next operation waits for it to settle,
     * but no more than {@link #RESET_GRACE_MILLIS}: a frozen one must not hold the new device, and
     * the drivers refuse to write once the device they started on is gone.
     */
    public void reset() {
        List<CompletableFuture<?>> toFail;
        synchronized (this) {
            generation++;
            CompletableFuture<?> inFlight = running;
            tail = inFlight == null || inFlight.isDone()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.anyOf(inFlight.handle((v, e) -> null),
                            CompletableFuture.runAsync(() -> { },
                                    CompletableFuture.delayedExecutor(resetGraceMillis, TimeUnit.MILLISECONDS, EXECUTOR)));
            stuck = null;
            toFail = new ArrayList<>(unsettled);
        }
        // Completed outside the lock: their dependants answer HTTP requests and publish events.
        StoryTellerException abandoned = new StoryTellerException(StoryTellerException.Reason.NO_DEVICE,
                "Abandoned: the device was unplugged");
        for (CompletableFuture<?> f : toFail) {
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
