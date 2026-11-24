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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plug and unplug events reach the drivers one at a time, in the order they were reported. */
class HotplugEventOrderTest {

    @Test
    @DisplayName("events are delivered in submission order, on a single thread, even when a listener is slow")
    void eventsKeepTheirOrder() throws Exception {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        Set<String> threads = Collections.synchronizedSet(new HashSet<>());
        DeviceHotplugEventListener listener = new DeviceHotplugEventListener() {
            @Override
            public void onDevicePlugged(Device device) {
                threads.add(Thread.currentThread().getName());
                // A slow ARRIVED used to be overtaken by the LEFT that followed it.
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                received.add("arrived");
            }

            @Override
            public void onDeviceUnplugged(Device device) {
                threads.add(Thread.currentThread().getName());
                received.add("left");
            }
        };

        List<String> expected = new ArrayList<>();
        CompletableFuture<Void> last = null;
        for (int i = 0; i < 50; i++) {
            LibUsbDetectionHelper.dispatchPlugged(listener, null);
            last = LibUsbDetectionHelper.dispatchUnplugged(listener, null);
            expected.add("arrived");
            expected.add("left");
        }
        last.get(10, TimeUnit.SECONDS);

        assertEquals(expected, received);
        assertEquals(1, threads.size(), "one event thread: " + threads);
    }

    @Test
    @DisplayName("a listener that throws does not stop the events that follow")
    void aFailingListenerDoesNotStopDelivery() throws Exception {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        DeviceHotplugEventListener listener = new DeviceHotplugEventListener() {
            @Override
            public void onDevicePlugged(Device device) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void onDeviceUnplugged(Device device) {
                received.add("left");
            }
        };

        LibUsbDetectionHelper.dispatchPlugged(listener, null);
        LibUsbDetectionHelper.dispatchUnplugged(listener, null).get(10, TimeUnit.SECONDS);

        assertEquals(List.of("left"), received);
    }
}
