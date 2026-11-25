/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.model.fs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a read-only inspection of a firmware 2.x / 3.x partition found wrong with its bookkeeping:
 * folders the index does not reference, index entries with nothing behind them, a {@code .pi.new}
 * left by an interrupted index write, and the leftovers of an interrupted in-place update.
 *
 * <p>Nothing here is a repair. Every repair is a separate, explicit operation that re-reads the card
 * before it acts.
 */
public class FsDeviceDiagnosis {

    /** A {@code .content} folder that no entry of {@code .pi} refers to. */
    public static final class OrphanFolder {
        private final String folder;
        private final long sizeInBytes;
        private final boolean complete;

        public OrphanFolder(String folder, long sizeInBytes, boolean complete) {
            this.folder = folder;
            this.sizeInBytes = sizeInBytes;
            this.complete = complete;
        }

        public String getFolder() {
            return folder;
        }

        public long getSizeInBytes() {
            return sizeInBytes;
        }

        /** Whether it holds every file STUdio writes, the boot file (written last) included. */
        public boolean isComplete() {
            return complete;
        }
    }

    /** An index entry whose folder is absent, or present without a node index. */
    public static final class MissingContent {
        private final UUID uuid;
        private final String folder;
        private final boolean folderPresent;

        public MissingContent(UUID uuid, String folder, boolean folderPresent) {
            this.uuid = uuid;
            this.folder = folder;
            this.folderPresent = folderPresent;
        }

        public UUID getUuid() {
            return uuid;
        }

        public String getFolder() {
            return folder;
        }

        public boolean isFolderPresent() {
            return folderPresent;
        }
    }

    /** How a pending {@code .pi.new} relates to the installed {@code .pi}. */
    public enum PendingIndexKind {
        /** Not a whole number of records: the write was cut before it completed. */
        TORN,
        /** Same content as {@code .pi}. */
        IDENTICAL,
        /** {@code .pi} plus one pack whose folder is absent or incomplete: an upload that did not finish. */
        ADD_INCOMPLETE,
        /** {@code .pi} plus packs whose content is complete on the card. */
        ADD,
        /** {@code .pi} minus some packs: an interrupted removal. */
        REMOVE,
        /** Same packs, another order: an interrupted reordering. */
        REORDER,
        /** Anything else. */
        OTHER
    }

    public static final class PendingIndex {
        private final PendingIndexKind kind;
        private final boolean safeToDiscard;
        private final String fingerprint;
        private final int currentCount;
        private final int pendingCount;
        private final List<UUID> added;
        private final List<UUID> removed;

        public PendingIndex(PendingIndexKind kind, boolean safeToDiscard, String fingerprint, int currentCount,
                            int pendingCount, List<UUID> added, List<UUID> removed) {
            this.kind = kind;
            this.safeToDiscard = safeToDiscard;
            this.fingerprint = fingerprint;
            this.currentCount = currentCount;
            this.pendingCount = pendingCount;
            this.added = List.copyOf(added);
            this.removed = List.copyOf(removed);
        }

        public PendingIndexKind getKind() {
            return kind;
        }

        /** Discarding it loses nothing the card relies on: STUdio may offer to do it without a choice. */
        public boolean isSafeToDiscard() {
            return safeToDiscard;
        }

        /** Identifies the two files as read; a resolution is refused if they changed since. */
        public String getFingerprint() {
            return fingerprint;
        }

        /** Entries of {@code .pi}, or -1 if it is missing or unreadable. */
        public int getCurrentCount() {
            return currentCount;
        }

        /** Entries of {@code .pi.new}, or -1 if it is torn. */
        public int getPendingCount() {
            return pendingCount;
        }

        public List<UUID> getAdded() {
            return added;
        }

        public List<UUID> getRemoved() {
            return removed;
        }
    }

    /** Where an interrupted in-place update stopped, read from the folders it leaves. */
    public enum InterruptedUpdateState {
        /** The new copy was being written; the pack itself is untouched. */
        COPY_INTERRUPTED,
        /** Between the two renames: the pack folder is missing, the new copy is complete. */
        SWAP_INTERRUPTED,
        /** The new content is in place; the previous one was still being removed. */
        CLEANUP_INTERRUPTED,
        /** Only the previous content is left, under its temporary name. */
        PREVIOUS_ONLY,
        /** Leftovers for a pack the index does not reference any more. */
        UNREFERENCED
    }

    public static final class InterruptedUpdate {
        private final String folder;
        private final UUID uuid;
        private final InterruptedUpdateState state;
        private final List<String> leftovers;

        public InterruptedUpdate(String folder, UUID uuid, InterruptedUpdateState state, List<String> leftovers) {
            this.folder = folder;
            this.uuid = uuid;
            this.state = state;
            this.leftovers = List.copyOf(leftovers);
        }

        /** The pack folder name (8 hex characters) the leftovers belong to. */
        public String getFolder() {
            return folder;
        }

        /** The indexed pack using that folder, or {@code null}. */
        public UUID getUuid() {
            return uuid;
        }

        public InterruptedUpdateState getState() {
            return state;
        }

        public List<String> getLeftovers() {
            return leftovers;
        }
    }

    private final List<OrphanFolder> orphans = new ArrayList<>();
    private final List<MissingContent> missingContent = new ArrayList<>();
    private final List<InterruptedUpdate> interruptedUpdates = new ArrayList<>();
    private PendingIndex pendingIndex;
    private int indexedPacks;

    public List<OrphanFolder> getOrphans() {
        return orphans;
    }

    public List<MissingContent> getMissingContent() {
        return missingContent;
    }

    public List<InterruptedUpdate> getInterruptedUpdates() {
        return interruptedUpdates;
    }

    /** {@code null} when there is no {@code .pi.new}. */
    public PendingIndex getPendingIndex() {
        return pendingIndex;
    }

    public void setPendingIndex(PendingIndex pendingIndex) {
        this.pendingIndex = pendingIndex;
    }

    public int getIndexedPacks() {
        return indexedPacks;
    }

    public void setIndexedPacks(int indexedPacks) {
        this.indexedPacks = indexedPacks;
    }

    public int problemCount() {
        return orphans.size() + missingContent.size() + interruptedUpdates.size() + (pendingIndex == null ? 0 : 1);
    }
}
