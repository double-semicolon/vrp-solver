"""
インスタンスと結果の JSON 入出力。

これがあると効く場面:
  - 重い探索を回し直さずに図を描き直せる (実行と作図の分離)
  - Step 3 で Java に移植したとき、両者の出力を同じ形式で diff できる
  - Step 5 の API のレスポンスがそのままこの形式になる

距離行列 D は coords から一意に決まるので保存しない。保存すると
「D と coords が食い違ったファイル」という不正な状態を作れてしまう。

inf / NaN は書かない。json の既定はこれらを Infinity / NaN と書くが、
JSON の規格外で他言語のパーサが読めない。null に置き換えることもしない。
置き換えると読み戻したときに元の値に戻らず、しかも黙って起きる。
非有限値は作った側 (ソルバ) の問題なので、保存の時点で例外にして気づかせる。
"""

from __future__ import annotations

import json
from pathlib import Path

from .model import Instance
from .result import Improvement, SolveResult

FORMAT = "vrp-run/1"   # 形式を変えたらここを上げる。Java 側の読み書きと揃える印


def dumps(payload: dict) -> str:
    """厳格な JSON にする。非有限値があれば ValueError。"""
    return json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


# ---------------------------------------------------------------- 変換


def instance_to_dict(inst: Instance) -> dict:
    return {
        "name": inst.name,
        "coords": [[x, y] for x, y in inst.coords],
        "demands": list(inst.demands),
        "capacity": inst.capacity,
        "num_vehicles": inst.num_vehicles,
    }


def instance_from_dict(d: dict) -> Instance:
    return Instance(
        name=d["name"],
        coords=[(float(x), float(y)) for x, y in d["coords"]],
        demands=[int(v) for v in d["demands"]],
        capacity=int(d["capacity"]),
        num_vehicles=int(d["num_vehicles"]),
    )


def result_to_dict(res: SolveResult) -> dict:
    return {
        "instance": res.instance,
        "solver": res.solver,
        "solution": [list(r) for r in res.solution],
        "cost": res.cost,
        "feasible": res.feasible,
        "elapsed_sec": res.elapsed_sec,
        "stats": dict(res.stats),
        "history": [
            {"elapsed_sec": h.elapsed_sec, "cost": h.cost, "iteration": h.iteration}
            for h in res.history
        ],
    }


def result_from_dict(d: dict) -> SolveResult:
    return SolveResult(
        instance=d["instance"],
        solver=d["solver"],
        solution=[list(r) for r in d["solution"]],
        cost=int(d["cost"]),
        feasible=bool(d["feasible"]),
        elapsed_sec=float(d["elapsed_sec"]),
        stats=dict(d["stats"]),
        history=[
            Improvement(float(h["elapsed_sec"]), int(h["cost"]), h["iteration"])
            for h in d["history"]
        ],
    )


# ---------------------------------------------------------------- ファイル


def save_run(path: str | Path, inst: Instance, results: list[SolveResult]) -> Path:
    """
    1インスタンス + そこで走らせた複数ソルバの結果を1ファイルにまとめる。

    この単位にしているのは、比較図が必要とする情報とちょうど一致するから。
    """
    # 書き始めてから失敗すると壊れたファイルが残るので、先に文字列にする
    text = dumps({
        "format": FORMAT,
        "instance": instance_to_dict(inst),
        "results": [result_to_dict(r) for r in results],
    })
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")
    return p


def _reject_constant(name: str):
    raise ValueError(f"JSON の規格外の値: {name}")


def loads(text: str) -> dict:
    """
    厳格に読む。json.loads は既定で Infinity / NaN を受け付けてしまうので、
    書く側だけでなく読む側でも弾く。他の実装 (Java 版) が書いたファイルも読むため。
    """
    return json.loads(text, parse_constant=_reject_constant)


def load_run(path: str | Path) -> tuple[Instance, list[SolveResult]]:
    d = loads(Path(path).read_text(encoding="utf-8"))
    if d.get("format") != FORMAT:
        raise ValueError(f"未知の形式: {d.get('format')!r} (期待: {FORMAT!r})")
    return instance_from_dict(d["instance"]), [result_from_dict(r) for r in d["results"]]
