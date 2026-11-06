package io.github.doublesemicolon.vrp.alns;

import io.github.doublesemicolon.vrp.Instance;
import io.github.doublesemicolon.vrp.search.Routes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 破壊演算子: 解から q 人の顧客を抜く。抜いた後の解は全顧客を訪問しない (修復で入れ直す)。
 * 空になったルートは消える ({@link Routes#removeCustomer})。
 *
 * <p>3 つとも Ropke &amp; Pisinger (2006) の形に倣う。「コストの悪い順」と「近い顧客同士」は、
 * 毎回同じ顧客ばかり抜かないよう、候補を良い順に並べて y^p 番目 (y は [0, 1) の一様乱数) を選ぶ。
 * p が大きいほど先頭に偏る。
 */
public interface Destroy {
    String name();

    /** s から q 人を抜き、抜いた順に返す。q は今いる顧客の数以下。 */
    int[] destroy(Routes s, int q, Random rnd);

    /** 今ルートにいる顧客 (ルート順)。 */
    static List<Integer> customers(Routes s) {
        List<Integer> out = new ArrayList<>();
        for (int r = 0; r < s.count(); r++) {
            for (int i = 0; i < s.size(r); i++) {
                out.add(s.node(r, i));
            }
        }
        return out;
    }

    /** 良い順に並んだ候補から、先頭に偏らせて 1 つ選ぶ。 */
    static int pick(List<Integer> sorted, double p, Random rnd) {
        return sorted.get((int) (Math.pow(rnd.nextDouble(), p) * sorted.size()));
    }

    /** ランダム: 無作為に q 人を抜く。探索を散らす役。 */
    final class RandomRemoval implements Destroy {
        @Override
        public String name() {
            return "random";
        }

        @Override
        public int[] destroy(Routes s, int q, Random rnd) {
            List<Integer> left = customers(s);
            int[] out = new int[q];
            for (int k = 0; k < q; k++) {
                out[k] = left.remove(rnd.nextInt(left.size()));
                s.removeCustomer(out[k]);
            }
            return out;
        }
    }

    /**
     * コストの悪い順: 抜くとコストが最も減る顧客 (前後との位置が悪い顧客) から抜く。
     * 減る量は d(a,c) + d(c,b) - d(a,b) (a, b は c の前後)。抜くたびに前後が変わるので、毎回数え直す。
     */
    final class WorstRemoval implements Destroy {
        static final double P = 3;   // Ropke & Pisinger の既定値
        private final double p;

        public WorstRemoval() {
            this(P);
        }

        WorstRemoval(double p) {
            this.p = p;
        }

        @Override
        public String name() {
            return "worst";
        }

        @Override
        public int[] destroy(Routes s, int q, Random rnd) {
            Instance inst = s.instance();
            int[] out = new int[q];
            for (int k = 0; k < q; k++) {
                long[] saving = new long[inst.n() + 1];
                List<Integer> left = customers(s);
                for (int c : left) {
                    int r = s.routeOf(c);
                    int i = s.posOf(c);
                    int a = s.prev(r, i);
                    int b = s.next(r, i);
                    saving[c] = (long) inst.dist(a, c) + inst.dist(c, b) - inst.dist(a, b);
                }
                left.sort(Comparator.<Integer>comparingLong(c -> -saving[c]).thenComparingInt(c -> c));
                out[k] = pick(left, p, rnd);
                s.removeCustomer(out[k]);
            }
            return out;
        }
    }

    /**
     * 近い顧客同士: 1 人を無作為に抜き、以後は抜いた顧客から 1 人を選んで、その顧客に近い順に抜く。
     * 近い顧客をまとめて抜くと、修復でその辺りのルートの割り当てを組み替えられる (Shaw removal の距離だけの版)。
     */
    final class RelatedRemoval implements Destroy {
        static final double P = 6;   // Ropke & Pisinger の既定値
        private final double p;

        public RelatedRemoval() {
            this(P);
        }

        RelatedRemoval(double p) {
            this.p = p;
        }

        @Override
        public String name() {
            return "related";
        }

        @Override
        public int[] destroy(Routes s, int q, Random rnd) {
            Instance inst = s.instance();
            List<Integer> left = customers(s);
            int[] out = new int[q];
            out[0] = left.remove(rnd.nextInt(left.size()));
            s.removeCustomer(out[0]);
            for (int k = 1; k < q; k++) {
                int from = out[rnd.nextInt(k)];
                left.sort(Comparator.<Integer>comparingInt(c -> inst.dist(from, c)).thenComparingInt(c -> c));
                out[k] = pick(left, p, rnd);
                left.remove(Integer.valueOf(out[k]));
                s.removeCustomer(out[k]);
            }
            return out;
        }
    }
}
