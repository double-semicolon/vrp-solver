"""テスト共通のヘルパ。"""

from __future__ import annotations

from itertools import combinations, permutations

from vrp.model import Instance, Solution


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
    n, K, Q, demands = inst.n, inst.num_vehicles, inst.capacity, inst.demands
    found: set[tuple[tuple[int, ...], ...]] = set()

    for tour in permutations(inst.customers):
        for n_cuts in range(min(K, n)):
            for cuts in combinations(range(1, n), n_cuts):
                bounds = (0,) + cuts + (n,)
                sol = [tour[a:b] for a, b in zip(bounds, bounds[1:])]
                if all(sum(demands[c] for c in r) <= Q for r in sol):
                    found.add(canonical(sol))
    return found
