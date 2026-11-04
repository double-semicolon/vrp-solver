# 言語共通のテストデータ

Python 版と Java 版のテストが同じ入力と同じ正解を使うための置き場所。
ここにあるファイルは、2 つの実装のあいだの契約として扱う。

```
cases/          手書きの小さいインスタンスと、全探索で求めた期待値 (vrp-case/1)
cvrplib/        CVRPLib X set の小さいインスタンス 2 件と、その既知最良解 (BKS)
distances/      座標と、Python で計算した距離行列 (vrp-dist/1)
runs/python/    Python のソルバが書いた結果ファイル (vrp-run/1)
runs/java/      Java が書いた結果ファイル (vrp-run/1)
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
| `runs/python/*.json` | Python | 今のソルバで作り直すと同じ。経過時間は除く | cost と feasible を計算し直すと一致 (`PythonWrittenRunsTest`) |
| `runs/java/golden.json` | Java | 今の書き出しと同じバイト列 (`GoldenRunTest`) | `load_run` で読め、Java で組み立てた値になる |
| `runs/invalid/*.json` | Python | 生成器の出力と同じ | 両言語とも、`invalid.json` の箇所を名指しして拒否 (`StrictReadingTest`) |

作り直し方 (定義を意図して変えたときだけ):

```
cd python && uv run python tests/make_testdata.py       distances/、runs/python/、runs/invalid/
cd java && VRP_REGENERATE=1 ./gradlew test               runs/java/golden.json
```

## vrp-run/1 を読むときの規則

Python (`serialize.py`) と Java (`VrpJson`) で同じにする。

- `NaN`・`Infinity`、同じキーの重複、知らないキー、欠けたキーはエラー
- 整数の欄は JSON の整数だけ (`3.7`、`3.0`、`true`、`"5"` は拒否)。`cost`・`iteration`・`stats` は
  64 bit、それ以外 (需要・容量・台数・顧客番号) は 32 bit に収まること
- 小数の欄は整数も受け付ける (`[50, 50]` など)。有限でなければエラー (`1e400` は読むと無限大になる)
- `null` を許すのは `num_vehicles` (上限なし)・`history` の `iteration`・`stats` の値だけ
- `stats` の値は null・真偽値・整数・小数・文字列のどれか (入れ子にしない)
- エラーの文言は `ファイル名: 箇所: 理由` の形。箇所 (`results[0].cost` など) は両言語で同じ書き方
