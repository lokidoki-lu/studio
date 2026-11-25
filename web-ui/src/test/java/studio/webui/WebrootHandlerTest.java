/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The web-root handler: what it serves, and above all that it never does disk work on the event
 * loop. That last property is the reason it exists — Vert.x-Web 3.9's StaticHandler opened every file
 * on the event loop, and an antivirus holding that open for 5 to 12 s froze every request of the
 * server. The first test reproduces exactly that situation with a source that takes seconds, on a
 * server with a single event loop, and requires an unrelated request to be answered meanwhile.
 *
 * <p>A router on an ephemeral port rather than the real {@link MainVerticle}: the property under test
 * is the handler's, and this way it runs even while a STUdio holds port 8080.
 */
@DisplayName("Web-root handler")
class WebrootHandlerTest {

    private static final Duration SLOW_OPEN = Duration.ofSeconds(3);

    private Vertx vertx;
    private int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Files of the fake web root, by relative path. */
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> reads = new ConcurrentHashMap<>();
    private final List<Boolean> readOnEventLoop = new CopyOnWriteArrayList<>();
    private final CountDownLatch slowReadStarted = new CountDownLatch(1);
    private WebrootHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        files.put("index.html", "<html>index</html>".getBytes(StandardCharsets.UTF_8));
        files.put("static/js/main.0123abcd.chunk.js", "console.log(1)".getBytes(StandardCharsets.UTF_8));
        files.put("favicon.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        files.put("slow.js", "slow()".getBytes(StandardCharsets.UTF_8));

        WebrootHandler.Source source = relativePath -> {
            readOnEventLoop.add(Context.isOnEventLoopThread());
            reads.computeIfAbsent(relativePath, k -> new AtomicInteger()).incrementAndGet();
            if (relativePath.equals("slow.js")) {
                // What the antivirus did to RandomAccessFile.open in the field.
                slowReadStarted.countDown();
                try {
                    Thread.sleep(SLOW_OPEN.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Optional.ofNullable(files.get(relativePath));
        };

        // A single event loop, as in the field log: one blocked thread is then the whole server.
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        handler = new WebrootHandler(vertx, source);
        Router router = Router.router(vertx);
        router.get("/ping").handler(ctx -> ctx.response().end("pong"));
        router.route().handler(handler);

        CompletableFuture<HttpServer> started = new CompletableFuture<>();
        vertx.createHttpServer().requestHandler(router).listen(0, "127.0.0.1", ar -> {
            if (ar.succeeded()) {
                started.complete(ar.result());
            } else {
                started.completeExceptionally(ar.cause());
            }
        });
        port = started.get(10, TimeUnit.SECONDS).actualPort();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        CountDownLatch closed = new CountDownLatch(1);
        vertx.close(ar -> closed.countDown());
        closed.await(30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a file that takes seconds to open does not hold up other requests")
    void slowFileDoesNotFreezeTheServer() throws Exception {
        CompletableFuture<HttpResponse<String>> slow =
                client.sendAsync(request("/slow.js").build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(slowReadStarted.await(5, TimeUnit.SECONDS), "the slow read never started");

        long start = System.nanoTime();
        HttpResponse<String> ping = client.send(request("/ping").build(), HttpResponse.BodyHandlers.ofString());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals("pong", ping.body());
        assertTrue(elapsedMillis < SLOW_OPEN.toMillis() / 3,
                "a request was held up " + elapsedMillis + " ms behind the slow file: "
                        + "the event loop is doing the file's I/O");

        HttpResponse<String> slowResponse = slow.get(10, TimeUnit.SECONDS);
        assertEquals(200, slowResponse.statusCode());
        assertEquals("slow()", slowResponse.body());
        assertFalse(readOnEventLoop.contains(true), "a web-root file was read on the event loop");
    }

    @Test
    @DisplayName("each file is read once, then served from memory")
    void filesAreReadOnce() throws Exception {
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> response = get("/index.html");
            assertEquals(200, response.statusCode());
            assertEquals("<html>index</html>", response.body());
        }
        assertEquals(1, reads.get("index.html").get());
        assertTrue(handler.isCached("index.html"));
    }

    @Test
    @DisplayName("the root and directory paths serve index.html")
    void rootServesIndex() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertEquals("<html>index</html>", response.body());
        assertEquals("text/html;charset=UTF-8", response.headers().firstValue("content-type").orElse(""));
        assertEquals(WebrootHandler.relativePath("/static/"), "static/index.html");
    }

    @Test
    @DisplayName("a path that names nothing falls through to 404, and is not cached")
    void missingIsNotFound() throws Exception {
        assertEquals(404, get("/nothing-here.js").statusCode());
        assertFalse(handler.isCached("nothing-here.js"));
    }

    @Test
    @DisplayName("hashed assets are cached for good, the rest is revalidated by ETag")
    void cachingHeaders() throws Exception {
        HttpResponse<String> asset = get("/static/js/main.0123abcd.chunk.js");
        assertEquals(WebrootHandler.IMMUTABLE_CACHE_CONTROL, asset.headers().firstValue("cache-control").orElse(""));
        assertEquals("application/javascript;charset=UTF-8", asset.headers().firstValue("content-type").orElse(""));

        HttpResponse<String> index = get("/index.html");
        assertEquals(WebrootHandler.REVALIDATE_CACHE_CONTROL, index.headers().firstValue("cache-control").orElse(""));
        String etag = index.headers().firstValue("etag").orElseThrow();

        HttpResponse<String> revalidated = client.send(request("/index.html")
                .header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(304, revalidated.statusCode());
        assertEquals("", revalidated.body());

        HttpResponse<String> changed = client.send(request("/index.html")
                .header("If-None-Match", "\"something-else\"").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, changed.statusCode());
    }

    @Test
    @DisplayName("HEAD gives the length without the body; other methods are not served")
    void headAndOtherMethods() throws Exception {
        HttpResponse<String> head = client.send(request("/favicon.png")
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, head.statusCode());
        assertEquals("4", head.headers().firstValue("content-length").orElse(""));
        assertEquals("image/png", head.headers().firstValue("content-type").orElse(""));

        HttpResponse<String> post = client.send(request("/favicon.png")
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, post.statusCode());
    }

    @Test
    @DisplayName("the classpath source reads files from a jar, and refuses directories and absent paths")
    void classpathSourceInAJar(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("bundle.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("webroot/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("webroot/static/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("webroot/static/a.js"));
            out.write("a()".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("outside.txt"));
            out.write("outside".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            WebrootHandler.Source source = WebrootHandler.classpathSource(loader, "webroot");
            assertEquals("a()", new String(source.read("static/a.js").orElseThrow(), StandardCharsets.UTF_8));
            assertTrue(source.read("static").isEmpty(), "a directory is not a file");
            assertTrue(source.read("missing.js").isEmpty());
        }
    }

    @Test
    @DisplayName("the classpath source reads files from a folder, and refuses directories")
    void classpathSourceInAFolder(@TempDir Path dir) throws Exception {
        Path webroot = Files.createDirectories(dir.resolve("webroot/static"));
        Files.writeString(webroot.resolve("b.css"), "b{}");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            WebrootHandler.Source source = WebrootHandler.classpathSource(loader, "webroot");
            assertEquals("b{}", new String(source.read("static/b.css").orElseThrow(), StandardCharsets.UTF_8));
            assertTrue(source.read("static").isEmpty(), "a directory is not a file");
            assertTrue(source.read("nope.css").isEmpty());
        }
    }

    @Test
    @DisplayName("the real web root on the test classpath is served without the Vert.x file cache")
    void realWebRoot() throws IOException {
        // src/main/resources/webroot holds the favicon and the translations at test time; the React
        // bundle is only copied in at prepare-package.
        WebrootHandler.Source source = WebrootHandler.classpathSource(WebrootHandler.class.getClassLoader(), "webroot");
        assertTrue(source.read("favicon.png").isPresent());
        assertTrue(source.read("locales/fr/translation.json").isPresent());
        assertTrue(source.read("locales").isEmpty());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return client.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
    }
}
