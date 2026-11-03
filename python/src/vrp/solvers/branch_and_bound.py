"""
Step 0b: 全探索にコスト下界による枝刈りを入れる (branch and bound)。

bruteforce.enumerate_solutions が「重複なく全部を数える」ための参照実装なのに対し、
こちらは「最適解を1つ見つける」ための探索。同じ木を歩くが、答えに関係ない
部分木を切り落とす。両者が同じ最適コストに到達することをテストで縛っている。

なぜ列挙器 (ジェネレータ) のままでは書けないか:
  枝刈りには暫定最適 best_cost が要る。しかし best_cost を更新するのは
  呼び出し側で、ジェネレータ側の引数名を書き換える手段はない
  (Python の代入は名前の再束縛であって、呼び出し側には伝播しない)。
  ここでは再帰が自分で self.best_cost を更新するので、
  フィードバックが再帰の内側で閉じ、この問題自体が消える。
"""

from __future__ import annotations

import math
import time
from itertools import combinations

from ..model import Instance, Route, Solution
from ..objective import is_feasible, solution_cost
from ..result import Improvement, SolveResult


class BranchAndBound:
    """
    コスト下界による枝刈り付きの深さ優先探索。

    列挙の骨格は enumerate_solutions と同一:
      「未割当のうち最小の顧客 m」を必ず今作るルートに入れる、と決めて再帰する。
      これでルートの並べ替え (k! 通り) の重複が消え、ルート内の順序は
      先頭 <= 末尾 のものだけ生成して逆走 (2^k 通り) の重複を消す。
      顧客は常に番号の昇順に試すので、探索順も enumerate_solutions と同じになる。

    枝刈りの根拠:
      距離が非負なので、確定済みルートの合計コストが暫定最適以上なら、
      その先にどんな解があっても暫定最適を下回れない。よって部分木ごと捨ててよい。
      同コストの解も捨てる (>= で刈る) ので、最適解が複数あっても1つしか返らない。

    上界は数値ではなく「暫定解 (incumbent)」として持つ。数値だけ渡すと、
    その値がたまたま最適コストと等しいときに >= 枝刈りが全ての解を捨て、
    上界を達成する解を1つも持たないまま探索が終わってしまう。
    解ごと持っていれば、探索が何も見つけなくても incumbent が答えになる。

    prune=False にすると枝刈りを止めて全列挙になる。探索木が
    enumerate_solutions と同一であることをテストで確認するために使う。
    """

    def __init__(self, inst: Instance, incumbent: Solution | None = None, prune: bool = True):
        self.inst = inst
        self.prune = prune
        self.best: Solution | None = None if incumbent is None else [list(r) for r in incumbent]
        self.best_cost: float = math.inf if incumbent is None else solution_cost(inst, incumbent)
        # 統計。nodes は _extend の呼び出し回数で、bruteforce の stats["nodes"] と同じ定義。
        self.nodes = 0          # 訪問したノード数 (刈られたノードも入った時点で1と数える)
        self.pruned = 0         # 入った直後にコスト超過で捨てたノード数
        self.leaves = 0         # 到達した完成解の数 (枝刈りするので実行可能解の総数ではない)
        self.route_prunes = 0   # ルートを組み立てる途中で捨てた部分順序の数
        # 暫定解が更新された瞬間の記録。収束の速さを他のソルバと同じ形で比べるため。
        self.history: list[Improvement] = []
        self._t0 = 0.0

    def solve(self) -> tuple[Solution, int, float, dict]:
        """(最適解, 最適コスト, 経過秒, 統計) を返す。実行可能解が無ければ ValueError。"""
        self._t0 = time.perf_counter()
        if self.best is not None:
            # 初期上界も「時刻0の暫定解」として履歴に載せる。
            # これが無いと、初期解を与えた探索の収束曲線が中空から始まる。
            self.history.append(Improvement(0.0, int(self.best_cost)))

        self._extend(frozenset(self.inst.customers), [], 0)
        if self.best is None:
            raise ValueError(f"{self.inst.name}: 実行可能解が存在しない")
        stats = {
            "nodes": self.nodes,
            "pruned": self.pruned,
            "route_prunes": self.route_prunes,
            "leaves": self.leaves,
        }
        return self.best, int(self.best_cost), time.perf_counter() - self._t0, stats

    # ------------------------------------------------------------ 再帰

    def _extend(self, remaining: frozenset[int], routes: Solution, cost: int) -> None:
        """remaining を routes に割り当て終えるまで再帰する。cost は確定済みルートの合計。"""
        self.nodes += 1

        if self.prune and cost >= self.best_cost:
            self.pruned += 1
            return

        if not remaining:
            # 冒頭の枝刈りを通過している = cost < best_cost は既に保証されている
            self.leaves += 1
            self.best_cost = cost
            self.best = [list(r) for r in routes]
            self.history.append(
                Improvement(time.perf_counter() - self._t0, cost, iteration=self.nodes)
            )
            return

        if len(routes) == self.inst.num_vehicles:
            return                    # 車が尽きた。行き止まり

        demands, Q = self.inst.demands, self.inst.capacity
        D = self.inst.D
        m = min(remaining)            # m は必ず「今作るルート」に入れる
        rest = sorted(remaining - {m})

        for r in range(len(rest) + 1):
            for others in combinations(rest, r):
                S = frozenset(others) | {m}
                if sum(demands[c] for c in S) > Q:
                    continue
                # S の並べ替えは permutations() で作らず、1顧客ずつ伸ばしながら列挙する。
                # 1手あたり D[last][c] を足すだけ (O(1)) で済み、route_cost() の
                # O(k) 再走査が k! 回分まるごと消える。途中コストでも刈れるようになる。
                for first in sorted(S):
                    rcost = D[0][first]
                    if self.prune and cost + rcost >= self.best_cost:
                        self.route_prunes += 1
                        continue
                    self._build_route(remaining, routes, cost, [first], S - {first}, rcost)

    def _build_route(
        self,
        remaining: frozenset[int],
        routes: Solution,
        base_cost: int,
        route: Route,
        left: frozenset[int],
        rcost: int,
    ) -> None:
        """
        1本のルートを1顧客ずつ伸ばして S の並べ替えを列挙する。

        rcost は depot -> route の距離で、最後の depot への復路はまだ足していない。
        復路は非負なので、rcost の時点で暫定最適を超えていればそこで捨ててよい。
        """
        D = self.inst.D

        if not left:
            total = base_cost + rcost + D[route[-1]][0]
            self._extend(remaining - set(route), routes + [route], total)
            return

        last = route[-1]
        final = len(left) == 1        # 次に置く顧客がこのルートの末尾になる
        for c in sorted(left):
            if final and c < route[0]:
                continue              # 逆走の重複を潰す (末尾 >= 先頭 のものだけ残す)
            step = rcost + D[last][c]
            if self.prune and base_cost + step >= self.best_cost:
                self.route_prunes += 1
                continue
            self._build_route(remaining, routes, base_cost, route + [c], left - {c}, step)


# ---------------------------------------------------------------- 入口

def solve_branch_and_bound(inst: Instance, incumbent: Solution | None = None) -> SolveResult:
    """
    枝刈り付き全探索で最適解を求める。

    incumbent に実行可能解を渡すと、そのコストを初期上界にして最初のノードから刈れる。
    実行不可能な解のコストは上界として使えない (制約を破って安くなった値で刈ると
    真の最適解を落とす) ので、渡された解が実行不可能なら ValueError にする。
    黙って捨てると、呼び出し側は上界が効いていると思い込んだまま遅い探索をする。
    """
    if incumbent is not None and not is_feasible(inst, incumbent):
        raise ValueError("incumbent が実行可能解ではない")

    bnb = BranchAndBound(inst, incumbent)
    initial_upper_bound = bnb.best_cost
    best, _cost, elapsed, stats = bnb.solve()
    # 上界なしは inf ではなく None で表す。inf は JSON の規格外の値になるので、
    # 非可逆な値を最初から作らない。
    stats["initial_upper_bound"] = (
        None if math.isinf(initial_upper_bound) else int(initial_upper_bound)
    )
    return SolveResult.build(inst, "bnb", best, elapsed, stats, bnb.history)
