package io.github.doublesemicolon.vrp.solvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Improvement;
import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.json.Cases;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 全探索 (参照実装) のテスト (python/tests/test_bruteforce.py と、test_reference.py の参照値)。
 *
 * <p>列挙そのものが正しいことを独立オラクルとの突き合わせで確かめ、そのうえで
 * testdata の参照値 (最適値・解の総数・探索ノード数) と一致することを確かめる。
 */
class BruteForceTest {
    static final double[][] COORDS = {{0, 0}, {6, 1}, {2, 7}, {-5, 3}, {-3, -6}, {4, -4}, {8, 6}};
    static final int[] DEMANDS = {0, 3, 4, 2, 5, 3, 4};

    static Instance make(int n, int capacity, Integer k) {
        return new Instance("t" + n + "-q" + capacity + "-k" + k, Arrays.copyOf(COORDS, n + 1),
                Arrays.copyOf(DEMANDS, n + 1), capacity, k == null ? OptionalInt.empty() : OptionalInt.of(k));
    }

    // 容量・車両数の効き方が違う組み合わせを並べる
    static Stream<Instance> instances() {
        return Stream.of(
                make(1, 5, 1),      // 顧客1件
                make(4, 100, 1),    // 1台で全員回れる = 巡回セールスマン
                make(4, 100, 4),    // 容量が効かず、台数の分け方だけが自由
                make(5, 7, 3),      // 容量がきつく、分け方が限られる
                make(6, 9, 3),
                make(5, 7, null),   // 上の (5, 7, 3) から上限を外す。4台以上の解が増える
                make(4, 100, null));
    }

    static List<Solution> all(Instance inst) {
        List<Solution> out = new ArrayList<>();
        BruteForce.enumerate(inst, out::add);
        return out;
    }

    @ParameterizedTest
    @MethodSource("instances")
    void everyEnumeratedSolutionIsFeasible(Instance inst) {
        for (Solution s : all(inst)) {
            assertTrue(Objective.isFeasible(inst, s), () -> "実行不可能な解を列挙した: " + s);
        }
    }

    @ParameterizedTest
    @MethodSource("instances")
    void enumerationHasNoDuplicates(Instance inst) {
        List<List<List<Integer>>> seen = all(inst).stream().map(Oracle::canonical).toList();
        assertEquals(seen.size(), new HashSet<>(seen).size());
    }

    @ParameterizedTest
    @MethodSource("instances")
    void enumerationIsExhaustive(Instance inst) {
        Set<List<List<Integer>>> enumerated = new HashSet<>(all(inst).stream().map(Oracle::canonical).toList());
        assertEquals(Oracle.naiveAllSolutions(inst), enumerated);
    }

    @ParameterizedTest
    @MethodSource("instances")
    void bruteForceFindsOptimum(Instance inst) {
        Set<List<List<Integer>>> oracle = Oracle.naiveAllSolutions(inst);
        long oracleBest = oracle.stream().mapToLong(s -> Objective.solutionCost(inst, new Solution(s))).min()
                .orElseThrow();
        SolveResult res = BruteForce.solve(inst);
        assertEquals(oracleBest, res.cost());
        assertTrue(res.feasible());
        assertEquals((long) oracle.size(), res.stats().get("solutions"));
        assertTrue((long) res.stats().get("nodes") >= (long) res.stats().get("solutions"));
        assertEquals("brute", res.solver());
    }

    @ParameterizedTest
    @MethodSource("instances")
    void historyIsStrictlyImprovingAndEndsAtOptimum(Instance inst) {
        SolveResult res = BruteForce.solve(inst);
        List<Long> costs = res.history().stream().map(Improvement::cost).toList();
        for (int i = 1; i < costs.size(); i++) {
            assertTrue(costs.get(i) < costs.get(i - 1));
        }
        assertEquals(res.cost(), costs.get(costs.size() - 1));
    }

    @Test
    void noFeasibleSolutionThrows() {
        // 需要の合計 14 に対して 容量 5 x 2台 = 10 しか積めない
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BruteForce.solve(make(4, 5, 2)));
        assertTrue(e.getMessage().contains("実行可能解が存在しない"));
    }

    @Test
    void unlimitedVehiclesEnumeratesMoreThanABindingLimit() {
        Set<List<List<Integer>>> limited = new HashSet<>(all(make(5, 7, 3)).stream().map(Oracle::canonical).toList());
        Set<List<List<Integer>>> unlimited = new HashSet<>(all(make(5, 7, null)).stream().map(Oracle::canonical).toList());
        assertTrue(unlimited.containsAll(limited) && unlimited.size() > limited.size());
        assertEquals(5, unlimited.stream().mapToInt(List::size).max().orElseThrow());   // 1顧客1台まで許される
    }

    // ---------------------------------------------------------------- testdata の参照値

    static Stream<Cases.Case> cases() {
        return Cases.all().values().stream();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void matchesTheReferenceValues(Cases.Case c) {
        // 解の総数と探索ノード数は、列挙の順序や重複の潰し方まで Python と同じでなければ一致しない
        List<Solution> sols = all(c.instance());
        long nodes = BruteForce.enumerate(c.instance(), s -> {});
        assertEquals(c.nSolutions(), sols.size(), c.name() + " の解の総数");
        assertEquals(c.nNodes(), nodes, c.name() + " の探索ノード数");
        if (c.optimalCost().isPresent()) {
            assertEquals(c.optimalCost().getAsLong(), BruteForce.solve(c.instance()).cost());
        } else {
            assertThrows(IllegalArgumentException.class, () -> BruteForce.solve(c.instance()));
        }
    }
}
