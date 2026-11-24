/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import studio.driver.event.TransferProgressListener;
import studio.driver.model.TransferStatus;

/**
 * Reports a transfer's life both ways: on the event bus, for the web UI, and in the
 * {@link TransferRegistry}, for anything that asks over HTTP. Both always say the same thing.
 *
 * <p>The {@code done} event keeps its {@code success} field and gains {@code error} and
 * {@code message} on failure, so the existing web UI keeps working and can show why.
 */
public class TransferNotifier {

    private final EventBus eventBus;
    private final TransferRegistry registry;

    public TransferNotifier(EventBus eventBus, TransferRegistry registry) {
        this.eventBus = eventBus;
        this.registry = registry;
    }

    public TransferRegistry registry() {
        return registry;
    }

    public String create(String uuid, String operation) {
        return registry.create(uuid, operation);
    }

    public void started(String transferId) {
        registry.started(transferId);
    }

    public void progress(String transferId, long transferred, long total) {
        if (!registry.progress(transferId, transferred, total)) {
            // Progress callbacks run asynchronously and may arrive after the verdict.
            return;
        }
        double p = total > 0 ? (double) transferred / (double) total : 0.0;
        send("storyteller.transfer." + transferId + ".progress", new JsonObject().put("progress", p));
    }

    public void succeeded(String transferId) {
        if (!registry.succeeded(transferId)) {
            return;
        }
        send("storyteller.transfer." + transferId + ".done", new JsonObject().put("success", true));
    }

    /** Fails every transfer not yet finished, as when the device is unplugged. */
    public void failAllUnfinished(Throwable cause) {
        for (String id : registry.unfinished()) {
            failed(id, cause);
        }
    }

    public void failed(String transferId, Throwable cause) {
        // Reported once: the queue giving up and the operation's own failure may both arrive.
        if (!registry.failed(transferId, cause)) {
            return;
        }
        JsonObject body = ApiErrors.body(cause);
        send("storyteller.transfer." + transferId + ".done", new JsonObject()
                .put("success", false)
                .put("error", body.getString("error"))
                .put("message", body.getString("message")));
    }

    /** A driver listener feeding progress into this notifier. Completion is reported by the caller. */
    public TransferProgressListener listener(String transferId) {
        return new TransferProgressListener() {
            @Override
            public void onProgress(TransferStatus status) {
                progress(transferId, status.getTransferred(), status.getTotal());
            }

            @Override
            public void onComplete(TransferStatus status) {
                // The caller decides when a transfer is over: for an upload, only once the pack index
                // has been written, which happens after the driver's own completion callback.
            }
        };
    }

    private void send(String address, JsonObject body) {
        if (eventBus != null) {
            eventBus.publish(address, body);
        }
    }
}
