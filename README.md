# vrp-solver

[![CI](https://github.com/double-semicolon/vrp-solver/actions/workflows/ci.yml/badge.svg)](https://github.com/double-semicolon/vrp-solver/actions/workflows/ci.yml)

> **就職活動用の制作物です。** 応募先企業への提出を前提に開発しており、
> 動くことだけでなく、設計上の判断とその理由が README とコミット履歴から
> 追えることを重視しています。

容量制約付き配送計画問題 (CVRP) を、全探索から始めて段階的に実用的なソルバへ育てる。

各段階で「前の段階の結果を正解として使う」形で正しさを担保するのが方針。
小さい問題の厳密解が残っている限り、後から入れた高速化が答えを壊していないことを
テストで確認できる。

## ロードマップ

| | 内容 | 言語 | 状態 |
|---|---|---|---|
| Step 0 | 全探索で厳密解 (顧客5〜8件)、枝刈り (branch and bound) | Python | 完了 |
| Step 1 | 構築法 (最近傍法) + 2-opt。顧客50件規模で改善幅を確認 | Python | 完了 |
| Step 2 | CVRPLib X set を読み込み、BKS (既知最良解) との gap で評価 | Python | 完了 |
| Step 3 | Java へ移植。同じ入力に対する出力 JSON が Python 版と一致することを確認 | Java | |
| Step 4 | 近傍拡張 (relocate / swap / Or-opt / 2-opt*)、近傍リスト、don't-look bits、ALNS | Java | |
| Step 5 | API 化して AWS にデプロイ | Java | |

進捗とタスクは GitHub Project で管理する。各 Step を 1 つのマイルストーンとし、
作業単位は issue に切る。

## 結果

### Step 1: 最近傍法 + ルート内 2-opt

ランダムインスタンス (シード固定) で、各シードの最良を 0% としたときの差の平均。

| 顧客数 | 配置 | シード | 最近傍法 | 最近傍法 + 2-opt | 最適 (B&B) |
|---|---|---|---|---|---|
| 50 | 一様 | 20 個 | +4.94% | 0% | - |
| 50 | クラスタ | 20 個 | +4.20% | 0% | - |
| 10 | 一様 | 10 個 | +29.04% | +22.64% | 0% |

最適解が出せる 10 件で見ると、2-opt が埋めるのは最近傍法と最適の差の 2 割強にとどまる。
2-opt はルートの中の順序しか変えないので、「どの顧客をどの車に割り当てるか」の
悪さは残る。最近傍法は最後の車にほぼ空のルートを作りがちで、これはルート間の
近傍 (Step 4 の relocate など) でしか直せない。

再現するコマンド:

```
uv run vrp bench -n 50 --seeds 20 --solvers nn nn+2opt
uv run vrp bench -n 50 --seeds 20 --solvers nn nn+2opt --layout clustered
uv run vrp bench -n 10 --seeds 10 --solvers nn nn+2opt bnb
```

### Step 2: CVRPLib X set での BKS との gap

X set 全 100 インスタンス (顧客 100〜1000 件) で、既知最良解 (BKS) からの差。
BKS のコストは .sol の記載を信用せず、目的関数で採点し直して記載と一致することを確かめてから使う。

| 顧客数 | インスタンス | 最近傍法 | 最近傍法 + 2-opt | 2-opt の改善 |
|---|---|---|---|---|
| 100〜250 | 33 | +28.64% | +24.97% | 3.68 ポイント |
| 251〜500 | 35 | +25.34% | +21.77% | 3.57 ポイント |
| 501〜1000 | 32 | +22.37% | +19.65% | 2.71 ポイント |
| 全体 | 100 | +25.48% | +22.15% | 3.33 ポイント |

gap の範囲は、最近傍法 + 2-opt で +1.5% (X-n376-k94) から +49.0% (X-n101-k25)。
時間は両ソルバとも 1 インスタンス平均 0.04 秒 (n=1000 でも 0.2 秒)。

ルート内 2-opt が詰めるのは 3 ポイント程度で、BKS との差の大半は残る。
Step 1 の「割り当ての悪さはルート内の近傍では直らない」という見立てを、
外部の基準でも確かめた形になる。Step 4 のルート間近傍は、この +22% をどこまで詰めるかで評価する。

再現するコマンド (X set の取得に py7zr が要る。`uv sync --extra bench`。開発用の環境には入っている):

```
uv run vrp fetch-x                   python/data/cvrplib/X に 100 インスタンスと BKS を取得 (リポジトリには含めない)
uv run vrp bench-x --save            全インスタンスで nn / nn+2opt の gap を測り、results/ に JSON で保存
```

## 使い方

`python/` で実行する。作図には matplotlib が要る (`uv sync --extra viz`。開発用の環境には入っている)。

```
uv run vrp demo                                      toy5/toy8 で全探索・枝刈り・構築法・2-opt を並べる
uv run vrp compare --instance random -n 50 --plot    複数ソルバを比べ、ルート図と収束図を出す
uv run vrp compare --instance toy8 --save            結果を results/ に JSON で保存する
uv run vrp plot results/xxx.json                     保存した結果から作図し直す
uv run vrp fetch-x                                   CVRPLib の X set を取得する
uv run vrp bench-x --max-n 300                       X set (顧客 300 件以下) で BKS との gap を測る
uv run pytest                                        テスト
```

Java 版は `java/` で実行する。JDK 21 が要る (Gradle は wrapper が取得するので入れなくてよい)。

```
./gradlew test                                       テスト (Windows では gradlew.bat test)
```

push ごとに GitHub Actions で両方のテストが走る (`.github/workflows/ci.yml`)。

## 言語の役割分担

**Python で検証し、Java で育てる。**

- Python (Step 0〜2): アルゴリズムの正しさを小さく速く確かめる場所。
  厳密解の参照値、評価の仕組み、作図をここで揃える。
- Java (Step 3〜5): 速度が要る探索と、サービスとして動かす部分の本実装。

移植の前に評価の仕組み (Step 2) を Python で完成させておくのは、
Java 版の正しさを「Python 版と同じ答えが出るか」で機械的に判定するため。
移植ミスとアルゴリズムの弱さを切り分けられる状態で移植に入る。

## 設計上の約束

**参照実装を凍結する。** 全探索は速くしない。toy インスタンスでの最適値・解の総数・
探索ノード数をテストに焼き、以降の高速化は全て「これと同じ答えに到達すること」で検証する。
(`testdata/cases/` に言語共通の JSON として置き、Python と Java の両方のテストが読む。branch and bound と 2-opt は toy で同じ最適値に届くことをテストしている)

**コストは必ず目的関数モジュールで採点し直す。** ソルバが差分計算で積算した値は
そのまま信用せず、結果を返す時点で計算し直して上書きする。差分計算のバグが結果として通らない。
(`SolveResult.build`。2-opt では差分で積算した値と計算し直した値の一致もテストしている)

**core は無依存に保つ。** 探索ロジックは標準ライブラリだけで書き、作図などの依存は
optional に切り出す。Java に移植する対象を純粋な探索ロジックだけに限るための線引き。
(matplotlib は `viz.py` でしか import しない。core が読み込まないことを別プロセスで確かめるテストがある)

**車両数は上限なしで解く。** CVRPLib の X set は台数を固定しない規約
(一覧表の規約欄に "Number of Routes: Free" とある) なので、BKS と同じルールで比べるために
`num_vehicles` を `None` (上限なし) にする。名前の k (例: X-n101-k25 の 25) は
⌈総需要 / 容量⌉ で決まる最小台数で、上限ではない。実際、X-n101-k25 の BKS は 26 ルート
を使っている (X-n106-k14 の BKS は k と同じ 14 ルート)。k を上限にすると BKS が実行不可能になる。
(`tests/test_cvrplib.py` で、BKS が実行可能で、採点し直したコストが公表値と一致することを確かめている)

**Java の core は外部ライブラリに依存しない。** `java/core` (モデル・目的関数・ソルバ) は
依存ゼロのサブプロジェクトにし、JSON の入出力は別のサブプロジェクト `java/json` (Jackson) に置く。Python では
テストで確かめていた線引きを、Java ではビルドの依存関係として書くので、core から外部ライブラリを
import するとコンパイルが通らない。(`java/settings.gradle.kts`、`java/core/build.gradle.kts`)

**JSON は厳格に保つ。両言語で同じ規則にする。** `inf` / `NaN` を書かない (`null` への書き換えもせず、
書き込みでも読み込みでも例外にする)。読むときは、知らないキー・重複したキー・整数の欄の `3.7` や `true` も拒否する。
片方だけ緩いと「Python では読めるが Java では読めないファイル」ができ、突き合わせの意味が無くなる。
拒否すべきファイルを `testdata/runs/invalid/` に置き、両言語のテストが同じファイルを全て、
同じ箇所を名指しして拒否することを確かめている。(`serialize.py`、`java/json` の `VrpJson`)

**距離は両言語で同じ式で計算する。** `int(sqrt(dx*dx + dy*dy) + 0.5)`。2乗を `**` (C の `pow`) で
書くと、Windows では `x*x` と 0.2% ほど結果が食い違う (`pow` には正しく丸める保証が無い)。
Java の `Math.round` は `0.49999999999999994` を 0 にするが、この式では 1 になるので使わない。
(`testdata/distances/` に Python で計算した行列を置き、Java の計算と全要素を比べている)

## 構成

```
python/    Python 実装 (Step 0〜2)
java/      Java 実装 (Step 3〜)
testdata/  両方の実装が使うテストデータ (インスタンスと期待値)
```
