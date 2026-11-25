/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.writer.archive;

import com.google.gson.stream.JsonWriter;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import studio.core.v1.model.ActionNode;
import studio.core.v1.model.Asset;
import studio.core.v1.model.Node;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.enriched.EnrichedNodePosition;
import studio.core.v1.model.enriched.EnrichedNodeType;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ArchiveStoryPackWriter {

    /**
     * Where the writer gets each asset's digest (its name in the archive) and bytes.
     *
     * <p>The digests are all needed first, for story.json; the bytes are then copied into the
     * archive one asset at a time. A source that streams them from files keeps the memory used by
     * the conversion independent of the size of the pack.
     */
    public interface AssetSource {
        /** SHA-1 (hexadecimal) of the bytes {@link #copy} writes for this asset. */
        String sha1(Asset asset) throws IOException;

        /** Writes the bytes of the asset to {@code out}, without closing it. */
        void copy(Asset asset, OutputStream out) throws IOException;
    }

    /** The bytes the assets of the pack carry. */
    public static final AssetSource IN_MEMORY = new AssetSource() {
        @Override
        public String sha1(Asset asset) {
            return DigestUtils.sha1Hex(asset.getRawData());
        }

        @Override
        public void copy(Asset asset, OutputStream out) throws IOException {
            IOUtils.write(asset.getRawData(), out);
        }
    };

    public void write(StoryPack pack, OutputStream outputStream) throws IOException {
        write(pack, outputStream, IN_MEMORY);
    }

    /**
     * Writes the pack, taking its assets from {@code source}. The archive holds the same entries,
     * with the same names and contents, whatever the source.
     */
    public void write(StoryPack pack, OutputStream outputStream, AssetSource source) throws IOException {

        // Zip archive contains a json file and separate assets
        ZipOutputStream zos = new ZipOutputStream(outputStream);

        // Assets to add, by entry name (first one met wins, as with putIfAbsent on the bytes)
        TreeMap<String, Asset> assets = new TreeMap<>();


        // Add story descriptor file: story.json
        ZipEntry zipEntry = new ZipEntry("story.json");
        zos.putNextEntry(zipEntry);

        // Start json document
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8));
        writer.setIndent("    ");
        writer.beginObject();

        // Write file format metadata
        writer.name("format").value("v1");

        // Write (optional) enriched pack metadata
        if (pack.getEnriched() != null) {
            String packTitle = pack.getEnriched().getTitle();
            if (packTitle != null) {
                writer.name("title").value(packTitle);
            } else {
                writer.name("title").value("MISSING_PACK_TITLE");
            }
            if (pack.getEnriched().getDescription() != null) {
                writer.name("description").value(pack.getEnriched().getDescription());
            }
            // TODO Thumbnail?
        }

        // Write metadata
        writer.name("version").value(pack.getVersion());

        // Write night mode
        writer.name("nightModeAvailable").value(pack.isNightModeAvailable());

        // Write stage nodes and keep track of action nodes and assets
        Map<ActionNode, String> actionNodeToId = new HashMap<>();
        writer.name("stageNodes");
        writer.beginArray();
        for (int i = 0; i < pack.getStageNodes().size(); i++) {
            StageNode node = pack.getStageNodes().get(i);
            writer.beginObject();
            writer.name("uuid").value(node.getUuid());

            // Write (optional) enriched node metadata
            if (node.getEnriched() != null) {
                writeEnrichedNodeMetadata(writer, node);
            }

            if (i == 0) {
                // The first stage node is marked as such
                writer.name("squareOne").value(true);
            }
            writer.name("image");
            if (node.getImage() == null) {
                writer.nullValue();
            } else {
                String extension = extensionFromMimeType(node.getImage().getMimeType());
                String assetFileName = source.sha1(node.getImage()) + extension;
                writer.value(assetFileName);
                assets.putIfAbsent(assetFileName, node.getImage());
            }
            writer.name("audio");
            if (node.getAudio() == null) {
                writer.nullValue();
            } else {
                String extension = extensionFromMimeType(node.getAudio().getMimeType());
                String assetFileName = source.sha1(node.getAudio()) + extension;
                writer.value(assetFileName);
                assets.putIfAbsent(assetFileName, node.getAudio());
            }
            writer.name("okTransition");
            if (node.getOkTransition() == null) {
                writer.nullValue();
            } else {
                writer.beginObject();
                if (!actionNodeToId.containsKey(node.getOkTransition().getActionNode())) {
                    actionNodeToId.put(node.getOkTransition().getActionNode(), UUID.randomUUID().toString());
                }
                writer.name("actionNode").value(actionNodeToId.get(node.getOkTransition().getActionNode()));
                writer.name("optionIndex").value(node.getOkTransition().getOptionIndex());
                writer.endObject();
            }
            writer.name("homeTransition");
            if (node.getHomeTransition() == null) {
                writer.nullValue();
            } else {
                writer.beginObject();
                if (!actionNodeToId.containsKey(node.getHomeTransition().getActionNode())) {
                    actionNodeToId.put(node.getHomeTransition().getActionNode(), UUID.randomUUID().toString());
                }
                writer.name("actionNode").value(actionNodeToId.get(node.getHomeTransition().getActionNode()));
                writer.name("optionIndex").value(node.getHomeTransition().getOptionIndex());
                writer.endObject();
            }
            writer.name("controlSettings");
            writer.beginObject();
            writer.name("wheel").value(node.getControlSettings().isWheelEnabled());
            writer.name("ok").value(node.getControlSettings().isOkEnabled());
            writer.name("home").value(node.getControlSettings().isHomeEnabled());
            writer.name("pause").value(node.getControlSettings().isPauseEnabled());
            writer.name("autoplay").value(node.getControlSettings().isAutoJumpEnabled());
            writer.endObject();
            writer.endObject();
        }
        writer.endArray();

        writer.name("actionNodes");
        writer.beginArray();
        for (Map.Entry<ActionNode, String> actionNode : actionNodeToId.entrySet()) {
            writer.beginObject();
            writer.name("id").value(actionNode.getValue());

            // Write (optional) enriched node metadata
            ActionNode node = actionNode.getKey();
            if (node.getEnriched() != null) {
                writeEnrichedNodeMetadata(writer, node);
            }

            writer.name("options");
            writer.beginArray();
            for (StageNode option : node.getOptions()) {
                writer.value(option.getUuid());
            }
            writer.endArray();
            writer.endObject();
        }
        writer.endArray();

        writer.endObject();
        writer.flush();


        // Add assets in separate directory
        zipEntry = new ZipEntry("assets/");
        zos.putNextEntry(zipEntry);
        for (Map.Entry<String, Asset> assetEntry : assets.entrySet()) {
            String assetPath = "assets/" + assetEntry.getKey();
            zipEntry = new ZipEntry(assetPath);
            zos.putNextEntry(zipEntry);
            source.copy(assetEntry.getValue(), zos);
        }

        zos.flush();
        zos.close();
    }

    private void writeEnrichedNodeMetadata(JsonWriter writer, Node node) throws IOException {
        String nodeName = node.getEnriched().getName();
        if (nodeName != null) {
            writer.name("name").value(nodeName);
        } else {
            writer.name("name").value("MISSING_NAME");
        }
        EnrichedNodeType nodeType = node.getEnriched().getType();
        if (nodeType != null) {
            writer.name("type").value(nodeType.label);
        }
        String nodeGroupId = node.getEnriched().getGroupId();
        if (nodeGroupId != null) {
            writer.name("groupId").value(nodeGroupId);
        }
        EnrichedNodePosition nodePosition = node.getEnriched().getPosition();
        if (nodePosition != null) {
            writer.name("position");
            writer.beginObject();
            writer.name("x").value(nodePosition.getX());
            writer.name("y").value(nodePosition.getY());
            writer.endObject();
        }
    }

    private String extensionFromMimeType(String mimeType) {
        switch (mimeType) {
            case "image/bmp":
                return ".bmp";
            case "image/png":
                return ".png";
            case "image/jpeg":
                return ".jpg";
            case "audio/x-wav":
                return ".wav";
            case "audio/mpeg":
                return ".mp3";
            case "audio/ogg":
                return ".ogg";
            default:
                return "";
        }
    }

}
