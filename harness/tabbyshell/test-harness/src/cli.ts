import { resolve } from "node:path";
import { loadTestCases } from "./yaml-loader.js";
import { runCase } from "./runner.js";
import { reportResult, reportSummary } from "./reporter.js";
import type { Lang } from "./types.js";

interface CliArgs {
  lang: Lang;
  projectRoot: string;
  implementationRoot: string;
  filter?: string;
  verbose: boolean;
}

function parseArgs(argv: string[]): CliArgs {
  let lang: Lang | undefined;
  let projectRoot: string | undefined;
  let implementationRoot: string | undefined;
  let filter: string | undefined;
  let verbose = false;

  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--lang") {
      const v = argv[++i];
      if (v !== "ts" && v !== "rust" && v !== "scala") {
        throw new Error(`--lang must be ts | rust | scala, got: ${v}`);
      }
      lang = v;
    } else if (a === "--project-root") {
      projectRoot = argv[++i];
    } else if (a === "--implementation-root") {
      implementationRoot = argv[++i];
    } else if (a === "--filter") {
      filter = argv[++i];
    } else if (a === "--verbose" || a === "-v") {
      verbose = true;
    } else if (a === "--help" || a === "-h") {
      process.stdout.write(
        `Usage: cli.ts --lang <ts|rust|scala> --project-root <path> --implementation-root <path> [--filter <substring>] [--verbose]\n`,
      );
      process.exit(0);
    } else {
      throw new Error(`unknown argument: ${a}`);
    }
  }

  if (!lang) throw new Error("--lang is required (ts | rust | scala)");
  if (!projectRoot) throw new Error("--project-root is required");
  if (!implementationRoot) throw new Error("--implementation-root is required");
  return {
    lang,
    projectRoot: resolve(projectRoot),
    implementationRoot: resolve(implementationRoot),
    filter,
    verbose,
  };
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  const testsDir = resolve(args.projectRoot, "tests");

  const cases = loadTestCases(testsDir, args.filter);
  if (cases.length === 0) {
    process.stdout.write("no test cases found\n");
    process.exit(1);
  }

  process.stdout.write(`tabbyshell tests — lang=${args.lang}, ${cases.length} case(s)\n`);

  const results = [];
  for (const tc of cases) {
    const result = await runCase({
      lang: args.lang,
      projectRoot: args.projectRoot,
      implementationRoot: args.implementationRoot,
    }, tc);
    reportResult(result, args.verbose);
    results.push(result);
  }
  reportSummary(results);

  const failed = results.filter((r) => !r.passed).length;
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  process.stderr.write(`fatal: ${(e as Error).message}\n`);
  process.exit(2);
});
