/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.Transition;
import studio.core.v1.reader.archive.ArchiveStoryPackReader;
import studio.core.v1.reader.fs.FsStoryPackReader;
import studio.core.v1.utils.StreamingPackConversion;
import studio.core.v1.writer.archive.ArchiveStoryPackWriter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A random option (a transition whose option index is -1, written by the editor for "au hasard")
 * survives archive → FS → archive: the FS reader used to drop it, leaving a dead end.
 */
@DisplayName("A random option, through the FS format")
class RandomOptionRoundTripTest {

    @TempDir
    Path tmp;

    /** The transitions of a pack whose option index is -1, with their action node's option count. */
    private static List<Integer> randomTransitions(StoryPack pack) {
        List<Integer> optionCounts = new ArrayList<>();
        for (StageNode stage : pack.getStageNodes()) {
            for (Transition t : new Transition[]{stage.getOkTransition(), stage.getHomeTransition()}) {
                if (t != null && t.getOptionIndex() == -1) {
                    assertNotNull(t.getActionNode(), "a random transition keeps its action node");
                    optionCounts.add(t.getActionNode().getOptions().size());
                }
            }
        }
        return optionCounts;
    }

    private Path fsPack() throws Exception {
        Path zip = tmp.resolve("source.zip");
        StreamingFixtures.richArchive(false).writeTo(zip);
        try (InputStream in = Files.newInputStream(zip)) {
            assertEquals(List.of(3), randomTransitions(new ArchiveStoryPackReader().read(in)),
                    "fixture: one random ok transition, into the 3-option menu");
        }
        return StreamingPackConversion.archiveToFs(zip, Files.createDirectories(tmp.resolve("fs"))).getProduced();
    }

    @Test
    @DisplayName("is read back from the FS pack with its action node")
    void fsReaderKeepsTheRandomTransition() throws Exception {
        StoryPack read = new FsStoryPackReader().read(fsPack());
        assertEquals(List.of(3), randomTransitions(read));
    }

    @Test
    @DisplayName("is still there once the FS pack is converted back to an archive (streaming and in memory, same bytes)")
    void survivesFsToArchive() throws Exception {
        Path fs = fsPack();

        ByteArrayOutputStream streaming = new ByteArrayOutputStream();
        StreamingPackConversion.fsToArchive(fs, streaming);
        ByteArrayOutputStream inMemory = new ByteArrayOutputStream();
        new ArchiveStoryPackWriter().write(new FsStoryPackReader().read(fs), inMemory);

        StoryPack back = new ArchiveStoryPackReader().read(new ByteArrayInputStream(streaming.toByteArray()));
        assertEquals(List.of(3), randomTransitions(back), "the archive keeps optionIndex -1");

        Map<String, byte[]> expected = StreamingConversionEquivalenceTest.zipEntries(new ByteArrayInputStream(inMemory.toByteArray()));
        Map<String, byte[]> actual = StreamingConversionEquivalenceTest.zipEntries(new ByteArrayInputStream(streaming.toByteArray()));
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()));
        for (String entry : expected.keySet()) {
            if (entry.equals("story.json")) {
                assertEquals(StreamingConversionEquivalenceTest.canonicalStory(expected.get(entry)),
                        StreamingConversionEquivalenceTest.canonicalStory(actual.get(entry)));
            } else {
                assertArrayEquals(expected.get(entry), actual.get(entry), entry);
            }
        }
    }

    @Test
    @DisplayName("an option index below -1 or past the options is still refused")
    void outOfRangeOptionIsRefused() throws Exception {
        Path fs = fsPack();
        Path ni = fs.resolve("ni");
        byte[] bytes = Files.readAllBytes(ni);
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        // Node 0's ok transition: li index at +8, option count at +12, selected option at +16
        int optionCount = bb.getInt(512 + 12);
        assertTrue(optionCount > 0, "fixture: node 0 has an ok transition");

        bb.putInt(512 + 16, optionCount);
        Files.write(ni, bytes);
        assertThrows(IOException.class, () -> new FsStoryPackReader().read(fs));

        bb.putInt(512 + 16, -2);
        Files.write(ni, bytes);
        assertThrows(IOException.class, () -> new FsStoryPackReader().read(fs));
    }
}
