package io.github.doublesemicolon.vrp.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.Solution;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Or-opt のテスト。全近傍に共通のテストは {@link NeighborhoodTestBase}。 */
class OrOptTest extends NeighborhoodTestBase {
    static final OrOpt OR_OPT = new OrOpt();

    @Override
    Neighborhood neighborhood() {
        return OR_OPT;
    }

    /**
     * Or-opt の定義そのまま: 連続する 2〜3 人を抜き (空になったルートは消す)、あらゆる位置に
     * 両方の向きで入れる。新しいルートに入れるのは、元のルートに顧客が残るときだけ。
     */
    @Override
    List<Solution> naive(Instance inst, Solution sol) {
        List<Solution> out = new ArrayList<>();
        for (int r = 0; r < sol.numRoutes(); r++) {
            int len = sol.routes().get(r).size();
            for (int length = 2; length <= 3; length++) {
                for (int start = 0; start + length <= len; start++) {
                    List<List<Integer>> rest = NeighborhoodContract.mutable(sol);
                    List<Integer> seg = new ArrayList<>(rest.get(r).subList(start, start + length));
                    rest.get(r).subList(start, start + length).clear();
                    boolean sourceRemains = !rest.get(r).isEmpty();
                    Solution base = NeighborhoodContract.solution(rest);
                    for (boolean reversed : new boolean[] {false, true}) {
                        List<Integer> piece = new ArrayList<>(seg);
                        if (reversed) {
                            Collections.reverse(piece);
                        }
                        for (int to = 0; to < base.numRoutes(); to++) {
                            for (int t = 0; t <= base.routes().get(to).size(); t++) {
                                List<List<Integer>> x = NeighborhoodContract.mutable(base);
                                x.get(to).addAll(t, piece);
                                out.add(NeighborhoodContract.solution(x));
                            }
                        }
                        if (sourceRemains) {
                            List<List<Integer>> x = NeighborhoodContract.mutable(base);
                            x.add(piece);
                            out.add(NeighborhoodContract.solution(x));
                        }
                    }
                }
            }
        }
        return out;
    }

    static Instance line(int capacity) {
        // depot を原点に、x 軸上に顧客を並べる。距離が手で計算できる
        double[][] coords = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {4, 0}, {10, 0}};
        return new Instance("line", coords, new int[] {0, 1, 1, 1, 1, 1}, capacity, OptionalInt.empty());
    }

    @Test
    void aSegmentCanBeMovedReversed() {
        // [1, 2] と [5, 4, 3] (逆向きに並んだ 3 人)。[4, 3] を逆向きにして 2 の後ろへ入れると [1, 2, 3, 4] と [5]
        // 前: [1, 2] は 0->1->2->0 で 1+1+2 = 4、[5, 4, 3] は 0->5->4->3->0 で 10+6+1+3 = 20。計 24
        // 後: [1, 2, 3, 4] は 8、[5] は 20。計 28。差は +4
        Instance inst = line(5);
        Routes s = Routes.of(inst, Solution.of(new int[] {1, 2}, new int[] {5, 4, 3}));
        List<OrOpt.OrOptMove> moves = new ArrayList<>();
        OR_OPT.scan(s, m -> moves.add((OrOpt.OrOptMove) m));
        OrOpt.OrOptMove m = moves.stream()
                .filter(x -> x.from() == 1 && x.start() == 1 && x.length() == 2 && x.to() == 0 && x.toPos() == 2
                        && x.reversed())
                .findFirst().orElseThrow();
        assertEquals(4, m.delta());
        m.apply(s);
        assertEquals(Solution.of(new int[] {1, 2, 3, 4}, new int[] {5}), s.toSolution());
    }

    @Test
    void aWholeRouteIsNotMovedToANewRoute() {
        // [1, 2] は区間全体。新しいルートへ移すと、ルートの並び順か向きが変わるだけ
        Instance inst = line(5);
        List<OrOpt.OrOptMove> moves = new ArrayList<>();
        Routes s = Routes.of(inst, Solution.of(new int[] {1, 2}, new int[] {3, 4, 5}));
        OR_OPT.scan(s, m -> moves.add((OrOpt.OrOptMove) m));
        assertTrue(moves.stream().noneMatch(m -> m.from() == 0 && m.to() == s.count()));
        assertTrue(moves.stream().anyMatch(m -> m.from() == 1 && m.to() == s.count()), "3 人のルートからは出せる");
    }

    @Test
    void segmentLoadIsCheckedAgainstTheTargetRoute() {
        // 容量 4、需要は全員 1。ルートは [1, 2] (積載 2) と [3, 4, 5] (積載 3)
        // - [1, 2] の区間 (積載 2) は [3, 4, 5] へ入らない (3 + 2 > 4)
        // - [3, 4, 5] の長さ 2 の区間は [1, 2] へ入る (2 + 2 = 4)。長さ 3 は入らない (2 + 3 > 4)
        Instance inst = line(4);
        List<OrOpt.OrOptMove> moves = new ArrayList<>();
        OR_OPT.scan(Routes.of(inst, Solution.of(new int[] {1, 2}, new int[] {3, 4, 5})), m -> moves.add((OrOpt.OrOptMove) m));
        assertTrue(moves.stream().noneMatch(m -> m.from() == 0 && m.to() == 1));
        assertTrue(moves.stream().anyMatch(m -> m.from() == 1 && m.to() == 0 && m.length() == 2));
        assertTrue(moves.stream().noneMatch(m -> m.from() == 1 && m.to() == 0 && m.length() == 3));
    }
}
