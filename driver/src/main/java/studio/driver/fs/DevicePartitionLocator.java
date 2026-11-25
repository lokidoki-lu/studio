/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Finds the drive on which a plugged story teller has been mounted.
 *
 * <p>Seeing the device on the USB bus and being able to read its filesystem are two distinct events,
 * and on Windows they can be seconds apart: the OS has to enumerate the mass-storage interface,
 * assign a drive letter and mount the volume. The previous implementation gave the OS ten one-second
 * attempts and then threw. The throw was swallowed by the {@code CompletableFuture} that invoked the
 * hotplug listener, so a slow mount produced no device, no error in the UI, and — because the USB
 * arrival event only fires once — no further attempt for as long as the device stayed plugged in.
 *
 * <p>This class polls until the partition shows up, until the caller says the device is gone, or
 * until a bounded overall deadline. Every dependency is injected so the behaviour can be tested
 * without a device, a drive letter or a real clock.
 */
public class DevicePartitionLocator {

    private static final Logger LOGGER = Logger.getLogger(DevicePartitionLocator.class.getName());

    static final long DEFAULT_POLL_DELAY_MS = 1000L;
    /** 120 attempts at one second: generous for a slow hub, still bounded. */
    static final int DEFAULT_MAX_ATTEMPTS = 120;
    /** Report progress every Nth attempt rather than on each one. */
    static final int PROGRESS_LOG_INTERVAL = 5;

    /** Injectable so tests do not have to actually wait. Returns false if the wait was interrupted. */
    public interface Sleeper {
        boolean sleep(long millis);
    }

    private final Supplier<List<String>> mountPointsSupplier;
    private final Predicate<String> partitionMarkerPresent;
    private final Sleeper sleeper;
    private final long pollDelayMillis;
    private final int maxAttempts;

    public DevicePartitionLocator(Supplier<List<String>> mountPointsSupplier,
                                  Predicate<String> partitionMarkerPresent,
                                  Sleeper sleeper,
                                  long pollDelayMillis,
                                  int maxAttempts) {
        this.mountPointsSupplier = mountPointsSupplier;
        this.partitionMarkerPresent = partitionMarkerPresent;
        this.sleeper = sleeper;
        this.pollDelayMillis = pollDelayMillis;
        this.maxAttempts = maxAttempts;
    }

    /** Production wiring: real mount points, real marker file, real sleep. */
    public static DevicePartitionLocator forDeviceMetadataFile(String metadataFileName) {
        return new DevicePartitionLocator(
                DeviceUtils::listMountPoints,
                path -> new File(path, metadataFileName).exists(),
                millis -> {
                    try {
                        Thread.sleep(millis);
                        return true;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                },
                DEFAULT_POLL_DELAY_MS,
                DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * Polls for the device partition.
     *
     * @param deviceStillPlugged consulted before every attempt; when it turns false the search is
     *                           abandoned at once, so an unplug cannot leave a search running or a
     *                           stale mount point behind.
     * @return the mount point, or empty if the device went away or the deadline expired.
     */
    public Optional<String> awaitPartition(BooleanSupplier deviceStillPlugged) {
        return awaitPartition(deviceStillPlugged, List::of);
    }

    /**
     * As {@link #awaitPartition(BooleanSupplier)}, never answering one of the {@code excluded} mount
     * points (compared by volume, like candidates): the partition of a device already published
     * belongs to that device, not to the one just plugged. A volume is skipped, not refused: the
     * search goes on until the new device's own partition is mounted. Consulted before every
     * attempt, so a volume stops being excluded once its device is gone.
     */
    public Optional<String> awaitPartition(BooleanSupplier deviceStillPlugged, Supplier<List<String>> excluded) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!deviceStillPlugged.getAsBoolean()) {
                LOGGER.info("Device partition search cancelled: device is no longer plugged");
                return Optional.empty();
            }

            List<Object> excludedVolumes = new ArrayList<>();
            for (String mountPoint : excluded.get()) {
                excludedVolumes.add(volumeIdentity(mountPoint));
            }
            Optional<String> found = scanOnce(excludedVolumes);
            if (found.isPresent()) {
                LOGGER.info("Device partition located on " + found.get() + " after " + attempt + " attempt(s)");
                return found;
            }

            if (attempt == 1 || attempt % PROGRESS_LOG_INTERVAL == 0) {
                LOGGER.info("Device is plugged but its partition is not mounted yet; still waiting (attempt "
                        + attempt + "/" + maxAttempts + ")");
            }

            if (!sleeper.sleep(pollDelayMillis)) {
                LOGGER.info("Device partition search interrupted");
                return Optional.empty();
            }
        }

        LOGGER.warning("Gave up locating the device partition after " + maxAttempts
                + " attempt(s). Unplug and plug the device back in to retry.");
        return Optional.empty();
    }

    private Optional<String> scanOnce(List<Object> excludedVolumes) {
        List<String> mountPoints;
        try {
            mountPoints = mountPointsSupplier.get();
        } catch (Exception e) {
            // Listing volumes can fail transiently (a disconnected network drive, an empty card
            // reader). That is not a reason to abandon the search.
            LOGGER.log(Level.WARNING, "Failed to list mount points; will retry", e);
            return Optional.empty();
        }

        // Keyed by volume identity, so that one volume listed under several names (a symlink, a
        // bind mount, the same path spelled twice) counts once. The first name listed is kept.
        Map<Object, String> byVolume = new LinkedHashMap<>();
        for (String mountPoint : mountPoints) {
            try {
                if (partitionMarkerPresent.test(mountPoint)) {
                    Object volume = volumeIdentity(mountPoint);
                    if (excludedVolumes.contains(volume)) {
                        LOGGER.fine("Skipping " + mountPoint + ": it is the partition of the device already in use");
                        continue;
                    }
                    byVolume.putIfAbsent(volume, mountPoint);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Failed to probe mount point " + mountPoint, e);
            }
        }
        List<String> candidates = new ArrayList<>(byVolume.values());
        if (candidates.size() > 1) {
            // Two story tellers plugged at once, a card reader holding a copy of a device's
            // partition, the same volume mounted twice... Nothing here can tell which one is the
            // device that was just plugged, and picking the first in listing order would aim every
            // later write — the pack index included — at whichever volume the OS happened to list
            // first. Refuse instead; the search goes on, so removing the extra volume resolves it.
            LOGGER.warning("Several mounted volumes look like a story teller partition " + candidates
                    + "; refusing to choose one. Unplug or unmount all but one of them.");
            return Optional.empty();
        }
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    /**
     * What tells two mount points apart: the file key of the root (device and inode on Unix) when
     * the platform has one, else its canonical path ({@code toRealPath}, which resolves symlinks and
     * normalises the spelling), else the name as listed. Windows reports no file key, so there two
     * drive letters stay two candidates — the ambiguity is still refused, never guessed.
     */
    static Object volumeIdentity(String mountPoint) {
        try {
            Path real = Paths.get(mountPoint).toRealPath();
            try {
                Object fileKey = Files.readAttributes(real, BasicFileAttributes.class).fileKey();
                if (fileKey != null) {
                    return fileKey;
                }
            } catch (Exception noAttributes) {
                LOGGER.log(Level.FINEST, "No file key for " + real, noAttributes);
            }
            return real.toString();
        } catch (Exception unresolvable) {
            LOGGER.log(Level.FINEST, "Could not resolve mount point " + mountPoint, unresolvable);
            return mountPoint;
        }
    }
}
