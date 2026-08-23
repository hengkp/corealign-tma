# Archived material

Kept for provenance. Nothing here is used by a run, a build, or the website.

**Never take a workflow file from this folder.** The only supported runner is
`workflow/CoreAlign.groovy`, and the seven numbered steps it embeds live in
`workflow/embedded/*.groovy.src`. That distinction is the reason this folder exists: for most of
2026 the live step sources sat under an archive folder, which made it easy to edit the wrong copy.

| Folder | What it is | Why it is here |
|---|---|---|
| `pre-v2-workflow/` | `01_build_tma_grid.groovy.pre-v1.9` | An older copy of step 1. The live source is `workflow/embedded/01_build_tma_grid.groovy.src` and the two are **not** identical. |
| `tutorial-v1.9/` | Written tutorial and video brief for the v1.x flow | It teaches the old multi-run sequence: run, review, run again, approve, run again. v2.0.0 runs once, so following it now produces wrong instructions. Replaced by the [manual](https://hengkp.github.io/corealign-tma/guide/). |
| `tutorial-v1/` | The first tutorial source | Superseded by `tutorial-v1.9`, which was itself superseded. |
| `tools-pre-v2/` | `rebuild_embedded_step.py` | Rebuilt one payload, step 1 only. `scripts/embed-workflow.mjs` does all seven and is what the build uses. |
| `chatgpt-sites-scaffold/` | Scaffolding from an earlier hosting approach | The site is Next.js on GitHub Pages. |
| `website-v1.2/` | The previous Config Builder screenshot | Kept so old release notes still resolve. |
| `local-only/` | **Not in git.** Build cache from the abandoned scaffold, and 1.2 GB of video working files for the v1.x tutorial | Regenerable or unused. Delete it freely if the disk is needed. |

Renamed from `_archieved` on 23 August 2026. The old spelling was a typo that had been propagated
and then documented as deliberate.
