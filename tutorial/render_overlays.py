from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "tutorial" / "work"
FONT = Path.home() / "Library/Fonts/Inter-VariableFont_opsz,wght.ttf"


def font(size: int):
    return ImageFont.truetype(str(FONT), size)


def main() -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", (1920, 1080), (5, 14, 27))
    draw = ImageDraw.Draw(image)

    draw.rounded_rectangle((70, 58, 1850, 1022), radius=30, fill=(9, 25, 45), outline=(52, 84, 113), width=2)
    draw.rounded_rectangle((106, 94, 405, 145), radius=14, fill=(45, 198, 225))
    draw.text((130, 103), "MANDATORY PREFLIGHT", font=font(22), fill=(5, 24, 39))
    draw.text((106, 180), "Verify the exact working folder before Run", font=font(54), fill=(255, 255, 255))
    draw.text((106, 252), "CoreAlign now blocks duplicate configs and unsafe mixed parameters.", font=font(27), fill=(161, 181, 202))

    draw.rounded_rectangle((106, 330, 905, 875), radius=22, fill=(12, 35, 61), outline=(48, 78, 105), width=2)
    draw.text((150, 370), "ONE NEW FOLDER", font=font(21), fill=(103, 220, 240))
    draw.text((150, 426), "CoreAlign-Run/", font=font(34), fill=(255, 255, 255))
    tree = [
        ("01", "TMA_0.6mm_7_backsub.ome.tif"),
        ("02", "CoreAlign.groovy"),
        ("03", "corealign.config.json"),
    ]
    for index, (number, name) in enumerate(tree):
        y = 505 + index * 91
        draw.rounded_rectangle((150, y, 204, y + 54), radius=12, fill=(28, 66, 94))
        draw.text((165, y + 13), number, font=font(18), fill=(246, 176, 65))
        draw.text((230, y + 8), name, font=font(27), fill=(229, 237, 245))
    draw.text((150, 806), "No (1), (2), or backup config beside the slide", font=font(22), fill=(255, 157, 157))

    draw.rounded_rectangle((945, 330, 1814, 875), radius=22, fill=(12, 35, 61), outline=(48, 78, 105), width=2)
    draw.text((990, 370), "EXAMPLE SLIDE MUST SHOW", font=font(21), fill=(103, 220, 240))
    checks = [
        ("Profile", "skin_18x7"),
        ("Grid", "18 rows x 7 columns"),
        ("Core diameter", "0.6 mm"),
        ("Config", "the file in CoreAlign-Run"),
    ]
    for index, (label, value) in enumerate(checks):
        y = 448 + index * 87
        draw.ellipse((990, y + 5, 1022, y + 37), fill=(76, 214, 150))
        draw.line((999, y + 21, 1008, y + 30, 1026, y + 8), fill=(5, 40, 31), width=5)
        draw.text((1050, y), label.upper(), font=font(17), fill=(147, 170, 193))
        draw.text((1050, y + 25), value, font=font(27), fill=(255, 255, 255))
    draw.rounded_rectangle((990, 801, 1768, 842), radius=12, fill=(83, 31, 39))
    draw.text((1010, 810), "STOP if any value is different", font=font(20), fill=(255, 189, 189))

    draw.text((106, 928), "Open the slide copy from CoreAlign-Run, then open and run CoreAlign.groovy.", font=font(25), fill=(229, 237, 245))
    image.save(WORK / "preflight_guard.png", optimize=True)


if __name__ == "__main__":
    main()
