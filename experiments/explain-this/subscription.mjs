import assert from 'node:assert/strict';
import { createHash, createPublicKey, randomBytes, randomUUID, timingSafeEqual, verify } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const issuer = 'https://auth.openai.com';
const resource = 'https://api.openai.com/v1';
const random = () => randomBytes(32).toString('base64url');
const trusted = value => {
  const url = new URL(value);
  assert.equal(url.origin, issuer, 'Unexpected authorization endpoint.');
  assert.ok(!url.username && !url.password, 'Unexpected authorization endpoint credentials.');
  return url.href;
};

export function equalState(received, expected) {
  const left = Buffer.from(received ?? '');
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

export function validateIdentity(token, keys, clientId, nonce, now = Math.floor(Date.now() / 1000)) {
  const pieces = String(token).split('.');
  assert.equal(pieces.length, 3, 'Invalid identity token.');
  const header = JSON.parse(Buffer.from(pieces[0], 'base64url').toString('utf8'));
  assert.equal(header.alg, 'RS256', 'Unsupported identity signature.');
  const key = keys.find(key => key.kid === header.kid && key.kty === 'RSA' && (!key.use || key.use === 'sig') && (!key.alg || key.alg === 'RS256'));
  assert.ok(key, 'Identity signing key not found.');
  assert.ok(verify('RSA-SHA256', Buffer.from(`${pieces[0]}.${pieces[1]}`), createPublicKey({ key, format: 'jwk' }), Buffer.from(pieces[2], 'base64url')), 'Invalid identity signature.');
  const claims = JSON.parse(Buffer.from(pieces[1], 'base64url').toString('utf8'));
  assert.equal(claims.iss, issuer, 'Unexpected identity issuer.');
  assert.ok(typeof claims.sub === 'string' && claims.sub.length > 0, 'Missing identity subject.');
  assert.ok((Array.isArray(claims.aud) ? claims.aud : [claims.aud]).includes(clientId), 'Unexpected identity audience.');
  assert.ok(typeof claims.exp === 'number' && claims.exp > now, 'Identity token expired.');
  assert.ok(claims.nbf === undefined || claims.nbf <= now, 'Identity token is not yet valid.');
  assert.ok(equalState(claims.nonce, nonce), 'Identity nonce mismatch.');
  return claims;
}

async function readJson(url, options = {}) {
  const response = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`Official sign-in request failed (HTTP ${response.status}).`);
  return response.json();
}

async function revoke(endpoint, tokens, clientId) {
  if (!endpoint) return false;
  try {
    const response = await fetch(trusted(endpoint), {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ token: tokens.refresh_token || tokens.access_token, token_type_hint: tokens.refresh_token ? 'refresh_token' : 'access_token', client_id: clientId }),
    });
    return response.ok;
  } catch { return false; }
}

export async function signIn(root) {
  const privateDirectory = resolve(root, '.private/prompt-eval');
  await mkdir(privateDirectory, { recursive: true });
  const registrationPath = resolve(privateDirectory, 'registration.json');
  let registration;
  try { registration = JSON.parse(await readFile(registrationPath, 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw new Error('Could not read prompt-test registration.'); }
  registration ??= { ext_agent_host_id: `urn:uuid:${randomUUID()}` };
  assert.ok(typeof registration.ext_agent_host_id === 'string', 'Invalid prompt-test host.');
  if (registration.client_id) assert.ok(typeof registration.subject === 'string', 'Invalid prompt-test identity.');
  const saveRegistration = async () => {
    const temporaryPath = `${registrationPath}.${randomUUID()}.tmp`;
    await writeFile(temporaryPath, JSON.stringify(registration, null, 2), { mode: 0o600 });
    await rename(temporaryPath, registrationPath);
  };
  await saveRegistration();
  const state = random();
  const nonce = random();
  const verifier = random();
  let settle;
  let fail;
  const callback = new Promise((resolve, reject) => { settle = resolve; fail = reject; });
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (request.method !== 'GET' || url.pathname !== '/auth/callback' || !equalState(url.searchParams.get('state'), state)) {
      response.writeHead(400, { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' });
      response.end('Invalid sign-in callback.');
      return;
    }
    response.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end('Sign-in received. Return to Codex for the prompt comparison.');
    settle(url.searchParams);
  });
  server.requestTimeout = 10000;
  server.headersTimeout = 10000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const timer = setTimeout(() => fail(new Error('Sign-in timed out; rerun the test to get a fresh sign-in link.')), 180000);
  try {
    const redirectUri = `http://127.0.0.1:${server.address().port}/auth/callback`;
    const parameters = new URLSearchParams({
      client_id: registration.client_id || 'dynamic_agent_client',
      ext_agent_host_id: registration.ext_agent_host_id,
      response_type: 'code', redirect_uri: redirectUri,
      scope: 'openid profile email offline_access resource.invoke chatgpt.tokens.use.direct', resource,
      state, nonce, code_challenge_method: 'S256', code_challenge: createHash('sha256').update(verifier).digest('base64url'),
    });
    if (!registration.client_id) parameters.set('agent_name_hint', 'Quill Prompt Comparison');
    console.log(`Continue with ChatGPT: ${issuer}/api/accounts/authorize?${parameters}`);
    const values = await callback;
    if (values.has('error')) throw new Error('ChatGPT sign-in was declined; no model requests were sent.');
    const clientId = values.get('client_id') || registration.client_id;
    assert.ok(clientId && clientId !== 'dynamic_agent_client', 'No issued client registration was returned.');
    assert.ok(!registration.client_id || clientId === registration.client_id, 'Unexpected returning client registration.');
    const code = values.get('code');
    assert.ok(code, 'No sign-in authorization code returned.');
    // Pin discovery/JWKS to the official issuer; tokens never go to an arbitrary endpoint.
    const configuration = await readJson(`${issuer}/.well-known/openid-configuration`);
    assert.equal(configuration.issuer, issuer, 'Unexpected authorization issuer.');
    const tokens = await readJson(`${issuer}/api/accounts/oauth/token`, {
      method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'authorization_code', client_id: clientId, code, code_verifier: verifier, redirect_uri: redirectUri, resource }),
    });
    try {
      const jwks = await readJson(trusted(configuration.jwks_uri));
      const claims = validateIdentity(tokens.id_token, jwks.keys, clientId, nonce);
      assert.ok(!registration.subject || claims.sub === registration.subject, 'Different account from saved prompt-test registration.');
      const scopes = String(tokens.scope ?? '').split(' ');
      assert.ok(scopes.includes('resource.invoke') && scopes.includes('chatgpt.tokens.use.direct'), 'ChatGPT plan usage was not granted.');
      assert.ok(tokens.access_token && tokens.token_type?.toLowerCase() === 'bearer', 'No valid bearer token returned.');
      registration = { ext_agent_host_id: registration.ext_agent_host_id, client_id: clientId, subject: claims.sub };
      await saveRegistration();
      console.log('ChatGPT identity and plan-use permission verified. Credentials are kept in memory only.');
      return { accessToken: tokens.access_token, close: () => revoke(configuration.revocation_endpoint, tokens, clientId) };
    } catch (error) {
      await revoke(configuration.revocation_endpoint, tokens, clientId);
      throw error;
    }
  } finally {
    clearTimeout(timer);
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}
