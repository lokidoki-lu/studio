/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.reader.archive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import studio.core.v1.Constants;
import studio.core.v1.model.*;
import studio.core.v1.model.enriched.EnrichedNodeMetadata;
import studio.core.v1.model.enriched.EnrichedNodePosition;
import studio.core.v1.model.enriched.EnrichedNodeType;
import studio.core.v1.model.enriched.EnrichedPackMetadata;
import studio.core.v1.model.metadata.StoryPackMetadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

public class ArchiveStoryPackReader {

    private static final Logger LOGGER = Logger.getLogger(ArchiveStoryPackReader.class.getName());

    /** Largest uncompressed zip entry accepted (story.json, thumbnail or asset). */
    public static final long DEFAULT_MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    /** Largest cumulated uncompressed size of all the entries read from one archive. */
    public static final long DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    private final long maxEntryBytes;
    private final long maxTotalBytes;

    public ArchiveStoryPackReader() {
        this(DEFAULT_MAX_ENTRY_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    public ArchiveStoryPackReader(long maxEntryBytes, long maxTotalBytes) {
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalBytes = maxTotalBytes;
    }

    /** Running total of the bytes read from one archive. */
    private static final class ReadBudget {
        private long total;
    }

    /**
     * Reads the current entry in full, refusing it when it exceeds the per-entry limit or makes the
     * archive exceed the cumulated limit. The declared size is not trusted: the bytes are counted.
     */
    private byte[] readEntry(InputStream zis, ZipArchiveEntry entry, ReadBudget budget) throws IOException {
        if (entry.getSize() > maxEntryBytes) {
            throw new IOException("Archive entry too large: " + entry.getName() + " (" + entry.getSize() + " bytes, max " + maxEntryBytes + ")");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long count = 0;
        int n;
        while ((n = zis.read(buffer)) != -1) {
            count += n;
            if (count > maxEntryBytes) {
                throw new IOException("Archive entry too large: " + entry.getName() + " (more than " + maxEntryBytes + " bytes)");
            }
            if (budget.total + count > maxTotalBytes) {
                throw new IOException("Archive content too large (more than " + maxTotalBytes + " bytes uncompressed)");
            }
            out.write(buffer, 0, n);
        }
        budget.total += count;
        return out.toByteArray();
    }

    /**
     * Parses story.json as UTF-8, which is what the editor and this module's writer produce.
     *
     * <p>Archives written by older versions of the Java writer used the platform charset, which on
     * Windows with Java 11 is not UTF-8. Such a file is not valid UTF-8 as soon as it has an accented
     * character, so it is decoded as windows-1252 instead of being mangled.
     */
    private static JsonObject parseJson(byte[] json) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(json))
                    .toString();
        } catch (CharacterCodingException e) {
            LOGGER.warning("story.json is not valid UTF-8, decoding it as windows-1252");
            text = new String(json, Charset.forName("windows-1252"));
        }
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid story.json: " + e.getMessage(), e);
        }
    }

    /**
     * The entry name with every {@code \} turned into {@code /}. The zip format mandates forward
     * slashes, but archives re-zipped on Windows by some tools store {@code assets\x.mp3}: the
     * asset is there, and must not be reported missing because of the separator.
     */
    static String normalizeEntryName(String name) {
        return name == null ? "" : name.replace('\\', '/');
    }

    /** A directory entry, including one whose name only ends with a Windows separator. */
    private static boolean isDirectory(ZipArchiveEntry entry, String normalizedName) {
        return entry.isDirectory() || normalizedName.endsWith("/");
    }

    /** The pack-level fields of story.json: version, title, description, night mode, first stage uuid. */
    private static void applyStoryJson(StoryPackMetadata metadata, JsonObject root) {
        metadata.setVersion(root.get("version").getAsShort());
        Optional.ofNullable(root.get("title")).filter(JsonElement::isJsonPrimitive).ifPresent(title ->
                metadata.setTitle(title.getAsString())
        );
        Optional.ofNullable(root.get("description")).filter(JsonElement::isJsonPrimitive).ifPresent(desc ->
                metadata.setDescription(desc.getAsString())
        );
        // TODO Thumbnail?

        // Night mode
        metadata.setNightModeAvailable(Optional.ofNullable(root.get("nightModeAvailable")).map(JsonElement::getAsBoolean).orElse(false));

        // Read first stage node
        JsonObject mainStageNode = root.getAsJsonArray("stageNodes").get(0).getAsJsonObject();
        metadata.setUuid(mainStageNode.get("uuid").getAsString());
    }

    /**
     * Metadata of the archive at {@code path}, read through the zip central directory: only
     * story.json and thumbnail.png are decompressed, the assets are never read. A pack of a
     * gigabyte is thus read in milliseconds, where {@link #readMetadata(InputStream)} has to stream
     * through the whole file to reach entries that may sit at its end.
     *
     * <p>Same entries, same rules as the streaming reader (names normalized, matched ignoring case,
     * the last story.json winning, same size limits). When the central directory cannot be read —
     * a file still being copied, a truncated or unusual archive — this falls back to the streaming
     * reader, so such a file is handled exactly as before.
     */
    public StoryPackMetadata readMetadata(Path path) throws IOException {
        ZipFile zip;
        try {
            zip = ZipFile.builder().setPath(path).get();
        } catch (IOException e) {
            LOGGER.fine("Zip central directory unreadable, streaming instead: " + path + " (" + e.getMessage() + ")");
            try (InputStream in = Files.newInputStream(path)) {
                return readMetadata(in);
            }
        }
        try (ZipFile z = zip) {
            StoryPackMetadata metadata = new StoryPackMetadata(Constants.PACK_FORMAT_ARCHIVE);
            boolean hasStoryJsonEntry = false;
            ReadBudget budget = new ReadBudget();
            Enumeration<ZipArchiveEntry> entries = z.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String name = normalizeEntryName(entry.getName());
                if (isDirectory(entry, name)) {
                    continue;
                }
                boolean storyJson = name.equalsIgnoreCase("story.json");
                if (!storyJson && !name.equalsIgnoreCase("thumbnail.png")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = z.getInputStream(entry)) {
                    if (in == null) {
                        throw new IOException("Unsupported archive entry: " + entry.getName());
                    }
                    bytes = readEntry(in, entry, budget);
                }
                if (storyJson) {
                    hasStoryJsonEntry = true;
                    applyStoryJson(metadata, parseJson(bytes));
                } else {
                    metadata.setThumbnail(bytes);
                }
            }
            return hasStoryJsonEntry ? metadata : null;
        }
    }

    public StoryPackMetadata readMetadata(InputStream inputStream) throws IOException {
        // Zip archive contains a json file and separate assets
        ZipArchiveInputStream zis = new ZipArchiveInputStream(inputStream);

        // Pack metadata model
        StoryPackMetadata metadata = new StoryPackMetadata(Constants.PACK_FORMAT_ARCHIVE);

        boolean hasStoryJsonEntry = false;
        ReadBudget budget = new ReadBudget();

        ZipArchiveEntry entry;
        while((entry = zis.getNextEntry()) != null) {
            String name = normalizeEntryName(entry.getName());
            boolean directory = isDirectory(entry, name);
            // Story descriptor file: story.json
            if (!directory && name.equalsIgnoreCase("story.json")) {
                hasStoryJsonEntry = true;
                applyStoryJson(metadata, parseJson(readEntry(zis, entry, budget)));
            }
            // Pack thumbnail
            else if (!directory && name.equalsIgnoreCase("thumbnail.png")) {
                metadata.setThumbnail(readEntry(zis, entry, budget));
            }
            // Ignore asset files
            else if (!directory && name.startsWith("assets/")) {
                // no-op
            }
        }

        zis.close();

        return hasStoryJsonEntry ? metadata : null;
    }

    public StoryPack read(InputStream inputStream) throws IOException {

        // Zip archive contains a json file and separate assets
        ZipArchiveInputStream zis = new ZipArchiveInputStream(inputStream);

        // Store assets bytes
        TreeMap<String, byte[]> assets = new TreeMap<>();

        // Story pack model
        boolean factoryDisabled = false;
        short version = 0;
        // Keep stage nodes in the order they appear
        LinkedHashMap<String, StageNode> stageNodes = new LinkedHashMap<>();
        // Keep asset name to stage nodes map
        Map<String, List<StageNode>> assetToStageNodes = new HashMap<>();
        // Keep first node
        StageNode squareOne = null;
        // Enriched pack metadata
        EnrichedPackMetadata enrichedPack = null;
        boolean nightModeAvailable = false;
        boolean hasStoryJsonEntry = false;
        ReadBudget budget = new ReadBudget();
        // Transitions to validate once action nodes know their options
        Map<Transition, String> transitionsToCheck = new LinkedHashMap<>();


        ZipArchiveEntry entry;
        while((entry = zis.getNextEntry()) != null) {
            String name = normalizeEntryName(entry.getName());
            boolean directory = isDirectory(entry, name);
            // Story descriptor file: story.json
            if (!directory && name.equalsIgnoreCase("story.json")) {
                hasStoryJsonEntry = true;
                JsonObject root = parseJson(readEntry(zis, entry, budget));

                // Read metadata
                version = root.get("version").getAsShort();
                // Read (optional) enriched pack metadata
                Optional<String> maybeTitle = Optional.ofNullable(root.get("title")).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString);
                Optional<String> maybeDescription = Optional.ofNullable(root.get("description")).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString);
                // TODO Thumbnail?
                if (maybeTitle.isPresent() || maybeDescription.isPresent()) {
                    enrichedPack = new EnrichedPackMetadata(maybeTitle.orElse(null), maybeDescription.orElse(null));
                }

                // Night mode
                nightModeAvailable = Optional.ofNullable(root.get("nightModeAvailable")).map(JsonElement::getAsBoolean).orElse(false);

                // Read action nodes
                TreeMap<String, ActionNode> actionNodes = new TreeMap<>();
                Iterator<JsonElement> actionsIter = root.getAsJsonArray("actionNodes").iterator();
                while (actionsIter.hasNext()) {
                    JsonObject node = actionsIter.next().getAsJsonObject();

                    // Read (optional) enriched node metadata
                    EnrichedNodeMetadata enrichedNodeMetadata = readEnrichedNodeMetadata(node);

                    actionNodes.put(node.get("id").getAsString(), new ActionNode(enrichedNodeMetadata));
                }

                // Read stage nodes
                Iterator<JsonElement> stagesIter = root.getAsJsonArray("stageNodes").iterator();
                while (stagesIter.hasNext()) {
                    JsonObject node = stagesIter.next().getAsJsonObject();
                    String uuid = node.get("uuid").getAsString();
                    Transition okTransition = readTransition(node, "okTransition", uuid, actionNodes);
                    if (okTransition != null) {
                        transitionsToCheck.put(okTransition, "okTransition of stage node " + uuid);
                    }
                    Transition homeTransition = readTransition(node, "homeTransition", uuid, actionNodes);
                    if (homeTransition != null) {
                        transitionsToCheck.put(homeTransition, "homeTransition of stage node " + uuid);
                    }
                    JsonObject controlSettings = node.getAsJsonObject("controlSettings");

                    // Read (optional) enriched node metadata
                    EnrichedNodeMetadata enrichedNodeMetadata = readEnrichedNodeMetadata(node);

                    StageNode stageNode = new StageNode(
                            uuid,
                            null,
                            null,
                            okTransition,
                            homeTransition,
                            new ControlSettings(
                                    controlSettings.get("wheel").getAsBoolean(),
                                    controlSettings.get("ok").getAsBoolean(),
                                    controlSettings.get("home").getAsBoolean(),
                                    controlSettings.get("pause").getAsBoolean(),
                                    controlSettings.get("autoplay").getAsBoolean()
                            ),
                            enrichedNodeMetadata
                    );

                    if (node.get("squareOne") != null && node.get("squareOne").getAsBoolean()) {
                        squareOne = stageNode;
                    }

                    if (node.get("image") != null && !node.get("image").isJsonNull()) {
                        String imageAssetName = node.get("image").getAsString();
                        List<StageNode> atsn = assetToStageNodes.getOrDefault(imageAssetName, new ArrayList<>());
                        atsn.add(stageNode);
                        assetToStageNodes.put(imageAssetName, atsn);
                    }
                    if (node.get("audio") != null && !node.get("audio").isJsonNull()) {
                        String audioAssetName = node.get("audio").getAsString();
                        List<StageNode> atsn = assetToStageNodes.getOrDefault(audioAssetName, new ArrayList<>());
                        atsn.add(stageNode);
                        assetToStageNodes.put(audioAssetName, atsn);
                    }

                    stageNodes.put(uuid, stageNode);
                }

                // Link action nodes to stage nodes
                actionsIter = root.getAsJsonArray("actionNodes").iterator();
                while (actionsIter.hasNext()) {
                    JsonObject node = actionsIter.next().getAsJsonObject();
                    ActionNode actionNode = actionNodes.get(node.get("id").getAsString());
                    List<StageNode> options = new ArrayList<>();
                    Iterator<JsonElement> optionsIter = node.getAsJsonArray("options").iterator();
                    while (optionsIter.hasNext()) {
                        JsonElement option = optionsIter.next();
                        StageNode optionNode = option != null && option.isJsonPrimitive() ? stageNodes.get(option.getAsString()) : null;
                        if (optionNode == null) {
                            throw new IOException("Invalid story.json: action node " + node.get("id").getAsString()
                                    + " has an option that is not a known stage node: " + option);
                        }
                        options.add(optionNode);
                    }
                    actionNode.setOptions(options);
                }

            }
            // Separate asset files
            else if (!directory && name.startsWith("assets/")) {
                String assetName = name.substring("assets/".length());
                if (assets.containsKey(assetName)) {
                    // Same name twice, possibly once with each separator. The last entry wins, as it
                    // always did for exact duplicates; it is no longer silent.
                    LOGGER.warning("Duplicate asset entry in archive, the last one is kept: " + entry.getName());
                }
                assets.put(assetName, readEntry(zis, entry, budget));
            }
        }

        if (!hasStoryJsonEntry) {
            throw new IOException("Invalid archive story pack: no story.json entry");
        }
        if (stageNodes.isEmpty()) {
            throw new IOException("Invalid story.json: no stage node");
        }

        // Every transition must point to an existing option (-1 stands for a random option)
        for (Map.Entry<Transition, String> check : transitionsToCheck.entrySet()) {
            Transition transition = check.getKey();
            int optionsCount = transition.getActionNode().getOptions() == null ? 0 : transition.getActionNode().getOptions().size();
            if (transition.getOptionIndex() < -1 || transition.getOptionIndex() >= optionsCount) {
                throw new IOException("Invalid story.json: " + check.getValue() + " has option index "
                        + transition.getOptionIndex() + " but its action node has " + optionsCount + " option(s)");
            }
        }

        // Every asset referenced by a stage node must be in the archive
        for (String referencedAsset : assetToStageNodes.keySet()) {
            if (!assets.containsKey(referencedAsset)) {
                throw new IOException("Invalid archive story pack: asset referenced by the story is missing: assets/" + referencedAsset);
            }
        }

        // Update assets in stage nodes
        for (Map.Entry<String, byte[]> assetEntry : assets.entrySet()) {
            String assetName = assetEntry.getKey();

            // Stage nodes explicitly reference their assets' filenames
            List<StageNode> stageNodesReferencingAsset = assetToStageNodes.get(assetName);
            if (stageNodesReferencingAsset != null && !stageNodesReferencingAsset.isEmpty()) {
                int dotIndex = assetName.lastIndexOf(".");
                if (dotIndex == -1) {
                    throw new IOException("Invalid archive story pack: referenced asset has no file extension: assets/" + assetName);
                }
                String extension = assetName.substring(dotIndex).toLowerCase();
                for (StageNode stageNode : stageNodesReferencingAsset) {
                    switch (extension.toLowerCase()) {
                        case ".bmp":
                            stageNode.setImage(new ImageAsset("image/bmp", assetEntry.getValue(), assetName));
                            break;
                        case ".png":
                            stageNode.setImage(new ImageAsset("image/png", assetEntry.getValue(), assetName));
                            break;
                        case ".jpg":
                        case ".jpeg":
                            stageNode.setImage(new ImageAsset("image/jpeg", assetEntry.getValue(), assetName));
                            break;
                        case ".wav":
                            stageNode.setAudio(new AudioAsset("audio/x-wav", assetEntry.getValue(), assetName));
                            break;
                        case ".mp3":
                            stageNode.setAudio(new AudioAsset("audio/mpeg", assetEntry.getValue(), assetName));
                            break;
                        case ".ogg":
                        case ".oga":
                            stageNode.setAudio(new AudioAsset("audio/ogg", assetEntry.getValue(), assetName));
                            break;
                        default:
                            // Unsupported asset: kept as before (the node has no such asset)
                            LOGGER.warning("Unsupported asset type, ignored: assets/" + assetName);
                    }
                }
            }
        }

        zis.close();

        // Make sure the first node is actually 'square one'
        List<StageNode> nodes = new ArrayList<>(stageNodes.values());
        if (squareOne != null) {
            nodes.remove(squareOne);
            nodes.add(0, squareOne);
        }

        return new StoryPack(nodes.get(0).getUuid(), factoryDisabled, version, nodes, enrichedPack, nightModeAvailable);
    }

    private static Transition readTransition(JsonObject node, String field, String stageUuid, Map<String, ActionNode> actionNodes) throws IOException {
        JsonElement element = node.get(field);
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject transition = element.getAsJsonObject();
        JsonElement actionNodeId = transition.get("actionNode");
        ActionNode actionNode = actionNodeId != null && actionNodeId.isJsonPrimitive() ? actionNodes.get(actionNodeId.getAsString()) : null;
        if (actionNode == null) {
            throw new IOException("Invalid story.json: " + field + " of stage node " + stageUuid
                    + " references an unknown action node: " + actionNodeId);
        }
        JsonElement optionIndex = transition.get("optionIndex");
        if (optionIndex == null || !optionIndex.isJsonPrimitive()) {
            throw new IOException("Invalid story.json: " + field + " of stage node " + stageUuid + " has no option index");
        }
        return new Transition(actionNode, optionIndex.getAsShort());
    }

    private EnrichedNodeMetadata readEnrichedNodeMetadata(JsonObject node) {
        Optional<String> maybeName = Optional.ofNullable(node.get("name")).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString);
        Optional<String> maybeType = Optional.ofNullable(node.get("type")).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString);
        Optional<String> maybeGroupId = Optional.ofNullable(node.get("groupId")).filter(JsonElement::isJsonPrimitive).map(JsonElement::getAsString);
        Optional<JsonObject> maybePosition = Optional.ofNullable(node.get("position")).filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject);
        if (maybeName.isPresent() || maybeType.isPresent() || maybeGroupId.isPresent() || maybePosition.isPresent()) {
            return new EnrichedNodeMetadata(
                    maybeName.orElse(null),
                    maybeType.map(EnrichedNodeType::fromLabel).orElse(null),
                    maybeGroupId.orElse(null),
                    maybePosition
                            .map(position -> new EnrichedNodePosition(
                                    position.get("x").getAsShort(),
                                    position.get("y").getAsShort()))
                            .orElse(null)
            );
        }
        return null;
    }
}
