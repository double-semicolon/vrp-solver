import pytest

from vrp.model import Instance
from vrp.objective import is_feasible, route_cost, route_load, solution_cost, validate


@pytest.fixture
def inst() -> Instance:
    # depot を原点に、顧客を正方形の頂点に置く。距離は手で数えられる。
    #
    #   2 ---- 3        (0,4) ---- (4,4)
    #   |      |
    #   0 ---- 1        (0,0) ---- (4,0)
    #
    # 0 と 3 は対角 sqrt(32) = 5.66 -> 6
    return Instance(
        name="square",
        coords=[(0, 0), (4, 0), (0, 4), (4, 4)],
        demands=[0, 3, 3, 4],
        capacity=7,
        num_vehicles=2,
    )


def test_route_cost_includes_depot_both_ends(inst):
    assert route_cost(inst, [1]) == 4 + 4
    assert route_cost(inst, [1, 3, 2]) == 4 + 4 + 4 + 4
    assert route_cost(inst, [3]) == 6 + 6


def test_empty_route_costs_nothing(inst):
    assert route_cost(inst, []) == 0


def test_route_direction_does_not_change_cost(inst):
    assert route_cost(inst, [1, 3]) == route_cost(inst, [3, 1])


def test_route_load(inst):
    assert route_load(inst, [1, 3]) == 7
    assert route_load(inst, []) == 0


def test_solution_cost_sums_routes(inst):
    sol = [[1, 3], [2]]
    assert solution_cost(inst, sol) == (4 + 4 + 6) + (4 + 4)


def test_feasible_solution(inst):
    sol = [[1, 3], [2]]
    assert validate(inst, sol) == []
    assert is_feasible(inst, sol)


@pytest.mark.parametrize(
    "sol, expected",
    [
        ([[1, 2, 3]], "容量超過"),
        ([[1], [3]], "顧客 2 が未訪問"),
        ([[1, 3], [2, 1]], "顧客 1 を 2 回訪問"),
        ([[1], [2], [3]], "車両数超過"),
        ([[1, 3], [2, 0]], "depot(0)"),
        ([[1, 3], [2, 9]], "存在しない顧客 9"),
        ([[1, 3], [2, -1]], "存在しない顧客 -1"),
    ],
)
def test_infeasible_solution_is_reported(inst, sol, expected):
    errors = validate(inst, sol)
    assert any(expected in e for e in errors), errors
    assert not is_feasible(inst, sol)


def test_unknown_customer_does_not_count_toward_load(inst):
    # ルート [2, 3] の積載は 7 で容量ちょうど。
    # -1 を demands[-1] (= 顧客 3 の需要 4) として数えると 11 になり、容量超過と誤判定する
    errors = validate(inst, [[1], [2, 3, -1]])
    assert not any("容量超過" in e for e in errors), errors


def test_unlimited_vehicles_skips_the_count_check(inst):
    """上限なしなら、1顧客1台の3台使用も実行可能。上限 2 台なら違反になる (対照)。"""
    one_each = [[1], [2], [3]]
    assert any("車両数超過" in e for e in validate(inst, one_each))

    unlimited = Instance(name="square-unlimited", coords=inst.coords, demands=inst.demands,
                         capacity=inst.capacity, num_vehicles=None)
    assert validate(unlimited, one_each) == []
