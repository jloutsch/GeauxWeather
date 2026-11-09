// Run: npm test  (node --test tests/*.test.*)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fetchRetry } from '../scripts/check-apis.mjs';

const answer = (status, retryAfter) => ({ status, headers: { get: (h) => (h === 'retry-after' ? retryAfter : null) } });

test('a rate limit (429) is retried, then the real answer is used', async () => {
  const seen = [];
  const replies = [answer(429), answer(503), answer(200)];
  const res = await fetchRetry('u', {}, [1, 1], async () => (seen.push(1), replies.shift()));
  assert.equal(res.status, 200);
  assert.equal(seen.length, 3);
});

test('after the retries run out the last answer is returned (and reported by the caller)', async () => {
  const res = await fetchRetry('u', {}, [1, 1], async () => answer(429));
  assert.equal(res.status, 429);
});

test('a real error (404) is not retried', async () => {
  let calls = 0;
  const res = await fetchRetry('u', {}, [1, 1], async () => (calls++, answer(404)));
  assert.equal(res.status, 404);
  assert.equal(calls, 1);
});

test('network errors are retried, and thrown when they persist', async () => {
  let calls = 0;
  await assert.rejects(fetchRetry('u', {}, [1, 1], async () => (calls++, Promise.reject(new Error('offline')))), /offline/);
  assert.equal(calls, 3);
});
