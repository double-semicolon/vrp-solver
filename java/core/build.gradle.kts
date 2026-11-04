// core は実行時の依存を持たない。dependencies に implementation を足さないこと。
// テストの依存 (JUnit) はルートの build.gradle.kts で全サブプロジェクトに入れている。
plugins {
    // testdata の場所を引く TestData を、json のテストからも使えるようにする
    `java-test-fixtures`
}
