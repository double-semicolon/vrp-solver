package io.github.doublesemicolon.vrp.search;

import io.github.doublesemicolon.vrp.Instance;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * Or-opt: 連続する 2〜3 人の区間を抜き、同じルートの別の位置か、別のルートか、新しいルートに入れる。
 * 入れる向きはそのままと逆向きの両方を試す。長さ 1 は relocate と同じ手になるので含めない。
 * <pre>
 *     抜く:   ... a -> [f ... l] -> b ...   は   ... a -> b ...
 *     入れる: ... x -> y ...                は   ... x -> [f ... l] -> y ...   (逆向きなら x -> [l ... f] -> y)
 * </pre>
 * 距離が対称なので、区間の内側の辺は向きを変えても距離が変わらない。変わるのは境界の辺だけで、
 * delta = (d(a,b) - d(a,f) - d(l,b)) + (d(x,f) + d(l,y) - d(x,y))  (逆向きは f と l を入れ替える)。
 * 入れる位置は、relocate と同じく抜いた後の解で数える。
 *
 * <p>新しいルートに入れるのは、元のルートに顧客が残るときだけ。区間が元のルート全体なら、
 * 新しいルートに移しても、ルートの並び順か向きが変わるだけになる (向きを変えるのは 2-opt の受け持ち)。
 */
public final class OrOpt implements Neighborhood {
    static final int MIN_LENGTH = 2;
    static final int MAX_LENGTH = 3;

    @Override
    public String name() {
        return "or-opt";
    }

    /**
     * ルート from の start 番目から length 人を抜き、ルート to の toPos 番目に入れる
     * (reversed なら逆向きに)。to == s.count() は新しいルート。
     */
    record OrOptMove(int from, int start, int length, int to, int toPos, boolean reversed, long delta)
            implements Move {
        /** 区間の先頭の顧客。 */
        @Override
        public int origin(Routes s) {
            return s.node(from, start);
        }

        @Override
        public void apply(Routes s) {
            int[] src = s.nodesCopy(from);
            int[] seg = new int[length];
            for (int k = 0; k < length; k++) {
                seg[k] = src[reversed ? start + length - 1 - k : start + k];
            }
            int[] removed = new int[src.length - length];
            System.arraycopy(src, 0, removed, 0, start);
            System.arraycopy(src, start + length, removed, start, removed.length - start);

            if (to == from) {
                s.replace(from, insert(removed, toPos, seg));
                return;
            }
            if (to == s.count()) {
                s.add(seg);
            } else {
                s.replace(to, insert(s.nodesCopy(to), toPos, seg));
            }
            // 番号がずれるので、消すのは最後
            if (removed.length == 0) {
                s.remove(from);
            } else {
                s.replace(from, removed);
            }
        }

        private static int[] insert(int[] route, int pos, int[] seg) {
            int[] out = new int[route.length + seg.length];
            System.arraycopy(route, 0, out, 0, pos);
            System.arraycopy(seg, 0, out, pos, seg.length);
            System.arraycopy(route, pos, out, pos + seg.length, route.length - pos);
            return out;
        }
    }

    @Override
    public void scan(Routes s, IntPredicate look, Consumer<Move> out) {
        Instance inst = s.instance();
        int q = inst.capacity();
        for (int from = 0; from < s.count(); from++) {
            int len = s.size(from);
            for (int length = MIN_LENGTH; length <= MAX_LENGTH; length++) {
                for (int start = 0; start + length <= len; start++) {
                    int f = s.node(from, start);
                    if (!look.test(f)) {
                        continue;
                    }
                    int l = s.node(from, start + length - 1);
                    int a = s.prev(from, start);
                    int b = s.next(from, start + length - 1);
                    long removeGain = (long) inst.dist(a, b) - inst.dist(a, f) - inst.dist(l, b);
                    long segLoad = 0;
                    for (int k = start; k < start + length; k++) {
                        segLoad += inst.demand(s.node(from, k));
                    }

                    for (int to = 0; to < s.count(); to++) {
                        if (to == from) {
                            // 抜いた後のルート (長さ len - length) の t 番目に入れる。
                            // t == start でそのままの向きは元に戻るだけ。逆向きならその場で区間を裏返す手になる
                            // 区間がルート全体なら、抜いた時点でルートが消えるので入れ直す先が無い
                            int rest = len - length;
                            if (rest == 0) {
                                continue;
                            }
                            for (int t = 0; t <= rest; t++) {
                                int x = t == 0 ? 0 : afterRemoval(s, from, start, length, t - 1);
                                int y = t == rest ? 0 : afterRemoval(s, from, start, length, t);
                                if (t != start) {
                                    out.accept(new OrOptMove(from, start, length, to, t, false,
                                            removeGain + insertCost(inst, x, f, l, y)));
                                }
                                out.accept(new OrOptMove(from, start, length, to, t, true,
                                        removeGain + insertCost(inst, x, l, f, y)));
                            }
                            continue;
                        }
                        if (s.load(to) + segLoad > q) {
                            continue;
                        }
                        int m = s.size(to);
                        for (int t = 0; t <= m; t++) {
                            int x = t == 0 ? 0 : s.node(to, t - 1);
                            int y = t == m ? 0 : s.node(to, t);
                            out.accept(new OrOptMove(from, start, length, to, t, false,
                                    removeGain + insertCost(inst, x, f, l, y)));
                            out.accept(new OrOptMove(from, start, length, to, t, true,
                                    removeGain + insertCost(inst, x, l, f, y)));
                        }
                    }

                    if (len > length && s.canAddRoute()) {
                        long alone = (long) inst.dist(0, f) + inst.dist(l, 0);   // 逆向きでも同じ
                        out.accept(new OrOptMove(from, start, length, s.count(), 0, false, removeGain + alone));
                        out.accept(new OrOptMove(from, start, length, s.count(), 0, true, removeGain + alone));
                    }
                }
            }
        }
    }

    /** ルート r から start 番目以降 length 人を抜いた後の k 番目。 */
    private static int afterRemoval(Routes s, int r, int start, int length, int k) {
        return s.node(r, k < start ? k : k + length);
    }

    /** x -> y の間に、first から始まり last で終わる区間を入れたときの変化。 */
    private static long insertCost(Instance inst, int x, int first, int last, int y) {
        return (long) inst.dist(x, first) + inst.dist(last, y) - inst.dist(x, y);
    }
}
