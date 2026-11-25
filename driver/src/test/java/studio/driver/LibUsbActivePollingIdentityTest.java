/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.usb4java.Device;
import studio.driver.event.DeviceHotplugEventListener;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Windows poller (no hotplug there) announces a device that changed between two scans: another
 * Lunii, or the same one plugged again at another bus address. Comparing presence alone fired no
 * event, so the drivers kept the previous device and its drive letter. The decision is driven through
 * {@code onScanResult}, with no native libusb.
 */
class LibUsbActivePollingIdentityTest {

    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    private final DeviceHotplugEventListener listener = new DeviceHotplugEventListener() {
        @Override
        public void onDevicePlugged(Device device) {
            events.add("plug " + pointerOf(device));
        }

        @Override
        public void onDeviceUnplugged(Device device) {
            events.add("unplug " + pointerOf(device));
        }
    };

    private final LibUsbActivePollingWorker poller =
            new LibUsbActivePollingWorker(null, DeviceVersion.DEVICE_VERSION_2, listener);

    private static Device device(long pointer) {
        try {
            Constructor<Device> ctor = Device.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            Device device = ctor.newInstance();
            Field f = Device.class.getDeclaredField("devicePointer");
            f.setAccessible(true);
            f.set(device, pointer);
            return device;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long pointerOf(Device device) {
        try {
            Field f = Device.class.getDeclaredField("devicePointer");
            f.setAccessible(true);
            return (long) f.get(device);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Events are delivered on the hotplug event thread: waits for those already submitted. */
    private List<String> deliveredEvents() throws Exception {
        LibUsbDetectionHelper.dispatchPlugged(new DeviceHotplugEventListener() {
            @Override
            public void onDevicePlugged(Device device) {
            }

            @Override
            public void onDeviceUnplugged(Device device) {
            }
        }, null).get(5, TimeUnit.SECONDS);
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    @Test
    @DisplayName("a Lunii swapped for another between two scans: unplug of the first, then plug of the second")
    void swappedDevice() throws Exception {
        poller.onScanResult(device(0x1000L), "1:5");
        poller.onScanResult(device(0x2000L), "1:6");
        assertEquals(List.of("plug 4096", "unplug 4096", "plug 8192"), deliveredEvents());
    }

    @Test
    @DisplayName("the same libusb device at another address (plugged again): unplug, then plug")
    void replugAtAnotherAddress() throws Exception {
        poller.onScanResult(device(0x1000L), "1:5");
        poller.onScanResult(device(0x1000L), "1:7");
        assertEquals(List.of("plug 4096", "unplug 4096", "plug 4096"), deliveredEvents());
    }

    @Test
    @DisplayName("a stable device fires one plug only; losing it fires one unplug")
    void stableDevice() throws Exception {
        for (int i = 0; i < 5; i++) {
            poller.onScanResult(device(0x1000L), "1:5");
        }
        poller.onScanResult(null, null);
        poller.onScanResult(null, null);
        assertEquals(List.of("plug 4096", "unplug 4096"), deliveredEvents());
    }
}
