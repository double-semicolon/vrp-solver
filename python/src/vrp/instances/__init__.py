"""インスタンスの供給元。手書きの toy、シード固定のランダム生成、CVRPLib のファイル。"""

from __future__ import annotations

from .cvrplib import load_sol, load_vrp
from .generate import random_instance
from .toy import TOY5, TOY8, TOYS

__all__ = ["TOY5", "TOY8", "TOYS", "load_sol", "load_vrp", "random_instance"]
