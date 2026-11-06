package io.github.doublesemicolon.vrp.alns;

import java.util.Random;

/**
 * 焼きなましの受理基準。今の解より悪い候補も、確率 exp(-(候補 - 今) / T) で受け入れる。
 * T は反復ごとに一定の比で下げる。
 *
 * <p>初期温度は「初期解より 5% 悪い候補を確率 0.5 で受け入れる」温度、最後の反復では
 * その 0.2% まで下げる (Ropke &amp; Pisinger の 25000 反復・冷却率 0.99975 で下がる割合とほぼ同じ)。
 * 反復の回数に合わせて冷却率を決めるので、回数を変えても始めと終わりの温度は変わらない。
 */
final class Annealing {
    static final double START_WORSE = 0.05;
    static final double START_PROBABILITY = 0.5;
    static final double END_RATIO = 0.002;

    private double temperature;
    private final double cooling;

    Annealing(double start, double cooling) {
        this.temperature = start;
        this.cooling = cooling;
    }

    static Annealing of(long initialCost, int iterations) {
        double start = -START_WORSE * initialCost / Math.log(START_PROBABILITY);
        double cooling = iterations <= 1 ? 1 : Math.pow(END_RATIO, 1.0 / (iterations - 1));
        return new Annealing(start, cooling);
    }

    double temperature() {
        return temperature;
    }

    /** 悪くならない候補は必ず受け入れる (乱数は使わない)。悪い候補のときだけ乱数を 1 つ使う。 */
    boolean accept(long candidate, long current, Random rnd) {
        if (candidate <= current) {
            return true;
        }
        return temperature > 0 && rnd.nextDouble() < Math.exp(-(candidate - current) / temperature);
    }

    void cool() {
        temperature *= cooling;
    }
}
