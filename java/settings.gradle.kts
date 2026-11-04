// Java 版のルート。サブプロジェクトの境界で「何が何に依存してよいか」を決める。
//   core: モデル・目的関数・ソルバ・CVRPLib ローダ。外部ライブラリに依存しない
// JSON の入出力や CLI は、core に依存する別のサブプロジェクトとして足す (#17〜)。
// core から外部ライブラリを import するとコンパイルが通らないので、
// Python 版で「core が matplotlib を読まない」とテストしていた約束を、ここではビルドが守る。

rootProject.name = "vrp"

include("core")
