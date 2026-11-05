package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Objective;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.TestData;
import io.github.doublesemicolon.vrp.json.Cases;
import io.github.doublesemicolon.vrp.json.VrpJson;
import io.github.doublesemicolon.vrp.solvers.BruteForce;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RelocateTest {
    static final Relocate RELOCATE = new Relocate();

    /** relocate の定義そのまま: 顧客を 1 人抜き (空になったルートは消す)、あらゆる位置か新しいルートに入れる。 */
    static List<Solution> naive(Instance inst, Solution sol) {
        List<Solution> out = new ArrayList<>();
        List<List<Integer>> base = NeighborhoodContract.mutable(sol);
        for (int r = 0; r < base.size(); r++) {
            for (int p = 0; p < base.get(r).size(); p++) {
                List<List<Integer>> rest = NeighborhoodContract.mutable(NeighborhoodContract.solution(base));
                int c = rest.get(r).remove(p);
                rest = NeighborhoodContract.mutable(NeighborhoodContract.solution(rest));
                for (int to = 0; to < rest.size(); to++) {
                    for (int t = 0; t <= rest.get(to).size(); t++) {
                        List<List<Integer>> x = NeighborhoodContract.mutable(NeighborhoodContract.solution(rest));
                        x.get(to).add(t, c);
                        out.add(NeighborhoodContract.solution(x));
                    }
                }
                List<List<Integer>> x = NeighborhoodContract.mutable(NeighborhoodContract.solution(rest));
                x.add(new ArrayList<>(List.of(c)));
                out.add(NeighborhoodContract.solution(x));
            }
        }
        return out;
    }

    static Instance stored(String name) {
        try {
            return VrpJson.readRun(TestData.runs().resolve("python/" + name + ".json")).instance();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- 約束 (共通テスト)

    // 車両数の上限がちょうどのもの (toy5 は K=2)、余裕のあるもの (toy8-K4)、上限なし、容量がきついもの
    @ParameterizedTest
    @ValueSource(strings = {"toy5", "toy5-K3", "toy5-tight", "toy5-unlimited", "nn-over", "toy8-tight", "toy8"})
    void contractHoldsFromEveryFeasibleSolution(String name) {
        // 全探索で列挙できる実行可能解の全てを出発点にする
        Instance inst = Cases.get(name).instance();
        long n = BruteForce.enumerate(inst, sol -> NeighborhoodContract.check(inst, sol, RELOCATE, RelocateTest::naive));
        assertTrue(n > 0);
    }

    static Stream<String> randomInstances() {
        return Stream.of("rand30-uniform-s0", "rand30-clustered-s1", "rand50-uniform-s0", "rand50-clustered-s2");
    }

    @ParameterizedTest
    @MethodSource("randomInstances")
    void contractHoldsAlongTheDescent(String name) {
        // 最近傍法の解から局所最適まで、途中の全ての解で確かめる
        Instance inst = stored(name);
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        NeighborhoodContract.check(inst, s.toSolution(), RELOCATE, RelocateTest::naive);
        LocalSearch.descend(s, RELOCATE, cost ->
                NeighborhoodContract.check(inst, s.toSolution(), RELOCATE, RelocateTest::naive));
    }

    @Test
    void contractHoldsOnA200CustomerInstance() {
        Instance inst = stored("rand200-clustered-s1");
        NeighborhoodContract.check(inst, NearestNeighbor.build(inst), RELOCATE, RelocateTest::naive);
    }

    // ---------------------------------------------------------------- 局所探索

    @ParameterizedTest
    @MethodSource("randomInstances")
    void descentEndsAtALocalOptimumAndOnlyImproves(String name) {
        Instance inst = stored(name);
        Solution start = NearestNeighbor.build(inst);
        Routes s = Routes.of(inst, start);
        List<Long> costs = new ArrayList<>(List.of(s.cost()));
        long moves = LocalSearch.descend(s, RELOCATE, costs::add);

        assertEquals(moves, costs.size() - 1);
        for (int k = 1; k < costs.size(); k++) {
            assertTrue(costs.get(k) < costs.get(k - 1), "改善しない手を適用した");
        }
        Solution end = s.toSolution();
        assertEquals(List.of(), Objective.validate(inst, end));
        assertEquals(Objective.solutionCost(inst, end), s.cost());
        assertTrue(moves > 0 && s.cost() < Objective.solutionCost(inst, start), "最近傍法の解を改善できる");
        NeighborhoodContract.assertLocalOptimum(inst, end, RelocateTest::naive);
    }

    // ---------------------------------------------------------------- 個別の場面

    static Instance line(int capacity, OptionalInt k) {
        // depot を原点に、x 軸上に顧客を並べる。距離が手で計算できる
        double[][] coords = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {10, 0}};
        return new Instance("line", coords, new int[] {0, 1, 1, 1, 1}, capacity, k);
    }

    @Test
    void movingTheOnlyCustomerOutRemovesTheRoute() {
        Instance inst = line(4, OptionalInt.empty());
        Routes s = Routes.of(inst, Solution.of(new int[] {1, 2, 3}, new int[] {4}));
        // 4 を 3 の後ろへ。ルート [4] (往復 20) が消え、[1, 2, 3, 4] (往復 20) になる
        new Relocate.RelocateMove(1, 0, 0, 3, -6).apply(s);
        assertEquals(Solution.of(new int[] {1, 2, 3, 4}), s.toSolution());
        assertEquals(20, s.cost());
    }

    @Test
    void aNewRouteIsOfferedOnlyWhenAVehicleIsLeft() {
        Solution sol = Solution.of(new int[] {1, 2, 3, 4});
        for (int k : new int[] {1, 2}) {
            Instance inst = line(4, OptionalInt.of(k));
            List<Relocate.RelocateMove> toNew = new ArrayList<>();
            Routes s = Routes.of(inst, sol);
            RELOCATE.scan(s, m -> {
                Relocate.RelocateMove r = (Relocate.RelocateMove) m;
                if (r.to() == s.count()) {
                    toNew.add(r);
                }
            });
            assertEquals(k == 1 ? 0 : 4, toNew.size(), "K=" + k);
        }
    }

    @Test
    void capacityIsCheckedAgainstTheTargetRoute() {
        // 容量 2 で [1, 2] と [3]。3 を [1, 2] に入れる手は出ない。[1, 2] から [3] へは出る
        Instance inst = line(2, OptionalInt.empty());
        Routes s = Routes.of(inst, Solution.of(new int[] {1, 2}, new int[] {3, 4}));
        List<Relocate.RelocateMove> moves = new ArrayList<>();
        RELOCATE.scan(s, m -> moves.add((Relocate.RelocateMove) m));
        assertTrue(moves.stream().noneMatch(m -> m.from() != m.to() && m.to() < s.count()),
                "どちらのルートも満載なので、別のルートへは移せない");
        assertTrue(moves.stream().anyMatch(m -> m.to() == s.count()), "新しいルートへは移せる");
    }
}
