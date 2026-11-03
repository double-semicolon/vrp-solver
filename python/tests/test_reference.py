"""
toy インスタンスでの全探索の参照値を固定するテスト。

ここで固定した数値は、以降の全てのソルバが満たすべき正解として使う。
Step 1 以降で近傍やメタヒューリスティクスを実装したとき、小問題で
最適値に届かなければ実装バグだと即断できる。
"""

from __future__ import annotations

import pytest
from conftest import REFERENCE_IDS, REFERENCES, Reference, naive_all_solutions

from vrp.objective import solution_cost
from vrp.solvers import solve_brute_force


@pytest.mark.parametrize("ref", REFERENCES, ids=REFERENCE_IDS)
def test_brute_force_reproduces_reference(ref: Reference):
    res = solve_brute_force(ref.inst)
    assert res.cost == ref.optimal_cost
    assert res.stats["solutions"] == ref.n_solutions
    assert res.stats["nodes"] == ref.n_nodes
    assert res.feasible


@pytest.mark.parametrize("ref", REFERENCES, ids=REFERENCE_IDS)
def test_reference_agrees_with_independent_oracle(ref: Reference):
    """参照値そのものが正しいことを、全探索とは別の方法で確かめる。"""
    oracle = naive_all_solutions(ref.inst)
    assert len(oracle) == ref.n_solutions
    assert min(solution_cost(ref.inst, [list(r) for r in s]) for s in oracle) == ref.optimal_cost


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
