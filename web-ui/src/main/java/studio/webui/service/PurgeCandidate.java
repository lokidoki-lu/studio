/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonObject;

/**
 * A library entry that no longer serves any purpose, and why.
 *
 * <p>Only {@link LibraryService#purgeCandidates()} creates these, and the purge itself never trusts
 * one that came back from a client: it recomputes the list and acts only on names still in it.
 */
public final class PurgeCandidate {

    /** Why an entry is offered for removal. Each reason says whether it is selected by default. */
    public enum Reason {
        /**
         * A conversion output for which a later conversion of the same pack, to the same format,
         * exists. The UI only ever offers the most recent one to a device, so this one is never used.
         */
        SUPERSEDED(true),

        /**
         * The only conversion of its format, but its recorded provenance proves it was made from
         * content that is no longer anywhere in the library, while a current source is. Using it
         * would send an outdated pack; the current source can produce a fresh one.
         */
        SOURCE_CHANGED(true),

        /**
         * An older source file holding exactly the same bytes as a more recent source of the same
         * pack and format. Listed, but never selected by default: it is a file the user put there.
         */
        DUPLICATE_SOURCE(false);

        private final boolean selectedByDefault;

        Reason(boolean selectedByDefault) {
            this.selectedByDefault = selectedByDefault;
        }

        public boolean isSelectedByDefault() {
            return selectedByDefault;
        }
    }

    private final String path;
    private final String uuid;
    private final String title;
    private final String format;
    private final long size;
    private final long timestamp;
    private final Reason reason;
    /** The entry kept in its place, so the user can see what this one is redundant with. */
    private final String keptPath;

    public PurgeCandidate(String path, String uuid, String title, String format, long size,
                          long timestamp, Reason reason, String keptPath) {
        this.path = path;
        this.uuid = uuid;
        this.title = title;
        this.format = format;
        this.size = size;
        this.timestamp = timestamp;
        this.reason = reason;
        this.keptPath = keptPath;
    }

    public String getPath() {
        return path;
    }

    public String getUuid() {
        return uuid;
    }

    public String getTitle() {
        return title;
    }

    public String getFormat() {
        return format;
    }

    public long getSize() {
        return size;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Reason getReason() {
        return reason;
    }

    public String getKeptPath() {
        return keptPath;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject()
                .put("path", path)
                .put("uuid", uuid)
                .put("format", format)
                .put("size", size)
                .put("timestamp", timestamp)
                .put("reason", reason.name())
                .put("selected", reason.isSelectedByDefault())
                .put("keptPath", keptPath);
        if (title != null) {
            json.put("title", title);
        }
        return json;
    }
}
