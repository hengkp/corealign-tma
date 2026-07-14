# CoreAlign visual system

CoreAlign uses a bright scientific workbench style. The interface should feel precise, friendly, and easy to scan for wet-lab researchers, biologists, and pathologists.

## Direction

- Use the colorful workspace character of the Miro analysis in Awesome DESIGN.md.
- Apply Power Design rules for cognitive load, contrast, an 8 point spacing grid, short line lengths, and clear grouping.
- Use Remix Icon for all interface icons.
- Use Lottie only for small supportive motion. Never make motion necessary for understanding or operation.
- Use synthetic scientific imagery only. Never use patient or real pathology material as decoration.

## Color

Light mode uses pure white as the main canvas.

- Canvas: `#ffffff`
- Ink: `#171842`
- Primary blue: `#4262ff`
- Cyan: `#00a6d9`
- Yellow: `#ffd02f`
- Coral: `#ec6972`
- Mint: `#1aa998`
- Violet: `#8055df`

Dark mode uses deep navy rather than gray or green.

- Canvas: `#070b21`
- Surface: `#0e1533`
- Ink: `#f7f8ff`
- Primary blue: `#7188ff`

Use blue for primary actions. Use yellow, coral, mint, and violet to group steps or statuses. Never rely on color alone. Pair every status with text or an icon.

## Layout

- Keep the current menubar visible.
- Use one clear headline and one supporting paragraph per section.
- Use spacing from 8, 16, 24, 32, 48, 64, and 96 pixels.
- Keep body copy near 60 characters per line.
- Use rounded cards from 16 to 28 pixels with a thin border and restrained shadow.
- Let useful content fill the viewport. Avoid large empty regions that do not improve scanning.

## Motion

- Load Lottie after the main page content.
- Animate transform and opacity when possible.
- Keep loops calm and supportive.
- Stop decorative motion when `prefers-reduced-motion` is enabled.
- Keep `REPORT.html` fully offline. Use small CSS transitions there instead of a network runtime.

## Report

The website and `REPORT.html` share the same colors, card hierarchy, labels, and button language. QC images remain visually dominant. Decorative color must never cover tissue or annotations.
