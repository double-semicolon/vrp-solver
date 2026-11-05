package io.github.doublesemicolon.vrp.search;

import io.github.doublesemicolon.vrp.Instance;
import java.util.function.Consumer;

/**
 * 2-opt*: 2 本のルートをそれぞれ 1 か所で切り、後半同士を入れ替える。
 * <pre>
 *     前: 0 -> [前半1] -> a1 | b1 -> [後半1] -> 0      0 -> [前半2] -> a2 | b2 -> [後半2] -> 0
 *     後: 0 -> [前半1] -> a1 -> b2 -> [後半2] -> 0     0 -> [前半2] -> a2 -> b1 -> [後半1] -> 0
 * </pre>
 * 前半も後半も向きは変わらないので、変わるのは切れ目の 2 本の辺だけで、
 * delta = d(a1,b2) + d(a2,b1) - d(a1,b1) - d(a2,b2)。切れ目が端なら a や b は depot。
 * 積載量は前半と後半で入れ替わる: 新しいルート 1 は 前半1 + (ルート 2 の積載 - 前半2)。
 * 容量は新しい 2 本の両方で確かめる。
 *
 * <p>切れ目は「前半の人数」i (0..ルートの長さ) で表す。
 * <ul>
 *   <li>両方の切れ目が先頭 (ルートを丸ごと入れ替える) と、両方が末尾 (空の後半を入れ替える) は、
 *       解が変わらないので出さない。
 *   <li>片方が先頭でもう片方が末尾なら、2 本が 1 本につながり、空になった方を消す。
 *   <li>相手を空の新しいルートとみなすと、ルートを前半と後半の 2 本に分ける手になる。
 *       relocate や Or-opt と同じく、車両数の上限に余裕があるときだけ出す。
 * </ul>
 * 前半1 に逆向きの前半2 をつなぐ型 (HGS などでは 2-opt* に含める) は入れない。
 */
public final class TwoOptStar implements Neighborhood {
    @Override
    public String name() {
        return "2-opt*";
    }

    /**
     * ルート r1 を前半 i 人で、ルート r2 を前半 j 人で切り、後半を入れ替える (r1 < r2)。
     * r2 == s.count() は空の新しいルートで、ルート r1 を前半 i 人と残りの 2 本に分ける。
     */
    record TwoOptStarMove(int r1, int i, int r2, int j, long delta) implements Move {
        @Override
        public void apply(Routes s) {
            int[] a = s.nodesCopy(r1);
            if (r2 == s.count()) {
                s.replace(r1, slice(a, 0, i));
                s.add(slice(a, i, a.length));
                return;
            }
            int[] b = s.nodesCopy(r2);
            int[] new1 = concat(a, i, b, j);
            int[] new2 = concat(b, j, a, i);
            // r1 < r2 なので、r2 を先に扱えば r1 を消しても番号がずれない
            if (new2.length == 0) {
                s.remove(r2);
            } else {
                s.replace(r2, new2);
            }
            if (new1.length == 0) {
                s.remove(r1);
            } else {
                s.replace(r1, new1);
            }
        }

        /** head の前半 h 人のあとに、tail の t 番目以降をつなぐ。 */
        private static int[] concat(int[] head, int h, int[] tail, int t) {
            int[] out = new int[h + tail.length - t];
            System.arraycopy(head, 0, out, 0, h);
            System.arraycopy(tail, t, out, h, tail.length - t);
            return out;
        }

        private static int[] slice(int[] a, int from, int to) {
            int[] out = new int[to - from];
            System.arraycopy(a, from, out, 0, to - from);
            return out;
        }
    }

    @Override
    public void scan(Routes s, Consumer<Move> out) {
        Instance inst = s.instance();
        int q = inst.capacity();
        for (int r1 = 0; r1 < s.count(); r1++) {
            int len1 = s.size(r1);
            long load1 = s.load(r1);
            long pre1 = 0;   // 前半 i 人の積載
            for (int i = 0; i <= len1; i++) {
                if (i > 0) {
                    pre1 += inst.demand(s.node(r1, i - 1));
                }
                int a1 = i == 0 ? 0 : s.node(r1, i - 1);
                int b1 = i == len1 ? 0 : s.node(r1, i);
                long cut1 = inst.dist(a1, b1);

                for (int r2 = r1 + 1; r2 < s.count(); r2++) {
                    int len2 = s.size(r2);
                    long load2 = s.load(r2);
                    long pre2 = 0;
                    for (int j = 0; j <= len2; j++) {
                        if (j > 0) {
                            pre2 += inst.demand(s.node(r2, j - 1));
                        }
                        if ((i == 0 && j == 0) || (i == len1 && j == len2)) {
                            continue;
                        }
                        if (pre1 + load2 - pre2 > q || pre2 + load1 - pre1 > q) {
                            continue;
                        }
                        int a2 = j == 0 ? 0 : s.node(r2, j - 1);
                        int b2 = j == len2 ? 0 : s.node(r2, j);
                        long delta = (long) inst.dist(a1, b2) + inst.dist(a2, b1) - cut1 - inst.dist(a2, b2);
                        out.accept(new TwoOptStarMove(r1, i, r2, j, delta));
                    }
                }

                // 空の新しいルートが相手なら、ルートを 2 本に分ける (前半も後半も 1 人以上)
                if (0 < i && i < len1 && s.canAddRoute()) {
                    long delta = (long) inst.dist(a1, 0) + inst.dist(0, b1) - cut1;
                    out.accept(new TwoOptStarMove(r1, i, s.count(), 0, delta));
                }
            }
        }
    }
}
