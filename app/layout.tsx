import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL("https://hengkp.github.io/corealign-tma/"),
  title: "CoreAlign TMA | QuPath rotate then crop workflow",
  description:
    "Detect, review, rotate, and crop each TMA core with a configurable QuPath workflow.",
  icons: {
    icon: "https://hengkp.github.io/corealign-tma/favicon.svg",
    shortcut: "https://hengkp.github.io/corealign-tma/favicon.svg",
  },
  openGraph: {
    title: "CoreAlign TMA",
    description: "Rotate first. Crop second. Review what matters.",
    url: "https://hengkp.github.io/corealign-tma/",
    siteName: "CoreAlign TMA",
    images: [{ url: "https://hengkp.github.io/corealign-tma/og.png", width: 1536, height: 1024 }],
  },
  twitter: {
    card: "summary_large_image",
    title: "CoreAlign TMA",
    description: "Rotate first. Crop second. Review what matters.",
    images: ["https://hengkp.github.io/corealign-tma/og.png"],
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html: `(function(){try{var saved=localStorage.getItem("corealign-theme");var theme=saved==="dark"||saved==="light"?saved:(window.matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light");document.documentElement.dataset.theme=theme;document.documentElement.style.colorScheme=theme;}catch(error){document.documentElement.dataset.theme="light";document.documentElement.style.colorScheme="light";}})();`,
          }}
        />
        <link rel="preconnect" href="https://cdn.jsdelivr.net" />
        <link
          rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/remixicon@4.9.1/fonts/remixicon.css"
        />
      </head>
      <body>{children}</body>
    </html>
  );
}
