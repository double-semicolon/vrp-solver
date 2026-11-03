"""
枝刈り付き全探索 (branch and bound) のテスト。

参照実装 (bruteforce) との関係を2段階で縛る:
  1. prune=False なら探索木が完全に一致すること (順列生成の書き換えが等価である証明)
  2. prune=True なら同じ最適コストに到達すること (枝刈りが答えを削っていない証明)
"""

from __future__ import annotations

import pytest
from conftest import REFERENCE_IDS, REFERENCES, Reference, canonical

from vrp.instances import TOY5, TOY8
from vrp.model import Instance
from vrp.objective import solution_cost
from vrp.solvers import (
    BranchAndBound,
    enumerate_solutions,
    solve_branch_and_bound,
    solve_brute_force,
)


def _variant(inst: Instance, name: str, **overrides) -> Instance:
    kwargs = dict(
        name=name,
        coords=inst.coords,
        demands=inst.demands,
        capacity=inst.capacity,
        num_vehicles=inst.num_vehicles,
    )
    kwargs.update(overrides)
    return Instance(**kwargs)


# 車両数や容量を変えた派生インスタンス。枝刈りの正しさが
# 元の2インスタンスにたまたま合っているだけ、という事故を防ぐ。
INSTANCES = [
    TOY5,
    TOY8,
    _variant(TOY5, "toy5-K3", num_vehicles=3),
    _variant(TOY5, "toy5-tight", capacity=27),   # 総需要 53 / 2台 = ぎりぎり収まる
    _variant(TOY8, "toy8-K4", num_vehicles=4),
    _variant(TOY8, "toy8-tight", capacity=30),
]
IDS = [i.name for i in INSTANCES]


class _Collector(BranchAndBound):
    """枝刈りなしで走らせ、到達した完成解とその積算コストを全部記録する。"""

    def __init__(self, inst: Instance):
        super().__init__(inst, prune=False)
        self.found: list[tuple[list[list[int]], int]] = []

    def _extend(self, remaining, routes, cost):
        if not remaining:
            self.found.append(([list(r) for r in routes], cost))
        super()._extend(remaining, routes, cost)


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_unpruned_search_tree_matches_reference(inst: Instance):
    """
    枝刈りを切れば、参照実装と同じ木を同じ回数だけ歩くはず。

    permutations() を捨てて1顧客ずつ組み立てる形に書き換えた部分が
    等価であることを、ノード数と解の集合の両方で確認する。
    """
    ref = solve_brute_force(inst)
    c = _Collector(inst)
    c.solve()

    assert c.nodes == ref.stats["nodes"]
    assert c.leaves == ref.stats["solutions"]
    assert {canonical(sol) for sol, _ in c.found} == {
        canonical(sol) for sol in enumerate_solutions(inst)
    }


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_incremental_cost_matches_full_recomputation(inst: Instance):
    """
    再帰で積算しているコストが、解を作り直して計算した値と一致すること。

    ルート構築を O(1)/1手 の差分計算に変えた部分は、ここがずれると
    枝刈りの判断ごと壊れる。最適コストの一致だけでは検出できない。
    """
    c = _Collector(inst)
    c.solve()
    for sol, cost in c.found:
        assert cost == solution_cost(inst, sol), f"積算コストがずれている: {sol}"


@pytest.mark.parametrize("ref", REFERENCES, ids=REFERENCE_IDS)
def test_reaches_reference_optimum(ref: Reference):
    res = solve_branch_and_bound(ref.inst)
    assert res.cost == ref.optimal_cost
    assert res.feasible
    assert res.solver == "bnb"
    assert res.stats["initial_upper_bound"] is None


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_finds_the_same_optimum_as_brute_force(inst: Instance):
    ref = solve_brute_force(inst)
    res = solve_branch_and_bound(inst)

    assert res.cost == ref.cost
    assert res.feasible
    assert solution_cost(inst, res.solution) == res.cost


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_pruning_reduces_the_search(inst: Instance):
    ref = solve_brute_force(inst)
    res = solve_branch_and_bound(inst)
    assert res.stats["nodes"] < ref.stats["nodes"]


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_incumbent_never_makes_the_search_larger(inst: Instance):
    """初期上界が厳しくなれば刈れる量は増えこそすれ減らない (探索順は同一なので)。"""
    # 最適ではないが実行可能な解として、全探索が最初に見つけた解を使う
    first = next(enumerate_solutions(inst))
    without = solve_branch_and_bound(inst)
    with_seed = solve_branch_and_bound(inst, incumbent=first)

    assert with_seed.cost == without.cost
    assert with_seed.stats["nodes"] <= without.stats["nodes"]
    assert with_seed.stats["initial_upper_bound"] == solution_cost(inst, first)
    assert with_seed.history[0].cost == solution_cost(inst, first)


def test_incumbent_equal_to_optimum_is_still_returned():
    """
    上界を数値ではなく暫定解として持つ理由の回帰テスト。

    初期解がちょうど最適と同コストのとき、>= 枝刈りは全ての解を捨てる。
    上界を数値でしか持っていないと best が None のまま探索が終わる。
    """
    opt = solve_brute_force(TOY5)
    res = solve_branch_and_bound(TOY5, incumbent=opt.solution)

    assert res.stats["leaves"] == 0, "同コスト解は全て刈られるので新しい解は見つからない"
    assert res.cost == opt.cost
    assert res.feasible


def test_infeasible_incumbent_is_rejected():
    """制約を破った解のコストで刈ると、真の最適解を落としうる。"""
    one_route = [TOY5.customers]   # 総需要 53 > 容量 30
    with pytest.raises(ValueError, match="incumbent"):
        solve_branch_and_bound(TOY5, incumbent=one_route)


def test_infeasible_instance_is_reported_not_silently_wrong():
    """
    容量 x 車両数 が総需要に届かないインスタンス。実行可能解は存在しない。

    空の解や不正な解を黙って返さないことを確認しておく。
    """
    impossible = _variant(TOY5, "toy5-impossible", capacity=22)   # 22 x 2 = 44 < 53
    assert impossible.capacity * impossible.num_vehicles < sum(impossible.demands)

    with pytest.raises(ValueError, match="実行可能解が存在しない"):
        solve_branch_and_bound(impossible)
