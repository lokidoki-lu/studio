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
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseMetadataService {

    private static final Logger LOGGER = Logger.getLogger(DatabaseMetadataService.class.getName());

    public static final String OFFICIAL_DB_PROP = "studio.db.official";
    public static final String OFFICIAL_DB_JSON_PATH = "/.studio/db/official.json";
    public static final String THUMBNAILS_STORAGE_ROOT = "https://storage.googleapis.com/lunii-data-prod";
    public static final String LUNII_GUEST_TOKEN_URL = "https://server-auth-prod.lunii.com/guest/create";
    public static final String LUNII_PACKS_DATABASE_URL = "https://server-data-prod.lunii.com/v2/packs";
    public static final String UNOFFICIAL_DB_PROP = "studio.db.unofficial";
    public static final String UNOFFICIAL_DB_JSON_PATH = "/.studio/db/unofficial.json";

    private final Map<String, JsonObject> cachedOfficialDatabase;

    private final boolean isAgent;
    /** Unofficial database as last read or written, and the file attributes it matches (guarded by this). */
    private JsonObject unofficialCache;
    private long unofficialCacheSize = -1;
    private FileTime unofficialCacheTime;

    public DatabaseMetadataService(boolean isAgent) {
        this.isAgent = isAgent;
        // Read and cache official database
        cachedOfficialDatabase = new HashMap<>();
        if (!isAgent) {
            try {
                LOGGER.fine("Reading and caching official metadata database");
                // Read official metadata database file (path may be overridden by system property `studio.db.official`)
                String databasePath = System.getProperty(OFFICIAL_DB_PROP, System.getProperty("user.home") + OFFICIAL_DB_JSON_PATH);
                JsonObject officialRoot;
                try (FileReader databaseReader = new FileReader(databasePath)) {
                    officialRoot = JsonParser.parseReader(databaseReader).getAsJsonObject();   // throws IllegalStateException
                }
                // Support newer file format which has an additional wrapper: { "code": "0.0", "response": { ...
                final JsonObject packsRoot = (officialRoot.keySet().contains("response")) ? officialRoot.getAsJsonObject("response") : officialRoot;
                if (packsRoot == null) {
                    throw new IllegalStateException("Failed to get json root node");
                }
                // Go through all packs
                packsRoot.keySet().forEach(key -> {
                    JsonObject packMetadata = packsRoot.getAsJsonObject(key);
                    String uuid = packMetadata.get("uuid").getAsString();
                    cachedOfficialDatabase.put(uuid, packMetadata);
                });
            } catch (FileNotFoundException e) {
                LOGGER.log(Level.WARNING, "Missing official metadata database file", e);
                this.fetchOfficialDatabase();
            } catch (IOException e) {
                // Only reachable since the reader is closed: the read did not complete, so the
                // cache was not populated, and the same fallback applies as for a missing file.
                LOGGER.log(Level.WARNING, "Failed to read official metadata database file", e);
                this.fetchOfficialDatabase();
            } catch (JsonParseException|IllegalStateException e) {
                // Graceful failure on invalid file content
                LOGGER.log(Level.WARNING, "Official metadata database file is invalid", e);
                this.fetchOfficialDatabase();
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

    public Optional<DatabasePackMetadata> getOfficialMetadata(String uuid) {
        LOGGER.fine("Fetching metadata from official database for pack: " + uuid);
        return Optional.ofNullable(cachedOfficialDatabase.get(uuid)).map(packMetadata -> {
            // FIXME Handle multiple locales
            JsonObject localesAvailable = packMetadata.getAsJsonObject("locales_available");
            String locale = localesAvailable.keySet().contains("fr_FR") ? "fr_FR" : localesAvailable.keySet().stream().findFirst().get();
            JsonObject localizedInfos = packMetadata.getAsJsonObject("localized_infos").getAsJsonObject(locale);
            return new DatabasePackMetadata(
                    uuid,
                    localizedInfos.get("title").getAsString(),
                    localizedInfos.get("description").getAsString(),
                    THUMBNAILS_STORAGE_ROOT + localizedInfos.getAsJsonObject("image").get("image_url").getAsString(),
                    true
            );
        });
    }

    public boolean isOfficialPack(String uuid) {
        LOGGER.fine("Looking in official database for pack: " + uuid);
        return cachedOfficialDatabase.containsKey(uuid);
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

    private void replaceOfficialDatabase(JsonObject json) {
        // Update official database
        try {
            String databasePath = System.getProperty(OFFICIAL_DB_PROP, System.getProperty("user.home") + OFFICIAL_DB_JSON_PATH);
            writeDatabaseFile(Paths.get(databasePath), json);

            // Go through all packs to update cache
            json.keySet().forEach(key -> {
                JsonObject packMetadata = json.getAsJsonObject(key);
                String uuid = packMetadata.get("uuid").getAsString();
                cachedOfficialDatabase.put(uuid, packMetadata);
            });
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to update official metadata database file", e);
        }
    }

    private void fetchOfficialDatabase() {
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

                    // Try and update official database
                    LOGGER.info("Fetched metadata, updating local database");
                    this.replaceOfficialDatabase(response);
                } else {
                    LOGGER.log(Level.SEVERE, "Failed to fetch official metadata database. Status code: " + statusCode);
                }
            } else {
                LOGGER.log(Level.SEVERE, "Failed to fetch guest token. Status code: " + tokenStatusCode);
            }
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to fetch official metadata database.", e);
        }
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
            quarantineCorruptDatabase(path, e);
            root = new JsonObject();
            saveUnofficialDatabase(root);
            return root;
        }
        unofficialCache = root;
        unofficialCacheSize = attributes.size();
        unofficialCacheTime = attributes.lastModifiedTime();
        return root;
    }

    private void quarantineCorruptDatabase(Path path, Exception cause) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path backup = path.resolveSibling(path.getFileName() + ".corrupt-" + stamp);
        try {
            Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.log(Level.SEVERE, "Unofficial metadata database file is invalid: moved to " + backup
                    + ", starting from an empty database", cause);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unofficial metadata database file is invalid and could not be moved to "
                    + backup + ", it is replaced by an empty database", cause);
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
