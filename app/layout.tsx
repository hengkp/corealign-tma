import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "CoreAlign TMA — Automated rotate-then-crop for QuPath",
  description:
    "Bilingual documentation for reproducible TMA detection, per-core skin orientation, rotate-then-crop export, and human review in QuPath.",
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
  openGraph: {
    title: "CoreAlign TMA",
    description: "Rotate first. Crop second. Review what matters.",
    images: [{ url: "/og.png", width: 1743, height: 909 }],
  },
  twitter: {
    card: "summary_large_image",
    title: "CoreAlign TMA",
    description: "Rotate first. Crop second. Review what matters.",
    images: ["/og.png"],
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
        <link rel="preconnect" href="https://cdn.jsdelivr.net" />
        <link
          rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/remixicon@4.9.1/fonts/remixicon.css"
        />
      </head>
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        {children}
      </body>
    </html>
  );
}
