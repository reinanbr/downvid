// Prints uBlock Origin's builtin scriptlets as adblock-rust resources (JSON),
// the way Brave converts them (brave-core-crx-packager, lib/adBlockRustUtils.js).
//
// Usage (scripts/build_adblock_lists.py runs it with Node's permission model:
// read-only access to the uBlock checkout, no network, no child processes):
//   node scripts/ubo_scriptlets.mjs <uBlock>/src/js/resources/scriptlets.js
import { pathToFileURL } from 'node:url'

const { builtinScriptlets } = await import(pathToFileURL(process.argv[2]).href)
const out = builtinScriptlets.map(s => ({
  name: s.name,
  aliases: s.aliases ?? [],
  kind: { mime: 'application/javascript' },
  content: Buffer.from(s.fn.toString()).toString('base64'),
  dependencies: s.dependencies ?? [],
}))
process.stdout.write(JSON.stringify(out))
