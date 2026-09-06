# CoreAlign Studio · customer journey and the UI rebuild

Baseline read on 6 Sep 2026 against **v2.12.0**, verified byte-identical to the
`corealign-studio.sif` running on node1 (`sha256` of `index.html`,
`corealign_studio.py` and `CoreAlign.groovy` all match the deployed image).

Owner's report: the app is too hard to operate, and the steps should be simpler.

---

## 1 · The journey today, and where it costs the operator

Six stage tabs for what is conceptually five steps, and three of those steps hide the
primary action behind a manual save.

| # | Stage | What the operator does | Friction |
|---|---|---|---|
| 1 | Choose slide | Browse folders, click a slide | – |
| 2 | Set up | Answers **3** questions: tissue, output type, channels | F1 |
| 3 | Processing | Watches an indeterminate bar | – |
| 4 | Review grid | Drags circles, marks missing | F2 F3 F4 F5 |
| 5 | Review cores | Walks a queue, one core per screen | F2 F3 F6 |
| 6 | Arrange | Builds the figure | F2 |
| 7 | Results | Downloads zips | F8 |

### The eight friction points

| ID | What happens | Principle it breaks |
|---|---|---|
| **F1** | Three questions before the run starts. One of them, output type, is a choice the operator cannot reason about: `presentation` = PNG only, `research` = PNG + OME-TIFF + QuPath project. Picking wrong means re-running. | Hick's Law. A choice with no informed answer is not a choice, it is a trap. |
| **F2** | Grid, angles and figure each have a **manual Save button**. Nothing is written until it is pressed. | Recognition over recall. The system knows the work is unsaved and makes the person remember instead. |
| **F3** | The primary button is **disabled and relabelled** while edits are unsaved: *Continue* becomes *Save before continue*. | Gulf of execution. The one control the operator is reaching for goes dead, and the reason is in 12 px of grey text. The target also moves under the cursor. |
| **F4** | The grid editor can only **move** a circle and **mark it missing**. No resize, no add, no delete. | Missing affordance. The operator is pushed back to QuPath desktop, which is the exact thing Studio exists to avoid. |
| **F5** | Circles carry **no row or column label**. The name appears only in the side rail after selecting one. | Recall over recognition. Identifying `4-D` means clicking and reading elsewhere. |
| **F6** | Core review is a queue plus **one** large viewer. A 126-position slide is 126 sequential screens. | No overview. The operator cannot see that 118 are fine and 8 need work. |
| **F7** | Six stage tabs, one of which (*Processing*) is not a decision the operator makes. | Progressive disclosure applied to the wrong axis. |
| **F8** | No menu bar. Export and the QuPath project exist only at the end, and only if `research` was picked in step 2. | The operator cannot leave with their work at any point. |

### F2 and F3 are one bug wearing two hats

The manual save is not arbitrary. `03_review_correct_and_approve_grid.groovy.src` applies
corrections **from a file**, so approving a gate with unsaved edits would discard work the
operator had just done and report success. An earlier version did exactly that and lost three
corrections to a caught exception.

Autosave removes the failure mode rather than papering over it, **provided the gate waits for
the flush**. That is the single most important correctness rule in this rebuild.

---

## 2 · The journey after the rebuild

Five steps. One question per step. The run advances on one filled button.

```
1 Slide      pick a .qptiff or .ome.tiff
2 Tissue     skin  or  other                        -> Run
3 Cores      circles + row/col labels, edit freely  -> Run      (autosave)
4 Orientation responsive card grid, adjust angles   -> Run      (autosave)
5 Figure     arrange, channel, colour, export                   (autosave)
```

A menu bar sits above all five and never changes: project name, saved state, **File** menu
(Save project · Open in QuPath · Export), and a filled **Export** button.

---

## 3 · The changes, and the principle each one serves

### C1 · Delete the output-type choice · always write the research set

Step 2 asks **one** question: skin or other.

`saveRotatedMultichannelOmeTiff` is forced true, so every run produces PNG, OME-TIFF **and**
`qupath/project.qpproj`. That is what makes "open it in QuPath afterwards" true by default
rather than a setting somebody had to have guessed right hours earlier.

> Cost, stated plainly: the OME-TIFF writer is not thread safe, so research runs write cores
> one at a time. This makes every run slower than a `presentation` run. It is the price of the
> project file always existing, and the owner asked for the project file always existing.

### C2 · Delete the channel picker from step 2

Channel selection moves to step 5, where the owner put it and where it is a **display**
decision with a visible result.

The repo's own measurement settles the run side: reading 6 channels versus 19 is **5.1–5.7 s
against 5.1–5.6 s**, a ratio of 1.18 that sits inside the noise, and two full runs came out
**15:27 with all nineteen channels against 18:28 with six**. The subset was slightly *slower*.
So the run keeps every channel, and step 5 chooses what to show. This also removes the class of
bug the README attributes to the transformed server: three separate defects came from a channel
view being a different thing wearing the slide's clothes.

### C3 · Autosave everywhere, with a flush barrier before every gate

Replaces F2 and F3.

- Every edit marks the document dirty and schedules a debounced write at **800 ms**.
- The menu bar shows `Saving…` then `Saved`, using colour **plus** icon **plus** words.
- The three Save buttons are **removed**.
- The primary button is **never disabled for save reasons** and **never changes its label**.
- Pressing it calls `await flushSaves()` first. Only when the write has returned does the gate
  POST go out.
- A failed write turns the indicator red, keeps the edit in memory, and blocks the gate with a
  message naming the failure. Silence is never treated as success.

### C4 · Row and column labels on every circle

Replaces F5. The label renders inside the circle when it fits and above it when it does not,
in the same paint as the circle so it moves with a drag.

### C5 · Ghost circles at every empty position

Replaces the "add a circle" half of F4, decided by the owner on 6 Sep 2026.

The QuPath grid is a row × column lattice: **every position already has a slot**, whether or
not it holds a core. So the honest model is not "create a shape", it is "this position is
empty, use it".

```
A1 ●   A2 ●   A3 ◌   A4 ●
B1 ●   B2 ◌   B3 ●   B4 ●
```

- `●` present · solid outline
- `◌` empty · dashed ghost, always visible
- Click a ghost, or drag it onto tissue → `restore`
- Select a core, press Remove → `mark_missing`, it becomes a ghost

Both map onto correction actions that **already exist**, so no new grid semantics and no risk
to row labelling. A free-floating core outside the lattice was considered and rejected: the
README records that inserting a core shifts every later one along and would corrupt the row
labels.

### C6 · Per-core diameter, carried through to the backend

Replaces the "resize" half of F4, decided by the owner on 6 Sep 2026.

🔴 **This is a real backend defect, not only a missing control.** The corrections file already
carries `diameter`, and `03_review_correct_and_approve_grid.groovy.src` line 492 already parses
it into `dd` and draws the annotation with it at line 503. But line 577 then builds the
replacement core with `nominalDiameter` and **throws the operator's value away**:

```groovy
double diameter = markMissing ? Math.max(8.0d, nominalDiameter * 0.05d) : nominalDiameter
```

So today a resize would be silently ignored end to end. Fix: take the diameter from the
annotation's own ROI when not marking missing, keep the placeholder rule for missing.

UI: a selected circle gets **8 resize handles**, and the rail gets a diameter field in µm using
the slide calibration the run already carries.

### C7 · Step 4 becomes a responsive card grid

Replaces F6.

`repeat(auto-fill, minmax(200px, 1fr))`, so the column count follows the screen with no
breakpoint list to maintain. Each card holds the rotated core, its name, its status chip and a
compact angle control. Clicking a card opens the large view for fine work.

Above the grid: filter chips with live counts, a density control (S / M / L), and a selection
mode whose floating bar carries bulk actions.

The overview answers the step's one question, *which cores need me*, before any clicking.

### C8 · A menu bar that is always there

Replaces F8.

| Slot | Content |
|---|---|
| left | CoreAlign Studio · project name · **File** menu |
| centre | the 5-step tracker |
| right | saved state · language · theme · filled **Export** |

**File** menu: Save project · Open in QuPath (shows the path and copies it) · Export figure ·
Export all results · Download project as zip.

Save project writes the QuPath project at whatever stage the run has reached, so the operator
can always leave and open the work in QuPath desktop. This is the owner's stated requirement:
CoreAlign Studio is a front end onto QuPath, not a replacement for it.

### C9 · Five stage tabs, not six

Replaces F7. *Processing* stops being a tab and becomes a state **on** the tab it belongs to:
step 3 and step 4 each show their own progress in place. The tracker reads
`Slide · Tissue · Cores · Orientation · Figure`.

---

## 4 · Where the patterns come from

Sourced from shipped products via Mobbin, not invented.

| Screen | App | Used for |
|---|---|---|
| [Canvas with a floating pill toolbar and a deletable label chip on the shape](https://mobbin.com/screens/5ea60921-5049-4a24-9821-eea39363e153) | Magnific | Step 3 tool bar under the image, label on the circle |
| [Left tool rail plus a bottom shape row with Ellipse, zoom and undo](https://mobbin.com/screens/73825377-f283-4acc-a6b4-f20474c74aa1) | Ghost | Step 3 tool grouping |
| [Brush size slider in a side panel](https://mobbin.com/screens/3960c1af-d783-4e00-9a7f-b4191f81ddce) | Hootsuite | Diameter control for a selected core |
| [Asset grid with an Appearance popover: layout, card size S/M/L, fit or fill](https://mobbin.com/screens/edd6480e-da5e-4a27-8eb7-6b39ed072173) | Frame.io | Step 4 card grid and its density control |
| [Floating bar over a grid reading "Selected: 2" with bulk actions](https://mobbin.com/screens/666cfa8b-ae8a-4433-87b7-ee2d1ac9fb3a) | Grok | Step 4 selection mode |
| [Gallery cards with image, fields and a coloured status chip](https://mobbin.com/screens/0e9a986f-01cd-4332-ac89-ba5c36853ae3) | Airtable | Step 4 card anatomy |
| [Overflow menu with Rename, Undo, Export, Print and PDF](https://mobbin.com/screens/d8a34c61-88ae-4fac-872d-4786d29aa480) | Coda | The File menu |
| [Title with a saved state, Share and a filled Export on the right](https://mobbin.com/screens/f1ba98ee-6050-4ecc-a96e-4b7223104d99) | SchoolAI | Menu bar layout |
| [Export as a right-hand panel listing the formats](https://mobbin.com/screens/76d8f7eb-14ed-416e-b0e0-7add15c67b48) | ClickUp | The Export sheet |

Retained from the existing build, which already sourced them the same way: queue and decisions
(Reddit mod queue), media on a dark stage (ClassDojo), run header over a collapsed log
(Databricks), filter chips with counts (Frontify), numbered stage tracker (Zoho CRM).

---

## 5 · Files this touches

| File | Change |
|---|---|
| `studio/static/index.html` | Menu bar, 5 stages, autosave, ghosts, labels, resize handles, card grid. English and Thai strings for everything added. |
| `studio/corealign_studio.py` | Force research output · drop the channel argument from `/api/run` · `POST /api/project/save` · widen `GRID_ACTIONS` validation for the diameter · keep every existing route working. |
| `workflow/embedded/03_review_correct_and_approve_grid.groovy.src` | Line 577 · carry the annotation's own diameter. |
| `workflow/CoreAlign.groovy` | Re-embed step 3 after the change. Keep `saveRotatedMultichannelOmeTiff` honouring the config so a non-Studio caller is unaffected. |

## 6 · What must not regress

- The bridge token never reaches the browser.
- A gate is never answered while a write is in flight or has failed.
- `corealign-grid-corrections.json` keeps naming the grid hash it was made against, so it is
  never applied twice.
- A correction that cannot be applied still stops the run.
- Reading a finished project without re-running it still works.
- Slide picking stays sandboxed to the allowed roots with symlinks resolved.
