import SiteHeader from "../site-header";
import DocsToc from "./docs-toc";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";
const release = "https://github.com/hengkp/corealign-tma/releases/latest";

const toc = [
  { id: "overview", label: "Overview", tone: "toneBlue" },
  { id: "run", label: "Run CoreAlign", tone: "toneCoral" },
  { id: "review", label: "Review results", tone: "toneMint" },
  { id: "correct", label: "Correct a core", tone: "tonePurple" },
  { id: "results", label: "Find your files", tone: "toneCyan" },
  { id: "resume", label: "Resume or upgrade", tone: "toneYellow" },
] as const;

export default function Documentation() {
  return (
    <main className="docsPage" id="docs-top">
      <a className="skipLink" href="#docs-content">Skip to content</a>
      <SiteHeader />
      <div className="docsShell">
        <DocsToc toc={toc} release={release} />

        <article className="docsContent" id="docs-content">
          <header className="docsHero sectionBlue" id="overview">
            <p className="kicker"><i className="ri-book-open-line" /> Documentation</p>
            <h1>Prepare TMA cores in one repeatable workflow.</h1>
            <p>CoreAlign runs inside QuPath. It finds the array, rotates each core before cropping, and keeps every result in one project folder. <code>REPORT.html</code> shows what finished and what needs your review.</p>
            <div className="docsActions"><a className="button" href={release}>Download CoreAlign</a><a className="button secondary" href={`${basePath}/config-builder/`}>Create a config</a></div>
          </header>

          <section className="docsSection sectionCoral" id="run">
            <div className="docsSectionTitle"><span><i className="ri-play-circle-line" /></span><div><p className="docsEyebrow">Quick start</p><h2>Run CoreAlign</h2></div></div>
            <p>Make one folder for one slide. This keeps the script, review page, images, and checkpoints together.</p>
            <div className="folderTree"><pre>{`my-project/
|-- slide.ome.tif
|-- CoreAlign.groovy
|-- corealign.config.json   optional
|-- REPORT.html
|-- corealign-review-corrections.json   created after an angle edit
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
              <article><span>1</span><div><h3>Prepare the folder</h3><p>Put your slide and <code>CoreAlign.groovy</code> together. Add <code>corealign.config.json</code> only when you want custom output settings.</p></div></article>
              <article><span>2</span><div><h3>Press Run in QuPath</h3><p>Open the slide, open <code>CoreAlign.groovy</code> in Script Editor, then press Run. The Run summary tells you what will happen before any work starts.</p></div></article>
              <article><span>3</span><div><h3>Follow REPORT.html</h3><p>Open the report when CoreAlign pauses or finishes. Review only the requested items, then run the same script again to continue.</p></div></article>
            </div>
            <div className="docsCallout"><i className="ri-information-line" /><div><b>Read the Run summary</b><span>It identifies a first run, correction, resume, or output upgrade. It also lists the files that will be created and the accepted work that will be reused. Click OK to continue or Cancel to leave everything unchanged.</span></div></div>
            <div className="docsCallout purple"><i className="ri-speed-up-line" /><div><b>Safe parallel processing</b><span>Presentation PNG runs process up to two cores at once. QuPath annotations are updated in order after image work finishes. Research OME-TIFF export uses one worker to protect memory.</span></div></div>
          </section>

          <section className="docsSection sectionMint" id="review">
            <div className="docsSectionTitle"><span><i className="ri-eye-line" /></span><div><p className="docsEyebrow">Your project dashboard</p><h2>Review results</h2></div></div>
            <p><code>REPORT.html</code> is the only workflow HTML file you need. It opens without internet access and always shows the latest Grid QC, Orientation QC, files, and next action.</p>
            <div className="featureList">
              <div><i className="ri-layout-grid-line" /><span><b>Grid QC</b> The complete array opens in Fit view. Check that every ellipse covers the intended core. Missing positions should be empty locations, not undetected tissue.</span></div>
              <div><i className="ri-refresh-line" /><span><b>Orientation QC</b> Compare Before and Rotated. Missing positions use a synthetic black no-core image. Filters are All cores, QC pass, Missing, Needs review, and Changes.</span></div>
              <div><i className="ri-folder-open-line" /><span><b>Results</b> Open presentation PNG files, research OME-TIFF files, metadata tables, or the ordered QuPath project.</span></div>
            </div>
            <p className="docsSmall">Click Confirm when a rotation is correct. A confirmed edit moves to Changes. Undo restores the original angle and removes the change. Confirm all QC pass accepts every green card at once. For a local report, Chrome or Edge asks for the project folder once and remembers it. AppHub saves through its report preview. QuPath can stay closed until you are ready to run CoreAlign again.</p>
          </section>

          <section className="docsSection sectionPurple" id="correct">
            <div className="docsSectionTitle"><span><i className="ri-edit-circle-line" /></span><div><p className="docsEyebrow">Human correction in QuPath</p><h2>Correct one core</h2></div></div>
            <p>Correct only the affected position. Use the row-column code shown in <code>REPORT.html</code>, such as <code>4-C</code>. Unchanged cores keep their accepted checkpoints.</p>
            <div className="nameTable">
              <div><code>TMA orientation 4-C</code><span>Automatic ellipse for the refined core footprint.</span></div>
              <div><code>TMA correction 4-C</code><span>Correct a missed or misplaced detection.</span></div>
              <div><code>TMA mark missing 4-C</code><span>Mark a truly empty position.</span></div>
              <div><code>TMA crop override 4-C</code><span>Replace the crop footprint for one core.</span></div>
              <div><code>Epidermis override 4-C</code><span>Mark the true epidermal side for one skin core.</span></div>
            </div>
            <ol className="docsSimpleList">
              <li>In QuPath, select the wrong ellipse or draw a new ellipse over the correct core.</li>
              <li>Name it with the required action and position, for example <code>TMA correction 4-C</code>.</li>
              <li>Run <code>CoreAlign.groovy</code> again. CoreAlign refreshes the complete QC report and reprocesses only the changed core.</li>
            </ol>
            <div className="docsCallout purple"><i className="ri-refresh-line" /><div><b>Review a rotation</b><span>Click Confirm if the angle is correct. If it is wrong, click Edit, move the angle slider, then click Confirm. A Saved message confirms that the correction is in the project folder. Open QuPath and run the script when you are ready to update the image.</span></div></div>
          </section>

          <section className="docsSection sectionCyan" id="results">
            <div className="docsSectionTitle"><span><i className="ri-folder-open-line" /></span><div><p className="docsEyebrow">Easy-to-find outputs</p><h2>Find your files</h2></div></div>
            <p>Each output has one clear destination. CoreAlign never mixes temporary processing files with presentation or research results.</p>
            <div className="resultGrid">
              <article><i className="ri-image-line" /><code>results/png/</code><h3>Presentation images</h3><p>Full resolution PNG files. One display range is shared by every core from the slide.</p></article>
              <article><i className="ri-stack-line" /><code>results/ome-tiff/</code><h3>Research images</h3><p>Rotated multichannel OME-TIFF files for quantitative analysis.</p></article>
              <article><i className="ri-table-line" /><code>results/tables/</code><h3>Metadata</h3><p>Position, rotation, confidence, QC status, and reproducibility data.</p></article>
              <article><i className="ri-microscope-line" /><code>qupath/project.qpproj</code><h3>QuPath project</h3><p>Ordered core entries with row, column, QC, and transform metadata.</p></article>
            </div>
            <div className="docsCallout"><i className="ri-palette-line" /><div><b>Choose markers for a slide</b><span>Leave Presentation PNG markers blank for automatic color selection. To show specific markers, open the Config Builder and enter channel names such as DAPI, PanCK, Ki67. CoreAlign applies the same colors and display ranges to every core from that slide.</span></div></div>
            <div className="docsCallout purple"><i className="ri-bar-chart-box-line" /><div><b>Compare intensity correctly</b><span>PNG grading supports visual comparison between cores from the same slide. Do not measure intensity from PNG files or compare auto-graded PNG files across slides. Use the original multichannel OME-TIFF data, consistent acquisition settings, controls, and a validated normalization method for quantitative analysis.</span></div></div>
            <div className="docsCallout purple"><i className="ri-toggle-line" /><div><b>Presentation or Research</b><span>Open Results in <code>REPORT.html</code> and select Research. Choose the project folder once and the browser updates the config directly. QuPath does not need to stay open. Run CoreAlign again to create multichannel OME-TIFF files using the accepted transforms.</span></div></div>
          </section>

          <section className="docsSection sectionYellow" id="resume">
            <div className="docsSectionTitle"><span><i className="ri-history-line" /></span><div><p className="docsEyebrow">Saved checkpoints</p><h2>Resume or upgrade</h2></div></div>
            <p>The <code>work/</code> folder stores the accepted grid, rotation, crop, and per-core checkpoints. Keep this folder when correcting a core, restarting after an interruption, or upgrading the output.</p>
            <div className="docsCallout purple"><i className="ri-history-line" /><div><b>Presentation to Research package</b><span>Open Results in REPORT.html, choose Research, and run again. Accepted detection, rotation, and crop work is reused.</span></div></div>
            <details><summary>CoreAlign stopped after processing</summary><p>Open REPORT.html. It shows whether the workflow is waiting for grid or orientation review and gives the next action.</p></details>
            <details><summary>A circle or crop is wrong</summary><p>Add the matching correction annotation in QuPath and run again. Other accepted core checkpoints remain unchanged.</p></details>
            <details><summary>OME-TIFF files are missing</summary><p>Open Results in REPORT.html, choose Research, and run CoreAlign again.</p></details>
          </section>

          <div className="docsEnd"><h2>Ready to prepare a slide?</h2><div><a className="button" href={release}>Download</a><a className="button secondary" href={`${basePath}/config-builder/`}>Create config</a></div></div>
        </article>
      </div>
    </main>
  );
}
