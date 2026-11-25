/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.metadata;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import studio.metadata.utils.DatabaseUpdateStatusHolder;

public class DatabaseMetadataService {

    private static final Logger LOGGER = Logger.getLogger(DatabaseMetadataService.class.getName());

    public static final String OFFICIAL_DB_PROP = "studio.db.official";
    public static final String OFFICIAL_DB_JSON_PATH = "/.studio/db/official.json";
    public static final String THUMBNAILS_STORAGE_ROOT = "https://storage.googleapis.com/lunii-data-prod";
    public static final String LUNII_GUEST_TOKEN_URL = "https://server-auth-prod.lunii.com/guest/create";
    public static final String LUNII_PACKS_DATABASE_URL = "https://server-data-prod.lunii.com/v2/packs";
    public static final String UNOFFICIAL_DB_PROP = "studio.db.unofficial";
    public static final String UNOFFICIAL_DB_JSON_PATH = "/.studio/db/unofficial.json";
    /** Set to {@code false} to never download the official database (tests, offline setups). */
    public static final String OFFICIAL_DB_REFRESH_PROP = "studio.db.official.refresh";

    /** Age after which the official database is downloaded again. */
    static final long OFFICIAL_DB_MAX_AGE_MS = TimeUnit.HOURS.toMillis(24);
    /** Delay before trying again after a failed download, or while the database is empty. */
    static final long OFFICIAL_DB_RETRY_MS = TimeUnit.MINUTES.toMillis(15);

    /**
     * Official packs, by lower-case UUID. Only ever grows during a run: a pack withdrawn from the
     * Luniistore is still an official pack, and forgetting it would open the transfer guard.
     */
    private final Map<String, JsonObject> cachedOfficialDatabase = new ConcurrentHashMap<>();
    /** When the official database was last known to be up to date (file time or download), 0 if never. */
    private volatile long officialDatabaseUpdatedAt;
    /** When a download was last attempted, 0 if never. */
    private volatile long lastOfficialFetchAttempt;
    private ScheduledExecutorService officialRefresher;

    private final boolean isAgent;
    /** Unofficial database as last read or written, and the file attributes it matches (guarded by this). */
    private JsonObject unofficialCache;
    private long unofficialCacheSize = -1;
    private FileTime unofficialCacheTime;

    public DatabaseMetadataService(boolean isAgent) {
        this.isAgent = isAgent;
        // Read and cache official database. Never downloaded here: the constructor runs at startup,
        // which must not wait on the network (offline, Lunii servers down). A missing, invalid or old
        // file is downloaded in the background, see startOfficialDatabaseRefresh().
        if (!isAgent) {
            LOGGER.fine("Reading and caching official metadata database");
            Optional<JsonObject> packsRoot = readOfficialDatabaseFile();
            if (packsRoot.isPresent()) {
                cacheOfficialEntries(packsRoot.get());
                try {
                    officialDatabaseUpdatedAt = Files.getLastModifiedTime(Paths.get(officialDatabasePath())).toMillis();
                } catch (IOException e) {
                    officialDatabaseUpdatedAt = 0;
                }
            }
        }
        // Initialize empty unofficial database if needed
        String databasePath = System.getProperty(UNOFFICIAL_DB_PROP, System.getProperty("user.home") + UNOFFICIAL_DB_JSON_PATH);
        File unofficialDatabase = new File(databasePath);
        if (!unofficialDatabase.exists() || !unofficialDatabase.isFile()) {
            try {
                try (FileWriter fileWriter = new FileWriter(databasePath)) {
                    fileWriter.write("{}");
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to initialize unofficial metadata database", e);
                throw new IllegalStateException("Failed to initialize unofficial metadata database");
            }
        } else if (!isAgent) {
            // Otherwise clear unofficial database from official packs metadata
            this.cleanUnofficialDatabase();
        }
    }

    public Optional<DatabasePackMetadata> getPackMetadata(String uuid) {
        LOGGER.fine("Fetching metadata for pack: " + uuid);
        Optional<DatabasePackMetadata> metadata = this.getOfficialMetadata(uuid);
        if (!metadata.isPresent()) {
            metadata = this.getUnofficialMetadata(uuid);
        }
        return metadata;
    }

    /**
     * Metadata of an official pack. An entry of the official database that lacks some fields (no
     * locale, no localized infos, a null title...) still answers, as an official pack with whatever
     * could be read: it comes from the Lunii servers and is not validated, and one bad entry used
     * to throw out of every listing that met its pack.
     */
    public Optional<DatabasePackMetadata> getOfficialMetadata(String uuid) {
        LOGGER.fine("Fetching metadata from official database for pack: " + uuid);
        if (uuid == null) {
            return Optional.empty();
        }
        JsonObject packMetadata = cachedOfficialDatabase.get(uuid.toLowerCase(Locale.ROOT));
        if (packMetadata == null) {
            return Optional.empty();
        }
        String title = null;
        String description = null;
        String thumbnail = null;
        try {
            JsonObject localizedInfos = localizedInfos(packMetadata);
            if (localizedInfos != null) {
                title = string(localizedInfos, "title");
                description = string(localizedInfos, "description");
                JsonObject image = object(localizedInfos, "image");
                String imageUrl = image == null ? null : string(image, "image_url");
                thumbnail = imageUrl == null ? null : THUMBNAILS_STORAGE_ROOT + imageUrl;
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Incomplete entry in the official metadata database for pack " + uuid, e);
        }
        if (title == null) {
            LOGGER.warning("Incomplete entry in the official metadata database for pack " + uuid + ": no title");
        }
        return Optional.of(new DatabasePackMetadata(uuid, title, description, thumbnail, true));
    }

    /** The localized infos of an official entry, French first (FIXME handle multiple locales), or null. */
    private static JsonObject localizedInfos(JsonObject packMetadata) {
        JsonObject byLocale = object(packMetadata, "localized_infos");
        if (byLocale == null) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add("fr_FR");
        JsonObject localesAvailable = object(packMetadata, "locales_available");
        if (localesAvailable != null) {
            candidates.addAll(localesAvailable.keySet());
        }
        candidates.addAll(byLocale.keySet());
        for (String locale : candidates) {
            JsonObject infos = object(byLocale, locale);
            if (infos != null) {
                return infos;
            }
        }
        return null;
    }

    private static JsonObject object(JsonObject parent, String member) {
        JsonElement element = parent.get(member);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject parent, String member) {
        JsonElement element = parent.get(member);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /** Whether the official database lists {@code uuid}, ignoring case. */
    public boolean isOfficialPack(String uuid) {
        LOGGER.fine("Looking in official database for pack: " + uuid);
        return uuid != null && cachedOfficialDatabase.containsKey(uuid.toLowerCase(Locale.ROOT));
    }

    /** Whether the official database holds anything at all (it is empty until a first download succeeds). */
    public boolean isOfficialDatabaseAvailable() {
        return !cachedOfficialDatabase.isEmpty();
    }

    // ------------------------------------------------- official database, kept up to date

    private static String officialDatabasePath() {
        // Path may be overridden by system property `studio.db.official`
        return System.getProperty(OFFICIAL_DB_PROP, System.getProperty("user.home") + OFFICIAL_DB_JSON_PATH);
    }

    /** The packs of the official database file, or empty if it is missing or invalid (logged). */
    private static Optional<JsonObject> readOfficialDatabaseFile() {
        String databasePath = officialDatabasePath();
        try (Reader databaseReader = new InputStreamReader(new FileInputStream(databasePath), Charset.defaultCharset())) {
            JsonElement parsed = JsonParser.parseReader(databaseReader);
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("Root of the official metadata database is not an object");
            }
            JsonObject officialRoot = parsed.getAsJsonObject();
            // Support newer file format which has an additional wrapper: { "code": "0.0", "response": { ...
            JsonElement response = officialRoot.get("response");
            if (response != null) {
                if (!response.isJsonObject()) {
                    throw new JsonParseException("Failed to get json root node");
                }
                return Optional.of(response.getAsJsonObject());
            }
            return Optional.of(officialRoot);
        } catch (FileNotFoundException e) {
            LOGGER.log(Level.WARNING, "Missing official metadata database file: it will be downloaded in the background");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read official metadata database file", e);
        } catch (JsonParseException | IllegalStateException e) {
            // Graceful failure on invalid file content
            LOGGER.log(Level.WARNING, "Official metadata database file is invalid", e);
        }
        return Optional.empty();
    }

    /** Adds the packs of an official database to the cache. An entry that is not an object is skipped. */
    private void cacheOfficialEntries(JsonObject packsRoot) {
        for (Map.Entry<String, JsonElement> entry : packsRoot.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                LOGGER.warning("Skipped an official metadata database entry that is not an object: " + entry.getKey());
                continue;
            }
            JsonObject packMetadata = entry.getValue().getAsJsonObject();
            String uuid = string(packMetadata, "uuid");
            // Without its uuid field, the key of the entry is the best guess: an entry kept too
            // many is a pack refused, one left out is a pack let through
            cachedOfficialDatabase.put((uuid != null ? uuid : entry.getKey()).toLowerCase(Locale.ROOT), packMetadata);
        }
    }

    /**
     * Downloads the official database in the background now if it is missing, empty or older than a
     * day, then checks again periodically (every {@link #OFFICIAL_DB_RETRY_MS}): never blocks the
     * caller, and a failed download (offline) is only logged and tried again later. Does nothing for
     * the agent, or when the system property {@link #OFFICIAL_DB_REFRESH_PROP} is {@code false}.
     */
    public synchronized void startOfficialDatabaseRefresh() {
        if (isAgent || officialRefresher != null || "false".equalsIgnoreCase(System.getProperty(OFFICIAL_DB_REFRESH_PROP))) {
            return;
        }
        officialRefresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "official-metadata-refresh");
            thread.setDaemon(true);
            return thread;
        });
        officialRefresher.scheduleWithFixedDelay(() -> {
            try {
                refreshOfficialDatabaseIfStale(System.currentTimeMillis());
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Failed to refresh the official metadata database", e);
            }
        }, 0, OFFICIAL_DB_RETRY_MS, TimeUnit.MILLISECONDS);
    }

    /** Stops the background refresh started by {@link #startOfficialDatabaseRefresh()}. */
    public synchronized void stopOfficialDatabaseRefresh() {
        if (officialRefresher != null) {
            officialRefresher.shutdownNow();
            officialRefresher = null;
        }
    }

    /**
     * Downloads the official database if it is empty or older than {@link #OFFICIAL_DB_MAX_AGE_MS},
     * at most once every {@link #OFFICIAL_DB_RETRY_MS}.
     *
     * @return whether a download was attempted
     */
    boolean refreshOfficialDatabaseIfStale(long now) {
        boolean stale = cachedOfficialDatabase.isEmpty() || now - officialDatabaseUpdatedAt >= OFFICIAL_DB_MAX_AGE_MS;
        if (!stale || (lastOfficialFetchAttempt != 0 && now - lastOfficialFetchAttempt < OFFICIAL_DB_RETRY_MS)) {
            return false;
        }
        lastOfficialFetchAttempt = now;
        Optional<JsonObject> downloaded = downloadOfficialDatabase();
        if (downloaded.isPresent()) {
            LOGGER.info("Fetched metadata, updating local database");
            replaceOfficialDatabase(downloaded.get());
            officialDatabaseUpdatedAt = now;
            DatabaseUpdateStatusHolder.lastOfficialDatabaseUpdate = now;
            // Packs that have become official no longer belong to the unofficial database
            cleanUnofficialDatabase();
        }
        return true;
    }

    public synchronized Optional<DatabasePackMetadata> getUnofficialMetadata(String uuid) {
        LOGGER.fine("Fetching metadata from unofficial database for pack: " + uuid);
        // Served from memory: the file (path may be overridden by system property `studio.db.unofficial`)
        // is read again only when it has changed on disk since it was last read or written here.
        JsonObject unofficialRoot = loadUnofficialDatabase();
        if (unofficialRoot != null && unofficialRoot.has(uuid) && unofficialRoot.get(uuid).isJsonObject()) {
            JsonObject packMetadata = unofficialRoot.getAsJsonObject(uuid);
            return Optional.of(new DatabasePackMetadata(
                    uuid,
                    Optional.ofNullable(packMetadata.get("title")).map(JsonElement::getAsString).orElse(null),
                    Optional.ofNullable(packMetadata.get("description")).map(JsonElement::getAsString).orElse(null),
                    Optional.ofNullable(packMetadata.get("image")).map(JsonElement::getAsString).orElse(null),
                    false
            ));
        }

        // Missing metadata
        return Optional.empty();
    }

    /**
     * Adds a downloaded official database to the cache and to the file. Entries of the file that the
     * download no longer lists are kept: a pack withdrawn from the Luniistore is still official.
     */
    private void replaceOfficialDatabase(JsonObject json) {
        // The cache first: even if the file cannot be written, this run knows the packs
        cacheOfficialEntries(json);
        try {
            JsonObject merged = new JsonObject();
            readOfficialDatabaseFile().ifPresent(previous -> previous.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue())));
            json.entrySet().forEach(e -> merged.add(e.getKey(), e.getValue()));
            writeDatabaseFile(Paths.get(officialDatabasePath()), merged);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to update official metadata database file", e);
        }
    }

    /**
     * The official database from the Lunii servers (the "response" object), or empty if it could not
     * be downloaded (logged). Overridden by tests, which never reach the network.
     */
    protected Optional<JsonObject> downloadOfficialDatabase() {
        try {
            LOGGER.fine("Fetching official metadata database");
            // Get a guest token
            URL tokenUrl = new URL(LUNII_GUEST_TOKEN_URL);
            HttpURLConnection tokenConnection = (HttpURLConnection) tokenUrl.openConnection();
            tokenConnection.setRequestMethod("GET");
            tokenConnection.setConnectTimeout(10000);
            tokenConnection.setReadTimeout(10000);
            int tokenStatusCode = tokenConnection.getResponseCode();
            if (tokenStatusCode == 200) {
                // OK, read response body
                InputStream inputStream = tokenConnection.getInputStream();
                String token;
                try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    // Extract token from response body
                    JsonObject tokenJson = JsonParser.parseReader(new JsonReader(bufferedReader)).getAsJsonObject();
                    token = tokenJson.getAsJsonObject("response").getAsJsonObject("token").get("server").getAsString();
                }
                LOGGER.fine("Guest token: " + token);

                // Call service to fetch metadata for all packs
                URL url = new URL(LUNII_PACKS_DATABASE_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("X-AUTH-TOKEN", token);
                int statusCode = connection.getResponseCode();
                if (statusCode == 200) {
                    // OK, read response body
                    inputStream = connection.getInputStream();
                    JsonObject response;
                    try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                        // Extract metadata database from response body
                        JsonObject json = JsonParser.parseReader(new JsonReader(bufferedReader)).getAsJsonObject();
                        response = json.get("response").getAsJsonObject();
                    }
                    if (response.size() == 0) {
                        LOGGER.log(Level.SEVERE, "Fetched an empty official metadata database, ignored");
                        return Optional.empty();
                    }
                    return Optional.of(response);
                } else {
                    LOGGER.log(Level.SEVERE, "Failed to fetch official metadata database. Status code: " + statusCode);
                }
            } else {
                LOGGER.log(Level.SEVERE, "Failed to fetch guest token. Status code: " + tokenStatusCode);
            }
        } catch (IOException | RuntimeException e) {
            // RuntimeException: a body that is not the JSON expected (JsonParseException,
            // IllegalStateException, NullPointerException on a missing member)
            LOGGER.log(Level.SEVERE, "Failed to fetch official metadata database.", e);
        }
        return Optional.empty();
    }

    public synchronized void refreshUnofficialMetadata(DatabasePackMetadata meta) {
        // Refresh unofficial database only if the pack isn't an official one
        if (this.getOfficialMetadata(meta.getUuid()).isPresent()) {
            return;
        }
        JsonObject unofficialRoot = loadUnofficialDatabase();
        if (unofficialRoot == null) {
            LOGGER.log(Level.SEVERE, "Unofficial metadata database unavailable, metadata of pack " + meta.getUuid() + " not saved");
            return;
        }

        // Replace or add pack metadata
        JsonObject value = new JsonObject();
        value.addProperty("uuid", meta.getUuid());
        if (meta.getTitle() != null) {
            value.addProperty("title", meta.getTitle());
        }
        if (meta.getDescription() != null) {
            value.addProperty("description", meta.getDescription());
        }
        if (meta.getThumbnail() != null) {
            value.addProperty("image", meta.getThumbnail());
        }
        // Every library listing refreshes every pack: the file is rewritten only when something changed
        if (value.equals(unofficialRoot.get(meta.getUuid()))) {
            return;
        }
        unofficialRoot.add(meta.getUuid(), value);
        saveUnofficialDatabase(unofficialRoot);
    }

    public synchronized void cleanUnofficialDatabase() {
        LOGGER.fine("Cleaning unofficial database.");
        // Remove official packs from unofficial metadata database file
        JsonObject unofficialRoot = loadUnofficialDatabase();
        if (unofficialRoot == null) {
            return;
        }
        List<String> toClean = new ArrayList<>();
        for (String uuid : unofficialRoot.keySet()) {
            if (this.isOfficialPack(uuid)) {
                toClean.add(uuid);
            }
        }
        if (toClean.isEmpty()) {
            return;
        }
        for (String uuid : toClean) {
            unofficialRoot.remove(uuid);
        }
        saveUnofficialDatabase(unofficialRoot);
    }

    // ------------------------------------------------- unofficial database, in memory

    private static String unofficialDatabasePath() {
        return System.getProperty(UNOFFICIAL_DB_PROP, System.getProperty("user.home") + UNOFFICIAL_DB_JSON_PATH);
    }

    /**
     * The unofficial database, from memory while the file on disk keeps the size and modification time
     * it had when last read or written here (the agent reads it from another process, and a user may
     * edit it by hand).
     *
     * <p>A file that does not parse is moved aside as {@code <name>.corrupt-<timestamp>} and replaced
     * by an empty database, with a log: it used to be an uncaught exception at startup, which kept
     * STUdio from starting at all. The agent, which runs inside another application, never moves
     * anything: it just sees an empty database.
     *
     * @return the database (mutable, owned by this instance), or null if the file cannot be read
     */
    private JsonObject loadUnofficialDatabase() {
        Path path = Paths.get(unofficialDatabasePath());
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Missing unofficial metadata database file", e);
            invalidateUnofficialCache();
            return null;
        }
        if (unofficialCache != null && attributes.size() == unofficialCacheSize
                && attributes.lastModifiedTime().equals(unofficialCacheTime)) {
            return unofficialCache;
        }
        JsonObject root;
        // Default charset, as the file has always been written with (FileWriter)
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), Charset.defaultCharset())) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new JsonParseException("Root of the unofficial metadata database is not an object");
            }
            root = parsed.getAsJsonObject();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to read unofficial metadata database file", e);
            invalidateUnofficialCache();
            return null;
        } catch (JsonParseException | IllegalStateException e) {
            if (isAgent) {
                LOGGER.log(Level.SEVERE, "Unofficial metadata database file is invalid", e);
                invalidateUnofficialCache();
                return new JsonObject();
            }
            root = new JsonObject();
            if (quarantineCorruptDatabase(path, e)) {
                saveUnofficialDatabase(root);
            } else {
                // Not moved aside: the file is left as it is (never overwritten without a copy), and
                // this run works from an empty database in memory
                invalidateUnofficialCache();
            }
            return root;
        }
        unofficialCache = root;
        unofficialCacheSize = attributes.size();
        unofficialCacheTime = attributes.lastModifiedTime();
        return root;
    }

    /** Moves an invalid database aside. Whether it was: if not, it must not be overwritten. */
    private boolean quarantineCorruptDatabase(Path path, Exception cause) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path backup = path.resolveSibling(path.getFileName() + ".corrupt-" + stamp);
        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.log(Level.SEVERE, "Unofficial metadata database file is invalid: moved to " + backup
                    + ", starting from an empty database", cause);
            return true;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unofficial metadata database file is invalid and could not be moved to "
                    + backup + " (" + e.getMessage() + "): it is left untouched, and this run uses an empty database", cause);
            return false;
        }
    }

    private void saveUnofficialDatabase(JsonObject root) {
        Path path = Paths.get(unofficialDatabasePath());
        try {
            writeDatabaseFile(path, root);
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            unofficialCache = root;
            unofficialCacheSize = attributes.size();
            unofficialCacheTime = attributes.lastModifiedTime();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to update unofficial metadata database file", e);
            // What is in memory may now differ from the file: read the file again next time
            invalidateUnofficialCache();
        }
    }

    private void invalidateUnofficialCache() {
        unofficialCache = null;
        unofficialCacheTime = null;
        unofficialCacheSize = -1;
    }

    /**
     * Writes the whole file to a temporary file of the same directory, then moves it over the
     * database: a crash or a full disk during the write leaves the previous database intact instead
     * of a truncated one.
     */
    static void writeDatabaseFile(Path databasePath, JsonObject json) throws IOException {
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        byte[] content = gson.toJson(json).getBytes(Charset.defaultCharset());
        Path absolute = databasePath.toAbsolutePath();
        Path tmp = Files.createTempFile(absolute.getParent(), absolute.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

}
