import SiteHeader from "../site-header";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";
const release = "https://github.com/hengkp/corealign-tma/releases/latest";

const toc = [
  ["overview", "Overview"],
  ["project-folder", "Project folder"],
  ["run", "Run CoreAlign"],
  ["grid-qc", "Grid QC"],
  ["orientation-qc", "Orientation QC"],
  ["results", "Results"],
  ["qupath", "QuPath project"],
  ["resume", "Resume and upgrade"],
  ["troubleshooting", "Troubleshooting"],
];

const resultFolders = [
  { icon: "ri-image-line", path: "results/png/", title: "Presentation PNG", text: "Final full resolution RGB images. Each core is rotated first and cropped second." },
  { icon: "ri-stack-line", path: "results/ome-tiff/", title: "Research OME-TIFF", text: "Rotated multichannel core images for quantitative analysis. Created in Research package mode." },
  { icon: "ri-table-line", path: "results/tables/", title: "Tables and metadata", text: "Per-core coordinates, rotation, confidence, QC status, and reproducibility metadata." },
  { icon: "ri-slideshow-3-line", path: "results/presentation/", title: "Optional panels", text: "Config-driven comparison panels when presentation mapping is enabled." },
];

export default function Documentation() {
  return (
    <main className="docsPage">
      <a className="skipLink" href="#docs-content">Skip to content</a>
      <SiteHeader />
      <div className="docsShell">
        <aside className="docsToc" aria-label="Documentation table of contents">
          <p>Documentation</p>
          <nav>
            {toc.map(([id, label]) => <a href={`#${id}`} key={id}>{label}</a>)}
          </nav>
          <a className="docsDownload" href={release}><i className="ri-download-2-line" /> Download</a>
        </aside>

        <article className="docsContent" id="docs-content">
          <header className="docsHero" id="overview">
            <p className="kicker"><i className="ri-book-open-line" /> CoreAlign guide</p>
            <h1>One project folder. Clear results at every step.</h1>
            <p>CoreAlign organizes detection, orientation, export, and analysis files so you can find the current result without opening technical run folders.</p>
            <div className="docsQuickLinks">
              <a href="#project-folder"><i className="ri-folder-3-line" /> See the folder layout</a>
              <a href="#run"><i className="ri-play-circle-line" /> Run the workflow</a>
            </div>
          </header>

          <section className="docsSection" id="project-folder">
            <p className="docsEyebrow">Start here</p>
            <h2>Project folder</h2>
            <p>Use one folder for one slide. Keep the slide, script, config, and generated folders together.</p>
            <div className="folderTree" aria-label="CoreAlign project folder structure"><pre>{`my-project/
|-- slide.ome.tif
|-- CoreAlign.groovy
|-- corealign.config.json
|-- START-HERE.html
|-- PROJECT-README.txt
|-- qc/
|   |-- 01-grid/
|   +-- 02-orientation/
|-- results/
|   |-- png/
|   |-- ome-tiff/
|   |-- tables/
|   +-- presentation/
|-- qupath/
+-- work/`}</pre></div>
            <div className="docsCallout"><i className="ri-home-4-line" /><div><b>Open START-HERE.html first</b><span>It shows the current stage and links directly to the latest QC, results, and QuPath project.</span></div></div>
          </section>

          <section className="docsSection" id="run">
            <p className="docsEyebrow">Workflow</p>
            <h2>Run CoreAlign</h2>
            <div className="docsSteps">
              <article><span>1</span><div><h3>Prepare</h3><p>Put the slide and CoreAlign.groovy in the same folder. Add one optional config file.</p></div></article>
              <article><span>2</span><div><h3>Detect</h3><p>Open the slide in QuPath and run CoreAlign.groovy. Review the circles in qc/01-grid.</p></div></article>
              <article><span>3</span><div><h3>Orient</h3><p>Run the same script again after grid approval. Review flagged cores in qc/02-orientation.</p></div></article>
              <article><span>4</span><div><h3>Approve</h3><p>Confirm the final review. Use the images in results or open the project in qupath.</p></div></article>
            </div>
          </section>

          <section className="docsSection" id="grid-qc">
            <p className="docsEyebrow">Step 1 output</p>
            <h2>qc/01-grid</h2>
            <p>This folder answers one question: did CoreAlign find the correct TMA positions?</p>
            <div className="docsFileList">
              <div><code>*_grid_qc_latest.png</code><span>Whole-slide view with circles, labels, and connecting lines.</span></div>
              <div><code>*_grid_coordinates_latest.csv</code><span>Current row, column, center, diameter, missing state, and detection source.</span></div>
              <div><code>*_structural_qc.json</code><span>Row and column coverage checks used before approval.</span></div>
            </div>
            <div className="docsLegend"><span className="cyan">Automatic</span><span className="green">Human corrected</span><span className="red">Missing</span></div>
          </section>

          <section className="docsSection" id="orientation-qc">
            <p className="docsEyebrow">Step 2 output</p>
            <h2>qc/02-orientation</h2>
            <p>This folder contains the latest rotation and crop review. Start with the report, then inspect only the flagged cores.</p>
            <div className="docsFileList">
              <div><code>run_report.html</code><span>Run status, counts, duration, flagged cores, and next action.</span></div>
              <div><code>review.html</code><span>Filterable visual review of each rotated core.</span></div>
              <div><code>orientation_contact_sheet.png</code><span>All core positions in row and column order.</span></div>
              <div><code>rotated_previews/</code><span>Small images used by the visual review page.</span></div>
            </div>
          </section>

          <section className="docsSection" id="results">
            <p className="docsEyebrow">Final files</p>
            <h2>results</h2>
            <p>Open only the folder that matches your next task.</p>
            <div className="resultFolderGrid">
              {resultFolders.map((folder) => (
                <article key={folder.path}>
                  <i className={folder.icon} />
                  <code>{folder.path}</code>
                  <h3>{folder.title}</h3>
                  <p>{folder.text}</p>
                </article>
              ))}
            </div>
          </section>

          <section className="docsSection" id="qupath">
            <p className="docsEyebrow">Analysis</p>
            <h2>qupath</h2>
            <p>Research package mode creates <code>qupath/project.qpproj</code> after final human approval. Each non-missing core is a separate ordered image entry with row, column, QC, and transform metadata.</p>
            <ol className="docsSimpleList">
              <li>In QuPath, choose File, Project, Open project.</li>
              <li>Select <code>qupath/project.qpproj</code>.</li>
              <li>Use the core entries for downstream analysis.</li>
            </ol>
          </section>

          <section className="docsSection" id="resume">
            <p className="docsEyebrow">No repeated work</p>
            <h2>Resume and upgrade</h2>
            <p>The <code>work/</code> folder stores approved state and per-core checkpoints. Run the same script again after a stop or correction. CoreAlign processes only changed or incomplete cores.</p>
            <div className="docsCallout violet"><i className="ri-history-line" /><div><b>PNG to Research package</b><span>Replace the config with a Research package config and run again. Existing detection, rotation, and crop checkpoints are reused.</span></div></div>
            <p className="docsSmall">Projects made with an older CoreAlign version can still read legacy tma_* folders. New files are published into the clear folder layout above.</p>
          </section>

          <section className="docsSection" id="troubleshooting">
            <p className="docsEyebrow">Quick checks</p>
            <h2>Troubleshooting</h2>
            <details><summary>START-HERE.html says Grid ready for review</summary><p>Inspect qc/01-grid in QuPath. Correct missed or misplaced circles, then run CoreAlign.groovy again.</p></details>
            <details><summary>A core rotation or crop is wrong</summary><p>Add the documented orientation or crop override to that core and run again. Other accepted checkpoints are preserved.</p></details>
            <details><summary>results/ome-tiff is empty</summary><p>Your config is set to Presentation images. Create a Research package config and run the same slide folder again.</p></details>
            <details><summary>The workflow stopped</summary><p>Open qc/02-orientation/run_report.html. It explains whether the stop is a planned review gate or an error and lists the next action.</p></details>
          </section>

          <div className="docsEnd">
            <h2>Ready to prepare a slide?</h2>
            <a className="button" href={release}>Download CoreAlign</a>
            <a className="button ghost" href={`${basePath}/config-builder/`}>Create config</a>
          </div>
        </article>
      </div>
    </main>
  );
}
