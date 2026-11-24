/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.reader.binary;

import studio.core.v1.Constants;
import studio.core.v1.model.*;
import studio.core.v1.model.enriched.EnrichedNodeMetadata;
import studio.core.v1.model.enriched.EnrichedNodePosition;
import studio.core.v1.model.enriched.EnrichedNodeType;
import studio.core.v1.model.enriched.EnrichedPackMetadata;
import studio.core.v1.model.metadata.StoryPackMetadata;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

public class BinaryStoryPackReader {

    private static final Logger LOGGER = Logger.getLogger(BinaryStoryPackReader.class.getName());

    /** Size of the enriched metadata block of a node: name (UTF-16), group id, type, position. */
    private static final int ENRICHED_NODE_METADATA_SIZE = Constants.BINARY_ENRICHED_METADATA_NODE_NAME_TRUNCATE*2 + 16 + 1 + 4;

    /**
     * Upper bound for a single asset, in sectors (512 MiB). Far above any real image or audio file,
     * low enough to refuse a corrupted size before allocating it.
     */
    static final int MAX_ASSET_SECTORS = (512 * 1024 * 1024) / Constants.SECTOR_SIZE;

    public StoryPackMetadata readMetadata(InputStream inputStream) throws IOException {
        DataInputStream dis = new DataInputStream(inputStream);

        // Pack metadata model
        StoryPackMetadata metadata = new StoryPackMetadata(Constants.PACK_FORMAT_RAW);

        // Read sector 1
        skipFully(dis, 3);   // Skip to version
        metadata.setVersion(dis.readShort());

        // Read (optional) enriched pack metadata
        skipFully(dis, Constants.BINARY_ENRICHED_METADATA_SECTOR_1_ALIGNMENT_PADDING);
        Optional<String> maybeTitle = readString(dis, Constants.BINARY_ENRICHED_METADATA_TITLE_TRUNCATE);
        metadata.setTitle(maybeTitle.orElse(null));
        Optional<String> maybeDescription = readString(dis, Constants.BINARY_ENRICHED_METADATA_DESCRIPTION_TRUNCATE);
        metadata.setDescription(maybeDescription.orElse(null));
        // TODO Thumbnail?

        skipFully(dis, Constants.SECTOR_SIZE - 5
                - Constants.BINARY_ENRICHED_METADATA_SECTOR_1_ALIGNMENT_PADDING
                - Constants.BINARY_ENRICHED_METADATA_TITLE_TRUNCATE*2
                - Constants.BINARY_ENRICHED_METADATA_DESCRIPTION_TRUNCATE*2); // Skip to end of sector

        // Read main stage node
        long uuidLowBytes = dis.readLong();
        long uuidHighBytes = dis.readLong();
        String uuid = (new UUID(uuidLowBytes, uuidHighBytes)).toString();
        metadata.setUuid(uuid);

        dis.close();

        return metadata;
    }

    public StoryPack read(InputStream inputStream) throws IOException {
        DataInputStream dis = new DataInputStream(inputStream);

        // Read sector 1
        short stages = dis.readShort();
        boolean factoryDisabled = dis.readByte() == 1;
        short version = dis.readShort();
        if (stages <= 0) {
            throw new IOException("Invalid binary story pack: stage node count must be positive, was " + stages);
        }

        // Read (optional) enriched pack metadata
        EnrichedPackMetadata enrichedPack = null;
        skipFully(dis, Constants.BINARY_ENRICHED_METADATA_SECTOR_1_ALIGNMENT_PADDING);
        Optional<String> maybeTitle = readString(dis, Constants.BINARY_ENRICHED_METADATA_TITLE_TRUNCATE);
        Optional<String> maybeDescription = readString(dis, Constants.BINARY_ENRICHED_METADATA_DESCRIPTION_TRUNCATE);
        // TODO Thumbnail?
        if (maybeTitle.isPresent() || maybeDescription.isPresent()) {
            enrichedPack = new EnrichedPackMetadata(maybeTitle.orElse(null), maybeDescription.orElse(null));
        }

        skipFully(dis, Constants.SECTOR_SIZE - 5
                - Constants.BINARY_ENRICHED_METADATA_SECTOR_1_ALIGNMENT_PADDING
                - Constants.BINARY_ENRICHED_METADATA_TITLE_TRUNCATE*2
                - Constants.BINARY_ENRICHED_METADATA_DESCRIPTION_TRUNCATE*2); // Skip to end of sector

        // Read stage nodes (`stages` sectors, starting from sector 2)
        TreeMap<SectorAddr, StageNode> stageNodes = new TreeMap<>();
        TreeMap<AssetAddr, List<StageNode>> stagesWithImage = new TreeMap<>();         // StageNodes must be updated with the actual ImageAsset
        TreeMap<AssetAddr, List<StageNode>> stagesWithAudio = new TreeMap<>();         // StageNodes must be updated with the actual AudioAsset
        TreeMap<SectorAddr, List<Transition>> transitionsWithAction = new TreeMap<>(); // Transitions must be updated with the actual ActionNode
        Set<SectorAddr> actionNodesToVisit = new TreeSet<>();   // Stage nodes / transitions reference action nodes, which are read after all stage nodes
        Set<AssetAddr> assetAddrsToVisit = new TreeSet<>();     // Stage nodes reference assets, which are read after all nodes
        Map<SectorAddr, Integer> actionNodesOptionsCount = new TreeMap<>(); // Stage nodes' transitions carry the number of options of their action node
        for (int i = 0; i < stages; i++) {
            // Reading sector i+2

            // UUID
            long uuidLowBytes = dis.readLong();
            long uuidHighBytes = dis.readLong();
            String uuid = (new UUID(uuidLowBytes, uuidHighBytes)).toString();

            // Image asset
            int imageOffset = dis.readInt();
            int imageSize = dis.readInt();
            AssetAddr imageAssetAddr = null;
            if (imageOffset != -1) {
                checkAssetAddress("image", i, imageOffset, imageSize, stages);
                // Asset must be visited
                imageAssetAddr = new AssetAddr(imageOffset, imageSize, AssetType.IMAGE);
                assetAddrsToVisit.add(imageAssetAddr);
            }

            // Audio asset
            int audioOffset = dis.readInt();
            int audioSize = dis.readInt();
            AssetAddr audioAssetAddr = null;
            if (audioOffset != -1) {
                checkAssetAddress("audio", i, audioOffset, audioSize, stages);
                // Asset must be visited
                audioAssetAddr = new AssetAddr(audioOffset, audioSize, AssetType.AUDIO);
                assetAddrsToVisit.add(audioAssetAddr);
            }

            // Transitions
            short okTransitionOffset = dis.readShort();
            short okTransitionCount = dis.readShort();
            short okTransitionIndex = dis.readShort();
            SectorAddr okActionNodeAddr = null;
            if (okTransitionOffset != -1) {
                checkActionAddress("ok", i, okTransitionOffset, okTransitionCount, stages);
                // Action node must be visited
                okActionNodeAddr = new SectorAddr(okTransitionOffset);
                actionNodesToVisit.add(okActionNodeAddr);
            }
            short homeTransitionOffset = dis.readShort();
            short homeTransitionCount = dis.readShort();
            short homeTransitionIndex = dis.readShort();
            SectorAddr homeActionNodeAddr = null;
            if (homeTransitionOffset != -1) {
                checkActionAddress("home", i, homeTransitionOffset, homeTransitionCount, stages);
                // Action node must be visited
                homeActionNodeAddr = new SectorAddr(homeTransitionOffset);
                actionNodesToVisit.add(homeActionNodeAddr);
            }

            // Control settings
            boolean wheelEnabled = dis.readShort() == 1;
            boolean okEnabled = dis.readShort() == 1;
            boolean homeEnabled = dis.readShort() == 1;
            boolean pauseEnabled = dis.readShort() == 1;
            boolean autoJumpEnabled = dis.readShort() == 1;

            // Read (optional) enriched node metadata
            skipFully(dis, Constants.BINARY_ENRICHED_METADATA_STAGE_NODE_ALIGNMENT_PADDING);
            EnrichedNodeMetadata enrichedNodeMetadata = readEnrichedNodeMetadata(dis);

            // Build stage node
            SectorAddr address = new SectorAddr(i);
            Transition okTransition = okActionNodeAddr != null ? new Transition(null, okTransitionIndex) : null;
            Transition homeTransition = homeActionNodeAddr != null ? new Transition(null, homeTransitionIndex) : null;
            StageNode stageNode = new StageNode(
                    uuid,
                    null,
                    null,
                    okTransition,
                    homeTransition,
                    new ControlSettings(wheelEnabled, okEnabled, homeEnabled, pauseEnabled, autoJumpEnabled),
                    enrichedNodeMetadata
            );
            stageNodes.put(address, stageNode);

            // Assets will be updated when they are read
            if (imageAssetAddr != null) {
                List<StageNode> swi = stagesWithImage.getOrDefault(imageAssetAddr, new ArrayList<>());
                swi.add(stageNode);
                stagesWithImage.put(imageAssetAddr, swi);
            }
            if (audioAssetAddr != null) {
                List<StageNode> swa = stagesWithAudio.getOrDefault(audioAssetAddr, new ArrayList<>());
                swa.add(stageNode);
                stagesWithAudio.put(audioAssetAddr, swa);
            }
            // Action nodes will be updated when they are read
            if (okActionNodeAddr != null) {
                recordOptionCount(actionNodesOptionsCount, okActionNodeAddr, okTransitionCount);
                List<Transition> twa = transitionsWithAction.getOrDefault(okActionNodeAddr, new ArrayList<>());
                twa.add(okTransition);
                transitionsWithAction.put(okActionNodeAddr, twa);
            }
            if (homeActionNodeAddr != null) {
                recordOptionCount(actionNodesOptionsCount, homeActionNodeAddr, homeTransitionCount);
                List<Transition> twa = transitionsWithAction.getOrDefault(homeActionNodeAddr, new ArrayList<>());
                twa.add(homeTransition);
                transitionsWithAction.put(homeActionNodeAddr, twa);
            }

            // Skip to end of sector
            skipFully(dis, Constants.SECTOR_SIZE - 54
                    - Constants.BINARY_ENRICHED_METADATA_STAGE_NODE_ALIGNMENT_PADDING
                    - ENRICHED_NODE_METADATA_SIZE);
        }

        // Read action nodes
        // We are positioned at the end of sector stages+1
        int currentOffset = stages;
        Iterator<SectorAddr> actionNodesIter = actionNodesToVisit.iterator();
        while (actionNodesIter.hasNext()) {
            // Sector to read
            SectorAddr actionNodeAddr = actionNodesIter.next();
            // Skip to the beginning of the sector, if needed
            if (actionNodeAddr.getOffset() < currentOffset) {
                throw new IOException("Invalid binary story pack: action node at sector " + actionNodeAddr.getOffset()
                        + " overlaps data already read (sector " + currentOffset + ")");
            }
            while (actionNodeAddr.getOffset() > currentOffset) {
                skipFully(dis, Constants.SECTOR_SIZE);
                currentOffset++;
            }

            // The stage nodes' transitions carry the exact number of options of the action node.
            // Read exactly that many: a zero is a valid option (the first stage node lives at sector 0),
            // not a terminator.
            int count = actionNodesOptionsCount.get(actionNodeAddr);
            List<StageNode> options = new ArrayList<>(count);
            for (int o = 0; o < count; o++) {
                short optionAddr = dis.readShort();
                StageNode option = stageNodes.get(new SectorAddr(optionAddr));
                if (option == null) {
                    throw new IOException("Invalid binary story pack: action node at sector " + actionNodeAddr.getOffset()
                            + " references unknown stage node " + optionAddr + " (option " + o + ")");
                }
                options.add(option);
            }
            int bytesRead = 2 * count;

            // Read (optional) enriched node metadata, when it fits in the sector
            int alignmentOverflow = 2*count % Constants.BINARY_ENRICHED_METADATA_ACTION_NODE_ALIGNMENT;
            int alignmentPadding = Constants.BINARY_ENRICHED_METADATA_ACTION_NODE_ALIGNMENT_PADDING + (alignmentOverflow > 0 ? Constants.BINARY_ENRICHED_METADATA_ACTION_NODE_ALIGNMENT - alignmentOverflow : 0);
            EnrichedNodeMetadata enrichedNodeMetadata = null;
            if (bytesRead + alignmentPadding + ENRICHED_NODE_METADATA_SIZE <= Constants.SECTOR_SIZE) {
                skipFully(dis, alignmentPadding);
                enrichedNodeMetadata = readEnrichedNodeMetadata(dis);
                bytesRead += alignmentPadding + ENRICHED_NODE_METADATA_SIZE;
            }

            // Update action on transitions referencing this sector
            ActionNode actionNode = new ActionNode(options, enrichedNodeMetadata);
            transitionsWithAction.get(actionNodeAddr).forEach(transition -> transition.setActionNode(actionNode));

            // Skip to end of sector
            skipFully(dis, Constants.SECTOR_SIZE - bytesRead);
            currentOffset++;
        }

        // Read assets
        Iterator<AssetAddr> assetAddrsIter = assetAddrsToVisit.iterator();
        while (assetAddrsIter.hasNext()) {
            // First sector to read
            AssetAddr assetAddr = assetAddrsIter.next();
            // Skip to the beginning of the sector, if needed
            if (assetAddr.getOffset() < currentOffset) {
                throw new IOException("Invalid binary story pack: asset at sector " + assetAddr.getOffset()
                        + " overlaps data already read (sector " + currentOffset + ")");
            }
            while (assetAddr.getOffset() > currentOffset) {
                skipFully(dis, Constants.SECTOR_SIZE);
                currentOffset++;
            }
            // Read all bytes
            byte[] assetBytes = new byte[Constants.SECTOR_SIZE * assetAddr.getSize()];
            dis.readFully(assetBytes);

            // Update asset on stage nodes referencing this sector
            switch (assetAddr.getType()) {
                case AUDIO:
                    AudioAsset audioAsset = new AudioAsset("audio/x-wav", assetBytes, "0x" + Integer.toHexString(assetAddr.getOffset()));
                    stagesWithAudio.get(assetAddr).forEach(stageNode -> stageNode.setAudio(audioAsset));
                    break;
                case IMAGE:
                    ImageAsset imageAsset = new ImageAsset("image/bmp", assetBytes, "0x" + Integer.toHexString(assetAddr.getOffset()));
                    stagesWithImage.get(assetAddr).forEach(stageNode -> stageNode.setImage(imageAsset));
                    break;
            }

            currentOffset += assetAddr.getSize();
        }

        // The writer always ends a pack with the check bytes, right after the last asset. Packs from
        // elsewhere are not known to always carry them at that position, so a mismatch is only reported.
        checkTrailingCheckBytes(dis);

        dis.close();

        return new StoryPack(stageNodes.get(new SectorAddr(0)).getUuid(), factoryDisabled, version, List.copyOf(stageNodes.values()), enrichedPack, false);
    }

    private static void checkAssetAddress(String kind, int stage, int offset, int size, int stages) throws IOException {
        if (offset < stages) {
            throw new IOException("Invalid binary story pack: " + kind + " asset of stage node " + stage
                    + " has invalid sector offset " + offset);
        }
        if (size < 0 || size > MAX_ASSET_SECTORS) {
            throw new IOException("Invalid binary story pack: " + kind + " asset of stage node " + stage
                    + " has invalid size of " + size + " sectors");
        }
    }

    private static void checkActionAddress(String kind, int stage, short offset, short count, int stages) throws IOException {
        if (offset < stages) {
            throw new IOException("Invalid binary story pack: " + kind + " transition of stage node " + stage
                    + " points to invalid action node sector " + offset);
        }
        if (count < 0 || count > Constants.SECTOR_SIZE / 2) {
            throw new IOException("Invalid binary story pack: " + kind + " transition of stage node " + stage
                    + " has invalid option count " + count);
        }
    }

    /**
     * Records the option count a transition announces for an action node. When two transitions
     * disagree, the <em>first</em> one read (lowest stage node, ok before home) is kept and the
     * disagreement is only logged, which is exactly what {@code FsStoryPackReader} does with the same
     * information in the {@code ni} file: the same pack yields the same graph in either format.
     *
     * <p>The first rather than the largest, because a larger count is the unsafe direction here. The
     * options are a bare list of shorts at the start of the action node sector, followed by alignment
     * padding (zeros) and the enriched metadata. Reading more shorts than were written decodes that
     * padding as option addresses, and {@code 0} is a valid one (the first stage node): the pack
     * would silently gain options leading back to its start. A count that is too small at worst
     * drops trailing options, and is never larger than what some transition claimed. Every count is
     * already bounded by what fits in the sector ({@link #checkActionAddress}).
     */
    private static void recordOptionCount(Map<SectorAddr, Integer> counts, SectorAddr addr, short count) {
        Integer known = counts.putIfAbsent(addr, (int) count);
        if (known != null && known != count) {
            LOGGER.warning("Binary story pack: transitions disagree on the option count of action node at sector "
                    + addr.getOffset() + " (" + known + " vs " + count + "); keeping the first one, " + known);
        }
    }

    private static void checkTrailingCheckBytes(DataInputStream dis) {
        byte[] trailing = new byte[Constants.CHECK_BYTES.length];
        try {
            dis.readFully(trailing);
            if (!Arrays.equals(trailing, Constants.CHECK_BYTES)) {
                LOGGER.warning("Binary story pack does not end with the expected check bytes");
            }
        } catch (IOException e) {
            LOGGER.warning("Binary story pack is missing its trailing check bytes");
        }
    }

    /** Skips exactly {@code n} bytes, or fails: {@code skipBytes} may silently skip fewer. */
    static void skipFully(DataInputStream dis, int n) throws IOException {
        if (n < 0) {
            throw new IOException("Invalid binary story pack: negative skip of " + n + " bytes");
        }
        int remaining = n;
        while (remaining > 0) {
            int skipped = dis.skipBytes(remaining);
            if (skipped <= 0) {
                // skipBytes may return 0 without being at end of stream: probe with a read
                if (dis.read() == -1) {
                    throw new EOFException("Unexpected end of binary story pack (" + remaining + " bytes left to skip)");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private Optional<String> readString(DataInputStream dis, int maxChars) throws IOException {
        byte[] bytes = new byte[maxChars*2];
        dis.readFully(bytes);
        String str = new String(bytes, StandardCharsets.UTF_16);
        int firstNullChar = str.indexOf("\u0000");
        return firstNullChar == 0
                ? Optional.empty()
                : firstNullChar == -1
                    ? Optional.of(str)
                    : Optional.of(str.substring(0, firstNullChar));
    }

    private EnrichedNodeMetadata readEnrichedNodeMetadata(DataInputStream dis) throws IOException {
        Optional<String> maybeName = readString(dis, Constants.BINARY_ENRICHED_METADATA_NODE_NAME_TRUNCATE);
        Optional<String> maybeGroupId = Optional.empty();
        long groupIdLowBytes = dis.readLong();
        long groupIdHighBytes = dis.readLong();
        if (groupIdLowBytes != 0 || groupIdHighBytes != 0) {
            maybeGroupId = Optional.of((new UUID(groupIdLowBytes, groupIdHighBytes)).toString());
        }
        Optional<EnrichedNodeType> maybeType = Optional.empty();
        byte nodeTypeByte = dis.readByte();
        if (nodeTypeByte != 0x00) {
            maybeType = Optional.ofNullable(EnrichedNodeType.fromCode(nodeTypeByte));
        }
        Optional<EnrichedNodePosition> maybePosition = Optional.empty();
        short positionX = dis.readShort();
        short positionY = dis.readShort();
        if (positionX != 0 || positionY != 0) {
            maybePosition = Optional.of(new EnrichedNodePosition(positionX, positionY));
        }
        if (maybeName.isPresent() || maybeType.isPresent() || maybeGroupId.isPresent() || maybePosition.isPresent()) {
            return new EnrichedNodeMetadata(
                    maybeName.orElse(null),
                    maybeType.orElse(null),
                    maybeGroupId.orElse(null),
                    maybePosition.orElse(null)
            );
        }
        return null;
    }
}
