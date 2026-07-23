#!/usr/bin/env node

import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const kotlin = readFileSync(
  resolve(
    repositoryRoot,
    "app/src/main/java/com/gigagochi/app/core/webview/WebTypography.kt",
  ),
  "utf8",
);
const styles = readFileSync(resolve(repositoryRoot, "web/src/styles.css"), "utf8");
const eventStory = readFileSync(resolve(repositoryRoot, "web/src/eventStory.css"), "utf8");
const allCss = `${styles}\n${eventStory}`;

const kotlinTokens = [...kotlin.matchAll(
  /WebTypographyToken\("(--platform-sp-[a-z0-9-]+)"\s*,/g,
)].map((match) => match[1]);
const cssDeclarations = [...styles.matchAll(
  /^\s*(--platform-sp-[a-z0-9-]+)\s*:/gm,
)].map((match) => match[1]);
const cssReferences = new Set([...allCss.matchAll(
  /var\((--platform-sp-[a-z0-9-]+)\)/g,
)].map((match) => match[1]));

const fail = (message) => {
  process.stderr.write(`Typography contract check failed: ${message}\n`);
  process.exit(1);
};
const sameOrderedValues = (left, right) => (
  left.length === right.length && left.every((value, index) => value === right[index])
);

if (kotlinTokens.length === 0) fail("Kotlin token source is empty");
if (new Set(kotlinTokens).size !== kotlinTokens.length) fail("Kotlin token names are duplicated");
if (!sameOrderedValues(kotlinTokens, cssDeclarations)) {
  fail(
    `Kotlin and CSS declarations differ\nKotlin: ${kotlinTokens.join(", ")}\n` +
      `CSS: ${cssDeclarations.join(", ")}`,
  );
}

const missingReferences = kotlinTokens.filter((token) => !cssReferences.has(token));
if (missingReferences.length > 0) {
  fail(`declared tokens are unused: ${missingReferences.join(", ")}`);
}
const unknownReferences = [...cssReferences].filter((token) => !kotlinTokens.includes(token));
if (unknownReferences.length > 0) {
  fail(`CSS references unknown tokens: ${unknownReferences.join(", ")}`);
}

process.stdout.write(`Typography contract OK (${kotlinTokens.length} platform SP tokens)\n`);
