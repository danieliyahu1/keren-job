// Deterministic image pin for CI.
//
// Replaces the one `image:` line per repository in a workload manifest with the
// exact `tag@digest` reference produced by the build, instead of an
// indentation-sensitive `sed`. Fails closed if a repository does not appear
// exactly once, so a rename can never silently pin nothing.
//
// usage: node scripts/update-manifest.mjs <manifest> <repo=image-ref> [...]
//   e.g. node scripts/update-manifest.mjs deploy/deployment.yaml \
//          ghcr.io/danieliyahu1/keren-job=ghcr.io/danieliyahu1/keren-job:sha-abc@sha256:def

import { readFileSync, writeFileSync } from "node:fs";

const [, , manifestPath, ...pins] = process.argv;

if (!manifestPath || pins.length === 0) {
  console.error("usage: node scripts/update-manifest.mjs <manifest> <repo=image-ref> [...]");
  process.exit(2);
}

const escape = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

let content = readFileSync(manifestPath, "utf8");
let changed = false;

for (const pin of pins) {
  const separator = pin.indexOf("=");
  if (separator < 1) {
    console.error(`expected <repo>=<image-ref>, got '${pin}'`);
    process.exit(2);
  }

  const repo = pin.slice(0, separator);
  const image = pin.slice(separator + 1);

  if (!image.startsWith(`${repo}:`) || !/@sha256:[0-9a-f]{64}$/.test(image)) {
    console.error(`refusing to pin '${repo}' to a non-immutable reference: '${image}'`);
    process.exit(1);
  }

  const pattern = new RegExp(`^([ \\t]*image:[ \\t]*)${escape(repo)}:\\S+[ \\t]*$`, "gm");
  const matches = content.match(pattern);

  if (matches === null || matches.length !== 1) {
    console.error(
      `expected exactly one image line for ${repo} in ${manifestPath}, found ${matches?.length ?? 0}`,
    );
    process.exit(1);
  }

  const updated = content.replace(pattern, (_match, prefix) => `${prefix}${image}`);
  changed = changed || updated !== content;
  content = updated;
  console.log(`${repo} -> ${image}`);
}

if (changed) {
  writeFileSync(manifestPath, content);
  console.log(`${manifestPath} updated`);
} else {
  console.log(`${manifestPath} already pinned`);
}
