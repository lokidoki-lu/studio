/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.core.v1.model.StoryPack;
import studio.core.v1.reader.binary.BinaryStoryPackReader;
import studio.core.v1.writer.binary.BinaryStoryPackWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the raw binary reader refuses, instead of returning a pack with silently wrong content.
 *
 * <p>Layout reminders, from {@code BinaryStoryPackWriter}: sector 0 is the header (stage count on
 * its first two bytes), stage node {@code i} is the sector right after, at byte {@code 512 * (i+1)},
 * with its image offset and size at +16 / +20. Offsets inside the pack count from the first stage
 * node, so sector {@code n} is at byte {@code 512 * (n+1)}.
 */
@DisplayName("A raw binary pack reader")
class BinaryPackReaderValidationTest {

    private static final int SECTOR = Constants.SECTOR_SIZE;

    private static byte[] write(StoryPack pack) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new BinaryStoryPackWriter().write(pack, out, false);
        return out.toByteArray();
    }

    private static StoryPack read(byte[] bytes) throws IOException {
        return new BinaryStoryPackReader().read(new ByteArrayInputStream(bytes));
    }

    @Test
    @DisplayName("ends every pack it writes with the check bytes")
    void writerAlwaysEndsWithCheckBytes() throws IOException {
        byte[] bytes = write(PackFixtures.twoStagePack());
        assertArrayEquals(Constants.CHECK_BYTES,
                Arrays.copyOfRange(bytes, bytes.length - Constants.CHECK_BYTES.length, bytes.length));
    }

    @Test
    @DisplayName("still reads a pack without trailing check bytes (only a warning)")
    void readsWithoutCheckBytes() throws IOException {
        byte[] bytes = write(PackFixtures.twoStagePack());
        byte[] withoutCheckBytes = Arrays.copyOf(bytes, bytes.length - Constants.CHECK_BYTES.length);

        StoryPack read = read(withoutCheckBytes);

        assertEquals(2, read.getStageNodes().size());
    }

    @Test
    @DisplayName("refuses a pack truncated in the middle of an asset")
    void refusesTruncatedAsset() throws IOException {
        byte[] bytes = write(PackFixtures.twoStagePack());
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - Constants.CHECK_BYTES.length - 100);

        assertThrows(EOFException.class, () -> read(truncated));
    }

    @Test
    @DisplayName("refuses a pack truncated before its action nodes")
    void refusesTruncatedBeforeActionNodes() throws IOException {
        byte[] bytes = write(PackFixtures.twoStagePack());
        // Header + two stage nodes, then 10 bytes of the action sector.
        byte[] truncated = Arrays.copyOf(bytes, 3 * SECTOR + 10);

        assertThrows(EOFException.class, () -> read(truncated));
    }

    @Test
    @DisplayName("refuses a pack declaring no stage node")
    void refusesZeroStages() throws IOException {
        byte[] bytes = write(PackFixtures.twoStagePack());
        ByteBuffer.wrap(bytes).putShort(0, (short) 0);

        IOException thrown = assertThrows(IOException.class, () -> read(bytes));
        assertTrue(thrown.getMessage().contains("stage node count"), thrown.getMessage());
    }

    @Test
    @DisplayName("refuses a negative asset size")
    void refusesNegativeAssetSize() throws IOException {
        byte[] bytes = write(PackFixtures.twoStagePack());
        ByteBuffer.wrap(bytes).putInt(SECTOR + 20, -3);

        assertThrows(IOException.class, () -> read(bytes));
    }

    @Test
    @DisplayName("refuses an absurd asset size before allocating it")
    void refusesAbsurdAssetSize() throws IOException {
        byte[] bytes = write(PackFixtures.twoStagePack());
        ByteBuffer.wrap(bytes).putInt(SECTOR + 20, Integer.MAX_VALUE);

        assertThrows(IOException.class, () -> read(bytes));
    }

    @Test
    @DisplayName("refuses an asset offset pointing into the stage nodes")
    void refusesAssetOffsetInsideStageNodes() throws IOException {
        byte[] bytes = write(PackFixtures.twoStagePack());
        ByteBuffer.wrap(bytes).putInt(SECTOR + 16, 0);

        assertThrows(IOException.class, () -> read(bytes));
    }

    @Test
    @DisplayName("refuses an action node option that is not a stage node")
    void refusesUnknownOption() throws IOException {
        byte[] bytes = write(PackFixtures.twoStagePack());
        // The single action node is at sector 2 (right after the two stage nodes).
        ByteBuffer.wrap(bytes).putShort(3 * SECTOR, (short) 7);

        IOException thrown = assertThrows(IOException.class, () -> read(bytes));
        assertTrue(thrown.getMessage().contains("unknown stage node"), thrown.getMessage());
    }

    @Test
    @DisplayName("reads exactly the option count, even when an option after node 0 follows")
    void readsExactlyTheOptionCount() throws IOException {
        StoryPack pack = PackFixtures.twoStagePack();
        // Options: first, second, first — zeros in the middle and at the end of the list.
        pack.getStageNodes().get(0).getOkTransition().getActionNode().setOptions(new ArrayList<>(Arrays.asList(
                pack.getStageNodes().get(0), pack.getStageNodes().get(1), pack.getStageNodes().get(0))));

        StoryPack read = read(write(pack));

        assertEquals(3, read.getStageNodes().get(0).getOkTransition().getActionNode().getOptions().size());
        assertEquals(read.getStageNodes().get(1),
                read.getStageNodes().get(0).getOkTransition().getActionNode().getOptions().get(1));
    }

    @Test
    @DisplayName("transitions disagreeing on an option count: the first one read is kept, like the FS reader")
    void disagreeingOptionCountsKeepTheFirst() throws IOException {
        // Options: first, second. Every transition (ok and home of both stage nodes) announces 2.
        byte[] bytes = write(PackFixtures.packWithOptionOnFirstNode());
        // Stage node 1 is at byte 512 * 2; its ok option count at +34, its home option count at +40.
        ByteBuffer patched = ByteBuffer.wrap(bytes);
        patched.putShort(2 * SECTOR + 34, (short) 3);   // larger: would read padding as an option
        patched.putShort(2 * SECTOR + 40, (short) 1);   // smaller

        StoryPack read = read(bytes);

        assertEquals(2, read.getStageNodes().get(0).getOkTransition().getActionNode().getOptions().size(),
                "the count of stage node 0's ok transition, read first, wins");
        assertEquals(read.getStageNodes().get(1),
                read.getStageNodes().get(1).getOkTransition().getActionNode().getOptions().get(1));
        assertEquals(read.getStageNodes().get(0).getOkTransition().getActionNode(),
                read.getStageNodes().get(1).getHomeTransition().getActionNode(),
                "all four transitions still share one action node");
    }

    @Test
    @DisplayName("the writer refuses an option that is not a stage node of the pack")
    void writerRefusesForeignOption() {
        StoryPack pack = PackFixtures.twoStagePack();
        StoryPack other = PackFixtures.twoStagePack();
        pack.getStageNodes().get(0).getOkTransition().getActionNode()
                .setOptions(new ArrayList<>(Arrays.asList(other.getStageNodes().get(1))));

        assertThrows(IllegalArgumentException.class, () -> write(pack));
    }
}
