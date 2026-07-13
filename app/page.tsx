const repo = "https://github.com/hengkp/corealign-tma";
const release = `${repo}/releases/latest`;
import SiteHeader from "./site-header";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";
const path = (value: string) => `${basePath}${value}`;

const steps = [
  { icon: "ri-folder-open-line", number: "1", title: "Put the files together", text: "Keep the slide, CoreAlign.groovy, and optional config in one folder." },
  { icon: "ri-play-circle-line", number: "2", title: "Press Run in QuPath", text: "CoreAlign finds the array, core size, rows, columns, and useful channels." },
  { icon: "ri-eye-line", number: "3", title: "Check the result", text: "Confirm the grid and review only the cores that CoreAlign flags." },
  { icon: "ri-crop-2-line", number: "4", title: "Use the aligned images", text: "Each core is rotated first, cropped second, and saved at source quality." },
];

const outputs = [
  { icon: "ri-slideshow-3-line", label: "For slides and figures", title: "Full resolution PNG", text: "Fast, lossless images ready for PowerPoint, figures, and contact sheets." },
  { icon: "ri-stack-line", label: "For downstream analysis", title: "QuPath core project", text: "Optional rotated OME-TIFF files, ordered by row and column with QC metadata." },
];

export default function Home() {
  return (
    <main>
      <a className="skipLink" href="#content">Skip to content</a>
      <SiteHeader />

      <section className="brandHero simpleHero" id="content">
        <h1 className="srOnly">CoreAlign TMA for QuPath</h1>
        <figure className="brandHeroFrame reveal">
          {/* Generated illustrations contain no real specimen data. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="heroLightImage" src={path("/images/corealign-hero-light.webp")} alt="Generated example of aligned TMA cores" />
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="heroDarkImage" src={path("/images/corealign-hero-dark.webp")} alt="Generated example of aligned TMA cores in dark mode" />
          <figcaption><i className="ri-sparkling-2-line" /> Generated example</figcaption>
        </figure>
        <div className="heroSummary reveal delayOne">
          <div>
            <p className="kicker"><i className="ri-microscope-line" /> TMA preparation for QuPath</p>
            <h2>Turn one TMA slide into aligned core images.</h2>
            <p>CoreAlign finds each core, rotates it consistently, then crops and saves it. You check the result instead of repeating the same work by hand.</p>
          </div>
          <div className="heroSummaryActions">
            <div className="heroActions">
              <a className="button" href={release}><i className="ri-download-2-line" /> Download CoreAlign</a>
              <a className="button ghost" href={path("/config-builder/")}><i className="ri-settings-3-line" /> Create config</a>
            </div>
            <div className="quickStart"><i className="ri-timer-flash-line" /><div><b>Less time at the computer</b><span>No row or column setup for a normal run</span></div></div>
          </div>
        </div>
      </section>

      <section className="section workflowSection" id="workflow">
        <div className="sectionIntro compactIntro">
          <p className="eyebrow">How it works</p>
          <h2>Four steps. One script.</h2>
          <p>Start with the automatic settings. CoreAlign pauses when a person needs to check the result.</p>
        </div>
        <figure className="workflowVisual">
          {/* GPT Image 2 generated visual. No real specimen data. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={path("/images/corealign-workflow-v1.webp")} alt="Generated four-stage CoreAlign workflow from TMA slide to aligned core files" />
          <figcaption>Generated workflow visual. No patient or specimen data.</figcaption>
        </figure>
        <div className="stepGrid fourSteps compactSteps">
          {steps.map((step) => (
            <article className="stepCard" key={step.number}>
              <div><span>{step.number}</span><i className={step.icon} /></div>
              <h3>{step.title}</h3>
              <p>{step.text}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="section outputSection" id="outputs">
        <div className="sectionIntro compactIntro">
          <p className="eyebrow">What you get</p>
          <h2>Choose the files you need.</h2>
        </div>
        <div className="outputGrid simpleOutputs">
          {outputs.map((output) => (
            <article key={output.title}>
              <div className="outputIcon"><i className={output.icon} /></div>
              <div><p>{output.label}</p><h3>{output.title}</h3><span>{output.text}</span></div>
            </article>
          ))}
        </div>
      </section>

      <section className="section validationSection compactSafety" id="review">
        <div>
          <p className="eyebrow">You stay in control</p>
          <h2>Automation prepares the images. You approve the result.</h2>
        </div>
        <div className="validationCopy">
          <p>CoreAlign saves completed work after every core and stops when the grid or orientation needs attention.</p>
          <div><i className="ri-eye-line" /><span><b>Quick review</b> Check the grid and flagged cores before export.</span></div>
          <div><i className="ri-save-3-line" /><span><b>Safe resume</b> Continue without starting the whole slide again.</span></div>
        </div>
      </section>

      <section className="finalCta">
        <div><p className="eyebrow">Start here</p><h2>Download one script and open your slide in QuPath.</h2></div>
        <div>
          <a className="button light" href={release}>Download CoreAlign <i className="ri-arrow-right-line" /></a>
          <a className="button outlineLight" href={path("/config-builder/")}>Create config</a>
        </div>
      </section>

      <footer>
        <a className="siteBrand" href={path("/")}><span className="brandIcon"><i className="ri-focus-3-line" /></span><span>CoreAlign <b>TMA</b></span></a>
        <p>Research software for TMA image preparation. Review results before clinical use.</p>
        <div><a href={repo}>GitHub</a><a href={`${repo}/blob/main/README.md`}>Guide</a><a href={release}>Download</a></div>
      </footer>
    </main>
  );
}
