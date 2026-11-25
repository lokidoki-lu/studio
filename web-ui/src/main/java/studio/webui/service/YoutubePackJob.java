/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * The state of one pack creation, as the dialog polls it. Written by the job's thread, read by
 * request handlers: every accessor is synchronized.
 */
public final class YoutubePackJob {

    public enum State { RUNNING, DONE, FAILED, CANCELLED }

    public enum Step { CHECKING, ANALYSING, DOWNLOADING, CONVERTING, VOICES, WRITING, FINISHED }

    private static final int MAX_LOG_LINES = 200;

    private final String id = UUID.randomUUID().toString();
    private final long startedAt = System.currentTimeMillis();
    private State state = State.RUNNING;
    private Step step = Step.CHECKING;
    private double progress;
    private int current;
    private int total;
    private String currentTitle;
    private final Deque<String> log = new ArrayDeque<>();
    private int warnings;
    private String errorCode;
    private String errorMessage;
    private JsonObject result;
    private long endedAt;
    private volatile boolean cancelRequested;

    public String getId() {
        return id;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    void requestCancel() {
        cancelRequested = true;
    }

    synchronized void step(Step step, double progress) {
        this.step = step;
        this.progress = Math.max(this.progress, Math.min(1, progress));
        this.current = 0;
        this.total = 0;
        this.currentTitle = null;
    }

    synchronized void item(int current, int total, String title) {
        this.current = current;
        this.total = total;
        this.currentTitle = title;
    }

    synchronized void progress(double progress) {
        this.progress = Math.max(this.progress, Math.min(1, progress));
    }

    synchronized void log(String line) {
        log.addLast(line);
        while (log.size() > MAX_LOG_LINES) {
            log.removeFirst();
        }
    }

    synchronized void warn(String line) {
        warnings++;
        log("! " + line);
    }

    synchronized void done(JsonObject result) {
        this.state = State.DONE;
        this.step = Step.FINISHED;
        this.progress = 1;
        this.result = result;
        this.endedAt = System.currentTimeMillis();
    }

    synchronized void fail(YoutubePackException e) {
        this.state = e.getCode() == YoutubePackException.Code.CANCELLED ? State.CANCELLED : State.FAILED;
        this.errorCode = e.getCode().name();
        this.errorMessage = e.getMessage();
        this.endedAt = System.currentTimeMillis();
        log((state == State.CANCELLED ? "Annul\u00e9 : " : "\u00c9chec : ") + e.getMessage());
    }

    public synchronized long getEndedAt() {
        return endedAt;
    }

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject()
                .put("id", id)
                .put("state", state.name())
                .put("step", step.name())
                .put("progress", Math.round(progress * 1000) / 1000.0)
                .put("current", current)
                .put("total", total)
                .put("currentTitle", currentTitle)
                .put("warnings", warnings)
                .put("startedAt", startedAt)
                .put("cancelRequested", cancelRequested)
                .put("log", new JsonArray(new java.util.ArrayList<>(log)));
        if (errorCode != null) {
            o.put("error", errorCode).put("message", errorMessage);
        }
        if (result != null) {
            o.put("result", result.copy());
        }
        return o;
    }
}
