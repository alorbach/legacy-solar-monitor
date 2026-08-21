'use strict';

const fs = require('fs');
const path = require('path');

const outDir = process.env.RELEASE_OUT_DIR || '.release';
const tagName = String(process.env.TAG_NAME || process.env.GITHUB_REF_NAME || 'v0.0.0').trim();
const version = process.env.RELEASE_VERSION || tagName.replace(/^v/, '');
const versionCode = process.env.RELEASE_VERSION_CODE || '';

const apkUniversal = process.env.APK_UNIVERSAL_NAME
  || `legacy-solar-monitor-${version}-vc${versionCode}-universal.apk`;
const apkArm64 = process.env.APK_ARM64_NAME
  || `legacy-solar-monitor-${version}-vc${versionCode}-arm64-v8a.apk`;
const apkArmv7 = process.env.APK_ARMV7_NAME
  || `legacy-solar-monitor-${version}-vc${versionCode}-armeabi-v7a.apk`;
const apkX86 = process.env.APK_X86_NAME
  || `legacy-solar-monitor-${version}-vc${versionCode}-x86_64.apk`;
const aabName = process.env.AAB_NAME
  || `legacy-solar-monitor-${version}-vc${versionCode}.aab`;

const qrUniversal = process.env.QR_UNIVERSAL_NAME || 'qr-universal.png';
const qrArm64 = process.env.QR_ARM64_NAME || 'qr-arm64-v8a.png';
const qrArmv7 = process.env.QR_ARMV7_NAME || 'qr-armeabi-v7a.png';
const qrX86 = process.env.QR_X86_NAME || 'qr-x86_64.png';

const repo = process.env.GITHUB_REPOSITORY || 'alorbach/legacy-solar-monitor';
const downloadBase = `https://github.com/${repo}/releases/download/${tagName}`;

function readText(filePath) {
  try {
    return fs.readFileSync(filePath, 'utf8').trim();
  } catch {
    return '';
  }
}

function bullets(items) {
  return (items || []).map((item) => `- ${String(item).trim()}`).filter((line) => line !== '-').join('\n');
}

const githubNotes = readText(path.join(outDir, 'github-notes.md'))
  || 'No generated changelog entries were returned for this tag.';

let ai = null;
const aiRaw = readText(path.join(outDir, 'ai-release.json'));
if (aiRaw) {
  try {
    ai = JSON.parse(aiRaw);
  } catch (err) {
    console.warn('Could not parse ai-release.json:', err.message);
  }
}

const descriptionEn = ai?.description_en
  || 'Independent Android app for classic Bluetooth SMA Sunny Boy–class inverters: live power, archive sync, imports, stats, widgets, and optional Drive backup.';
const descriptionDe = ai?.description_de
  || 'Unabhängige Android-App für klassische Bluetooth-SMA-Sunny-Boy-Wechselrichter: Live-Leistung, Archiv-Sync, Importe, Statistik, Widgets und optionales Drive-Backup.';
const changelogEn = ai?.changelog_en?.length
  ? bullets(ai.changelog_en)
  : githubNotes;
const changelogDe = ai?.changelog_de?.length
  ? bullets(ai.changelog_de)
  : changelogEn;

const usedAi = Boolean(ai?.description_en || ai?.changelog_en?.length);

const body = [
  `# Legacy Solar Monitor ${tagName}`,
  '',
  descriptionEn,
  '',
  descriptionDe,
  '',
  '## Downloads',
  '',
  `- Universal APK (recommended for sideload): \`${apkUniversal}\``,
  `- arm64-v8a APK: \`${apkArm64}\``,
  `- armeabi-v7a APK: \`${apkArmv7}\``,
  `- x86_64 APK: \`${apkX86}\``,
  `- Play App Bundle: \`${aabName}\``,
  '',
  'If install fails with “App not installed”, uninstall any Studio/debug build of Legacy Solar Monitor first, then retry the universal APK.',
  '',
  'Bei „App nicht installiert“ zuerst eine vorhandene Studio-/Debug-Installation deinstallieren, danach die Universal-APK erneut versuchen.',
  '',
  '### Scan to install (universal APK)',
  '',
  `![QR universal APK](${downloadBase}/${qrUniversal})`,
  '',
  '',
  '---',
  '',
  '',
  '### arm64-v8a',
  '',
  `![QR arm64](${downloadBase}/${qrArm64})`,
  '',
  '',
  '---',
  '',
  '',
  '### armeabi-v7a',
  '',
  `![QR armeabi-v7a](${downloadBase}/${qrArmv7})`,
  '',
  '',
  '---',
  '',
  '',
  '### x86_64',
  '',
  `![QR x86_64](${downloadBase}/${qrX86})`,
  '',
  '',
  '---',
  '',
  '',

  '## What\'s new',
  '',
  changelogEn,
  '',
  '## Neu in dieser Version',
  '',
  changelogDe,
  '',
  '## Validation',
  '',
  'This release was built by GitHub Actions after unit tests and signed release packaging completed successfully.',
  usedAi
    ? 'Release notes were drafted with GitHub Models from commit history.'
    : 'Release notes use GitHub auto-generated changelog (AI step unavailable).',
  '',
  '## Technical changelog',
  '',
  githubNotes,
  '',
  '---',
  '',
  'Copyright (c) Andre Lorbach — https://github.com/alorbach',
  'License: Apache-2.0',
  '',
].join('\n');

fs.mkdirSync(outDir, { recursive: true });
const outPath = path.join(outDir, 'release-notes.md');
fs.writeFileSync(outPath, body, 'utf8');
console.log(`Wrote ${outPath}${usedAi ? ' (AI notes)' : ' (fallback)'}`);
