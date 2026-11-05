// 全サブプロジェクト共通の設定。
subprojects {
    apply(plugin = "java")

    group = "io.github.doublesemicolon"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        // 手元に入っている JDK が何であっても、Java 21 でコンパイル・実行する
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"   // Windows の既定 (MS932) で日本語のコメントが化けないように
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))   // 警告はエラーとして扱う
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // 差分計算が壊れると、局所探索が「改善した」と思い込んで止まらなくなることがある。
        // 落ちるべきテストが止まったままにならないよう、1 テストごとに打ち切る (Python 版の timeout と同じ)
        systemProperty("junit.jupiter.execution.timeout.default", "60 s")
        // 既定はテストが自分で終わってから時間超過を判定するので、無限ループは止められない。
        // 別スレッドで走らせ、時間が来たら待たずに失敗にする
        systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
        // 言語共通のテストデータ。テストはこのプロパティからパスを得る
        val testdata = rootDir.resolve("../testdata").canonicalFile
        systemProperty("vrp.testdata", testdata.path)
        // 入力として宣言しないと、testdata だけを変えたとき Gradle はテストを
        // UP-TO-DATE として飛ばし、前回の結果のまま成功してしまう
        inputs.dir(testdata).withPathSensitivity(PathSensitivity.RELATIVE)
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
