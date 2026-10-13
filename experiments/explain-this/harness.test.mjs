import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import test from 'node:test';
import { collectResponse, prepareRequests } from './evaluate.mjs';
import { equalState, validateIdentity } from './subscription.mjs';

test('source-derived variants isolate wrapper and grounding changes without leaking reference answers', async () => {
  const plan = await prepareRequests();
  assert.equal(plan.requests.length, 24);
  for (const item of plan.cases) {
    const variants = Object.fromEntries(plan.requests.filter(request => request.caseId === item.id).map(request => [request.variant, request.body]));
    assert.equal(variants.current.instructions, variants['wrapper-only'].instructions);
    assert.deepEqual(variants['wrapper-only'].input, variants.original.input);
    assert.deepEqual(variants.original.input, variants.candidate.input);
    assert.ok(variants.current.instructions.endsWith(variants.original.instructions));
    assert.ok(variants.current.input[0].content.endsWith('REQUEST:\n'));
    assert.ok(variants.original.input[0].content.endsWith('Process the context above based on your instructions.'));
    for (const body of Object.values(variants)) {
      assert.equal(body.model, 'gpt-6-luna');
      assert.equal(body.store, false);
      assert.equal(body.stream, true);
      assert.deepEqual(Object.keys(body).sort(), ['input', 'instructions', 'model', 'store', 'stream']);
      assert.equal(body.input.length, 1);
      assert.ok(body.input[0].content.includes(item.passage));
      assert.ok(!body.instructions.includes('${'));
      if (item.originalAppAnswer) assert.ok(!JSON.stringify(body).includes(item.originalAppAnswer));
    }
  }
});

function eventStream(events, terminate = true) {
  const text = events.map(event => `event: ${event.type}\r\ndata: ${JSON.stringify(event)}\r\n\r\n`).join('');
  const bytes = new TextEncoder().encode(terminate ? text : text.trimEnd());
  const stream = new ReadableStream({ start(controller) {
    // Small fragments split both UTF-8 characters and SSE delimiters.
    for (let offset = 0; offset < bytes.length; offset += 3) controller.enqueue(bytes.slice(offset, offset + 3));
    controller.close();
  } });
  return new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } });
}

test('stream collector preserves UTF-8 text and requires terminal completion', async () => {
  const events = [
    { type: 'response.output_text.delta', delta: 'She’s ' },
    { type: 'response.output_text.delta', delta: 'wailing.' },
    { type: 'response.completed', response: { status: 'completed', model: 'gpt-6-luna', usage: { output_tokens: 4 } } },
  ];
  assert.deepEqual(await collectResponse(eventStream(events, false)), { text: 'She’s wailing.', actualModel: 'gpt-6-luna', usage: { output_tokens: 4 } });
  const unexpectedHeader = eventStream(events);
  unexpectedHeader.headers.set('Content-Type', 'application/json');
  assert.equal((await collectResponse(unexpectedHeader)).text, 'She’s wailing.');
  await assert.rejects(collectResponse(eventStream(events.slice(0, 2))), /without a complete answer/);
  await assert.rejects(collectResponse(eventStream([...events.slice(0, 2), { type: 'response.incomplete' }])), /incompletely/);
  await assert.rejects(collectResponse(eventStream([{ type: 'response.completed', response: { status: 'completed' } }])), /without a complete answer/);
});

test('provider failures do not expose server messages or masquerade as successful answers', async () => {
  await assert.rejects(collectResponse(new Response(JSON.stringify({ error: { code: 'insufficient_quota', message: 'Private server text' } }), { status: 429 })), /^Error: Responses API rejected the request \(HTTP 429, insufficient_quota\)\.$/);
});

test('callback state must match exactly', () => {
  assert.ok(equalState('test-state', 'test-state'));
  assert.ok(!equalState('other-state', 'test-state'));
  assert.ok(!equalState(null, 'test-state'));
});

test('identity validation verifies signature, audience, issuer, expiry, and nonce', () => {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const keys = [{ ...publicKey.export({ format: 'jwk' }), kid: 'fixture-key', use: 'sig' }];
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const claims = { iss: 'https://auth.openai.com', sub: 'fixture-subject', aud: 'fixture-client', exp: 2000, nonce: 'fixture-nonce' };
  const token = changes => {
    const payload = `${encode({ alg: 'RS256', kid: 'fixture-key' })}.${encode({ ...claims, ...changes })}`;
    return `${payload}.${sign('RSA-SHA256', Buffer.from(payload), privateKey).toString('base64url')}`;
  };
  assert.deepEqual(validateIdentity(token({}), keys, 'fixture-client', 'fixture-nonce', 1000), claims);
  for (const change of [{ aud: 'other-client' }, { iss: 'https://example.com' }, { exp: 999 }, { nonce: 'wrong-nonce' }, { nbf: 1001 }]) {
    assert.throws(() => validateIdentity(token(change), keys, 'fixture-client', 'fixture-nonce', 1000));
  }
  const parts = token({}).split('.');
  parts[1] = encode({ ...claims, sub: 'forged-subject' });
  assert.throws(() => validateIdentity(parts.join('.'), keys, 'fixture-client', 'fixture-nonce', 1000), /Invalid identity signature/);
});
