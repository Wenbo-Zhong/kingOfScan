"""Create a standalone local before/after sheet from the actual native acceptance files."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

parser = argparse.ArgumentParser()
parser.add_argument("fixtures", type=Path)
parser.add_argument("results", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
font_path = Path("/System/Library/Fonts/Supplemental/Arial.ttf")
font = ImageFont.truetype(str(font_path), 18) if font_path.is_file() else ImageFont.load_default()
sheet = Image.new("RGB", (880, 1670), "#f3f4f6")
draw = ImageDraw.Draw(sheet)
draw.text((25, 18), "KingOfScan: actual iOS native outputs | local validation, 2026-11-23", fill="#17212b", font=font)
draw.text((180, 52), "Original", fill="#17212b", font=font)
draw.text((610, 52), "Processed", fill="#17212b", font=font)
for row, (name, title) in enumerate([
    ("docres", "Smart HD / DocRes"), ("handwriting", "Black handwriting / DIS"),
    ("fingers", "Fingers / HandsWild EYTH"), ("watermark", "Stamp / DocDiff"),
    ("esdnet", "Moire / ESDNet"),
]):
    top = 88 + row * 308
    draw.text((25, top), title, fill="#17212b", font=font)
    for column, path in enumerate([args.fixtures / f"{name}-page.png", args.results / f"{name}-page-result.jpg"]):
        image = ImageOps.contain(Image.open(path).convert("RGB"), (404, 270))
        left = 25 + column * 430
        draw.rectangle((left, top+27, left+404, top+297), fill="#dce0e5")
        sheet.paste(image, (left+(404-image.width)//2, top+27+(270-image.height)//2))
draw.text((25, 1637), "Sources and limitations: verification/models/README.md", fill="#374151", font=font)
args.output.parent.mkdir(parents=True, exist_ok=True)
sheet.save(args.output)
print(args.output)
