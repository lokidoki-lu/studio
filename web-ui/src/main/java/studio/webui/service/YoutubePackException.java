/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

/** A refused request or a failed pack creation from YouTube; the message is meant for people. */
public class YoutubePackException extends RuntimeException {

    public enum Code {
        INVALID_REQUEST(400),
        NOT_FOUND(404),
        BUSY(409),
        TOOLS_MISSING(503),
        /** YouTube asks to prove that one is not a robot, or answers "too many requests". */
        BLOCKED(502),
        NOTHING_DOWNLOADED(502),
        CANCELLED(499),
        FAILED(500);

        public final int status;

        Code(int status) {
            this.status = status;
        }
    }

    private final Code code;

    public YoutubePackException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public YoutubePackException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
