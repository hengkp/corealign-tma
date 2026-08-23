from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / "tutorial" / "work"
FONT = Path.home() / "Library/Fonts/Inter-VariableFont_opsz,wght.ttf"

CHAPTERS = [
    "WHAT COREALIGN SOLVES",
    "DOWNLOAD FROM GITHUB",
    "PREPARE THE WORKING FOLDER",
    "CONFIGURE YOUR ARRAY",
    "OPEN, RUN, AND REVIEW THE GRID",
    "ROTATE FIRST, CROP SECOND",
    "HUMAN REVIEW AND SELECTIVE RESUME",
    "PRESENTATION AND ARCHIVE OUTPUTS",
    "ACCURACY AND APPROVAL POLICY",
    "REUSE WITH OTHER ARRAYS",
]


def main() -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    small = ImageFont.truetype(str(FONT), 18)
    title = ImageFont.truetype(str(FONT), 34)
    number = ImageFont.truetype(str(FONT), 24)

    for index, chapter in enumerate(CHAPTERS, start=1):
        image = Image.new("RGBA", (1920, 1080), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
        right = min(1250, 170 + int(draw.textlength(chapter, font=title)) + 130)
        draw.rounded_rectangle((48, 46, right, 156), radius=16, fill=(8, 24, 45, 238))
        draw.rounded_rectangle((48, 46, 58, 156), radius=5, fill=(45, 198, 225, 255))
        draw.text((86, 65), "COREALIGN TMA TUTORIAL", font=small, fill=(103, 220, 240, 255))
        draw.text((86, 94), chapter, font=title, fill=(255, 255, 255, 255))
        draw.text((right - 60, 61), f"{index:02d}", font=number, fill=(246, 176, 65, 255))
        image.save(WORK / f"overlay_{index:02d}.png", optimize=True)


if __name__ == "__main__":
    main()
