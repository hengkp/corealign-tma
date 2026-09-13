"""Functional checks for the angle corrections file, run by tests/studio-page.test.mjs.

Everything here runs against a temporary project folder and the real Run class, with no
QuPath and no bridge: the file path is the one a project opened after its run takes.
"""
import importlib.util
import json
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "corealign_studio", HERE.parent / "studio" / "corealign_studio.py")
studio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(studio)

failures = []


def check(condition, message):
    if not condition:
        failures.append(message)


def project_with_report(folder: Path, cores: list, started_at: str = "2026-09-13T01:00:00Z"):
    (folder / "qc" / "02-orientation").mkdir(parents=True, exist_ok=True)
    report = {"image": "slide.ome.tif", "startedAt": started_at, "status": "ORIENTATION_REVIEW_REQUIRED",
              "cores": [{"index": i + 1, "core": name, "row": 1, "col": i + 1, "status": "ok",
                         "webRotationAdjustmentDeg": applied}
                        for i, (name, applied) in enumerate(cores)]}
    (folder / "qc" / "02-orientation" / "run_report.json").write_text(json.dumps(report), "utf-8")


def angles_in(path: Path):
    saved = json.loads(path.read_text("utf-8"))
    return {item["core"]: item["rotationAdjustmentDeg"] for item in saved["corrections"]}, saved


with tempfile.TemporaryDirectory() as tmp:
    project = Path(tmp) / "project"
    project.mkdir()
    (project / "slide.ome.tif").write_bytes(b"")
    run = studio.Run()
    run.slide = project / "slide.ome.tif"
    run.project = project
    target = project / "corealign-review-corrections.json"

    # Pass one: 1-A already carries an applied web angle of 10, the page edits 1-B.
    project_with_report(project, [("1-A", 10.0), ("1-B", 0.0), ("1-C", 0.0)])
    answer = run.save_corrections([{"core": "1-B", "rotationAdjustmentDeg": 20}])
    check(answer.get("ok") is True and answer.get("via") == "file", f"first save failed: {answer}")
    angles, saved = angles_in(target)
    check(angles == {"1-A": 10.0, "1-B": 20.0},
          f"the file must carry the applied angle and the new edit, got {angles}")
    check(saved["baseRun"] == "2026-09-13T01:00:00Z", "the file must name the report's run")
    check(answer.get("saved") == 1, "the count reported to the page is the page's own edits")

    # Saving exactly the same set again must not touch the file: its timestamp is what
    # CoreAlign reads as "reprocess and ask again".
    before = target.stat().st_mtime_ns
    target_text = target.read_text("utf-8")
    answer = run.save_corrections([{"core": "1-B", "rotationAdjustmentDeg": 20}])
    check(answer.get("ok") is True and answer.get("unchanged") is True,
          f"an identical save must report unchanged, got {answer}")
    check(target.stat().st_mtime_ns == before and target.read_text("utf-8") == target_text,
          "an identical save must leave the file alone")

    # An edit wins over the applied angle for the same core, and zero takes it back.
    answer = run.save_corrections([{"core": "1-A", "rotationAdjustmentDeg": 0},
                                   {"core": "1-B", "rotationAdjustmentDeg": 20}])
    angles, _ = angles_in(target)
    check(angles == {"1-B": 20.0}, f"resetting 1-A to zero must drop it, got {angles}")

    # Pass two: CoreAlign applied both, the report moved on, the page edits 1-C only.
    project_with_report(project, [("1-A", 10.0), ("1-B", 20.0), ("1-C", 0.0)],
                        started_at="2026-09-13T01:30:00Z")
    answer = run.save_corrections([{"core": "1-C", "rotationAdjustmentDeg": -15}])
    angles, saved = angles_in(target)
    check(angles == {"1-A": 10.0, "1-B": 20.0, "1-C": -15.0},
          f"the second pass must keep every applied angle, got {angles}")
    check(saved["baseRun"] == "2026-09-13T01:30:00Z", "the file must move to the new run")

    # The review model surfaces only what is not applied yet, so the page counts pending
    # edits correctly; the file still holds all three.
    model = run.review()
    check(model["corrections"] == {"1-A": 10.0, "1-B": 20.0, "1-C": -15.0},
          f"the model reads the file for this run, got {model['corrections']}")

    # A file left by another slide is no answer for this one.
    target.write_text(json.dumps({"schemaVersion": 1, "image": "other.ome.tif",
                                  "baseRun": "x", "corrections": [
                                      {"core": "1-A", "rotationAdjustmentDeg": 10.0},
                                      {"core": "1-B", "rotationAdjustmentDeg": 20.0},
                                      {"core": "1-C", "rotationAdjustmentDeg": -15.0}]}), "utf-8")
    check(run.corrections_on_disk(model) == {}, "another slide's file must read as absent")
    answer = run.save_corrections([{"core": "1-C", "rotationAdjustmentDeg": -15}])
    check(answer.get("ok") is True and not answer.get("unchanged"),
          f"the other slide's file must be replaced, got {answer}")
    angles, saved = angles_in(target)
    check(saved["image"] == "slide.ome.tif" and angles == {"1-A": 10.0, "1-B": 20.0, "1-C": -15.0},
          f"the replacement must name this slide, got {saved.get('image')} {angles}")

    # Validation still refuses what the bridge would refuse.
    check(run.save_corrections([{"core": "9-Z", "rotationAdjustmentDeg": 1}]).get("ok") is False,
          "an unknown core must be refused")
    check(run.save_corrections([{"core": "1-A", "rotationAdjustmentDeg": 200}]).get("ok") is False,
          "an angle outside -180..180 must be refused")

if failures:
    for line in failures:
        print("FAIL", line)
    sys.exit(1)
print("STUDIO_CORRECTIONS_OK")
