/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.model;

import studio.core.v1.model.metadata.StoryPackMetadata;

import java.nio.file.Path;

public class LibraryPack {

    private Path path;
    private long timestamp;
    private StoryPackMetadata metadata;
    private volatile long size = -1;

    public LibraryPack(Path path, long timestamp, StoryPackMetadata metadata) {
        this.path = path;
        this.timestamp = timestamp;
        this.metadata = metadata;
    }

    public Path getPath() {
        return path;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public StoryPackMetadata getMetadata() {
        return metadata;
    }

    /** Bytes on disk (the file, or the regular files of a folder), or -1 when unknown. */
    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}
