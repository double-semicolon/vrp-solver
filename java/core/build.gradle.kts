// core は実行時の依存を持たない。dependencies に implementation を足さないこと。
// テストだけが JUnit を使う (testImplementation は core の本体には入らない)。
dependencies {
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
