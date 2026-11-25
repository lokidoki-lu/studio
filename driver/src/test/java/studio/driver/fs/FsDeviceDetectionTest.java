/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import org.usb4java.Device;
import studio.driver.event.DeviceHotplugEventListener;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The detection half of the FS driver: what happens between "USB says a device arrived" and
 * "STUdio publishes a usable device".
 *
 * <p>The driver is allocated without running its constructor, which would initialise libusb, start
 * threads and register a JVM shutdown hook. Only the detection fields are populated; nothing in the
 * transfer or write path is touched by these tests.
 */
class FsDeviceDetectionTest {

    private FsStoryTellerAsyncDriver driver;
    private List<String> pluggedEvents;
    private List<String> unpluggedEvents;

    private static Device newDevice() {
        try {
            Constructor<Device> ctor = Device.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set " + field, e);
        }
    }

    private static Object get(Object target, String field) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read " + field, e);
        }
    }

    /** A locator whose answer the test controls, with no filesystem involved. */
    private DevicePartitionLocator locatorReturning(String mountPoint, AtomicBoolean observedCancellation) {
        return new DevicePartitionLocator(
                () -> mountPoint == null ? List.of() : Arrays.asList(mountPoint),
                path -> path.equals(mountPoint),
                millis -> true,
                0L,
                1) {
            @Override
            public Optional<String> awaitPartition(java.util.function.BooleanSupplier deviceStillPlugged) {
                if (observedCancellation != null && !deviceStillPlugged.getAsBoolean()) {
                    observedCancellation.set(true);
                    return Optional.empty();
                }
                return Optional.ofNullable(mountPoint);
            }
        };
    }

    private void installLocator(DevicePartitionLocator locator) {
        set(driver, "partitionLocator", locator);
    }

    @BeforeEach
    void buildDriverWithoutTouchingLibUsb() {
        driver = new ObjenesisStd().newInstance(FsStoryTellerAsyncDriver.class);
        pluggedEvents = new ArrayList<>();
        unpluggedEvents = new ArrayList<>();
        List<DeviceHotplugEventListener> listeners = new ArrayList<>();
        listeners.add(new DeviceHotplugEventListener() {
            @Override
            public void onDevicePlugged(Device device) {
                pluggedEvents.add("plugged");
            }

            @Override
            public void onDeviceUnplugged(Device device) {
                unpluggedEvents.add("unplugged");
            }
        });
        set(driver, "listeners", listeners);
    }

    @Test
    @DisplayName("publishes the device once the partition is located")
    void publishesDeviceWhenPartitionIsFound() {
        installLocator(locatorReturning("D:\\", null));
        Device device = newDevice();

        driver.handleDevicePlugged(device);

        assertEquals("D:\\", driver.getPartitionMountPoint());
        assertSame(device, get(driver, "device"));
        assertEquals(1, pluggedEvents.size());
    }

    @Test
    @DisplayName("D8: a partition that never appears leaves no device and, above all, throws nothing")
    void missingPartitionIsNotAnException() {
        // The caller is a CompletableFuture that only logs, so throwing here produced a device that
        // was invisible to STUdio with nothing shown to the user. Failing quietly and leaving the
        // state clean is the contract; the listener is simply never notified.
        installLocator(locatorReturning(null, null));
        Device device = newDevice();

        assertDoesNotThrow(() -> driver.handleDevicePlugged(device));

        assertNull(driver.getPartitionMountPoint());
        assertNull(get(driver, "device"));
        assertEquals(0, pluggedEvents.size());
        assertNull(get(driver, "awaitingPartitionFor"), "no search must be left pending");
    }

    @Test
    @DisplayName("D5: an unplug during the search cancels it and leaves no stale mount point")
    void unplugDuringSearchLeavesNoStaleMountPoint() {
        Device device = newDevice();
        AtomicBoolean cancellationObserved = new AtomicBoolean(false);

        // A locator that unplugs the device midway, then consults the cancellation predicate.
        installLocator(new DevicePartitionLocator(List::of, path -> false, millis -> true, 0L, 1) {
            @Override
            public Optional<String> awaitPartition(java.util.function.BooleanSupplier deviceStillPlugged) {
                driver.handleDeviceUnplugged(device);
                if (!deviceStillPlugged.getAsBoolean()) {
                    cancellationObserved.set(true);
                }
                return Optional.of("D:\\");   // even if a mount point is found, it must be discarded
            }
        });

        driver.handleDevicePlugged(device);

        assertEquals(true, cancellationObserved.get(), "the search must observe the unplug");
        assertNull(driver.getPartitionMountPoint(), "a mount point found after an unplug must be discarded");
        assertNull(get(driver, "device"));
        assertEquals(0, pluggedEvents.size(), "no device may be published after an unplug");
        assertEquals(1, unpluggedEvents.size());
    }

    @Test
    @DisplayName("D6: a replug after a failed detection starts a clean, successful sequence")
    void replugAfterFailureSucceeds() {
        Device first = newDevice();
        installLocator(locatorReturning(null, null));
        driver.handleDevicePlugged(first);
        assertEquals(0, pluggedEvents.size(), "precondition: first detection failed");

        // Device unplugged and plugged back in: the volume is there this time.
        driver.handleDeviceUnplugged(first);
        Device second = newDevice();
        installLocator(locatorReturning("E:\\", null));

        driver.handleDevicePlugged(second);

        assertEquals("E:\\", driver.getPartitionMountPoint());
        assertEquals(1, pluggedEvents.size(), "the replug must produce exactly one plug event");
    }

    @Test
    @DisplayName("unplugging clears the mount point so no stale drive letter can be reused")
    void unplugClearsTheMountPoint() {
        installLocator(locatorReturning("D:\\", null));
        Device device = newDevice();
        driver.handleDevicePlugged(device);
        assertEquals("D:\\", driver.getPartitionMountPoint());

        driver.handleDeviceUnplugged(device);

        assertNull(driver.getPartitionMountPoint());
        assertNull(get(driver, "device"));
        assertEquals(1, unpluggedEvents.size());
    }

    @Test
    @DisplayName("D7: two plug events for the same device publish it once per event, never inconsistently")
    void repeatedPlugEventsKeepStateConsistent() {
        installLocator(locatorReturning("D:\\", null));
        Device device = newDevice();

        driver.handleDevicePlugged(device);
        driver.handleDevicePlugged(device);

        assertEquals("D:\\", driver.getPartitionMountPoint());
        assertSame(device, get(driver, "device"));
        assertNull(get(driver, "awaitingPartitionFor"), "no search may be left dangling");
        assertEquals(2, pluggedEvents.size());
    }

    @Test
    @DisplayName("an unexpected failure inside detection is contained, not propagated")
    void unexpectedFailureIsContained() {
        installLocator(new DevicePartitionLocator(List::of, path -> false, millis -> true, 0L, 1) {
            @Override
            public Optional<String> awaitPartition(java.util.function.BooleanSupplier deviceStillPlugged) {
                throw new IllegalStateException("something unexpected");
            }
        });

        assertDoesNotThrow(() -> driver.handleDevicePlugged(newDevice()));

        assertNull(driver.getPartitionMountPoint());
        assertNull(get(driver, "awaitingPartitionFor"));
    }

    /** A device distinct from {@link #newDevice()} ones: usb4java compares devices by native pointer. */
    private static Device deviceWithPointer(long pointer) {
        Device device = newDevice();
        set(device, "devicePointer", pointer);
        return device;
    }

    @Test
    @DisplayName("the unplug of another device leaves the current one, and its generation, alone")
    void unplugOfAnotherDeviceIsIgnored() {
        installLocator(locatorReturning("D:\\", null));
        Device current = deviceWithPointer(0x1000L);
        driver.handleDevicePlugged(current);
        FsStoryTellerAsyncDriver.PartitionSession before = driver.currentPartition();

        driver.handleDeviceUnplugged(deviceWithPointer(0x2000L));
        driver.handleDeviceUnplugged(null);

        assertEquals("D:\\", driver.getPartitionMountPoint());
        assertSame(current, get(driver, "device"));
        assertEquals(before.generation, driver.currentPartition().generation, "operations in flight must not be cancelled");
        assertEquals(0, unpluggedEvents.size(), "listeners hear nothing");
    }

    @Test
    @DisplayName("the unplug of another device does not cancel the partition search in flight")
    void unplugOfAnotherDeviceDoesNotCancelTheSearch() {
        Device awaited = deviceWithPointer(0x1000L);
        AtomicBoolean cancellationObserved = new AtomicBoolean(false);
        installLocator(new DevicePartitionLocator(List::of, path -> false, millis -> true, 0L, 1) {
            @Override
            public Optional<String> awaitPartition(java.util.function.BooleanSupplier deviceStillPlugged) {
                driver.handleDeviceUnplugged(deviceWithPointer(0x2000L));
                if (!deviceStillPlugged.getAsBoolean()) {
                    cancellationObserved.set(true);
                }
                return Optional.of("D:\\");
            }
        });

        driver.handleDevicePlugged(awaited);

        assertEquals(false, cancellationObserved.get());
        assertSame(awaited, get(driver, "device"));
        assertEquals(1, pluggedEvents.size());
        assertEquals(0, unpluggedEvents.size());
    }

    @Test
    @DisplayName("a second Lunii plugged next to the published one gets its own partition, never the first one's")
    void secondDeviceDoesNotTakeTheFirstOnesPartition(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws java.io.IOException {
        String mountA = java.nio.file.Files.createDirectory(root.resolve("A")).toString();
        String mountB = java.nio.file.Files.createDirectory(root.resolve("B")).toString();
        Device first = deviceWithPointer(0x1000L);
        Device second = deviceWithPointer(0x2000L);
        installLocator(locatorReturning(mountA, null));
        driver.handleDevicePlugged(first);
        assertEquals(mountA, driver.getPartitionMountPoint());

        // B is seen on the bus before its volume is mounted: the first scans only list A's.
        java.util.concurrent.atomic.AtomicInteger scans = new java.util.concurrent.atomic.AtomicInteger();
        installLocator(new DevicePartitionLocator(
                () -> scans.getAndIncrement() < 2 ? List.of(mountA) : List.of(mountA, mountB),
                path -> true, millis -> true, 0L, 10));
        driver.handleDevicePlugged(second);

        assertEquals(mountB, driver.getPartitionMountPoint(), "B must not be published on A's partition");
        assertSame(second, get(driver, "device"));
        assertEquals(2, pluggedEvents.size());
    }

    /** Publishes {@code first} on {@code mountA}, then plugs {@code second} while only that volume is listed. */
    private void plugSecondWhileOnlyFirstVolumeIsListed(Device first, Device second, String mountA,
                                                        String usbSerial, String volumeSerial) {
        installLocator(locatorReturning(mountA, null));
        driver.handleDevicePlugged(first);
        set(driver, "usbSerialReader", (java.util.function.Function<Device, Optional<String>>) d -> Optional.ofNullable(usbSerial));
        set(driver, "volumeSerialReader", (java.util.function.Function<String, String>) m -> volumeSerial);
        installLocator(new DevicePartitionLocator(() -> List.of(mountA), path -> true, millis -> true, 0L, 3));
        driver.handleDevicePlugged(second);
    }

    @Test
    @DisplayName("the same Lunii enumerated again under a new libusb object keeps its partition (same serial)")
    void sameSerialReenumeratedIsNotSkipped(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws java.io.IOException {
        String mountA = java.nio.file.Files.createDirectory(root.resolve("A")).toString();
        Device again = deviceWithPointer(0x2000L);
        plugSecondWhileOnlyFirstVolumeIsListed(deviceWithPointer(0x1000L), again, mountA, "SN1", " sn1 ");
        assertEquals(mountA, driver.getPartitionMountPoint());
        assertSame(again, get(driver, "device"));
    }

    @Test
    @DisplayName("another Lunii (other serial, or none readable) never gets the published partition")
    void otherOrUnknownSerialIsSkipped(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws java.io.IOException {
        String mountA = java.nio.file.Files.createDirectory(root.resolve("A")).toString();
        Device first = deviceWithPointer(0x1000L);
        plugSecondWhileOnlyFirstVolumeIsListed(first, deviceWithPointer(0x2000L), mountA, "SN2", "SN1");
        assertSame(first, get(driver, "device"));
        assertEquals(mountA, driver.getPartitionMountPoint());

        plugSecondWhileOnlyFirstVolumeIsListed(first, deviceWithPointer(0x3000L), mountA, null, "SN1");
        assertSame(first, get(driver, "device"));
        plugSecondWhileOnlyFirstVolumeIsListed(first, deviceWithPointer(0x4000L), mountA, "SN1", null);
        assertSame(first, get(driver, "device"));
    }

    @Test
    @DisplayName("the same Lunii plugged again is still found on its partition")
    void sameDeviceAgainIsNotExcluded() {
        Device device = deviceWithPointer(0x1000L);
        installLocator(new DevicePartitionLocator(() -> List.of("D:\\no-such-mount"), path -> true, millis -> true, 0L, 1));
        driver.handleDevicePlugged(device);
        driver.handleDevicePlugged(device);
        assertEquals("D:\\no-such-mount", driver.getPartitionMountPoint());
        assertEquals(2, pluggedEvents.size());
    }

    @Test
    @DisplayName("a search superseded by a newer one for the same device does not publish")
    void supersededSearchDoesNotPublish() {
        Device device = deviceWithPointer(0x1000L);
        List<String> outcomes = new ArrayList<>();
        // First search: while it waits, the device is unplugged and plugged again, which starts a
        // second search for the very same device. The first one must see itself cancelled.
        installLocator(new DevicePartitionLocator(List::of, path -> false, millis -> true, 0L, 1) {
            private boolean nested = false;

            @Override
            public Optional<String> awaitPartition(java.util.function.BooleanSupplier deviceStillPlugged) {
                if (!nested) {
                    nested = true;
                    driver.handleDeviceUnplugged(device);
                    driver.handleDevicePlugged(device);   // second search, completes and publishes E:
                    outcomes.add("first search still wanted: " + deviceStillPlugged.getAsBoolean());
                    return Optional.of("D:\\");
                }
                return Optional.of("E:\\");
            }
        });

        driver.handleDevicePlugged(device);

        assertEquals(List.of("first search still wanted: false"), outcomes);
        assertEquals("E:\\", driver.getPartitionMountPoint(), "only the newer search published");
        assertEquals(1, pluggedEvents.size());
        assertNull(get(driver, "awaitingPartitionFor"));
    }
}
