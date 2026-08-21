'use strict';

const fs = require('fs');
const path = require('path');

const outDir = process.env.RELEASE_OUT_DIR || '.release';
const endpoint = process.env.GITHUB_MODELS_ENDPOINT || 'https://models.github.ai/inference/chat/completions';
const model = process.env.RELEASE_AI_MODEL || 'openai/gpt-4.1-mini';

function readText(name) {
  return fs.readFileSync(path.join(outDir, name), 'utf8').trim();
}

const schema = {
  name: 'release_notes',
  strict: true,
  schema: {
    type: 'object',
    properties: {
      description_en: { type: 'string' },
      description_de: { type: 'string' },
      changelog_en: { type: 'array', items: { type: 'string' } },
      changelog_de: { type: 'array', items: { type: 'string' } },
    },
    additionalProperties: false,
    required: ['description_en', 'description_de', 'changelog_en', 'changelog_de'],
  },
};

async function main() {
  const token = process.env.GITHUB_TOKEN;
  if (!token) {
    throw new Error('GITHUB_TOKEN is required for GitHub Models');
  }

  const releaseContext = readText('release-context.txt');
  const githubNotes = readText('github-notes.md');

  const response = await fetch(endpoint, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify({
      model,
      temperature: 0.3,
      max_tokens: 2000,
      messages: [
        {
          role: 'system',
          content:
            'You write bilingual release notes for Legacy Solar Monitor, an independent Android app for classic Bluetooth SMA inverters. Respond only with JSON matching the schema. Focus on end-user value. Do not invent features. Do not imply affiliation with SMA.',
        },
        {
          role: 'user',
          content: [
            'Draft release notes for Legacy Solar Monitor.',
            '',
            'Release context:',
            releaseContext,
            '',
            'GitHub auto-generated changelog (technical reference):',
            githubNotes,
          ].join('\n'),
        },
      ],
      response_format: {
        type: 'json_schema',
        json_schema: schema,
      },
    }),
  });

  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`GitHub Models request failed (${response.status}): ${detail.slice(0, 500)}`);
  }

  const payload = await response.json();
  const content = payload?.choices?.[0]?.message?.content;
  if (!content) {
    throw new Error('GitHub Models returned no message content');
  }

  const parsed = JSON.parse(content);
  fs.writeFileSync(path.join(outDir, 'ai-release.json'), JSON.stringify(parsed, null, 2), 'utf8');
  console.log('Wrote .release/ai-release.json');
}

main().catch((err) => {
  console.warn(`AI release notes skipped: ${err.message}`);
  process.exit(0);
});
