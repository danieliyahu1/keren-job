// Delivery-contract check for deploy/. Runs in CI before anything is published.
//
// kubeconform proves the manifests are valid Kubernetes. This proves they are a
// complete delivery unit for this platform: immutable images, both containers,
// probes, resource bounds, a private metrics path, a secret contract that names
// vault entries, and a dashboard that is valid JSON in the observability
// namespace. Dependency-free on purpose so it runs on any runner.
//
// usage: node scripts/validate-deploy.mjs

import { readFileSync } from "node:fs";
import { join } from "node:path";

const DEPLOY = "deploy";
const errors = [];

const read = (name) => {
  try {
    return readFileSync(join(DEPLOY, name), "utf8");
  } catch {
    errors.push(`deploy/${name} is missing`);
    return "";
  }
};

const expect = (condition, message) => {
  if (!condition) errors.push(message);
};

const APP_REPO = "ghcr.io/danieliyahu1/keren-job";
const OPENCODE_REPO = "ghcr.io/danieliyahu1/keren-job-opencode";
const PINNED = new RegExp(
  `^ghcr\\.io/danieliyahu1/keren-job(?:-opencode)?:sha-[0-9a-f]{40}@sha256:[0-9a-f]{64}$`,
);

const deployment = read("deployment.yaml");
const service = read("service.yaml");
const metricsService = read("metrics-service.yaml");
const scrape = read("vmservicescrape.yaml");
const externalSecret = read("externalsecret.yaml");
const configMap = read("configmap.yaml");
const dashboard = read("grafana-dashboard.yaml");
const namespace = read("namespace.yaml");

// --- images ---------------------------------------------------------------
const images = [...deployment.matchAll(/^[ \t]*image:[ \t]*(\S+)[ \t]*$/gm)].map((m) => m[1]);
expect(images.length === 2, `deployment must declare exactly 2 images, found ${images.length}`);
for (const image of images) {
  expect(
    PINNED.test(image),
    `image must be pinned to sha-<commit>@sha256:<digest>: '${image}'`,
  );
  expect(!/:latest\b/.test(image), `image must not use the moving :latest tag: '${image}'`);
}
const repos = images.map((image) => image.split(":sha-")[0]);
expect(repos.includes(APP_REPO), `missing app image from ${APP_REPO}`);
expect(repos.includes(OPENCODE_REPO), `missing sidecar image from ${OPENCODE_REPO}`);

// --- containers, probes, resources ---------------------------------------
// Split only the containers section: the volumes below also have `- name:` items
// (opencode-tmp, opencode-state, ...) that would otherwise truncate the block.
const containerSection =
  deployment.split(/^ {6}containers:/m)[1]?.split(/^ {6}volumes:/m)[0] ?? "";
const appBlock =
  containerSection.split(/^ {8}- name: app\b/m)[1]?.split(/^ {8}- name: opencode\b/m)[0] ?? "";
const opencodeBlock = containerSection.split(/^ {8}- name: opencode\b/m)[1] ?? "";

expect(appBlock.length > 0, "deployment must define a container named app");
expect(opencodeBlock.length > 0, "deployment must define a container named opencode");

for (const [name, block] of [
  ["app", appBlock],
  ["opencode", opencodeBlock],
]) {
  expect(block.includes("startupProbe:"), `${name} needs a startupProbe`);
  expect(block.includes("readinessProbe:"), `${name} needs a readinessProbe`);
  expect(block.includes("requests:"), `${name} needs resource requests`);
  expect(block.includes("limits:"), `${name} needs resource limits`);
}
expect(appBlock.includes("livenessProbe:"), "app needs a livenessProbe");
// Numeric UIDs: runAsNonRoot cannot verify a named USER, and Kubernetes refuses
// to start the container when it cannot. These must match the Dockerfiles.
expect(appBlock.includes("runAsUser: 10001"), "app must run as numeric UID 10001");
expect(appBlock.includes("runAsGroup: 10001"), "app must run as numeric GID 10001");
expect(opencodeBlock.includes("runAsUser: 10002"), "opencode must run as numeric UID 10002");
expect(opencodeBlock.includes("runAsGroup: 10002"), "opencode must run as numeric GID 10002");

expect(appBlock.includes("containerPort: 8080"), "app must listen on 8080");
expect(appBlock.includes("containerPort: 9090"), "app must expose metrics on 9090");
expect(appBlock.includes("readOnlyRootFilesystem: true"), "app must run with a read-only rootfs");
expect(opencodeBlock.includes("containerPort: 4096"), "opencode must listen on 4096");
expect(opencodeBlock.includes("OPENCODE_API_KEY"), "opencode must receive OPENCODE_API_KEY");
expect(
  opencodeBlock.includes("OPENCODE_PASSWORD"),
  "opencode must receive OPENCODE_PASSWORD (the V2 server always requires auth)",
);
expect(
  appBlock.includes("OPENCODE_PASSWORD"),
  "app must receive OPENCODE_PASSWORD to authenticate against the sidecar",
);
expect(
  !/readinessProbe:\s*\n\s*httpGet: \{ path: \/api\/info/.test(opencodeBlock),
  "opencode probes must authenticate, so they cannot be plain httpGet",
);
expect(
  opencodeBlock.includes("127.0.0.1:4096/api/info"),
  "opencode probes must check the authenticated /api/info endpoint",
);

expect(deployment.includes("runAsNonRoot: true"), "pod must run as non-root");
expect(
  /seccompProfile:\s*\n\s*type: RuntimeDefault/.test(deployment),
  "pod must use the RuntimeDefault seccomp profile",
);
expect(!deployment.includes("privileged: true"), "containers must not be privileged");
expect(
  /strategy:\s*\n\s*type: Recreate/.test(deployment),
  "deployment must use the Recreate strategy (one pod at a time on a small node)",
);
expect(
  deployment.includes("automountServiceAccountToken: false"),
  "the pod must not mount a service account token",
);

// --- services -------------------------------------------------------------
expect(service.includes("name: kerenjob"), "public Service must be named kerenjob");
expect(service.includes("type: ClusterIP"), "public Service must stay ClusterIP");
expect(!service.includes("9090"), "public Service must not publish the metrics port");
expect(
  metricsService.includes("app.kubernetes.io/component: metrics"),
  "metrics Service must carry the metrics component label",
);
expect(metricsService.includes("port: 9090"), "metrics Service must expose 9090");

// --- scrape ---------------------------------------------------------------
expect(scrape.includes("kind: VMServiceScrape"), "a VMServiceScrape must exist");
expect(scrape.includes("path: /actuator/prometheus"), "scrape path must be /actuator/prometheus");
expect(scrape.includes("port: metrics"), "scrape must target the metrics port");
expect(scrape.includes("jobLabel: app.kubernetes.io/name"), "scrape must set the job label");
expect(
  scrape.includes("app.kubernetes.io/component: metrics"),
  "scrape must select only the metrics Service",
);

// --- secrets --------------------------------------------------------------
expect(
  externalSecret.includes("kind: ClusterSecretStore") && externalSecret.includes("name: oci-vault"),
  "ExternalSecret must read from the oci-vault ClusterSecretStore",
);
expect(externalSecret.includes("name: kerenjob-secrets"), "ExternalSecret must target kerenjob-secrets");
for (const key of [
  "k3s-01-kerenjob-opencode-api-key",
  "k3s-01-kerenjob-opencode-password",
]) {
  expect(externalSecret.includes(key), `ExternalSecret must map vault entry ${key}`);
}
expect(!/-----BEGIN/.test(externalSecret), "ExternalSecret must never embed a private key");

// --- config ---------------------------------------------------------------
expect(configMap.includes('PORT: "8080"'), "config must set PORT=8080");
expect(configMap.includes('METRICS_PORT: "9090"'), "config must set METRICS_PORT=9090");
expect(
  configMap.includes("OPENCODE_AUTO_START: \"false\""),
  "config must leave the sidecar owning the OpenCode process",
);
expect(
  configMap.includes("http://127.0.0.1:4096"),
  "config must point the app at the loopback sidecar",
);

// --- namespace and dashboard ---------------------------------------------
expect(namespace.includes("name: kerenjob"), "Namespace manifest must create kerenjob");
expect(
  dashboard.includes("namespace: observability"),
  "dashboard ConfigMap must live in the observability namespace",
);
expect(dashboard.includes('grafana_dashboard: "1"'), "dashboard must carry the grafana_dashboard label");

const dashboardJson = dashboard.split("kerenjob-overview.json: |")[1];
if (!dashboardJson) {
  errors.push("dashboard ConfigMap must contain kerenjob-overview.json");
} else {
  try {
    const parsed = JSON.parse(dashboardJson.replace(/^ {4}/gm, ""));
    expect(typeof parsed.title === "string" && parsed.title.length > 0, "dashboard needs a title");
    expect(Array.isArray(parsed.panels) && parsed.panels.length > 0, "dashboard needs panels");
    const queries = parsed.panels.flatMap((panel) => panel.targets ?? []).map((t) => t.expr ?? "");
    expect(
      queries.some((expr) => expr.includes('job="kerenjob"')),
      "dashboard queries must filter on job=\"kerenjob\"",
    );
    expect(
      queries.some((expr) => expr.includes("http_server_requests_seconds")),
      "dashboard must chart the HTTP request metrics",
    );
  } catch (error) {
    errors.push(`dashboard JSON is invalid: ${error.message}`);
  }
}

if (errors.length > 0) {
  console.error("delivery contract violations:");
  for (const error of errors) console.error(`  - ${error}`);
  process.exit(1);
}

console.log("deploy/ satisfies the delivery contract");
