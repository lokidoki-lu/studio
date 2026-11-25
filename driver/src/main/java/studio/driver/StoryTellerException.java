/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver;

public class StoryTellerException extends RuntimeException {

    /**
     * Why an operation was refused, for the failures a caller can act on. Everything else is
     * {@link #UNKNOWN}: the message still says what happened, but no client should branch on it.
     */
    public enum Reason {
        UNKNOWN,
        NO_DEVICE,
        PACK_ALREADY_ON_DEVICE,
        PACK_NOT_ON_DEVICE,
        NOT_ENOUGH_SPACE,
        CONTENT_FOLDER_EXISTS,
        FOLDER_STILL_REFERENCED,
        INVALID_REQUEST,
        LIBRARY_PACK_NOT_FOUND,
        /** A {@code .pi.new} left by an interrupted index write blocks every index change. */
        PACK_INDEX_PENDING,
        /** What a repair was asked for no longer matches what is on the device. */
        DEVICE_STATE_CHANGED,
        /** The operation does not exist for this kind of device (e.g. a firmware 1.x Lunii). */
        UNSUPPORTED_ON_DEVICE
    }

    private final Reason reason;

    public StoryTellerException(String message) {
        this(Reason.UNKNOWN, message, null);
    }

    public StoryTellerException(String message, Throwable cause) {
        this(Reason.UNKNOWN, message, cause);
    }

    public StoryTellerException(Throwable cause) {
        super(cause);
        this.reason = Reason.UNKNOWN;
    }

    public StoryTellerException(Reason reason, String message) {
        this(reason, message, null);
    }

    public StoryTellerException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason == null ? Reason.UNKNOWN : reason;
    }

    public Reason getReason() {
        return reason;
    }

    /**
     * The first explicit reason found along the cause chain, so that a failure wrapped by a
     * {@code CompletionException} or by a more general {@code StoryTellerException} still reports
     * what actually refused it.
     */
    public static Reason reasonOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof StoryTellerException && ((StoryTellerException) c).reason != Reason.UNKNOWN) {
                return ((StoryTellerException) c).reason;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return Reason.UNKNOWN;
    }
}
