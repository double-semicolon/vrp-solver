package io.github.doublesemicolon.vrp.alns;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.search.Routes;
import java.util.ArrayList;
import java.util.List;

/**
 * 修復演算子: 抜いた顧客を全員、容量と車両数を守って入れ直す。
 * どの顧客にも入れ先が無くなったら (容量も車両数も足りない) 失敗として false を返す。
 * そのときの解は途中までしか入っていないので、呼び出し側は捨てる。
 *
 * <p>入れ先は、既存のルートの全ての隙間と、車両数に余裕があれば新しいルート。
 * 入れるコストは d(x,c) + d(c,y) - d(x,y) (新しいルートなら 2 d(0,c))。
 */
public interface Repair {
    String name();

    boolean repair(Routes s, int[] removed);

    /** 顧客 c の入れ先 1 つ。route == s.count() は新しいルート。 */
    record Insertion(int customer, int route, int pos, long cost) {}

    /** 顧客 c のルートごとの最も安い入れ先 (容量に収まるルートだけ。新しいルートも 1 本のルートとして数える)。 */
    static List<Insertion> bestPerRoute(Routes s, int c) {
        Instance inst = s.instance();
        List<Insertion> out = new ArrayList<>();
        for (int r = 0; r < s.count(); r++) {
            if (s.load(r) + inst.demand(c) > inst.capacity()) {
                continue;
            }
            int m = s.size(r);
            Insertion best = null;
            for (int t = 0; t <= m; t++) {
                int x = t == 0 ? 0 : s.node(r, t - 1);
                int y = t == m ? 0 : s.node(r, t);
                long cost = (long) inst.dist(x, c) + inst.dist(c, y) - inst.dist(x, y);
                if (best == null || cost < best.cost()) {
                    best = new Insertion(c, r, t, cost);
                }
            }
            out.add(best);
        }
        if (s.canAddRoute()) {
            out.add(new Insertion(c, s.count(), 0, 2L * inst.dist(0, c)));
        }
        return out;
    }

    /**
     * 貪欲挿入: 残っている顧客の中で、最も安く入れられる顧客をその場所に入れる、を繰り返す。
     * 同じコストなら、抜いた順で先の顧客・番号の小さいルート・前の位置。
     */
    final class GreedyInsertion implements Repair {
        @Override
        public String name() {
            return "greedy";
        }

        @Override
        public boolean repair(Routes s, int[] removed) {
            List<Integer> left = new ArrayList<>();
            for (int c : removed) {
                left.add(c);
            }
            while (!left.isEmpty()) {
                Insertion best = null;
                for (int c : left) {
                    for (Insertion ins : bestPerRoute(s, c)) {
                        if (best == null || ins.cost() < best.cost()) {
                            best = ins;
                        }
                    }
                }
                if (best == null) {
                    return false;
                }
                s.insertCustomer(best.customer(), best.route(), best.pos());
                left.remove(Integer.valueOf(best.customer()));
            }
            return true;
        }
    }

    /**
     * regret-2 挿入: 「一番安いルート」と「二番目に安いルート」の差 (regret) が最も大きい顧客から入れる。
     * 後回しにすると良い入れ先を他の顧客に取られて大きく損をする顧客を、先に入れる考え方。
     * 入れ先が 1 本のルートしか無い顧客は差を無限大とみなし、最優先にする。
     * 同じ regret なら一番安い入れ先の安い方、それも同じなら抜いた順で先の顧客。
     */
    final class RegretInsertion implements Repair {
        @Override
        public String name() {
            return "regret2";
        }

        @Override
        public boolean repair(Routes s, int[] removed) {
            List<Integer> left = new ArrayList<>();
            for (int c : removed) {
                left.add(c);
            }
            while (!left.isEmpty()) {
                Insertion pick = null;
                long pickRegret = 0;
                for (int c : left) {
                    List<Insertion> options = bestPerRoute(s, c);
                    if (options.isEmpty()) {
                        return false;   // この顧客はどこにも入らない
                    }
                    Insertion first = null;
                    long second = Long.MAX_VALUE;
                    for (Insertion ins : options) {
                        if (first == null || ins.cost() < first.cost()) {
                            if (first != null) {
                                second = first.cost();
                            }
                            first = ins;
                        } else if (ins.cost() < second) {
                            second = ins.cost();
                        }
                    }
                    long regret = second == Long.MAX_VALUE ? Long.MAX_VALUE : second - first.cost();
                    if (pick == null || regret > pickRegret || (regret == pickRegret && first.cost() < pick.cost())) {
                        pick = first;
                        pickRegret = regret;
                    }
                }
                s.insertCustomer(pick.customer(), pick.route(), pick.pos());
                left.remove(Integer.valueOf(pick.customer()));
            }
            return true;
        }
    }
}
