/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.raw;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.driver.model.raw.RawStoryPackInfos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a firmware 1.x upload puts a pack. Sectors are relative to the index sector (sector 0), a
 * pack occupies {@code [start, start + size - 1]}, and the free sectors between a last used sector L
 * and the next used sector N are {@code L+1 .. N-1}: {@code N - L - 1} of them.
 */
class RawPackPlacementTest {

    private static RawStoryPackInfos pack(int startSector, int sizeInSectors) {
        return new RawStoryPackInfos(null, (short) 0, startSector, sizeInSectors, (short) 0, (short) 0);
    }

    private static List<RawStoryPackInfos> packs(RawStoryPackInfos... packs) {
        return new ArrayList<>(List.of(packs));
    }

    @Test
    @DisplayName("a gap between two packs takes a pack of exactly its size")
    void exactFitBetweenPacks() {
        // A occupies 1..10, B occupies 21..25: sectors 11..20 are free, 10 of them.
        List<RawStoryPackInfos> index = packs(pack(1, 10), pack(21, 5));

        assertEquals(Optional.of(11), RawStoryTellerAsyncDriver.findSuitableSectorBetweenPacks(index, 10));
    }

    @Test
    @DisplayName("a pack one sector larger than the gap is NOT placed there (it would overwrite the next pack)")
    void oneSectorTooLargeForTheGap() {
        List<RawStoryPackInfos> index = packs(pack(1, 10), pack(21, 5));

        // The previous computation (N - L = 11) accepted this at sector 11, ending on sector 21,
        // which is the first sector of B.
        assertEquals(Optional.empty(), RawStoryTellerAsyncDriver.findSuitableSectorBetweenPacks(index, 11));
        assertEquals(Optional.of(26), RawStoryTellerAsyncDriver.findFirstSuitableSector(index, 1000, 11),
                "it goes after the last pack instead");
    }

    @Test
    @DisplayName("the gap before the first pack excludes the index sector and the pack's first sector")
    void firstGap() {
        // Sectors 1..4 are free before a pack starting at 5.
        List<RawStoryPackInfos> index = packs(pack(5, 3));

        assertEquals(Optional.of(1), RawStoryTellerAsyncDriver.findSuitableSectorBetweenPacks(index, 4));
        assertEquals(Optional.empty(), RawStoryTellerAsyncDriver.findSuitableSectorBetweenPacks(index, 5));
    }

    @Test
    @DisplayName("packs are considered in sector order, whatever the index order, and the index is not reordered")
    void unorderedIndex() {
        List<RawStoryPackInfos> index = packs(pack(21, 5), pack(1, 10));

        assertEquals(Optional.of(11), RawStoryTellerAsyncDriver.findSuitableSectorBetweenPacks(index, 10));
        assertEquals(21, index.get(0).getStartSector(), "the caller's list is left in its order");
    }

    @Test
    @DisplayName("at the end of the card, the last usable relative sector is size - 1")
    void endOfCard() {
        // Last pack ends on 25; card of 100 relative sectors (0..99): 26..99 are free, 74 of them.
        List<RawStoryPackInfos> index = packs(pack(1, 25));

        assertEquals(Optional.of(26), RawStoryTellerAsyncDriver.findFirstSuitableSector(index, 100, 74));
        assertEquals(Optional.empty(), RawStoryTellerAsyncDriver.findFirstSuitableSector(index, 100, 75));
    }

    @Test
    @DisplayName("an empty index places the first pack right after the index sector")
    void emptyIndex() {
        assertEquals(Optional.of(1), RawStoryTellerAsyncDriver.findFirstSuitableSector(packs(), 100, 99));
        assertEquals(Optional.empty(), RawStoryTellerAsyncDriver.findFirstSuitableSector(packs(), 100, 100));
    }

    @Test
    @DisplayName("adjacent packs leave no gap")
    void adjacentPacks() {
        List<RawStoryPackInfos> index = packs(pack(1, 10), pack(11, 10));

        assertEquals(Optional.empty(), RawStoryTellerAsyncDriver.findSuitableSectorBetweenPacks(index, 1));
        assertEquals(20, RawStoryTellerAsyncDriver.lastUsedSector(index));
    }
}
