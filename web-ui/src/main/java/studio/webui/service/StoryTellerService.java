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
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

public class StoryTellerService implements IStoryTellerService {

    private final Logger LOGGER = LoggerFactory.getLogger(StoryTellerService.class);

    private final EventBus eventBus;

    private final DatabaseMetadataService databaseMetadataService;

    private RawStoryTellerAsyncDriver driver;
    private FsStoryTellerAsyncDriver fsDriver;
    // Written by the hotplug threads, read by unordered request workers.
    private volatile Device device;
    private volatile Device fsDevice;
    // Last answers read from a 1.x device, served while the queue is busy with it: a 1.x device is
    // read over USB, inside the queue, and a long transfer would otherwise hold every refresh.
    private volatile Optional<JsonObject> lastInfosV1 = Optional.empty();
    private volatile JsonArray lastPacksV1;

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
                    eventBus.publish("storyteller.failure", null);
                } else {
                    LOGGER.info("Device 1.x plugged");
                    StoryTellerService.this.device = device;
                    CompletableFuture.runAsync(() -> driver.getDeviceInfos()
                            .handle((infos, e) -> {
                                if (e != null) {
                                    LOGGER.error("Failed to plug device 1.x", e);
                                    // Send 'failure' event on bus
                                    eventBus.publish("storyteller.failure", null);
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
                                    eventBus.publish("storyteller.plugged", eventData);
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
                abandonDeviceOperations();
                // Send 'unplugged' event on bus
                eventBus.publish("storyteller.unplugged", null);
            }
        });

        // React when a device with firmware 2.x is plugged or unplugged
        fsDriver.registerDeviceListener(new DeviceHotplugEventListener() {
            @Override
            public void onDevicePlugged(Device device) {
                if (device == null) {
                    LOGGER.error("Device 2.x plugged but got null device");
                    // Send 'failure' event on bus
                    eventBus.publish("storyteller.failure", null);
                } else {
                    LOGGER.info("Device 2.x plugged");
                    StoryTellerService.this.fsDevice = device;
                    CompletableFuture.runAsync(() -> fsDriver.getDeviceInfos()
                            .handle((infos, e) -> {
                                if (e != null) {
                                    LOGGER.error("Failed to plug device 2.x", e);
                                    // Send 'failure' event on bus
                                    eventBus.publish("storyteller.failure", null);
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
                                    eventBus.publish("storyteller.plugged", eventData);
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
                abandonDeviceOperations();
                // Send 'unplugged' event on bus
                eventBus.publish("storyteller.unplugged", null);
            }
        });
    }

    /**
     * Called when a device goes away: an operation still running against it, or waiting for it,
     * cannot succeed, and a frozen one would otherwise hold the queue until STUdio is restarted.
     */
    private void abandonDeviceOperations() {
        lastInfosV1 = Optional.empty();
        lastPacksV1 = null;
        queue.reset();
        notifier.failAllUnfinished(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE,
                "The device was unplugged before the transfer ended"));
    }

    @Override
    public OptionalLong deviceBlockSize() {
        return fsDevice != null ? fsDriver.partitionBlockSize() : OptionalLong.empty();
    }

    public CompletableFuture<Optional<JsonObject>> deviceInfos() {
        if (device != null) {
            // A 1.x device is read over USB, which must not overlap a transfer.
            if (queue.pending() > 0 && lastInfosV1.isPresent()) {
                return CompletableFuture.completedFuture(Optional.of(lastInfosV1.get().copy().put("cached", true)));
            }
            return queue.submit(this::deviceInfosV1).thenApply(infos -> {
                lastInfosV1 = infos;
                return infos;
            });
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
            if (queue.pending() > 0 && lastPacksV1 != null) {
                return CompletableFuture.completedFuture(lastPacksV1.copy());
            }
            return queue.submit(this::packsV1).thenApply(packs -> {
                lastPacksV1 = packs;
                return packs;
            });
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
        if (notifier.registry().hasUnfinished(uuid, "add")) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.PACK_ALREADY_ON_DEVICE,
                    "The pack " + uuid + " is already being transferred to the device"));
        }
        // On a 2.x+ device the index is a file, safe to read while a queued upload is running (it is
        // replaced atomically). A 1.x device is read over USB, which must not overlap a transfer:
        // there the check is left to the transfer itself, which makes it again in its turn, so that
        // the request is answered at once instead of waiting behind every queued operation.
        CompletableFuture<Boolean> precheck = fsDevice != null
                ? isPackOnDevice(uuid)
                : CompletableFuture.completedFuture(false);
        return precheck
                .thenApply(present -> {
                    if (present) {
                        LOGGER.error("Cannot add pack to device because the pack already exists on the device");
                        throw alreadyOnDevice(uuid);
                    }
                    String transferId = notifier.create(uuid, "add");
                    LOGGER.info("Transfer " + transferId + " queued (" + (queue.pending()) + " device operation(s) ahead)");
                    queue.submit(() -> runAddPack(transferId, uuid, packFile))
                            // The queue may give up on the operation (timeout, device unplugged)
                            // before it settles: the transfer must not stay "running" for ever.
                            .whenComplete((ignored, t) -> settleQueued(transferId, t));
                    return Optional.of(transferId);
                });
    }

    /**
     * What the queue's answer means for a transfer. A timeout is not a verdict: the operation may
     * still be writing and may still succeed, so the transfer stays running, marked as stalled.
     */
    private void settleQueued(String transferId, Throwable t) {
        if (t == null) {
            return;
        }
        Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        if (c instanceof DeviceOperationQueue.OperationTimeoutException) {
            notifier.registry().stalled(transferId, c.getMessage());
        } else {
            notifier.failed(transferId, t);
        }
    }

    private CompletableFuture<Void> runAddPack(String transferId, String uuid, File packFile) {
        notifier.started(transferId);
        CompletableFuture<Void> outcome = checkedAgain(uuid, packFile)
                .thenCompose(ignored -> isPackOnDevice(uuid))
                .thenCompose(present -> {
                    if (present) {
                        throw alreadyOnDevice(uuid);
                    }
                    if (device != null) {
                        return uploadPackV1(transferId, packFile);
                    } else if (fsDevice != null) {
                        LOGGER.info("Transferring pack folder to device: " + packFile.getAbsolutePath());
                        return fsDeviceKey().thenCompose(key -> fsDriver.uploadPack(uuid, packFile.getAbsolutePath(), notifier.listener(transferId))
                                .thenApply(status -> {
                                    key.ifPresent(k -> notifyWritten(k, uuid, packFile));
                                    return status;
                                }));
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

    private volatile SendGuard sendGuard;

    @Override
    public void setSendGuard(SendGuard guard) {
        this.sendGuard = guard;
    }

    /**
     * The send guard, checked again when the transfer starts (in the device queue): the library
     * entry checked when the request was accepted may have been replaced by another pack, or found
     * official, while the transfer waited. A guard that fails refuses the transfer.
     */
    private CompletableFuture<Void> checkedAgain(String uuid, File packFile) {
        SendGuard guard = sendGuard;
        if (guard == null) {
            return CompletableFuture.completedFuture(null);
        }
        Optional<StoryTellerException> refused;
        try {
            refused = guard.refusal(uuid, packFile);
        } catch (RuntimeException e) {
            refused = Optional.of(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                    "The library entry " + packFile.getName() + " could not be checked again before the transfer: nothing was sent", e));
        }
        return refused.isPresent() ? CompletableFuture.failedFuture(refused.get()) : CompletableFuture.completedFuture(null);
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
                return fsDeviceKey().thenCompose(key -> fsDriver.deletePack(uuid).thenApply(removed -> {
                    if (Boolean.TRUE.equals(removed)) {
                        key.ifPresent(k -> notifyRemoved(k, uuid));
                    }
                    return removed;
                }));
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
    @Override
    public CompletableFuture<JsonArray> removeOrphanFolders(List<String> folderNames, String deviceToken) {
        if (deviceToken == null || deviceToken.isEmpty()) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST,
                    "The 'deviceToken' the device check answered with is required: nothing was removed"));
        }
        return queue.submit(() -> {
            if (fsDevice == null) {
                return CompletableFuture.failedFuture(device != null
                        ? new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Only devices with firmware 2.x and later have pack folders")
                        : noDevice());
            }
            JsonArray results = new JsonArray();
            CompletableFuture<Void> chain = fsDriver.requireDeviceToken(deviceToken);
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
        try {
            if (!UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)) {
                throw new IllegalArgumentException("Not a canonical UUID");
            }
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Invalid pack UUID: " + uuid, e));
        }
        if (device == null && fsDevice == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        if (device != null ? packFile.exists() : new File(packFile, uuid).exists()) {
            LOGGER.error("Cannot extract pack from device because the destination file already exists");
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS,
                    "The pack " + uuid + " is already in the library"));
        }
        String transferId = notifier.create(uuid, "extract");
        queue.submit(() -> {
            notifier.started(transferId);
            CompletableFuture<TransferStatus> extraction;
            try {
                // Checked again in turn: another extraction of the same pack may have been queued
                // ahead of this one since the request was accepted.
                if (device != null ? packFile.exists() : new File(packFile, uuid).exists()) {
                    extraction = CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.CONTENT_FOLDER_EXISTS,
                            "The pack " + uuid + " is already in the library"));
                } else if (device != null) {
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
        }).whenComplete((ignored, t) -> settleQueued(transferId, t));
        return CompletableFuture.completedFuture(Optional.of(transferId));
    }

    private CompletableFuture<TransferStatus> extractPackV1(String transferId, String uuid, File destFile) {
        // Written beside the destination under a hidden name, then renamed: the library never lists
        // a partial pack, and an existing file is never truncated.
        File partFile = new File(destFile.getParentFile(), ".part-" + destFile.getName() + "." + transferId);
        FileOutputStream fos;
        try {
            fos = new FileOutputStream(partFile);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new StoryTellerException("Failed to open destination file", e));
        }
        CompletableFuture<TransferStatus> download;
        try {
            download = driver.downloadPack(uuid, fos, notifier.listener(transferId));
        } catch (RuntimeException e) {
            download = CompletableFuture.failedFuture(e);
        }
        return download.handle((status, t) -> {
            try {
                fos.close();
            } catch (IOException e) {
                LOGGER.error("Failed to close destination file.", e);
                if (t == null) {
                    t = e;
                }
            }
            if (t == null) {
                try {
                    java.nio.file.Files.move(partFile.toPath(), destFile.toPath());
                } catch (IOException e) {
                    t = new StoryTellerException(destFile.exists() ? StoryTellerException.Reason.CONTENT_FOLDER_EXISTS : StoryTellerException.Reason.UNKNOWN,
                            "Could not install the extracted pack as " + destFile.getName(), e);
                }
            }
            if (t != null) {
                if (partFile.exists() && !partFile.delete()) {
                    LOGGER.warn("Could not remove the partial pack file " + partFile);
                }
                throw t instanceof CompletionException ? (CompletionException) t : new CompletionException(t);
            }
            return status;
        });
    }

    public CompletableFuture<Void> dump(String outputPath) {
        if (this.device == null) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.NO_DEVICE,
                    "Only a plugged device with firmware 1.x can be dumped"));
        }
        // Read over USB: in the queue, like every other access to a 1.x device.
        return queue.submit(() -> {
            new File(outputPath).mkdirs();
            return driver.dump(outputPath);
        });
    }

    // ================================================================== device check, repairs, updates

    private volatile DevicePackListener devicePackListener;

    @Override
    public void setDevicePackListener(DevicePackListener listener) {
        this.devicePackListener = listener;
    }

    private static StoryTellerException notOnRawDevice(String what) {
        return new StoryTellerException(StoryTellerException.Reason.UNSUPPORTED_ON_DEVICE, what
                + " is not available on a Lunii with firmware 1.x: its packs are stored in raw sectors, not in folders");
    }

    @Override
    public CompletableFuture<Optional<String>> fsDeviceKey() {
        if (fsDevice == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return fsDriver.getDeviceInfos().thenApply(StoryTellerService::deviceKeyOf).exceptionally(e -> Optional.empty());
    }

    static Optional<String> deviceKeyOf(studio.driver.model.fs.FsDeviceInfos infos) {
        String serial = infos.getSerialNumber() == null ? "" : infos.getSerialNumber().trim();
        if (!serial.isEmpty() && serial.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
            return Optional.of(serial);
        }
        return infos.getUuid() == null ? Optional.empty() : Optional.of(Hex.encodeHexString(infos.getUuid()));
    }

    /**
     * The device check. Run in the device queue, like the orphan listing, so that a folder an upload
     * has created but not yet indexed is never reported.
     */
    @Override
    public CompletableFuture<JsonObject> diagnosis() {
        if (device != null && fsDevice == null) {
            return CompletableFuture.completedFuture(new JsonObject().put("supported", false).put("driver", "raw"));
        }
        return queue.submit(() -> {
            if (fsDevice == null) {
                return CompletableFuture.failedFuture(noDevice());
            }
            return fsDriver.diagnose().thenApply(DeviceRepairJson::diagnosis);
        });
    }

    @Override
    public CompletableFuture<Boolean> resolvePendingIndex(boolean adopt, String fingerprint, String deviceToken) {
        return queue.submit(() -> {
            if (fsDevice == null) {
                return CompletableFuture.failedFuture(device != null ? notOnRawDevice("Resolving a pending index") : noDevice());
            }
            return fsDriver.resolvePendingIndex(adopt, fingerprint, deviceToken);
        });
    }

    @Override
    public CompletableFuture<Boolean> resolveInterruptedUpdate(String folder, String state, String deviceToken) {
        return queue.submit(() -> {
            if (fsDevice == null) {
                return CompletableFuture.failedFuture(device != null ? notOnRawDevice("Resolving an interrupted update") : noDevice());
            }
            if (!"NO_COMPLETE_COPY".equals(state)) {
                return fsDriver.resolveInterruptedUpdate(folder, state, deviceToken);
            }
            // That repair removes the pack from the index: what is known to be on this Lunii forgets it too.
            return fsDeviceKey().thenCompose(key -> fsDriver.packsUsingFolder(folder)
                    .thenCompose(removed -> fsDriver.resolveInterruptedUpdate(folder, state, deviceToken).thenApply(done -> {
                        if (Boolean.TRUE.equals(done)) {
                            key.ifPresent(k -> removed.forEach(uuid -> notifyRemoved(k, uuid.toString())));
                        }
                        return done;
                    })));
        });
    }

    @Override
    public CompletableFuture<Boolean> removeMissingContentEntry(String uuid, String deviceToken) {
        return queue.submit(() -> {
            if (fsDevice == null) {
                return CompletableFuture.failedFuture(device != null ? notOnRawDevice("Removing an entry without content") : noDevice());
            }
            return fsDeviceKey().thenCompose(key -> fsDriver.removeMissingContentEntry(uuid, deviceToken).thenApply(removed -> {
                if (Boolean.TRUE.equals(removed)) {
                    key.ifPresent(k -> notifyRemoved(k, uuid));
                }
                return removed;
            }));
        });
    }

    /** Integrity checks, by id: their progress, then their results. The last few are kept. */
    private final Map<String, JsonObject> integrityChecks = Collections.synchronizedMap(new LinkedHashMap<String, JsonObject>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JsonObject> eldest) {
            return size() > 10;
        }
    });

    @Override
    public CompletableFuture<String> startIntegrityCheck() {
        if (fsDevice == null) {
            return CompletableFuture.failedFuture(device != null ? notOnRawDevice("The integrity check") : noDevice());
        }
        String id = UUID.randomUUID().toString();
        JsonObject state = new JsonObject().put("id", id).put("done", false).put("checked", 0).put("total", 0);
        integrityChecks.put(id, state);
        // In the queue: the check reads while nothing writes, so a pack being replaced is never
        // reported as damaged. It only reads index files and the first block of each asset.
        queue.submit(() -> {
            if (fsDevice == null) {
                return CompletableFuture.<List<studio.driver.model.fs.FsPackIntegrity>>failedFuture(noDevice());
            }
            // Official packs: structure only, their ciphered parts may use a key of their own.
            return fsDriver.checkIntegrity((checked, total) -> {
                synchronized (state) {
                    state.put("checked", checked).put("total", total);
                }
            }, uuid -> databaseMetadataService.getPackMetadata(uuid.toString()).map(m -> m.isOfficial()).orElse(false));
        }).whenComplete((results, e) -> {
            synchronized (state) {
                state.put("done", true);
                if (e != null) {
                    state.mergeIn(ApiErrors.body(e));
                } else {
                    JsonArray packs = new JsonArray();
                    results.forEach(r -> packs.add(DeviceRepairJson.integrity(r, databaseMetadataService)));
                    state.put("success", true).put("packs", packs).put("checked", state.getInteger("total"));
                }
            }
        });
        return CompletableFuture.completedFuture(id);
    }

    @Override
    public Optional<JsonObject> integrityCheck(String id) {
        JsonObject state = id == null ? null : integrityChecks.get(id);
        if (state == null) {
            return Optional.empty();
        }
        synchronized (state) {
            return Optional.of(state.copy());
        }
    }

    @Override
    public CompletableFuture<Boolean> packContentMatches(String uuid, File packFolder) {
        if (fsDevice == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        if (packFolder == null || !packFolder.isDirectory()) {
            return CompletableFuture.completedFuture(false);
        }
        // In the device queue: the pack is read in full, and must not be read while it is being
        // replaced (nor hold handles that would make the rename of an update fail on Windows). Only
        // when the queue is idle, so that the caller never waits behind a transfer. An error is a
        // failure, never "different": only a difference may be remembered by the caller.
        return queue.submitIfIdle(() -> fsDevice == null
                ? CompletableFuture.<Boolean>failedFuture(noDevice())
                : fsDriver.packContentMatches(uuid, packFolder.getAbsolutePath()));
    }

    @Override
    public boolean deviceBusy() {
        return queue.pending() > 0;
    }

    /**
     * Queues the in-place replacement of a pack, as a transfer whose operation is {@code update}.
     * Only on a 2.x+ device: a 1.x device stores packs in contiguous raw sectors, and rewriting one
     * of a different size in place could overwrite its neighbours.
     */
    @Override
    public CompletableFuture<Optional<String>> updatePack(String uuid, File packFile) {
        try {
            UUID.fromString(uuid);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.INVALID_REQUEST, "Invalid pack UUID: " + uuid, e));
        }
        if (device != null && fsDevice == null) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.UNSUPPORTED_ON_DEVICE,
                    "Updating a pack in place is not supported on a Lunii with firmware 1.x: its packs are stored in"
                            + " contiguous sectors, and writing a new version over one could overwrite the packs after it."
                            + " Remove the pack from the Lunii, then send it again."));
        }
        if (fsDevice == null) {
            return CompletableFuture.failedFuture(noDevice());
        }
        if (packFile == null || !packFile.isDirectory()) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.LIBRARY_PACK_NOT_FOUND,
                    "Not a pack folder of the library: " + (packFile == null ? null : packFile.getName())
                            + ". A pack must be converted for this Lunii before it can replace the one on it."));
        }
        if (notifier.registry().hasUnfinished(uuid, "update") || notifier.registry().hasUnfinished(uuid, "add")) {
            return CompletableFuture.failedFuture(new StoryTellerException(StoryTellerException.Reason.PACK_ALREADY_ON_DEVICE,
                    "The pack " + uuid + " is already being transferred to the device"));
        }
        String transferId = notifier.create(uuid, "update");
        queue.submit(() -> runUpdatePack(transferId, uuid, packFile))
                .whenComplete((ignored, t) -> settleQueued(transferId, t));
        return CompletableFuture.completedFuture(Optional.of(transferId));
    }

    private CompletableFuture<Void> runUpdatePack(String transferId, String uuid, File packFile) {
        notifier.started(transferId);
        if (fsDevice == null) {
            notifier.failed(transferId, noDevice());
            return CompletableFuture.failedFuture(noDevice());
        }
        CompletableFuture<Optional<String>> key = checkedAgain(uuid, packFile).thenCompose(ignored -> fsDeviceKey());
        return key.thenCompose(deviceKey -> fsDriver.updatePack(uuid, packFile.getAbsolutePath(), notifier.listener(transferId))
                .thenApply(status -> {
                    deviceKey.ifPresent(k -> notifyWritten(k, uuid, packFile));
                    return (Void) null;
                }))
                .whenComplete((ignored, t) -> {
                    if (t != null) {
                        LOGGER.error("Failed to update pack on device", t);
                        notifier.failed(transferId, t);
                    } else {
                        LOGGER.info("Pack updated.");
                        notifier.succeeded(transferId);
                    }
                });
    }

    private void notifyWritten(String deviceKey, String uuid, File packFile) {
        DevicePackListener listener = devicePackListener;
        if (listener != null) {
            try {
                listener.onWritten(deviceKey, uuid, packFile);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to record the pack written to the device", e);
            }
        }
    }

    private void notifyRemoved(String deviceKey, String uuid) {
        DevicePackListener listener = devicePackListener;
        if (listener != null) {
            try {
                listener.onRemoved(deviceKey, uuid);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to forget the pack removed from the device", e);
            }
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
