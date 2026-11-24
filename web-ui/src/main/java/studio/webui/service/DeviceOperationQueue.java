/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

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
 */
public class DeviceOperationQueue {

    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
    private int pending = 0;

    /**
     * Queues an operation. The supplier is invoked only once every operation submitted before it
     * has completed; whatever it throws becomes the failure of the returned future.
     */
    public synchronized <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> operation) {
        pending++;
        CompletableFuture<T> result = tail
                .handle((ignored, error) -> null)
                .thenCompose(ignored -> {
                    try {
                        CompletableFuture<T> started = operation.get();
                        return started != null ? started : CompletableFuture.<T>completedFuture(null);
                    } catch (Throwable t) {
                        return CompletableFuture.<T>failedFuture(t);
                    }
                });
        result.whenComplete((r, e) -> {
            synchronized (DeviceOperationQueue.this) {
                pending--;
            }
        });
        tail = result;
        return result;
    }

    /** Operations submitted and not yet settled, the running one included. */
    public synchronized int pending() {
        return pending;
    }
}
