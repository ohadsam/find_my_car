#!/usr/bin/env node
// Parses every shipped JS file and fails on the first syntax error.
//
// This exists because of a real, shipped outage: an unescaped apostrophe in a
// Hebrew changelog string ("ווידג'ט") inside a single-quoted literal broke
// js/config.js, and with it the entire app — every module imports CFG. Nothing
// caught it. `npm test` passed (the unit tests import utils/store/vehicles, not
// config), Capacitor's sync just copies files, and Gradle happily packaged the
// broken asset into a green APK build.
//
// A syntax error in any of these files is total — the app does not start at
// all — so it deserves a gate that cannot be passed by accident. Uses
// `node --check`, which does a real parse (package.json's "type": "module"
// makes that a module-goal parse) without executing or resolving anything.
import { readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = fileURLToPath(new URL('..', import.meta.url));
const files = [
  ...readdirSync(join(root, 'js')).filter(f => f.endsWith('.js')).map(f => join('js', f)).sort(),
  'sw.js',
];

let failed = 0;
for (const rel of files) {
  const res = spawnSync(process.execPath, ['--check', join(root, rel)], { encoding: 'utf8' });
  if (res.status !== 0) {
    console.error(`✗ ${rel}\n${(res.stderr || '').trim().split('\n').slice(0, 4).join('\n')}\n`);
    failed++;
  }
}

if (failed) {
  console.error(`${failed} file(s) failed to parse — the app would not start.`);
  process.exit(1);
}
console.log(`✓ ${files.length} JS files parse cleanly`);
