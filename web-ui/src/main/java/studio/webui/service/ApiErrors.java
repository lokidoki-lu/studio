/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import studio.driver.StoryTellerException;

/**
 * Turns a refused device operation into an HTTP answer a client can act on.
 *
 * <p>Before this, every refusal was a bare 500 rendered by the generic error handler: a pack
 * already on the device, a device that is not plugged and a full card all looked the same, and a
 * script could only guess. The answer is now a status that says which kind of problem it is and a
 * JSON body: {@code {"success": false, "error": "<CODE>", "message": "<what happened>"}}.
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static int statusOf(StoryTellerException.Reason reason) {
        switch (reason) {
            case NO_DEVICE:
                return 503;
            case PACK_ALREADY_ON_DEVICE:
            case CONTENT_FOLDER_EXISTS:
            case FOLDER_STILL_REFERENCED:
                return 409;
            case PACK_NOT_ON_DEVICE:
            case LIBRARY_PACK_NOT_FOUND:
                return 404;
            case NOT_ENOUGH_SPACE:
                return 507;
            case INVALID_REQUEST:
                return 400;
            default:
                return 500;
        }
    }

    /** The stable error code sent to clients. It is the reason's name, except for unknown failures. */
    public static String codeOf(StoryTellerException.Reason reason) {
        return reason == StoryTellerException.Reason.UNKNOWN ? "INTERNAL_ERROR" : reason.name();
    }

    public static JsonObject body(Throwable failure) {
        StoryTellerException.Reason reason = StoryTellerException.reasonOf(failure);
        return new JsonObject()
                .put("success", false)
                .put("error", codeOf(reason))
                .put("message", TransferRegistry.messageOf(failure));
    }

    /** Ends the request with the status and body matching the failure. */
    public static void respond(RoutingContext ctx, Throwable failure) {
        StoryTellerException.Reason reason = StoryTellerException.reasonOf(failure);
        if (ctx.response().ended()) {
            return;
        }
        ctx.response()
                .setStatusCode(statusOf(reason))
                .putHeader("content-type", "application/json")
                .end(Json.encode(body(failure)));
    }
}
