package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** swap のテスト。全近傍に共通のテストは {@link NeighborhoodTestBase}。 */
class SwapTest extends NeighborhoodTestBase {
    static final Swap SWAP = new Swap();

    @Override
    Neighborhood neighborhood() {
        return SWAP;
    }

    /** swap の定義そのまま: 位置の組 (前の位置 < 後の位置) ごとに 2 人を入れ替える。 */
    @Override
    List<Solution> naive(Instance inst, Solution sol) {
        List<int[]> positions = new ArrayList<>();
        for (int r = 0; r < sol.numRoutes(); r++) {
            for (int p = 0; p < sol.routes().get(r).size(); p++) {
                positions.add(new int[] {r, p});
            }
        }
        List<Solution> out = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                List<List<Integer>> x = NeighborhoodContract.mutable(sol);
                int[] u = positions.get(i);
                int[] v = positions.get(j);
                int t = x.get(u[0]).get(u[1]);
                x.get(u[0]).set(u[1], x.get(v[0]).get(v[1]));
                x.get(v[0]).set(v[1], t);
                out.add(NeighborhoodContract.solution(x));
            }
        }
        return out;
    }

    static Instance line(int capacity, int... demands) {
        // depot を原点に、x 軸上に顧客を並べる。距離が手で計算できる
        double[][] coords = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {10, 0}};
        int[] d = new int[coords.length];
        System.arraycopy(demands, 0, d, 1, demands.length);
        return new Instance("line", coords, d, capacity, OptionalInt.empty());
    }

    @Test
    void adjacentSwapSharesAnEdge() {
        // [1, 2, 3, 4] の 2 と 3 を入れ替えると [1, 3, 2, 4]。1->3 (2) + 3->2 (1) + 2->4 (8) = 11 は
        // 元の 1->2 (1) + 2->3 (1) + 3->4 (7) = 9 より 2 長い。離れた 2 人の式で数えると 2 にならない
        Instance inst = line(4, 1, 1, 1, 1);
        Routes s = Routes.of(inst, Solution.of(new int[] {1, 2, 3, 4}));
        List<Swap.SwapMove> moves = new ArrayList<>();
        SWAP.scan(s, m -> moves.add((Swap.SwapMove) m));
        Swap.SwapMove m = moves.stream().filter(x -> x.p1() == 1 && x.p2() == 2).findFirst().orElseThrow();
        assertEquals(2, m.delta());
    }

    @Test
    void capacityIsCheckedOnBothRoutes() {
        // 容量 3、ルートは [1, 2] と [3, 4]。括弧内は需要
        // ok:    [1(2), 2(1)] 積載 3 と [3(1), 4(1)] 積載 2。どの組を入れ替えても両方 3 以下に収まる
        Instance ok = line(3, 2, 1, 1, 1);
        // tight: [1(2), 2(1)] 積載 3 と [3(2), 4(1)] 積載 3。1 と 4 を入れ替えると後ろが 4、
        //        2 と 3 を入れ替えると前が 4 になって超える。需要が同じ組 (1 と 3、2 と 4) だけ残る
        Instance tight = line(3, 2, 1, 2, 1);
        Solution sol = Solution.of(new int[] {1, 2}, new int[] {3, 4});
        assertEquals(4, interRouteMoves(ok, sol));
        assertEquals(2, interRouteMoves(tight, sol));
    }

    private static long interRouteMoves(Instance inst, Solution sol) {
        List<Swap.SwapMove> moves = new ArrayList<>();
        SWAP.scan(Routes.of(inst, sol), m -> moves.add((Swap.SwapMove) m));
        return moves.stream().filter(m -> m.r1() != m.r2()).count();
    }

    @Test
    void twoSingleCustomerRoutesAreNotSwapped() {
        // 入れ替えてもルートの並び順が変わるだけ
        Instance inst = line(4, 1, 1, 1, 1);
        List<Move> moves = new ArrayList<>();
        SWAP.scan(Routes.of(inst, Solution.of(new int[] {1}, new int[] {2})), moves::add);
        assertEquals(List.of(), moves);
    }
}
