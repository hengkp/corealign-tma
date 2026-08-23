import type { Metadata } from "next";
import SiteHeader from "../site-header";

const repo = "https://github.com/hengkp/corealign-tma";
const release = `${repo}/releases/latest`;
const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

export const metadata: Metadata = {
  title: "CoreAlign Manual",
  description:
    "Run CoreAlign once, answer twice, collect your files. The complete manual for the QuPath TMA workflow.",
};

const steps = [
  {
    number: "1",
    minutes: "1 minute",
    title: "Put three things in one folder",
    body: "Your slide, CoreAlign.groovy, and nothing else. CoreAlign writes every result beside the slide it opened, so the folder you open from is the folder you get back.",
    detail: [
      "Do not open a second copy of the slide from Downloads.",
      "A config file is optional. CoreAlign writes one for you on the first run.",
    ],
  },
  {
    number: "2",
    minutes: "10 seconds",
    title: "Open the slide and run the script",
    body: "In QuPath, open the slide, then Automate, then Show script editor. Open CoreAlign.groovy and press Run.",
    detail: [
      "QuPath 0.7 or newer.",
      "The script is one file. There is nothing to install.",
    ],
  },
  {
    number: "3",
    minutes: "5 seconds",
    title: "Answer two questions and press Start",
    body: "Tissue: Skin keeps the epidermis at the top, Other tissue keeps the strongest outer edge at the top. Results: Images gives PNG files, Images plus research files adds OME-TIFF and a QuPath project.",
    detail: [
      "Rows, columns, core size, and array position are measured from the slide. You are never asked for them.",
      "You can change Results later without repeating any accepted work.",
    ],
  },
  {
    number: "4",
    minutes: "2 to 5 minutes",
    title: "Check the cores CoreAlign found",
    body: "The report opens by itself with the whole slide image and the counts. A bar at the top says QuPath is waiting for you.",
    detail: [
      "If every circle sits on a core, press Grid is correct.",
      "If a core was missed, draw an ellipse over it in QuPath, name it TMA correction, then press Grid is correct. CoreAlign applies it and shows you the refreshed image before going further.",
      "To confirm a genuinely empty position, name an ellipse TMA mark missing.",
    ],
  },
  {
    number: "5",
    minutes: "hands off",
    title: "Let it rotate and crop",
    body: "Each core is located, rotated, then cropped, in that order. Progress is written after every core.",
    detail: [
      "If QuPath closes, run the script again. It resumes from the last saved core.",
      "You can keep using QuPath while this runs.",
    ],
  },
  {
    number: "6",
    minutes: "5 to 15 minutes",
    title: "Check the rotated cores and approve",
    body: "The report fills with one card per core. Cores that passed automatic QC are green, cores that need a look are yellow.",
    detail: [
      "Correct? Press Confirm.",
      "Wrong? Press Edit, drag the angle, press Update. The change saves by itself.",
      "When you are happy, press Approve and finish at the top of the page.",
    ],
  },
  {
    number: "7",
    minutes: "done",
    title: "Collect your files",
    body: "Images land in results/png. Research runs also produce results/ome-tiff and an ordered QuPath project under qupath.",
    detail: [
      "Tables and audit files are in results/tables.",
      "Keep the work folder. It holds the checkpoints that let you upgrade to a research package later without reprocessing.",
    ],
  },
];

const annotations = [
  { name: "TMA correction 4-C", who: "You draw it", what: "Puts a core where CoreAlign missed one, or moves one that landed wrong." },
  { name: "TMA mark missing 14-G", who: "You draw it", what: "Confirms a grid position is genuinely empty." },
  { name: "TMA crop override 4-C", who: "You draw it", what: "Replaces the region CoreAlign chose to crop." },
  { name: "Epidermis override 4-C", who: "You draw it", what: "Points at the true epidermal side when automatic orientation disagrees." },
  { name: "TMA orientation 4-C", who: "CoreAlign draws it", what: "Shows the refined footprint actually used for rotate then crop." },
];

const problems = [
  {
    q: "The report opened but the button says it cannot reach QuPath.",
    a: "Keep QuPath open and press it again. If it still fails, use the Continue button in the small CoreAlign window instead. It does exactly the same thing.",
  },
  {
    q: "I closed the report by accident.",
    a: "Press Open report in the small CoreAlign window, or open REPORT.html from the project folder. The run is still waiting.",
  },
  {
    q: "QuPath closed in the middle of a long run.",
    a: "Open the same slide and run the script again. Completed cores are not processed twice.",
  },
  {
    q: "CoreAlign stopped and said structural QC failed.",
    a: "It found too few cores to trust the array, or a whole row or column empty. Look at the grid image, add TMA correction annotations, and run again.",
  },
  {
    q: "I picked Images and now I need OME-TIFF files.",
    a: "Open the report, switch Results to Research, and run the script once more. Detection, rotation, and crop are reused. Only the missing files are created.",
  },
  {
    q: "I want to change tissue type after starting.",
    a: "Run the script again and change it in the setup dialog. Changing tissue changes how cores are rotated, so orientation is recalculated. The grid is kept.",
  },
];

export default function Guide() {
  return (
    <main className="prosePage">
      <a className="skipLink" href="#content">Skip to content</a>
      <SiteHeader />

      <section className="proseHero" id="content">
        <p className="kicker"><i className="ri-guide-line" /> Manual</p>
        <h1>Run once. Answer twice. Collect your files.</h1>
        <p className="lead">
          CoreAlign detects the array, rotates every core so the tissue faces the same way, crops
          it, and exports it. You are asked two questions before it starts and two questions
          while it works. There is nothing else to learn.
        </p>
        <div className="heroActions">
          <a className="button" href={release}><i className="ri-download-2-line" /> Download CoreAlign</a>
          <a className="button secondary" href={`${basePath}/config-builder/`}><i className="ri-settings-3-line" /> Unusual channel names</a>
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">At a glance</p>
          <h2>The whole workflow.</h2>
        </div>
        <div className="glanceRow">
          <article><span>Run the script</span><i className="ri-arrow-right-line" /></article>
          <article><span>Pick 2 options, press Start</span><i className="ri-arrow-right-line" /></article>
          <article className="glanceWait"><span>Check the grid, press Continue</span><i className="ri-arrow-right-line" /></article>
          <article className="glanceWait"><span>Check the cores, press Approve</span><i className="ri-arrow-right-line" /></article>
          <article className="glanceDone"><span>Files ready</span></article>
        </div>
        <p className="glanceNote">
          <i className="ri-information-line" />
          You never return to the script editor. Both waiting points are answered on the report
          page or in the small CoreAlign window, and the same run continues from your answer.
        </p>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Step by step</p>
          <h2>Seven steps, start to finish.</h2>
        </div>
        <div className="stepList">
          {steps.map((step) => (
            <article key={step.number} className="stepRow">
              <div className="stepMark"><span>{step.number}</span><small>{step.minutes}</small></div>
              <div className="stepBody">
                <h3>{step.title}</h3>
                <p>{step.body}</p>
                <ul>{step.detail.map((line) => <li key={line}>{line}</li>)}</ul>
              </div>
            </article>
          ))}
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Corrections</p>
          <h2>Five annotation names. That is the whole vocabulary.</h2>
          <p>Draw the shape in QuPath, give it the name, and continue. CoreAlign fits it to the array and works out the row and column.</p>
        </div>
        <div className="tableWrap">
          <table className="guideTable">
            <thead>
              <tr><th>Name it</th><th>Who draws it</th><th>What it does</th></tr>
            </thead>
            <tbody>
              {annotations.map((row) => (
                <tr key={row.name}>
                  <td><code>{row.name}</code></td>
                  <td>{row.who}</td>
                  <td>{row.what}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Where things land</p>
          <h2>Your project folder afterwards.</h2>
        </div>
        <pre className="treeBlock">{`my-project/
  slide.ome.tif
  CoreAlign.groovy
  corealign.config.json      written for you on the first run
  REPORT.html                the only page you need
  qc/
    01-grid/                 whole slide detection image and coordinates
    02-orientation/          per core previews and the contact sheet
  results/
    png/                     full resolution images, one per core
    ome-tiff/                research runs only
    tables/                  CSV and JSON audit data
  qupath/                    ordered core project, research runs only
  work/                      checkpoints. keep this folder`}</pre>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">When something goes wrong</p>
          <h2>Six things that actually happen.</h2>
        </div>
        <div className="faqList">
          {problems.map((item) => (
            <article key={item.q} className="faqRow">
              <h3>{item.q}</h3>
              <p>{item.a}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="moodSection">
        <div className="sectionHeading">
          <p className="eyebrow">Before you publish</p>
          <h2>What CoreAlign does not claim.</h2>
        </div>
        <div className="noticeCard">
          <i className="ri-shield-check-line" />
          <div>
            <p>
              CoreAlign is research and quality control software. A finished run means every
              position was accepted by a person and the exact grid and result hashes were
              approved. It does not mean the detection is correct without that review.
            </p>
            <p>
              Independent pathologist review and local validation are required before any
              clinical claim. Do not publish slide data or annotations that identify a patient.
            </p>
          </div>
        </div>
      </section>

      <section className="startCta">
        <div><p className="eyebrow">Ready</p><h2>Open your slide and press Run.</h2></div>
        <a className="button" href={release}>Download latest release <i className="ri-arrow-right-line" /></a>
      </section>

      <footer>
        <a className="siteBrand" href={`${basePath}/`}><span className="brandIcon"><i className="ri-focus-3-line" /></span><span>CoreAlign <b>TMA</b></span></a>
        <p>Research software for TMA image preparation. Review results before clinical use.</p>
        <div>
          <a href={`${basePath}/moodboard/`}>Moodboard</a>
          <a href={`${basePath}/playbook/`}>Playbook</a>
          <a href={`${basePath}/docs/`}>Reference</a>
          <a href={repo}>GitHub</a>
        </div>
      </footer>
    </main>
  );
}
