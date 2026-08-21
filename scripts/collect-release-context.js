'use strict';

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const tagName = String(process.env.TAG_NAME || process.env.GITHUB_REF_NAME || '').trim();
if (!tagName) {
  console.error('TAG_NAME or GITHUB_REF_NAME is required');
  process.exit(1);
}

const outDir = process.env.RELEASE_OUT_DIR || '.release';
fs.mkdirSync(outDir, { recursive: true });

const logFormat = '%h %s (%an)';

function runGit(args) {
  const result = spawnSync('git', args, { encoding: 'utf8' });
  if (result.status !== 0) {
    return '';
  }
  return (result.stdout || '').trim();
}

function resolveTagCommit(tag) {
  return runGit(['rev-list', '-n', '1', tag]);
}

function listVersionTags() {
  const raw = runGit(['tag', '--list', 'v*', '--sort=-v:refname']);
  return raw ? raw.split(/\r?\n/).filter(Boolean) : [];
}

const tags = listVersionTags();
const tagIndex = tags.indexOf(tagName);
const previousTag = tagIndex >= 0 && tagIndex < tags.length - 1 ? tags[tagIndex + 1] : '';
const version = tagName.replace(/^v/, '');

let commitLog = '';
if (previousTag) {
  commitLog = runGit(['log', `${previousTag}..${tagName}`, `--pretty=format:${logFormat}`]);
}
if (!commitLog) {
  const tagCommit = resolveTagCommit(tagName);
  if (tagCommit) {
    commitLog = runGit(['log', tagCommit, '--max-count', '30', `--pretty=format:${logFormat}`]);
  }
}
if (!commitLog) {
  commitLog = runGit(['log', 'HEAD', '--max-count', '30', `--pretty=format:${logFormat}`]);
}

const context = [
  `Product: Legacy Solar Monitor`,
  `Tag: ${tagName}`,
  `Version: ${version}`,
  `Previous tag: ${previousTag || '(none — first release)'}`,
  '',
  'Commit history for this release:',
  commitLog || '(no commits found)',
  '',
  'Product context:',
  '- Independent Android hobby app for classic Bluetooth SMA Sunny Boy–class inverters (SBFspot-compatible)',
  '- Not affiliated with SMA Solar Technology AG; do not imply official SMA branding',
  '- Live Bluetooth reads, archive sync, SBFspot CSV/ZIP/SQLite import, stats, reports, widgets',
  '- Optional Google Drive backup/restore',
  '- Package: com.alorbach.solarmonitor; Apache License 2.0',
].join('\n');

const outPath = path.join(outDir, 'release-context.txt');
fs.writeFileSync(outPath, context, 'utf8');
console.log(`Wrote ${outPath}`);
