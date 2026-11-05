package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import io.github.doublesemicolon.vrp.solvers.TwoOpt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** ルート内 2-opt のテスト。全近傍に共通のテストは {@link NeighborhoodTestBase}。 */
class IntraTwoOptTest extends NeighborhoodTestBase {
    static final IntraTwoOpt INTRA_TWO_OPT = new IntraTwoOpt();

    @Override
    Neighborhood neighborhood() {
        return INTRA_TWO_OPT;
    }

    /** 2-opt の定義そのまま: ルートごとに、2 人以上の区間を逆順にする。 */
    @Override
    List<Solution> naive(Instance inst, Solution sol) {
        List<Solution> out = new ArrayList<>();
        for (int r = 0; r < sol.numRoutes(); r++) {
            int len = sol.routes().get(r).size();
            for (int i = 0; i < len; i++) {
                for (int j = i + 1; j < len; j++) {
                    List<List<Integer>> x = NeighborhoodContract.mutable(sol);
                    Collections.reverse(x.get(r).subList(i, j + 1));
                    out.add(NeighborhoodContract.solution(x));
                }
            }
        }
        return out;
    }

    @ParameterizedTest
    @MethodSource({"randomInstances", "largerInstances"})
    void descentGivesTheSameSolutionAsStep1TwoOpt(String name) {
        // Step 1 の 2-opt (Python と解が一致することを確かめてある) と、手の数まで一致する
        Instance inst = stored(name);
        Solution start = NearestNeighbor.build(inst);
        long[] step1Moves = {0};
        Solution step1 = TwoOpt.improve(inst, start, (applied, cost) -> step1Moves[0]++);

        Routes s = Routes.of(inst, start);
        long moves = LocalSearch.descend(s, INTRA_TWO_OPT, cost -> {});
        assertEquals(step1, s.toSolution());
        assertEquals(step1Moves[0], moves);
    }

    @Test
    void tiesAtTheSameIArePickedWithTheSmallestJ() {
        // 1 手目の候補のうち、i..j = 0..1 と 0..2 が同じだけ縮む (どちらも 2)。two-opt-tie の同点は
        // i が違うものだけなので、j の決め方はこの手作りのデータで確かめる
        double[][] coords = {{0, 0}, {-3, 4}, {-4, -1}, {-4, 2}, {2, 2}, {5, 1}, {-2, -4}};
        Instance inst = new Instance("tie-same-i", coords, new int[] {0, 1, 1, 1, 1, 1, 1}, 6, OptionalInt.empty());
        Solution start = Solution.of(new int[] {1, 2, 3, 4, 5, 6});

        List<IntraTwoOpt.IntraTwoOptMove> best = new ArrayList<>();
        INTRA_TWO_OPT.scan(Routes.of(inst, start), m -> best.add((IntraTwoOpt.IntraTwoOptMove) m));
        long min = best.stream().mapToLong(Move::delta).min().orElseThrow();
        assertEquals(List.of("0..1", "0..2", "1..2"),
                best.stream().filter(m -> m.delta() == min).map(m -> m.i() + ".." + m.j()).toList());

        Routes s = Routes.of(inst, start);
        LocalSearch.descend(s, INTRA_TWO_OPT, cost -> {});
        assertEquals(TwoOpt.improve(inst, start, (applied, cost) -> {}), s.toSolution());
    }

    static List<String> largerInstances() {
        // two-opt-tie は同点の手が 23 回現れるデータ (#20)
        return List.of("rand200-clustered-s1", "two-opt-tie");
    }
}
