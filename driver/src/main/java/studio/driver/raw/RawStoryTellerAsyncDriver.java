/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.raw;

import org.usb4java.Device;
import org.usb4java.DeviceHandle;
import studio.driver.DeviceVersion;
import studio.driver.LibUsbDetectionHelper;
import studio.driver.StoryTellerException;
import studio.driver.event.DeviceHotplugEventListener;
import studio.driver.event.TransferProgressListener;
import studio.driver.model.raw.RawDeviceInfos;
import studio.driver.model.raw.RawStoryPackInfos;
import studio.driver.model.TransferStatus;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class RawStoryTellerAsyncDriver {

    private static final Logger LOGGER = Logger.getLogger(RawStoryTellerAsyncDriver.class.getName());

    private static final int SDCARD_DEFAULT_SIZE_IN_SECTORS = 6815513;
    private static final int SDCARD_FAT16_PARTITION_SIZE_IN_SECTORS = 20480;    // 10.5 MB
    private static final int DEVICE_INFOS_SPI_OFFSET = 520192;
    private static final int DEVICE_INFOS_SD_SECTOR_0 = 0;
    private static final int DEVICE_INFOS_SD_SECTOR_2 = 2;
    private static final int PACK_INDEX_SD_SECTOR = 100000;
    private static final int PACK_TRANSFER_CHUNK_SIZE_IN_SECTORS = 5000;    // 2.5 MB

    private Device device = null;
    private List<DeviceHotplugEventListener> listeners = new ArrayList<>();


    public RawStoryTellerAsyncDriver() {
        // Initialize libusb, handle and propagate hotplug events
        LOGGER.fine("Registering hotplug listener");
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_1, new DeviceHotplugEventListener() {
                    @Override
                    public void onDevicePlugged(Device device) {
                        // Update device reference
                        RawStoryTellerAsyncDriver.this.device = device;
                        // Notify listeners
                        RawStoryTellerAsyncDriver.this.listeners.forEach(listener -> listener.onDevicePlugged(device));
                    }
                    @Override
                    public void onDeviceUnplugged(Device device) {
                        // Update device reference
                        RawStoryTellerAsyncDriver.this.device = null;
                        // Notify listeners
                        RawStoryTellerAsyncDriver.this.listeners.forEach(listener -> listener.onDeviceUnplugged(device));
                    }
                }
        );
    }


    public void registerDeviceListener(DeviceHotplugEventListener listener) {
        this.listeners.add(listener);
        if (this.device != null) {
            listener.onDevicePlugged(this.device);
        }
    }


    public CompletableFuture<RawDeviceInfos> getDeviceInfos() {
        if (this.device == null) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE, "No device plugged"));
        }

        return LibUsbMassStorageHelper.executeOnDeviceHandle(this.device, (handle) -> {
            return readDeviceInfos(handle);
        });
    }

    private CompletableFuture<RawDeviceInfos> readDeviceInfos(DeviceHandle handle) {
        // Read UUID and Serial Number from SPI
        return LibUsbMassStorageHelper.asyncReadSPISectors(handle, DEVICE_INFOS_SPI_OFFSET, (short)1)
                .thenCompose(spiDeviceInfosSector -> {
                    UUID uuid = null;
                    long uuidLowBytes = spiDeviceInfosSector.getLong(8);    // Read low 8 bytes
                    long uuidHighBytes = spiDeviceInfosSector.getLong(16);  // Read high 8 bytes
                    if ((uuidHighBytes != 0L || uuidLowBytes != 0L) && (uuidHighBytes != -1L || uuidLowBytes != -1L) && (uuidLowBytes != -4294967296L || uuidHighBytes != -4294967296L)) {
                        uuid = new UUID(uuidHighBytes, uuidLowBytes);
                        LOGGER.fine("UUID from SPI: " + uuid.toString());
                    } else {
                        LOGGER.warning("No UUID in SPI");
                    }
                    final UUID finalUuid = uuid;

                    // Read serial number from SPI
                    String serialNumber = null;
                    long sn = spiDeviceInfosSector.getLong(0);
                    if (sn != 0L && sn != -1L && sn != -4294967296L) {
                        serialNumber = String.format("%014d", sn);
                        LOGGER.fine("Serial Number: " + serialNumber);
                    } else {
                        LOGGER.warning("No serial number in SPI");
                    }
                    final String finalSerialNumber = serialNumber;

                    // TODO Otherwise, read UUID from SD ?
                    /*if (uuid == null) {
                        ByteBuffer sdSector0 = LibUsbUtils1.readSDSectors(handle, DEVICE_INFOS_SD_SECTOR_0, (short) 1);
                        uuidLowBytes = sdSector0.getLong(0);   // Read low 8 bytes
                        uuidHighBytes = sdSector0.getLong(8);  // Read high 8 bytes

                        if (uuidHighBytes != 0L || uuidLowBytes != 0L) {
                            uuid = new UUID(uuidHighBytes, uuidLowBytes);
                            LOGGER.fine("UUID from SD: " + uuid.toString());
                        } else {
                            LOGGER.warning("NO UUID IN SD");
                        }
                    }*/


                    // Read firmware version, card size and error from SD
                    return LibUsbMassStorageHelper.asyncReadSDSectors(handle, DEVICE_INFOS_SD_SECTOR_2, (short)1)
                            .thenCompose(sdDeviceInfosSector2 -> {
                                // Firmware version
                                short major = -1;
                                short minor = -1;
                                char[] version = new char[7];
                                version[0] = (char) sdDeviceInfosSector2.get(0);
                                version[1] = (char) sdDeviceInfosSector2.get(2);
                                version[2] = (char) sdDeviceInfosSector2.get(4);
                                version[3] = (char) sdDeviceInfosSector2.get(6);
                                version[4] = (char) sdDeviceInfosSector2.get(8);
                                version[5] = (char) sdDeviceInfosSector2.get(10);
                                version[6] = (char) sdDeviceInfosSector2.get(12);
                                if ("version".equals(new String(version))) {
                                    major = sdDeviceInfosSector2.get(16);
                                    minor = sdDeviceInfosSector2.get(20);
                                    LOGGER.fine("Firmware version: " + major + "." + minor);
                                } else {
                                    LOGGER.warning("No firmware version");
                                }
                                final short finalMajor = major;
                                final short finalMinor = minor;

                                // Read card size from SD, if needed (firmware >= 1.1)
                                // TODO Suspicious, left as inherited from upstream STUdio: byte 26 is
                                //  read twice (as the most and the least significant byte) and byte 25
                                //  is never read. A plain byte order would read 24..27, e.g.
                                //  27<<24 | 26<<16 | 25<<8 | 24 (little endian) or 24<<24 | 25<<16 |
                                //  26<<8 | 27 (big endian). Nothing in this repository (docs, tests,
                                //  any other parse of sector 2) documents this layout, so the value is
                                //  not changed without a dump of a real 1.x device to check it against.
                                //  It feeds the reported card size and findSuitableSectorAfter, i.e. the
                                //  check that a pack placed after the last one still fits on the card.
                                int sdCardSizeInSectors = -1;
                                if (major >= 1 && minor >= 1) {
                                    sdCardSizeInSectors = (sdDeviceInfosSector2.get(26) & 0xff) << 24
                                            | (sdDeviceInfosSector2.get(27) & 0xff) << 16
                                            | (sdDeviceInfosSector2.get(24) & 0xff) << 8
                                            | sdDeviceInfosSector2.get(26) & 0xff;
                                }
                                // Fix card size
                                if (sdCardSizeInSectors == -1) {
                                    sdCardSizeInSectors = SDCARD_DEFAULT_SIZE_IN_SECTORS;
                                } else {
                                    // Account for the 10.5 MB FAT16 partition
                                    sdCardSizeInSectors -= SDCARD_FAT16_PARTITION_SIZE_IN_SECTORS;
                                }
                                // Account for the 100000 reserved sectors
                                sdCardSizeInSectors -= PACK_INDEX_SD_SECTOR;
                                LOGGER.fine("SD card size: " + sdCardSizeInSectors);
                                final int finalSdCardSizeInSectors = sdCardSizeInSectors;

                                // Read error from SD
                                short errorCode = sdDeviceInfosSector2.getShort(0);
                                final boolean hasError = errorCode == 1;
                                LOGGER.fine("Error code: " + errorCode);

                                // Compute used SD card space from packs index
                                return readPackIndex(handle)
                                        .thenApply(packs -> {
                                            Integer usedSpaceInSectors = packs.stream()
                                                    .map(RawStoryPackInfos::getSizeInSectors)
                                                    .reduce(0, Integer::sum);
                                            return new RawDeviceInfos(finalUuid, finalMajor, finalMinor, finalSerialNumber, finalSdCardSizeInSectors, usedSpaceInSectors, hasError);
                                        });
                            });
                });
    }


    public CompletableFuture<List<RawStoryPackInfos>> getPacksList() {
        if (this.device == null) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE, "No device plugged"));
        }

        return LibUsbMassStorageHelper.executeOnDeviceHandle(this.device, (handle) -> {
            // Read pack index
            return readPackIndex(handle);
        });
    }

    private CompletableFuture<List<RawStoryPackInfos>> readPackIndex(DeviceHandle handle) {
        return LibUsbMassStorageHelper.asyncReadSDSectors(handle, PACK_INDEX_SD_SECTOR, (short) 1)
                .thenCompose(sdPackIndexSector -> {
                    sdPackIndexSector.position(0);
                    short nbPacks = sdPackIndexSector.getShort();
                    LOGGER.fine("Number of packs in index: " + nbPacks);

                    CompletableFuture<List<RawStoryPackInfos>> promise = CompletableFuture.completedFuture(new ArrayList<>());
                    for (short i = 0; i < nbPacks; i++) {
                        int startSector = sdPackIndexSector.getInt();
                        int sizeInSectors = sdPackIndexSector.getInt();
                        short statsOffset = sdPackIndexSector.getShort();
                        short samplingRate = sdPackIndexSector.getShort();
                        LOGGER.fine("Pack #" + (i + 1) + ": " + startSector + " - " + sizeInSectors);
                        // Read version from pack's sector 0 and UUID from pack's sector 1
                        promise = promise.thenCompose(packs ->
                                LibUsbMassStorageHelper.asyncReadSDSectors(handle, PACK_INDEX_SD_SECTOR + startSector, (short) 2)
                                        .thenApply(sdPackSectors -> {
                                            short version = sdPackSectors.getShort(3);
                                            if (version == 0) {
                                                version = 1;
                                            }
                                            LOGGER.fine("Pack version: " + version);
                                            long uuidHighBytes = sdPackSectors.getLong(LibUsbMassStorageHelper.SECTOR_SIZE);
                                            long uuidLowBytes = sdPackSectors.getLong(LibUsbMassStorageHelper.SECTOR_SIZE + 8);
                                            UUID uuid = new UUID(uuidHighBytes, uuidLowBytes);
                                            LOGGER.fine("Pack UUID: " + uuid.toString());
                                            RawStoryPackInfos storyPackInfos = new RawStoryPackInfos(uuid, version, startSector, sizeInSectors, statsOffset, samplingRate);
                                            packs.add(storyPackInfos);
                                            return packs;
                                        })
                        );
                    }
                    return promise;
                });
    }


    public CompletableFuture<Boolean> reorderPacks(List<String> uuids) {
        if (this.device == null) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE, "No device plugged"));
        }

        return LibUsbMassStorageHelper.executeOnDeviceHandle(this.device, (handle) -> {
            return readPackIndex(handle)
                    .thenCompose(packs -> {
                        // Look for UUIDs in packs index (ALL uuids must match)
                        boolean allUUIDsAreOnDevice = uuids.stream().allMatch(uuid -> packs.stream().anyMatch(p -> p.getUuid().equals(UUID.fromString(uuid))));
                        if (allUUIDsAreOnDevice) {
                            // Reorder list according to uuids list
                            packs.sort(Comparator.comparingInt(p -> uuids.indexOf(p.getUuid().toString())));
                            // Write pack index
                            return writePackIndex(handle, packs);
                        } else {
                            throw new StoryTellerException("Packs on device do not match UUIDs");
                        }
                    });
        });
    }

    public CompletableFuture<Boolean> deletePack(String uuid) {
        if (this.device == null) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE, "No device plugged"));
        }

        return LibUsbMassStorageHelper.executeOnDeviceHandle(this.device, (handle) -> {
            return readPackIndex(handle)
                    .thenCompose(packs -> {
                        // Look for UUID in packs index
                        Optional<RawStoryPackInfos> matched = packs.stream().filter(p -> p.getUuid().equals(UUID.fromString(uuid))).findFirst();
                        if (matched.isPresent()) {
                            LOGGER.fine("Found pack with uuid: " + uuid);
                            LOGGER.fine("Matched: " + matched.get().getStartSector() + " - " + matched.get().getSizeInSectors());
                            // Remove from index
                            packs.remove(matched.get());
                            // Write pack index
                            return writePackIndex(handle, packs);
                        } else {
                            throw new StoryTellerException(StoryTellerException.Reason.PACK_NOT_ON_DEVICE, "Pack not found");
                        }
                    });
        });
    }

    private CompletableFuture<Boolean> writePackIndex(DeviceHandle handle, List<RawStoryPackInfos> packs) {
        // Compute packs index bytes
        ByteBuffer bb = ByteBuffer.allocateDirect(LibUsbMassStorageHelper.SECTOR_SIZE);
        bb.putShort((short) packs.size());
        for (RawStoryPackInfos pack: packs) {
            bb.putInt(pack.getStartSector());
            bb.putInt(pack.getSizeInSectors());
            bb.putShort(pack.getStatsOffset());
            bb.putShort(pack.getSamplingRate());
        }

        return LibUsbMassStorageHelper.asyncWriteSDSectors(handle, PACK_INDEX_SD_SECTOR, (short) 1, bb);
    }


    public CompletableFuture<TransferStatus> downloadPack(String uuid, OutputStream output, TransferProgressListener listener) {
        if (this.device == null) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE, "No device plugged"));
        }

        return LibUsbMassStorageHelper.executeOnDeviceHandle(device, (handle) ->
            readPackIndex(handle)
                    .thenCompose(packs -> {
                        // Look for UUID in packs index
                        Optional<RawStoryPackInfos> matched = packs.stream().filter(p -> p.getUuid().equals(UUID.fromString(uuid))).findFirst();
                        if (matched.isPresent()) {
                            LOGGER.fine("Found pack with uuid: " + uuid);
                            LOGGER.fine("Matched: " + matched.get().getStartSector() + " - " + matched.get().getSizeInSectors());
                            // Keep track of transferred bytes and elapsed time
                            final long startTime = System.currentTimeMillis();
                            // Copy pack chunk by chunk into the output stream
                            int totalSize = matched.get().getSizeInSectors() * LibUsbMassStorageHelper.SECTOR_SIZE;
                            CompletableFuture<TransferStatus> promise = CompletableFuture.completedFuture(new TransferStatus(false, 0, totalSize, 0.0));
                            for(int offset = 0; offset < matched.get().getSizeInSectors(); offset += PACK_TRANSFER_CHUNK_SIZE_IN_SECTORS) {
                                int sector = PACK_INDEX_SD_SECTOR + matched.get().getStartSector() + offset;
                                short nbSectorsToRead = (short) Math.min(PACK_TRANSFER_CHUNK_SIZE_IN_SECTORS, matched.get().getSizeInSectors() - offset);
                                promise = promise.thenCompose(status -> {
                                    LOGGER.finer("Reading " + (nbSectorsToRead * LibUsbMassStorageHelper.SECTOR_SIZE) + " bytes from device");
                                    return LibUsbMassStorageHelper.asyncReadSDSectors(handle, sector, nbSectorsToRead)
                                            .thenApply(read -> {
                                                // TODO Write directly from ByteBuffer to output stream ?
                                                byte[] bytes = new byte[read.remaining()];
                                                read.get(bytes);
                                                try {
                                                    LOGGER.finer("Writing " + bytes.length + " bytes to output stream");
                                                    output.write(bytes);
                                                    // Compute progress
                                                    status.setTransferred(status.getTransferred() + bytes.length);
                                                    long elapsed = System.currentTimeMillis() - startTime;
                                                    double speed = ((double) status.getTransferred()) / ((double) elapsed / 1000.0);
                                                    status.setSpeed(speed);
                                                    LOGGER.finer("Transferred " + status.getTransferred() + " bytes in " + elapsed + " ms");
                                                    LOGGER.finer("Average speed = " + speed + " bytes/sec");
                                                    if (status.getTransferred() == totalSize) {
                                                        status.setDone(true);
                                                    }
                                                    // Call (optional) listener with transfer status
                                                    if (listener != null) {
                                                        CompletableFuture.runAsync(() -> listener.onProgress(status));
                                                        if (status.isDone()) {
                                                            CompletableFuture.runAsync(() -> listener.onComplete(status));
                                                        }
                                                    }
                                                    return status;
                                                } catch (IOException e) {
                                                    throw new StoryTellerException("Failed to write pack to destination file", e);
                                                }
                                            });
                                        });
                            }
                            return promise;
                        } else {
                            throw new StoryTellerException(StoryTellerException.Reason.PACK_NOT_ON_DEVICE, "Pack not found");
                        }
                    })
                );
    }


    public CompletableFuture<TransferStatus> uploadPack(InputStream input, int packSizeInSectors, TransferProgressListener listener) {
        if (this.device == null) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE, "No device plugged"));
        }

        return LibUsbMassStorageHelper.executeOnDeviceHandle(this.device, (handle) -> {
            // Find first large-enough free space
            return findFirstSuitableSector(handle, packSizeInSectors)
                    .thenCompose(startSector -> {
                        if (startSector.isEmpty()) {
                            throw new StoryTellerException(StoryTellerException.Reason.NOT_ENOUGH_SPACE, "Not enough free space on the device");
                        }
                        LOGGER.fine("Adding pack at start sector: " + startSector.get());

                        // Keep track of transferred bytes and elapsed time
                        final long startTime = System.currentTimeMillis();
                        // Copy pack chunk by chunk from the input stream
                        int totalSize = packSizeInSectors * LibUsbMassStorageHelper.SECTOR_SIZE;
                        CompletableFuture<TransferStatus> promise = CompletableFuture.completedFuture(new TransferStatus(false, 0, totalSize, 0.0));
                        for(int offset = 0; offset < packSizeInSectors; offset += PACK_TRANSFER_CHUNK_SIZE_IN_SECTORS) {
                            int sector = PACK_INDEX_SD_SECTOR + startSector.get() + offset;
                            short nbSectorsToWrite = (short) Math.min(PACK_TRANSFER_CHUNK_SIZE_IN_SECTORS, packSizeInSectors - offset);
                            promise = promise.thenCompose(status -> {
                                // TODO Write directly from input stream to ByteBuffer ?
                                int chunkSize = nbSectorsToWrite * LibUsbMassStorageHelper.SECTOR_SIZE;
                                ByteBuffer bb = ByteBuffer.allocateDirect(chunkSize);
                                try {
                                    // Read next chunk from input stream
                                    LOGGER.finer("Reading " + chunkSize + " bytes from input stream");
                                    // readNBytes loops until chunkSize bytes or end of stream; a short
                                    // array means the stream ended before the size announced by the
                                    // caller. Refuse it: writing a partial chunk would leave sectors
                                    // of stale data inside the pack, and bb.put(chunk, 0, chunkSize)
                                    // used to fail here with an unexplained IndexOutOfBoundsException.
                                    // The index is not touched, so the sectors already written stay free.
                                    byte[] chunk = input.readNBytes(chunkSize);
                                    if (chunk.length < chunkSize) {
                                        throw new StoryTellerException("The pack stream ended early: expected "
                                                + totalSize + " bytes (" + packSizeInSectors + " sectors), got "
                                                + ((long) (sector - PACK_INDEX_SD_SECTOR - startSector.get()) * LibUsbMassStorageHelper.SECTOR_SIZE + chunk.length)
                                                + ". The pack index was not updated.");
                                    }
                                    bb.put(chunk, 0, chunkSize);
                                    LOGGER.finer("Writing " + chunkSize + " bytes to device");
                                    return LibUsbMassStorageHelper.asyncWriteSDSectors(handle, sector, nbSectorsToWrite, bb)
                                            .thenApply(written -> {
                                                // Compute progress
                                                status.setTransferred(status.getTransferred() + chunkSize);
                                                long elapsed = System.currentTimeMillis() - startTime;
                                                double speed = ((double) status.getTransferred()) / ((double) elapsed / 1000.0);
                                                status.setSpeed(speed);
                                                LOGGER.finer("Transferred " + status.getTransferred() + " bytes in " + elapsed + " ms");
                                                LOGGER.finer("Average speed = " + speed + " bytes/sec");
                                                if (status.getTransferred() == totalSize) {
                                                    status.setDone(true);
                                                }
                                                // Call (optional) listener with transfer status
                                                if (listener != null) {
                                                    CompletableFuture.runAsync(() -> listener.onProgress(status));
                                                }
                                                return status;
                                            });
                                } catch (IOException e) {
                                    throw new StoryTellerException("Failed to read pack from file", e);
                                }
                            });
                        }

                        // Rewrite packs index with added pack
                        return promise
                                .thenCompose(status -> readPackIndex(handle)
                                        .thenCompose(packs -> {
                                            // Add pack to index list
                                            packs.add(new RawStoryPackInfos(
                                                    null,   // Not used to write packs index
                                                    (short) 0,  // Not used to write packs index
                                                    startSector.get(),
                                                    packSizeInSectors,
                                                    (short) 0,
                                                    (short) 0
                                            ));
                                            // Write pack index
                                            return writePackIndex(handle, packs)
                                                    .thenApply(done -> {
                                                        if (status.isDone()) {
                                                            // Call listener only after the index has been rewritten
                                                            CompletableFuture.runAsync(() -> listener.onComplete(status));
                                                        }
                                                        return status;
                                                    });
                                        }));
                    });
        });
    }

    private CompletableFuture<Optional<Integer>> findFirstSuitableSector(DeviceHandle handle, int packSizeInSectors) {
        return readPackIndex(handle)
                .thenCompose(packs -> {
                    // Look for a large enough free space between packs first: the card size is only
                    // read when none is found, exactly as before.
                    Optional<Integer> betweenPacks = findSuitableSectorBetweenPacks(packs, packSizeInSectors);
                    if (betweenPacks.isPresent()) {
                        return CompletableFuture.completedFuture(betweenPacks);
                    }
                    // Check if there is enough space after the last pack
                    final int lastUsedSector = lastUsedSector(packs);
                    return readDeviceInfos(handle)
                            .thenApply(infos -> findSuitableSectorAfter(lastUsedSector, infos.getSdCardSizeInSectors(), packSizeInSectors));
                });
    }

    /*
     * Sector arithmetic for pack placement. Sectors are relative to PACK_INDEX_SD_SECTOR: sector 0
     * holds the pack index, and a pack occupies [startSector, startSector + sizeInSectors - 1].
     *
     * A pack is placed right after the last sector in use, at lastUsed + 1. Between a last used
     * sector L and the next used sector N, the free sectors are L+1 .. N-1, i.e. N - L - 1 of them.
     * The previous code counted N - L, one too many: a pack exactly one sector too large for the gap
     * was accepted and its last sector overwrote sector 0 of the next pack. Same off-by-one before
     * the first pack (L = 0, the index sector) and at the end of the card.
     */

    /** Visible for tests: same answer as the device search, from an index and a card size. */
    static Optional<Integer> findFirstSuitableSector(List<RawStoryPackInfos> packs, int sdCardSizeInSectors, int packSizeInSectors) {
        Optional<Integer> betweenPacks = findSuitableSectorBetweenPacks(packs, packSizeInSectors);
        if (betweenPacks.isPresent()) {
            return betweenPacks;
        }
        return findSuitableSectorAfter(lastUsedSector(packs), sdCardSizeInSectors, packSizeInSectors);
    }

    static Optional<Integer> findSuitableSectorBetweenPacks(List<RawStoryPackInfos> packs, int packSizeInSectors) {
        // Order packs by their start sector (on a copy: the caller's list is left as it was given)
        List<RawStoryPackInfos> sorted = new ArrayList<>(packs);
        sorted.sort(Comparator.comparingInt(RawStoryPackInfos::getStartSector));
        int previousUsedSector = 0;
        for (RawStoryPackInfos pack : sorted) {
            int nextUsedSector = pack.getStartSector();
            int freeSpace = nextUsedSector - previousUsedSector - 1;
            if (freeSpace >= packSizeInSectors) {
                return Optional.of(previousUsedSector + 1);
            }
            previousUsedSector = Math.max(previousUsedSector, pack.getStartSector() + pack.getSizeInSectors() - 1);
        }
        return Optional.empty();
    }

    static int lastUsedSector(List<RawStoryPackInfos> packs) {
        int lastUsedSector = 0;
        for (RawStoryPackInfos pack : packs) {
            lastUsedSector = Math.max(lastUsedSector, pack.getStartSector() + pack.getSizeInSectors() - 1);
        }
        return lastUsedSector;
    }

    /**
     * {@code sdCardSizeInSectors} is the number of sectors from the index sector onwards, so the last
     * usable relative sector is {@code sdCardSizeInSectors - 1}. The previous code treated
     * {@code sdCardSizeInSectors + 1} as the first unavailable sector and counted one more on top,
     * accepting packs up to two sectors past that bound.
     */
    static Optional<Integer> findSuitableSectorAfter(int lastUsedSector, int sdCardSizeInSectors, int packSizeInSectors) {
        int firstUnavailableSector = sdCardSizeInSectors;
        int freeSpace = firstUnavailableSector - lastUsedSector - 1;
        if (freeSpace >= packSizeInSectors) {
            return Optional.of(lastUsedSector + 1);
        }
        // There are no large-enough free space
        return Optional.empty();
    }

    public CompletableFuture<Void> dump(String outputPath) {
        if (this.device == null) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE, "No device plugged"));
        }

        return LibUsbMassStorageHelper.executeOnDeviceHandle(this.device, (handle) -> {
            return dumpSector(handle, DEVICE_INFOS_SD_SECTOR_0, outputPath)
                    .thenCompose(__ -> dumpSector(handle, DEVICE_INFOS_SD_SECTOR_2, outputPath))
                    .thenCompose(__ -> dumpSector(handle, PACK_INDEX_SD_SECTOR, outputPath))
                    .thenCompose(__ -> LibUsbMassStorageHelper.asyncReadSDSectors(handle, PACK_INDEX_SD_SECTOR, (short) 1)
                                .thenCompose(sdPackIndexSector -> {
                                    sdPackIndexSector.position(0);
                                    short nbPacks = sdPackIndexSector.getShort();
                                    LOGGER.info("Number of packs to dump: " + nbPacks);

                                    CompletableFuture<Void> promise = CompletableFuture.completedFuture(null);
                                    for (short i = 0; i < nbPacks; i++) {
                                        int startSector = sdPackIndexSector.getInt();
                                        int sizeInSectors = sdPackIndexSector.getInt();
                                        short statsOffset = sdPackIndexSector.getShort();
                                        short samplingRate = sdPackIndexSector.getShort();
                                        LOGGER.fine("Pack #" + (i + 1) + ": " + startSector + " - " + sizeInSectors);
                                        // Dump first, second and last sector of each pack
                                        promise = promise
                                                .thenCompose(___ -> dumpSector(handle, PACK_INDEX_SD_SECTOR + startSector, outputPath))
                                                .thenCompose(___ -> dumpSector(handle, PACK_INDEX_SD_SECTOR + startSector + 1, outputPath))
                                                .thenCompose(___ -> dumpSector(handle, PACK_INDEX_SD_SECTOR + startSector + sizeInSectors - 1, outputPath));
                                    }
                                    return promise;
                                })
                    );
        });
    }

    private CompletableFuture<Void> dumpSector(DeviceHandle handle, int sector, String outputPath) {
        String dest = outputPath + File.separator + "sector" + sector + ".bin";
        LOGGER.info("Dumping sector " + sector + " into " + dest);
        return LibUsbMassStorageHelper.asyncReadSDSectors(handle, sector, (short) 1)
                .thenAccept(read -> {
                    // try-with-resources: a write failure must not leave the dump file locked on Windows
                    try (FileOutputStream sectorOutputStream = new FileOutputStream(dest)) {
                        writeByteBufferToStream(read, sectorOutputStream);
                    } catch (IOException e) {
                        throw new StoryTellerException("Failed to dump sector " + sector + " from SD card.", e);
                    }
                });
    }

    private void writeByteBufferToStream(ByteBuffer bb, OutputStream output) throws IOException {
        // TODO Write directly from ByteBuffer to output stream ?
        byte[] bytes = new byte[bb.remaining()];
        bb.get(bytes);
        output.write(bytes);
    }
}
