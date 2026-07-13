"use client";

export default function ThemeToggle() {
  function toggleTheme() {
    const root = document.documentElement;
    const nextTheme = root.dataset.theme === "dark" ? "light" : "dark";
    root.dataset.theme = nextTheme;
    root.style.colorScheme = nextTheme;
    localStorage.setItem("corealign-theme", nextTheme);
  }

  return (
    <button
      className="themeToggle"
      type="button"
      aria-label="Toggle color theme"
      title="Toggle color theme"
      onClick={toggleTheme}
    >
      <i className="ri-moon-clear-line themeMoon" aria-hidden="true" />
      <i className="ri-sun-line themeSun" aria-hidden="true" />
      <span>Theme</span>
    </button>
  );
}
