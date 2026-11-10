import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, statSync } from "node:fs";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const files = execFileSync(
  "git",
  ["ls-files", "--cached", "--others", "--exclude-standard"],
  { cwd: root, encoding: "utf8" },
)
  .trim()
  .split(/\r?\n/);
const findings = [];
const credentialPattern =
  /-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----|\bAKIA[A-Z0-9]{16}\b|\bsk-[A-Za-z0-9_-]{32,}|\bgh[pousr]_[A-Za-z0-9]{20,}|\bgithub_pat_[A-Za-z0-9_]{20,}|\bAIza[A-Za-z0-9_-]{35}|\bxox[baprs]-[A-Za-z0-9-]{20,}|\b(?:https?|jdbc:mysql):\/\/[^/\s:@]+:[^/\s@]+@/;
function checkCredentials(text, file) {
  if (credentialPattern.test(text))
    findings.push(`${file}: credential pattern found`);
  if (/\.properties$|\.env\.example$/.test(file))
    for (const line of text.split(/\r?\n/)) {
      const match = line.match(
        /^\s*[^#\n]*(?:password|api[-_.]?key|admin[-_.]?key|signing[-_.]?key)\s*=\s*(.+)$/i,
      );
      if (
        match &&
        !/^(?:\$\{|replace-|your-|example|placeholder)/i.test(match[1].trim())
      )
        findings.push(
          `${file}: literal credential setting must use an environment reference or placeholder`,
        );
    }
}
if (process.argv.includes("--history")) {
  const history = execFileSync(
    "git",
    ["log", "--all", "--format=", "-p", "--no-ext-diff"],
    { cwd: root, encoding: "utf8", maxBuffer: 32 * 1024 * 1024 },
  );
  for (const patch of history.split(/^diff --git /m).slice(1)) {
    const file = patch.match(/ b\/(.+)\r?\n/)?.[1] ?? "unknown file";
    const content = patch
      .split(/\r?\n/)
      .filter((line) => /^[+-](?![+-])/.test(line))
      .map((line) => line.slice(1))
      .join("\n");
    checkCredentials(content, file);
  }
}
if (process.argv.includes("--artifact")) {
  const jarTool = process.env.JAVA_HOME
    ? join(
        process.env.JAVA_HOME,
        "bin",
        process.platform === "win32" ? "jar.exe" : "jar",
      )
    : "jar";
  const entries = execFileSync(
    jarTool,
    ["tf", join(root, "backend/target/backend-0.0.1-SNAPSHOT.jar")],
    { encoding: "utf8" },
  ).split(/\r?\n/);
  for (const entry of entries)
    if (
      /(?:^|\/)(?:application-local\.properties|application-[^/]*\.local\.properties|\.env[^/]*|[^/]+\.(?:pem|key|p12|pfx))$/.test(
        entry,
      )
    )
      findings.push(`${entry}: private configuration must not be packaged`);
}
for (const file of new Set(files)) {
  const path = resolve(root, file);
  if (!existsSync(path) || !statSync(path).isFile()) continue;
  if (
    /(?:^|\/)(?:node_modules|target|dist|backups|logs|playwright-report|test-results)\//.test(
      file,
    ) ||
    (/\.(?:log|zip|jar|class|war|ear|pem|key|p12|pfx)$|(?:^|\/)application-(?:local|[^/]+\.local)\.properties$|(?:^|\/)\.env(?:\.[^/]*)?$/.test(
      file,
    ) &&
      !file.endsWith(".example"))
  ) {
    findings.push(`${file}: local/generated file must not be tracked`);
    continue;
  }
  if (
    statSync(path).size > 2_000_000 ||
    /\.(?:png|jpg|zip|jar|class)$/.test(file)
  )
    continue;
  const text = readFileSync(path, "utf8");
  checkCredentials(text, file);
}
if (findings.length) {
  console.error(findings.join("\n"));
  process.exit(1);
}
console.log(
  `Repository hygiene check passed (${files.length} indexed/unignored paths checked; secret values are never printed).`,
);
