#!/usr/bin/env node

import { createHash } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const manifestFileName = "web-bundle-manifest.json";
const schemaHashPrefix = "gigagochi-bridge-v3-";
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "..");

const defaults = {
  bundleDirectory: path.join(
    repositoryRoot,
    "app",
    "build",
    "generated",
    "webAssets",
    "web",
  ),
  kotlinContracts: path.join(
    repositoryRoot,
    "app",
    "src",
    "main",
    "java",
    "com",
    "gigagochi",
    "app",
    "core",
    "webview",
    "BridgeModels.kt",
  ),
  typescriptContracts: path.join(repositoryRoot, "web", "src", "contracts.ts"),
  contractSourceList: path.join(
    repositoryRoot,
    "scripts",
    "wire-contract-sources.txt",
  ),
};

const constantDefinitions = [
  {
    manifestName: "protocolVersion",
    kotlinName: "BridgeProtocolVersion",
    typescriptName: "BRIDGE_PROTOCOL_VERSION",
    valuePattern: "(\\d+)",
    parse: (raw) => {
      const value = Number(raw);
      if (!Number.isSafeInteger(value) || value < 0) {
        throw new Error(`invalid protocol version: ${raw}`);
      }
      return value;
    },
  },
  {
    manifestName: "webBundleVersion",
    kotlinName: "BridgeWebBundleVersion",
    typescriptName: "WEB_BUNDLE_VERSION",
    valuePattern: '("(?:[^"\\\\]|\\\\.)*")',
    parse: parseQuotedString,
  },
  {
    manifestName: "schemaHash",
    kotlinName: "BridgeSchemaHash",
    typescriptName: "BRIDGE_SCHEMA_HASH",
    valuePattern: '("(?:[^"\\\\]|\\\\.)*")',
    parse: parseQuotedString,
  },
];

function usage() {
  return [
    "Usage:",
    "  node scripts/web-bundle-manifest.mjs generate [options]",
    "  node scripts/web-bundle-manifest.mjs verify [options]",
    "  node scripts/web-bundle-manifest.mjs schema-hash [options]",
    "  node scripts/web-bundle-manifest.mjs verify-contracts [options]",
    "",
    "Options:",
    "  --bundle-dir PATH   Generated Vite bundle directory",
    "  --kotlin PATH       Kotlin bridge contract source",
    "  --typescript PATH   TypeScript bridge contract source",
    "  --source-root PATH   Root for paths in the wire source list",
    "  --contract-list PATH  Newline-delimited wire source list",
    "  --stamp PATH        Write a verification stamp (verify only)",
  ].join("\n");
}

function parseArguments(argv) {
  if (argv.length === 0 || argv[0] === "--help" || argv[0] === "-h") {
    process.stdout.write(`${usage()}\n`);
    process.exit(argv.length === 0 ? 2 : 0);
  }

  const command = argv[0];
  if (
    command !== "generate" &&
    command !== "verify" &&
    command !== "schema-hash" &&
    command !== "verify-contracts"
  ) {
    throw new Error(`unknown command: ${command}\n${usage()}`);
  }

  const options = {
    command,
    bundleDirectory: defaults.bundleDirectory,
    kotlinContracts: defaults.kotlinContracts,
    typescriptContracts: defaults.typescriptContracts,
    sourceRoot: repositoryRoot,
    contractSourceList: defaults.contractSourceList,
    stamp: null,
  };
  const optionNames = new Map([
    ["--bundle-dir", "bundleDirectory"],
    ["--kotlin", "kotlinContracts"],
    ["--typescript", "typescriptContracts"],
    ["--source-root", "sourceRoot"],
    ["--contract-list", "contractSourceList"],
    ["--stamp", "stamp"],
  ]);

  for (let index = 1; index < argv.length; index += 2) {
    const flag = argv[index];
    const property = optionNames.get(flag);
    const value = argv[index + 1];
    if (property === undefined || value === undefined || value.startsWith("--")) {
      throw new Error(`invalid option: ${flag ?? "<missing>"}\n${usage()}`);
    }
    options[property] = path.resolve(value);
  }

  if (command !== "verify" && options.stamp !== null) {
    throw new Error("--stamp is only supported by the verify command");
  }
  return options;
}

function parseQuotedString(raw) {
  try {
    const value = JSON.parse(raw);
    if (typeof value !== "string") {
      throw new Error("not a string");
    }
    return value;
  } catch (error) {
    throw new Error(`invalid quoted string ${raw}: ${error.message}`);
  }
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function normalizeLineEndings(source) {
  return source.replace(/\r\n?/g, "\n");
}

function maskComments(source) {
  const output = source.split("");
  let state = "code";
  let blockDepth = 0;

  for (let index = 0; index < source.length; index += 1) {
    const character = source[index];
    const next = source[index + 1];

    if (state === "line-comment") {
      if (character === "\n") {
        state = "code";
      } else {
        output[index] = " ";
      }
      continue;
    }
    if (state === "block-comment") {
      if (character === "/" && next === "*") {
        output[index] = " ";
        output[index + 1] = " ";
        blockDepth += 1;
        index += 1;
      } else if (character === "*" && next === "/") {
        output[index] = " ";
        output[index + 1] = " ";
        blockDepth -= 1;
        index += 1;
        if (blockDepth === 0) {
          state = "code";
        }
      } else if (character !== "\n") {
        output[index] = " ";
      }
      continue;
    }
    if (state === "double-quoted" || state === "single-quoted") {
      const closingQuote = state === "double-quoted" ? '"' : "'";
      if (character === "\\") {
        index += 1;
      } else if (character === closingQuote) {
        state = "code";
      }
      continue;
    }
    if (state === "triple-quoted") {
      if (source.startsWith('\"\"\"', index)) {
        index += 2;
        state = "code";
      }
      continue;
    }
    if (state === "template-quoted") {
      if (character === "\\") {
        index += 1;
      } else if (character === "`") {
        state = "code";
      }
      continue;
    }

    if (character === "/" && next === "/") {
      output[index] = " ";
      output[index + 1] = " ";
      state = "line-comment";
      index += 1;
    } else if (character === "/" && next === "*") {
      output[index] = " ";
      output[index + 1] = " ";
      state = "block-comment";
      blockDepth = 1;
      index += 1;
    } else if (source.startsWith('\"\"\"', index)) {
      state = "triple-quoted";
      index += 2;
    } else if (character === '"') {
      state = "double-quoted";
    } else if (character === "'") {
      state = "single-quoted";
    } else if (character === "`") {
      state = "template-quoted";
    }
  }
  return output.join("");
}

function extractExactlyOne(source, pattern, description, parse) {
  const matches = [...maskComments(source).matchAll(pattern)];
  if (matches.length !== 1) {
    throw new Error(
      `${description} must be declared exactly once; found ${matches.length}`,
    );
  }
  const match = matches[0];
  return {
    value: parse(match[1]),
    matchIndex: match.index,
    matchLength: match[0].length,
  };
}

function declarationPattern(language, definition) {
  if (language === "kotlin") {
    return new RegExp(
      `^[ \\t]*(?:(?:public|internal|private)[ \\t]+)?const[ \\t]+val[ \\t]+${escapeRegExp(definition.kotlinName)}[ \\t]*=[ \\t]*${definition.valuePattern}[ \\t]*;?[ \\t]*$`,
      "gm",
    );
  }
  return new RegExp(
    `^[ \\t]*export[ \\t]+const[ \\t]+${escapeRegExp(definition.typescriptName)}[ \\t]*=[ \\t]*${definition.valuePattern}[ \\t]*(?:as[ \\t]+const)?[ \\t]*;?[ \\t]*$`,
    "gm",
  );
}

async function readBridgeMetadata(options, contractSourceSha256) {
  const kotlinPath = options.kotlinContracts;
  const typescriptPath = options.typescriptContracts;
  const [kotlinSource, typescriptSource] = await Promise.all([
    fs.readFile(kotlinPath, "utf8"),
    fs.readFile(typescriptPath, "utf8"),
  ]);
  const normalizedKotlinSource = normalizeLineEndings(kotlinSource);
  const normalizedTypescriptSource = normalizeLineEndings(typescriptSource);
  const metadata = {};

  for (const definition of constantDefinitions) {
    const kotlinDeclaration = extractExactlyOne(
      normalizedKotlinSource,
      declarationPattern("kotlin", definition),
      `${definition.kotlinName} in ${kotlinPath}`,
      definition.parse,
    );
    const typescriptDeclaration = extractExactlyOne(
      normalizedTypescriptSource,
      declarationPattern("typescript", definition),
      `${definition.typescriptName} in ${typescriptPath}`,
      definition.parse,
    );
    if (kotlinDeclaration.value !== typescriptDeclaration.value) {
      throw new Error(
        `bridge contract mismatch: ${definition.kotlinName}=${JSON.stringify(kotlinDeclaration.value)} but ${definition.typescriptName}=${JSON.stringify(typescriptDeclaration.value)}`,
      );
    }
    metadata[definition.manifestName] = kotlinDeclaration.value;
  }

  const expectedSchemaHash = `${schemaHashPrefix}${contractSourceSha256}`;
  if (metadata.schemaHash !== expectedSchemaHash) {
    throw new Error(
      `bridge schema hash is stale: found ${JSON.stringify(metadata.schemaHash)}, expected ${JSON.stringify(expectedSchemaHash)}; run \`node scripts/web-bundle-manifest.mjs schema-hash\``,
    );
  }
  return metadata;
}

function stableCompare(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

async function readContractSourcePaths(options) {
  let sourceList;
  try {
    sourceList = normalizeLineEndings(
      await fs.readFile(options.contractSourceList, "utf8"),
    );
  } catch (error) {
    throw new Error(
      `cannot read wire contract source list ${options.contractSourceList}: ${error.message}`,
    );
  }

  const relativePaths = sourceList
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line !== "" && !line.startsWith("#"));
  if (relativePaths.length === 0) {
    throw new Error("wire contract source list is empty");
  }
  const duplicates = relativePaths.filter(
    (relativePath, index) => relativePaths.indexOf(relativePath) !== index,
  );
  if (duplicates.length > 0) {
    throw new Error(
      `wire contract source list contains duplicates: ${[...new Set(duplicates)].join(", ")}`,
    );
  }

  const sourceRoot = path.resolve(options.sourceRoot);
  const sources = relativePaths.map((relativePath) => {
    if (
      path.isAbsolute(relativePath) ||
      relativePath.includes("\\") ||
      relativePath.split("/").some((segment) => segment === "" || segment === "." || segment === "..")
    ) {
      throw new Error(`unsafe wire contract source path: ${relativePath}`);
    }
    const absolutePath = path.resolve(sourceRoot, relativePath);
    const relativeToRoot = path.relative(sourceRoot, absolutePath);
    if (relativeToRoot.startsWith(`..${path.sep}`) || relativeToRoot === "..") {
      throw new Error(`wire contract source escapes source root: ${relativePath}`);
    }
    return { relativePath, absolutePath };
  });
  sources.sort((left, right) => stableCompare(left.relativePath, right.relativePath));

  for (const requiredPath of [options.kotlinContracts, options.typescriptContracts]) {
    const absoluteRequiredPath = path.resolve(requiredPath);
    if (!sources.some((source) => source.absolutePath === absoluteRequiredPath)) {
      throw new Error(
        `wire contract source list must include schema constant source: ${absoluteRequiredPath}`,
      );
    }
  }
  return sources;
}

function removeDeclarationLine(source, declaration) {
  const lineStart = source.lastIndexOf("\n", declaration.matchIndex - 1) + 1;
  const nextNewline = source.indexOf(
    "\n",
    declaration.matchIndex + declaration.matchLength,
  );
  const lineEnd = nextNewline === -1 ? source.length : nextNewline + 1;
  return `${source.slice(0, lineStart)}${source.slice(lineEnd)}`;
}

function updateFramedHash(hash, value) {
  const content = Buffer.from(value, "utf8");
  const length = Buffer.alloc(8);
  length.writeBigUInt64BE(BigInt(content.byteLength));
  hash.update(length);
  hash.update(content);
}

async function computeContractSourceHash(options) {
  const sources = await readContractSourcePaths(options);
  const schemaDefinition = constantDefinitions.find(
    (definition) => definition.manifestName === "schemaHash",
  );
  if (schemaDefinition === undefined) {
    throw new Error("internal error: schema hash constant definition is missing");
  }

  const kotlinContractsPath = path.resolve(options.kotlinContracts);
  const typescriptContractsPath = path.resolve(options.typescriptContracts);
  const normalizedSources = [];
  for (const source of sources) {
    const entry = await fs.lstat(source.absolutePath).catch((error) => {
      throw new Error(
        `cannot stat wire contract source ${source.relativePath}: ${error.message}`,
      );
    });
    if (!entry.isFile() || entry.isSymbolicLink()) {
      throw new Error(`wire contract source must be a regular file: ${source.relativePath}`);
    }
    let content = normalizeLineEndings(
      await fs.readFile(source.absolutePath, "utf8"),
    );
    if (content.includes("\0")) {
      throw new Error(`wire contract source contains NUL: ${source.relativePath}`);
    }

    if (source.absolutePath === kotlinContractsPath) {
      const declaration = extractExactlyOne(
        content,
        declarationPattern("kotlin", schemaDefinition),
        `${schemaDefinition.kotlinName} in ${source.absolutePath}`,
        schemaDefinition.parse,
      );
      content = removeDeclarationLine(content, declaration);
    } else if (source.absolutePath === typescriptContractsPath) {
      const declaration = extractExactlyOne(
        content,
        declarationPattern("typescript", schemaDefinition),
        `${schemaDefinition.typescriptName} in ${source.absolutePath}`,
        schemaDefinition.parse,
      );
      content = removeDeclarationLine(content, declaration);
    }
    normalizedSources.push({ relativePath: source.relativePath, content });
  }

  const hash = createHash("sha256");
  hash.update("gigagochi-wire-contract-sources-v1\0", "utf8");
  for (const source of normalizedSources) {
    updateFramedHash(hash, source.relativePath);
    updateFramedHash(hash, source.content);
  }
  return {
    contractSourceSha256: hash.digest("hex"),
    sourcePaths: normalizedSources.map((source) => source.relativePath),
  };
}

async function collectRelativeFiles(rootDirectory, relativeDirectory = "") {
  const absoluteDirectory = path.join(rootDirectory, relativeDirectory);
  let entries;
  try {
    entries = await fs.readdir(absoluteDirectory, { withFileTypes: true });
  } catch (error) {
    throw new Error(`cannot read bundle directory ${absoluteDirectory}: ${error.message}`);
  }
  entries.sort((left, right) => stableCompare(left.name, right.name));

  const files = [];
  for (const entry of entries) {
    const relativePath = relativeDirectory
      ? `${relativeDirectory}/${entry.name}`
      : entry.name;
    if (entry.isSymbolicLink()) {
      throw new Error(`bundle must not contain symbolic links: ${relativePath}`);
    }
    if (entry.isDirectory()) {
      files.push(...(await collectRelativeFiles(rootDirectory, relativePath)));
    } else if (entry.isFile()) {
      if (relativePath !== manifestFileName) {
        files.push(relativePath);
      }
    } else {
      throw new Error(`unsupported bundle entry: ${relativePath}`);
    }
  }
  return files;
}

async function describeBundleFiles(bundleDirectory) {
  const relativeFiles = await collectRelativeFiles(bundleDirectory);
  relativeFiles.sort(stableCompare);
  if (!relativeFiles.includes("index.html")) {
    throw new Error("bundle is missing index.html");
  }
  if (!relativeFiles.some((relativePath) => relativePath.startsWith("assets/"))) {
    throw new Error("bundle does not contain any files under assets/");
  }

  return Promise.all(
    relativeFiles.map(async (relativePath) => {
      const content = await fs.readFile(path.join(bundleDirectory, relativePath));
      return {
        path: relativePath,
        size: content.byteLength,
        sha256: createHash("sha256").update(content).digest("hex"),
      };
    }),
  );
}

function serializeManifest(metadata, contractSourceSha256, files) {
  return `${JSON.stringify(
    {
      protocolVersion: metadata.protocolVersion,
      webBundleVersion: metadata.webBundleVersion,
      schemaHash: metadata.schemaHash,
      contractSourceSha256,
      files,
    },
    null,
    2,
  )}\n`;
}

async function expectedManifest(options) {
  const contractSources = await computeContractSourceHash(options);
  const [metadata, files] = await Promise.all([
    readBridgeMetadata(options, contractSources.contractSourceSha256),
    describeBundleFiles(options.bundleDirectory),
  ]);
  return serializeManifest(
    metadata,
    contractSources.contractSourceSha256,
    files,
  );
}

async function writeAtomic(destination, content) {
  const destinationDirectory = path.dirname(destination);
  const temporaryPath = path.join(
    path.dirname(destinationDirectory),
    `.${path.basename(destination)}.${process.pid}.${Date.now()}.tmp`,
  );
  await fs.mkdir(destinationDirectory, { recursive: true });
  try {
    await fs.writeFile(temporaryPath, content, { encoding: "utf8", mode: 0o644 });
    await fs.rename(temporaryPath, destination);
  } finally {
    await fs.rm(temporaryPath, { force: true });
  }
}

async function generate(options) {
  const serialized = await expectedManifest(options);
  const destination = path.join(options.bundleDirectory, manifestFileName);
  await writeAtomic(destination, serialized);
  process.stdout.write(`generated ${destination}\n`);
}

async function verify(options) {
  const manifestPath = path.join(options.bundleDirectory, manifestFileName);
  let actual;
  try {
    actual = await fs.readFile(manifestPath, "utf8");
  } catch (error) {
    throw new Error(`cannot read bundle manifest ${manifestPath}: ${error.message}`);
  }

  try {
    JSON.parse(actual);
  } catch (error) {
    throw new Error(`bundle manifest is not valid JSON: ${error.message}`);
  }
  const expected = await expectedManifest(options);
  if (actual !== expected) {
    throw new Error(
      `bundle manifest does not match bridge contracts or packaged files: ${manifestPath}`,
    );
  }

  if (options.stamp !== null) {
    const stamp = `${createHash("sha256").update(actual).digest("hex")}\n`;
    await writeAtomic(options.stamp, stamp);
  }
  process.stdout.write(`web bundle verification passed: ${options.bundleDirectory}\n`);
}

async function printSchemaHash(options) {
  const contractSources = await computeContractSourceHash(options);
  process.stdout.write(
    [
      `contractSourceSha256=${contractSources.contractSourceSha256}`,
      `expectedSchemaHash=${schemaHashPrefix}${contractSources.contractSourceSha256}`,
      `contractSourceCount=${contractSources.sourcePaths.length}`,
      ...contractSources.sourcePaths.map((sourcePath) => `source=${sourcePath}`),
      "",
    ].join("\n"),
  );
}

async function verifyContracts(options) {
  const contractSources = await computeContractSourceHash(options);
  const metadata = await readBridgeMetadata(
    options,
    contractSources.contractSourceSha256,
  );
  process.stdout.write(
    `wire contract verification passed: ${metadata.schemaHash}\n`,
  );
}

try {
  const options = parseArguments(process.argv.slice(2));
  if (options.command === "generate") {
    await generate(options);
  } else if (options.command === "verify") {
    await verify(options);
  } else if (options.command === "schema-hash") {
    await printSchemaHash(options);
  } else {
    await verifyContracts(options);
  }
} catch (error) {
  process.stderr.write(`web bundle manifest error: ${error.message}\n`);
  process.exitCode = 1;
}
