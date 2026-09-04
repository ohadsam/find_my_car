#!/usr/bin/env node
// Stages the PWA's web assets into www/ for Capacitor's `webDir`.
// This is a build-time copy only (www/ is gitignored) — index.html/js/style.css
// in the repo root remain the single source of truth for both the PWA and the
// native app; this script never edits them.
import { cpSync, rmSync, mkdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const www  = join(root, 'www');

const ASSETS = [
  'index.html',
  'style.css',
  'sw.js',
  'manifest.json',
  'js',
  'icons',
];

rmSync(www, { recursive: true, force: true });
mkdirSync(www, { recursive: true });

for (const asset of ASSETS) {
  const src = join(root, asset);
  if (!existsSync(src)) continue;
  cpSync(src, join(www, asset), { recursive: true });
}

console.log(`Staged ${ASSETS.length} assets into ${www}`);
