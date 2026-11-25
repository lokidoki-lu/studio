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
 * The result of a read-only integrity check of one pack on a firmware 2.x / 3.x device: either no
 * problem, or the precise list of what is missing or inconsistent.
 */
public class FsPackIntegrity {

    /** What can be wrong with a pack. Stable codes: the web UI translates them. */
    public enum ProblemCode {
        FOLDER_MISSING,
        FILE_MISSING,
        FILE_UNREADABLE,
        NODE_INDEX_INVALID,
        ASSET_INDEX_INVALID,
        LIST_INDEX_INVALID,
        COUNT_MISMATCH,
        ASSET_REFERENCE_OUT_OF_RANGE,
        TRANSITION_OUT_OF_RANGE,
        ASSET_MISSING,
        ASSET_EMPTY,
        ASSET_HEADER_INVALID,
        BOOT_FILE_INVALID
    }

    public static final class Problem {
        private final ProblemCode code;
        private final String detail;

        public Problem(ProblemCode code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        public ProblemCode getCode() {
            return code;
        }

        /** What exactly: a file name, a node number, an index. For people, not for branching. */
        public String getDetail() {
            return detail;
        }

        @Override
        public String toString() {
            return code + ": " + detail;
        }
    }

    private final UUID uuid;
    private final String folder;
    private final List<Problem> problems = new ArrayList<>();
    private int stageNodes;
    private int images;
    private int sounds;
    private int filesChecked;
    private boolean structureOnly;

    public FsPackIntegrity(UUID uuid, String folder) {
        this.uuid = uuid;
        this.folder = folder;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getFolder() {
        return folder;
    }

    public boolean isOk() {
        return problems.isEmpty();
    }

    public List<Problem> getProblems() {
        return problems;
    }

    public void add(ProblemCode code, String detail) {
        problems.add(new Problem(code, detail));
    }

    public boolean has(ProblemCode code) {
        return problems.stream().anyMatch(p -> p.getCode() == code);
    }

    public int getStageNodes() {
        return stageNodes;
    }

    public void setStageNodes(int stageNodes) {
        this.stageNodes = stageNodes;
    }

    public int getImages() {
        return images;
    }

    public void setImages(int images) {
        this.images = images;
    }

    public int getSounds() {
        return sounds;
    }

    public void setSounds(int sounds) {
        this.sounds = sounds;
    }

    public int getFilesChecked() {
        return filesChecked;
    }

    /**
     * Whether only the structure was checked (files present, node index, sizes): the ciphered parts
     * and the boot file of an official pack are not checked, their key may be the pack's own.
     */
    public boolean isStructureOnly() {
        return structureOnly;
    }

    public void setStructureOnly(boolean structureOnly) {
        this.structureOnly = structureOnly;
    }

    public void setFilesChecked(int filesChecked) {
        this.filesChecked = filesChecked;
    }
}
