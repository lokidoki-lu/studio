/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.client.HttpRequest;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;

import java.util.function.Consumer;

public class EvergreenService {

    public static final String GITHUB_API_FQDN = "api.github.com";
    public static final String GITHUB_RAW_FQDN = "raw.githubusercontent.com";
    /** Repository whose releases and announce this build follows (the fork, not the upstream). */
    public static final String GITHUB_REPOSITORY = "lokidoki-lu/studio";
    public static final String GITHUB_API_ROOT = "/repos/" + GITHUB_REPOSITORY;
    public static final String GITHUB_API_LATEST_RELEASE = GITHUB_API_ROOT + "/releases/latest";
    public static final String GITHUB_API_ANNOUNCE_COMMIT = GITHUB_API_ROOT + "/commits?path=ANNOUNCE.md";
    public static final String GITHUB_API_ANNOUNCE_CONTENT = "/" + GITHUB_REPOSITORY + "/master/ANNOUNCE.md";

    public static final String GITHUB_API_BUILTIN_ANNOUNCE_CONTENT_EN = "### Good news, everyone!\n\nSTUdio is improving, at a slow but steady pace, and that's primarily thanks to your feedback. Thanks for letting me know what's missing or broken (and also what's not \uD83D\uDE01)!\n\nTo better benefit from this feedback, I wanted a way to communicate directly to you and let you know how STUdio is evolving. So here it is: **a brand new announce mechanism!**\n\nI plan to use it sparsely, to announce major features and occasionally request your feedback (a beta version will be released soon). These announces **are only displayed once**, and **you can opt-out if you feel like it**.\n\nHere's to all the great story packs you're building! \uD83C\uDF7B";
    public static final String GITHUB_API_BUILTIN_ANNOUNCE_CONTENT_FR = "### Good news, everyone!\n\nSTUdio s'améliore, lentement mais sûrement, et le mérite en revient grandement à tous vos retours. Merci de me faire savoir ce qu'il manque ou ce qui est cassé (et aussi ce qui ne l'est pas \uD83D\uDE01) !\n\nPour tirer avantage au mieux de vos retours, je souhaitais pouvoir communiquer directement avec vous pour vous faire part des évolutions de STUdio. Alors le voici : **le tout nouveau mécanisme d'annonces !**\n\nJe prévois de l'utiliser avec parcimonie, pour annoncer les fonctionnalités majeures et faire appel à vous occasionnellement (une version bêta va bientôt voir le jour). Ces annonces **ne s'afficheront qu'une fois**, et **vous pouvez les désactiver si vous le souhaitez**.\n\nÀ tous les packs d'histoires que vous créerez ! \uD83C\uDF7B";

    private final Logger LOGGER = LoggerFactory.getLogger(EvergreenService.class);

    /** No answer from GitHub after this long fails the request, instead of leaving it pending. */
    static final long REQUEST_TIMEOUT_MS = 15_000;

    private ConfigRetriever configRetriever;
    private WebClient webClient;
    private final String apiHost;
    private final String rawHost;
    private final int port;
    private final boolean ssl;

    public EvergreenService(Vertx vertx) {
        this(vertx, GITHUB_API_FQDN, GITHUB_RAW_FQDN, 443, true);
    }

    /** For tests: GitHub replaced by a local server. */
    EvergreenService(Vertx vertx, String apiHost, String rawHost, int port, boolean ssl) {
        this.apiHost = apiHost;
        this.rawHost = rawHost;
        this.port = port;
        this.ssl = ssl;
        ConfigStoreOptions propertiesStore = new ConfigStoreOptions()
                .setType("file")
                .setFormat("properties")
                .setConfig(new JsonObject().put("path", "evergreen.properties"));
        ConfigRetrieverOptions options = new ConfigRetrieverOptions().addStore(propertiesStore);
        configRetriever = ConfigRetriever.create(vertx, options);
        webClient = WebClient.create(vertx);
    }

    public Future<JsonObject> infos() {
        Future<JsonObject> future = Future.future();
        configRetriever.getConfig(ar -> {
            if (ar.succeeded()) {
                future.tryComplete(ar.result());
            } else {
                future.tryFail(ar.cause());
            }
        });
        return future;
    }

    public Future<JsonObject> latest() {
        // Latest available release (from github)
        Future<JsonObject> future = Future.future();
        get(apiHost, GITHUB_API_LATEST_RELEASE).send(ar -> handle(ar, future, "latest release", response -> {
            JsonObject release = response.bodyAsJsonObject();
            if (release == null) {
                throw new IllegalStateException("GitHub answered an empty body for the latest release");
            }
            future.tryComplete(release);
        }));
        return future;
    }

    public Future<JsonObject> announce() {
        Future<JsonObject> future = Future.future();
        // Get announce's last modification date (from github)
        get(apiHost, GITHUB_API_ANNOUNCE_COMMIT).send(ar -> handle(ar, future, "announce commits", response -> {
            JsonArray commits = response.bodyAsJsonArray();
            if (commits == null || commits.size() == 0) {
                 // First announce
                future.tryComplete(new JsonObject()
                        .put("date", "2020-05-12T00:00:00.000Z")
                        .put("content", GITHUB_API_BUILTIN_ANNOUNCE_CONTENT_EN + "\n\n-----\n\n" + GITHUB_API_BUILTIN_ANNOUNCE_CONTENT_FR)
                );
                return;
            }
            // ClassCastException or NullPointerException on an unexpected shape: failed by handle()
            String commitDate = commits.getJsonObject(0).getJsonObject("commit").getJsonObject("committer").getString("date");
            if (commitDate == null) {
                throw new IllegalStateException("GitHub answered a commit without a date for the announce");
            }
            // Get announce content (from github)
            get(rawHost, GITHUB_API_ANNOUNCE_CONTENT).send(ar2 -> handle(ar2, future, "announce content", response2 ->
                    future.tryComplete(new JsonObject().put("date", commitDate).put("content", response2.bodyAsString()))));
        }));
        return future;
    }

    private HttpRequest<Buffer> get(String host, String path) {
        return webClient.get(port, host, path).ssl(ssl).timeout(REQUEST_TIMEOUT_MS);
    }

    /**
     * Completes {@code future} whatever GitHub answers: a failed request, a status other than 200
     * (403 when rate limited) or a body {@code onOk} cannot use (decode error, unexpected shape)
     * fails it. An exception thrown in a WebClient callback used to leave the future pending, and
     * the HTTP request waiting on it unanswered.
     */
    private void handle(AsyncResult<HttpResponse<Buffer>> ar, Future<JsonObject> future, String what,
                        Consumer<HttpResponse<Buffer>> onOk) {
        if (ar.failed()) {
            future.tryFail(ar.cause());
            return;
        }
        HttpResponse<Buffer> response = ar.result();
        if (response.statusCode() != 200) {
            LOGGER.warn("GitHub answered " + response.statusCode() + " for the " + what);
            future.tryFail(new IllegalStateException("GitHub answered " + response.statusCode() + " for the " + what));
            return;
        }
        try {
            onOk.accept(response);
        } catch (RuntimeException e) {
            LOGGER.warn("Unexpected answer from GitHub for the " + what + ": " + e.getMessage());
            future.tryFail(e);
        }
    }

}
