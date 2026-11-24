/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.raw;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import org.usb4java.Device;
import studio.driver.event.DeviceHotplugEventListener;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The 1.x driver only forgets its device on the unplug of that very device. */
class RawDeviceUnplugTest {

    private RawStoryTellerAsyncDriver driver;
    private final List<Device> unplugged = new ArrayList<>();

    private static Device deviceWithPointer(long pointer) {
        try {
            Constructor<Device> ctor = Device.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            Device device = ctor.newInstance();
            set(device, "devicePointer", pointer);
            return device;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void driverWithoutLibUsb() {
        driver = new ObjenesisStd().newInstance(RawStoryTellerAsyncDriver.class);
        List<DeviceHotplugEventListener> listeners = new ArrayList<>();
        listeners.add(new DeviceHotplugEventListener() {
            @Override
            public void onDevicePlugged(Device device) {
            }

            @Override
            public void onDeviceUnplugged(Device device) {
                unplugged.add(device);
            }
        });
        set(driver, "listeners", listeners);
    }

    @Test
    @DisplayName("the unplug of another device, or of none, is ignored")
    void unplugOfAnotherDeviceIsIgnored() {
        Device current = deviceWithPointer(0x1000L);
        set(driver, "device", current);

        driver.handleDeviceUnplugged(deviceWithPointer(0x2000L));
        driver.handleDeviceUnplugged(null);

        assertSame(current, get(driver, "device"));
        assertEquals(List.of(), unplugged);
    }

    @Test
    @DisplayName("the unplug of the current device forgets it and notifies the listeners")
    void unplugOfTheCurrentDevice() {
        Device current = deviceWithPointer(0x1000L);
        set(driver, "device", current);
        Device sameDeviceOtherWrapper = deviceWithPointer(0x1000L);

        driver.handleDeviceUnplugged(sameDeviceOtherWrapper);

        assertNull(get(driver, "device"));
        assertEquals(List.of(sameDeviceOtherWrapper), unplugged);
    }
}
