package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class NeighborListTest {
    // depot は原点。顧客 1..5 は x 軸上の x = 1, 3, 5, 6, 9
    static final Instance LINE = new Instance("line",
            new double[][] {{0, 0}, {1, 0}, {3, 0}, {5, 0}, {6, 0}, {9, 0}},
            new int[] {0, 1, 1, 1, 1, 1}, 5, OptionalInt.empty());

    @Test
    void nearestFirstAndTiesByNumber() {
        // 顧客 2 (x=3) から見ると 1 と 3 がどちらも距離 2
        NeighborList near = NeighborList.of(LINE, 2);
        assertArrayEquals(new int[] {2, 3}, near.near(1));
        assertArrayEquals(new int[] {1, 3}, near.near(2));   // 同じ距離なら番号の小さい順。depot は入らない
        assertArrayEquals(new int[] {4, 3}, near.near(5));
        assertTrue(near.contains(2, 3));
        assertFalse(near.contains(2, 4));
        assertFalse(near.contains(1, 0));
        assertFalse(near.contains(0, 1));
    }

    @Test
    void kIsCappedAtEveryoneElse() {
        NeighborList near = NeighborList.of(LINE, 100);
        assertEquals(4, near.k());
        assertArrayEquals(new int[] {2, 3, 4, 5}, near.near(1));
        assertThrows(IllegalArgumentException.class, () -> NeighborList.of(LINE, 0));
    }
}
