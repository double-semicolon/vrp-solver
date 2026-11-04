"""
作図。matplotlib はここでしか import しない。

ソルバや CLI のトップレベルから import しないこと。core を無依存に保つのは、
Step 3 で Java に移植する対象を「純粋な探索ロジックだけ」に限るため。
matplotlib は optional-dependencies の viz グループに入っている:
    uv sync --extra viz

全ての描画関数は ax を受け取り、渡されなければ自分で作る。こうしておくと
複数の解を並べる図が組み合わせで書ける。
"""

from __future__ import annotations

from pathlib import Path

try:
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    from matplotlib.axes import Axes
    from matplotlib.figure import Figure
except ModuleNotFoundError as e:  # pragma: no cover - 環境依存
    raise ModuleNotFoundError("作図には matplotlib が要る: uv sync --extra viz") from e

from .model import Instance, Solution
from .objective import route_cost, route_load
from .result import SolveResult

# 日本語ラベルが豆腐 (□) にならないよう、入っているゴシック体を1つ選ぶ。
# 見つからなければ既定のまま進む (英数字は問題なく出る)。
_JP_FONTS = ["Yu Gothic", "Meiryo", "BIZ UDGothic", "MS Gothic",
             "Noto Sans CJK JP", "IPAGothic", "Hiragino Sans"]


def _use_japanese_font() -> None:
    available = {f.name for f in font_manager.fontManager.ttflist}
    for name in _JP_FONTS:
        if name in available:
            plt.rcParams["font.family"] = name
            plt.rcParams["axes.unicode_minus"] = False   # 全角フォントだと − が化ける
            return


_use_japanese_font()

# 黄金比。連続するルートの色相を大きく離すために使う。
_GOLDEN = 0.6180339887498949


def _colors(k: int) -> list:
    """
    k 本のルート (または系列) に全て異なる色を割り当てる。

    tab10 / tab20 は見やすいが 10色 / 20色しか無く、それを超えると色が回って
    別のルートと区別できなくなる。それ以上は hsv の色相を黄金比ずつずらす。
    """
    if k <= 10:
        return [plt.get_cmap("tab10")(i) for i in range(k)]
    if k <= 20:
        return [plt.get_cmap("tab20")(i) for i in range(k)]
    cmap = plt.get_cmap("hsv")
    return [cmap((i * _GOLDEN) % 1.0) for i in range(k)]


def _new_ax(figsize: tuple[float, float]) -> Axes:
    _, ax = plt.subplots(figsize=figsize)
    return ax


# ---------------------------------------------------------------- ルート図


def plot_solution(
    inst: Instance,
    sol: Solution,
    ax: Axes | None = None,
    title: str | None = None,
    annotate: bool | None = None,
    legend: bool | None = None,
) -> Axes:
    """解を描く。ルートごとに色を変え、depot との往復も線で結ぶ。点の大きさは需要。"""
    ax = ax or _new_ax((6, 6))
    if annotate is None:
        annotate = inst.n <= 60          # 60件を超えると番号が重なって読めない
    if legend is None:
        legend = len(sol) <= 10          # ルートが多いと凡例が図を潰す

    depot = inst.coords[0]
    for i, (r, color) in enumerate(zip(sol, _colors(len(sol)))):
        pts = [depot] + [inst.coords[c] for c in r] + [depot]
        ax.plot([p[0] for p in pts], [p[1] for p in pts], "-", color=color, lw=1.4,
                alpha=0.9, zorder=2,
                label=f"車{i}: 積載 {route_load(inst, r)}/{inst.capacity}"
                      f", 距離 {route_cost(inst, r)}")
        ax.scatter([inst.coords[c][0] for c in r], [inst.coords[c][1] for c in r],
                   s=[20 + 6 * inst.demands[c] for c in r], color=color,
                   zorder=3, edgecolors="white")

    ax.scatter(*depot, marker="s", s=140, c="#d62728", zorder=5)

    if annotate:
        for i, (x, y) in enumerate(inst.coords[1:], start=1):
            ax.annotate(str(i), (x, y), fontsize=7, ha="center", va="center", zorder=6)

    ax.set_aspect("equal")
    ax.set_title(title or inst.name)
    ax.grid(alpha=0.25)
    if legend:
        ax.legend(fontsize=7, loc="best", framealpha=0.85)
    return ax


def plot_result(inst: Instance, res: SolveResult, ax: Axes | None = None, **kw) -> Axes:
    """SolveResult をそのまま描く。タイトルにコストと実行可能性を入れる。"""
    flag = "  [実行不可能]" if not res.feasible else ""
    title = f"{res.solver}: 距離 {res.cost}  ({res.elapsed_sec:.3f} 秒){flag}"
    return plot_solution(inst, res.solution, ax=ax, title=title, **kw)


# ---------------------------------------------------------------- 収束


def plot_convergence(results: list[SolveResult], ax: Axes | None = None) -> Axes:
    """
    暫定解の推移を、横軸を経過時間にして描く。

    履歴を持たないソルバ (最近傍法など一発で終わるもの) は点1つで描く。
    ここで抜けると「比較図に出てこないソルバがある」ことになって紛らわしい。

    横軸は時間に揃える。反復回数はソルバごとに意味が違い (2-opt の move 数と
    B&B のノード数は比べられない)、同じ軸に載せると誤解を招く。
    """
    ax = ax or _new_ax((7, 4.5))

    for res, color in zip(results, _colors(len(results))):
        if not res.history:
            ax.plot([res.elapsed_sec], [res.cost], "o", color=color, label=res.solver)
            continue
        xs = [h.elapsed_sec for h in res.history]
        ys = [h.cost for h in res.history]
        # 暫定解は次の改善まで一定なので階段で描く。折れ線だと
        # 「その間ずっと改善していた」ように見えてしまう。
        ax.plot(xs, ys, drawstyle="steps-post", color=color, marker="o",
                ms=3, lw=1.4, label=res.solver)

    ax.set_xlabel("経過時間 (秒)")
    ax.set_ylabel("暫定解の総距離")
    ax.set_title("収束の比較")
    ax.grid(alpha=0.25)
    ax.legend(fontsize=8)
    return ax


# ---------------------------------------------------------------- 並べる


def plot_comparison(inst: Instance, results: list[SolveResult], ncols: int = 2) -> Figure:
    """ソルバごとのルート図をグリッドに並べ、最後に収束曲線を1枚足す。"""
    if not results:
        raise ValueError("描く結果が無い")
    n_panels = len(results) + 1
    ncols = max(1, min(ncols, n_panels))
    nrows = -(-n_panels // ncols)          # 切り上げ

    fig, axes = plt.subplots(nrows, ncols, figsize=(6 * ncols, 5.6 * nrows), squeeze=False)
    flat = [a for row in axes for a in row]

    feasible = [r.cost for r in results if r.feasible]
    best = min(feasible) if feasible else None
    for ax, res in zip(flat, results):
        plot_result(inst, res, ax=ax)
        if best is not None and res.feasible and res.cost != best:
            ax.set_title(ax.get_title() + f"  最良比 {res.gap_to(best):+.2f}%")

    plot_convergence(results, ax=flat[len(results)])
    for ax in flat[n_panels:]:
        ax.axis("off")                     # 余ったマスは枠ごと消す

    K = inst.num_vehicles
    vehicles = "上限なし" if K is None else f"{K} 台"
    fig.suptitle(f"{inst.name}  顧客 {inst.n} 件 / 容量 {inst.capacity} / 車両 {vehicles}",
                 fontsize=13)
    fig.tight_layout(rect=(0, 0, 1, 0.97))   # suptitle の場所を空ける
    return fig


def save(fig: Figure, path: str | Path, dpi: int = 130) -> Path:
    """PNG に書き出して Figure を閉じる。閉じないと開いたままメモリに残り続ける。"""
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(p, dpi=dpi, bbox_inches="tight")
    plt.close(fig)
    return p
