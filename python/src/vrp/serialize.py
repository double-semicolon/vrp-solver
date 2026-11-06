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

読むときは Java 版 (java/json の VrpJson) と同じ規則で厳格に読む。片方だけ緩いと
「Python では読めるが Java では読めないファイル」ができ、突き合わせの意味が無くなる。
  - 知らないキーも、欠けたキーも、同じキーの重複もエラー
  - 整数の欄は JSON の整数だけ (3.7 や 3.0、true、"5" は拒否)。cost・iteration・stats は
    64 bit、それ以外 (需要・容量・台数・顧客番号) は 32 bit に収まること (Java の long と int)
  - 小数の欄は整数も受け付ける (座標 [50, 50] など)
  - null を許すのは num_vehicles・history の iteration・stats の値だけ
  - stats の値は null・真偽値・整数・小数・文字列、または数の配列 (1 段だけ。要素に null・
    真偽値・文字列・配列・オブジェクトは置けない。ALNS の重みの推移などに使う)
"""

from __future__ import annotations

import json
import math
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


def instance_from_dict(d: dict, where: str = "instance") -> Instance:
    _keys(d, where, "name", "coords", "demands", "capacity", "num_vehicles")
    coords = []
    for i, xy in enumerate(_list(d["coords"], f"{where}.coords")):
        at = f"{where}.coords[{i}]"
        if len(_list(xy, at)) != 2:
            raise ValueError(f"{at}: [x, y] の 2 要素でない: {xy!r}")
        coords.append((_float(xy[0], at), _float(xy[1], at)))
    nv = d["num_vehicles"]
    return Instance(
        name=_str(d["name"], f"{where}.name"),
        coords=coords,
        demands=[_int(v, f"{where}.demands[{i}]")
                 for i, v in enumerate(_list(d["demands"], f"{where}.demands"))],
        capacity=_int(d["capacity"], f"{where}.capacity"),
        # null は上限なし
        num_vehicles=None if nv is None else _int(nv, f"{where}.num_vehicles"),
    )


def result_to_dict(res: SolveResult) -> dict:
    for k, v in res.stats.items():
        _stat(v, f"stats.{k}")   # 読めないファイルを書かない
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


def result_from_dict(d: dict, where: str = "result") -> SolveResult:
    _keys(d, where, "instance", "solver", "solution", "cost", "feasible", "elapsed_sec",
          "stats", "history")
    solution = []
    for i, r in enumerate(_list(d["solution"], f"{where}.solution")):
        at = f"{where}.solution[{i}]"
        solution.append([_int(c, f"{at}[{j}]") for j, c in enumerate(_list(r, at))])
    stats = d["stats"]
    if not isinstance(stats, dict):
        raise ValueError(f"{where}.stats: オブジェクトでない")
    history = []
    for i, h in enumerate(_list(d["history"], f"{where}.history")):
        at = f"{where}.history[{i}]"
        _keys(h, at, "elapsed_sec", "cost", "iteration")
        it = h["iteration"]
        history.append(Improvement(_float(h["elapsed_sec"], f"{at}.elapsed_sec"),
                                   _int(h["cost"], f"{at}.cost", 64),
                                   None if it is None else _int(it, f"{at}.iteration", 64)))
    return SolveResult(
        instance=_str(d["instance"], f"{where}.instance"),
        solver=_str(d["solver"], f"{where}.solver"),
        solution=solution,
        cost=_int(d["cost"], f"{where}.cost", 64),
        feasible=_bool(d["feasible"], f"{where}.feasible"),
        elapsed_sec=_float(d["elapsed_sec"], f"{where}.elapsed_sec"),
        stats={k: _stat(v, f"{where}.stats.{k}") for k, v in stats.items()},
        history=history,
    )


# ---------------------------------------------------------------- 型の検査
#
# int(v) や float(v) で変換すると、3.7 -> 3、"5" -> 5、True -> 1 が黙って通る。
# 変換はせず、JSON の型そのものを確かめる。bool は int の派生型なので先に弾く。

def _keys(d: object, where: str, *keys: str) -> None:
    if not isinstance(d, dict):
        raise ValueError(f"{where}: オブジェクトでない")
    missing = [k for k in keys if k not in d]
    unknown = [k for k in d if k not in keys]
    if missing:
        raise ValueError(f"{where}: キーが無い: {missing}")
    if unknown:
        raise ValueError(f"{where}: 知らないキー: {unknown}")


def _int(v: object, where: str, bits: int = 32) -> int:
    if isinstance(v, bool) or not isinstance(v, int):
        raise ValueError(f"{where}: 整数でない: {v!r}")
    if not -(2 ** (bits - 1)) <= v < 2 ** (bits - 1):
        raise ValueError(f"{where}: {bits} bit に収まらない: {v}")
    return v


def _float(v: object, where: str) -> float:
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        raise ValueError(f"{where}: 数でない: {v!r}")
    try:
        x = float(v)   # 桁の大きすぎる整数は OverflowError になる
    except OverflowError:
        x = math.inf
    # 1e400 は Infinity と書かなくても、読むと無限大になる
    if not math.isfinite(x):
        raise ValueError(f"{where}: 有限の数でない: {v!r}")
    return x


def _str(v: object, where: str) -> str:
    if not isinstance(v, str):
        raise ValueError(f"{where}: 文字列でない: {v!r}")
    return v


def _bool(v: object, where: str) -> bool:
    if not isinstance(v, bool):
        raise ValueError(f"{where}: 真偽値でない: {v!r}")
    return v


def _list(v: object, where: str) -> list:
    if not isinstance(v, list):
        raise ValueError(f"{where}: 配列でない: {v!r}")
    return v


def _stat(v: object, where: str) -> object:
    if v is None or isinstance(v, (bool, str)):
        return v
    if isinstance(v, int):
        return _int(v, where, 64)
    if isinstance(v, float):
        return _float(v, where)
    if isinstance(v, list):
        out = []
        for i, e in enumerate(v):
            at = f"{where}[{i}]"
            if isinstance(e, bool) or not isinstance(e, (int, float)):
                raise ValueError(f"{at}: stats の配列の要素は数だけ: {e!r}")
            out.append(_int(e, at, 64) if isinstance(e, int) else _float(e, at))
        return out
    raise ValueError(f"{where}: stats の値は null・真偽値・数・文字列・数の配列のどれか: {v!r}")


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
    # 改行は OS によらず LF。Windows の既定 (CRLF) にすると Java 版の出力と揃わない
    p.write_text(text, encoding="utf-8", newline="\n")
    return p


def _reject_constant(name: str):
    raise ValueError(f"JSON の規格外の値: {name}")


def _reject_duplicates(pairs: list[tuple[str, object]]) -> dict:
    d = dict(pairs)
    if len(d) != len(pairs):
        seen: set[str] = set()
        dup = next(k for k, _ in pairs if k in seen or seen.add(k))
        raise ValueError(f"同じキーが2回ある: {dup!r}")
    return d


def loads(text: str) -> dict:
    """
    厳格に読む。json.loads は既定で Infinity / NaN を受け付け、重複したキーは
    後の値で黙って上書きする。書く側だけでなく読む側でも弾く。
    他の実装 (Java 版) が書いたファイルも読むため。
    """
    return json.loads(text, parse_constant=_reject_constant, object_pairs_hook=_reject_duplicates)


def load_run(path: str | Path) -> tuple[Instance, list[SolveResult]]:
    path = Path(path)
    try:
        d = loads(path.read_text(encoding="utf-8"))
        if not isinstance(d, dict) or d.get("format") != FORMAT:
            got = d.get("format") if isinstance(d, dict) else d
            raise ValueError(f"未知の形式: {got!r} (期待: {FORMAT!r})")
        _keys(d, "run", "format", "instance", "results")
        return instance_from_dict(d["instance"]), [
            result_from_dict(r, f"results[{i}]")
            for i, r in enumerate(_list(d["results"], "results"))
        ]
    except ValueError as e:   # json.JSONDecodeError も ValueError の派生
        raise ValueError(f"{path.name}: {e}") from None
