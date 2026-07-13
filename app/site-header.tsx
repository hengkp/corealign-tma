import ThemeToggle from "./theme-toggle";

const repo = "https://github.com/hengkp/corealign-tma";
const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

export default function SiteHeader() {
  return (
    <header className="siteHeader">
      <a className="siteBrand" href={`${basePath}/`} aria-label="CoreAlign TMA home">
        <span className="brandIcon"><i className="ri-focus-3-line" /></span>
        <span>CoreAlign <b>TMA</b></span>
      </a>
      <nav aria-label="Main navigation">
        <a href={`${basePath}/#workflow`}>How it works</a>
        <a href={`${basePath}/#outputs`}>Outputs</a>
        <a href={`${basePath}/docs/`}>Documentation</a>
      </nav>
      <div className="headerActions">
        <ThemeToggle />
        <a className="textLink" href={repo}>GitHub</a>
        <a className="button small" href={`${basePath}/config-builder/`}>Build config</a>
      </div>
    </header>
  );
}
