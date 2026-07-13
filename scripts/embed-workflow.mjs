import { readFile, writeFile } from "node:fs/promises";
import { gzipSync } from "node:zlib";

const root = new URL("../", import.meta.url);
const runnerUrl = new URL("workflow/CoreAlign.groovy", root);
const sources = [
  ["1", "01_build_tma_grid.groovy", "workflow/embedded/01_build_tma_grid.groovy.src"],
  ["2", "02_auto_orient_epidermis.groovy", "_archieved/legacy-multi-file-workflow/02_auto_orient_epidermis.groovy"],
  ["3", "03_review_correct_and_approve_grid.groovy", "_archieved/legacy-multi-file-workflow/03_review_correct_and_approve_grid.groovy"],
  ["4", "04_restore_approved_grid.groovy", "_archieved/legacy-multi-file-workflow/04_restore_approved_grid.groovy"],
  ["5", "05_finalize_orientation_review.groovy", "_archieved/legacy-multi-file-workflow/05_finalize_orientation_review.groovy"],
  ["6", "06_export_presentation_package.groovy", "_archieved/legacy-multi-file-workflow/06_export_presentation_package.groovy"],
  ["7", "07_build_qupath_analysis_project.groovy", "_archieved/legacy-multi-file-workflow/07_build_qupath_analysis_project.groovy"],
];

const wrapBase64 = (value) => value.match(/.{1,76}/g).join("\n");
let runner = await readFile(runnerUrl, "utf8");

for (const [step, name, sourcePath] of sources) {
  const source = await readFile(new URL(sourcePath, root), "utf8");
  const payload = wrapBase64(gzipSync(Buffer.from(source, "utf8"), { level: 9 }).toString("base64"));
  const pattern = new RegExp(
    `(def step${step} = new EmbeddedWorkflowScript\\(name: '${name.replaceAll(".", "\\.")}', payload: '''\\n)[\\s\\S]*?(\\n'''\\))`,
  );
  if (!pattern.test(runner)) throw new Error(`Could not find embedded step ${step}: ${name}`);
  runner = runner.replace(pattern, `$1${payload}$2`);
}

await writeFile(runnerUrl, runner, "utf8");
