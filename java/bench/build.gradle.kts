// X set のベンチマーク (Python 版の vrp bench-x)。コマンドとして動かす部分はここに置き、
// core (探索) と json (ファイルの読み書き) には入れない。
//   ./gradlew :bench:run --args="--save"
plugins {
    application
}

dependencies {
    implementation(project(":json"))   // json が api で core を公開している
    testImplementation(testFixtures(project(":core")))
}

application {
    mainClass.set("io.github.doublesemicolon.vrp.bench.XBench")
}

tasks.named<JavaExec>("run") {
    // 既定のパス (../python/data/cvrplib/X と results/) を java/ から数える。
    // Gradle の既定はサブプロジェクトのディレクトリ (java/bench) なので変える
    workingDir = rootDir
}
