import SiteHeader from "./site-header";

const repo = "https://github.com/hengkp/corealign-tma";
const release = `${repo}/releases/latest`;
const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

const workflow = [
  {
    icon: "ri-folder-open-line",
    title: "Prepare one folder",
    text: "Add your slide and CoreAlign.groovy. A config is optional.",
  },
  {
    icon: "ri-play-circle-line",
    title: "Run in QuPath",
    text: "CoreAlign finds the array, estimates core size, rotates each core, then crops it.",
  },
  {
    icon: "ri-eye-line",
    title: "Review and use",
    text: "Open REPORT.html, check flagged cores, and continue with the saved results.",
  },
];

export default function Home() {
  return (
    <main>
      <a className="skipLink" href="#content">Skip to content</a>
      <SiteHeader />

      <section className="homeHero" id="content">
        <div className="heroCopy">
          <p className="kicker"><i className="ri-microscope-line" /> TMA preparation for QuPath</p>
          <h1>Aligned TMA cores. Less repetitive work.</h1>
          <p className="lead">Detect, rotate, and crop every core in QuPath. Review only the flagged results.</p>
          <div className="heroActions">
            <a className="button" href={release}><i className="ri-download-2-line" /> Download CoreAlign</a>
            <a className="button secondary" href={`${basePath}/config-builder/`}><i className="ri-magic-line" /> Create a config</a>
          </div>
          <div className="heroNote"><i className="ri-shield-check-line" /><span><b>Human checked.</b> Work is saved after each core, so you can correct and continue.</span></div>
        </div>

        <div className="heroArtwork" aria-label="Illustration of TMA core detection, rotation, and crop">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="heroArtLight" src={`${basePath}/images/corealign-hero-v2-light.webp`} width="1693" height="929" alt="Synthetic TMA cores moving through detection, rotation, and crop" />
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="heroArtDark" src={`${basePath}/images/corealign-hero-v2-dark.webp`} width="1692" height="929" alt="Synthetic TMA cores moving through detection, rotation, and crop in dark mode" />
        </div>
      </section>

      <section className="homeSection workflowSection" id="workflow">
        <div className="sectionHeading">
          <p className="eyebrow">How it works</p>
          <h2>One script. Three clear steps.</h2>
          <p>Start with the automatic settings. CoreAlign pauses only when your review is needed.</p>
        </div>
        <div className="workflowCards">
          {workflow.map((step, index) => (
            <article key={step.title}>
              <div className={`stepIcon color${index + 1}`}><i className={step.icon} /></div>
              <span>Step {index + 1}</span>
              <h3>{step.title}</h3>
              <p>{step.text}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="homeSection outputBand" id="outputs">
        <div className="sectionHeading compact">
          <p className="eyebrow">Choose your output</p>
          <h2>Ready for your next task.</h2>
        </div>
        <div className="outputCards">
          <article>
            <i className="ri-slideshow-3-line" />
            <div><h3>Presentation images</h3><p>Full resolution PNG files with shared slide-level color grading.</p><code>results/png/</code></div>
          </article>
          <article>
            <i className="ri-stack-line" />
            <div><h3>Research package</h3><p>Multichannel OME-TIFF files plus an ordered QuPath core project.</p><code>results/ome-tiff/</code></div>
          </article>
        </div>
      </section>

      <section className="startCta">
        <div><p className="eyebrow">Ready to start</p><h2>Download the script, open your slide, and press Run.</h2></div>
        <a className="button" href={release}>Download latest release <i className="ri-arrow-right-line" /></a>
      </section>

      <footer>
        <a className="siteBrand" href={`${basePath}/`}><span className="brandIcon"><i className="ri-focus-3-line" /></span><span>CoreAlign <b>TMA</b></span></a>
        <p>Research software for TMA image preparation. Review results before clinical use.</p>
        <div><a href={`${basePath}/docs/`}>Guide</a><a href={`${basePath}/config-builder/`}>Config</a><a href={repo}>GitHub</a></div>
      </footer>
    </main>
  );
}
