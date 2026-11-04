package io.github.doublesemicolon.vrp;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * 暫定解が更新された瞬間の記録 (Python 版の result.Improvement)。
 *
 * @param iteration 反復回数の概念を持つソルバだけが埋める
 */
public record Improvement(double elapsedSec, long cost, OptionalLong iteration) {
    public Improvement {
        Objects.requireNonNull(iteration, "iteration");
    }

    public Improvement(double elapsedSec, long cost) {
        this(elapsedSec, cost, OptionalLong.empty());
    }

    public Improvement(double elapsedSec, long cost, long iteration) {
        this(elapsedSec, cost, OptionalLong.of(iteration));
    }
}
