package io.github.doublesemicolon.vrp.search;

import java.util.function.Consumer;

/**
 * 近傍。今の解から 1 手で作れる解の集合を、手 ({@link Move}) として列挙する。
 *
 * <p>約束 (全ての近傍のテストで、総当たりで作った集合と突き合わせて確かめる):
 * <ul>
 *   <li>実行可能な解に対して、適用後も実行可能な手だけを、漏れなく渡す
 *   <li>適用しても解が変わらない手 (ルートの並び順を除いて同じになる手) は渡さない
 *   <li>手の delta は、適用前後のコストを計算し直した差と一致する
 * </ul>
 * 実行可能でない解 (容量超過、車両数超過) を渡したときの振る舞いは決めない。
 */
public interface Neighborhood {
    /** 近傍の名前 (結果の stats や表示に使う)。 */
    String name();

    void scan(Routes s, Consumer<Move> out);
}
