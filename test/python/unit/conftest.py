"""pytest bootstrap for the platformcore unit tests: put src/python on sys.path so
`from platformcore import ...` resolves without an install (mirrors netctl's convention)."""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "src" / "python"))
