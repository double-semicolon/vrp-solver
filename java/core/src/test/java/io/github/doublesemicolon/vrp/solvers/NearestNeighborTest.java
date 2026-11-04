package io.github.doublesemicolon.vrp.solvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.json.Cases;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 最近傍法のテスト (python/tests/test_nearest_neighbor.py のうち、Java で必要なもの)。
 * ランダムインスタンスでの Python との一致は SameAsPythonTest が見ている。
 */
class NearestNeighborTest {

    static Instance line(double[][] xs, int[] demands, int capacity, int k) {
        double[][] coords = new double[xs.length + 1][];
        int[] d = new int[demands.length + 1];
        coords[0] = new double[] {0, 0};
        for (int i = 0; i < xs.length; i++) {
            coords[i + 1] = xs[i];
            d[i + 1] = demands[i];
        }
        return new Instance("line", coords, d, capacity, OptionalInt.of(k));
    }

    @ParameterizedTest
    @CsvSource({"toy5, 246", "toy8, 318"})
    void costOnToys(String name, long expected) {
        // toy5 では最適と一致し、toy8 では最適 303 に対して 318 (+5.0%)
        SolveResult res = NearestNeighbor.solve(Cases.get(name).instance());
        assertEquals(expected, res.cost());
        assertTrue(res.feasible());
        assertEquals((long) res.solution().numRoutes(), res.stats().get("routes"));
    }

    @Test
    void skipsTheNearestCustomerWhenItDoesNotFit() {
        // 1 (需要8) を積んだ後、次に近い 2 (需要8) は入らないが、遠い 3 (需要2) は入る
        Instance inst = line(new double[][] {{1, 0}, {2, 0}, {3, 0}}, new int[] {8, 8, 2}, 10, 2);
        assertEquals(Solution.of(new int[] {1, 3}, new int[] {2}), NearestNeighbor.build(inst));
    }

    @Test
    void mayExceedTheVehicleLimitAndSaysSo() {
        // 近い 1, 2 を同じ車に積むと、需要7の 3, 4 が1台に乗らず3台要る
        Instance inst = line(new double[][] {{1, 0}, {2, 0}, {0, 10}, {0, -10}}, new int[] {3, 3, 7, 7}, 10, 2);
        SolveResult res = NearestNeighbor.solve(inst);
        assertEquals(Solution.of(new int[] {1, 2}, new int[] {3}, new int[] {4}), res.solution());
        assertFalse(res.feasible());
        assertEquals(List.of(), Objective.validate(inst, Solution.of(new int[] {1, 3}, new int[] {2, 4})));
    }

    @Test
    void tiesGoToTheSmallerCustomerNumber() {
        // 1 と 2 は depot から同じ距離。番号の小さい 1 を先に訪れる
        Instance inst = line(new double[][] {{0, 5}, {5, 0}}, new int[] {1, 1}, 10, 1);
        assertEquals(Solution.of(new int[] {1, 2}), NearestNeighbor.build(inst));
    }
}
