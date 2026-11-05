package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** 2-opt* のテスト。全近傍に共通のテストは {@link NeighborhoodTestBase}。 */
class TwoOptStarTest extends NeighborhoodTestBase {
    static final TwoOptStar TWO_OPT_STAR = new TwoOptStar();

    @Override
    Neighborhood neighborhood() {
        return TWO_OPT_STAR;
    }

    /**
     * 2-opt* の定義そのまま: ルートの組ごとに、全ての切れ目の組で後半を入れ替える (空になったルートは消す)。
     * 相手が空の新しいルートの場合 (ルートを 2 本に分ける) も含める。
     */
    @Override
    List<Solution> naive(Instance inst, Solution sol) {
        List<List<Integer>> routes = NeighborhoodContract.mutable(sol);
        List<Solution> out = new ArrayList<>();
        for (int r1 = 0; r1 < routes.size(); r1++) {
            List<Integer> a = routes.get(r1);
            for (int i = 0; i <= a.size(); i++) {
                for (int r2 = r1 + 1; r2 < routes.size(); r2++) {
                    List<Integer> b = routes.get(r2);
                    for (int j = 0; j <= b.size(); j++) {
                        List<List<Integer>> x = NeighborhoodContract.mutable(sol);
                        x.set(r1, join(a.subList(0, i), b.subList(j, b.size())));
                        x.set(r2, join(b.subList(0, j), a.subList(i, a.size())));
                        out.add(NeighborhoodContract.solution(x));
                    }
                }
                List<List<Integer>> x = NeighborhoodContract.mutable(sol);
                x.set(r1, new ArrayList<>(a.subList(0, i)));
                x.add(new ArrayList<>(a.subList(i, a.size())));
                out.add(NeighborhoodContract.solution(x));
            }
        }
        return out;
    }

    private static List<Integer> join(List<Integer> head, List<Integer> tail) {
        List<Integer> out = new ArrayList<>(head);
        out.addAll(tail);
        return out;
    }

    // ---------------------------------------------------------------- 個別の場面

    static Instance line(int capacity, OptionalInt k) {
        // depot を原点に、x 軸上に顧客を並べる。距離が手で計算できる
        double[][] coords = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {10, 0}};
        return new Instance("line", coords, new int[] {0, 1, 1, 1, 1}, capacity, k);
    }

    private static List<TwoOptStar.TwoOptStarMove> scan(Routes s) {
        List<TwoOptStar.TwoOptStarMove> moves = new ArrayList<>();
        TWO_OPT_STAR.scan(s, m -> moves.add((TwoOptStar.TwoOptStarMove) m));
        return moves;
    }

    private static TwoOptStar.TwoOptStarMove find(List<TwoOptStar.TwoOptStarMove> moves, int i, int j) {
        return moves.stream().filter(m -> m.i() == i && m.j() == j).findFirst().orElseThrow();
    }

    @Test
    void tailsAreExchanged() {
        // [1, 4] と [3, 2] をどちらも 1 人目の後ろで切ると [1, 2] と [3, 4]
        // 前: 0->1->4->0 = 1+9+10 = 20、0->3->2->0 = 3+1+2 = 6。計 26
        // 後: 0->1->2->0 = 1+1+2 = 4、  0->3->4->0 = 3+7+10 = 20。計 24。差は -2
        Routes s = Routes.of(line(4, OptionalInt.empty()), Solution.of(new int[] {1, 4}, new int[] {3, 2}));
        TwoOptStar.TwoOptStarMove m = find(scan(s), 1, 1);
        assertEquals(-2, m.delta());
        m.apply(s);
        assertEquals(Solution.of(new int[] {1, 2}, new int[] {3, 4}), s.toSolution());
        assertEquals(24, s.cost());
    }

    @Test
    void twoRoutesCanBeJoinedIntoOne() {
        // 前: [1, 2] (1+1+2 = 4) と [3, 4] (3+7+10 = 20)
        Instance inst = line(4, OptionalInt.empty());
        Solution sol = Solution.of(new int[] {1, 2}, new int[] {3, 4});

        // [1, 2] を末尾で、[3, 4] を先頭で切ると [1, 2, 3, 4] (1+1+1+7+10 = 20)。差は 20 - 24 = -4
        Routes s = Routes.of(inst, sol);
        TwoOptStar.TwoOptStarMove m = find(scan(s), 2, 0);
        assertEquals(-4, m.delta());
        m.apply(s);
        assertEquals(Solution.of(new int[] {1, 2, 3, 4}), s.toSolution());

        // 逆に [1, 2] を先頭で、[3, 4] を末尾で切ると [3, 4, 1, 2] (3+7+9+1+2 = 22)。差は -2
        Routes t = Routes.of(inst, sol);
        m = find(scan(t), 0, 2);
        assertEquals(-2, m.delta());
        m.apply(t);
        assertEquals(Solution.of(new int[] {3, 4, 1, 2}), t.toSolution());
    }

    @Test
    void capacityIsCheckedOnBothNewRoutes() {
        // 容量 3、需要は全員 1。[1, 2, 3] (積載 3) と [4] (積載 1)
        Routes s = Routes.of(line(3, OptionalInt.empty()), Solution.of(new int[] {1, 2, 3}, new int[] {4}));
        List<TwoOptStar.TwoOptStarMove> moves = scan(s);
        // 1 人目の後ろと [4] の先頭で切ると [1, 4] と [2, 3]。どちらも積載 2
        assertTrue(moves.stream().anyMatch(m -> m.r2() == 1 && m.i() == 1 && m.j() == 0));
        // [1, 2, 3] の末尾と [4] の先頭: 1 本目が [1, 2, 3, 4] で積載 4
        assertTrue(moves.stream().noneMatch(m -> m.r2() == 1 && m.i() == 3 && m.j() == 0));
        // [1, 2, 3] の先頭と [4] の末尾: 2 本目が [4, 1, 2, 3] で積載 4
        assertTrue(moves.stream().noneMatch(m -> m.r2() == 1 && m.i() == 0 && m.j() == 1));
    }

    @Test
    void aRouteIsSplitOnlyWhenAVehicleIsLeft() {
        Solution sol = Solution.of(new int[] {1, 2, 3, 4});
        for (int k : new int[] {1, 2}) {
            Routes s = Routes.of(line(4, OptionalInt.of(k)), sol);
            // 1 本しか無いので、出せるのは分ける手だけ。切れ目は 1 人目・2 人目・3 人目の後ろ
            assertEquals(k == 1 ? 0 : 3, scan(s).size(), "K=" + k);
        }
        // [1, 2] と [3, 4] に分ける: 0->2 (2) と 0->3 (3) が増え、2->3 (1) が減る。差は +4
        Routes s = Routes.of(line(4, OptionalInt.of(2)), sol);
        TwoOptStar.TwoOptStarMove m = find(scan(s), 2, 0);
        assertEquals(4, m.delta());
        m.apply(s);
        assertEquals(Solution.of(new int[] {1, 2}, new int[] {3, 4}), s.toSolution());
    }
}
