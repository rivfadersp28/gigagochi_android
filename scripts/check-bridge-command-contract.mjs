#!/usr/bin/env node

import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDirectory, "..");

const files = {
  kotlin: path.join(
    root,
    "app/src/main/java/com/gigagochi/app/core/webview/BridgeModels.kt",
  ),
  production: path.join(
    root,
    "app/src/main/java/com/gigagochi/app/core/webview/ProductionWebAppRuntime.kt",
  ),
  debug: path.join(
    root,
    "app/src/debug/java/com/gigagochi/app/webview/DebugWebAppRuntime.kt",
  ),
  typescript: path.join(root, "web/src/contracts.ts"),
};

const [kotlin, production, debug, typescript] = await Promise.all(
  Object.values(files).map((file) => fs.readFile(file, "utf8")),
);

const kotlinUnion = between(
  kotlin,
  "internal val BridgeProductCommandTypes = setOf(",
  "\n)",
);
const typescriptUnion = between(
  typescript,
  "export type ProductCommand =",
  "\n\nexport type ProductCommandType",
);
const productionDispatch = between(
  production,
  "val petTapFeedback = when (command.type) {",
  "\n        revisionSequence += 1",
);
const debugDispatch = between(
  debug,
  "val feedback = when (command.type) {",
  "\n        revisionNumber += 1",
);

const contracts = new Map([
  ["Kotlin ingress union", quotedUppercase(kotlinUnion)],
  ["TypeScript union", typeDiscriminants(typescriptUnion)],
  ["production runtime", whenBranches(productionDispatch)],
  ["debug runtime", whenBranches(debugDispatch)],
]);
const expected = contracts.get("Kotlin ingress union");
if (!expected || expected.size === 0) {
  throw new Error("Kotlin bridge command union is empty");
}

for (const [label, actual] of contracts) {
  const missing = [...expected].filter((command) => !actual.has(command));
  const extra = [...actual].filter((command) => !expected.has(command));
  if (missing.length > 0 || extra.length > 0) {
    throw new Error(
      `${label} drifted from Kotlin ingress union; ` +
      `missing=[${missing.join(",")}], extra=[${extra.join(",")}]`,
    );
  }
}

process.stdout.write(`Bridge command contract OK (${expected.size} closed commands)\n`);

function between(source, startMarker, endMarker) {
  const start = source.indexOf(startMarker);
  if (start < 0) throw new Error(`missing marker: ${startMarker}`);
  const end = source.indexOf(endMarker, start + startMarker.length);
  if (end < 0) throw new Error(`missing marker: ${endMarker}`);
  return source.slice(start + startMarker.length, end);
}

function quotedUppercase(source) {
  return matches(source, /"([A-Z][A-Z0-9_]*)"/g);
}

function typeDiscriminants(source) {
  return matches(source, /type:\s*"([A-Z][A-Z0-9_]*)"/g);
}

function whenBranches(source) {
  return matches(source, /"([A-Z][A-Z0-9_]*)"\s*(?:,|->)/g);
}

function matches(source, pattern) {
  return new Set([...source.matchAll(pattern)].map((match) => match[1]));
}
