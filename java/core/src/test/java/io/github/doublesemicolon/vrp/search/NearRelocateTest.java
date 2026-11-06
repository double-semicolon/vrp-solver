package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import io.github.doublesemicolon.vrp.solvers.NearestNeighbor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 近傍リストで絞った relocate のテスト。全近傍に共通のテストは {@link NeighborhoodTestBase}。 */
class NearRelocateTest extends NeighborhoodTestBase {
    static final int K = 3;
    final WithNear relocate = new WithNear(K, Relocate::new);

    @Override
    Neighborhood neighborhood() {
        return relocate;
    }

    /** 絞らない relocate の素朴な版のうち、c が近くの k 人の誰かの隣に入る手と、新しいルートへ移す手。 */
    @Override
    List<Solution> naive(Instance inst, Solution sol) {
        return naiveNear(sol, relocate.list(inst));
    }

    static List<Solution> naiveNear(Solution sol, NeighborList near) {
        List<Solution> out = new ArrayList<>();
        for (int r = 0; r < sol.numRoutes(); r++) {
            for (int p = 0; p < sol.routes().get(r).size(); p++) {
                List<List<Integer>> rest = NeighborhoodContract.mutable(sol);
                int c = rest.get(r).remove(p);
                rest = NeighborhoodContract.mutable(NeighborhoodContract.solution(rest));
                for (int to = 0; to < rest.size(); to++) {
                    List<Integer> route = rest.get(to);
                    for (int t = 0; t <= route.size(); t++) {
                        boolean nextToNear = (t > 0 && near.contains(c, route.get(t - 1)))
                                || (t < route.size() && near.contains(c, route.get(t)));
                        if (nextToNear) {
                            List<List<Integer>> x = NeighborhoodContract.mutable(NeighborhoodContract.solution(rest));
                            x.get(to).add(t, c);
                            out.add(NeighborhoodContract.solution(x));
                        }
                    }
                }
                List<List<Integer>> x = NeighborhoodContract.mutable(NeighborhoodContract.solution(rest));
                x.add(new ArrayList<>(List.of(c)));
                out.add(NeighborhoodContract.solution(x));
            }
        }
        return out;
    }

    @ParameterizedTest
    @MethodSource("randomInstances")
    void withEveryoneAsNearItIsTheFullRelocate(String name) {
        // どの隙間にも顧客が 1 人は接しているので、k = 顧客数 - 1 なら絞らない relocate と同じ手の集合
        Instance inst = stored(name);
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        assertEquals(sortedSolutions(s, new Relocate()),
                sortedSolutions(s, new Relocate(NeighborList.of(inst, inst.n()))));
    }

    @ParameterizedTest
    @MethodSource("randomInstances")
    void nearMovesAreFarFewer(String name) {
        Instance inst = stored(name);
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        int all = sortedSolutions(s, new Relocate()).size();
        int near = sortedSolutions(s, relocate).size();
        // 1 人あたり、近くの 3 人の前後 (最大 6 か所) と新しいルート
        assertTrue(near <= inst.n() * (2 * K + 1) && near * 3 < all, near + " / " + all);
    }

    /** 近傍の手を全て適用した解 (並び順を無視した表現) を、整列して返す。 */
    static List<String> sortedSolutions(Routes s, Neighborhood nb) {
        List<String> out = new ArrayList<>();
        nb.scan(s, m -> {
            Routes t = s.copy();
            m.apply(t);
            out.add(NeighborhoodContract.canonical(t.toSolution()));
        });
        out.sort(null);
        return out;
    }
}
