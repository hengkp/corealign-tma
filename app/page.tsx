const repo = "https://github.com/hengkp/corealign-tma";
const release = `${repo}/releases/tag/v1.3.0`;
const tutorialVideo = `${repo}/releases/download/v1.2.0/CoreAlign-TMA-tutorial-v3-1080p.mp4`;
import SiteHeader from "./site-header";

const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";
const path = (value: string) => `${basePath}${value}`;

const validation = [
  ["126", "grid positions"],
  ["117", "tissue cores"],
  ["9", "empty positions"],
  ["0", "run errors"],
];

const steps = [
  { icon: "ri-folder-open-line", number: "01", title: "Put two files together", text: "Place the slide and CoreAlign.groovy in one folder. Config is optional." },
  { icon: "ri-play-circle-line", number: "02", title: "Press Run", text: "CoreAlign detects rows, columns, and the array automatically." },
  { icon: "ri-crop-2-line", number: "03", title: "Review and export", text: "Each core is rotated first, cropped second, and saved automatically." },
];

const outputs = [
  { icon: "ri-timer-flash-line", label: "Less manual work", title: "Batch preparation", text: "Process the full array and resume from the last completed core." },
  { icon: "ri-slideshow-3-line", label: "For presentations", title: "Full resolution PNG", text: "Ready for PowerPoint, figures, and contact sheets." },
  { icon: "ri-stack-line", label: "When all channels matter", title: "Multichannel OME-TIFF", text: "Optional UINT16 export for downstream analysis and archive." },
];

export default function Home() {
  return (
    <main>
      <a className="skipLink" href="#content">Skip to content</a>
      <SiteHeader />

      <section className="brandHero" id="content">
        <h1 className="srOnly">CoreAlign TMA for QuPath</h1>
        <figure className="brandHeroFrame reveal">
          {/* Generated illustrations contain no real specimen data. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="heroLightImage" src={path("/images/corealign-hero-light.webp")} alt="Generated CoreAlign TMA illustration for light mode" />
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="heroDarkImage" src={path("/images/corealign-hero-dark.webp")} alt="Generated CoreAlign TMA illustration for dark mode" />
          <figcaption><i className="ri-sparkling-2-line" /> Generated example</figcaption>
        </figure>
        <div className="heroSummary reveal delayOne">
          <div>
            <p className="kicker"><i className="ri-microscope-line" /> TMA preparation in QuPath</p>
            <h2>Prepare TMA cores for analysis and presentation.</h2>
            <p>Detect every core, rotate skin with epidermis at the top, and crop at source resolution. Review only the results that need attention.</p>
          </div>
          <div className="heroSummaryActions">
            <div className="heroActions">
              <a className="button" href={release}><i className="ri-download-2-line" /> Download</a>
              <a className="button ghost" href={path("/config-builder/")}><i className="ri-settings-3-line" /> Optional config</a>
            </div>
            <div className="quickStart"><i className="ri-file-code-line" /><div><b>One script</b><span>Slide + CoreAlign.groovy</span></div></div>
          </div>
        </div>
      </section>

      <section className="validationStrip" aria-label="Reference slide test result">
        <div className="validationLabel"><i className="ri-shield-check-line" /> Tested reference slide</div>
        {validation.map(([value, label]) => <div className="metric" key={label}><strong>{value}</strong><span>{label}</span></div>)}
      </section>

      <section className="section workflowSection" id="workflow">
        <div className="sectionIntro compactIntro">
          <p className="eyebrow">Simple workflow</p>
          <h2>From slide to aligned cores in three steps.</h2>
        </div>
        <div className="stepGrid threeSteps">
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
          <p className="eyebrow">Useful outputs</p>
          <h2>Choose speed or a full research archive.</h2>
        </div>
        <div className="outputGrid threeOutputs">
          {outputs.map((output) => (
            <article key={output.title}>
              <div className="outputIcon"><i className={output.icon} /></div>
              <div><p>{output.label}</p><h3>{output.title}</h3><span>{output.text}</span></div>
            </article>
          ))}
        </div>
      </section>

      <section className="section tutorialSection" id="tutorial">
        <div className="tutorialPlayer">
          <video controls preload="metadata" poster={path("/images/corealign-hero-dark.webp")}>
            <source src={tutorialVideo} type="video/mp4" />
            Your browser does not support embedded video.
          </video>
          <div><span><i className="ri-play-circle-line" /> 4 minutes 9 seconds</span><span>English narration, English and Thai subtitles</span></div>
        </div>
        <div className="tutorialCopy">
          <p className="eyebrow">Video tutorial</p>
          <h2>Watch the complete workflow in four minutes.</h2>
          <p>Download, press Run, review, and export. Version 1.3 removes row and column setup; the video remains useful for QuPath review and export steps.</p>
          <a className="button" href={tutorialVideo}><i className="ri-play-line" /> Watch or download</a>
        </div>
      </section>

      <section className="section validationSection" id="review">
        <div>
          <p className="eyebrow">Built-in safety</p>
          <h2>Automation does the repetitive work. A person confirms the result.</h2>
        </div>
        <div className="validationCopy">
          <p>CoreAlign pauses after grid detection and flags uncertain orientations. Completed cores are saved, so corrections do not restart the full array.</p>
          <div><i className="ri-eye-line" /><span><b>Quick review</b> Check the grid and flagged cores only.</span></div>
          <div><i className="ri-save-3-line" /><span><b>Safe resume</b> Continue from the last completed core.</span></div>
        </div>
      </section>

      <section className="finalCta">
        <div><p className="eyebrow">Start here</p><h2>Download one script and press Run in QuPath.</h2></div>
        <div>
          <a className="button light" href={release}>Download CoreAlign <i className="ri-arrow-right-line" /></a>
          <a className="button outlineLight" href={path("/config-builder/")}>Optional config</a>
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
