# CoreAlign TMA v1.4 tutorial production prompt

Copy the prompt below into the recording and editing workflow. Do not publish any output until every acceptance check passes.

## Master prompt

Create a complete English video tutorial for CoreAlign TMA v1.4 by recording real clicks on the prepared Mac and real behavior in the current public website, Finder, and QuPath 0.7.x. The audience is wet-lab researchers, biologists, and pathologists who do not want to learn technical configuration. Keep the explanation short, practical, calm, and linear.

The finished video should be approximately 6 to 7 minutes. Record at 1920 by 1080, 60 frames per second, with a visible but unobtrusive cursor. Every visible action must be performed on the machine. Do not use a fake cursor, simulated dialog, mock result, or reconstructed screen.

Use only the public website, the clean `CoreAlign-Run` demonstration folder, the validated generic example slide, `CoreAlign.groovy`, and one `corealign.config.json`. Do not show patient identifiers, private project names, Dropbox contents, Downloads root, browser bookmarks, email, calendar, terminal history, recent documents, other QuPath scripts, or any unrelated file.

## Privacy setup before recording

Complete this setup before the screen recorder starts:

1. Use a separate clean macOS desktop with a plain neutral wallpaper.
2. Turn on Do Not Disturb and disable notification previews.
3. Close Mail, Messages, Calendar, Dropbox windows, terminal windows, and every unrelated application.
4. Use a clean logged-out browser profile with no bookmarks bar, account avatar, history suggestions, downloads list, or extensions visible.
5. Keep only one browser tab open at a time.
6. In Finder, open the prepared `CoreAlign-Run` folder before recording. Hide the sidebar, path bar, recent files, tags, and unrelated folders.
7. The visible folder must contain only the slide, `CoreAlign.groovy`, `corealign.config.json`, and `READ-ME-FIRST.md`.
8. In QuPath Script Editor, close all old saved scripts. Discard only known temporary Untitled test tabs. Open only the production `CoreAlign.groovy` from the demonstration folder.
9. Open the slide from that same demonstration folder. The folder containing the open slide determines which config CoreAlign uses.
10. Confirm that no patient name, specimen identifier, staff name, username, private path, project title, or institutional file is visible at any time.
11. Record application windows or a fixed safe region rather than the entire desktop.
12. Perform a frame-by-frame privacy review before export. Blur is a fallback only. Re-record a scene when possible.

## Visual and narration rules

- Narration language: English only.
- Voice: warm, clear, neutral international English, reassuring rather than promotional.
- Speaking rate: 140 to 150 words per minute.
- ElevenLabs starting settings: stability 55, similarity 75, style 10, speaker boost on.
- Generate narration once from the approved text below. Do not rewrite narration after screen capture without also updating the clicks and captions.
- Keep the cursor movement slow and deliberate. Pause for one second before a click and two seconds after an important result appears.
- Use short punch-in zooms only around the active button, grid review, and output folder.
- Use straight cuts or subtle 150 millisecond dissolves. No decorative transitions.
- Speed up long processing sections between 6x and 10x, keep the progress visible, and label them `Processing time shortened`.
- Background music must be instrumental, light, and unobtrusive. Target voice at about minus 16 LUFS integrated and music around minus 30 LUFS under narration.
- Add accurate English captions from the final narration. Do not auto-publish unreviewed captions.
- Use the GPT Image 2 workflow visual as the opening and chapter transition image. Do not use generated imagery to replace actual clicks.

## Scene plan with exact narration

### Scene 1. Result first, 0:00 to 0:25

Screen action:

- Begin with the generated four-stage workflow visual.
- Cut to a clean folder containing several aligned PNG core images.
- Show one core briefly, with the tissue direction consistently at the top.

Narration:

> CoreAlign prepares a complete TMA slide for analysis and presentation. It finds each core, rotates it consistently, crops it after rotation, and saves the result. Instead of repeating the same work by hand, you review only the parts that need attention.

### Scene 2. Download CoreAlign, 0:25 to 1:00

Screen action:

- Open `https://hengkp.github.io/corealign-tma/` in the clean browser.
- Point to the short four-step workflow.
- Click `Download CoreAlign`.
- On the GitHub release page, download `CoreAlign.groovy` only. Keep the browser crop tight so no account or unrelated page content is visible.

Narration:

> Start on the CoreAlign website. The workflow has four steps: keep the files together, press Run in QuPath, check the result, and use the aligned images. Download the latest CoreAlign Groovy file from the release page.

### Scene 3. Create the optional config, 1:00 to 1:40

Screen action:

- Return to the website and click `Create config`.
- Select `Skin`.
- Select `Presentation images`.
- Keep the channel helper closed.
- Click `Download config`.

Narration:

> A config is optional, but it makes the intended output clear. Choose Skin when the epidermis should be placed at the top. Choose Presentation images for full resolution PNG files. Use the Research package only when every original channel is also needed. Rows, columns, and core size do not need to be entered.

### Scene 4. Prepare one clean folder, 1:40 to 2:10

Screen action:

- Show the already prepared `CoreAlign-Run` Finder window.
- Point once to the slide, `CoreAlign.groovy`, and `corealign.config.json`.
- Do not open Downloads root and do not drag files from another private folder while recording.

Narration:

> Put the slide, CoreAlign dot groovy, and the optional config in one folder. This is important because CoreAlign reads the config and saves every result beside the slide that is open in QuPath. Keep exactly one CoreAlign config in this folder.

### Scene 5. Open the correct slide and script, 2:10 to 2:55

Screen action:

- In QuPath choose `File`, then `Open`, and open the slide from the prepared folder.
- Choose `Automate`, then `Script editor`.
- Show only one saved script tab.
- Choose `File`, then `Open`, and open `CoreAlign.groovy` from the same folder.
- Point to the Run button but do not click until the narration reaches the final sentence.

Narration:

> Open the slide copy from the prepared folder. Next, open the QuPath Script Editor and load CoreAlign dot groovy from the same folder. If old scripts are listed, close the script tabs first. Closing a saved tab does not delete the Groovy file. Now press Run.

### Scene 6. Automatic detection, 2:55 to 3:55

Screen action:

- Click Run once.
- Show the log lines for profile `automatic`, runtime config verification, structural QC, and the human review pause.
- Shorten the waiting portion while keeping genuine processing visible.
- For the validated example, show `TECHNICAL DETECTION VALIDATION: 126/126` when it appears.

Narration:

> CoreAlign now scans the slide, identifies useful channels, detects the array, and checks its structure. A known validated slide uses its locked reference geometry. A new slide estimates core size, rows, and columns automatically. If the layout is uncertain, CoreAlign stops instead of creating a guessed grid. The first successful run pauses for a human grid check.

### Scene 7. Review the grid, 3:55 to 4:35

Screen action:

- Fit the full TMA array in the QuPath viewer.
- Slowly pan across the first row, one middle row, and the final row.
- Open the generated grid QC image from the safe working folder.
- Point to present cores, true empty positions, and any amber or review marker.
- Do not approve while an obvious core is outside its circle or a real core is marked missing.

Narration:

> Check that every circle is centered on the correct core, that true empty positions are marked missing, and that the first and last rows are complete. The QC image gives a quick overview. If anything is wrong, do not approve it. Correct the grid first. When the grid is correct, run the same CoreAlign script again.

### Scene 8. Rotate first and crop second, 4:35 to 5:35

Screen action:

- Run the same script again.
- Accept the grid only after the reviewed hash and dimensions are shown.
- Show genuine per-core processing, then speed up the middle of the run.
- Open the orientation contact sheet or review page.
- Point to accepted cores and one flagged core if a real flag exists. Do not create a fake flag.

Narration:

> After approval, CoreAlign processes one core at a time. It refines the source region, estimates the orientation, rotates the full source data, and only then crops the final image. A checkpoint is saved after every core. If QuPath stops, run the same script again and completed cores are reused. Review any core that CoreAlign flags before final approval.

### Scene 9. Find the output files, 5:35 to 6:20

Screen action:

- Open only the new CoreAlign export folder.
- Show `rotated_fullres` and open two PNG files.
- If the Research package was selected in a separate approved take, show `rotated_multichannel_ome` without opening a very large file.
- Show the contact sheet and review report briefly.

Narration:

> The rotated full resolution folder contains PNG images for presentations and figures. The optional multichannel folder contains rotated OME TIFF files with every original channel. The contact sheet and review report make the complete array easy to check. Keep the original whole slide as the source of truth.

### Scene 10. Close, 6:20 to 6:45

Screen action:

- Return to the generated workflow visual.
- End on the public CoreAlign website with the Download and Create config buttons visible.

Narration:

> The normal workflow is simple: prepare one folder, press Run, check the grid, review flagged cores, and use the aligned images. CoreAlign reduces repetitive computer work while keeping the researcher in control.

## Required on-screen callouts

Use no more than one callout at a time:

- `Keep all files in one folder`
- `No rows or columns to enter`
- `Review before approval`
- `Rotate first, crop second`
- `Checkpoint saved after every core`
- `Processing time shortened`
- `PNG for presentation`
- `OME-TIFF for all channels`

## Editing and delivery

Deliver these files together:

- `CoreAlign-TMA-v1.4-tutorial-1080p.mp4`, H.264, 1920 by 1080, 60 fps
- `CoreAlign-TMA-v1.4-tutorial-master.mov`, high-quality edit master
- `CoreAlign-TMA-v1.4-tutorial-en.srt`
- `CoreAlign-TMA-v1.4-tutorial-en.vtt`
- `CoreAlign-TMA-v1.4-tutorial-chapters.txt`
- one 1280 by 720 generated thumbnail with no real specimen data
- a privacy QC checklist signed off scene by scene

## Acceptance checklist

Do not publish unless all items pass:

- The website shown is the current production site.
- No video player or outdated tutorial is visible on the website.
- Every click is real and follows the narration in the same order.
- The slide and config come from the same clean folder.
- The config profile shown is `automatic`.
- No row, column, or punch-size entry is demonstrated.
- The first run stops at grid review.
- The second run demonstrates rotate first and crop second.
- Resume and checkpoints are explained accurately.
- No patient data, private path, personal account, unrelated file, notification, or recent document is visible.
- Captions exactly match the final narration.
- Music never masks the voice.
- The final frame points to the current website and release.
