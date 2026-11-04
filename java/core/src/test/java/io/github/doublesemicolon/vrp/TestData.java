package io.github.doublesemicolon.vrp;

import java.nio.file.Files;
import java.nio.file.Path;

/** 言語共通のテストデータ (リポジトリ直下の testdata/) の場所。 */
final class TestData {
    private TestData() {}

    /**
     * build.gradle.kts がテストに渡す vrp.testdata を読む。
     *
     * <p>作業ディレクトリからの相対パスで探さないのは、IDE から実行したときと
     * Gradle から実行したときで作業ディレクトリが違うため。見つからなければ、
     * 黙ってテストを飛ばさずに失敗させる。
     */
    static Path root() {
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

    static Path cases() {
        return root().resolve("cases");
    }
}
