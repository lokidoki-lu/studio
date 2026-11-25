/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import studio.core.v1.model.ActionNode;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.enriched.EnrichedNodeMetadata;
import studio.core.v1.model.StoryPack;
import studio.core.v1.reader.binary.BinaryStoryPackReader;
import studio.core.v1.writer.binary.BinaryStoryPackWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What survives a write and a read back in the raw binary format.
 *
 * <p>This module produces the bytes that end up on the card, and until now nothing exercised it: the
 * only test in {@code core} covered the endianness helpers. The round trip is the cheapest property
 * worth having — a pack written and read back must describe the same story — and it is the one that
 * would notice a reader and a writer drifting apart.
 *
 * <p>The cases are marked as they are found. Where the format keeps something faithfully that is a
 * <strong>specification</strong> and stays that way. Where it does not, the case is
 * <strong>characterization</strong>: it records what the code does today, including where that is
 * arguably wrong, so that changing it is a decision someone makes rather than an accident. Three are
 * below, and they are not all of the same weight: two are consequences of a sector-based format that
 * the model does not express, and one substitutes a pack's identity. A
 * {@code KNOWN GAP} case passing says the behaviour is known, not that it is acceptable.
 */
@DisplayName("A raw binary pack, written and read back")
class BinaryPackRoundTripTest {

    private static StoryPack roundTrip(StoryPack pack, boolean enriched) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BinaryStoryPackWriter().write(pack, out, enriched);
        return new BinaryStoryPackReader().read(new ByteArrayInputStream(out.toByteArray()));
    }

    @Nested
    @DisplayName("keeps")
    class Keeps {

        @Test
        @DisplayName("its version and its node count")
        void versionAndNodeCount() throws IOException {
            StoryPack read = roundTrip(PackFixtures.twoStagePack(), false);

            assertEquals((short) 1, read.getVersion(), "pack version");
            assertEquals(2, read.getStageNodes().size(), "stage node count");
        }

        @Test
        @DisplayName("each stage node's asset bytes, when they fill whole sectors")
        void assetBytes() throws IOException {
            StoryPack original = PackFixtures.twoStagePack();
            StoryPack read = roundTrip(original, false);

            for (int i = 0; i < original.getStageNodes().size(); i++) {
                StageNode before = original.getStageNodes().get(i);
                StageNode after = read.getStageNodes().get(i);
                assertArrayEquals(before.getImage().getRawData(), after.getImage().getRawData(),
                        "image bytes of node " + i);
                assertArrayEquals(before.getAudio().getRawData(), after.getAudio().getRawData(),
                        "audio bytes of node " + i);
            }
        }

        @Test
        @DisplayName("the mime types the format is defined over")
        void mimeTypes() throws IOException {
            StoryPack read = roundTrip(PackFixtures.twoStagePack(), false);

            for (StageNode node : read.getStageNodes()) {
                assertEquals("image/bmp", node.getImage().getMimeType());
                assertEquals("audio/x-wav", node.getAudio().getMimeType());
            }
        }

        @Test
        @DisplayName("the control settings of each node")
        void controlSettings() throws IOException {
            StoryPack read = roundTrip(PackFixtures.twoStagePack(), false);

            for (StageNode node : read.getStageNodes()) {
                assertNotNull(node.getControlSettings(), "control settings");
                assertTrue(node.getControlSettings().isWheelEnabled(), "wheel");
                assertTrue(node.getControlSettings().isOkEnabled(), "ok");
            }
        }

        @Test
        @DisplayName("the transitions, still pointing at a shared action node")
        void transitions() throws IOException {
            StoryPack read = roundTrip(PackFixtures.twoStagePack(), false);

            StageNode first = read.getStageNodes().get(0);
            assertNotNull(first.getOkTransition(), "ok transition");
            assertNotNull(first.getHomeTransition(), "home transition");
            // The fixture points both stage nodes at one action node. A format that stored the
            // action node twice would still read back, so this is worth asserting rather than
            // assuming: the identity of the target is part of the story's shape.
            assertSame(first.getOkTransition().getActionNode(),
                    read.getStageNodes().get(1).getOkTransition().getActionNode(),
                    "both stage nodes should reach the same action node");
            assertEquals(1, first.getOkTransition().getActionNode().getOptions().size(),
                    "action node options");
        }
    }

    @Nested
    @DisplayName("keeps an option pointing at the first stage node")
    class OptionOnFirstNode {

        // Options are stored as sector addresses, and the first stage node sits at sector 0. The
        // reader used to stop at the first zero, which silently dropped that option and every one
        // after it. The stage nodes' transitions carry the exact option count, which is what the
        // reader now uses.

        @Test
        @DisplayName("in the plain format")
        void plain() throws IOException {
            assertOptionsSurvive(false);
        }

        @Test
        @DisplayName("in the enriched format")
        void enriched() throws IOException {
            assertOptionsSurvive(true);
        }

        private void assertOptionsSurvive(boolean enriched) throws IOException {
            StoryPack original = PackFixtures.packWithOptionOnFirstNode();

            StoryPack read = roundTrip(original, enriched);

            StageNode first = read.getStageNodes().get(0);
            StageNode second = read.getStageNodes().get(1);
            assertEquals(2, first.getOkTransition().getActionNode().getOptions().size(),
                    "both options are read back");
            assertSame(first, first.getOkTransition().getActionNode().getOptions().get(0),
                    "option 0 is the first stage node");
            assertSame(second, first.getOkTransition().getActionNode().getOptions().get(1),
                    "option 1 is the second stage node");
            assertSame(second.getOkTransition().getActionNode(), first.getOkTransition().getActionNode(),
                    "still one shared action node");
        }
    }

    @Nested
    @DisplayName("does not keep — KNOWN GAP")
    class DoesNotKeep {

        @Test
        @DisplayName("KNOWN GAP: the pack's own uuid, replaced by its first stage node's")
        void packUuidIsReplacedByTheFirstNodes() throws IOException {
            StoryPack original = PackFixtures.twoStagePack();

            StoryPack read = roundTrip(original, false);

            // The binary header does carry a pack uuid -- readMetadata() returns it -- but read()
            // ignores it and takes the uuid of whatever stage node sits at sector 0:
            //
            //     new StoryPack(stageNodes.get(new SectorAddr(0)).getUuid(), ...)
            //
            // So a pack whose own uuid differs from its first node's does not survive a raw round
            // trip with its identity intact. This is not academic: LibraryService names a converted
            // artifact `<uuid>.converted_<millis>`, so the file a conversion produces is named after
            // the first node rather than the pack.
            assertEquals("11111111-1111-1111-1111-111111111111", read.getUuid(),
                    "the first stage node's uuid, not the pack's");
            assertNotEquals(PackFixtures.UUID, read.getUuid(),
                    "the pack's own uuid is not what comes back");
        }

        @Test
        @DisplayName("KNOWN GAP: asset names, which the reader invents from the sector offset")
        void assetNamesAreLost() throws IOException {
            StoryPack original = PackFixtures.twoStagePack();
            String nameBefore = original.getStageNodes().get(0).getImage().getName();

            StoryPack read = roundTrip(original, false);
            String nameAfter = read.getStageNodes().get(0).getImage().getName();

            // The binary format has nowhere to put an asset name, so the reader manufactures one
            // from the address it found the asset at. Nothing is broken by this — names are not
            // used to address anything — but a pack that goes raw and comes back has lost them,
            // and the enriched metadata that carries human-facing names is a separate section.
            assertEquals(PackFixtures.UUID.length() > 0 ? "11111111-1111-1111-1111-111111111111.bmp" : "",
                    nameBefore, "the fixture names its assets");
            assertTrue(nameAfter.startsWith("0x"),
                    "the reader names assets after their offset, was: " + nameAfter);
        }

        @Test
        @DisplayName("KNOWN GAP: asset length, when it is not a whole number of sectors")
        void assetLengthIsPaddedToSectors() throws IOException {
            int imageSize = Constants.SECTOR_SIZE + 1;
            StoryPack original = PackFixtures.packWithAssetSizes(imageSize, Constants.SECTOR_SIZE);

            StoryPack read = roundTrip(original, false);
            byte[] after = read.getStageNodes().get(0).getImage().getRawData();

            // Assets occupy whole sectors and the reader reads the whole allocation back, so an
            // asset of 513 bytes returns as 1024. The extra bytes are zero. A consumer that trusts
            // the length gets a longer asset than was written; nothing in the model records the
            // real one.
            assertEquals(Constants.SECTOR_SIZE * 2, after.length,
                    "an asset of " + imageSize + " bytes comes back padded to two sectors");
            assertArrayEquals(original.getStageNodes().get(0).getImage().getRawData(),
                    Arrays.copyOf(after, imageSize),
                    "the bytes that were written are intact at the front");
            for (int i = imageSize; i < after.length; i++) {
                assertEquals(0, after[i], "padding at index " + i);
            }
        }
    }

    @Nested
    @DisplayName("a large menu, in the enriched format")
    class LargeMenu {

        /** The two-stage pack, its action node offering the second stage node {@code options} times, with a name. */
        private StoryPack packWithMenuOf(int options) {
            StoryPack pack = PackFixtures.twoStagePack();
            StageNode second = pack.getStageNodes().get(1);
            ActionNode action = pack.getStageNodes().get(0).getOkTransition().getActionNode();
            action.setOptions(new ArrayList<>(Collections.nCopies(options, second)));
            action.setEnriched(new EnrichedNodeMetadata("Menu", null, null, null));
            return pack;
        }

        @Test
        @DisplayName("keeps the action node metadata while it fits in the sector (152 options)")
        void keepsMetadataWhenItFits() throws IOException {
            StoryPack read = roundTrip(packWithMenuOf(152), true);

            ActionNode action = read.getStageNodes().get(0).getOkTransition().getActionNode();
            assertEquals(152, action.getOptions().size());
            assertNotNull(action.getEnriched());
            assertEquals("Menu", action.getEnriched().getName());
        }

        @Test
        @DisplayName("is written without the action node metadata when it does not fit (160 options), instead of failing")
        void dropsMetadataWhenItDoesNotFit() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new BinaryStoryPackWriter().write(packWithMenuOf(160), out, true);
            StoryPack read = new BinaryStoryPackReader().read(new ByteArrayInputStream(out.toByteArray()));

            ActionNode action = read.getStageNodes().get(0).getOkTransition().getActionNode();
            assertEquals(160, action.getOptions().size());
            assertNull(action.getEnriched(), "the reader does not look for metadata that cannot fit");
            assertEquals(0, (out.size() - Constants.CHECK_BYTES.length) % Constants.SECTOR_SIZE, "sectors stay aligned");
        }

        @Test
        @DisplayName("refuses more options than a sector holds, with a clear message")
        void refusesMoreOptionsThanASectorHolds() {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new BinaryStoryPackWriter().write(packWithMenuOf(257), new ByteArrayOutputStream(), false));
            assertTrue(thrown.getMessage().contains("257"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("refuses")
    class Refuses {

        @Test
        @DisplayName("a pack whose assets are compressed, rather than writing something unreadable")
        void refusesCompressedAssets() {
            StoryPack pack = PackFixtures.twoStagePack();
            pack.getStageNodes().get(0).getImage().setMimeType("image/png");

            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new BinaryStoryPackWriter().write(pack, new ByteArrayOutputStream(), false));

            assertTrue(thrown.getMessage().contains("Uncompress"),
                    "the message should say what to do, was: " + thrown.getMessage());
        }
    }
}
