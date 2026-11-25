/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.raw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.usb4java.Device;
import org.usb4java.DeviceHandle;
import org.usb4java.LibUsb;
import studio.driver.StoryTellerException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A V1 operation gives its libusb handle back on every path. A handle left open with its interface
 * claimed made every later claim fail ("Unable to claim libusb interface") until the Lunii was
 * unplugged. Nothing native runs: the libusb calls go through {@link LibUsbMassStorageHelper.HandleOps}.
 */
class LibUsbHandleReleaseTest {

    /** Records the calls, answers with the scripted results. */
    static class FakeOps implements LibUsbMassStorageHelper.HandleOps {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        int detachResult = LibUsb.SUCCESS;
        int claimResult = LibUsb.SUCCESS;
        int releaseResult = LibUsb.SUCCESS;

        @Override
        public int open(Device device, DeviceHandle handle) {
            calls.add("open");
            return LibUsb.SUCCESS;
        }

        @Override
        public int detachKernelDriver(DeviceHandle handle, int interfaceId) {
            calls.add("detach");
            return detachResult;
        }

        @Override
        public int claimInterface(DeviceHandle handle, int interfaceId) {
            calls.add("claim");
            return claimResult;
        }

        @Override
        public int releaseInterface(DeviceHandle handle, int interfaceId) {
            calls.add("release");
            return releaseResult;
        }

        RuntimeException closeFailure = null;

        @Override
        public void close(DeviceHandle handle) {
            calls.add("close");
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    @Test
    @DisplayName("an Error thrown before the operation returns its future still releases and closes")
    void synchronousErrorReleasesTheHandle() {
        FakeOps ops = new FakeOps();
        AssertionError boom = new AssertionError("boom");
        CompletionException e = assertThrows(CompletionException.class,
                () -> LibUsbMassStorageHelper.<Boolean>executeOnDeviceHandle(null, handle -> {
                    throw boom;
                }, ops).join());
        assertSame(boom, e.getCause());
        assertEquals(List.of("open", "detach", "claim", "release", "close"), ops.calls);
    }

    @Test
    @DisplayName("a close that fails after a failed claim is attached to the claim failure, not hiding it")
    void closeFailureIsSuppressed() {
        FakeOps ops = new FakeOps();
        ops.claimResult = LibUsb.ERROR_BUSY;
        ops.closeFailure = new IllegalStateException("close failed");
        CompletionException e = assertThrows(CompletionException.class, () -> LibUsbMassStorageHelper.executeOnDeviceHandle(null,
                handle -> CompletableFuture.completedFuture(true), ops).join());
        assertTrue(e.getCause().getMessage().contains("claim"), e.getCause().getMessage());
        assertEquals(1, e.getCause().getSuppressed().length);
        assertSame(ops.closeFailure, e.getCause().getSuppressed()[0]);
    }

    @Test
    @DisplayName("an operation that throws before returning its future still releases and closes")
    void synchronousFailureReleasesTheHandle() {
        FakeOps ops = new FakeOps();
        StoryTellerException submitFailed = new StoryTellerException("Unable to submit transfer OUT");
        CompletionException e = assertThrows(CompletionException.class,
                () -> LibUsbMassStorageHelper.<Boolean>executeOnDeviceHandle(null, handle -> {
                    throw submitFailed;
                }, ops).join());
        assertSame(submitFailed, e.getCause());
        assertEquals(List.of("open", "detach", "claim", "release", "close"), ops.calls);
    }

    @Test
    @DisplayName("a claim that fails closes the handle, and the operation is not run")
    void failedClaimClosesTheHandle() {
        FakeOps ops = new FakeOps();
        ops.claimResult = LibUsb.ERROR_BUSY;
        boolean[] ran = {false};
        assertThrows(CompletionException.class, () -> LibUsbMassStorageHelper.executeOnDeviceHandle(null, handle -> {
            ran[0] = true;
            return CompletableFuture.completedFuture(true);
        }, ops).join());
        assertFalse(ran[0]);
        assertEquals(List.of("open", "detach", "claim", "close"), ops.calls);
    }

    @Test
    @DisplayName("a detach that fails closes the handle")
    void failedDetachClosesTheHandle() {
        FakeOps ops = new FakeOps();
        ops.detachResult = LibUsb.ERROR_ACCESS;
        assertThrows(CompletionException.class, () -> LibUsbMassStorageHelper.executeOnDeviceHandle(null,
                handle -> CompletableFuture.completedFuture(true), ops).join());
        assertEquals(List.of("open", "detach", "close"), ops.calls);
    }

    @Test
    @DisplayName("a release that fails still closes the handle, and is reported")
    void failedReleaseStillCloses() {
        FakeOps ops = new FakeOps();
        ops.releaseResult = LibUsb.ERROR_NO_DEVICE;
        CompletionException e = assertThrows(CompletionException.class, () -> LibUsbMassStorageHelper.executeOnDeviceHandle(null,
                handle -> CompletableFuture.completedFuture(true), ops).join());
        assertTrue(e.getCause() instanceof StoryTellerException);
        assertEquals(List.of("open", "detach", "claim", "release", "close"), ops.calls);
    }

    @Test
    @DisplayName("the nominal path releases and closes once, and returns the result")
    void nominalPath() {
        FakeOps ops = new FakeOps();
        assertEquals("ok", LibUsbMassStorageHelper.executeOnDeviceHandle(null,
                handle -> CompletableFuture.completedFuture("ok"), ops).join());
        assertEquals(List.of("open", "detach", "claim", "release", "close"), ops.calls);
    }
}
