"""
目的関数と実行可能性チェッカー。

「解の良さ」と「解が正しいか」の定義はここ1箇所にしかない。
全てのソルバがこのファイルの関数で採点される。
"""

from __future__ import annotations

from itertools import pairwise

from .model import Instance, Route, Solution

# ---------------------------------------------------------------- 目的関数


def route_cost(inst: Instance, route: Route) -> int:
    """depot -> 顧客列 -> depot の距離。空ルートは 0。"""
    if not route:
        return 0
    D = inst.D
    c = D[0][route[0]] + D[route[-1]][0]
    for a, b in pairwise(route):
        c += D[a][b]
    return c


def route_load(inst: Instance, route: Route) -> int:
    return sum(inst.demands[c] for c in route)


def solution_cost(inst: Instance, sol: Solution) -> int:
    return sum(route_cost(inst, r) for r in sol)


# ---------------------------------------------------------------- 実行可能性


def validate(inst: Instance, sol: Solution) -> list[str]:
    """実行可能でない理由を列挙して返す。空リストなら実行可能解。"""
    errors: list[str] = []

    if inst.num_vehicles is not None and len(sol) > inst.num_vehicles:
        errors.append(f"車両数超過: {len(sol)} 台使用 / 上限 {inst.num_vehicles} 台")

    visited: dict[int, int] = {}
    for r in sol:
        for c in r:
            visited[c] = visited.get(c, 0) + 1

    for c in inst.customers:
        cnt = visited.get(c, 0)
        if cnt == 0:
            errors.append(f"顧客 {c} が未訪問")
        elif cnt > 1:
            errors.append(f"顧客 {c} を {cnt} 回訪問")

    valid = set(inst.customers)
    for c in visited:
        if c == 0:
            errors.append("ルート内に depot(0) が含まれている")
        elif c not in valid:
            errors.append(f"存在しない顧客 {c}")

    for i, r in enumerate(sol):
        # 存在しない番号で demands を引くと、負の番号が末尾の需要を黙って読んでしまう
        load = route_load(inst, [c for c in r if c in valid])
        if load > inst.capacity:
            errors.append(f"ルート {i} が容量超過: 積載 {load} / 上限 {inst.capacity}")

    return errors


def is_feasible(inst: Instance, sol: Solution) -> bool:
    return not validate(inst, sol)
