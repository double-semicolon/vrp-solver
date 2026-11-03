"""インスタンスの供給元。手書きの toy と、シード固定のランダム生成。"""

from __future__ import annotations

from .generate import random_instance
from .toy import TOY5, TOY8, TOYS

__all__ = ["TOY5", "TOY8", "TOYS", "random_instance"]
