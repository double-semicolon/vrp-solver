plugins {
    `java-library`
    // testdata のケース (vrp-case/1) を読む Cases を、core のテストからも使えるようにする
    `java-test-fixtures`
}

dependencies {
    // 公開 API が Instance や SolveResult を返すので、使う側にも core が見える必要がある
    api(project(":core"))
    // Jackson は内部でだけ使う。公開 API に Jackson の型を出さない
    implementation(platform("tools.jackson:jackson-bom:3.2.4"))
    implementation("tools.jackson.core:jackson-databind")

    testImplementation(testFixtures(project(":core")))
    testFixturesImplementation(testFixtures(project(":core")))
    // Cases はケースのファイルを Jackson で読む。本体の implementation は見えないので別に足す
    testFixturesImplementation(platform("tools.jackson:jackson-bom:3.2.4"))
    testFixturesImplementation("tools.jackson.core:jackson-databind")
}
