"""
作図のテスト。

画像そのものは比較しない。フォントや matplotlib のバージョンで容易に変わるので、
スナップショット比較はフレークの温床になる。ここで縛るのは「落ちずに描ける」と
「描かれた要素の数が解と対応している」の2つ。見た目の良し悪しは目で確かめるもの。
"""

from __future__ import annotations

import pytest

matplotlib = pytest.importorskip("matplotlib")
matplotlib.use("Agg")   # 画面のない環境で描くため。pyplot を読み込む前に呼ぶ

import matplotlib.pyplot as plt  # noqa: E402

from vrp import viz  # noqa: E402
from vrp.instances import TOY5, TOY8, random_instance  # noqa: E402
from vrp.result import SolveResult  # noqa: E402
from vrp.solvers import SOLVERS, solve_nearest_neighbor, solve_nn_2opt  # noqa: E402

INSTANCES = [TOY5, TOY8, random_instance(50, seed=1), random_instance(50, seed=1, layout="clustered")]
IDS = [i.name for i in INSTANCES]


@pytest.fixture(autouse=True)
def close_figures():
    """作った Figure を必ず閉じる。閉じないと大量に開いて matplotlib が警告を出す。"""
    yield
    plt.close("all")


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_plot_solution_draws_one_line_per_route(inst):
    sol = solve_nn_2opt(inst).solution
    ax = viz.plot_solution(inst, sol)
    assert len(ax.get_lines()) == len(sol)


@pytest.mark.parametrize("inst", INSTANCES, ids=IDS)
def test_each_route_line_starts_and_ends_at_the_depot(inst):
    """depot との往復が線に含まれていること。抜けるとルートが途中で切れて見える。"""
    sol = solve_nn_2opt(inst).solution
    ax = viz.plot_solution(inst, sol)
    for line, route in zip(ax.get_lines(), sol):
        xs, ys = line.get_data()
        assert (xs[0], ys[0]) == (xs[-1], ys[-1]) == inst.coords[0]
        assert len(xs) == len(route) + 2


def test_plot_result_puts_the_cost_in_the_title():
    res = solve_nearest_neighbor(TOY8)
    ax = viz.plot_result(TOY8, res)
    assert str(res.cost) in ax.get_title()
    assert "実行不可能" not in ax.get_title()


def test_infeasible_result_is_marked_in_the_title():
    res = SolveResult.build(TOY5, "dummy", [[1, 2], [3], [4, 5]], elapsed_sec=0.0)
    assert not res.feasible
    assert "実行不可能" in viz.plot_result(TOY5, res).get_title()


def test_convergence_draws_one_series_per_result_even_without_history():
    """
    履歴を持たないソルバ (最近傍法) も点1つで描かれること。
    抜けると「比較図に出てこないソルバがある」ことになって紛らわしい。
    """
    results = [solve_nearest_neighbor(TOY8), solve_nn_2opt(TOY8)]
    assert not results[0].history and len(results[1].history) == 2
    ax = viz.plot_convergence(results)
    lines = ax.get_lines()
    assert [line.get_label() for line in lines] == ["nn", "nn+2opt"]
    assert len(lines[0].get_xdata()) == 1
    assert list(lines[1].get_ydata()) == [318, 303]


def test_plot_comparison_has_one_panel_per_solver_plus_the_convergence_panel():
    results = [spec.run(TOY8) for spec in SOLVERS.values()]
    fig = viz.plot_comparison(TOY8, results)
    visible = [ax for ax in fig.axes if ax.axison]
    assert len(visible) == len(results) + 1


def test_plot_comparison_survives_a_single_result():
    fig = viz.plot_comparison(TOY5, [solve_nearest_neighbor(TOY5)])
    assert len([ax for ax in fig.axes if ax.axison]) == 2


def test_plot_comparison_rejects_no_results():
    with pytest.raises(ValueError):
        viz.plot_comparison(TOY5, [])


def test_save_creates_the_parent_directory_and_closes_the_figure(tmp_path):
    fig = viz.plot_comparison(TOY5, [solve_nearest_neighbor(TOY5)])
    path = viz.save(fig, tmp_path / "a" / "b.png")
    assert path.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"
    assert not plt.fignum_exists(fig.number)


@pytest.mark.parametrize("k", [3, 10, 15, 20, 40])
def test_route_colors_never_repeat(k):
    """tab10/tab20 の色数を超えても、全てのルートが別の色になること。"""
    colors = viz._colors(k)
    assert len(colors) == k
    assert len({tuple(c) for c in colors}) == k
