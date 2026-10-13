import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomInt } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { signIn } from './subscription.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
const root = resolve(directory, '../..');
const upstream = '585b8e868f1febb854bfb0beb1dd2780f98a08a5';
const model = 'gpt-6-luna';
const normalize = value => value.replaceAll('\r\n', '\n').trim();

export async function prepareRequests() {
  const originalSource = execFileSync('git', ['show', `${upstream}:functions/src/ai/chat/quickChat.ts`], { cwd: root, encoding: 'utf8' });
  const baseline = JSON.parse(await readFile(resolve(directory, 'historical-baseline.json'), 'utf8'));
  const original = originalSource.match(/EXPLAIN_THIS: \(title, author\) => `\s*([\s\S]*?)\s*`\.trim\(\)/)?.[1];
  const current = baseline.explanation;
  assert.ok(original && current, 'Could not extract explanation instructions.');
  assert.equal(normalize(original), normalize(current), 'Shared explanation body has changed; review baselines before running.');
  const prefix = baseline.groundingPrefix;
  const originalWrapper = originalSource.match(/const finalQueryText = `([\s\S]*?)`\.trim\(\)/)?.[1];
  assert.ok(originalWrapper, 'Could not extract original message wrapper.');
  const candidate = normalize(await readFile(resolve(directory, 'candidate.txt'), 'utf8'));
  const cases = JSON.parse(await readFile(resolve(directory, 'cases.json'), 'utf8'));
  const requests = [];
  for (const item of cases) {
    const render = template => template.replaceAll('${title}', item.bookTitle).replaceAll('${author}', item.bookAuthor);
    const originalInput = originalWrapper.replaceAll('${context}', item.passage).replaceAll('${query || "Process the context above based on your instructions."}', 'Process the context above based on your instructions.').trim();
    assert.ok(!originalInput.includes('${'), 'Unresolved original template.');
    const variants = [
      { id: 'current', instructions: prefix + render(normalize(current)), input: baseline.inputTemplate.replaceAll('${passage}', item.passage) },
      { id: 'wrapper-only', instructions: prefix + render(normalize(current)), input: originalInput },
      { id: 'original', instructions: render(normalize(original)), input: originalInput },
      { id: 'candidate', instructions: render(candidate), input: originalInput },
    ];
    for (const variant of variants) {
      requests.push({ caseId: item.id, variant: variant.id, body: { model, store: false, stream: true, instructions: variant.instructions, input: [{ role: 'user', content: variant.input }] } });
    }
  }
  return { upstream, model, cases, requests };
}

export async function collectResponse(response) {
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    const code = String(error?.error?.code ?? 'provider_error').replace(/[^a-zA-Z0-9_.-]/g, '').slice(0, 80);
    throw new Error(`Responses API rejected the request (HTTP ${response.status}, ${code}).`);
  }
  // Match the app: verify SSE events and completion, rather than trusting the header.
  const contentType = response.headers.get('content-type') ?? 'missing';
  let pending = '';
  let text = '';
  let completed = false;
  let actualModel;
  let usage;
  const decoder = new TextDecoder();
  function consume(block) {
    const data = block.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
    if (!data || data === '[DONE]') return;
    const event = JSON.parse(data);
    if (event.type === 'response.output_text.delta') text += event.delta;
    if (event.type === 'response.completed') {
      if (event.response?.status && event.response.status !== 'completed') throw new Error('Response was not completed.');
      completed = true;
      actualModel = event.response?.model;
      usage = event.response?.usage;
    }
    if (['response.failed', 'response.incomplete', 'error'].includes(event.type) || event.error) throw new Error('The model request failed or ended incompletely.');
  }
  function drain() {
    pending = pending.replaceAll('\r\n', '\n');
    let end;
    while ((end = pending.indexOf('\n\n')) >= 0) {
      consume(pending.slice(0, end));
      pending = pending.slice(end + 2);
    }
  }
  for await (const chunk of response.body) {
    pending += decoder.decode(chunk, { stream: true });
    drain();
  }
  pending += decoder.decode();
  drain();
  if (pending.trim()) consume(pending);
  if (!completed || !text.trim()) throw new Error(`Stream ended without a complete answer (content type: ${contentType}).`);
  return { text: text.trim(), actualModel, usage };
}

function shuffled(items) {
  const result = [...items];
  for (let i = result.length - 1; i > 0; i--) {
    const j = randomInt(i + 1);
    [result[i], result[j]] = [result[j], result[i]];
  }
  return result;
}

async function saveReview(outputDirectory, plan, answers) {
  const key = [];
  const lines = ['# Blind explanation review', '', 'Live model outputs only. Variant labels are in review-key.json; review answers before opening it.', '', 'Score meaning, clarity, explanation of difficult wording, and natural tone from 0–2 each. Flag invented story facts, spoilers, or literal readings of figurative claims. Prefer understandable explanations over matching reference wording or merely increasing length.', ''];
  let index = 1;
  for (const item of plan.cases) {
    lines.push(`## ${item.id}`, '', item.passage, '', 'Review points:', ...item.reviewPoints.map(point => `- ${point}`), '');
    for (const answer of shuffled(answers.filter(answer => answer.caseId === item.id))) {
      const label = `Answer ${index++}`;
      key.push({ label, caseId: item.id, variant: answer.variant, repeat: answer.repeat });
      lines.push(`### ${label}`, '', answer.text, '', 'Meaning: __ / 2; Clarity: __ / 2; Unpacking wording: __ / 2; Tone: __ / 2', '', 'Invented facts / spoilers / figurative confusion: __', '');
    }
  }
  await writeFile(resolve(outputDirectory, 'review.md'), lines.join('\n'));
  await writeFile(resolve(outputDirectory, 'review-key.json'), JSON.stringify(key, null, 2));
}

async function main() {
  const plan = await prepareRequests();
  const args = process.argv.slice(2);
  const live = args.includes('--live');
  assert.ok(args.every(arg => ['--live', '--prepare'].includes(arg)), 'Use --prepare or --live.');
  const stamp = new Date().toISOString().replaceAll(':', '-').replaceAll('.', '-');
  const outputDirectory = resolve(root, 'artifacts/prompt-eval', `${live ? 'live' : 'prepared'}-${stamp}`);
  await mkdir(outputDirectory, { recursive: true });
  await writeFile(resolve(outputDirectory, 'requests.json'), JSON.stringify(plan, null, 2));
  const status = { liveModelTest: false, state: 'prepared', model, requestsPerRepeat: plan.requests.length, repeats: 2, outputDirectory };
  const saveStatus = () => writeFile(resolve(outputDirectory, 'status.json'), JSON.stringify(status, null, 2));
  await saveStatus();
  console.log(`Prepared ${plan.requests.length} requests per repeat. Output: ${outputDirectory}`);
  if (!live) {
    console.log('Preparation only: no model calls made and no app files changed.');
    return;
  }
  let session;
  const answers = [];
  try {
    session = await signIn(root);
    const catalogResponse = await fetch('https://api.openai.com/v1/models', { headers: { Authorization: `Bearer ${session.accessToken}` }, signal: AbortSignal.timeout(30000) });
    if (!catalogResponse.ok) throw new Error(`Could not fetch authorized models (HTTP ${catalogResponse.status}).`);
    const catalog = await catalogResponse.json();
    assert.ok(catalog.models?.some(entry => entry.visibility === 'list' && entry.slug === model), 'GPT-6 Luna is not listed for this authorized account. No substitute model was used.');
    status.state = 'running';
    await saveStatus();
    for (let repeat = 1; repeat <= 2; repeat++) {
      for (const request of shuffled(plan.requests)) {
        const response = await fetch('https://api.openai.com/v1/responses', {
          method: 'POST', headers: { Authorization: `Bearer ${session.accessToken}`, 'Content-Type': 'application/json', 'User-Agent': 'QuillPromptComparison/1.0' },
          body: JSON.stringify(request.body), signal: AbortSignal.timeout(120000),
        });
        const answer = { caseId: request.caseId, variant: request.variant, repeat, ...await collectResponse(response) };
        answers.push(answer);
        await writeFile(resolve(outputDirectory, 'answers.json'), JSON.stringify(answers, null, 2));
        console.log(`Completed ${answers.length}/${plan.requests.length * 2}: ${request.caseId} / ${request.variant}`);
      }
    }
    await saveReview(outputDirectory, plan, answers);
    status.liveModelTest = true;
    status.state = 'completed-awaiting-review';
  } catch (error) {
    status.state = 'failed';
    status.failure = error.message;
    throw error;
  } finally {
    status.completedAnswers = answers.length;
    if (session) status.sessionRevocationConfirmed = await session.close();
    await saveStatus();
  }
  console.log(`Model calls completed. Review: ${resolve(outputDirectory, 'review.md')}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
