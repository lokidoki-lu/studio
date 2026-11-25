/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.MimeMapping;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.RoutingContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Serves the web UI bundle without ever touching the disk on the event loop.
 *
 * <p>It replaces Vert.x-Web's {@code StaticHandler}, which cannot be configured to do that in 3.9.
 * Whatever its options, the stock handler ends in {@code HttpServerResponse.sendFile}, and in
 * Vert.x 3.9 {@code sendFile} resolves the file, stats it and opens it with a
 * {@code RandomAccessFile} synchronously, on the calling thread: the event loop. For a web root on
 * the classpath, the file it opens is the copy Vert.x extracted from the jar into
 * {@code %TEMP%\vertx-cache\file-cache-<random>} — a new folder at every start, in the most
 * scanned place on a Windows machine. When the antivirus holds that open (a scan backlog while a
 * build writes thousands of files, say), the open takes seconds, and during those seconds no
 * request of any client is answered: the whole UI freezes. Measured in the field at 5 to 12 s,
 * in {@code RandomAccessFile.open0} under {@code StaticHandlerImpl.sendFile}.
 * {@code setFilesReadOnly} and the file-props cache only spare the {@code stat}; the open stays.
 *
 * <p>So: the bundle is read straight from the classpath, on a worker, once per file, and kept in
 * memory. The event loop only ever writes a buffer it already holds. Nothing is extracted to the
 * Vert.x file cache, so there is no freshly written file for an antivirus to scan on first open.
 * Holding the bundle in memory is affordable because it is the frontend build and nothing else —
 * about 10 MB, source maps included — and it never changes while the server runs. Large content
 * (packs, audio) never comes through here: it is served by the API, from blocking handlers.
 *
 * <p>Behaviour kept from the stock handler: GET and HEAD only, {@code index.html} for a directory
 * path, and a path that names nothing (or a directory) goes to the next handler, which answers 404.
 * Added: an ETag with {@code If-None-Match}, and long-lived caching for {@code /static/}, whose
 * names carry a content hash; everything else is revalidated, so an upgrade is never served stale.
 * Not kept: range requests, which nothing in the bundle needs.
 */
public class WebrootHandler implements Handler<RoutingContext> {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebrootHandler.class);

    /** Assets whose file name carries a content hash, as the CRA build emits them. */
    static final String IMMUTABLE_PREFIX = "/static/";

    static final String IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";

    static final String REVALIDATE_CACHE_CONTROL = "no-cache";

    /** Where the bytes of a web-root file come from. Blocking; only ever called on a worker. */
    @FunctionalInterface
    public interface Source {
        /**
         * The content of a regular file of the web root, or empty when the path names nothing or a
         * directory.
         *
         * @param relativePath a path relative to the web root, without a leading slash
         */
        Optional<byte[]> read(String relativePath) throws IOException;
    }

    /** One file of the web root, ready to be written. */
    static final class Resource {
        final Buffer content;
        final String contentType;
        final String etag;

        Resource(byte[] bytes, String contentType) {
            this.content = Buffer.buffer(bytes);
            this.contentType = contentType;
            this.etag = etagOf(bytes);
        }
    }

    private final Vertx vertx;
    private final Source source;
    private final Map<String, Resource> cache = new ConcurrentHashMap<>();

    WebrootHandler(Vertx vertx, Source source) {
        this.vertx = vertx;
        this.source = source;
    }

    /** The web root at {@code webroot/} on this application's classpath. */
    public static WebrootHandler create(Vertx vertx) {
        return new WebrootHandler(vertx, classpathSource(WebrootHandler.class.getClassLoader(), "webroot"));
    }

    @Override
    public void handle(RoutingContext ctx) {
        HttpMethod method = ctx.request().method();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            ctx.next();
            return;
        }
        String path = ctx.normalisedPath();
        String relative = relativePath(path);

        Resource cached = cache.get(relative);
        if (cached != null) {
            respond(ctx, path, cached);
            return;
        }

        // Unordered: the browser asks for the bundle's files in parallel, and none depends on another.
        vertx.<Optional<Resource>>executeBlocking(promise -> {
            try {
                promise.complete(source.read(relative)
                        .map(bytes -> new Resource(bytes, contentTypeOf(relative))));
            } catch (IOException | RuntimeException e) {
                promise.fail(e);
            }
        }, false, result -> {
            if (result.failed()) {
                LOGGER.error("Failed to read web-root file " + relative, result.cause());
                ctx.fail(500);
                return;
            }
            Optional<Resource> resource = result.result();
            if (resource.isEmpty()) {
                ctx.next();
                return;
            }
            Resource loaded = cache.computeIfAbsent(relative, key -> resource.get());
            respond(ctx, path, loaded);
        });
    }

    /** Whether a file is already held in memory. For tests. */
    boolean isCached(String relativePath) {
        return cache.containsKey(relativePath);
    }

    private static void respond(RoutingContext ctx, String path, Resource resource) {
        HttpServerResponse response = ctx.response();
        if (response.closed() || response.ended()) {
            return;
        }
        response.putHeader(HttpHeaders.ETAG, resource.etag)
                .putHeader(HttpHeaders.CACHE_CONTROL, cacheControlOf(path));

        String ifNoneMatch = ctx.request().getHeader(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && matches(ifNoneMatch, resource.etag)) {
            response.setStatusCode(304).end();
            return;
        }
        response.putHeader(HttpHeaders.CONTENT_TYPE, resource.contentType);
        if (ctx.request().method() == HttpMethod.HEAD) {
            response.putHeader(HttpHeaders.CONTENT_LENGTH, String.valueOf(resource.content.length())).end();
        } else {
            response.end(resource.content);
        }
    }

    static String relativePath(String normalisedPath) {
        String relative = normalisedPath.startsWith("/") ? normalisedPath.substring(1) : normalisedPath;
        return relative.isEmpty() || relative.endsWith("/") ? relative + "index.html" : relative;
    }

    static String cacheControlOf(String path) {
        return path.startsWith(IMMUTABLE_PREFIX) ? IMMUTABLE_CACHE_CONTROL : REVALIDATE_CACHE_CONTROL;
    }

    static String contentTypeOf(String relativePath) {
        String type = MimeMapping.getMimeTypeForFilename(relativePath);
        if (type == null) {
            return "application/octet-stream";
        }
        boolean textual = type.startsWith("text/") || type.equals("application/javascript")
                || type.equals("application/json");
        return textual ? type + ";charset=UTF-8" : type;
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.trim();
            if (c.equals("*") || c.equals(etag) || c.equals("W/" + etag)) {
                return true;
            }
        }
        return false;
    }

    private static String etagOf(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder etag = new StringBuilder("\"");
            for (int i = 0; i < 12; i++) {
                etag.append(String.format("%02x", digest[i]));
            }
            return etag.append('"').toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required of every JVM", e);
        }
    }

    /**
     * Files under {@code root} on a class path, read in place: from the jar for the packaged
     * application, from {@code target/classes} in a build. Never through Vert.x's file resolver,
     * which would extract them to its cache first.
     */
    static Source classpathSource(ClassLoader classLoader, String root) {
        return relativePath -> {
            URL url = classLoader.getResource(root + "/" + relativePath);
            if (url == null) {
                return Optional.empty();
            }
            switch (url.getProtocol()) {
                case "file":
                    Path file;
                    try {
                        file = Paths.get(url.toURI());
                    } catch (URISyntaxException e) {
                        throw new IOException("Unreadable web-root location " + url, e);
                    }
                    return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
                case "jar":
                    // Uncached: the JVM-wide jar cache would keep the file open for good, which on
                    // Windows locks it. Each file is read once, so reopening the jar costs nothing.
                    JarURLConnection connection = (JarURLConnection) url.openConnection();
                    connection.setUseCaches(false);
                    try (JarFile jar = connection.getJarFile()) {
                        JarEntry entry = jar.getJarEntry(connection.getEntryName());
                        if (entry == null || entry.isDirectory()) {
                            return Optional.empty();
                        }
                        try (InputStream in = jar.getInputStream(entry)) {
                            return Optional.of(in.readAllBytes());
                        }
                    }
                default:
                    // Any other protocol: no way to tell a directory apart, read it as it comes.
                    try (InputStream in = url.openStream()) {
                        return Optional.of(in.readAllBytes());
                    }
            }
        };
    }
}
