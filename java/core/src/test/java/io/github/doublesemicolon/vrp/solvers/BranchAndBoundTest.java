package io.github.doublesemicolon.vrp.solvers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.SolveResult;
import io.github.doublesemicolon.vrp.json.Cases;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 枝刈り付き全探索のテスト (python/tests/test_branch_and_bound.py の移植)。
 *
 * <p>参照実装 (BruteForce) との関係を2段階で縛る:
 * <ol>
 *   <li>枝刈りを止めれば探索木が完全に一致すること (1顧客ずつ組み立てる書き換えが等価である証明)
 *   <li>枝刈りありでも同じ最適コストに到達すること (枝刈りが答えを削っていない証明)
 * </ol>
 */
class BranchAndBoundTest {

    // 共通テストデータのうち実行可能解があるもの全部
    static Stream<Instance> instances() {
        return Cases.feasible().stream().map(Cases.Case::instance);
    }

    // 顧客 4 件の nn-over のような極小ケースには刈る余地が無いので除く
    static Stream<Instance> prunable() {
        return instances().filter(i -> i.n() >= 5);
    }

    record Leaf(Solution solution, long cost) {}

    static List<Leaf> unprunedLeaves(Instance inst, BranchAndBound[] out) {
        BranchAndBound b = new BranchAndBound(inst, null, false);
        List<Leaf> found = new ArrayList<>();
        b.onLeaf = (s, c) -> found.add(new Leaf(s, c));
        b.search();
        out[0] = b;
        return found;
    }

    @ParameterizedTest
    @MethodSource("instances")
    void unprunedSearchTreeMatchesReference(Instance inst) {
        SolveResult ref = BruteForce.solve(inst);
        BranchAndBound[] b = new BranchAndBound[1];
        List<Leaf> found = unprunedLeaves(inst, b);

        assertEquals(ref.stats().get("nodes"), b[0].nodes);
        assertEquals(ref.stats().get("solutions"), b[0].leaves);
        Set<List<List<Integer>>> enumerated = new HashSet<>();
        BruteForce.enumerate(inst, s -> enumerated.add(Oracle.canonical(s)));
        assertEquals(enumerated, new HashSet<>(found.stream().map(l -> Oracle.canonical(l.solution())).toList()));
    }

    @ParameterizedTest
    @MethodSource("instances")
    void incrementalCostMatchesFullRecomputation(Instance inst) {
        // 差分で積算したコストがずれると、枝刈りの判断ごと壊れる。最適コストの一致だけでは検出できない
        for (Leaf l : unprunedLeaves(inst, new BranchAndBound[1])) {
            assertEquals(Objective.solutionCost(inst, l.solution()), l.cost(), () -> "積算コストがずれている: " + l);
        }
    }

    @ParameterizedTest
    @MethodSource("instances")
    void findsTheSameOptimumAsBruteForce(Instance inst) {
        SolveResult res = BranchAndBound.solve(inst);
        assertEquals(BruteForce.solve(inst).cost(), res.cost());
        assertTrue(res.feasible());
        assertEquals("bnb", res.solver());
        assertNull(res.stats().get("initial_upper_bound"));
        assertTrue(res.stats().containsKey("initial_upper_bound"));
        assertFalse(res.stats().containsKey("nn_seed"));
    }

    @ParameterizedTest
    @MethodSource("prunable")
    void pruningReducesTheSearch(Instance inst) {
        assertTrue((long) BranchAndBound.solve(inst).stats().get("nodes")
                < (long) BruteForce.solve(inst).stats().get("nodes"));
    }

    @ParameterizedTest
    @MethodSource("instances")
    void incumbentNeverMakesTheSearchLarger(Instance inst) {
        // 初期上界が厳しくなれば刈れる量は増えこそすれ減らない (探索順は同一なので)
        List<Solution> first = new ArrayList<>();
        BruteForce.enumerate(inst, s -> {
            if (first.isEmpty()) {
                first.add(s);
            }
        });
        SolveResult without = BranchAndBound.solve(inst);
        SolveResult seeded = BranchAndBound.solve(inst, first.get(0));
        assertEquals(without.cost(), seeded.cost());
        assertTrue((long) seeded.stats().get("nodes") <= (long) without.stats().get("nodes"));
        long ub = Objective.solutionCost(inst, first.get(0));
        assertEquals(ub, seeded.stats().get("initial_upper_bound"));
        assertEquals(ub, seeded.history().get(0).cost());
    }

    @Test
    void incumbentEqualToOptimumIsStillReturned() {
        // 上界を数値ではなく暫定解として持つ理由の回帰テスト
        Instance toy5 = Cases.get("toy5").instance();
        SolveResult opt = BruteForce.solve(toy5);
        SolveResult res = BranchAndBound.solve(toy5, opt.solution());
        assertEquals(0L, res.stats().get("leaves"), "同コスト解は全て刈られるので新しい解は見つからない");
        assertEquals(opt.cost(), res.cost());
        assertTrue(res.feasible());
    }

    @Test
    void infeasibleIncumbentIsRejected() {
        // 制約を破った解のコストで刈ると、真の最適解を落としうる
        Instance toy5 = Cases.get("toy5").instance();
        assertThrows(IllegalArgumentException.class,
                () -> BranchAndBound.solve(toy5, Solution.of(new int[] {1, 2, 3, 4, 5})));   // 総需要 53 > 容量 30
    }

    @Test
    void infeasibleInstanceIsReportedNotSilentlyWrong() {
        Instance impossible = Cases.get("toy5-impossible").instance();   // 22 x 2 = 44 < 53
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BranchAndBound.solve(impossible));
        assertTrue(e.getMessage().contains("実行可能解が存在しない"));
    }

    // ---------------------------------------------------------------- 最近傍法の初期解

    @ParameterizedTest
    @MethodSource("instances")
    void nnSeedReachesTheSameOptimumWithNoLargerSearch(Instance inst) {
        SolveResult without = BranchAndBound.solve(inst);
        SolveResult seeded = BranchAndBound.solveWithNnSeed(inst);
        assertEquals("bnb+nn", seeded.solver());
        assertEquals(without.cost(), seeded.cost());
        assertTrue((long) seeded.stats().get("nodes") <= (long) without.stats().get("nodes"));
        if ((boolean) seeded.stats().get("nn_seed")) {
            assertEquals(Objective.solutionCost(inst, NearestNeighbor.build(inst)),
                    seeded.stats().get("initial_upper_bound"));
        }
    }

    @Test
    void nnSeedShrinksTheSearchOnToy8() {
        // 初期上界 318 (最適 303) で探索ノードがほぼ半減する
        Instance toy8 = Cases.get("toy8").instance();
        SolveResult seeded = BranchAndBound.solveWithNnSeed(toy8);
        assertEquals(true, seeded.stats().get("nn_seed"));
        assertTrue((long) seeded.stats().get("nodes") < (long) BranchAndBound.solve(toy8).stats().get("nodes") * 0.7);
    }

    @Test
    void infeasibleNnIsNotUsedAsUpperBound() {
        // 最近傍法が K 台を超えたら、そのコストで刈ってはいけない
        Instance inst = Cases.get("nn-over").instance();
        assertFalse(Objective.isFeasible(inst, NearestNeighbor.build(inst)));
        SolveResult res = BranchAndBound.solveWithNnSeed(inst);
        assertEquals(false, res.stats().get("nn_seed"));
        assertNull(res.stats().get("initial_upper_bound"));
        assertEquals(BruteForce.solve(inst).cost(), res.cost());
    }

    @Test
    void nnIsAlwaysUsableWhenVehiclesAreUnlimited() {
        Instance inst = Cases.get("nn-over-unlimited").instance();
        Solution nn = NearestNeighbor.build(inst);
        assertEquals(3, nn.numRoutes());
        assertTrue(Objective.isFeasible(inst, nn));
        SolveResult res = BranchAndBound.solveWithNnSeed(inst);
        assertEquals(true, res.stats().get("nn_seed"));
        assertEquals(BruteForce.solve(inst).cost(), res.cost());
    }
}
