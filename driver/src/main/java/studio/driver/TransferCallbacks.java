/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where the drivers call their transfer listeners: a single daemon thread, so that callbacks run in
 * the order they were issued. Off the copying thread, as before, so that a slow listener does not
 * slow the copy; but no longer on the common pool, where a progress could be delivered after the
 * completion of the same transfer, or a later progress before an earlier one.
 */
public final class TransferCallbacks {

    public static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "storyteller-transfer-callbacks");
        thread.setDaemon(true);
        return thread;
    });

    private TransferCallbacks() {
    }
}
