/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver;

import org.usb4java.*;
import studio.driver.event.DeviceHotplugEventListener;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LibUsbActivePollingWorker implements Runnable {

    private static final Logger LOGGER = Logger.getLogger(LibUsbActivePollingWorker.class.getName());

    /** Only log every Nth consecutive failure past the first. */
    static final int FAILURE_LOG_INTERVAL = 12;

    private final Context context;
    private final DeviceVersion deviceVersion;
    private final DeviceHotplugEventListener listener;
    private Device device = null;
    /** Where {@link #device} sat on the bus when it was announced (see {@link #identityOf}). */
    private Object deviceIdentity = null;
    private int consecutiveFailures = 0;

    public LibUsbActivePollingWorker(Context context, DeviceVersion deviceVersion, DeviceHotplugEventListener listener) {
        this.context = context;
        this.deviceVersion = deviceVersion;
        this.listener = listener;
    }

    /**
     * Runs one scan.
     *
     * <p>This is scheduled with {@code ScheduledExecutorService.scheduleAtFixedRate}, whose contract
     * is unforgiving: <em>if any execution of the task encounters an exception, subsequent executions
     * are suppressed</em>. The scan below reads a descriptor for every USB device on the machine, and
     * {@code getDeviceDescriptor} legitimately fails with a transient error when a device disappears
     * between the list snapshot and the read — which is exactly what happens while devices are being
     * plugged and unplugged. Letting that propagate cancelled the polling task permanently: the
     * backend stayed up and served HTTP, but no device was ever detected again until STUdio was
     * restarted, with nothing in the UI to explain it.
     *
     * <p>On Windows libusb reports no hotplug capability, so this poller — not the hotplug callback —
     * is the whole of device detection. Nothing may escape this method.
     */
    @Override
    public void run() {
        try {
            scanOnce();
            if (consecutiveFailures > 0) {
                LOGGER.info("Active polling recovered after " + consecutiveFailures + " consecutive failure(s)");
                consecutiveFailures = 0;
            }
        } catch (Exception e) {
            int failures = ++consecutiveFailures;
            // Throttled: a permanently failing scan must not fill the log file every POLL_DELAY.
            if (failures == 1 || failures % FAILURE_LOG_INTERVAL == 0) {
                LOGGER.log(Level.WARNING, "Active polling scan failed (consecutive failures: " + failures
                        + "); detection continues on the next scan", e);
            }
        }
    }

    /** Observable so a degraded poller can be diagnosed without reading the logs. */
    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    /**
     * Where a device sits on the bus: its bus number and address. A device unplugged and plugged
     * again is enumerated anew and usually gets another address, even if libusb hands back the same
     * object for it.
     */
    protected Object identityOf(Device d) {
        return LibUsb.getBusNumber(d) + ":" + LibUsb.getDeviceAddress(d);
    }

    /**
     * Fires the events one scan calls for. Comparing presence alone missed a device unplugged and
     * plugged again, or swapped for another Lunii, between two scans (POLL_DELAY apart): no event, so
     * the drivers kept the previous device, and its drive letter. A device found that is not the one
     * announced (another libusb device, or the same one at another bus address) is now announced as
     * the unplug of the previous one, then the plug of this one, in that order.
     *
     * @param identity {@link #identityOf(Device)} of {@code found}, {@code null} when nothing was found
     */
    void onScanResult(Device found, Object identity) {
        if (found != null && this.device != null
                && (!found.equals(this.device) || !Objects.equals(identity, this.deviceIdentity))) {
            LOGGER.info("Active polling found another device than the one announced (" + this.deviceIdentity
                    + " -> " + identity + "). Firing unplug, then plug.");
            Device lost = this.device;
            this.device = null;
            this.deviceIdentity = null;
            LibUsbDetectionHelper.dispatchUnplugged(this.listener, lost);
        }
        if (found != null && this.device == null) {
            LOGGER.info("Active polling found a new device. Firing event.");
            this.device = found;
            this.deviceIdentity = identity;
            LibUsbDetectionHelper.dispatchPlugged(this.listener, found);
        } else if (found == null && this.device != null) {
            LOGGER.info("Active polling lost the device. Firing event.");
            // The device is captured here, not read from the field inside the task: the field is
            // cleared on the next line, and the task used to run after that, announcing the
            // unplug of "null" — which the drivers now ignore as not being their device.
            Device lost = this.device;
            this.device = null;
            this.deviceIdentity = null;
            LibUsbDetectionHelper.dispatchUnplugged(this.listener, lost);
        }
    }

    /** Seam: the actual scan, overridden in tests to inject failures without native libusb. */
    protected void scanOnce() {
        // List available devices
        DeviceList devices = new DeviceList();
        int result = LibUsb.getDeviceList(this.context, devices);
        if (result < 0) {
            throw new LibUsbException("Unable to get libusb device list", result);
        }
        try {
            // Iterate over all devices and scan for the right one
            Device found = null;
            for (Device d: devices) {
                DeviceDescriptor descriptor = new DeviceDescriptor();
                result = LibUsb.getDeviceDescriptor(d, descriptor);
                if (result != LibUsb.SUCCESS) {
                    throw new LibUsbException("Unable to read libusb device descriptor", result);
                }
                if (
                        (deviceVersion == DeviceVersion.DEVICE_VERSION_1 || deviceVersion == DeviceVersion.DEVICE_VERSION_ANY)
                        && descriptor.idVendor() == LibUsbDetectionHelper.VENDOR_ID_FW1 && descriptor.idProduct() == LibUsbDetectionHelper.PRODUCT_ID_FW1
                ) {
                    found = d;
                }
                if (
                        (deviceVersion == DeviceVersion.DEVICE_VERSION_2 || deviceVersion == DeviceVersion.DEVICE_VERSION_ANY)
                        && (
                                (descriptor.idVendor() == LibUsbDetectionHelper.VENDOR_ID_FW2 && descriptor.idProduct() == LibUsbDetectionHelper.PRODUCT_ID_FW2)
                                || (descriptor.idVendor() == LibUsbDetectionHelper.VENDOR_ID_V2 && descriptor.idProduct() == (short)(LibUsbDetectionHelper.PRODUCT_ID_V2 & 0xffff))
                        )
                ) {
                    found = d;
                }
            }
            // Fire plugged / unplugged events
            onScanResult(found, found == null ? null : identityOf(found));
        } finally {
            // Ensure the allocated device list is freed
            LibUsb.freeDeviceList(devices, false);  // Do NOT unref devices
        }
    }
}
