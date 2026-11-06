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

/** 近傍リストで絞った swap のテスト。全近傍に共通のテストは {@link NeighborhoodTestBase}。 */
class NearSwapTest extends NeighborhoodTestBase {
    static final int K = 3;
    final WithNear swap = new WithNear(K, Swap::new);

    @Override
    Neighborhood neighborhood() {
        return swap;
    }

    /** 絞らない swap の素朴な版のうち、片方がもう片方の近くの k 人に入っている組。 */
    @Override
    List<Solution> naive(Instance inst, Solution sol) {
        NeighborList near = swap.list(inst);
        List<int[]> positions = new ArrayList<>();
        for (int r = 0; r < sol.numRoutes(); r++) {
            for (int p = 0; p < sol.routes().get(r).size(); p++) {
                positions.add(new int[] {r, p});
            }
        }
        List<Solution> out = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                int[] pu = positions.get(i);
                int[] pv = positions.get(j);
                int u = sol.routes().get(pu[0]).get(pu[1]);
                int v = sol.routes().get(pv[0]).get(pv[1]);
                if (!near.contains(u, v) && !near.contains(v, u)) {
                    continue;
                }
                List<List<Integer>> x = NeighborhoodContract.mutable(sol);
                x.get(pu[0]).set(pu[1], v);
                x.get(pv[0]).set(pv[1], u);
                out.add(NeighborhoodContract.solution(x));
            }
        }
        return out;
    }

    @ParameterizedTest
    @MethodSource("randomInstances")
    void withEveryoneAsNearItIsTheFullSwap(String name) {
        Instance inst = stored(name);
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        assertEquals(NearRelocateTest.sortedSolutions(s, new Swap()),
                NearRelocateTest.sortedSolutions(s, new Swap(NeighborList.of(inst, inst.n()))));
    }

    @ParameterizedTest
    @MethodSource("randomInstances")
    void eachPairIsOfferedOnce(String name) {
        // 互いに近くの k 人に入っている組も、出すのは 1 回だけ
        Instance inst = stored(name);
        Routes s = Routes.of(inst, NearestNeighbor.build(inst));
        List<String> pairs = new ArrayList<>();
        swap.scan(s, m -> {
            Swap.SwapMove w = (Swap.SwapMove) m;
            int u = s.node(w.r1(), w.p1());
            int v = s.node(w.r2(), w.p2());
            pairs.add(Math.min(u, v) + "-" + Math.max(u, v));
        });
        assertEquals(pairs.stream().distinct().count(), pairs.size());
        assertTrue(!pairs.isEmpty() && pairs.size() <= inst.n() * K);
    }
}
