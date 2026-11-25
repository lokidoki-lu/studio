/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import org.apache.commons.codec.binary.Hex;
import org.usb4java.Device;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.driver.DeviceVersion;
import studio.driver.LibUsbDetectionHelper;
import studio.driver.model.fs.FsDeviceDiagnosis;
import studio.driver.model.fs.FsDeviceInfos;
import studio.driver.model.fs.FsPackIntegrity;
import studio.driver.model.fs.FsDeviceKeyV3;
import studio.driver.model.fs.FsStoryPackInfos;
import studio.driver.StoryTellerException;
import studio.driver.TransferCallbacks;
import studio.driver.event.DeviceHotplugEventListener;
import studio.driver.event.TransferProgressListener;
import studio.driver.model.TransferStatus;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FsStoryTellerAsyncDriver {

    private static final Logger LOGGER = Logger.getLogger(FsStoryTellerAsyncDriver.class.getName());

    private static final String DEVICE_METADATA_FILENAME = ".md";
    private static final String PACK_INDEX_FILENAME = ".pi";
    /** {@code .pi} is a bare concatenation of big-endian UUIDs; a valid one is a multiple of this. */
    private static final int PACK_INDEX_RECORD_SIZE = 16;
    private static final String CONTENT_FOLDER = ".content";
    private static final String NODE_INDEX_FILENAME = "ni";
    private static final String NIGHT_MODE_FILENAME = "nm";

    // Volatile: written by the libusb detection thread (hotplug callback or active polling), read by
    // the Vert.x threads serving HTTP requests.
    private volatile Device device = null;
    private volatile String partitionMountPoint = null;
    /**
     * Incremented, under the instance lock, every time a device is published or unplugged. A mount
     * point string alone does not identify a card: after card A is unplugged, card B can be mounted
     * on the very same {@code E:\}. An operation therefore freezes the pair (mount point,
     * generation) at its start, and refuses to write or remove anything once the generation moved,
     * even if the mount point string reads the same again.
     *
     * <p>A primitive, not an {@code AtomicLong}: the tests build the driver without running its
     * constructor or field initializers, and a {@code long} needs neither.
     */
    private volatile long partitionGeneration = 0L;
    /**
     * The device whose partition is currently being waited for. Set before the search starts and
     * cleared on unplug, which is what lets an unplug cancel a search already in flight.
     */
    private volatile Device awaitingPartitionFor = null;
    private List<DeviceHotplugEventListener> listeners = new ArrayList<>();

    private final DevicePartitionLocator partitionLocator;
    private final PackIndexWriter packIndexWriter;


    public FsStoryTellerAsyncDriver() {
        this(DevicePartitionLocator.forDeviceMetadataFile(DEVICE_METADATA_FILENAME));
    }

    FsStoryTellerAsyncDriver(DevicePartitionLocator partitionLocator) {
        this(partitionLocator, new TemporaryFilePackIndexWriter());
    }

    FsStoryTellerAsyncDriver(DevicePartitionLocator partitionLocator, PackIndexWriter packIndexWriter) {
        this.partitionLocator = partitionLocator;
        this.packIndexWriter = packIndexWriter;
        this.usbSerialReader = FsStoryTellerAsyncDriver::readUsbSerialNumber;
        // Initialize libusb, handle and propagate hotplug events
        LOGGER.fine("Registering hotplug listener");
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, new DeviceHotplugEventListener() {
                    @Override
                    public void onDevicePlugged(Device device) {
                        FsStoryTellerAsyncDriver.this.onDevicePluggedEvent(device);
                    }

                    @Override
                    public void onDeviceUnplugged(Device device) {
                        FsStoryTellerAsyncDriver.this.handleDeviceUnplugged(device);
                    }
                }
        );
    }

    /**
     * Identifies one partition search. {@link #awaitingPartitionFor} alone cannot: after plug A,
     * unplug A, plug A again, the first search would see "still waiting for A" and carry on next to
     * the second one. Guarded by the instance lock, like the fields it is compared with.
     */
    private Object partitionSearch = null;

    /**
     * Entry point of the hotplug listener. Events reach it one at a time, in the order libusb
     * reported them (see {@code LibUsbDetectionHelper}), so the search is only <em>registered</em>
     * here and run elsewhere: waiting for the partition on the event thread would hold back the
     * very unplug event that is supposed to cancel it.
     */
    private void onDevicePluggedEvent(Device device) {
        Object search = beginPartitionSearch(device);
        CompletableFuture.runAsync(() -> awaitPartitionAndPublish(device, search))
                .exceptionally(e -> {
                    LOGGER.log(Level.SEVERE, "Failed to handle device plug event", e);
                    return null;
                });
    }

    private synchronized Object beginPartitionSearch(Device device) {
        Object search = new Object();
        this.partitionSearch = search;
        this.awaitingPartitionFor = device;
        return search;
    }

    private synchronized boolean isAnotherDevicePublished(Device device) {
        return this.device != null && !this.device.equals(device) && this.partitionMountPoint != null;
    }

    /**
     * The serial number a plugged device reports on USB, or empty when it cannot be read. Set by the
     * production constructor; {@code null} (a driver built without it, as in the tests) means unknown.
     */
    private volatile java.util.function.Function<Device, Optional<String>> usbSerialReader;
    /**
     * The serial number in the {@code .md} of a mount point, or {@code null}. {@code null} reader:
     * read it with {@link #getDeviceInfos(String)}.
     */
    private volatile java.util.function.Function<String, String> volumeSerialReader;

    /**
     * The mount point to skip while searching the partition of {@code device}, read at every attempt:
     * the one of the published device when it is another one. Except when that volume's {@code .md}
     * carries the serial number {@code device} reports on USB: then it is the same Lunii, enumerated
     * again under a new libusb object (its unplug was lost or comes late), and its own partition must
     * not be refused. When either serial cannot be read, or they differ, the volume is skipped.
     */
    private List<String> mountPointToSkip(Device device, Optional<String> newDeviceSerial) {
        String mountPoint;
        synchronized (this) {
            if (!isAnotherDevicePublished(device)) {
                return List.of();
            }
            mountPoint = this.partitionMountPoint;
        }
        if (newDeviceSerial.isPresent()) {
            String volumeSerial = serialOnVolume(mountPoint);
            if (volumeSerial != null && volumeSerial.trim().equalsIgnoreCase(newDeviceSerial.get().trim())) {
                LOGGER.info("The device plugged carries the serial number of the partition " + mountPoint
                        + " already in use: the same Lunii enumerated again, its partition is not skipped");
                return List.of();
            }
        }
        return List.of(mountPoint);
    }

    private String serialOnVolume(String mountPoint) {
        try {
            java.util.function.Function<String, String> reader = this.volumeSerialReader;
            if (reader != null) {
                return reader.apply(mountPoint);
            }
            return getDeviceInfos(mountPoint).join().getSerialNumber();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not read the serial number on " + mountPoint, e);
            return null;
        }
    }

    private Optional<String> usbSerialOf(Device device) {
        java.util.function.Function<Device, Optional<String>> reader = this.usbSerialReader;
        if (reader == null) {
            return Optional.empty();
        }
        try {
            Optional<String> serial = reader.apply(device);
            return serial == null ? Optional.empty() : serial.filter(s -> !s.trim().isEmpty());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not read the USB serial number of the device plugged", e);
            return Optional.empty();
        }
    }

    /** Reads the USB serial number string of {@code device}, opening it without claiming anything. */
    static Optional<String> readUsbSerialNumber(Device device) {
        org.usb4java.DeviceDescriptor descriptor = new org.usb4java.DeviceDescriptor();
        if (org.usb4java.LibUsb.getDeviceDescriptor(device, descriptor) != org.usb4java.LibUsb.SUCCESS
                || descriptor.iSerialNumber() == 0) {
            return Optional.empty();
        }
        org.usb4java.DeviceHandle handle = new org.usb4java.DeviceHandle();
        if (org.usb4java.LibUsb.open(device, handle) != org.usb4java.LibUsb.SUCCESS) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(org.usb4java.LibUsb.getStringDescriptor(handle, descriptor.iSerialNumber()));
        } finally {
            org.usb4java.LibUsb.close(handle);
        }
    }

    private synchronized boolean isSearchCurrent(Device device, Object search) {
        return this.partitionSearch == search && this.awaitingPartitionFor == device;
    }

    /** Ends the search, unless another one (or an unplug) already replaced it. */
    private synchronized void endPartitionSearch(Object search) {
        if (this.partitionSearch == search) {
            this.partitionSearch = null;
            this.awaitingPartitionFor = null;
        }
    }

    /**
     * Resolves the mounted partition, then publishes the device. Runs the whole sequence on the
     * calling thread.
     *
     * <p>Nothing is allowed to escape: this runs inside a {@code CompletableFuture} whose exceptions
     * are only logged, so throwing here used to mean "no device, no message, and no retry until the
     * next plug event" — the device stayed invisible to STUdio even though the OS had mounted it.
     */
    void handleDevicePlugged(Device device) {
        awaitPartitionAndPublish(device, beginPartitionSearch(device));
    }

    private void awaitPartitionAndPublish(Device device, Object search) {
        LOGGER.info("Device plugged; waiting for its partition to be mounted");
        try {
            // While another Lunii is published, its volume is already mounted and carries a .md: the
            // first scan, run before the new one is mounted, used to find it alone and publish the new
            // device on the partition of the other — whose unplug was then ignored. Its volume is
            // skipped (see mountPointToSkip); the same device plugged again is not concerned.
            Optional<String> mountPoint;
            if (isAnotherDevicePublished(device)) {
                Optional<String> serial = usbSerialOf(device);
                mountPoint = partitionLocator.awaitPartition(() -> isSearchCurrent(device, search),
                        () -> mountPointToSkip(device, serial));
            } else {
                mountPoint = partitionLocator.awaitPartition(() -> isSearchCurrent(device, search));
            }

            String published;
            synchronized (this) {
                // Checked and published under the same lock an unplug takes, so an unplug can no
                // longer slip in between "still wanted?" and "publish".
                if (!isSearchCurrent(device, search)) {
                    // Unplugged (or superseded) while we were waiting; the state is not ours anymore.
                    LOGGER.info("Device was unplugged before its partition became available");
                    return;
                }
                endPartitionSearch(search);
                if (!mountPoint.isPresent()) {
                    // Already logged by the locator. Deliberately not thrown: the caller only logs.
                    return;
                }
                this.partitionGeneration++;
                this.partitionMountPoint = mountPoint.get();
                this.device = device;
                published = this.partitionMountPoint;
            }
            LOGGER.info("Device ready on " + published);
            this.listeners.forEach(listener -> listener.onDevicePlugged(device));
        } catch (Exception e) {
            endPartitionSearch(search);
            LOGGER.log(Level.SEVERE, "Failed to handle device plug event", e);
        }
    }

    /**
     * Forgets the device, if it is the one published or the one a partition search is waiting for.
     *
     * <p>Any other unplug is ignored: a {@code DEVICE_LEFT} for another (or an already forgotten)
     * Lunii must not take down the device currently in use, nor move the generation and make the
     * operations running on it refuse to write.
     */
    void handleDeviceUnplugged(Device device) {
        boolean wasCurrent;
        boolean wasAwaited;
        synchronized (this) {
            wasCurrent = device != null && device.equals(this.device);
            wasAwaited = device != null && device.equals(this.awaitingPartitionFor);
            if (!wasCurrent && !wasAwaited) {
                LOGGER.info("Ignoring the unplug of a device that is neither the current one nor awaited: " + device);
                return;
            }
            LOGGER.info("Device unplugged");
            if (wasAwaited) {
                // Cancels the partition search in flight.
                this.awaitingPartitionFor = null;
                this.partitionSearch = null;
            }
            // Prevents a stale mount point from surviving.
            this.partitionGeneration++;
            if (wasCurrent) {
                this.device = null;
                this.partitionMountPoint = null;
            }
        }
        this.listeners.forEach(listener -> listener.onDeviceUnplugged(device));
    }

    /**
     * The identity of the partition an operation started on: its mount point, plus the plug
     * generation it was current in. Two sessions on the same mount point string but different
     * generations are two different cards as far as writes and removals are concerned.
     */
    static final class PartitionSession {
        final String mountPoint;
        final long generation;

        PartitionSession(String mountPoint, long generation) {
            this.mountPoint = mountPoint;
            this.generation = generation;
        }

        @Override
        public String toString() {
            return mountPoint + " (generation " + generation + ")";
        }
    }

    /**
     * Freezes the current partition, or returns {@code null} when no device is ready. Read under the
     * lock that plug and unplug update it with, so the mount point and the generation always belong
     * to the same plug event.
     */
    synchronized PartitionSession currentPartition() {
        String mountPoint = this.partitionMountPoint;
        if (this.device == null || mountPoint == null) {
            return null;
        }
        return new PartitionSession(mountPoint, this.partitionGeneration);
    }

    /** Whether {@code session} is still the partition the driver considers current. */
    private synchronized boolean isCurrent(PartitionSession session) {
        return session.generation == this.partitionGeneration
                && session.mountPoint.equals(this.partitionMountPoint);
    }

    /** Visible for tests: the mount point currently considered valid, if any. */
    String getPartitionMountPoint() {
        return partitionMountPoint;
    }


    public void registerDeviceListener(DeviceHotplugEventListener listener) {
        this.listeners.add(listener);
        if (this.device != null) {
            listener.onDevicePlugged(this.device);
        }
    }


    /**
     * The block size the partition's file store reports, or empty without a partition or when it
     * cannot be read. On FAT32 it is the cluster on some systems and the sector on others: see
     * {@link PackTransferSizeEstimator#clusterBytes}, which decides whether to trust it. Reads no
     * file of the device, so it is not queued with the device operations.
     */
    public OptionalLong partitionBlockSize() {
        String mountPoint = this.partitionMountPoint;
        if (this.device == null || mountPoint == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Files.getFileStore(Paths.get(mountPoint)).getBlockSize());
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not read the block size of the partition " + mountPoint, e);
            return OptionalLong.empty();
        }
    }

    public CompletableFuture<FsDeviceInfos> getDeviceInfos() {
        String mountPoint = this.partitionMountPoint;
        if (this.device == null || mountPoint == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        return getDeviceInfos(mountPoint);
    }

    /** Reads the metadata of the partition the calling operation froze at its start. */
    private CompletableFuture<FsDeviceInfos> getDeviceInfos(String mountPoint) {
        FsDeviceInfos infos = new FsDeviceInfos();
        String mdFile = mountPoint + File.separator + DEVICE_METADATA_FILENAME;
        LOGGER.finest("Reading device infos from file: " + mdFile);
        // try-with-resources, because the stream must be released on every path out of this method,
        // not just the nominal one. The explicit close() this replaces was skipped by the early
        // return for an unsupported metadata version and by every exception raised while parsing, so
        // a device with an unreadable `.md` leaked one handle per call — and getDeviceInfos runs on
        // every /infos request and at the start of every transfer. On Windows the retained handle
        // keeps `.md` locked. Parsing, version dispatch and error mapping below are unchanged.
        // Opened through NIO, not FileInputStream: on Windows a FileInputStream handle is opened
        // without FILE_SHARE_DELETE, so a read served outside the device operation queue (this runs
        // on every /infos request) made a concurrent delete or rename on the card fail.
        try (InputStream deviceMetadataFis = Files.newInputStream(Paths.get(mdFile))) {
            // MD file format version
            short mdVersion = readLittleEndianShort(deviceMetadataFis);
            LOGGER.finest("Device metadata format version: " + mdVersion);
            if (mdVersion >= 1 && mdVersion <= 3) {
                this.parseDeviceInfosMeta1to3(infos, deviceMetadataFis);
            } else if (mdVersion >= 6 && mdVersion <= 7) {
                this.parseDeviceInfosMeta6to7(infos, mdVersion, deviceMetadataFis);
            } else {
                return CompletableFuture.failedFuture(new StoryTellerException("Unsupported device metadata format version: " + mdVersion));
            }

            // SD card size and used space
            File mdFd = new File(mdFile);
            long sdCardTotalSpace = mdFd.getTotalSpace();
            long sdCardUsedSpace = mdFd.getTotalSpace() - mdFd.getFreeSpace();
            infos.setSdCardSizeInBytes(sdCardTotalSpace);
            infos.setUsedSpaceInBytes(sdCardUsedSpace);
            LOGGER.fine("SD card size: " + sdCardTotalSpace);
            LOGGER.fine("SD card used space: " + sdCardUsedSpace);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new StoryTellerException("Failed to read device metadata on partition", e));
        }

        return CompletableFuture.completedFuture(infos);
    }

    private void parseDeviceInfosMeta1to3(FsDeviceInfos infos, InputStream deviceMetadataFis) throws IOException {
        // Firmware version
        deviceMetadataFis.skip(4);
        short major = readLittleEndianShort(deviceMetadataFis);
        short minor = readLittleEndianShort(deviceMetadataFis);
        infos.setFirmwareMajor(major);
        infos.setFirmwareMinor(minor);
        LOGGER.fine("Firmware version: " + major + "." + minor);

        // Serial number
        String serialNumber = null;
        long sn = readBigEndianLong(deviceMetadataFis);
        if (sn != 0L && sn != -1L && sn != -4294967296L) {
            serialNumber = String.format("%014d", sn);
            LOGGER.fine("Serial Number: " + serialNumber);
        } else {
            LOGGER.warning("No serial number in SPI");
        }
        infos.setSerialNumber(serialNumber);

        // UUID
        deviceMetadataFis.skip(238);
        byte[] uuid = deviceMetadataFis.readNBytes(256);
        infos.setUuid(uuid);
        LOGGER.fine("UUID: " + Hex.encodeHexString(uuid));
    }

    private void parseDeviceInfosMeta6to7(FsDeviceInfos infos, short mdVersion, InputStream deviceMetadataFis) throws IOException {
        // Firmware version
        short major = readAsciiToShort(deviceMetadataFis, 1);
        deviceMetadataFis.skip(1);
        short minor = readAsciiToShort(deviceMetadataFis, 1);
        infos.setFirmwareMajor(major);
        infos.setFirmwareMinor(minor);
        LOGGER.fine("Firmware version: " + major + "." + minor);

        // Serial number
        deviceMetadataFis.skip(21);
        byte[] snBytes = deviceMetadataFis.readNBytes(24);
        String serialNumber = new String(snBytes);
        LOGGER.info("Serial Number: " + serialNumber);
        infos.setSerialNumber(serialNumber);

        byte[] aesKey, aesIv, btFile;
        if (mdVersion == 6) {
            // Construct AES key and IV from serial number
            aesKey = new byte[16];
            System.arraycopy(snBytes, 0, aesKey, 0 , 16);
            aesIv = new byte[16];
            System.arraycopy(snBytes, 16, aesIv, 0 , 8);
            System.arraycopy(snBytes, 0, aesIv, 8 , 8);

            // BT file content
            deviceMetadataFis.skip(14);
            btFile = deviceMetadataFis.readNBytes(32);
        } else {    // v7
            // Construct AES key and IV
            deviceMetadataFis.skip(14);
            aesKey = deviceMetadataFis.readNBytes(16);
            aesIv = deviceMetadataFis.readNBytes(16);

            // BT file content from serial number
            btFile = new byte[32];
            System.arraycopy(snBytes, 0, btFile, 0 , 24);
            System.arraycopy(snBytes, 0, btFile, 24 , 8);
        }

        infos.setDeviceKeyV3(new FsDeviceKeyV3(aesKey, aesIv, btFile));

        // Dummy UUID
        byte[] uuid = new byte[64];
        System.arraycopy(aesKey, 0, uuid, 0 , 16);
        System.arraycopy(aesIv, 0, uuid, 16 , 16);
        System.arraycopy(btFile, 0, uuid, 32 , 32);
        infos.setUuid(uuid);
        LOGGER.fine("UUID: " + Hex.encodeHexString(uuid));
    }

    private short readLittleEndianShort(InputStream fis) throws IOException {
        byte[] buffer = new byte[2];
        fis.read(buffer);
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        return bb.getShort();
    }

    private long readBigEndianLong(InputStream fis) throws IOException {
        byte[] buffer = new byte[8];
        fis.read(buffer);
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.order(ByteOrder.BIG_ENDIAN);
        return bb.getLong();
    }

    private short readAsciiToShort(InputStream fis, int numberBytes) throws IOException {
        return Short.parseShort(new String(fis.readNBytes(numberBytes), StandardCharsets.UTF_8));
    }

    private long readAsciiToLong(InputStream fis, int numberBytes) throws IOException {
        return Long.parseLong(new String(fis.readNBytes(numberBytes), StandardCharsets.UTF_8));
    }


    public CompletableFuture<List<FsStoryPackInfos>> getPacksList() {
        String mountPoint = this.partitionMountPoint;
        if (this.device == null || mountPoint == null) {
            return CompletableFuture.failedFuture(noDevice());
        }

        return readPackIndex(mountPoint)
                .thenApply(packUUIDs -> {
                    try {
                        LOGGER.fine("Number of packs in index: " + packUUIDs.size());
                        List<FsStoryPackInfos> packs = new ArrayList<>();
                        for (UUID packUUID : packUUIDs) {
                            FsStoryPackInfos packInfos = new FsStoryPackInfos();
                            packInfos.setUuid(packUUID);
                            LOGGER.fine("Pack UUID: " + packUUID.toString());

                            // Compute .content folder (last 4 bytes of UUID)
                            String folderName = computePackFolderName(packUUID.toString());
                            String packFolderPath = mountPoint + File.separator + CONTENT_FOLDER + File.separator + folderName;
                            packInfos.setFolderName(folderName);

                            // Open 'ni' file
                            File packFolder = new File(packFolderPath);
                            File nodeIndex = new File(packFolder, NODE_INDEX_FILENAME);
                            if (!nodeIndex.exists()) {
                                // An index entry with nothing behind it is a LOCAL inconsistency, not
                                // a corrupt index: the other packs are intact and must stay listable.
                                // Opening the missing file used to throw and fail the whole listing,
                                // which made one stale entry enough to hide every pack on the device.
                                // Nothing is repaired here — the entry stays in .pi, and whatever is
                                // or is not on the card is left exactly as found.
                                LOGGER.warning("Pack " + packUUID + " is listed in the index but has no readable"
                                        + " content on the device; leaving it out of the list");
                                continue;
                            }
                            // try-with-resources: the version read below throws on a truncated 'ni',
                            // and the explicit closes this replaces were skipped when it did. NIO, not
                            // FileInputStream: the listing runs outside the device operation queue, and
                            // on Windows only an NIO handle lets a concurrent delete of the pack proceed.
                            short version;
                            byte[] niHeader;
                            try (InputStream niFis = Files.newInputStream(nodeIndex.toPath());
                                 DataInputStream niDis = new DataInputStream(niFis)) {
                                niHeader = niDis.readNBytes(512);
                            } catch (IOException unreadable) {
                                // Same rule as a missing 'ni': one broken pack must not hide the others.
                                LOGGER.log(Level.WARNING, "Pack " + packUUID + " is listed in the index but its node"
                                        + " index cannot be read; leaving it out of the list", unreadable);
                                continue;
                            }
                            if (niHeader.length < 4) {
                                // A 'ni' cut short (0 bytes after an interrupted copy, for one) has no
                                // version to read; reading it anyway threw and failed the whole listing.
                                // As for a missing 'ni', nothing is repaired: the pack is only left out.
                                LOGGER.warning("Pack " + packUUID + " is listed in the index but its node index is"
                                        + " truncated (" + niHeader.length + " bytes); leaving it out of the list");
                                continue;
                            }
                            version = ByteBuffer.wrap(niHeader).order(ByteOrder.LITTLE_ENDIAN).getShort(2);
                            packInfos.setVersion(version);
                            LOGGER.fine("Pack version: " + version);

                            // Night mode is available if file 'nm' exists
                            packInfos.setNightModeAvailable(new File(packFolder, NIGHT_MODE_FILENAME).exists());

                            // Compute folder size
                            packInfos.setSizeInBytes(FileUtils.getFolderSize(packFolderPath));

                            packs.add(packInfos);
                        }
                        return packs;
                    } catch (Exception e) {
                        throw new StoryTellerException("Failed to read pack metadata on device partition", e);
                    }
                });
    }

    /**
     * The folders under {@code .content} that no entry of {@code .pi} refers to.
     *
     * <p>Answers one question and only that one: which directories exist there that the index does
     * not reference. It says nothing about where they came from — a delete that could not finish, an
     * upload interrupted after it claimed its destination, another tool, a hand edit — because
     * nothing on the card records that. In particular it does not mean "safe to remove": this method
     * reads, and the driver offers no operation to act on what it finds.
     *
     * <p>Only the folder name is reported, and deliberately so. A {@code .content} folder carries the
     * last eight hex characters of a pack UUID and nothing more, so the full UUID is simply not on
     * the card. The comparison therefore runs one way only — every index entry is turned into the
     * folder name it implies, and the names present are matched against that set. Nothing is ever
     * reconstructed in the other direction.
     *
     * <p>Names are compared without regard to case. The card is FAT32, which does not distinguish
     * case, and a pack folder is opened by the upper-case name its UUID implies; a folder differing
     * only in case is the one the driver would open, so treating it as unreferenced would point at a
     * pack that is in use. A false positive is the costly error here, and this avoids it.
     *
     * @return the names of the unreferenced folders, sorted, or an empty list when there is no
     *         {@code .content} directory at all — never a partial answer standing in for a failure.
     */
    public CompletableFuture<List<String>> getUnreferencedPackContentFolders() {
        String mountPoint = this.partitionMountPoint;
        if (this.device == null || mountPoint == null) {
            return CompletableFuture.failedFuture(noDevice());
        }

        return readPackIndex(mountPoint)
                .thenApply(packUUIDs -> {
                    Set<String> referenced = new HashSet<>();
                    for (UUID packUUID : packUUIDs) {
                        referenced.add(computePackFolderName(packUUID.toString()).toUpperCase(Locale.ROOT));
                    }

                    List<String> unreferenced = new ArrayList<>();
                    Path contentFolder = Paths.get(mountPoint, CONTENT_FOLDER);
                    // No `.content` at all is a device with no pack ever written, not a failure. A
                    // listing that fails for any other reason is reported rather than flattened into
                    // an empty answer, which would read as "nothing to see here".
                    if (Files.isDirectory(contentFolder)) {
                        try (Stream<Path> entries = Files.list(contentFolder)) {
                            entries.filter(Files::isDirectory)
                                    .map(entry -> entry.getFileName().toString())
                                    // The leftovers of an interrupted in-place update are reported,
                                    // with what to do about them, by the device check (diagnose).
                                    .filter(name -> FsDeviceMaintenance.stagingHex(name) == null)
                                    .filter(name -> !referenced.contains(name.toUpperCase(Locale.ROOT)))
                                    .forEach(unreferenced::add);
                        } catch (IOException e) {
                            throw new StoryTellerException(
                                    "Failed to list pack content folders on device partition", e);
                        }
                    }
                    // Sorted, because the order a filesystem hands back its entries is its own
                    // business and callers should not inherit it.
                    unreferenced.sort(Comparator.naturalOrder());
                    LOGGER.fine("Unreferenced pack content folders: " + unreferenced.size());
                    return unreferenced;
                });
    }

    /**
     * Removes one {@code .content} folder that the pack index does not reference.
     *
     * <p>This is the operation {@link #getUnreferencedPackContentFolders()} deliberately left out,
     * and it is only ever run on an explicit request naming the folder. The listing cannot say where
     * such a folder came from; the caller, who knows which upload it just lost, can. So the guard
     * here is not provenance but reference: the name must be a pack folder name (eight hex
     * characters), and the index, re-read now, must not point at it. A referenced folder is refused,
     * whatever the caller believes, because removing it would leave the index naming a pack with no
     * content.
     *
     * <p>Callers must not run this concurrently with an upload of the same pack, which creates its
     * folder before indexing it: the web service serializes every device operation for that reason.
     *
     * @return a future completed with {@code true} once the folder is gone, or failed with a
     *         {@link StoryTellerException} whose reason says why nothing was removed.
     */
    public CompletableFuture<Boolean> removeUnreferencedPackContentFolder(String folderName) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        String mountPoint = session.mountPoint;
        if (folderName == null || !folderName.matches("[0-9A-Fa-f]{8}")) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                    "Not a pack content folder name: " + folderName));
        }

        return readPackIndex(mountPoint)
                .thenApply(packUUIDs -> {
                    String wanted = folderName.toUpperCase(Locale.ROOT);
                    for (UUID packUUID : packUUIDs) {
                        if (computePackFolderName(packUUID.toString()).toUpperCase(Locale.ROOT).equals(wanted)) {
                            throw new StoryTellerException(StoryTellerException.Reason.FOLDER_STILL_REFERENCED,
                                    "The pack content folder " + folderName + " is referenced by the pack index ("
                                            + packUUIDs.size() + " entries): it belongs to pack " + packUUID
                                            + " and was not removed");
                        }
                    }
                    // A folder a pending .pi.new names would be a pack again if that index were adopted.
                    for (UUID pendingUUID : FsDeviceMaintenance.pendingIndexEntries(Paths.get(mountPoint))) {
                        if (folderNameOf(pendingUUID).equalsIgnoreCase(wanted)) {
                            throw new StoryTellerException(StoryTellerException.Reason.FOLDER_STILL_REFERENCED,
                                    "The pack content folder " + folderName + " is named by the pending pack index"
                                            + " (.pi.new), as pack " + pendingUUID + ": it was not removed. Resolve the"
                                            + " pending index first.");
                        }
                    }
                    Path contentFolder = Paths.get(mountPoint, CONTENT_FOLDER);
                    Path target = null;
                    if (Files.isDirectory(contentFolder)) {
                        try (Stream<Path> entries = Files.list(contentFolder)) {
                            target = entries.filter(Files::isDirectory)
                                    .filter(entry -> entry.getFileName().toString().equalsIgnoreCase(folderName))
                                    .findFirst()
                                    .orElse(null);
                        } catch (IOException e) {
                            throw new StoryTellerException("Failed to list pack content folders on device partition", e);
                        }
                    }
                    if (target == null) {
                        throw new StoryTellerException(StoryTellerException.Reason.PACK_NOT_ON_DEVICE,
                                "No pack content folder named " + folderName + " on the device partition");
                    }
                    ensureSamePartition(session, "the folder was not removed");
                    LOGGER.info("Removing unreferenced pack content folder: " + target);
                    try {
                        // Leftovers of an update of it may be the same folder on the card (a rename cut short)
                        for (String prefix : List.of(FsDeviceMaintenance.STAGING_NEW_PREFIX, FsDeviceMaintenance.STAGING_OLD_PREFIX)) {
                            requireDistinct(session, contentFolder.resolve(prefix + wanted), target);
                        }
                        org.apache.commons.io.FileUtils.deleteDirectory(target.toFile());
                    } catch (IOException e) {
                        throw new StoryTellerException("Failed to remove pack content folder " + target, e);
                    }
                    return true;
                });
    }

    /** Kept for the tests that reach it by reflection: reads the index of the current partition. */
    private CompletableFuture<List<UUID>> readPackIndex() {
        return readPackIndex(this.partitionMountPoint);
    }

    private CompletableFuture<List<UUID>> readPackIndex(String mountPoint) {
        return CompletableFuture.supplyAsync(() -> {
            String piFile = mountPoint + File.separator + PACK_INDEX_FILENAME;
            LOGGER.finest("Reading packs index from file: " + piFile);

            byte[] index;
            try {
                // Read the index in one go. readAllBytes owns and closes its own channel on every
                // path — the previous loop closed its stream only when the read completed normally —
                // and having the whole file in hand is what makes the framing check below exact.
                // The index is 16 bytes per pack, so this stays in the kilobytes.
                index = Files.readAllBytes(Paths.get(piFile));
            } catch (Exception e) {
                if (isIndexMissingNextToPending(mountPoint)) {
                    // Not an unknown failure: a replacement of the index was cut halfway, and the device
                    // check is where it is resolved. Say so, with the reason the client acts on.
                    throw new StoryTellerException(StoryTellerException.Reason.PACK_INDEX_PENDING,
                            "The pack index (.pi) is missing on the device partition, but a pending one (.pi.new) is"
                                    + " there: an index update was interrupted. Nothing was read, written or removed."
                                    + " It can be resolved from the device check (GET /api/device/diagnosis, then"
                                    + " POST /api/device/repair/pendingIndex).", e);
                }
                throw new StoryTellerException("Failed to read pack index on device partition", e);
            }

            // The index is the only authority on what the device holds, and it cannot be rebuilt
            // from the card. A file that is not a whole number of records is structurally invalid:
            // the previous loop read a partial record into a reused buffer and turned the leftover
            // bytes into a pack UUID that was never written. Report it; do not repair it.
            if (index.length % PACK_INDEX_RECORD_SIZE != 0) {
                throw new StoryTellerException("Invalid pack index on device partition: " + index.length
                        + " bytes is not a whole number of " + PACK_INDEX_RECORD_SIZE + "-byte records");
            }

            List<UUID> packUUIDs = new ArrayList<>();
            ByteBuffer bb = ByteBuffer.wrap(index);
            while (bb.hasRemaining()) {
                long high = bb.getLong();
                long low = bb.getLong();
                packUUIDs.add(new UUID(high, low));
            }
            return packUUIDs;
        });
    }


    public CompletableFuture<Boolean> reorderPacks(List<String> uuids) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        String mountPoint = session.mountPoint;
        List<UUID> requested = new ArrayList<>();
        try {
            if (uuids == null) {
                throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Missing pack UUID list");
            }
            for (String uuid : uuids) {
                requested.add(canonicalPackUuid(uuid));
            }
        } catch (StoryTellerException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }

        return readPackIndex(mountPoint)
                .thenCompose(packUUIDs -> {
                    try {
                        boolean allUUIDsAreOnDevice = packUUIDs.containsAll(requested);
                        if (allUUIDsAreOnDevice) {
                            // Reorder list according to uuids list. Compared as UUIDs, never as the
                            // strings received: an upper-case request would otherwise match nothing
                            // and leave the order unchanged while reporting success.
                            // Only the slots of the requested packs are reordered. A pack of the index
                            // missing from the request (one the list left out, its 'ni' truncated or
                            // missing) keeps its exact position: sorting the whole index by indexOf gave
                            // it -1 and moved it to the top.
                            List<Integer> slots = new ArrayList<>();
                            List<UUID> movable = new ArrayList<>();
                            for (int i = 0; i < packUUIDs.size(); i++) {
                                if (requested.contains(packUUIDs.get(i))) {
                                    slots.add(i);
                                    movable.add(packUUIDs.get(i));
                                }
                            }
                            movable.sort(Comparator.comparingInt(requested::indexOf));
                            List<UUID> reordered = new ArrayList<>(packUUIDs);
                            for (int k = 0; k < slots.size(); k++) {
                                reordered.set(slots.get(k), movable.get(k));
                            }
                            // Write pack index
                            return writePackIndex(session, reordered);
                        } else {
                            throw new StoryTellerException("Packs on device do not match UUIDs");
                        }
                    } catch (Exception e) {
                        throw new StoryTellerException("Failed to read pack metadata on device partition", e);
                    }
                });
    }

    public CompletableFuture<Boolean> deletePack(String uuid) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        String mountPoint = session.mountPoint;
        UUID packUUID;
        try {
            packUUID = canonicalPackUuid(uuid);
        } catch (StoryTellerException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }

        return readPackIndex(mountPoint)
                .thenCompose(packUUIDs -> {
                    // Look for UUID in packs index
                    if (!packUUIDs.contains(packUUID)) {
                        throw new StoryTellerException(StoryTellerException.Reason.PACK_NOT_ON_DEVICE, "Pack not found");
                    }
                    LOGGER.fine("Found pack with uuid: " + packUUID);
                    // Remove EVERY occurrence: an index that lists the pack twice (an older upload
                    // path appended without checking) would otherwise keep pointing at the folder
                    // removed below.
                    packUUIDs.removeIf(packUUID::equals);

                    // A folder is named after the last 8 hex digits of the UUID only, so two distinct
                    // packs can share it. If one that stays in the index does, the folder is its
                    // content too, and removing it would leave that pack indexed with nothing behind it.
                    String packFolderName = folderNameOf(packUUID);
                    Optional<UUID> sharingFolder = packUUIDs.stream()
                            .filter(p -> folderNameOf(p).equalsIgnoreCase(packFolderName))
                            .findFirst();

                    // Write pack index
                    return writePackIndex(session, packUUIDs)
                            .thenCompose(ok -> {
                                if (sharingFolder.isPresent()) {
                                    LOGGER.warning("Pack " + packUUID + " was removed from the index, but its content folder "
                                            + packFolderName + " is also the folder of pack " + sharingFolder.get()
                                            + ", which is still indexed: the folder was left in place");
                                    return CompletableFuture.completedFuture(ok);
                                }
                                // Generate folder name
                                String folderName = mountPoint + File.separator + CONTENT_FOLDER + File.separator + packFolderName;
                                try {
                                    ensureSamePartition(session, "the pack folder " + folderName + " was not removed");
                                } catch (StoryTellerException changed) {
                                    return CompletableFuture.failedFuture(changed);
                                }
                                LOGGER.fine("Removing pack folder: " + folderName);
                                try {
                                    org.apache.commons.io.FileUtils.deleteDirectory(new File(folderName));
                                    return CompletableFuture.completedFuture(ok);
                                } catch (IOException e) {
                                    return CompletableFuture.failedFuture(new StoryTellerException("Failed to delete pack folder on device partition", e));
                                }
                            });
                });
    }

    /**
     * Hands the index off to the {@link PackIndexWriter}, and keeps the async and error contract the
     * callers already rely on: a completed future carrying {@code true}, or a failed one carrying a
     * {@link StoryTellerException} wrapping the original cause. How the file is actually replaced is
     * no longer this class's business.
     *
     * <p>Kept for the tests that reach it by reflection: writes the index of the current partition.
     */
    private CompletableFuture<Boolean> writePackIndex(List<UUID> packUUIDs) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        return writePackIndex(session, packUUIDs);
    }

    /**
     * Writes the index of the partition the calling operation froze at its start — and refuses to,
     * if that is no longer the partition the driver considers current. The index of a device is
     * computed from what was read on that device; writing it anywhere else, or onto a device that
     * was unplugged and replaced in the meantime, would install another card's pack list.
     */
    private CompletableFuture<Boolean> writePackIndex(PartitionSession session, List<UUID> packUUIDs) {
        try {
            ensureSamePartition(session, "the pack index was not written");
        } catch (StoryTellerException changed) {
            return CompletableFuture.failedFuture(changed);
        }
        try {
            packIndexWriter.write(session.mountPoint + File.separator + PACK_INDEX_FILENAME, packUUIDs);
            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            // A leftover .pi.new is a state the user can resolve (GET /api/device/diagnosis): say so
            // with a reason the client can act on, rather than as an unknown failure.
            StoryTellerException.Reason reason = e.getCause() instanceof FileAlreadyExistsException
                    ? StoryTellerException.Reason.PACK_INDEX_PENDING : StoryTellerException.Reason.UNKNOWN;
            return CompletableFuture.failedFuture(new StoryTellerException(reason, "Failed to write pack index on device partition", e));
        }
    }

    /**
     * Fails if the partition an operation started on is no longer the current one: unplugged,
     * replaced, or re-detected elsewhere while the operation was running — including when another
     * card was mounted on the same path meanwhile, which only the generation can tell apart.
     */
    /** The packs of the index whose content folder is {@code folderName} (case ignored). Reads only. */
    public CompletableFuture<List<UUID>> packsUsingFolder(String folderName) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        return readPackIndex(session.mountPoint).thenApply(uuids -> uuids.stream().distinct()
                .filter(u -> folderName != null && folderNameOf(u).equalsIgnoreCase(folderName))
                .collect(Collectors.toList()));
    }

    private void ensureSamePartition(PartitionSession session, String consequence) {
        if (!isCurrent(session)) {
            PartitionSession current = currentPartition();
            throw new StoryTellerException(StoryTellerException.Reason.NO_DEVICE,
                    "The device partition changed while the operation was running (was " + session
                            + ", now " + (current == null ? "none" : current) + "): " + consequence);
        }
    }

    private static StoryTellerException noDevice() {
        return new StoryTellerException(StoryTellerException.Reason.NO_DEVICE, "No device plugged");
    }

    /**
     * Parses a pack UUID received from a caller, and only accepts its canonical spelling.
     *
     * <p>{@code UUID.fromString} is lenient: it accepts {@code 1-2-3-4-5} or groups with leading
     * zeros missing, and turns them into a UUID whose {@code toString()} is a different string. Any
     * name derived from the raw string — the {@code .content} folder, a local destination — would
     * then disagree with the one derived from the UUID that ends up in the index. Such input is
     * refused; only the case of the hex digits may differ.
     */
    static UUID canonicalPackUuid(String uuid) {
        if (uuid == null) {
            throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Missing pack UUID");
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Invalid pack UUID: " + uuid, e);
        }
        if (!parsed.toString().equalsIgnoreCase(uuid)) {
            throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                    "Invalid pack UUID: " + uuid + " is not in canonical form (expected " + parsed + ")");
        }
        return parsed;
    }

    private String folderNameOf(UUID packUUID) {
        return computePackFolderName(packUUID.toString());
    }


    // ================================================================== maintenance: diagnosis and repairs

    /** Reads {@code .pi} in full and refuses one that is not a whole number of records. */
    private static byte[] readValidIndexBytes(String mountPoint) {
        byte[] index;
        try {
            index = Files.readAllBytes(Paths.get(mountPoint, PACK_INDEX_FILENAME));
        } catch (IOException e) {
            throw new StoryTellerException("Failed to read pack index on device partition", e);
        }
        if (index.length % PACK_INDEX_RECORD_SIZE != 0) {
            throw new StoryTellerException("Invalid pack index on device partition: " + index.length
                    + " bytes is not a whole number of " + PACK_INDEX_RECORD_SIZE + "-byte records");
        }
        return index;
    }

    /**
     * As {@link #readValidIndexBytes}, but returns {@code null} when {@code .pi} is missing while a
     * {@code .pi.new} is there. A replacement of the index by a rename can, on some FAT drivers, remove
     * the old entry before it creates the new one; cut in between, it leaves exactly that. The pending
     * index is then the only one left, and the check and its resolution must be able to read it
     * instead of failing on the index that is gone. Without {@code .pi.new} a missing {@code .pi}
     * still fails: there is nothing to adopt, and no index is invented.
     */
    private static byte[] readIndexBytesOrNullIfOnlyPending(String mountPoint) {
        Path index = Paths.get(mountPoint, PACK_INDEX_FILENAME);
        if (isIndexMissingNextToPending(mountPoint)) {
            LOGGER.warning("The pack index " + index + " is missing, but a pending one is there: reading the"
                    + " installed index as empty");
            return null;
        }
        return readValidIndexBytes(mountPoint);
    }

    private static boolean isIndexMissingNextToPending(String mountPoint) {
        Path index = Paths.get(mountPoint, PACK_INDEX_FILENAME);
        Path pending = Paths.get(mountPoint, PACK_INDEX_FILENAME + TemporaryFilePackIndexWriter.TEMPORARY_SUFFIX);
        // notExists, not !exists: an index whose presence cannot be determined is not "missing".
        return Files.notExists(index, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Identifies the Lunii plugged in {@code session}: its serial number and the plug generation. A
     * repair asked with the token of an earlier check is refused if the Lunii was swapped, or
     * unplugged and plugged again, in between: what the check showed may not be this card any more.
     */
    String deviceToken(PartitionSession session) {
        String serial;
        try {
            FsDeviceInfos infos = getDeviceInfos(session.mountPoint).join();
            serial = infos.getSerialNumber() == null ? "" : infos.getSerialNumber().trim();
        } catch (RuntimeException e) {
            serial = "";
        }
        return serial + "#" + session.generation;
    }

    /** Fails with {@code DEVICE_STATE_CHANGED} unless the Lunii plugged now is the one {@code token} names. */
    public CompletableFuture<Void> requireDeviceToken(String token) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        return CompletableFuture.runAsync(() -> requireDeviceToken(session, token));
    }

    private void requireDeviceToken(PartitionSession session, String token) {
        if (token == null || !token.equals(deviceToken(session))) {
            throw new StoryTellerException(StoryTellerException.Reason.DEVICE_STATE_CHANGED,
                    "The Lunii plugged now is not the one that was checked, or it was plugged again since: nothing"
                            + " was done. Check it again.");
        }
    }

    /**
     * Inspects the partition's bookkeeping, reading only: unreferenced folders (with their size),
     * index entries without content, a pending {@code .pi.new} and what discarding or adopting it
     * would mean, and the leftovers of an interrupted in-place update. The answer carries the token
     * every repair must be asked with ({@link #deviceToken}).
     */
    public CompletableFuture<FsDeviceDiagnosis> diagnose() {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        return CompletableFuture.supplyAsync(() -> {
            byte[] index = readIndexBytesOrNullIfOnlyPending(session.mountPoint);
            FsDeviceInfos infos = deviceInfosOrNull(session);
            try {
                FsDeviceDiagnosis diagnosis = FsDeviceMaintenance.diagnose(Paths.get(session.mountPoint), index, infos);
                diagnosis.setDeviceToken(deviceToken(session));
                return diagnosis;
            } catch (IOException e) {
                throw new StoryTellerException("Failed to inspect the device partition", e);
            }
        });
    }

    /** Where {@code .pi} is copied before an adopted {@code .pi.new} replaces it. */
    static final String PACK_INDEX_BACKUP_FILENAME = PACK_INDEX_FILENAME + ".bak";

    /**
     * Resolves a pending {@code .pi.new}: discards it (the installed {@code .pi} stays) or adopts it
     * (it replaces {@code .pi}, by the same atomic move the index writer uses, after {@code .pi} was
     * copied to {@code .pi.bak}).
     *
     * <p>{@code fingerprint} is the one the diagnosis reported: the two files are read again here,
     * and nothing is done if they changed since. Adopting is refused for a torn file, and for one
     * that names any pack whose folder is not a complete copy on the card: an index that lists a
     * pack with nothing behind it, or that drops packs because it was cut short, is never installed.
     *
     * <p>When {@code .pi} is missing next to {@code .pi.new} (a replacement cut between the two halves
     * of the rename), the installed index reads as empty: adopting puts {@code .pi.new} in place under
     * the same conditions (no backup, there is nothing to keep), and discarding is refused, since it
     * would leave the device with no index at all.
     */
    public CompletableFuture<Boolean> resolvePendingIndex(boolean adopt, String fingerprint, String deviceToken) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        return CompletableFuture.supplyAsync(() -> {
            requireDeviceToken(session, deviceToken);
            Path partition = Paths.get(session.mountPoint);
            Path index = partition.resolve(PACK_INDEX_FILENAME);
            Path pending = partition.resolve(PACK_INDEX_FILENAME + TemporaryFilePackIndexWriter.TEMPORARY_SUFFIX);
            if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
                throw new StoryTellerException(StoryTellerException.Reason.DEVICE_STATE_CHANGED,
                        "There is no pending pack index on the device any more: nothing was done");
            }
            // null: .pi is missing next to .pi.new, read as an empty index (see readIndexBytesOrNullIfOnlyPending)
            byte[] current = readIndexBytesOrNullIfOnlyPending(session.mountPoint);
            byte[] pendingBytes;
            try {
                pendingBytes = Files.readAllBytes(pending);
            } catch (IOException e) {
                throw new StoryTellerException("Failed to read the pending pack index", e);
            }
            FsDeviceDiagnosis.PendingIndex analysis = FsDeviceMaintenance.analysePendingIndex(current, pendingBytes,
                    partition.resolve(CONTENT_FOLDER));
            if (fingerprint == null || !fingerprint.equals(analysis.getFingerprint())) {
                throw new StoryTellerException(StoryTellerException.Reason.DEVICE_STATE_CHANGED,
                        "The pack index files changed since they were checked: nothing was done. Check the device again.");
            }
            ensureSamePartition(session, "the pending pack index was left as it is");
            try {
                if (adopt) {
                    if (analysis.getKind() == FsDeviceDiagnosis.PendingIndexKind.TORN) {
                        throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                                "The pending pack index is incomplete and cannot be adopted"
                                        + (current == null
                                        ? ". The pack index (.pi) is missing, so it cannot be discarded either: nothing was done"
                                        : "; it can only be discarded"));
                    }
                    if (!analysis.getUnlistedCompleteFolders().isEmpty()) {
                        throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                                "The pack index (.pi) is missing, and the pending one does not name "
                                        + analysis.getUnlistedCompleteFolders().size() + " complete pack folder(s) of the"
                                        + " device (" + analysis.getUnlistedCompleteFolders() + "): it may have been cut"
                                        + " short, and adopting it would drop them from the index. Nothing was done.");
                    }
                    if (!analysis.isAdoptable()) {
                        throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                                "Adopting the pending pack index would list " + analysis.getIncomplete().size()
                                        + " pack(s) whose folder is not a complete copy on the device ("
                                        + analysis.getIncomplete() + "): nothing was done."
                                        + (current == null ? "" : " It can only be discarded."));
                    }
                    if (current == null) {
                        // No previous index to keep, so an index that appeared since the check must not
                        // be replaced: it would be lost without a backup. Files.move without options
                        // refuses an existing target (FileAlreadyExistsException) — ATOMIC_MOVE would
                        // not: it replaces the target on every platform. On Windows the refusal is
                        // atomic (MoveFileEx without MOVEFILE_REPLACE_EXISTING). On Unix the JDK checks,
                        // then renames: a .pi created in between would still be replaced. That window is
                        // accepted: every device operation of STUdio is serialized by the web service, so
                        // only another program writing .pi at that very instant could hit it. Hard links,
                        // which would close it, do not exist on FAT.
                        LOGGER.info("Adopting the pending pack index " + pending + " in place of the missing " + index);
                        FsSync.forceFile(pending);
                        try {
                            Files.move(pending, index);
                        } catch (FileAlreadyExistsException appeared) {
                            throw new StoryTellerException(StoryTellerException.Reason.DEVICE_STATE_CHANGED,
                                    "A pack index appeared on the device since it was checked: nothing was done."
                                            + " Check the device again.", appeared);
                        }
                    } else {
                        Path backup = partition.resolve(PACK_INDEX_BACKUP_FILENAME);
                        LOGGER.info("Adopting the pending pack index " + pending + "; the previous one is kept as " + backup);
                        Files.copy(index, backup, StandardCopyOption.REPLACE_EXISTING);
                        FsSync.forceFile(backup);
                        FsSync.forceFile(pending);
                        Files.move(pending, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                } else if (current == null) {
                    // .pi.new is the only index left: discarding it would leave the device with none.
                    throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                            "The pack index (.pi) is missing and the pending one (.pi.new) is the only index left on"
                                    + " the device: it was not discarded. It can be adopted if every pack it names is a"
                                    + " complete copy.");
                } else {
                    LOGGER.info("Discarding the pending pack index " + pending);
                    Files.delete(pending);
                }
            } catch (IOException e) {
                throw new StoryTellerException("Failed to resolve the pending pack index", e);
            }
            FsSync.forceDirectoryBestEffort(partition);
            return true;
        });
    }

    /**
     * Finishes, or cleans up after, an in-place update that was interrupted, for the pack folder
     * {@code folderName}. The state is read again here from the folders on the card, and nothing is
     * done unless it is still {@code expectedState} (the one the check showed). See
     * {@link FsDeviceMaintenance#interruptedState} for the states. Whatever the state:
     * <ul>
     *     <li>a copy is only put in place when it is complete;</li>
     *     <li>a complete copy is only removed once the pack folder left in place is complete and passes
     *     the integrity check;</li>
     *     <li>no folder is removed while another name of the pack is the same folder on the card (an
     *     interrupted rename on FAT can leave two entries on the same clusters): the alias probe
     *     ({@link #sameFolderOnCard}) is run before every such removal, and two distinct copies that
     *     merely hold the same files can be cleaned up;</li>
     *     <li>a damaged pack folder (complete, failing the integrity check) next to a healthy copy is
     *     only moved aside, by a rename, for that copy to take its place;</li>
     *     <li>a pack none of whose folders is a complete copy ({@code NO_COMPLETE_COPY}) is removed from
     *     the index, then its incomplete folders;</li>
     *     <li>a pack folder next to both leftovers ({@code CONFLICT}) is never touched.</li>
     * </ul>
     * Every move is a rename, done before any removal, so an interruption leaves a state the check
     * recognises again. The pack index is only touched for {@code NO_COMPLETE_COPY}.
     */
    public CompletableFuture<Boolean> resolveInterruptedUpdate(String folderName, String expectedState, String deviceToken) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        if (folderName == null || !FsDeviceMaintenance.PACK_FOLDER_NAME.matcher(folderName).matches()) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                    "Not a pack content folder name: " + folderName));
        }
        String hex = folderName.toUpperCase(Locale.ROOT);
        return CompletableFuture.supplyAsync(() -> {
            requireDeviceToken(session, deviceToken);
            Path content = Paths.get(session.mountPoint, CONTENT_FOLDER);
            Path folder = content.resolve(hex);
            Path fresh = content.resolve(FsDeviceMaintenance.STAGING_NEW_PREFIX + hex);
            Path previous = content.resolve(FsDeviceMaintenance.STAGING_OLD_PREFIX + hex);
            if (!Files.isDirectory(fresh, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(previous, LinkOption.NOFOLLOW_LINKS)) {
                throw new StoryTellerException(StoryTellerException.Reason.DEVICE_STATE_CHANGED,
                        "No interrupted update is left for the pack folder " + hex + ": nothing was done");
            }
            List<UUID> packUUIDs = readPackIndex(session.mountPoint).join();
            boolean referenced = packUUIDs.stream().anyMatch(u -> folderNameOf(u).equalsIgnoreCase(hex));
            FsDeviceInfos infos = deviceInfosOrNull(session);
            FsDeviceDiagnosis.InterruptedUpdateState state = FsDeviceMaintenance.interruptedState(content, hex, referenced, infos);
            if (expectedState == null || !expectedState.equals(state.name())) {
                throw new StoryTellerException(StoryTellerException.Reason.DEVICE_STATE_CHANGED, "The leftovers of " + hex
                        + " are now in the state " + state + ", not " + expectedState + " as checked: nothing was done."
                        + " Check the device again.");
            }
            ensureSamePartition(session, "the interrupted update was left as it is");
            try {
                resolveInterruptedState(session, state, hex, content, folder, fresh, previous, infos, packUUIDs);
            } catch (IOException e) {
                throw new StoryTellerException("Failed to resolve the interrupted update of " + hex, e);
            }
            FsSync.forceDirectoryBestEffort(content);
            return true;
        });
    }

    private FsDeviceInfos deviceInfosOrNull(PartitionSession session) {
        try {
            return getDeviceInfos(session.mountPoint).join();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void resolveInterruptedState(PartitionSession session, FsDeviceDiagnosis.InterruptedUpdateState state,
                                         String hex, Path content, Path folder, Path fresh, Path previous,
                                         FsDeviceInfos infos, List<UUID> packUUIDs) throws IOException {
        switch (state) {
            case COPY_INTERRUPTED:
                // The pack folder is complete; the new copy, finished or not, is not in use. An
                // unfinished copy holds nothing to keep; a complete one is only removed once the pack
                // folder passes the integrity check.
                if (FsDeviceMaintenance.FolderState.of(fresh) == FsDeviceMaintenance.FolderState.COMPLETE) {
                    requireHealthy(folder, infos, "the complete new copy " + fresh.getFileName() + " was kept"
                            + " (it is put in place instead when it passes the check). If neither passes it, copy both"
                            + " folders off the card to keep them, move " + fresh.getFileName() + " out of .content,"
                            + " check the Lunii again, then update the story from the library");
                }
                requireDistinct(session, folder, fresh);
                LOGGER.info("Removing the unfinished copy of " + hex);
                removeFolder(fresh);
                break;
            case CLEANUP_INTERRUPTED:
                if (FsDeviceMaintenance.FolderState.of(previous) == FsDeviceMaintenance.FolderState.COMPLETE) {
                    requireHealthy(folder, infos, "the previous version " + previous.getFileName() + " was kept"
                            + " (it is put back instead when it passes the check). If neither passes it, copy both"
                            + " folders off the card to keep them, move " + previous.getFileName() + " out of .content,"
                            + " check the Lunii again, then update the story from the library");
                }
                requireDistinct(session, folder, previous);
                LOGGER.info("Removing the previous version of " + hex);
                removeFolder(previous);
                break;
            case NEW_REPLACES_DAMAGED:
                // Renames only: the damaged pack folder is kept as the previous version, which leaves
                // CLEANUP_INTERRUPTED (or SWAP_INTERRUPTED if cut between the two renames). Nothing is
                // removed, so there is no alias probe here: the removal offered next is preceded by one.
                LOGGER.info("Putting the healthy new copy of " + hex + " in place of a pack folder that fails the integrity check");
                renameFolder(folder, previous);
                renameFolder(fresh, folder);
                break;
            case PREVIOUS_REPLACES_DAMAGED:
                // Renames only: the damaged pack folder is kept as a new copy, which leaves
                // COPY_INTERRUPTED (or SWAP_INTERRUPTED, which puts the healthy version back, if cut short).
                LOGGER.info("Putting the healthy previous version of " + hex + " back in place of a pack folder that fails the integrity check");
                renameFolder(folder, fresh);
                renameFolder(previous, folder);
                break;
            case SWAP_INTERRUPTED:
                // The new copy is complete (that is what makes the state). It goes in place if it passes
                // the integrity check, and the previous version is then removed; otherwise the previous
                // version goes back in place and the new copy is kept, for the check to report again.
                if (FsDeviceMaintenance.isHealthy(fresh, infos)) {
                    // Two names for the same folder stay so after a rename: checked before anything moves.
                    requireDistinct(session, fresh, previous);
                    LOGGER.info("Completing the interrupted update of " + hex);
                    renameFolder(fresh, folder);
                    FsSync.forceDirectoryBestEffort(content);
                    removeFolder(previous);
                } else {
                    LOGGER.warning("The new copy of " + hex + " does not pass the integrity check: restoring the previous version");
                    renameFolder(previous, folder);
                }
                break;
            case PREVIOUS_ONLY: {
                // An unfinished copy (never a complete one, by the state) is all that may be left.
                boolean removeFresh = FsDeviceMaintenance.FolderState.of(fresh) == FsDeviceMaintenance.FolderState.INCOMPLETE;
                if (removeFresh) {
                    requireDistinct(session, previous, fresh);
                }
                LOGGER.info("Restoring the previous content of " + hex);
                renameFolder(previous, folder);
                FsSync.forceDirectoryBestEffort(content);
                if (removeFresh) {
                    removeFolder(fresh);
                }
                break;
            }
            case NEW_ONLY:
                // A rename, nothing removed: the only copy left becomes the pack folder. A rename cut
                // short here can leave both names on the same entries (COPY_INTERRUPTED): the removal
                // offered then is preceded by the alias probe (requireDistinct).
                LOGGER.info("Putting the new copy of " + hex + " in place");
                renameFolder(fresh, folder);
                break;
            case ABANDONED_COPY:
                LOGGER.info("Removing the unfinished copy of " + hex + ", the only content left for it");
                removeFolder(fresh);
                break;
            case PROMOTE_NEW:
                // The pack folder is incomplete, the new copy complete: the incomplete folder is moved
                // aside first (a rename), so an interruption leaves SWAP_INTERRUPTED.
                requireDistinct(session, fresh, folder);
                LOGGER.info("Putting the complete new copy of " + hex + " in place of an incomplete folder");
                renameFolder(folder, previous);
                renameFolder(fresh, folder);
                FsSync.forceDirectoryBestEffort(content);
                if (FsDeviceMaintenance.FolderState.of(previous) == FsDeviceMaintenance.FolderState.INCOMPLETE) {
                    removeFolder(previous);
                }
                break;
            case RESTORE_PREVIOUS:
                requireDistinct(session, previous, folder);
                LOGGER.info("Putting the previous version of " + hex + " back in place of an incomplete folder");
                renameFolder(folder, fresh);
                renameFolder(previous, folder);
                FsSync.forceDirectoryBestEffort(content);
                if (FsDeviceMaintenance.FolderState.of(fresh) == FsDeviceMaintenance.FolderState.INCOMPLETE) {
                    removeFolder(fresh);
                }
                break;
            case NO_COMPLETE_COPY:
                removeWithoutCompleteCopy(session, hex, content, folder, fresh, previous, packUUIDs);
                break;
            case UNREFERENCED:
                resolveUnreferenced(session, hex, content, folder, fresh, previous);
                break;
            case CONFLICT:
            default:
                throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "The pack folder " + hex
                        + " and the leftovers of its update cannot be told apart safely (" + state + "): nothing was"
                        + " done. No copy is removed or moved while it is not certain which one holds the pack. To get"
                        + " out of it: check the card for errors (chkdsk on Windows, fsck or First Aid elsewhere), copy"
                        + " the three folders off the card to keep them, move the leftovers " + fresh.getFileName()
                        + " and " + previous.getFileName() + " out of .content (do not delete them), then check the"
                        + " Lunii again.");
        }
    }

    /**
     * {@code NO_COMPLETE_COPY}: no folder of the pack is a complete copy, so nothing can be kept. The
     * pack is removed from the index first (so an interruption leaves only unreferenced leftovers),
     * then every incomplete folder of it; a complete one, should it have appeared since, is never
     * removed. Refused if another indexed pack uses the same folder.
     */
    private void removeWithoutCompleteCopy(PartitionSession session, String hex, Path content, Path folder, Path fresh,
                                           Path previous, List<UUID> packUUIDs) throws IOException {
        Set<UUID> named = packUUIDs.stream().filter(u -> folderNameOf(u).equalsIgnoreCase(hex))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (named.size() != 1) {
            throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "The content folder " + hex
                    + " is the folder of " + named.size() + " packs of the index (" + named + "): nothing was done");
        }
        List<UUID> remaining = new ArrayList<>(packUUIDs);
        remaining.removeIf(named::contains);
        LOGGER.info("Removing " + named + " from the index: none of its folders (" + hex + ") is a complete copy");
        writePackIndex(session, remaining).join();
        FsSync.forceDirectoryBestEffort(Paths.get(session.mountPoint));
        for (Path p : List.of(fresh, previous, folder)) {
            if (FsDeviceMaintenance.FolderState.of(p) == FsDeviceMaintenance.FolderState.INCOMPLETE) {
                ensureSamePartition(session, "the incomplete folder " + p.getFileName() + " was left on the card");
                removeFolder(p);
            }
        }
        FsSync.forceDirectoryBestEffort(content);
    }

    /**
     * Leftovers of a pack the index no longer names: unfinished copies are removed; a complete one is
     * put back under the pack folder name when that name is free (it then shows as an orphan folder,
     * which the user may keep or remove); a complete one that cannot be is left as it is.
     */
    private void resolveUnreferenced(PartitionSession session, String hex, Path content, Path folder, Path fresh,
                                     Path previous) throws IOException {
        boolean changed = false;
        for (Path leftover : List.of(fresh, previous)) {
            FsDeviceMaintenance.FolderState leftoverState = FsDeviceMaintenance.FolderState.of(leftover);
            if (leftoverState == FsDeviceMaintenance.FolderState.INCOMPLETE) {
                for (Path other : List.of(folder, fresh, previous)) {
                    if (!other.equals(leftover)) {
                        requireDistinct(session, other, leftover);
                    }
                }
                removeFolder(leftover);
                changed = true;
            } else if (leftoverState == FsDeviceMaintenance.FolderState.COMPLETE && !Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
                renameFolder(leftover, folder);
                FsSync.forceDirectoryBestEffort(content);
                changed = true;
            }
        }
        if (!changed) {
            throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "The leftovers of " + hex
                    + " are complete copies and the folder " + hex + " exists: nothing was removed. Remove the orphan"
                    + " folder " + hex + " first if it is not needed, then check again.");
        }
    }

    private static void requireHealthy(Path folder, FsDeviceInfos infos, String consequence) {
        if (!FsDeviceMaintenance.isHealthy(folder, infos)) {
            throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "The folder " + folder.getFileName()
                    + " is not a complete pack that passes the integrity check: " + consequence + ". Nothing was removed.");
        }
    }

    /**
     * Refuses, removing nothing, when {@code kept} and {@code removed} are the same folder on the card
     * (see {@link #sameFolderOnCard}). Two distinct folders holding the very same files pass.
     */
    private void requireDistinct(PartitionSession session, Path kept, Path removed) throws IOException {
        if (sameFolderOnCard(session, kept, removed)) {
            throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "The folders " + kept.getFileName()
                    + " and " + removed.getFileName() + " are two names for the same folder on the card (a rename cut"
                    + " short on FAT can leave that): removing one would remove the other. Nothing was removed (CONFLICT)."
                    + " Check the card for errors (chkdsk on Windows, fsck or First Aid elsewhere), then check the Lunii again.");
        }
    }

    /** Prefix of the marker file the alias probe writes, for a moment, in a folder of {@code .content}. */
    static final String ALIAS_PROBE_PREFIX = ".studio-probe-";

    /**
     * The alias probe: whether {@code a} and {@code b} are two directory entries for the same folder on
     * the card — which an interrupted rename on FAT can leave, both names pointing at the same
     * clusters, so that removing one removes the other's files. Comparing contents cannot tell that
     * from two copies that are merely identical; writing can. A marker file with a unique name is
     * created in {@code b}, looked for in {@code a}, and removed, even if the probe fails.
     *
     * <p>It writes on the card: callers run it inside the device operation queue, and the partition
     * session is checked first. If the marker cannot be removed afterwards, the probe fails (nothing
     * is removed by the caller) and says where the marker is. A seam: tests stand in for an alias.
     *
     * @return {@code false} when either folder is absent
     */
    boolean sameFolderOnCard(PartitionSession session, Path a, Path b) throws IOException {
        if (!Files.isDirectory(a, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(b, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        ensureSamePartition(session, "nothing was written or removed");
        String name = ALIAS_PROBE_PREFIX + UUID.randomUUID().toString().replace("-", "");
        Path marker = b.resolve(name);
        Path mirror = a.resolve(name);
        if (Files.exists(mirror, LinkOption.NOFOLLOW_LINKS) || Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "A probe file " + name
                    + " is already there: nothing was removed");
        }
        boolean alias;
        IOException failure = null;
        try {
            Files.createFile(marker);
            FsSync.forceDirectoryBestEffort(b);
            alias = Files.exists(mirror, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            failure = e instanceof IOException ? (IOException) e : new IOException(e);
            alias = false;
        } finally {
            try {
                Files.deleteIfExists(marker);
                Files.deleteIfExists(mirror);
                FsSync.forceDirectoryBestEffort(b);
            } catch (IOException | RuntimeException cleanup) {
                if (failure == null) {
                    failure = new IOException("Could not remove the probe file " + marker, cleanup);
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure == null && (Files.exists(marker, LinkOption.NOFOLLOW_LINKS) || Files.exists(mirror, LinkOption.NOFOLLOW_LINKS))) {
            failure = new IOException("The probe file " + marker + " is still there");
        }
        if (failure != null) {
            throw new StoryTellerException(StoryTellerException.Reason.UNKNOWN, "Could not tell whether " + a.getFileName()
                    + " and " + b.getFileName() + " are the same folder on the card (probe file " + name + "): nothing"
                    + " was removed. If a file named " + name + " is left in either folder, it can be deleted.", failure);
        }
        if (alias) {
            LOGGER.warning(a + " and " + b + " are the same folder on the card");
        }
        return alias;
    }

    /**
     * Removes from the index a pack whose content is missing, as the check reported it — and only if
     * it still is: its folder is absent or has no node index, and no leftover of an update of it is
     * there. Otherwise nothing is done.
     */
    public CompletableFuture<Boolean> removeMissingContentEntry(String uuid, String deviceToken) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        UUID packUUID;
        try {
            packUUID = canonicalPackUuid(uuid);
        } catch (StoryTellerException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        return CompletableFuture.supplyAsync(() -> {
            requireDeviceToken(session, deviceToken);
            String hex = folderNameOf(packUUID);
            Path content = Paths.get(session.mountPoint, CONTENT_FOLDER);
            boolean hasContent = Files.isRegularFile(content.resolve(hex).resolve(NODE_INDEX_FILENAME));
            boolean hasLeftovers = Files.exists(content.resolve(FsDeviceMaintenance.STAGING_NEW_PREFIX + hex), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(content.resolve(FsDeviceMaintenance.STAGING_OLD_PREFIX + hex), LinkOption.NOFOLLOW_LINKS);
            if (hasContent || hasLeftovers) {
                throw new StoryTellerException(StoryTellerException.Reason.DEVICE_STATE_CHANGED, "The pack " + packUUID
                        + " has content on the device again (" + (hasContent ? "its folder " + hex : "leftovers of an update")
                        + "): it was not removed from the index. Check the device again.");
            }
            return true;
        }).thenCompose(ok -> deletePack(packUUID.toString()));
    }

    /** Progress of a whole-device integrity check: files looked at so far, out of an estimated total. */
    public interface IntegrityProgress {
        void onProgress(int checked, int total);
    }

    /**
     * Checks every pack of the index, reading only (see {@link FsDeviceMaintenance#checkPack}).
     * Packs are reported in index order, once each.
     */
    public CompletableFuture<List<FsPackIntegrity>> checkIntegrity(IntegrityProgress progress) {
        return checkIntegrity(progress, uuid -> false);
    }

    /**
     * @param structureOnly the packs for which only what does not depend on a key is checked: the
     *                      official ones, whose ciphered parts may use a key of their own.
     */
    public CompletableFuture<List<FsPackIntegrity>> checkIntegrity(IntegrityProgress progress,
                                                                   java.util.function.Predicate<UUID> structureOnly) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        return readPackIndex(session.mountPoint)
                .thenCompose(packUUIDs -> getDeviceInfos(session.mountPoint)
                        .thenApply(infos -> {
                            requireSupportedFirmware(infos);
                            Path content = Paths.get(session.mountPoint, CONTENT_FOLDER);
                            List<UUID> packs = new ArrayList<>(new LinkedHashSet<>(packUUIDs));
                            int total = 0;
                            for (UUID uuid : packs) {
                                total += FsDeviceMaintenance.filesToCheck(content.resolve(folderNameOf(uuid)));
                            }
                            final int estimated = Math.max(total, 1);
                            int[] checked = {0};
                            List<FsPackIntegrity> results = new ArrayList<>();
                            for (UUID uuid : packs) {
                                ensureSamePartition(session, "the check was not finished");
                                results.add(FsDeviceMaintenance.checkPack(uuid, content.resolve(folderNameOf(uuid)), infos, n -> {
                                    checked[0] += n;
                                    if (progress != null) {
                                        progress.onProgress(checked[0], Math.max(estimated, checked[0]));
                                    }
                                }, structureOnly.test(uuid)));
                            }
                            return results;
                        }));
    }

    /**
     * Whether the pack on the device holds exactly what uploading the library folder
     * {@code libraryFolder} would write: same files, and the same bytes once transformed for this
     * device as an upload transforms them. The boot file, regenerated per device, is left out.
     * Reads only, and stops at the first difference.
     */
    public CompletableFuture<Boolean> packContentMatches(String uuid, String libraryFolder) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        UUID packUUID;
        try {
            packUUID = canonicalPackUuid(uuid);
        } catch (StoryTellerException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        return getDeviceInfos(session.mountPoint).thenApply(infos -> {
            requireSupportedFirmware(infos);
            Path deviceFolder = Paths.get(session.mountPoint, CONTENT_FOLDER, folderNameOf(packUUID));
            Path source = Paths.get(libraryFolder);
            try {
                return sameContent(source, deviceFolder, infos);
            } catch (IOException e) {
                // Not an answer: "different" would be remembered as such by the caller.
                throw new StoryTellerException("Could not compare " + source + " with " + deviceFolder, e);
            }
        });
    }

    private static boolean sameContent(Path source, Path deviceFolder, FsDeviceInfos infos) throws IOException {
        if (!Files.isDirectory(source) || !Files.isDirectory(deviceFolder)) {
            return false;
        }
        Map<String, Path> expected = relativeFiles(source, true);
        Map<String, Path> actual = relativeFiles(deviceFolder, false);
        if (!expected.keySet().equals(actual.keySet())) {
            return false;
        }
        boolean isCleartext = new FsStoryPackReader().isCleartext(source, false) || riLooksCleartext(source);
        for (Map.Entry<String, Path> file : expected.entrySet()) {
            Path s = file.getValue();
            Path d = actual.get(file.getKey());
            byte[] wanted = uploadedBytes(s, isCleartext, infos);
            if (Files.size(d) != wanted.length || !Arrays.equals(wanted, Files.readAllBytes(d))) {
                return false;
            }
        }
        return true;
    }

    /** Regular files under {@code root}, by upper-case relative path; the boot file and {@code .cleartext} left out. */
    private static Map<String, Path> relativeFiles(Path root, boolean library) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                boolean top = root.equals(p.getParent());
                if (top && isBootFile(p)) {
                    continue;
                }
                if (library && !CipherUtils.shouldBeCopied(p)) {
                    continue;
                }
                files.put(root.relativize(p).toString().replace('\\', '/').toUpperCase(Locale.ROOT), p);
            }
        }
        return files;
    }

    /** The bytes an upload writes for {@code s}: the same rules as {@link #copyPackFolder}. */
    private static byte[] uploadedBytes(Path s, boolean isCleartext, FsDeviceInfos deviceInfos) throws IOException {
        byte[] data = Files.readAllBytes(s);
        if (!CipherUtils.shouldBeCiphered(s)) {
            return data;
        }
        if (deviceInfos.getFirmwareMajor() == 2) {
            return isCleartext ? CipherUtils.cipherFirstBlockCommonKey(data) : data;
        }
        if (!isCleartext) {
            data = CipherUtils.decipherFirstBlockCommonKey(data);
        }
        return CipherUtils.cipherFirstBlockSpecificKeyV3(data, deviceInfos.getDeviceKeyV3());
    }

    /**
     * Replaces a pack already on the device by the library folder {@code inputPath}, keeping its
     * place in the index — which is not even rewritten: the entry is the UUID, and the folder name
     * follows from it.
     *
     * <ol>
     *     <li>The new content is copied, exactly as an upload copies it, into
     *     {@code .content/.studio-new-XXXXXXXX}, created exclusively, and pushed to the card. The pack
     *     on the device is untouched meanwhile: an unplug here leaves it playable, plus a leftover.</li>
     *     <li>The pack folder is renamed to {@code .studio-old-XXXXXXXX}, then the new copy to the pack
     *     folder: two renames in the same directory, back to back. FAT has no atomic exchange, so an
     *     unplug exactly between the two leaves the pack folder missing; the diagnosis recognises that
     *     state and finishes the swap by a single rename, nothing copied again.</li>
     *     <li>The previous content is removed. If that is cut short the pack is already updated, and
     *     the leftover is reported by the diagnosis.</li>
     * </ol>
     */
    public CompletableFuture<TransferStatus> updatePack(String uuid, String inputPath, TransferProgressListener listener) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        UUID packUUID;
        try {
            packUUID = canonicalPackUuid(uuid);
        } catch (StoryTellerException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        String mountPoint = session.mountPoint;
        String hex = folderNameOf(packUUID);
        Path content = Paths.get(mountPoint, CONTENT_FOLDER);
        Path folder = content.resolve(hex);
        Path fresh = content.resolve(FsDeviceMaintenance.STAGING_NEW_PREFIX + hex);
        Path previous = content.resolve(FsDeviceMaintenance.STAGING_OLD_PREFIX + hex);

        return readPackIndex(mountPoint).thenCompose(packUUIDs -> {
            if (!packUUIDs.contains(packUUID)) {
                throw new StoryTellerException(StoryTellerException.Reason.PACK_NOT_ON_DEVICE,
                        "The pack " + packUUID + " is not on the device: there is nothing to update, it can be added instead");
            }
            Optional<UUID> sharing = packUUIDs.stream()
                    .filter(p -> !p.equals(packUUID) && folderNameOf(p).equalsIgnoreCase(hex)).findFirst();
            if (sharing.isPresent()) {
                throw new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "The content folder " + hex
                        + " is also the folder of pack " + sharing.get() + ": replacing it would change that pack too");
            }
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
                throw new StoryTellerException(StoryTellerException.Reason.PACK_NOT_ON_DEVICE, "The pack " + packUUID
                        + " is listed by the index but its folder " + hex + " is missing: check the device first");
            }
            if (Files.exists(fresh, LinkOption.NOFOLLOW_LINKS) || Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                throw new StoryTellerException(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS, "An earlier update of "
                        + packUUID + " was interrupted and left " + (Files.exists(fresh) ? fresh : previous)
                        + ": nothing was copied. Resolve it from the device check first (GET /api/device/diagnosis).");
            }
            long requiredBytes;
            try {
                long currentIndexBytes = new File(mountPoint + File.separator + PACK_INDEX_FILENAME).length();
                // The previous content is still on the card while the new one is copied.
                requiredBytes = PackTransferSizeEstimator.additionalBytesForUpload(Paths.get(inputPath), currentIndexBytes);
            } catch (IOException | ArithmeticException e) {
                throw new StoryTellerException(StoryTellerException.Reason.NOT_ENOUGH_SPACE, "Not enough free space on the device", e);
            }
            if (!hasEnoughFreeSpace(new File(mountPoint + File.separator + DEVICE_METADATA_FILENAME).getFreeSpace(), requiredBytes)) {
                throw new StoryTellerException(StoryTellerException.Reason.NOT_ENOUGH_SPACE,
                        "Not enough free space on the device: the new version is copied before the previous one is removed");
            }
            try {
                ensureSamePartition(session, "nothing was copied");
                Files.createDirectory(fresh);
            } catch (FileAlreadyExistsException e) {
                throw new StoryTellerException(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS, "Already exists: " + fresh, e);
            } catch (IOException e) {
                throw new StoryTellerException("Failed to copy pack to device", e);
            }
            AtomicBoolean swapped = new AtomicBoolean(false);
            CompletableFuture<TransferStatus> update = getDeviceInfos(mountPoint).thenApply(deviceInfos -> {
                TransferStatus status;
                try {
                    status = copyPackFolder(session, inputPath, fresh.toFile(), deviceInfos, true, progressOnly(listener));
                    FsSync.forceDirectoryTreeBestEffort(fresh);
                    FsSync.forceDirectoryBestEffort(content);
                } catch (IOException e) {
                    throw new StoryTellerException("Failed to copy pack to device", e);
                }
                ensureSamePartition(session, "the pack on the device was left as it was");
                try {
                    renameFolder(folder, previous);
                } catch (IOException e) {
                    throw new StoryTellerException("Failed to move the previous version of the pack aside", e);
                }
                try {
                    renameFolder(fresh, folder);
                    swapped.set(true);
                } catch (IOException | RuntimeException e) {
                    // Put the previous version back, so the pack stays as it was.
                    try {
                        renameFolder(previous, folder);
                    } catch (IOException | RuntimeException rollback) {
                        e.addSuppressed(rollback);
                        LOGGER.log(Level.SEVERE, "Could not put back " + previous + " after a failed update: the device"
                                + " check will offer to finish or undo it", rollback);
                    }
                    throw e instanceof StoryTellerException ? (StoryTellerException) e
                            : new StoryTellerException("Failed to install the new version of the pack", e);
                }
                FsSync.forceDirectoryBestEffort(content);
                try {
                    removeFolder(previous);
                } catch (IOException | RuntimeException e) {
                    // The pack is updated; only the leftover remains, which the diagnosis reports.
                    LOGGER.log(Level.WARNING, "The pack was updated but its previous version could not be removed: " + previous, e);
                }
                if (listener != null) {
                    listener.onComplete(status);
                }
                return status;
            });
            return update.handle((status, failure) -> {
                if (failure == null) {
                    return CompletableFuture.completedFuture(status);
                }
                Throwable cause = (failure instanceof CompletionException && failure.getCause() != null) ? failure.getCause() : failure;
                if (!swapped.get() && Files.exists(fresh) && Files.isDirectory(folder) && isCurrent(session)) {
                    try {
                        // A rename cut short may have left both names on the same folder: never removed then.
                        if (sameFolderOnCard(session, folder, fresh)) {
                            LOGGER.severe(fresh + " is the same folder as " + folder + " on the card: it was not removed");
                        } else {
                            removeFolder(fresh);
                        }
                    } catch (IOException | RuntimeException cleanup) {
                        cause.addSuppressed(cleanup);
                    }
                }
                return CompletableFuture.<TransferStatus>failedFuture(cause);
            }).thenCompose(f -> f);
        });
    }

    /** Renames a folder within {@code .content}. A seam: tests interrupt an update here. */
    void renameFolder(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Removes a folder and everything in it. A seam, like {@link #renameFolder}. */
    void removeFolder(Path folder) throws IOException {
        org.apache.commons.io.FileUtils.deleteDirectory(folder.toFile());
    }


    /** Prefix of the folder a pack is extracted under in the library; the library listing skips it. */
    static final String LIBRARY_STAGING_PREFIX = ".staging-";

    /**
     * Extracts a pack from the device into the library folder {@code outputPath}, as
     * {@code <outputPath>/<uuid>}.
     *
     * <p>The pack is assembled under a hidden {@code .staging-<uuid>.<random>} sibling and renamed to
     * its final name only once complete, so the library never lists a partial pack: on any failure,
     * unsupported firmware included, the staging folder is removed and {@code <uuid>} never appears.
     * An existing {@code <uuid>} — folder, file, link or junction — is refused, never written into,
     * replaced or followed.
     */
    public CompletableFuture<TransferStatus> downloadPack(String uuid, String outputPath, TransferProgressListener listener) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        String mountPoint = session.mountPoint;
        UUID packUUID;
        try {
            packUUID = canonicalPackUuid(uuid);
        } catch (StoryTellerException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }

        return readPackIndex(mountPoint)
                .thenCompose(packUUIDs -> getDeviceInfos(mountPoint)
                        .thenCompose(deviceInfos -> CompletableFuture.supplyAsync(() -> {
                    // Look for UUID in packs index
                    if (!packUUIDs.contains(packUUID)) {
                        throw new StoryTellerException(StoryTellerException.Reason.PACK_NOT_ON_DEVICE, "Pack not found");
                    }
                    LOGGER.fine("Found pack with uuid: " + packUUID);

                    // Generate folder name
                    String sourceFolder = mountPoint + File.separator + CONTENT_FOLDER + File.separator + folderNameOf(packUUID);
                    LOGGER.finest("Downloading pack folder: " + sourceFolder);
                    if (!Files.exists(Paths.get(sourceFolder))) {
                        throw new StoryTellerException("Pack folder not found");
                    }
                    requireSupportedFirmware(deviceInfos);
                    TransferStatus status = extractIntoLibrary(session, sourceFolder, Paths.get(outputPath), packUUID, deviceInfos, listener);
                    if (listener != null) {
                        listener.onComplete(status);
                    }
                    return status;
                })));
    }

    /** Copies the pack under a staging name, then renames it to {@code <library>/<uuid>}. */
    private TransferStatus extractIntoLibrary(PartitionSession session, String sourceFolder, Path library, UUID packUUID,
                                              FsDeviceInfos deviceInfos, TransferProgressListener listener) {
        Path destination = library.resolve(packUUID.toString());
        refuseExistingLibraryEntry(destination);
        Path staging;
        try {
            Files.createDirectories(library);
            staging = library.resolve(LIBRARY_STAGING_PREFIX + packUUID + "." + UUID.randomUUID());
            Files.createDirectory(staging);
        } catch (IOException e) {
            throw new StoryTellerException("Failed to copy pack from device", e);
        }
        boolean installed = false;
        try {
            TransferStatus status = copyPackFolder(session, sourceFolder, staging.toFile(), deviceInfos, false,
                    progressOnly(listener));
            // Checked again just before the rename: on some platforms a rename onto an existing empty
            // folder replaces it, so this check is what refuses it there. Extractions are serialized
            // by the caller, which keeps the window between the two to that of a concurrent tool.
            refuseExistingLibraryEntry(destination);
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Same parent folder, so this is still a rename; without REPLACE_EXISTING it refuses
                // an existing destination.
                Files.move(staging, destination);
            }
            installed = true;
            return status;
        } catch (FileAlreadyExistsException e) {
            throw libraryEntryExists(destination, e);
        } catch (IOException e) {
            throw new StoryTellerException("Failed to copy pack from device", e);
        } finally {
            if (!installed) {
                removeStagingQuietly(staging);
            }
        }
    }

    private static void refuseExistingLibraryEntry(Path destination) {
        // NOFOLLOW_LINKS: a link or junction at that name is an existing entry, whatever it points to
        // (and even if it points nowhere). It is neither followed nor replaced.
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw libraryEntryExists(destination, null);
        }
    }

    private static StoryTellerException libraryEntryExists(Path destination, Throwable cause) {
        return new StoryTellerException(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS,
                "The pack is already in the library: " + destination + " exists. Nothing was written there.", cause);
    }

    private static void removeStagingQuietly(Path staging) {
        try {
            // commons-io does not follow symbolic links while deleting.
            org.apache.commons.io.FileUtils.deleteDirectory(staging.toFile());
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to remove the staging folder " + staging + " after a failed extraction", e);
        }
    }

    /** Forwards progress but not completion: the operation completes once its last step is done. */
    private static TransferProgressListener progressOnly(TransferProgressListener listener) {
        return new TransferProgressListener() {
            @Override
            public void onProgress(TransferStatus status) {
                if (listener != null) {
                    listener.onProgress(status);
                }
            }

            @Override
            public void onComplete(TransferStatus status) {
                // Not forwarded: the caller reports completion itself, once the pack is in place.
            }
        };
    }

    private static void requireSupportedFirmware(FsDeviceInfos deviceInfos) {
        if (deviceInfos.getFirmwareMajor() != 2 && deviceInfos.getFirmwareMajor() != 3) {
            throw new StoryTellerException("Failed to copy pack folder: unsupported firmware version " + deviceInfos.getFirmwareMajor());
        }
    }

    public CompletableFuture<TransferStatus> uploadPack(String uuid, String inputPath, TransferProgressListener listener) {
        PartitionSession session = currentPartition();
        if (session == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        String mountPoint = session.mountPoint;
        UUID packUUID;
        try {
            packUUID = canonicalPackUuid(uuid);
        } catch (StoryTellerException invalid) {
            return CompletableFuture.failedFuture(invalid);
        }

        // A leftover index temporary makes every index write refuse (TemporaryFilePackIndexWriter
        // will not take over a file it did not create). Finding that out only at the end would mean
        // copying the whole pack first, and leaving it behind unindexed. Ask before anything is
        // reserved or copied.
        Path pendingIndex = Paths.get(mountPoint, PACK_INDEX_FILENAME + TemporaryFilePackIndexWriter.TEMPORARY_SUFFIX);
        if (Files.exists(pendingIndex, LinkOption.NOFOLLOW_LINKS)) {
            LOGGER.warning("Refusing to upload the pack: a pack index temporary file is already present at " + pendingIndex);
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.PACK_INDEX_PENDING,
                    "A pack index temporary file already exists on the device partition: " + pendingIndex
                            + ". The index could not be updated, so the upload was refused before anything was"
                            + " copied. Nothing was read, written or removed. It can be resolved from the device"
                            + " check (GET /api/device/diagnosis)."));
        }

        // Leftovers of an interrupted update of this pack folder hold copies of the pack. An upload
        // next to them could leave a partial pack folder between two complete copies, a state no
        // repair can sort out safely: they are resolved first, from the device check.
        String packHex = folderNameOf(packUUID);
        for (String prefix : List.of(FsDeviceMaintenance.STAGING_NEW_PREFIX, FsDeviceMaintenance.STAGING_OLD_PREFIX)) {
            Path leftover = Paths.get(mountPoint, CONTENT_FOLDER, prefix + packHex);
            if (Files.exists(leftover, LinkOption.NOFOLLOW_LINKS)) {
                LOGGER.warning("Refusing to upload the pack: leftovers of an interrupted update are present at " + leftover);
                return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS,
                        "Leftovers of an interrupted update of this pack are on the device (" + leftover + "): they may"
                                + " hold its only complete copy, so the upload was refused before anything was copied."
                                + " Resolve them from the device check (GET /api/device/diagnosis) first."));
            }
        }

        final Path destFolderPath;
        try {
            // Check free space. The estimate is in bytes the transfer is known to add: the source
            // sizes it used to sum are neither what lands on the device nor all of it.
            long currentIndexBytes = new File(mountPoint + File.separator + PACK_INDEX_FILENAME).length();
            long requiredBytes;
            try {
                requiredBytes = PackTransferSizeEstimator.additionalBytesForUpload(Paths.get(inputPath), currentIndexBytes);
            } catch (ArithmeticException overflow) {
                // An estimate too large to represent is refused rather than wrapped into a small
                // number that would read as "plenty of room".
                throw new StoryTellerException(StoryTellerException.Reason.NOT_ENOUGH_SPACE, "Not enough free space on the device", overflow);
            }
            LOGGER.finest("Pack transfer needs at least " + requiredBytes + " additional bytes");
            String mdFile = mountPoint + File.separator + DEVICE_METADATA_FILENAME;
            File mdFd = new File(mdFile);
            if (!hasEnoughFreeSpace(mdFd.getFreeSpace(), requiredBytes)) {
                throw new StoryTellerException(StoryTellerException.Reason.NOT_ENOUGH_SPACE, "Not enough free space on the device");
            }

            // Generate folder name, from the parsed UUID and never from the string received
            String folderName = mountPoint + File.separator + CONTENT_FOLDER + File.separator + folderNameOf(packUUID);
            LOGGER.fine("Uploading pack to folder: " + folderName);

            // Claim the destination before anything is transferred into it. `.content` is shared by
            // every pack, so its existence is normal and it is created if missing; the pack's own
            // folder is created exclusively, which decides here, once and atomically, whether this
            // upload owns it. `mkdirs()` answered the same question and threw the answer away, so a
            // folder that was already there was written into as if it were ours.
            destFolderPath = Paths.get(folderName);
            try {
                Files.createDirectories(destFolderPath.getParent());
                Files.createDirectory(destFolderPath);
            } catch (FileAlreadyExistsException preexisting) {
                // Not ours. It could be the residue of an interrupted upload, of a delete that could
                // not remove it, of another tool or of a hand edit, and nothing here can tell which.
                // Writing into it would merge two packs into one folder and index the result as valid;
                // emptying it would destroy content whose provenance we do not know.
                LOGGER.warning("Refusing to upload the pack: a destination folder already exists at "
                        + folderName + ", and it was not created by this operation");
                return CompletableFuture.failedFuture(new StoryTellerException(
                        StoryTellerException.Reason.CONTENT_FOLDER_EXISTS,
                        "A pack content folder already exists on the device partition: " + folderName
                                + ". It was not created by this operation, so the upload was refused rather"
                                + " than written into it: merging the pack into an existing or partial folder,"
                                + " or emptying it, would risk content whose origin is not known. Nothing was"
                                + " read, written or removed. If the folder is not referenced by the pack index,"
                                + " it can be removed explicitly (GET /api/device/orphans, then"
                                + " POST /api/device/orphans/remove).", preexisting));
            }
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new StoryTellerException("Failed to copy pack to device", e));
        } catch (StoryTellerException refused) {
            return CompletableFuture.failedFuture(refused);
        }

        // From here the destination folder is ours. It is removed again on every failure that
        // happens before the index has been installed, so a failed upload leaves the card as it found it.
        File destFolder = destFolderPath.toFile();
        AtomicBoolean indexInstalled = new AtomicBoolean(false);
        // Copy folder with progress tracking
        CompletableFuture<TransferStatus> upload = getDeviceInfos(mountPoint).thenCompose(deviceInfos ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        // Completion is not forwarded: the pack must be added to the index first.
                        TransferStatus copied = copyPackFolder(session, inputPath, destFolder, deviceInfos, true, progressOnly(listener));
                        // Every file was forced by copyPackFolder before it returned; the directory entries that name
                        // them are asked for too, best effort, before the index starts pointing at them.
                        FsSync.forceDirectoryTreeBestEffort(destFolderPath);
                        FsSync.forceDirectoryBestEffort(destFolderPath.getParent());
                        return copied;
                    } catch (IOException e) {
                        throw new StoryTellerException("Failed to copy pack from device", e);
                    }
                })).thenCompose(status -> {
                    // Finally, add pack UUID to index
                    return readPackIndex(mountPoint)
                            .thenCompose(packUUIDs -> {
                                if (packUUIDs.contains(packUUID)) {
                                    // The index already names this pack (an entry whose content had
                                    // gone missing): its folder is now back, and a second entry would
                                    // list the pack twice.
                                    LOGGER.info("Pack " + packUUID + " is already listed in the index; not adding it again");
                                    indexInstalled.set(true);
                                    if (listener != null) {
                                        listener.onComplete(status);
                                    }
                                    return CompletableFuture.completedFuture(status);
                                }
                                // Add UUID in packs index
                                packUUIDs.add(packUUID);
                                // Write pack index
                                return writePackIndex(session, packUUIDs)
                                        .thenApply(ok -> {
                                            indexInstalled.set(true);
                                            if (listener != null) {
                                                listener.onComplete(status);
                                            }
                                            return status;
                                        });
                            });
                });

        return upload.handle((status, failure) -> {
            if (failure == null) {
                return CompletableFuture.completedFuture(status);
            }
            Throwable cause = (failure instanceof CompletionException && failure.getCause() != null) ? failure.getCause() : failure;
            if (!indexInstalled.get()) {
                removeFolderCreatedByThisUpload(session, destFolderPath, cause);
            }
            return CompletableFuture.<TransferStatus>failedFuture(cause);
        }).thenCompose(f -> f);
    }

    /**
     * Removes the destination folder after a failed upload. Only ever called with the folder this
     * very upload created exclusively, and only while the index does not reference it yet.
     *
     * <p>Skipped when the partition is no longer the one the upload started on: the same path may
     * now be another card, on which a folder of that name is not ours.
     */
    private void removeFolderCreatedByThisUpload(PartitionSession session, Path destFolderPath, Throwable primaryFailure) {
        if (!isCurrent(session)) {
            LOGGER.warning("Upload failed and the device partition changed meanwhile (started on " + session
                    + "); leaving " + destFolderPath
                    + " in place rather than removing a folder on a partition that may not be the one it was created on");
            return;
        }
        try {
            LOGGER.info("Upload failed; removing the pack folder it created: " + destFolderPath);
            org.apache.commons.io.FileUtils.deleteDirectory(destFolderPath.toFile());
        } catch (IOException | RuntimeException cleanupFailure) {
            LOGGER.log(Level.WARNING, "Failed to remove the pack folder " + destFolderPath + " after a failed upload", cleanupFailure);
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    /** Same rule as FsStoryPackReader's own detection: the ri file starts with a cleartext path. */
    static boolean riLooksCleartext(Path packFolder) throws IOException {
        Path ri = packFolder.resolve(FsStoryPackReader.IMAGE_INDEX_FILENAME);
        if (!Files.isRegularFile(ri)) {
            return false;
        }
        byte[] marker = FsStoryPackReader.CLEARTEXT_RI_BEGINNING;
        byte[] head;
        try (InputStream in = Files.newInputStream(ri)) {
            head = in.readNBytes(marker.length);
        }
        return java.util.Arrays.equals(head, marker);
    }

    /**
     * Copies a pack folder, transcoding its assets for the direction of the transfer.
     *
     * <p>{@code destFolder} must have just been created, exclusively, by the calling operation: every
     * file is written with {@code CREATE_NEW}, so nothing that already exists in it is ever
     * truncated or replaced. Before each file, the partition the operation started on must still be
     * the current one, so that a thread left over from a card that was unplugged never writes to (or
     * reads from) another card mounted at the same path.
     */
    private TransferStatus copyPackFolder(PartitionSession session, String sourceFolder, File destFolder, FsDeviceInfos deviceInfos, boolean isUpload, TransferProgressListener listener) throws IOException {
        // Keep track of transferred bytes and elapsed time
        final long startTime = System.currentTimeMillis();
        AtomicLong transferred = new AtomicLong(0);
        long folderSize = FileUtils.getFolderSize(sourceFolder);
        LOGGER.finest("Pack folder size: " + folderSize);

        // Fail for unsupported firmware versions
        requireSupportedFirmware(deviceInfos);
        String partitionChanged = isUpload ? "the pack was not copied to the device" : "the pack was not extracted";

        // Assets are cleartext if file '.cleartext' exists, or (a pack missing it) if its ri looks so.
        // Read only: isCleartext(.., true) used to create '.cleartext' in the source, a pack of the
        // library, whose content digest then no longer matched its conversion record.
        boolean isCleartext = (new FsStoryPackReader()).isCleartext(Paths.get(sourceFolder), false)
                || (isUpload && riLooksCleartext(Paths.get(sourceFolder)));
        
        // Files written to the card by this upload, forced in one pass once the copy is over.
        List<Path> writtenFiles = new ArrayList<>();

        // Always add .cleartext file when downloading
        if (!isUpload) {
            // Indicate that files are cleartext
            Files.write(destFolder.toPath().resolve(FsStoryPackReader.CLEARTEXT_FILENAME), new byte[0], EXCLUSIVE);
        }

        // Copy folders and files
        // The stream owns an open directory handle per level; when the lambda below throws, the
        // traversal is abandoned mid-way and only try-with-resources still closes them. Leaving them
        // open makes the source pack undeletable on Windows, and on FAT32 its directory entry
        // survives in the parent even after a successful delete.
        Path sourceRoot = Paths.get(sourceFolder);
        try (Stream<Path> sourcePaths = Files.walk(sourceRoot)) {
            sourcePaths.forEach(s -> {
                try {
                    Path d = destFolder.toPath().resolve(sourceRoot.relativize(s));
                    if (Files.isDirectory(s)) {
                        if (!Files.exists(d)) {
                            ensureSamePartition(session, partitionChanged);
                            LOGGER.finer("Creating directory " + d.toString());
                            Files.createDirectory(d);
                        }
                    } else {
                        // DO NOT COPY .cleartext file
                        if (!CipherUtils.shouldBeCopied(s)) {
                            LOGGER.finer("NOT copying file " + s.toString());
                            return;
                        }

                        long fileSize = FileUtils.getFileSize(s.toAbsolutePath().toString());
                        // The boot file is device specific and regenerated below once the copy is over;
                        // the one a pack extracted from a device carries is not copied, only accounted
                        // for, so that the generated one can be written exclusively like every other file.
                        if (s.getParent() != null && s.getParent().equals(sourceRoot) && isBootFile(s)) {
                            LOGGER.finer("NOT copying boot file " + s + ": it is regenerated for the target");
                            transferred.addAndGet(fileSize);
                            return;
                        }
                        ensureSamePartition(session, partitionChanged);
                        LOGGER.finer("Copying file " + s.toString() + " to " + d.toString() + " (" + fileSize + " bytes)");

                        // Only the first block of a file is ciphered: it is transformed in memory and
                        // the rest of the file is copied as is, without loading the file (see
                        // CipherUtils.transformFirstBlock).
                        if (CipherUtils.shouldBeCiphered(s)) {
                            if (deviceInfos.getFirmwareMajor() == 2) {
                                if (isUpload) {
                                    if (isCleartext) {
                                        CipherUtils.transformFirstBlock(s, d, CipherUtils::cipherFirstBlockCommonKey, EXCLUSIVE);
                                    } else {
                                        Files.copy(s, d);
                                    }
                                } else {    // Download
                                    CipherUtils.transformFirstBlock(s, d, CipherUtils::decipherFirstBlockCommonKey, EXCLUSIVE);
                                }
                            } else {    // V3
                                FsDeviceKeyV3 deviceKeyV3 = deviceInfos.getDeviceKeyV3();
                                if (isUpload) {
                                    CipherUtils.transformFirstBlock(s, d, head -> CipherUtils.cipherFirstBlockSpecificKeyV3(
                                            isCleartext ? head : CipherUtils.decipherFirstBlockCommonKey(head), deviceKeyV3), EXCLUSIVE);
                                } else {    // Download
                                    CipherUtils.transformFirstBlock(s, d, head -> CipherUtils.decipherFirstBlockSpecificKeyV3(head, deviceKeyV3), EXCLUSIVE);
                                }
                            }
                        } else {
                            Files.copy(s, d);
                        }
                        if (isUpload) {
                            writtenFiles.add(d);
                        }

                        // Compute progress and speed
                        long xferred = transferred.addAndGet(fileSize);
                        long elapsed = System.currentTimeMillis() - startTime;
                        double speed = ((double) xferred) / ((double) elapsed / 1000.0);
                        LOGGER.finer("Transferred " + xferred + " bytes in " + elapsed + " ms");
                        LOGGER.finer("Average speed = " + speed + " bytes/sec");
                        TransferStatus status = new TransferStatus(xferred == folderSize, xferred, folderSize, speed);

                        // Call (optional) listener with transfer status
                        if (listener != null) {
                            // One thread for every callback, in the order they are issued: on the common
                            // pool a progress could arrive after the completion, or go backwards.
                            CompletableFuture.runAsync(() -> listener.onProgress(status), TransferCallbacks.EXECUTOR);
                            if (status.isDone()) {
                                CompletableFuture.runAsync(() -> listener.onComplete(status), TransferCallbacks.EXECUTOR);
                            }
                        }
                    }
                } catch (StoryTellerException e) {
                    // Already says what went wrong, and with which reason (a partition that changed
                    // must still read as NO_DEVICE).
                    throw e;
                } catch (Exception e) {
                    throw new StoryTellerException("Failed to copy pack folder", e);
                }
            });
        }
        // When transfer is complete, generate device-specific boot file
        LOGGER.fine("Generating device-specific boot file");
        ensureSamePartition(session, partitionChanged);
        try {
            if (deviceInfos.getFirmwareMajor() == 2) {
                CipherUtils.addBootFileV2(destFolder.toPath(), deviceInfos.getUuid(), EXCLUSIVE);
            } else {
                CipherUtils.addBootFileV3(destFolder.toPath(), deviceInfos.getDeviceKeyV3(), EXCLUSIVE);
            }
        } catch (IOException e) {
            throw new StoryTellerException("Failed to generate device-specific boot file", e);
        }
        if (isUpload) {
            // The index is written only once every file is on the card. Rather than forcing each
            // file as soon as it is written — a synchronous flush between every two copies — every
            // file of the pack, boot file included, is pushed to the card in one pass here. This
            // method returns only after that pass, and the index is written only after it returns,
            // so the guarantee is the same: nothing the index points at is left in the OS cache.
            writtenFiles.add(destFolder.toPath().resolve(CipherUtils.BOOT_FILENAME));
            for (Path written : writtenFiles) {
                try {
                    FsSync.forceFile(written);
                } catch (IOException e) {
                    throw new StoryTellerException("Failed to flush " + written + " to the device", e);
                }
            }
        }
        return new TransferStatus(transferred.get() == folderSize, transferred.get(), folderSize, 0.0);
    }

    /**
     * Options for every file a copy writes: the folder was created exclusively by the operation, so
     * a file already there is not ours and must make the copy fail rather than be truncated.
     * ({@code Files.copy} without {@code REPLACE_EXISTING} already refuses an existing target.)
     */
    private static final OpenOption[] EXCLUSIVE = {StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE};

    private static boolean isBootFile(Path file) {
        return CipherUtils.BOOT_FILENAME.equalsIgnoreCase(file.getFileName().toString());
    }

    /**
     * The preflight decision, kept apart from the filesystem so it can be exercised directly.
     *
     * <p>Both operands are {@code long} and are compared rather than subtracted: a difference beyond
     * the int range — or beyond the long range — is exactly the case the old {@code int} estimate got
     * wrong, by wrapping negative and passing the check unconditionally.
     */
    static boolean hasEnoughFreeSpace(long freeSpace, long requiredBytes) {
        return freeSpace >= requiredBytes;
    }

    public String computePackFolderName(String uuid) {
        String uuidStr = uuid.replaceAll("-", "");
        return uuidStr.substring(uuidStr.length() - 8).toUpperCase();
    }
}
