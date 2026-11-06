package io.github.doublesemicolon.vrp.alns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class WeightsTest {
    @Test
    void segmentEndMovesTheWeightTowardTheAveragePoints() {
        Weights w = new Weights(3, true);
        // 演算子 0: 2 回使って 33 点と 0 点 (平均 16.5)。演算子 1: 1 回使って 0 点。演算子 2: 使わない
        w.used(0);
        w.reward(0, Weights.BEST);
        w.used(0);
        w.used(1);
        w.endSegment();
        assertEquals(1 * 0.9 + 0.1 * 16.5, w.weight(0), 1e-12);
        assertEquals(0.9, w.weight(1), 1e-12);
        assertEquals(1.0, w.weight(2), 1e-12, "使われなかった演算子の重みは変えない");

        // 点と回数は区間ごとに消える。次の区間で使わなければ重みはそのまま
        w.endSegment();
        assertEquals(List.of(1.0, 2.55, 2.55), w.trace(0).stream().map(x -> Math.round(x * 1e9) / 1e9).toList());
        assertEquals(3, w.trace(1).size());
    }

    @Test
    void fixedWeightsStayAtOne() {
        Weights w = new Weights(2, false);
        w.used(0);
        w.reward(0, Weights.BEST);
        w.used(1);
        w.endSegment();
        assertEquals(List.of(1.0, 1.0), w.trace(0));
        assertEquals(List.of(1.0, 1.0), w.trace(1));
    }

    @Test
    void selectionIsProportionalToTheWeights() {
        Weights w = new Weights(3, true);
        w.used(0);
        w.reward(0, Weights.BEST);   // 0.9 + 3.3 = 4.2
        w.used(1);                   // 0.9
        w.endSegment();              // 演算子 2 は 1.0。合計 6.1
        int[] count = new int[3];
        Random rnd = new Random(0);
        int draws = 61000;
        for (int k = 0; k < draws; k++) {
            count[w.select(rnd)]++;
        }
        assertEquals(42000, count[0], 600);
        assertEquals(9000, count[1], 600);
        assertEquals(10000, count[2], 600);
    }

    @Test
    void selectionUsesExactlyOneRandomNumber() {
        // 乱数を使う数が変わると、同じシードでも後の反復がずれる
        Weights w = new Weights(5, true);
        Random a = new Random(3);
        Random b = new Random(3);
        for (int k = 0; k < 100; k++) {
            w.select(a);
            b.nextDouble();
        }
        assertEquals(b.nextLong(), a.nextLong());
    }

    @Test
    void uniformSelectionCoversEveryOperator() {
        Weights w = new Weights(4, false);
        int[] count = new int[4];
        Random rnd = new Random(1);
        for (int k = 0; k < 40000; k++) {
            count[w.select(rnd)]++;
        }
        for (int c : count) {
            assertTrue(Math.abs(c - 10000) < 500, java.util.Arrays.toString(count));
        }
    }
}
