"""
最近傍法のテスト。

構築法は最適性を求めないので、「正しい形の解を返す」ことと
「最適値からどれだけ離れているか」を分けて確かめる。
"""

from __future__ import annotations

import pytest
from conftest import REFERENCE_IDS, REFERENCES, Reference

from vrp.instances import TOY5, TOY8, random_instance
from vrp.model import Instance
from vrp.objective import validate
from vrp.solvers import nearest_neighbor, solve_nearest_neighbor

RANDOM = [random_instance(50, seed=s, layout=lay) for s in range(5) for lay in ("uniform", "clustered")]
INSTANCES = [TOY5, TOY8, *RANDOM]
IDS = [i.name for i in INSTANCES]


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_visits_every_customer_once_within_capacity(inst: Instance):
    """
    車両数上限は破りうるが、訪問と容量は必ず守る。

    validate() を使わず自前で見る。validate() は K 台超過もエラーにするので、
    「K 以外は守っている」ことを確かめるには粗すぎる。
    """
    sol = nearest_neighbor(inst)
    assert sorted(c for r in sol for c in r) == inst.customers
    assert all(r for r in sol), "空ルートがある"
    assert all(sum(inst.demands[c] for c in r) <= inst.capacity for r in sol)


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_result_passes_the_feasibility_check(inst: Instance):
    """これらのインスタンスでは K 台に収まり、実行可能解になる。"""
    res = solve_nearest_neighbor(inst)
    assert validate(inst, res.solution) == []
    assert res.feasible
    assert res.solver == "nn"
    assert res.stats["routes"] == len(res.solution)


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_is_deterministic(inst: Instance):
    assert nearest_neighbor(inst) == nearest_neighbor(inst)


# toy での最近傍法の値。最適値との差は Step 1 で局所探索が埋めるべき幅になる。
# 実装を変えてここが動いたら、構築法の振る舞いが変わったということ。
NN_COSTS = {"toy5": 246, "toy8": 318}


@pytest.mark.parametrize("ref", REFERENCES, ids=REFERENCE_IDS)
def test_gap_to_the_optimum_on_toys(ref: Reference):
    """toy5 では最適と一致し、toy8 では最適 303 に対して 318 (+5.0%)。"""
    res = solve_nearest_neighbor(ref.inst)
    assert res.cost >= ref.optimal_cost
    assert res.cost == NN_COSTS[ref.inst.name]


def _line(name: str, xs: list[tuple[float, float]], demands: list[int], capacity: int, k: int):
    return Instance(name=name, coords=[(0, 0), *xs], demands=[0, *demands],
                    capacity=capacity, num_vehicles=k)


def test_skips_the_nearest_customer_when_it_does_not_fit():
    """
    最も近い客が積めないときは、そこで戻らず、積める中で最も近い客へ行く。

    1 (需要8) を積んだ後、次に近い 2 (需要8) は入らないが、遠い 3 (需要2) は入る。
    """
    inst = _line("skip", [(1, 0), (2, 0), (3, 0)], [8, 8, 2], capacity=10, k=2)
    assert nearest_neighbor(inst) == [[1, 3], [2]]


def test_may_exceed_the_vehicle_limit_and_says_so():
    """
    K 台を超えた解は、隠さず feasible=False で返す。

    近い 1, 2 (需要3ずつ) を同じ車に積むと、需要7の 3, 4 が1台に乗らず3台要る。
    {1,3} と {2,4} に分ければ2台で済むので、実行可能解自体は存在する。
    """
    inst = _line("over", [(1, 0), (2, 0), (0, 10), (0, -10)], [3, 3, 7, 7], capacity=10, k=2)
    res = solve_nearest_neighbor(inst)
    assert res.solution == [[1, 2], [3], [4]]
    assert not res.feasible
    assert validate(inst, [[1, 3], [2, 4]]) == []
