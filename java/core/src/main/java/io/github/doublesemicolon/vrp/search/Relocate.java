package io.github.doublesemicolon.vrp.search;

import io.github.doublesemicolon.vrp.Instance;
import java.util.function.Consumer;

/**
 * relocate: 顧客 1 人を抜き、同じルートの別の位置か、別のルートか、新しいルートに入れる。
 * <pre>
 *     抜く:   ... a -> c -> b ...   は   ... a -> b ...        (a, b は c の前後)
 *     入れる: ... x -> y ...        は   ... x -> c -> y ...    (x, y は抜いた後の解で隣り合う 2 つ)
 * </pre>
 * delta = 抜く分 (d(a,b) - d(a,c) - d(c,b)) + 入れる分 (d(x,c) + d(c,y) - d(x,y))。
 * 入れる位置は抜いた後の解で数える。同じルートの中で動かすときも、この式がそのまま使える。
 *
 * <p>抜いてルートが空になったら、そのルートを消す。新しいルートへ入れるのは、
 * 車両数の上限に余裕があるときだけ (入れる分は depot との往復 2 d(0,c))。
 */
public final class Relocate implements Neighborhood {
    @Override
    public String name() {
        return "relocate";
    }

    /** ルート from の fromPos 番目を抜き、ルート to の toPos 番目に入れる。to == s.count() は新しいルート。 */
    record RelocateMove(int from, int fromPos, int to, int toPos, long delta) implements Move {
        @Override
        public void apply(Routes s) {
            int[] src = s.nodesCopy(from);
            int c = src[fromPos];
            int[] removed = new int[src.length - 1];
            System.arraycopy(src, 0, removed, 0, fromPos);
            System.arraycopy(src, fromPos + 1, removed, fromPos, removed.length - fromPos);

            if (to == from) {
                s.replace(from, insert(removed, toPos, c));
                return;
            }
            if (to == s.count()) {
                s.add(new int[] {c});
            } else {
                s.replace(to, insert(s.nodesCopy(to), toPos, c));
            }
            // 番号がずれるので、消すのは最後
            if (removed.length == 0) {
                s.remove(from);
            } else {
                s.replace(from, removed);
            }
        }

        private static int[] insert(int[] route, int pos, int c) {
            int[] out = new int[route.length + 1];
            System.arraycopy(route, 0, out, 0, pos);
            out[pos] = c;
            System.arraycopy(route, pos, out, pos + 1, route.length - pos);
            return out;
        }
    }

    @Override
    public void scan(Routes s, Consumer<Move> out) {
        Instance inst = s.instance();
        int q = inst.capacity();
        for (int from = 0; from < s.count(); from++) {
            int len = s.size(from);
            for (int p = 0; p < len; p++) {
                int c = s.node(from, p);
                int a = s.prev(from, p);
                int b = s.next(from, p);
                long removeGain = (long) inst.dist(a, b) - inst.dist(a, c) - inst.dist(c, b);

                for (int to = 0; to < s.count(); to++) {
                    if (to == from) {
                        // 抜いた後のルート (長さ len - 1) の toPos 番目に入れる。toPos == p は元に戻るだけ
                        for (int t = 0; t < len; t++) {
                            if (t == p) {
                                continue;
                            }
                            int x = t == 0 ? 0 : afterRemoval(s, from, p, t - 1);
                            int y = t == len - 1 ? 0 : afterRemoval(s, from, p, t);
                            out.accept(new RelocateMove(from, p, to, t, removeGain + insertCost(inst, x, c, y)));
                        }
                        continue;
                    }
                    if (s.load(to) + inst.demand(c) > q) {
                        continue;
                    }
                    int m = s.size(to);
                    for (int t = 0; t <= m; t++) {
                        int x = t == 0 ? 0 : s.node(to, t - 1);
                        int y = t == m ? 0 : s.node(to, t);
                        out.accept(new RelocateMove(from, p, to, t, removeGain + insertCost(inst, x, c, y)));
                    }
                }

                // 1 人だけのルートから新しいルートへ移しても、ルートの並び順が変わるだけ
                if (len > 1 && s.canAddRoute()) {
                    out.accept(new RelocateMove(from, p, s.count(), 0, removeGain + 2L * inst.dist(0, c)));
                }
            }
        }
    }

    /** ルート r から p 番目を抜いた後の k 番目。 */
    private static int afterRemoval(Routes s, int r, int p, int k) {
        return s.node(r, k < p ? k : k + 1);
    }

    private static long insertCost(Instance inst, int x, int c, int y) {
        return (long) inst.dist(x, c) + inst.dist(c, y) - inst.dist(x, y);
    }
}
