// Java 版のルート。サブプロジェクトの境界で「何が何に依存してよいか」を決める。
//   core: モデル・目的関数・ソルバ・CVRPLib ローダ。外部ライブラリに依存しない
//   json: 結果ファイル (vrp-run/1) などの JSON 入出力。core と Jackson に依存する
//   bench: X set のベンチマークを走らせるコマンド。core と json に依存する
// core から外部ライブラリを import するとコンパイルが通らないので、
// Python 版で「core が matplotlib を読まない」とテストしていた約束を、ここではビルドが守る。

rootProject.name = "vrp"

include("core", "json", "bench")
