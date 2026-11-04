"""テスト共通のヘルパ。"""

from __future__ import annotations

from dataclasses import dataclass
from itertools import combinations, permutations
from pathlib import Path

from vrp.instances import random_instance
from vrp.model import Instance, Solution
from vrp.serialize import instance_from_dict, loads

# 言語共通のテストデータ (リポジトリ直下)。Java 版のテストも同じファイルを読む。
TESTDATA = Path(__file__).resolve().parents[2] / "testdata"
CASE_FORMAT = "vrp-case/1"


@dataclass(frozen=True)
class Reference:
    """全探索 (参照実装) で求めた正解。以降の全てのソルバの検証に使う。"""

    inst: Instance
    optimal_cost: int | None   # None は実行可能解が存在しない
    n_solutions: int           # 実行可能解の総数 (重複なし)
    n_nodes: int               # 全探索の探索ノード数
    description: str = ""


def load_case(path: Path) -> Reference:
    d = loads(path.read_text(encoding="utf-8"))
    if d.get("format") != CASE_FORMAT:
        raise ValueError(f"{path.name}: 未知の形式 {d.get('format')!r}")
    inst = instance_from_dict(d["instance"])
    if inst.name != path.stem:
        raise ValueError(f"{path.name}: ファイル名とインスタンス名 {inst.name!r} が違う")
    e = d["expected"]
    return Reference(inst, e["optimal_cost"], e["n_solutions"], e["n_nodes"], d["description"])


# testdata/cases/*.json の全ケース。期待値は全探索で求めたもので、
# 実装を変えてこの数値が動いたら、それは意図した変更か必ず確認すること。
# 全探索を速くする変更は入れない約束なので、ここが動くこと自体が異常のサイン。
CASES: dict[str, Reference] = {
    p.stem: load_case(p) for p in sorted((TESTDATA / "cases").glob("*.json"))
}

# 手書きの toy 2 件。全てのソルバが最適値に届くかを確かめる基準。
REFERENCES = [CASES["toy5"], CASES["toy8"]]
REFERENCE_IDS = [ref.inst.name for ref in REFERENCES]

# 最近傍法が台数を超える例 (説明は testdata/cases/nn-over.json)
NN_OVER = CASES["nn-over"].inst

# 構築法・局所探索のテストで使う顧客 50 件のランダムインスタンス一式。
# 生成器が Python 専用なので JSON にはしない (Java 用は #20 で保存したものを使う)。
RANDOM_50 = [random_instance(50, seed=s, layout=lay)
             for s in range(5) for lay in ("uniform", "clustered")]


def canonical(sol: Solution) -> tuple[tuple[int, ...], ...]:
    """
    解を「同じ解」と見なせる形に正規化する。

    ルートの逆走 (距離が対称なのでコスト同一) と、ルート同士の並び順は
    解の同一性に影響しない。この2つを潰した形を代表として返す。
    """
    return tuple(sorted(min(tuple(r), tuple(reversed(r))) for r in sol))


def naive_all_solutions(inst: Instance) -> set[tuple[tuple[int, ...], ...]]:
    """
    実行可能解の集合を、列挙器とは無関係な素朴な方法で作る独立オラクル。

    顧客の全順列 (giant tour) を作り、それを K 個以下の連続する区間に切る。
    この方法は「顧客を順序付きで K 個以下のグループに分ける」全パターンを
    確実に網羅する。遅いが、列挙器が取りこぼしていないことを
    確かめるための基準として信頼できる。

    実行可能性は validate() を使わず自前で判定する。テスト対象と同じ関数を
    使うと、そこにバグがあったとき両方が同じように間違えて気づけない。
    切り方の都合で「全顧客をちょうど1回ずつ訪問」「車両数 <= K」は
    構成上自動的に満たされるので、実際に見るのは容量だけでよい。
    """
    n, Q, demands = inst.n, inst.capacity, inst.demands
    # 上限なし (None) は n 台と同じ。1ルートに顧客が最低1人いるので n 本を超えない
    K = n if inst.num_vehicles is None else inst.num_vehicles
    found: set[tuple[tuple[int, ...], ...]] = set()

    for tour in permutations(inst.customers):
        for n_cuts in range(min(K, n)):
            for cuts in combinations(range(1, n), n_cuts):
                bounds = (0,) + cuts + (n,)
                sol = [tour[a:b] for a, b in zip(bounds, bounds[1:])]
                if all(sum(demands[c] for c in r) <= Q for r in sol):
                    found.add(canonical(sol))
    return found
