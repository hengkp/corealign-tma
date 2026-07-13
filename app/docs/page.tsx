import SiteHeader from "../site-header";
import DocsToc from "./docs-toc";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";
const release = "https://github.com/hengkp/corealign-tma/releases/latest";

const toc = [
  ["overview", "Overview"],
  ["run", "Run CoreAlign"],
  ["review", "Review results"],
  ["correct", "Correct a core"],
  ["results", "Find your files"],
  ["resume", "Resume or upgrade"],
] as const;

export default function Documentation() {
  return (
    <main className="docsPage">
      <a className="skipLink" href="#docs-content">Skip to content</a>
      <SiteHeader />
      <div className="docsShell">
        <DocsToc toc={toc} release={release} />

        <article className="docsContent" id="docs-content">
          <header className="docsHero" id="overview">
            <p className="kicker"><i className="ri-book-open-line" /> Documentation</p>
            <h1>Start with one file. Follow one clear dashboard.</h1>
            <p>CoreAlign organizes detection, orientation, review, and export in one project folder. Open <code>START-HERE.html</code> whenever you need the current status or next action.</p>
            <div className="docsActions"><a className="button" href={release}>Download CoreAlign</a><a className="button secondary" href={`${basePath}/config-builder/`}>Create a config</a></div>
          </header>

          <section className="docsSection" id="run">
            <p className="docsEyebrow">Quick start</p>
            <h2>Run CoreAlign</h2>
            <div className="folderTree"><pre>{`my-project/
|-- slide.ome.tif
|-- CoreAlign.groovy
|-- corealign.config.json   optional
|-- START-HERE.html
|-- qc/
|   |-- 01-grid/
|   +-- 02-orientation/
|-- results/
|   |-- png/
|   |-- ome-tiff/
|   +-- tables/
|-- qupath/
+-- work/`}</pre></div>
            <div className="docsSteps">
              <article><span>1</span><div><h3>Prepare</h3><p>Put the slide and CoreAlign.groovy in the same folder. Add one optional config.</p></div></article>
              <article><span>2</span><div><h3>Run</h3><p>Open the slide in QuPath. Open the script in Script Editor and press Run.</p></div></article>
              <article><span>3</span><div><h3>Review</h3><p>Open START-HERE.html. Check the requested QC, correct if needed, then run the same script again.</p></div></article>
            </div>
            <div className="docsCallout"><i className="ri-information-line" /><div><b>A pause is usually intentional</b><span>CoreAlign stops at review gates. Accepted work is saved and does not need to run again.</span></div></div>
          </section>

          <section className="docsSection" id="review">
            <p className="docsEyebrow">Your project dashboard</p>
            <h2>Review everything in START-HERE.html</h2>
            <p>This is the only workflow HTML file. Use its tabs to move between Grid QC, Orientation QC, Results, and Help.</p>
            <div className="featureList">
              <div><i className="ri-layout-grid-line" /><span><b>Grid QC</b> Tissue is brightened under lighter outlines. The complete array opens in Fit view, and Reset always returns to the full image.</span></div>
              <div><i className="ri-refresh-line" /><span><b>Orientation QC</b> Switch between Before and Rotated. Click Edit only when a core needs a different angle.</span></div>
              <div><i className="ri-folder-open-line" /><span><b>Results</b> Open the current PNG, OME-TIFF, table, or QuPath project folder.</span></div>
            </div>
            <p className="docsSmall">Edit shows one slider with Reset, Cancel, and Confirm. After confirming, choose Download changes and save <code>corealign-review-corrections.json</code> beside the slide. Run CoreAlign again. Only edited cores are recalculated.</p>
          </section>

          <section className="docsSection" id="correct">
            <p className="docsEyebrow">Human correction in QuPath</p>
            <h2>Correct only the core that needs attention</h2>
            <p>Use the same action-first naming pattern for automatic and manual annotations.</p>
            <div className="nameTable">
              <div><code>TMA orientation 4-C</code><span>Automatic ellipse for the refined core footprint.</span></div>
              <div><code>TMA correction 4-C</code><span>Correct a missed or misplaced detection.</span></div>
              <div><code>TMA mark missing 4-C</code><span>Mark a truly empty position.</span></div>
              <div><code>TMA crop override 4-C</code><span>Replace the crop footprint for one core.</span></div>
              <div><code>Epidermis override 4-C</code><span>Mark the true epidermal side for one skin core.</span></div>
            </div>
            <ol className="docsSimpleList">
              <li>Select the ellipse or draw a new ellipse over the correct core.</li>
              <li>Give it the matching action and row-column name, for example <code>TMA correction 4-C</code>.</li>
              <li>Run CoreAlign.groovy again. The full QC summary is refreshed, but unchanged cores are reused.</li>
            </ol>
            <div className="docsCallout purple"><i className="ri-refresh-line" /><div><b>Change a wrong rotation</b><span>Open Orientation QC, click Edit on the core, adjust the slider, and Confirm. Download the changes beside the slide and run <code>CoreAlign.groovy</code> again.</span></div></div>
          </section>

          <section className="docsSection" id="results">
            <p className="docsEyebrow">Easy-to-find outputs</p>
            <h2>Open the folder for your next task</h2>
            <div className="resultGrid">
              <article><i className="ri-image-line" /><code>results/png/</code><h3>Presentation images</h3><p>Full resolution PNG files. Each core is rotated first, then cropped.</p></article>
              <article><i className="ri-stack-line" /><code>results/ome-tiff/</code><h3>Research images</h3><p>Rotated multichannel OME-TIFF files for quantitative analysis.</p></article>
              <article><i className="ri-table-line" /><code>results/tables/</code><h3>Metadata</h3><p>Position, rotation, confidence, QC status, and reproducibility data.</p></article>
              <article><i className="ri-microscope-line" /><code>qupath/project.qpproj</code><h3>QuPath project</h3><p>Ordered core entries with row, column, QC, and transform metadata.</p></article>
            </div>
          </section>

          <section className="docsSection" id="resume">
            <p className="docsEyebrow">Saved checkpoints</p>
            <h2>Resume or upgrade without starting over</h2>
            <p>The <code>work/</code> folder stores approved state and per-core checkpoints. Run the same script after a correction, interruption, or config output change.</p>
            <div className="docsCallout purple"><i className="ri-history-line" /><div><b>Presentation to Research package</b><span>Create a Research package config, replace the old config, and run again. Accepted detection, rotation, and crop work is reused.</span></div></div>
            <details><summary>CoreAlign stopped after processing</summary><p>Open START-HERE.html. It shows whether the workflow is waiting for grid or orientation review and gives the next action.</p></details>
            <details><summary>A circle or crop is wrong</summary><p>Add the matching correction annotation in QuPath and run again. Other accepted core checkpoints remain unchanged.</p></details>
            <details><summary>OME-TIFF files are missing</summary><p>The current config is probably set to Presentation images. Create a Research package config and rerun the same project folder.</p></details>
          </section>

          <div className="docsEnd"><h2>Ready to prepare a slide?</h2><div><a className="button" href={release}>Download</a><a className="button secondary" href={`${basePath}/config-builder/`}>Create config</a></div></div>
        </article>
      </div>
    </main>
  );
}
