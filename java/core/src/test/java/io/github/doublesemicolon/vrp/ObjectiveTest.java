package io.github.doublesemicolon.vrp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** python/tests/test_objective.py の移植。 */
class ObjectiveTest {

    // depot を原点に、顧客を正方形の頂点に置く。距離は手で数えられる。
    //
    //   2 ---- 3        (0,4) ---- (4,4)
    //   |      |
    //   0 ---- 1        (0,0) ---- (4,0)
    //
    // 0 と 3 は対角 sqrt(32) = 5.66 -> 6
    static final double[][] SQUARE = {{0, 0}, {4, 0}, {0, 4}, {4, 4}};
    static final Instance INST = new Instance("square", SQUARE, new int[] {0, 3, 3, 4}, 7, OptionalInt.of(2));

    static List<Integer> route(Integer... cs) {
        return List.of(cs);
    }

    @Test
    void routeCostIncludesDepotBothEnds() {
        assertEquals(4 + 4, Objective.routeCost(INST, route(1)));
        assertEquals(4 + 4 + 4 + 4, Objective.routeCost(INST, route(1, 3, 2)));
        assertEquals(6 + 6, Objective.routeCost(INST, route(3)));
    }

    @Test
    void emptyRouteCostsNothing() {
        assertEquals(0, Objective.routeCost(INST, route()));
    }

    @Test
    void routeDirectionDoesNotChangeCost() {
        assertEquals(Objective.routeCost(INST, route(1, 3)), Objective.routeCost(INST, route(3, 1)));
    }

    @Test
    void routeLoad() {
        assertEquals(7, Objective.routeLoad(INST, route(1, 3)));
        assertEquals(0, Objective.routeLoad(INST, route()));
    }

    @Test
    void solutionCostSumsRoutes() {
        assertEquals((4 + 4 + 6) + (4 + 4), Objective.solutionCost(INST, Solution.of(new int[] {1, 3}, new int[] {2})));
    }

    @Test
    void feasibleSolution() {
        Solution sol = Solution.of(new int[] {1, 3}, new int[] {2});
        assertEquals(List.of(), Objective.validate(INST, sol));
        assertTrue(Objective.isFeasible(INST, sol));
    }

    static Stream<Arguments> infeasible() {
        return Stream.of(
                Arguments.of(new int[][] {{1, 2, 3}}, "容量超過"),
                Arguments.of(new int[][] {{1}, {3}}, "顧客 2 が未訪問"),
                Arguments.of(new int[][] {{1, 3}, {2, 1}}, "顧客 1 を 2 回訪問"),
                Arguments.of(new int[][] {{1}, {2}, {3}}, "車両数超過"),
                Arguments.of(new int[][] {{1, 3}, {2, 0}}, "depot(0)"),
                Arguments.of(new int[][] {{1, 3}, {2, 9}}, "存在しない顧客 9"),
                Arguments.of(new int[][] {{1, 3}, {2, -1}}, "存在しない顧客 -1"));
    }

    @ParameterizedTest
    @MethodSource("infeasible")
    void infeasibleSolutionIsReported(int[][] routes, String expected) {
        List<String> errors = Objective.validate(INST, Solution.of(routes));
        assertTrue(errors.stream().anyMatch(e -> e.contains(expected)), errors::toString);
        assertFalse(Objective.isFeasible(INST, Solution.of(routes)));
    }

    @Test
    void unknownCustomerDoesNotCountTowardLoad() {
        // ルート [2, 3] の積載は 7 で容量ちょうど。-1 を数えると容量超過と誤判定する
        List<String> errors = Objective.validate(INST, Solution.of(new int[] {1}, new int[] {2, 3, -1}));
        assertTrue(errors.stream().noneMatch(e -> e.contains("容量超過")), errors::toString);
    }

    @Test
    void costOfAnUnknownCustomerIsAnErrorNotAValue() {
        // Python では D[0][-1] が末尾の行を黙って読む。Java では番号を名指しして止める
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Objective.routeCost(INST, route(2, -1)));
        assertTrue(e.getMessage().contains("-1"));
    }

    @Test
    void unlimitedVehiclesSkipsTheCountCheck() {
        Solution oneEach = Solution.of(new int[] {1}, new int[] {2}, new int[] {3});
        assertTrue(Objective.validate(INST, oneEach).stream().anyMatch(e -> e.contains("車両数超過")));

        Instance unlimited = new Instance("square-unlimited", SQUARE, INST.demands(), 7, OptionalInt.empty());
        assertEquals(List.of(), Objective.validate(unlimited, oneEach));
    }

    @Test
    void buildRecomputesCostAndFeasibility() {
        Solution sol = Solution.of(new int[] {1, 2, 3});
        SolveResult r = SolveResult.build(INST, "dummy", sol, 0.0, Map.of("nodes", 3), List.of());
        assertEquals(Objective.solutionCost(INST, sol), r.cost());
        assertFalse(r.feasible());
        assertEquals(3L, r.stats().get("nodes"), "Integer は Long に揃える");
    }

    static SolveResult withStat(Object v) {
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("x", v);
        return SolveResult.build(INST, "dummy", Solution.of(new int[] {1, 3}, new int[] {2}), 0.0, stats, List.of());
    }

    @Test
    void statsRejectValuesThatJsonCannotRoundTrip() {
        for (Object bad : new Object[] {List.of(List.of(1)), List.of(1, "2"), List.of(true), Map.of("a", 1),
                new int[] {1}, 1.5f}) {
            assertThrows(IllegalArgumentException.class, () -> withStat(bad), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> withStat(java.util.Arrays.asList(1L, null)));
    }

    @Test
    void numberListsAreNormalizedToWhatJsonReadsBack() {
        // 整数だけなら Long の List。小数が 1 つでもあれば全て Double (JSON に書くと [1.0, 0.5] になるため)
        assertEquals(List.of(1L, 2L), withStat(List.of(1, 2L)).stats().get("x"));
        assertEquals(List.of(1.0, 0.5), withStat(List.of(1, 0.5)).stats().get("x"));
        assertEquals(List.of(), withStat(List.of()).stats().get("x"));
        java.util.List<Object> source = new java.util.ArrayList<>(List.of(1L));
        SolveResult r = withStat(source);
        source.add(2L);
        assertEquals(List.of(1L), r.stats().get("x"), "渡した List を後から変えても影響しない");
    }
}
