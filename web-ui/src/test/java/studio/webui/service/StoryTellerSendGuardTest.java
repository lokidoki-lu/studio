/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objenesis.ObjenesisStd;
import org.usb4java.Device;
import studio.driver.StoryTellerException;
import studio.driver.event.TransferProgressListener;
import studio.driver.fs.FsStoryTellerAsyncDriver;
import studio.driver.model.TransferStatus;
import studio.driver.model.fs.FsDeviceInfos;
import studio.driver.model.fs.FsStoryPackInfos;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P7: the checks of a library pack sent to the Lunii (official, declared UUID) are made again when
 * the queued transfer starts, not only when the request was accepted.
 */
class StoryTellerSendGuardTest {

    private static final String U = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";

    @TempDir
    Path root;

    /** A 2.x+ driver that writes nothing: it records what it was asked to send. */
    public static class RecordingDriver extends FsStoryTellerAsyncDriver {
        List<String> sent;

        @Override
        public CompletableFuture<List<FsStoryPackInfos>> getPacksList() {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        @Override
        public CompletableFuture<FsDeviceInfos> getDeviceInfos() {
            return CompletableFuture.failedFuture(new StoryTellerException("no infos in this test"));
        }

        @Override
        public CompletableFuture<TransferStatus> uploadPack(String uuid, String inputPath, TransferProgressListener listener) {
            sent.add("add:" + new File(inputPath).getName());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<TransferStatus> updatePack(String uuid, String inputPath, TransferProgressListener listener) {
            sent.add("update:" + new File(inputPath).getName());
            return CompletableFuture.completedFuture(null);
        }
    }

    private StoryTellerService service;
    private RecordingDriver driver;
    private TransferRegistry registry;

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = StoryTellerService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        ObjenesisStd objenesis = new ObjenesisStd();
        service = objenesis.newInstance(StoryTellerService.class);
        driver = objenesis.newInstance(RecordingDriver.class);
        driver.sent = Collections.synchronizedList(new ArrayList<>());
        registry = new TransferRegistry();
        set(service, "LOGGER", LoggerFactory.getLogger(StoryTellerService.class));
        set(service, "queue", new DeviceOperationQueue());
        set(service, "notifier", new TransferNotifier(null, registry));
        set(service, "lastInfosV1", Optional.empty());
        set(service, "fsDriver", driver);
        Constructor<Device> ctor = Device.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        set(service, "fsDevice", ctor.newInstance());
    }

    private JsonObject awaitEnd(String transferId) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            JsonObject t = registry.get(transferId).orElseThrow();
            if (t.getBoolean("done")) {
                return t;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("transfer " + transferId + " did not end");
    }

    @Test
    @DisplayName("a library entry that became another pack while the transfer waited is not sent, added or updated")
    void checkedAgainWhenTheTransferStarts() throws Exception {
        Path pack = Files.createDirectories(root.resolve(U + ".fs"));
        AtomicInteger checks = new AtomicInteger();
        // The entry changed after the request was accepted (here: a marker says so)
        service.setSendGuard((uuid, file) -> {
            checks.incrementAndGet();
            return Files.exists(root.resolve("replaced"))
                    ? Optional.of(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "now another pack"))
                    : Optional.empty();
        });
        Files.createFile(root.resolve("replaced"));

        JsonObject add = awaitEnd(service.addPack(U, pack.toFile()).join().orElseThrow());
        assertEquals("FAILED", add.getString("state"));
        JsonObject update = awaitEnd(service.updatePack(U, pack.toFile()).join().orElseThrow());
        assertEquals("FAILED", update.getString("state"));
        assertTrue(driver.sent.isEmpty(), driver.sent.toString());
        assertEquals(2, checks.get());

        // Still the pack it claims to be: sent
        Files.delete(root.resolve("replaced"));
        assertEquals("SUCCEEDED", awaitEnd(service.addPack(U, pack.toFile()).join().orElseThrow()).getString("state"));
        assertEquals("SUCCEEDED", awaitEnd(service.updatePack(U, pack.toFile()).join().orElseThrow()).getString("state"));
        assertEquals(List.of("add:" + pack.getFileName(), "update:" + pack.getFileName()), driver.sent);
    }

    @Test
    @DisplayName("a guard that cannot decide refuses the transfer")
    void failingGuardRefuses() throws Exception {
        Path pack = Files.createDirectories(root.resolve(U + ".fs"));
        service.setSendGuard((uuid, file) -> {
            throw new IllegalStateException("unreadable");
        });
        assertEquals("FAILED", awaitEnd(service.addPack(U, pack.toFile()).join().orElseThrow()).getString("state"));
        assertTrue(driver.sent.isEmpty());
    }

    @Test
    @DisplayName("P5: removing orphan folders without the device check's token is refused before anything is queued")
    void orphanRemovalNeedsTheToken() {
        Throwable e = service.removeOrphanFolders(List.of("0A0B0C0D"), null).handle((r, t) -> t).join();
        assertEquals(StoryTellerException.Reason.INVALID_REQUEST, StoryTellerException.reasonOf(e));
    }
}
