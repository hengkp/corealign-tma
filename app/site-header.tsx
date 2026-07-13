import ThemeToggle from "./theme-toggle";

const repo = "https://github.com/hengkp/corealign-tma";
const release = `${repo}/releases/latest`;
const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? "";

export default function SiteHeader() {
  return (
    <header className="siteHeader">
      <a className="siteBrand" href={`${basePath}/`} aria-label="CoreAlign TMA home">
        <span className="brandIcon"><i className="ri-focus-3-line" /></span>
        <span>CoreAlign <b>TMA</b></span>
      </a>
      <nav aria-label="Main navigation">
        <a href={`${basePath}/docs/`}>Guide</a>
        <a href={`${basePath}/config-builder/`}>Config</a>
        <a href={repo}>GitHub</a>
      </nav>
      <div className="headerActions">
        <ThemeToggle />
        <a className="button small" href={release}><i className="ri-download-2-line" /> Download</a>
      </div>
    </header>
  );
}
