"""
全探索 (参照実装) のテスト。

toy インスタンスの参照値を焼く前に、列挙そのものが正しいことを
独立オラクル (conftest.naive_all_solutions) との突き合わせで確かめる。
"""

from __future__ import annotations

import pytest
from conftest import canonical, naive_all_solutions

from vrp.model import Instance
from vrp.objective import is_feasible, solution_cost
from vrp.solvers.bruteforce import enumerate_solutions, solve_brute_force

COORDS = [(0, 0), (6, 1), (2, 7), (-5, 3), (-3, -6), (4, -4), (8, 6)]
DEMANDS = [0, 3, 4, 2, 5, 3, 4]


def make(n: int, capacity: int, num_vehicles: int) -> Instance:
    return Instance(
        name=f"t{n}-q{capacity}-k{num_vehicles}",
        coords=COORDS[: n + 1],
        demands=DEMANDS[: n + 1],
        capacity=capacity,
        num_vehicles=num_vehicles,
    )


# 容量・車両数の効き方が違う組み合わせを並べる
INSTANCES = [
    make(1, 5, 1),     # 顧客1件
    make(4, 100, 1),   # 1台で全員回れる = 巡回セールスマン
    make(4, 100, 4),   # 容量が効かず、台数の分け方だけが自由
    make(5, 7, 3),     # 容量がきつく、分け方が限られる
    make(6, 9, 3),
]
IDS = [inst.name for inst in INSTANCES]


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_every_enumerated_solution_is_feasible(inst: Instance):
    for sol in enumerate_solutions(inst):
        assert is_feasible(inst, sol), f"実行不可能な解を列挙した: {sol}"


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_enumeration_has_no_duplicates(inst: Instance):
    """docstring の「同じ解の重複は一切生成しない」を数値で縛る。"""
    seen = [canonical(sol) for sol in enumerate_solutions(inst)]
    assert len(seen) == len(set(seen))


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_enumeration_is_exhaustive(inst: Instance):
    """独立オラクルと突き合わせて、取りこぼしが無いことを確認する。"""
    enumerated = {canonical(sol) for sol in enumerate_solutions(inst)}
    assert enumerated == naive_all_solutions(inst)


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_brute_force_finds_optimum(inst: Instance):
    oracle_best = min(solution_cost(inst, [list(r) for r in s]) for s in naive_all_solutions(inst))

    res = solve_brute_force(inst)

    assert res.cost == oracle_best
    assert res.feasible
    assert res.stats["solutions"] == len(naive_all_solutions(inst))
    assert res.stats["nodes"] >= res.stats["solutions"]
    assert res.instance == inst.name and res.solver == "brute"


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_history_is_strictly_improving_and_ends_at_optimum(inst: Instance):
    res = solve_brute_force(inst)
    costs = [h.cost for h in res.history]
    assert costs == sorted(costs, reverse=True)
    assert len(costs) == len(set(costs))
    assert costs[-1] == res.cost


def test_no_feasible_solution_raises():
    # 需要の合計 14 に対して 容量 5 x 2台 = 10 しか積めない
    with pytest.raises(ValueError, match="実行可能解が存在しない"):
        solve_brute_force(make(4, 5, 2))
