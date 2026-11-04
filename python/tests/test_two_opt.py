"""
2-opt のテスト。

差分計算 (gain) は速さのための近道で、間違えても探索は黙って動き続ける。
だから gain は必ず route_cost() の計算し直しと突き合わせる。
"""

from __future__ import annotations

import random

import pytest
from conftest import NN_OVER, RANDOM_50, REFERENCE_IDS, REFERENCES, Reference
from make_testdata import TWO_OPT_TIE

from vrp.instances import TOY5, TOY8, random_instance
from vrp.model import Instance
from vrp.objective import route_cost, solution_cost, validate
from vrp.solvers import nearest_neighbor, solve_nn_2opt, two_opt
from vrp.solvers.two_opt import gain, improve_once

INSTANCES = [TOY5, TOY8, *RANDOM_50]
IDS = [i.name for i in INSTANCES]


def _reversed(route: list[int], i: int, j: int) -> list[int]:
    return route[:i] + route[i:j + 1][::-1] + route[j + 1:]


@pytest.mark.parametrize("m", [1, 2, 3, 4, 10, 25])
def test_gain_matches_full_recomputation(m: int):
    """
    全ての (i, j) で、gain が「逆順にする前後の route_cost の差」と一致すること。

    端 (i == 0 で depot から入る辺、j == m-1 で depot へ戻る辺) を取り違えるのが
    典型的なバグなので、短いルートも含める。
    """
    inst = random_instance(40, seed=m)
    rng = random.Random(m)
    for _ in range(20):
        route = rng.sample(inst.customers, m)
        for i in range(m):
            for j in range(i + 1, m):
                expected = route_cost(inst, route) - route_cost(inst, _reversed(route, i, j))
                assert gain(inst.D, route, i, j) == expected, (route, i, j)


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_accumulated_cost_matches_recomputation(inst: Instance):
    """差分で積算したコスト (history) と、目的関数で計算し直したコストが一致すること。"""
    res = solve_nn_2opt(inst)
    assert res.history[-1].cost == res.cost == solution_cost(inst, res.solution)


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_result_is_feasible_and_never_worse(inst: Instance):
    start = nearest_neighbor(inst)
    res = solve_nn_2opt(inst)

    assert validate(inst, res.solution) == []
    assert res.cost <= solution_cost(inst, start)
    assert res.solver == "nn+2opt"
    # ルート内の並べ替えしかしないので、どの客がどのルートかは変わらない
    assert [sorted(r) for r in res.solution] == [sorted(r) for r in start]


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_result_is_a_local_optimum(inst: Instance):
    """
    どの区間を逆順にしても、もう縮まないこと。

    gain を使わず route_cost で直接確かめる。gain が改善を見落とすバグがあれば、
    gain で判定する限り気づけない。
    """
    for route in solve_nn_2opt(inst).solution:
        base = route_cost(inst, route)
        for i in range(len(route)):
            for j in range(i + 1, len(route)):
                assert route_cost(inst, _reversed(route, i, j)) >= base, (route, i, j)


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_history_starts_from_nn_and_strictly_decreases(inst: Instance):
    res = solve_nn_2opt(inst)
    costs = [h.cost for h in res.history]

    assert res.history[0].iteration == 0
    assert costs[0] == solution_cost(inst, nearest_neighbor(inst))
    assert all(a > b for a, b in zip(costs, costs[1:]))
    assert [h.iteration for h in res.history] == list(range(len(costs)))
    assert res.stats["moves"] == len(costs) - 1


@pytest.mark.parametrize("ref", REFERENCES, ids=REFERENCE_IDS)
def test_reaches_the_optimum_on_toys(ref: Reference):
    """toy8 では最近傍法の 318 から 1 手で最適 303 に届く。toy5 は最初から最適。"""
    assert solve_nn_2opt(ref.inst).cost == ref.optimal_cost


def test_improves_on_50_customer_instances():
    """顧客 50 件規模で、実際に改善していること (Step 1 の目的)。"""
    for inst in RANDOM_50:
        assert solve_nn_2opt(inst).cost < solution_cost(inst, nearest_neighbor(inst)), inst.name


def test_does_not_modify_the_input():
    start = nearest_neighbor(TOY8)
    snapshot = [list(r) for r in start]
    two_opt(TOY8, start)
    assert start == snapshot


def test_does_not_fix_a_vehicle_limit_violation():
    """
    ルート数を変えない近傍なので、K 台超過の初期解は超過したまま出てくる。

    近い 1, 2 を1台に積むと 3, 4 が乗らず3台になる (K=2)。
    """
    res = solve_nn_2opt(NN_OVER)
    assert len(res.solution) == 3
    assert not res.feasible


def test_tie_goes_to_the_smallest_i_then_the_smallest_j():
    """
    縮む距離が同じ move が複数あるときの決め方。Java 版も同じ決め方で、解が完全に一致する。

    最近傍法のルート [3, 1, 2, 4, 5, 6] で、(i, j) = (3, 5) と (4, 5) がどちらも 1 縮む。
    i の小さい (3, 5) を選ぶので [3, 1, 2, 6, 5, 4] になる ((4, 5) なら [3, 1, 2, 4, 6, 5])。
    """
    inst = TWO_OPT_TIE
    route = nearest_neighbor(inst)[0]
    assert route == [3, 1, 2, 4, 5, 6]
    assert gain(inst.D, route, 3, 5) == gain(inst.D, route, 4, 5) == 1   # 前提: 同点
    assert max(gain(inst.D, route, i, j) for i in range(6) for j in range(i + 1, 6)) == 1

    assert improve_once(inst.D, route) == 1
    assert route == [3, 1, 2, 6, 5, 4]


def test_tie_with_the_same_i_goes_to_the_smallest_j():
    """
    同点の決め方の後半 (i が同じなら j の小さい方)。上のテストは i が違う同点なので、
    こちらが無いと「同じ i の中で j を大きい順に見る」誤りに気づけない。

    ルート [1, 2, 3, 4, 5, 6] で (1, 3) と (1, 5) がどちらも 4 縮む。(1, 3) を選ぶ。
    """
    inst = Instance(name="two-opt-tie-same-i",
                    coords=[(5, 5), (7, 4), (1, 6), (2, 8), (5, 7), (1, 2), (10, 5)],
                    demands=[0, 1, 1, 1, 1, 1, 1], capacity=6, num_vehicles=1)
    route = [1, 2, 3, 4, 5, 6]
    assert gain(inst.D, route, 1, 3) == gain(inst.D, route, 1, 5) == 4   # 前提: 同点
    assert max(gain(inst.D, route, i, j) for i in range(6) for j in range(i + 1, 6)) == 4

    assert improve_once(inst.D, route) == 4
    assert route == [1, 4, 3, 2, 5, 6]
