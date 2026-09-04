import { spawn } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, cpSync, accessSync, constants, existsSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { execSync } from "node:child_process";
import type { Lang, TestCase, TestResult } from "./types.js";
import { checkAssertion, type ExecutionResult } from "./assertions.js";

interface RunnerConfig {
  lang: Lang;
  /** Absolute path to the public TabbyShell project root. */
  projectRoot: string;
  /** Absolute path to exactly the implementation under test. */
  implementationRoot: string;
}

/** Build the command to launch a TabbyShell binary in non-interactive mode. */
function buildCommand(config: RunnerConfig): { cmd: string; args: string[] } {
  switch (config.lang) {
    case "ts":
      return {
        cmd: "npx",
        args: ["tsx", join(config.implementationRoot, "src/main.ts"), "--no-color", "--eval-file", "-"],
      };
    case "rust": {
      const release = join(config.implementationRoot, "target/release/tabbyshell");
      const debug = join(config.implementationRoot, "target/debug/tabbyshell");
      return {
        cmd: existsOrThrow([release, debug], "rust binary not built in the explicit implementation root"),
        args: ["--no-color", "--eval-file", "-"],
      };
    }
    case "scala": {
      const jar = findScalaJar(config.implementationRoot);
      return {
        cmd: javaCommand(),
        args: ["-jar", jar, "--no-color", "--eval-file", "-"],
      };
    }
  }
}

function existsOrThrow(candidates: string[], message: string): string {
  for (const c of candidates) {
    try {
      accessSync(c);
      return c;
    } catch {
      // try next
    }
  }
  throw new Error(message);
}

function javaCommand(): string {
  const homebrewJava = "/opt/homebrew/opt/openjdk/bin/java";
  try {
    accessSync(homebrewJava, constants.X_OK);
    return homebrewJava;
  } catch {
    return "java";
  }
}

function findScalaJar(implementationRoot: string): string {
  const target = join(implementationRoot, "target");
  if (!existsSync(target)) {
    throw new Error("scala target/ not found in the explicit implementation root");
  }
  for (const sub of readdirSync(target)) {
    if (!sub.startsWith("scala-")) continue;
    const dir = join(target, sub);
    for (const f of readdirSync(dir)) {
      if (f.endsWith("-assembly-0.1.0.jar") || f.match(/^tabbyshell-assembly-.*\.jar$/)) {
        return join(dir, f);
      }
    }
  }
  throw new Error("scala assembly jar not found in the explicit implementation root");
}

export async function runCase(config: RunnerConfig, testCase: TestCase): Promise<TestResult> {
  const start = Date.now();
  const tmp = mkdtempSync(join(tmpdir(), "tabbyshell-test-"));
  let stdout = "";
  let stderr = "";
  let exitCode: number | null = null;
  const failures: string[] = [];

  try {
    // Stage fixtures referenced via SetupStep `cp` semantics is left to shell; we just
    // pre-create files declared in `files:` and run shell setup inside the tmp cwd.
    if (testCase.files) {
      for (const f of testCase.files) {
        const target = join(tmp, f.path);
        mkdirSync(dirname(target), { recursive: true });
        writeFileSync(target, f.content);
      }
    }
    if (testCase.setup) {
      for (const step of testCase.setup) {
        execSync(step.shell, { cwd: tmp, stdio: "pipe" });
      }
    }

    const { cmd, args } = buildCommand(config);
    const fullArgs = [...args, ...(testCase.extra_args ?? [])];
    const env: Record<string, string> = { ...process.env as Record<string, string> };
    env.TABBY_PROJECT_ROOT = config.projectRoot;
    if (testCase.now !== undefined) env.TABBY_NOW = String(testCase.now);
    if (testCase.no_ai) env.TABBY_DISABLE_AI = "1";
    env.NO_COLOR = "1";

    const child = spawn(cmd, fullArgs, { cwd: tmp, env });
    child.stdout.on("data", (chunk: Buffer) => { stdout += chunk.toString("utf8"); });
    child.stderr.on("data", (chunk: Buffer) => { stderr += chunk.toString("utf8"); });
    child.stdin.write(testCase.script);
    child.stdin.end();

    const timeoutMs = (testCase.timeout ?? 30) * 1000;
    exitCode = await waitForExit(child, timeoutMs);

    const result: ExecutionResult = { stdout, stderr, exitCode, cwd: tmp };
    for (const assertion of testCase.assertions) {
      const failure = checkAssertion(assertion, result);
      if (failure) failures.push(failure);
    }
  } catch (err) {
    failures.push(`harness error: ${(err as Error).message}`);
  } finally {
    if (testCase.cleanup) {
      for (const step of testCase.cleanup) {
        try { execSync(step.shell, { cwd: tmp, stdio: "pipe" }); } catch { /* ignore */ }
      }
    }
    try { rmSync(tmp, { recursive: true, force: true }); } catch { /* ignore */ }
  }

  return {
    name: testCase.name,
    passed: failures.length === 0,
    failures,
    durationMs: Date.now() - start,
    stdout,
    stderr,
    exitCode,
  };
}

function waitForExit(child: ReturnType<typeof spawn>, timeoutMs: number): Promise<number | null> {
  return new Promise((resolveP) => {
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      resolveP(null);
    }, timeoutMs);

    child.on("exit", (code) => {
      clearTimeout(timer);
      resolveP(code);
    });
  });
}

export { resolve as resolvePath, cpSync as copyDirSync };
