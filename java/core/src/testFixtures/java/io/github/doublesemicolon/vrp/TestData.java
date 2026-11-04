package io.github.doublesemicolon.vrp;

import java.nio.file.Files;
import java.nio.file.Path;

/** 言語共通のテストデータ (リポジトリ直下の testdata/) の場所。 */
public final class TestData {
    private TestData() {}

    /**
     * build.gradle.kts がテストに渡す vrp.testdata を読む。
     *
     * <p>作業ディレクトリからの相対パスで探さないのは、IDE から実行したときと
     * Gradle から実行したときで作業ディレクトリが違うため。見つからなければ、
     * 黙ってテストを飛ばさずに失敗させる。
     */
    public static Path root() {
        String p = System.getProperty("vrp.testdata");
        if (p == null) {
            throw new IllegalStateException("システムプロパティ vrp.testdata が無い (Gradle から実行する)");
        }
        Path root = Path.of(p);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("testdata が見つからない: " + root);
        }
        return root;
    }

    /** 小さいインスタンスと全探索の期待値 (vrp-case/1)。 */
    public static Path cases() {
        return root().resolve("cases");
    }

    /** Python と Java がそれぞれ書いた結果ファイル (vrp-run/1)。 */
    public static Path runs() {
        return root().resolve("runs");
    }

    /** Python で計算した距離行列。Java の計算と突き合わせる。 */
    public static Path distances() {
        return root().resolve("distances");
    }
}
