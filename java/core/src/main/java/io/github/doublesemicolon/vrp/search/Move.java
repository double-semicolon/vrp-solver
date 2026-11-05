package io.github.doublesemicolon.vrp.search;

/**
 * 近傍の手 1 つ。{@link Neighborhood#scan} が、そのときの解に対して作る。
 *
 * <p>作ったときの解にしか適用できない (ルートの番号や位置で手を表しているので、
 * 解が変わると別の場所を指してしまう)。
 */
public interface Move {
    /** 適用したときのコストの変化。負なら改善。差分で求めた値。 */
    long delta();

    /** 解に適用する。 */
    void apply(Routes s);
}
