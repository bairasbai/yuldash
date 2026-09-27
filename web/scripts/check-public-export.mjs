import { existsSync, readFileSync, statSync } from "node:fs";
import { dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

export const publicPages = ["", "ba", "help", "safety", "privacy", "terms", "delete-account"];

// Build validation may omit the separately supplied APK. A release may not:
// deploy.sh replaces the live directory, including any previously uploaded APK.
export function checkPublicExport(directory, { release = false } = {}) {
  const root = resolve(directory);
  const errors = [];
  const downloads = new Set();
  for (const route of publicPages) {
    const page = resolve(root, route, "index.html");
    if (!existsSync(page)) {
      errors.push(`Missing public page: /${route}`);
      continue;
    }
    const html = readFileSync(page, "utf8");
    for (const match of html.matchAll(/<a\b[^>]*>/gi)) {
      const tag = match[0];
      const href = /\bhref="([^"]*)"/i.exec(tag)?.[1];
      if (href?.startsWith("#") && href.length > 1) {
        const ids = [...html.matchAll(/\bid="([^"]*)"/g)].map((item) => item[1]);
        if (!ids.includes(href.slice(1))) errors.push(`Missing fragment target: ${href} (/${route})`);
      }
      if (!href || !href.startsWith("/") || href.startsWith("//")) continue;
      const pathname = href.split(/[?#]/)[0];
      let target;
      try {
        target = resolve(root, `.${decodeURIComponent(pathname)}`);
      } catch {
        errors.push(`Invalid local link: ${href}`);
        continue;
      }
      if (!target.startsWith(`${root}${sep}`) && target !== root) {
        errors.push(`Link leaves export directory: ${href}`);
        continue;
      }
      const isDownload = /\sdownload(?:\s|=|>)/i.test(tag);
      if (isDownload) {
        downloads.add(pathname);
        if (!release) continue;
      }
      const candidate = existsSync(target) && statSync(target).isDirectory()
        ? resolve(target, "index.html") : target;
      if (!existsSync(candidate) || !statSync(candidate).isFile() || statSync(candidate).size === 0) {
        errors.push(`Missing or empty ${isDownload ? "download" : "local link"}: ${href} (/${route})`);
      }
    }
  }
  return { errors: [...new Set(errors)], downloads: [...downloads] };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const directory = resolve(dirname(fileURLToPath(import.meta.url)), "../out");
  const release = process.argv.includes("--release");
  const result = checkPublicExport(directory, { release });
  if (result.errors.length) {
    console.error(result.errors.join("\n"));
    process.exitCode = 1;
  } else {
    console.log(`Public export OK (${publicPages.length} routes).`);
    if (!release && result.downloads.length) {
      console.log(`Downloads require separate release validation: ${result.downloads.join(", ")}`);
    }
  }
}
