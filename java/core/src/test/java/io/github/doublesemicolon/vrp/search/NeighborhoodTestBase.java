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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 全ての近傍に共通のテスト。近傍ごとのテストはこれを継承し、近傍と、その素朴な版
 * (定義どおりに解を丸ごと作るもの) を渡す。{@link NeighborhoodContract} を参照。
 */
abstract class NeighborhoodTestBase {
    abstract Neighborhood neighborhood();

    /** 解から 1 手で作れる解を、制約を気にせず、近傍の数え方と同じ回数だけ全て返す。 */
    abstract List<Solution> naive(Instance inst, Solution sol);

    static Instance stored(String name) {
        try {
            return VrpJson.readRun(TestData.runs().resolve("python/" + name + ".json")).instance();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Stream<String> randomInstances() {
        return Stream.of("rand30-uniform-s0", "rand30-clustered-s1", "rand50-uniform-s0", "rand50-clustered-s2");
    }

    // 車両数の上限がちょうどのもの (toy5 は K=2)、余裕のあるもの (toy5-K3)、上限なし、容量がきついもの
    @ParameterizedTest
    @ValueSource(strings = {"toy5", "toy5-K3", "toy5-tight", "toy5-unlimited", "nn-over", "toy8-tight", "toy8"})
    void contractHoldsFromEveryFeasibleSolution(String name) {
        // 全探索で列挙できる実行可能解の全てを出発点にする
        Instance inst = Cases.get(name).instance();
        long n = BruteForce.enumerate(inst, sol -> NeighborhoodContract.check(inst, sol, neighborhood(), this::naive));
        assertTrue(n > 0);
    }

    @ParameterizedTest
    @MethodSource("randomInstances")
    void contractHoldsAlongTheDescent(String name) {
        // 最近傍法の解から局所最適まで、途中の全ての解で確かめる
        Instance inst = stored(name);
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        NeighborhoodContract.check(inst, s.toSolution(), neighborhood(), this::naive);
        LocalSearch.descend(s, neighborhood(), cost ->
                NeighborhoodContract.check(inst, s.toSolution(), neighborhood(), this::naive));
    }

    @Test
    void contractHoldsOnA200CustomerInstance() {
        Instance inst = stored("rand200-clustered-s1");
        NeighborhoodContract.check(inst, NearestNeighbor.build(inst), neighborhood(), this::naive);
    }

    @ParameterizedTest
    @MethodSource("randomInstances")
    void descentEndsAtALocalOptimumAndOnlyImproves(String name) {
        Instance inst = stored(name);
        Solution start = NearestNeighbor.build(inst);
        Routes s = Routes.of(inst, start);
        List<Long> costs = new ArrayList<>(List.of(s.cost()));
        long moves = LocalSearch.descend(s, neighborhood(), costs::add);

        assertEquals(moves, costs.size() - 1);
        for (int k = 1; k < costs.size(); k++) {
            assertTrue(costs.get(k) < costs.get(k - 1), "改善しない手を適用した");
        }
        Solution end = s.toSolution();
        assertEquals(List.of(), Objective.validate(inst, end));
        assertEquals(Objective.solutionCost(inst, end), s.cost());
        assertTrue(moves > 0 && s.cost() < Objective.solutionCost(inst, start), "最近傍法の解を改善できる");
        NeighborhoodContract.assertLocalOptimum(inst, end, this::naive);
    }
}
