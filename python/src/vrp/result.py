"""
ソルバの返り値の契約。

全てのソルバは SolveResult を返す。可視化・保存・比較はこの型だけを知っていれば
よく、ソルバが増えても下流は変わらない。ソルバ固有の数値 (探索ノード数、反復回数
など) は stats に逃がす。

history を最初から入れてあるのは ALNS のためだけではない。
「どのソルバも改善の履歴を同じ形で残す」と決めておかないと、収束を比べる
コードがソルバごとに必要になる。
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .model import Instance, Solution
from .objective import is_feasible, solution_cost


@dataclass
class Improvement:
    """暫定解が更新された瞬間の記録。"""

    elapsed_sec: float
    cost: int
    iteration: int | None = None   # 反復回数の概念を持つソルバだけが埋める


@dataclass
class SolveResult:
    instance: str                  # インスタンス名 (解そのものには含まれない情報)
    solver: str                    # ソルバの識別子。図の凡例やファイル名に使う
    solution: Solution
    cost: int
    feasible: bool
    elapsed_sec: float
    stats: dict = field(default_factory=dict)
    history: list[Improvement] = field(default_factory=list)

    @classmethod
    def build(
        cls,
        inst: Instance,
        solver: str,
        solution: Solution,
        elapsed_sec: float,
        stats: dict | None = None,
        history: list[Improvement] | None = None,
    ) -> SolveResult:
        """
        コストと実行可能性は必ず objective.py で計算し直す。

        ソルバが自分で積算した値をそのまま信じると、差分計算のバグが
        そのまま結果として通ってしまう。ここが最後の関門。
        """
        return cls(
            instance=inst.name,
            solver=solver,
            solution=[list(r) for r in solution],
            cost=solution_cost(inst, solution),
            feasible=is_feasible(inst, solution),
            elapsed_sec=elapsed_sec,
            stats=dict(stats or {}),
            history=list(history or []),
        )

    def gap_to(self, reference_cost: int) -> float:
        """基準コストからの相対差 (%)。基準を 0% とする。"""
        return (self.cost - reference_cost) / reference_cost * 100
