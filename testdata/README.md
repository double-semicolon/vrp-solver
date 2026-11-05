# 言語共通のテストデータ

Python 版と Java 版のテストが同じ入力と同じ正解を使うための置き場所。
ここにあるファイルは、2 つの実装のあいだの契約として扱う。

```
cases/          手書きの小さいインスタンスと、全探索で求めた期待値 (vrp-case/1)
cvrplib/        CVRPLib X set の小さいインスタンス 2 件と、その既知最良解 (BKS)
cvrplib/parse/  ローダの対照 (valid.vrp / valid.sol) と、両言語が拒否する壊れたファイル (invalid/)
distances/      座標と、Python で計算した距離行列 (vrp-dist/1)
runs/python/    Python のソルバが書いた結果ファイル (vrp-run/1)
runs/java/      Java が書いた結果ファイル (vrp-run/1) とベンチマーク結果 (vrp-bench/1)
runs/invalid/   両言語が拒否しなければならない結果ファイル。invalid.json は拒否の理由の表
```

## cases/*.json (vrp-case/1)

```json
{
  "format": "vrp-case/1",
  "description": "このケースを置いている理由",
  "instance": { "name": "...", "coords": [[x, y], ...], "demands": [...],
                "capacity": 10, "num_vehicles": 2 },
  "expected": { "optimal_cost": 43, "n_solutions": 2, "n_nodes": 13 }
}
```

- `instance` は結果ファイル (vrp-run/1) のインスタンスと同じ形。index 0 が depot。
  `num_vehicles` が `null` なら車両数の上限なし。ファイル名は `instance.name` と同じにする。
- `expected` は Python の全探索 (参照実装、速くしない約束) で求めた値。
  - `optimal_cost`: 最適な総距離。実行可能解が存在しなければ `null` (ソルバはエラーにする)
  - `n_solutions`: 実行可能解の総数。ルートの並び順と逆走を同一視して数える
  - `n_nodes`: 全探索の探索ノード数。探索順まで同じ実装でなければ一致しない
- 距離は EUC_2D (ユークリッド距離を四捨五入して整数化。0.5 は切り上げ)。

期待値が正しいことは、Python のテストで全探索とは別の方法 (独立オラクル) でも確かめている
(`python/tests/test_reference.py`)。値を書き換えるのは、全探索の定義を意図して変えたときだけ。

## 2 つの実装をつなぐファイル (distances/、runs/)

CI では Python と Java が別々のジョブで走るので、ファイルを介して両方向を確かめる。

| ファイル | 書く側 | 書く側のテスト | 読む側のテスト |
|---|---|---|---|
| `distances/*.json` | Python | 今の定義で計算し直すと同じ (`test_testdata_files.py`) | Java の計算と全要素が一致 (`DistanceMatrixTest`) |
| `runs/python/*.json` | Python | 今のソルバで作り直すと同じ。経過時間は除く。2-opt の同点が 20 回以上起きていること | cost と feasible を計算し直すと一致 (`PythonWrittenRunsTest`)。Java のソルバで解き直すと、解・stats・改善の履歴まで一致 (`SameAsPythonTest`) |
| `runs/java/golden.json` | Java | 今の書き出しと同じバイト列 (`GoldenRunTest`) | `load_run` で読め、Java で組み立てた値になる |
| `runs/java/bench-x.json` | Java | `cvrplib/` の 2 件で今の `XBench` が書く内容と同じ。経過時間は除く (`XBenchTest`) | `load_bench` で読め、Python が同じ 2 件を走らせた結果と `compare_benches` で一致 |
| `runs/invalid/*.json` | Python | 生成器の出力と同じ | 両言語とも、`invalid.json` の箇所を名指しして拒否 (`StrictReadingTest`) |
| `cvrplib/parse/invalid/*` | Python | 生成器の出力と同じ | 両言語とも、`invalid.tsv` の理由を名指しして拒否 (`CvrplibTest`)。core のテストは JSON を読めないので表はタブ区切り |

作り直し方 (定義を意図して変えたときだけ):

```
cd python && uv run python tests/make_testdata.py       distances/、runs/python/、runs/invalid/、cvrplib/parse/
cd java && VRP_REGENERATE=1 ./gradlew test               runs/java/golden.json、runs/java/bench-x.json
```

## vrp-run/1・vrp-bench/1 を読むときの規則

Python (`serialize.py`、`benchmark.py` の `load_bench`) と Java (`VrpJson`) で同じにする。
vrp-bench/1 の行 (`rows[i]`) の欄は `instance`・`n`・`bks`・`results` で、`results` の 1 件は vrp-run/1 と同じ形。

- `NaN`・`Infinity`、同じキーの重複、知らないキー、欠けたキーはエラー
- 整数の欄は JSON の整数だけ (`3.7`、`3.0`、`true`、`"5"` は拒否)。`cost`・`iteration`・`stats`・`bks` は
  64 bit、それ以外 (需要・容量・台数・顧客番号) は 32 bit に収まること
- 小数の欄は整数も受け付ける (`[50, 50]` など)。有限でなければエラー (`1e400` は読むと無限大になる)
- `null` を許すのは `num_vehicles` (上限なし)・`history` の `iteration`・`stats` の値だけ
- `stats` の値は null・真偽値・整数・小数・文字列のどれか (入れ子にしない)
- エラーの文言は `ファイル名: 箇所: 理由` の形。箇所 (`results[0].cost` など) は両言語で同じ書き方

## CVRPLib のファイルを読むときの数の形

Python (`cvrplib.py`) と Java (`Cvrplib.java`) で同じにする。標準の関数は言語ごとに緩さが違い
(Python の `int` は `1_000` を、Java の `parseInt` は全角数字を、`parseDouble` は `0x1p4`・`1d`・`NaN` を読む)、
任せると読めるファイルの範囲が食い違うので、正規表現で形を確かめてから変換する。

- 整数: `-?[0-9]+`
- 座標: `-?[0-9]+(\.[0-9]+)?([eE][-+]?[0-9]+)?`。有限でなければエラー (`1e400`)
- `.sol` の Cost: `[0-9]+` か、小数部がすべて 0 のもの (`20215.0`)
