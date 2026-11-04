"""
Step 1b: 2-opt によるルート内の局所探索。

ルートの中の辺 2 本を張り替える (= 途中の区間を逆走する) 近傍。

    変更前:  ... a -> b ...... c -> d ...
    変更後:  ... a -> c ...... b -> d ...     (b..c の区間を逆順に辿る)

距離が対称なので、逆走させた区間の内部の距離は変わらない。変わるのは
境界の 2 本だけ。差分計算が O(1) で済むのはこのため。

この近傍はルートをまたがない。顧客がどのルートに属するかは構築法が
決めたままで、割り当てが悪ければその損は残る。ルート間の改善 (relocate /
2-opt*) は Step 4 の担当。Step 1 でどこまで届くかを測っておくことが、
その必要性を数字で示す材料になる。

ルート内の顧客の集合が変わらないので、積載量もルート数も変わらない。
初期解が容量制約を満たしていれば満たしたままで、探索の内側に容量チェックは
要らない。逆に、初期解が K 台を超えていたら超えたまま出てくる。
"""

from __future__ import annotations

import time
from collections.abc import Callable

from ..model import Instance, Route, Solution
from ..objective import solution_cost
from ..result import Improvement, SolveResult
from .nearest_neighbor import nearest_neighbor

# ---------------------------------------------------------------- 近傍


def gain(D: list[list[int]], route: Route, i: int, j: int) -> int:
    """
    route[i:j+1] を逆順にしたときに縮む距離を返す。正なら改善。

    Route は depot を含まない (前後に 0 が暗黙で付く) ので、端では depot を補う:

        番地:    i-1     i             j     j+1
        訪問:  ... a  ->  b  ......  c  ->  d ...

    消える辺は (a,b) と (c,d)、生まれる辺は (a,c) と (b,d)。
    """
    a = route[i - 1] if i > 0 else 0
    b = route[i]
    c = route[j]
    d = route[j + 1] if j < len(route) - 1 else 0
    return D[a][b] + D[c][d] - D[a][c] - D[b][d]


def improve_once(D: list[list[int]], route: Route) -> int:
    """
    最も縮む move を 1 つ route に適用し、縮んだ距離を返す (改善が無ければ 0)。

    best improvement (全ペアを見てから最良を適用) にしている。first improvement
    (最初に見つけた改善を即適用) と比べ、顧客 50〜200 件のランダムインスタンスで
    改善率が平均 0.2〜0.5 ポイント高く、時間は 1.2〜1.4 倍。どちらもミリ秒単位なので
    Step 1 では質を取る。近傍リストで速度が効いてくる Step 4 で改めて選ぶ。

    i == 0 かつ j == m-1 はルート全体の逆走で、対称距離では gain がちょうど 0 になる。
    改善候補に上がらないので弾く必要は無い。

    同点 (縮む距離が同じ move が複数) のときは、i が最小のもの、その中で j が最小のものを
    選ぶ。i, j の昇順に見て、真に大きいときだけ更新しているのでこうなる。Java 版
    (TwoOpt.java) も同じ決め方にしてあり、解が完全に一致することを突き合わせている。
    >= に変えると最後に見つけた同点の move を選ぶようになり、解が変わる。
    """
    m = len(route)
    best_gain, best_ij = 0, None
    for i in range(m - 1):
        for j in range(i + 1, m):
            g = gain(D, route, i, j)
            if g > best_gain:
                best_gain, best_ij = g, (i, j)
    if best_ij is not None:
        i, j = best_ij
        route[i:j + 1] = reversed(route[i:j + 1])
    return best_gain


# ---------------------------------------------------------------- 局所探索


def two_opt(
    inst: Instance,
    sol: Solution,
    on_improve: Callable[[int, int], None] | None = None,
) -> Solution:
    """
    全ルートを、どの move も改善しなくなるまで 2-opt で磨く。

    引数の解は壊さず、新しい Solution を返す。呼び出し側が初期解と
    比べられるようにするため。

    on_improve(applied, cost) は move を 1 つ適用するたびに呼ばれる。
    ここは経過時間を知らない。時刻を打つのはソルバの仕事。

    cost は差分の積み上げなので、そのままでは信用できない値。最終的なコストは
    SolveResult.build が計算し直す。両者が一致することはテストで確かめる。
    """
    D = inst.D
    out = [list(r) for r in sol]
    cost = solution_cost(inst, out)
    applied = 0

    for route in out:
        # 改善を 1 つ適用するたびに全ペアを見直す。無駄な再走査を削る
        # don't-look bits は Step 4。
        while (g := improve_once(D, route)) > 0:
            cost -= g
            applied += 1
            if on_improve is not None:
                on_improve(applied, cost)

    return out


def solve_nn_2opt(inst: Instance) -> SolveResult:
    """最近傍法で作った解を 2-opt で磨く。Step 1 の成果物。"""
    t0 = time.perf_counter()

    sol = nearest_neighbor(inst)
    # iteration は適用した move の通し番号。0 番は磨く前の初期解。
    # これが無いと収束曲線が改善後から始まって、出発点が図に出ない。
    history = [Improvement(time.perf_counter() - t0, solution_cost(inst, sol), iteration=0)]

    def record(applied: int, cost: int) -> None:
        history.append(Improvement(time.perf_counter() - t0, cost, iteration=applied))

    sol = two_opt(inst, sol, on_improve=record)
    elapsed = time.perf_counter() - t0

    return SolveResult.build(
        inst, "nn+2opt", sol, elapsed,
        stats={"routes": len(sol), "moves": history[-1].iteration},
        history=history,
    )
