plugins {
    `java-library`
}

dependencies {
    // 公開 API が Instance や SolveResult を返すので、使う側にも core が見える必要がある
    api(project(":core"))
    // Jackson は内部でだけ使う。公開 API に Jackson の型を出さない
    implementation(platform("tools.jackson:jackson-bom:3.2.4"))
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(testFixtures(project(":core")))
}
