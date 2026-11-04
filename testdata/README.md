# 言語共通のテストデータ

Python 版と Java 版のテストが同じ入力と同じ正解を使うための置き場所。
ここにあるファイルは、2 つの実装のあいだの契約として扱う。

```
cases/     手書きの小さいインスタンスと、全探索で求めた期待値 (vrp-case/1)
cvrplib/   CVRPLib X set の小さいインスタンス 2 件と、その既知最良解 (BKS)
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
