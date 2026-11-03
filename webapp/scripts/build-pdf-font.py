"""Build the bundled, renamed Noto Sans derivative (fontTools required).

Source: google/fonts ofl/notosans/NotoSans[wdth,wght].ttf, SIL OFL 1.1.
SHA256 pins the exact source; no network access is performed by this script.
"""
import argparse
import hashlib
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
expected = "bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d"
if hashlib.sha256(args.source.read_bytes()).hexdigest() != expected:
    raise ValueError("Unexpected source font; verify provenance before rebuilding")
font = instantiateVariableFont(TTFont(args.source), {"wght": 400, "wdth": 100}, inplace=True)
options = subset.Options()
options.recalc_timestamp = False
subsetter = subset.Subsetter(options=options)
subsetter.populate(unicodes=set(range(0x250)) | set(range(0x400, 0x530))
                   | set(range(0x1E00, 0x1F00)) | set(range(0x2000, 0x2070))
                   | set(range(0x20A0, 0x20D0)) | set(range(0x2190, 0x2200)))
subsetter.subset(font)
# Modified font is renamed, retaining original copyright and OFL license records.
for record in font["name"].names:
    replacement = {1: "Project PDF Sans", 3: "ProjectPDFSans-Regular-1", 4: "Project PDF Sans Regular",
                   6: "ProjectPDFSans-Regular", 16: "Project PDF Sans", 17: "Regular"}.get(record.nameID)
    if replacement:
        record.string = replacement.encode(record.getEncoding())
font.recalcTimestamp = False
args.output.parent.mkdir(parents=True, exist_ok=True)
font.save(args.output)
cmap = TTFont(args.output).getBestCmap()
assert all(ord(c) in cmap for c in "ӘәҒғҠҡҢңӨөҪҫҮүҺһҘҙ₽")
assert args.output.stat().st_size <= 200_000
print(f"PDF_FONT_PASS bytes={args.output.stat().st_size}")
