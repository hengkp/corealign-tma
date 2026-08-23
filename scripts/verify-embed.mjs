import { readFile } from "node:fs/promises";
import { gunzipSync } from "node:zlib";
const root = new URL("../", import.meta.url);
const runner = await readFile(new URL("workflow/CoreAlign.groovy", root), "utf8");
const names = ["01_build_tma_grid.groovy","02_auto_orient_epidermis.groovy",
  "03_review_correct_and_approve_grid.groovy","04_restore_approved_grid.groovy",
  "05_finalize_orientation_review.groovy","06_export_presentation_package.groovy",
  "07_build_qupath_analysis_project.groovy"];
let bad = 0;
for (const [i, name] of names.entries()) {
  const re = new RegExp(`def step${i+1} = new EmbeddedWorkflowScript\\(name: '${name.replaceAll(".","\\.")}', payload: '''\\n([\\s\\S]*?)\\n'''\\)`);
  const m = runner.match(re);
  if (!m) { console.log("MISSING", name); bad++; continue; }
  const decoded = gunzipSync(Buffer.from(m[1].replace(/\n/g, ""), "base64")).toString("utf8");
  const src = await readFile(new URL(`workflow/embedded/${name}.src`, root), "utf8");
  if (decoded === src) console.log(`ROUNDTRIP OK   ${name}  (${decoded.length} chars)`);
  else { console.log(`ROUNDTRIP FAIL ${name}`); bad++; }
}
// the two gate flags must actually be inside the embedded payloads
const g3 = runner.match(/def step3[\s\S]*?'''\)/)[0];
const g5 = runner.match(/def step5[\s\S]*?'''\)/)[0];
const d3 = gunzipSync(Buffer.from(g3.match(/'''\n([\s\S]*?)\n'''/)[1].replace(/\n/g,""),"base64")).toString();
const d5 = gunzipSync(Buffer.from(g5.match(/'''\n([\s\S]*?)\n'''/)[1].replace(/\n/g,""),"base64")).toString();
console.log("step3 honours gate:", d3.includes("corealign.gate.gridApproved"));
console.log("step5 honours gate:", d5.includes("corealign.gate.finalApproved"));
if (!d3.includes("corealign.gate.gridApproved") || !d5.includes("corealign.gate.finalApproved")) bad++;
process.exit(bad ? 1 : 0);
