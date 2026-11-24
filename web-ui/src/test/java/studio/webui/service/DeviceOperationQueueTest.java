/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceOperationQueueTest {

    @Test
    @DisplayName("an operation does not start until the one before it has completed")
    void runsOneAtATime() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue();
        CompletableFuture<String> first = new CompletableFuture<>();
        AtomicInteger secondStarted = new AtomicInteger();

        CompletableFuture<String> a = queue.submit(() -> first);
        CompletableFuture<String> b = queue.submit(() -> {
            secondStarted.incrementAndGet();
            return CompletableFuture.completedFuture("b");
        });

        assertEquals(0, secondStarted.get());
        assertEquals(2, queue.pending());
        first.complete("a");
        assertEquals("a", a.get(1, TimeUnit.SECONDS));
        assertEquals("b", b.get(1, TimeUnit.SECONDS));
        assertEquals(1, secondStarted.get());
        assertEquals(0, queue.pending());
    }

    @Test
    @DisplayName("a failed or throwing operation does not stop the ones queued after it")
    void survivesFailures() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue();
        CompletableFuture<Object> failed = queue.submit(() -> CompletableFuture.failedFuture(new IllegalStateException("boom")));
        CompletableFuture<Object> thrown = queue.submit(() -> {
            throw new IllegalStateException("thrown");
        });
        CompletableFuture<String> after = queue.submit(() -> CompletableFuture.completedFuture("ok"));

        assertEquals("ok", after.get(1, TimeUnit.SECONDS));
        assertTrue(failed.isCompletedExceptionally());
        assertTrue(thrown.isCompletedExceptionally());
    }

    @Test
    @DisplayName("concurrent submissions run in submission order, never overlapping")
    void keepsOrderUnderConcurrency() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger running = new AtomicInteger();
        List<CompletableFuture<Integer>> all = new ArrayList<>();
        boolean[] overlap = {false};
        for (int i = 0; i < 50; i++) {
            final int n = i;
            all.add(queue.submit(() -> CompletableFuture.supplyAsync(() -> {
                if (running.incrementAndGet() > 1) {
                    overlap[0] = true;
                }
                order.add(n);
                running.decrementAndGet();
                return n;
            })));
        }
        CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        assertFalse(overlap[0]);
        for (int i = 0; i < 50; i++) {
            assertEquals(i, order.get(i));
        }
    }
}
