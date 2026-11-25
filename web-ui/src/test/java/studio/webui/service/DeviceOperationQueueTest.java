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

    @Test
    @DisplayName("an operation that never ends fails its caller, and the queue refuses at once until reset")
    void timesOutAndRecoversOnReset() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue(100, 100);
        CompletableFuture<String> never = new CompletableFuture<>();
        CompletableFuture<String> hung = queue.submit(() -> never);
        java.util.concurrent.ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class, () -> hung.get(5, TimeUnit.SECONDS));
        assertEquals(studio.driver.StoryTellerException.Reason.NO_DEVICE, studio.driver.StoryTellerException.reasonOf(e));
        assertTrue(queue.isStuck());

        AtomicInteger started = new AtomicInteger();
        CompletableFuture<String> refused = queue.submit(() -> {
            started.incrementAndGet();
            return CompletableFuture.completedFuture("x");
        });
        assertTrue(refused.isCompletedExceptionally());
        assertEquals(0, started.get());

        queue.reset();
        assertFalse(queue.isStuck());
        assertEquals("ok", queue.submit(() -> CompletableFuture.completedFuture("ok")).get(1, TimeUnit.SECONDS));
        assertEquals(0, queue.pending());
    }

    @Test
    @DisplayName("a stuck operation that finally settles frees the queue by itself")
    void recoversWhenTheStuckOperationEnds() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue(100);
        CompletableFuture<String> slow = new CompletableFuture<>();
        CompletableFuture<String> first = queue.submit(() -> slow);
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
        assertTrue(queue.isStuck());
        slow.complete("late");
        assertFalse(queue.isStuck());
        assertEquals("ok", queue.submit(() -> CompletableFuture.completedFuture("ok")).get(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("reset fails the operations waiting behind a hung one without starting them")
    void resetAbandonsWaiting() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue(DeviceOperationQueue.DEFAULT_TIMEOUT_MILLIS, 100);
        CompletableFuture<String> never = new CompletableFuture<>();
        CompletableFuture<String> running = queue.submit(() -> never);
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<String> waiting = queue.submit(() -> {
            started.incrementAndGet();
            return CompletableFuture.completedFuture("w");
        });
        assertEquals(2, queue.pending());
        queue.reset();
        assertTrue(running.isCompletedExceptionally());
        assertTrue(waiting.isCompletedExceptionally());
        assertEquals(0, queue.pending());
        assertEquals("ok", queue.submit(() -> CompletableFuture.completedFuture("ok")).get(1, TimeUnit.SECONDS));
        assertEquals(0, started.get());
    }

    @Test
    @DisplayName("the operation never runs on the submitting thread")
    void neverRunsOnCaller() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue();
        Thread caller = Thread.currentThread();
        Thread[] ran = new Thread[1];
        queue.submit(() -> {
            ran[0] = Thread.currentThread();
            return CompletableFuture.completedFuture(null);
        }).get(1, TimeUnit.SECONDS);
        assertFalse(caller == ran[0]);
    }

    @Test
    @DisplayName("the timeout runs from an operation's start, not while it waits its turn")
    void timeoutStartsWhenTheOperationStarts() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue(300);
        java.util.function.Supplier<CompletableFuture<String>> slow = () -> CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        });
        CompletableFuture<String> a = queue.submit(slow);
        CompletableFuture<String> b = queue.submit(slow);
        CompletableFuture<String> c = queue.submit(slow);
        assertEquals("done", a.get(5, TimeUnit.SECONDS));
        assertEquals("done", b.get(5, TimeUnit.SECONDS));
        assertEquals("done", c.get(5, TimeUnit.SECONDS));
        assertFalse(queue.isStuck());
    }

    @Test
    @DisplayName("after a reset, the next operation waits a little for the abandoned one to settle")
    void resetWaitsForTheAbandonedOperation() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue(DeviceOperationQueue.DEFAULT_TIMEOUT_MILLIS, 5_000);
        CompletableFuture<String> old = new CompletableFuture<>();
        queue.submit(() -> old);
        Thread.sleep(50);
        queue.reset();
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<String> next = queue.submit(() -> {
            started.incrementAndGet();
            return CompletableFuture.completedFuture("next");
        });
        Thread.sleep(100);
        assertEquals(0, started.get());
        old.complete("old");
        assertEquals("next", next.get(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("when an operation overruns, those already waiting behind it fail too, and never start")
    void waitingOperationsFailWithTheStuckOne() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue(100, 100);
        CompletableFuture<String> never = new CompletableFuture<>();
        CompletableFuture<String> hung = queue.submit(() -> never);
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<String> waiting = queue.submit(() -> {
            started.incrementAndGet();
            return CompletableFuture.completedFuture("w");
        });
        java.util.concurrent.ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class, () -> hung.get(5, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof DeviceOperationQueue.OperationTimeoutException, String.valueOf(e.getCause()));
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class,
                () -> waiting.get(5, TimeUnit.SECONDS));
        never.complete("late");
        Thread.sleep(100);
        assertEquals(0, started.get());
        assertEquals(0, queue.pending());
    }

    @Test
    @DisplayName("submitIfIdle runs at once on an idle queue, refuses without queueing behind another operation")
    void submitIfIdle() throws Exception {
        DeviceOperationQueue queue = new DeviceOperationQueue();
        assertEquals("idle", queue.submitIfIdle(() -> CompletableFuture.completedFuture("idle")).get(1, TimeUnit.SECONDS));

        CompletableFuture<String> transfer = new CompletableFuture<>();
        CompletableFuture<String> running = queue.submit(() -> transfer);
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<String> refused = queue.submitIfIdle(() -> {
            started.incrementAndGet();
            return CompletableFuture.completedFuture("read");
        });
        assertTrue(refused.isCompletedExceptionally());
        java.util.concurrent.ExecutionException e = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class, refused::get);
        assertTrue(e.getCause() instanceof DeviceOperationQueue.QueueBusyException);
        transfer.complete("done");
        assertEquals("done", running.get(1, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(0, started.get(), "never queued");
        assertEquals(0, queue.pending());
    }
}
