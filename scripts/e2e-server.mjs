import { spawn, spawnSync } from "node:child_process";
import { readFileSync, mkdirSync, createWriteStream } from "node:fs";
import { dirname, resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const windows = process.platform === "win32";
const children = [];
const logs = join(root, "backend", "target", "e2e-output");
mkdirSync(logs, { recursive: true });
function start(command, args, options = {}) {
  const child = spawn(command, args, {
    cwd: root,
    env: process.env,
    windowsHide: true,
    ...options,
  });
  children.push(child);
  return child;
}
function run(command, args) {
  return new Promise((yes, no) => {
    const child = start(command, args, { shell: windows, stdio: "inherit" });
    child.on("error", no);
    child.on("exit", (code) =>
      code === 0 ? yes() : no(new Error(`${command} failed (${code})`)),
    );
  });
}
function background(command, args, name, options = {}) {
  const child = start(command, args, {
    stdio: ["ignore", "pipe", "pipe"],
    ...options,
  });
  const log = createWriteStream(join(logs, `${name}.log`));
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  child.on("error", (error) => console.error(name, error.message));
  return child;
}
async function ready(url) {
  for (let i = 0; i < 120; i++) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1000) });
      const config = await response.json();
      if (config.areas?.some((a) => a.id === "BACKEND" && a.approved >= 10))
        return;
    } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(
    `Backend did not become ready: ${url}. See backend/target/e2e-output.`,
  );
}
function cleanup() {
  for (const child of children) {
    if (child.exitCode !== null || !child.pid) continue;
    if (windows)
      spawnSync("taskkill", ["/pid", String(child.pid), "/T", "/F"], {
        windowsHide: true,
        stdio: "ignore",
      });
    else child.kill("SIGTERM");
  }
}
process.on("SIGINT", () => process.exit(0));
process.on("SIGTERM", () => process.exit(0));
process.on("exit", cleanup);
try {
  await run(windows ? "mvn.cmd" : "mvn", [
    "-f",
    "backend/pom.xml",
    "test-compile",
    "dependency:build-classpath",
    "-Dmdep.outputFile=target/e2e-classpath.txt",
    "-DincludeScope=test",
    "-DskipTests",
    "-q",
  ]);
  const cp = [
    join(root, "backend", "target", "test-classes"),
    join(root, "backend", "target", "classes"),
    readFileSync(
      join(root, "backend", "target", "e2e-classpath.txt"),
      "utf8",
    ).trim(),
  ].join(windows ? ";" : ":");
  const java = process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, "bin", windows ? "java.exe" : "java")
    : "java";
  for (const [mode, port] of [
    ["interview_mode", 18181],
    ["generation_mode", 18182],
  ])
    background(
      java,
      [
        "-cp",
        cp,
        "com.interviewbot.backend.e2e.E2eApplication",
        mode,
        String(port),
      ],
      mode,
      { shell: false },
    );
  await Promise.all([
    ready("http://127.0.0.1:18181/api/config"),
    ready("http://127.0.0.1:18182/api/config"),
  ]);
  for (const [port, api] of [
    [5174, 18181],
    [5175, 18182],
  ])
    background(
      windows ? "npm.cmd" : "npm",
      [
        "--prefix",
        "frontend",
        "run",
        "dev",
        "--",
        "--host",
        "127.0.0.1",
        "--port",
        String(port),
        "--strictPort",
      ],
      `frontend-${port}`,
      {
        shell: windows,
        env: {
          ...process.env,
          VITE_API_BASE_URL: `http://127.0.0.1:${api}/api`,
        },
      },
    );
  console.log("Both isolated E2E application modes are ready.");
  setInterval(() => {}, 1000);
} catch (error) {
  console.error(error);
  process.exit(1);
}
