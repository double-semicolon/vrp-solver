package io.github.doublesemicolon.vrp.alns;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 演算子の重み (適応的な重み)。演算子は重みに比例する確率で選ぶ (ルーレット選択)。
 *
 * <p>Ropke &amp; Pisinger (2006) の方式に倣う。反復を {@link #SEGMENT} 回ずつの区間に分け、
 * 区間の中で演算子が使われるたびに、その反復の結果に応じて点を足す:
 * <ul>
 *   <li>{@link #BEST}: 最良解を更新した
 *   <li>{@link #BETTER}: 今の解より良い候補を作った (最良解は更新しない)
 *   <li>{@link #WORSE_ACCEPTED}: 今の解より悪いが、受け入れられた (探索を進めた)
 * </ul>
 * 今の解と同じコストの候補には点を付けない。抜いた顧客を元の場所に戻しただけの候補は
 * よく起きるので、点を付けると何もしない演算子が得をする (R&amp;P は解のハッシュで
 * 「初めて見た解か」を確かめるが、ここではコストが変わったかで代える)。
 * 修復に失敗した反復は 0 点だが、使った回数には数えるので、失敗の多い演算子は平均点が下がる。
 *
 * <p>区間の終わりに、重み w を w (1 - r) + r × (区間の点の合計 / 区間で使った回数) に更新する
 * (r は {@link #REACTION})。区間で 1 度も使われなかった演算子の重みは変えない。
 * 固定の重みのとき (adaptive = false) は、点を数えても重みは 1 のまま (一様に選ぶ)。
 */
final class Weights {
    static final int SEGMENT = 100;
    static final double BEST = 33;
    static final double BETTER = 9;
    static final double WORSE_ACCEPTED = 13;
    static final double REACTION = 0.1;

    private final boolean adaptive;
    private final double[] weight;
    private final double[] score;
    private final long[] uses;
    private final List<List<Double>> trace = new ArrayList<>();

    Weights(int operators, boolean adaptive) {
        this.adaptive = adaptive;
        this.weight = new double[operators];
        this.score = new double[operators];
        this.uses = new long[operators];
        java.util.Arrays.fill(weight, 1.0);
        for (int i = 0; i < operators; i++) {
            trace.add(new ArrayList<>(List.of(1.0)));
        }
    }

    /** 重みに比例する確率で演算子を 1 つ選ぶ。乱数を 1 つだけ使う。 */
    int select(Random rnd) {
        double total = 0;
        for (double w : weight) {
            total += w;
        }
        double x = rnd.nextDouble() * total;
        int last = 0;
        for (int i = 0; i < weight.length; i++) {
            if (weight[i] > 0) {
                last = i;
                x -= weight[i];
                if (x < 0) {
                    return i;
                }
            }
        }
        return last;   // 丸め誤差で x が残ったとき
    }

    /** 演算子 i を使った。点は {@link #reward} で後から足す。 */
    void used(int i) {
        uses[i]++;
    }

    void reward(int i, double points) {
        score[i] += points;
    }

    /** 区間の終わり。重みを更新して記録し、区間の点と回数を消す。 */
    void endSegment() {
        for (int i = 0; i < weight.length; i++) {
            if (adaptive && uses[i] > 0) {
                weight[i] = weight[i] * (1 - REACTION) + REACTION * score[i] / uses[i];
            }
            score[i] = 0;
            uses[i] = 0;
            trace.get(i).add(weight[i]);
        }
    }

    double weight(int i) {
        return weight[i];
    }

    /** 演算子 i の重みの推移。最初の値 (1) と、区間が終わるたびの値。 */
    List<Double> trace(int i) {
        return List.copyOf(trace.get(i));
    }
}
