"""
全探索の参照値 (testdata/cases/*.json) を固定するテスト。

ここで固定した数値は、以降の全てのソルバが満たすべき正解として使う。
Step 1 以降で近傍やメタヒューリスティクスを実装したとき、小問題で
最適値に届かなければ実装バグだと即断できる。
"""

from __future__ import annotations

import pytest
from conftest import CASES, REFERENCE_IDS, REFERENCES, TESTDATA, Reference, naive_all_solutions

from vrp.instances import TOYS
from vrp.objective import solution_cost
from vrp.solvers import solve_brute_force

ALL = list(CASES.values())
ALL_IDS = list(CASES)
# 独立オラクルは全順列 x 全切り方を作るので重い。toy8 の派生 (台数の上限が大きい) は
# 1件で数十秒かかるため、顧客 5 件以下と基準の toy 2 件に絞る。
ORACLE = [c for c in ALL if c.inst.n <= 5 or c in REFERENCES]


@pytest.mark.parametrize("ref", ALL, ids=ALL_IDS)
def test_brute_force_reproduces_reference(ref: Reference):
    if ref.optimal_cost is None:
        with pytest.raises(ValueError, match="実行可能解が存在しない"):
            solve_brute_force(ref.inst)
        return
    res = solve_brute_force(ref.inst)
    assert res.cost == ref.optimal_cost
    assert res.stats["solutions"] == ref.n_solutions
    assert res.stats["nodes"] == ref.n_nodes
    assert res.feasible


@pytest.mark.parametrize("ref", ORACLE, ids=[c.inst.name for c in ORACLE])
def test_reference_agrees_with_independent_oracle(ref: Reference):
    """参照値そのものが正しいことを、全探索とは別の方法で確かめる。"""
    oracle = naive_all_solutions(ref.inst)
    assert len(oracle) == ref.n_solutions
    costs = [solution_cost(ref.inst, [list(r) for r in s]) for s in oracle]
    assert (min(costs) if costs else None) == ref.optimal_cost


def test_every_case_file_is_loaded():
    """glob で読んでいるので、ファイルが消えてもテストは黙って減るだけになる。数で縛る。"""
    assert len(CASES) == len(list((TESTDATA / "cases").glob("*.json"))) == 11
    assert all(c.description for c in ALL), "ケースの意図を description に書く"


@pytest.mark.parametrize("toy", TOYS, ids=[t.name for t in TOYS])
def test_packaged_toys_match_the_shared_files(toy):
    """
    CLI のデモが使う toy.py の定義と、共通ファイルが食い違っていないこと。

    パッケージは配布物に含まれない testdata/ を読めないので、定義が2か所にある。
    片方だけ直す事故をここで止める。
    """
    shared = CASES[toy.name].inst
    assert (shared.coords, shared.demands, shared.capacity, shared.num_vehicles) ==            (toy.coords, toy.demands, toy.capacity, toy.num_vehicles)


@pytest.mark.parametrize("ref", REFERENCES, ids=REFERENCE_IDS)
def test_toy_instance_uses_capacity_and_fleet(ref: Reference):
    """
    toy が「容量も台数も効いている」問題であること。

    1台で全員を回れてしまうと巡回セールスマン問題になり、ルートの分け方を
    間違えるバグがテストで見つからなくなる。
    """
    inst = ref.inst
    assert sum(inst.demands) > inst.capacity
    assert sum(inst.demands) <= inst.capacity * inst.num_vehicles
    assert len(solve_brute_force(inst).solution) > 1
