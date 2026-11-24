/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.codec.binary.Hex;
import org.usb4java.Device;
import studio.core.v1.Constants;
import studio.driver.model.fs.FsStoryPackInfos;
import studio.driver.model.raw.RawStoryPackInfos;
import studio.driver.raw.LibUsbMassStorageHelper;
import studio.driver.raw.RawStoryTellerAsyncDriver;
import studio.driver.StoryTellerException;
import studio.driver.event.DeviceHotplugEventListener;
import studio.driver.event.TransferProgressListener;
import studio.driver.fs.FsStoryTellerAsyncDriver;
import studio.driver.model.TransferStatus;
import studio.metadata.DatabaseMetadataService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class StoryTellerService implements IStoryTellerService {

    private final Logger LOGGER = LoggerFactory.getLogger(StoryTellerService.class);

    private final EventBus eventBus;

    private final DatabaseMetadataService databaseMetadataService;

    private RawStoryTellerAsyncDriver driver;
    private FsStoryTellerAsyncDriver fsDriver;
    private Device device;
    private Device fsDevice;

    private final DeviceOperationQueue queue = new DeviceOperationQueue();
    private final TransferNotifier notifier;


    public StoryTellerService(EventBus eventBus, DatabaseMetadataService databaseMetadataService) {
        this.eventBus = eventBus;
        this.databaseMetadataService = databaseMetadataService;
        this.notifier = new TransferNotifier(eventBus, new TransferRegistry());

        LOGGER.info("Setting up story teller driver");
        driver = new RawStoryTellerAsyncDriver();
        fsDriver = new FsStoryTellerAsyncDriver();

        // React when a device with firmware 1.x is plugged or unplugged
        driver.registerDeviceListener(new DeviceHotplugEventListener() {
            @Override
            public void onDevicePlugged(Device device) {
                if (device == null) {
                    LOGGER.error("Device 1.x plugged but got null device");
                    // Send 'failure' event on bus
                    eventBus.send("storyteller.failure", null);
                } else {
                    LOGGER.info("Device 1.x plugged");
                    StoryTellerService.this.device = device;
                    CompletableFuture.runAsync(() -> driver.getDeviceInfos()
                            .handle((infos, e) -> {
                                if (e != null) {
                                    LOGGER.error("Failed to plug device 1.x", e);
                                    // Send 'failure' event on bus
                                    eventBus.send("storyteller.failure", null);
                                } else {
                                    JsonObject eventData = new JsonObject()
                                            .put("uuid", infos.getUuid().toString())
                                            .put("serial", infos.getSerialNumber())
                                            .put("firmware", infos.getFirmwareMajor() == -1 ? null : infos.getFirmwareMajor() + "." + infos.getFirmwareMinor())
                                            .put("storage", new JsonObject()
                                                    .put("size", infos.getSdCardSizeInSectors() * (long) LibUsbMassStorageHelper.SECTOR_SIZE)
                                                    .put("free", (infos.getSdCardSizeInSectors() - infos.getUsedSpaceInSectors()) * (long) LibUsbMassStorageHelper.SECTOR_SIZE)
                                                    .put("taken", infos.getUsedSpaceInSectors() * (long) LibUsbMassStorageHelper.SECTOR_SIZE)
                                            )
                                            .put("error", infos.isInError())
                                            .put("driver", "raw");
                                    // Send 'plugged' event on bus
                                    eventBus.send("storyteller.plugged", eventData);
                                }
                                return null;
                            })
                    );
                }
            }

            @Override
            public void onDeviceUnplugged(Device device) {
                LOGGER.info("Device 1.x unplugged");
                StoryTellerService.this.device = null;
                // Send 'unplugged' event on bus
                eventBus.send("storyteller.unplugged", null);
            }
        });

        // React when a device with firmware 2.x is plugged or unplugged
        fsDriver.registerDeviceListener(new DeviceHotplugEventListener() {
            @Override
            public void onDevicePlugged(Device device) {
                if (device == null) {
                    LOGGER.error("Device 2.x plugged but got null device");
                    // Send 'failure' event on bus
                    eventBus.send("storyteller.failure", null);
                } else {
                    LOGGER.info("Device 2.x plugged");
                    StoryTellerService.this.fsDevice = device;
                    CompletableFuture.runAsync(() -> fsDriver.getDeviceInfos()
                            .handle((infos, e) -> {
                                if (e != null) {
                                    LOGGER.error("Failed to plug device 2.x", e);
                                    // Send 'failure' event on bus
                                    eventBus.send("storyteller.failure", null);
                                } else {
                                    JsonObject eventData = new JsonObject()
                                            .put("uuid", Hex.encodeHexString(infos.getUuid()))
                                            .put("serial", infos.getSerialNumber())
                                            .put("firmware", infos.getFirmwareMajor() + "." + infos.getFirmwareMinor())
                                            .put("storage", new JsonObject()
                                                    .put("size", infos.getSdCardSizeInBytes())
                                                    .put("free", infos.getSdCardSizeInBytes() - infos.getUsedSpaceInBytes())
                                                    .put("taken", infos.getUsedSpaceInBytes())
                                            )
                                            .put("error", false)
                                            .put("driver", "fs");
                                    // Send 'plugged' event on bus
                                    eventBus.send("storyteller.plugged", eventData);
                                }
                                return null;
                            })
                    );
                }
            }

            @Override
            public void onDeviceUnplugged(Device device) {
                LOGGER.info("Device 2.x unplugged");
                StoryTellerService.this.fsDevice = null;
                // Send 'unplugged' event on bus
                eventBus.send("storyteller.unplugged", null);
            }
        });
    }

    public CompletableFuture<Optional<JsonObject>> deviceInfos() {
        if (device != null) {
            return deviceInfosV1();
        } else if (fsDevice != null) {
            return deviceInfosV2();
        } else {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }
    private CompletableFuture<Optional<JsonObject>> deviceInfosV1() {
        return driver.getDeviceInfos()
                .thenApply(infos -> Optional.of(
                        new JsonObject()
                                .put("uuid", infos.getUuid().toString())
                                .put("serial", infos.getSerialNumber())
                                .put("firmware", infos.getFirmwareMajor() == -1 ? null : infos.getFirmwareMajor() + "." + infos.getFirmwareMinor())
                                .put("storage", new JsonObject()
                                        .put("size", infos.getSdCardSizeInSectors() * (long) LibUsbMassStorageHelper.SECTOR_SIZE)
                                        .put("free", (infos.getSdCardSizeInSectors() - infos.getUsedSpaceInSectors()) * (long) LibUsbMassStorageHelper.SECTOR_SIZE)
                                        .put("taken", infos.getUsedSpaceInSectors() * (long) LibUsbMassStorageHelper.SECTOR_SIZE)
                                )
                                .put("error", infos.isInError())
                                .put("driver", "raw")
                        )
                );
    }
    private CompletableFuture<Optional<JsonObject>> deviceInfosV2() {
        return fsDriver.getDeviceInfos()
                .thenApply(infos -> Optional.of(
                        new JsonObject()
                                .put("uuid", Hex.encodeHexString(infos.getUuid()))
                                .put("serial", infos.getSerialNumber())
                                .put("firmware", infos.getFirmwareMajor() + "." + infos.getFirmwareMinor())
                                .put("storage", new JsonObject()
                                        .put("size", infos.getSdCardSizeInBytes())
                                        .put("free", infos.getSdCardSizeInBytes() - infos.getUsedSpaceInBytes())
                                        .put("taken", infos.getUsedSpaceInBytes())
                                )
                                .put("error", false)
                                .put("driver", "fs")
                        )
                );
    }

    public CompletableFuture<JsonArray> packs() {
        if (device != null) {
            return packsV1();
        } else if (fsDevice != null) {
            return packsV2();
        } else {
            return CompletableFuture.completedFuture(new JsonArray());
        }
    }
    private CompletableFuture<JsonArray> packsV1() {
        return driver.getPacksList()
                .thenApply(packs ->
                        new JsonArray(
                                packs.stream()
                                        .map(this::getRawPackMetadata)
                                        .collect(Collectors.toList())
                        )
                );
    }
    private CompletableFuture<JsonArray> packsV2() {
        return fsDriver.getPacksList()
                .thenApply(packs ->
                        new JsonArray(
                                packs.stream()
                                        .map(this::getFsPackMetadata)
                                        .collect(Collectors.toList())
                        )
                );
    }

    public TransferRegistry transfers() {
        return notifier.registry();
    }

    /**
     * Queues a pack for upload and answers with its transfer id.
     *
     * <p>What can be known before the transfer is checked here and refused with a typed failure:
     * no device, a library file that is not there, a pack already on the device. The upload itself
     * runs in the device queue, after every operation submitted before it, and checks again when its
     * turn comes, since the device may have changed by then. Its outcome is reported on the event bus
     * and kept in the {@link TransferRegistry}.
     */
    public CompletableFuture<Optional<String>> addPack(String uuid, File packFile) {
        try {
            UUID.fromString(uuid);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Invalid pack UUID: " + uuid, e));
        }
        if (device == null && fsDevice == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        if (packFile == null || !packFile.exists()) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.LIBRARY_PACK_NOT_FOUND,
                    "Pack not found in the library: " + (packFile == null ? null : packFile.getName())));
        }
        // On a 2.x+ device the index is a file, safe to read while a queued upload is running (it is
        // replaced atomically). A 1.x device is read over USB, which must not overlap a transfer, so
        // there the check waits its turn in the queue like any other device access.
        CompletableFuture<Boolean> precheck = fsDevice != null
                ? isPackOnDevice(uuid)
                : queue.submit(() -> isPackOnDevice(uuid));
        return precheck
                .thenApply(present -> {
                    if (present) {
                        LOGGER.error("Cannot add pack to device because the pack already exists on the device");
                        throw alreadyOnDevice(uuid);
                    }
                    String transferId = notifier.create(uuid, "add");
                    LOGGER.info("Transfer " + transferId + " queued (" + (queue.pending()) + " device operation(s) ahead)");
                    queue.submit(() -> runAddPack(transferId, uuid, packFile));
                    return Optional.of(transferId);
                });
    }

    private CompletableFuture<Void> runAddPack(String transferId, String uuid, File packFile) {
        notifier.started(transferId);
        CompletableFuture<Void> outcome = isPackOnDevice(uuid)
                .thenCompose(present -> {
                    if (present) {
                        throw alreadyOnDevice(uuid);
                    }
                    if (device != null) {
                        return uploadPackV1(transferId, packFile);
                    } else if (fsDevice != null) {
                        LOGGER.info("Transferring pack folder to device: " + packFile.getAbsolutePath());
                        return fsDriver.uploadPack(uuid, packFile.getAbsolutePath(), notifier.listener(transferId));
                    } else {
                        throw noDevice();
                    }
                })
                .thenApply(status -> (Void) null);
        return outcome.whenComplete((ignored, t) -> {
            if (t != null) {
                LOGGER.error("Failed to add pack to device", t);
                notifier.failed(transferId, t);
            } else {
                LOGGER.info("Pack added.");
                notifier.succeeded(transferId);
            }
        });
    }

    private CompletableFuture<TransferStatus> uploadPackV1(String transferId, File packFile) {
        FileInputStream fis;
        try {
            fis = new FileInputStream(packFile);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new StoryTellerException("Failed to open pack file", e));
        }
        LOGGER.info("Transferring pack to device: " + packFile.length() + " bytes");
        int fileSizeInSectors = (int) (packFile.length() / LibUsbMassStorageHelper.SECTOR_SIZE);
        CompletableFuture<TransferStatus> upload;
        try {
            upload = driver.uploadPack(fis, fileSizeInSectors, notifier.listener(transferId));
        } catch (RuntimeException e) {
            upload = CompletableFuture.failedFuture(e);
        }
        return upload.whenComplete((status, t) -> {
            try {
                fis.close();
            } catch (IOException e) {
                LOGGER.error("Failed to close source file.", e);
            }
        });
    }

    private CompletableFuture<Boolean> isPackOnDevice(String uuid) {
        UUID wanted = UUID.fromString(uuid);
        if (device != null) {
            return driver.getPacksList().thenApply(packs -> packs.stream().anyMatch(p -> p.getUuid().equals(wanted)));
        } else if (fsDevice != null) {
            return fsDriver.getPacksList().thenApply(packs -> packs.stream().anyMatch(p -> p.getUuid().equals(wanted)));
        } else {
            return CompletableFuture.failedFuture(noDevice());
        }
    }

    private static StoryTellerException noDevice() {
        return new StoryTellerException(StoryTellerException.Reason.NO_DEVICE, "No device plugged");
    }

    private static StoryTellerException alreadyOnDevice(String uuid) {
        return new StoryTellerException(StoryTellerException.Reason.PACK_ALREADY_ON_DEVICE, "The pack " + uuid + " is already on the device");
    }

    public CompletableFuture<Boolean> deletePack(String uuid) {
        return queue.submit(() -> {
            if (device != null) {
                return driver.deletePack(uuid);
            } else if (fsDevice != null) {
                return fsDriver.deletePack(uuid);
            } else {
                return CompletableFuture.failedFuture(noDevice());
            }
        });
    }

    public CompletableFuture<Boolean> reorderPacks(List<String> uuids) {
        return queue.submit(() -> {
            if (device != null) {
                return driver.reorderPacks(uuids);
            } else if (fsDevice != null) {
                return fsDriver.reorderPacks(uuids);
            } else {
                return CompletableFuture.failedFuture(noDevice());
            }
        });
    }

    /**
     * Pack content folders the index does not reference. They are listed in the device queue, so an
     * upload that has created its folder but not yet indexed it is never reported as one.
     */
    public CompletableFuture<JsonArray> orphanFolders() {
        return queue.submit(() -> {
            if (fsDevice != null) {
                return fsDriver.getUnreferencedPackContentFolders().thenApply(JsonArray::new);
            } else if (device != null) {
                // A 1.x device stores packs in raw sectors: there are no folders to be left behind.
                return CompletableFuture.completedFuture(new JsonArray());
            } else {
                return CompletableFuture.failedFuture(noDevice());
            }
        });
    }

    /**
     * Removes the named unreferenced folders, one after the other, and reports each outcome. A
     * folder that is referenced by the index, or that is not there, is left alone and reported.
     */
    public CompletableFuture<JsonArray> removeOrphanFolders(List<String> folderNames) {
        return queue.submit(() -> {
            if (fsDevice == null) {
                return CompletableFuture.failedFuture(device != null
                        ? new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Only devices with firmware 2.x and later have pack folders")
                        : noDevice());
            }
            JsonArray results = new JsonArray();
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (String name : folderNames) {
                chain = chain.thenCompose(ignored -> fsDriver.removeUnreferencedPackContentFolder(name)
                        .handle((removed, t) -> {
                            JsonObject r = new JsonObject().put("folder", name).put("removed", t == null && Boolean.TRUE.equals(removed));
                            if (t != null) {
                                r.mergeIn(ApiErrors.body(t)).remove("success");
                            }
                            results.add(r);
                            return null;
                        }));
            }
            return chain.thenApply(ignored -> results);
        });
    }

    public CompletableFuture<Optional<String>> extractPack(String uuid, File packFile) {
        if (device == null && fsDevice == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        if (device != null ? packFile.exists() : new File(packFile, uuid).exists()) {
            LOGGER.error("Cannot extract pack from device because the destination file already exists");
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String transferId = notifier.create(uuid, "extract");
        queue.submit(() -> {
            notifier.started(transferId);
            CompletableFuture<TransferStatus> extraction;
            try {
                if (device != null) {
                    extraction = extractPackV1(transferId, uuid, packFile);
                } else if (fsDevice != null) {
                    extraction = fsDriver.downloadPack(uuid, packFile.getAbsolutePath(), notifier.listener(transferId));
                } else {
                    extraction = CompletableFuture.failedFuture(noDevice());
                }
            } catch (RuntimeException e) {
                extraction = CompletableFuture.failedFuture(e);
            }
            return extraction.whenComplete((status, t) -> {
                if (t != null) {
                    LOGGER.error("Failed to extract pack from device", t);
                    notifier.failed(transferId, t);
                } else {
                    LOGGER.info("Pack extracted.");
                    notifier.succeeded(transferId);
                }
            });
        });
        return CompletableFuture.completedFuture(Optional.of(transferId));
    }

    private CompletableFuture<TransferStatus> extractPackV1(String transferId, String uuid, File destFile) {
        FileOutputStream fos;
        try {
            fos = new FileOutputStream(destFile);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new StoryTellerException("Failed to open destination file", e));
        }
        CompletableFuture<TransferStatus> download;
        try {
            download = driver.downloadPack(uuid, fos, notifier.listener(transferId));
        } catch (RuntimeException e) {
            download = CompletableFuture.failedFuture(e);
        }
        return download.whenComplete((status, t) -> {
            try {
                fos.close();
            } catch (IOException e) {
                LOGGER.error("Failed to close destination file.", e);
            }
        });
    }

    public CompletableFuture<Void> dump(String outputPath) {
        if (this.device == null) {
            return CompletableFuture.completedFuture(null);
        } else {
            new File(outputPath).mkdirs();
            return driver.dump(outputPath);
        }
    }

    private JsonObject getRawPackMetadata(RawStoryPackInfos pack) {
        return databaseMetadataService.getPackMetadata(pack.getUuid().toString())
                .map(metadata -> new JsonObject()
                        .put("uuid", pack.getUuid().toString())
                        .put("format", Constants.PACK_FORMAT_RAW)
                        .put("version", pack.getVersion())
                        .put("title", metadata.getTitle())
                        .put("description", metadata.getDescription())
                        .put("image", metadata.getThumbnail())
                        .put("sectorSize", pack.getSizeInSectors())
                        .put("official", metadata.isOfficial())
                )
                .orElse(new JsonObject()
                        .put("uuid", pack.getUuid().toString())
                        .put("format", Constants.PACK_FORMAT_RAW)
                        .put("version", pack.getVersion())
                        .put("sectorSize", pack.getSizeInSectors())
                );
    }
    private JsonObject getFsPackMetadata(FsStoryPackInfos pack) {
        return databaseMetadataService.getPackMetadata(pack.getUuid().toString())
                .map(metadata -> new JsonObject()
                        .put("uuid", pack.getUuid().toString())
                        .put("format", Constants.PACK_FORMAT_FS)
                        .put("version", pack.getVersion())
                        .put("title", metadata.getTitle())
                        .put("description", metadata.getDescription())
                        .put("image", metadata.getThumbnail())
                        .put("folderName", pack.getFolderName())
                        .put("sizeInBytes", pack.getSizeInBytes())
                        .put("official", metadata.isOfficial())
                        .put("nightModeAvailable", pack.isNightModeAvailable())
                )
                .orElse(new JsonObject()
                        .put("uuid", pack.getUuid().toString())
                        .put("format", Constants.PACK_FORMAT_FS)
                        .put("version", pack.getVersion())
                        .put("folderName", pack.getFolderName())
                        .put("sizeInBytes", pack.getSizeInBytes())
                );
    }

}
