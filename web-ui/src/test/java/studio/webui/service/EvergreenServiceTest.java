/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The announce and the latest release always answer, whatever GitHub sends back: a rate limit (403
 * with a JSON object), an HTML page, a body of the wrong shape. GitHub is a local server here.
 */
class EvergreenServiceTest {

    /** Path (with query) -> status and body. */
    private final Map<String, Object[]> answers = new ConcurrentHashMap<>();
    private Vertx vertx;
    private HttpServer server;
    private EvergreenService service;

    @BeforeEach
    void start() throws Exception {
        vertx = Vertx.vertx();
        CompletableFuture<HttpServer> started = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(request -> {
            Object[] answer = answers.get(request.uri());
            if (answer == null) {
                request.response().setStatusCode(404).end("{\"message\": \"Not Found\"}");
            } else {
                request.response().setStatusCode((Integer) answer[0]).end((String) answer[1]);
            }
        }).listen(0, "127.0.0.1", ar -> {
            if (ar.succeeded()) {
                started.complete(ar.result());
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        server = started.get(10, TimeUnit.SECONDS);
        service = new EvergreenService(vertx, "127.0.0.1", "127.0.0.1", server.actualPort(), false);
    }

    @AfterEach
    void stop() throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        vertx.close(ar -> closed.complete(null));
        closed.get(10, TimeUnit.SECONDS);
    }

    private static <T> Future<T> await(Future<T> future) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!future.isComplete() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(future.isComplete(), "the future must be completed, not left pending");
        return future;
    }

    private static final String RATE_LIMITED = "{\"message\": \"API rate limit exceeded for 1.2.3.4.\"}";

    @Test
    @DisplayName("announce fails, rather than hanging, when GitHub is rate limited (403 and a JSON object)")
    void announceRateLimited() throws Exception {
        answers.put(EvergreenService.GITHUB_API_ANNOUNCE_COMMIT, new Object[]{403, RATE_LIMITED});
        assertTrue(await(service.announce()).failed());
    }

    @Test
    @DisplayName("announce fails, rather than hanging, on a 200 whose body is not the list of commits")
    void announceUnexpectedBody() throws Exception {
        answers.put(EvergreenService.GITHUB_API_ANNOUNCE_COMMIT, new Object[]{200, "{\"not\": \"an array\"}"});
        assertTrue(await(service.announce()).failed(), "an object");

        answers.put(EvergreenService.GITHUB_API_ANNOUNCE_COMMIT, new Object[]{200, "<html>portail captif</html>"});
        assertTrue(await(service.announce()).failed(), "HTML");

        answers.put(EvergreenService.GITHUB_API_ANNOUNCE_COMMIT, new Object[]{200, "[{\"commit\": 3}]"});
        assertTrue(await(service.announce()).failed(), "a commit of the wrong shape");
    }

    @Test
    @DisplayName("announce still answers the date and the content when GitHub does")
    void announceOk() throws Exception {
        answers.put(EvergreenService.GITHUB_API_ANNOUNCE_COMMIT,
                new Object[]{200, "[{\"commit\": {\"committer\": {\"date\": \"2024-01-02T03:04:05Z\"}}}]"});
        answers.put(EvergreenService.GITHUB_API_ANNOUNCE_CONTENT, new Object[]{200, "# Hello"});

        Future<JsonObject> announce = await(service.announce());
        assertTrue(announce.succeeded(), String.valueOf(announce.cause()));
        assertEquals("2024-01-02T03:04:05Z", announce.result().getString("date"));
        assertEquals("# Hello", announce.result().getString("content"));

        // The content itself unavailable: failed, not pending
        answers.remove(EvergreenService.GITHUB_API_ANNOUNCE_CONTENT);
        assertTrue(await(service.announce()).failed());
    }

    @Test
    @DisplayName("latest fails on a status other than 200, instead of answering GitHub's error as the release")
    void latestErrorStatus() throws Exception {
        answers.put(EvergreenService.GITHUB_API_LATEST_RELEASE, new Object[]{403, RATE_LIMITED});
        assertTrue(await(service.latest()).failed());
    }

    @Test
    @DisplayName("latest fails, rather than hanging, on a body that is not JSON")
    void latestNotJson() throws Exception {
        answers.put(EvergreenService.GITHUB_API_LATEST_RELEASE, new Object[]{200, "<html>proxy</html>"});
        assertTrue(await(service.latest()).failed());
    }

    @Test
    @DisplayName("latest answers the release when GitHub does")
    void latestOk() throws Exception {
        answers.put(EvergreenService.GITHUB_API_LATEST_RELEASE, new Object[]{200, "{\"tag_name\": \"v1.2.3\"}"});
        Future<JsonObject> latest = await(service.latest());
        assertFalse(latest.failed(), String.valueOf(latest.cause()));
        assertEquals("v1.2.3", latest.result().getString("tag_name"));
    }
}
