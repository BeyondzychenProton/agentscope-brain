import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';

// The model is the external boundary. This server holds each actual SSE chunk
// until the browser has observed the preceding chunk, including the terminator.
function controlledModel() {
  let response;
  let count = 0;
  let requestReceived;
  const requested = new Promise(resolve => { requestReceived = resolve; });
  const server = createServer((request, reply) => {
    if (request.method !== 'POST' || request.url !== '/v1/chat/completions') {
      reply.writeHead(404).end();
      return;
    }
    request.resume();
    count++;
    request.on('end', () => {
      response = reply;
      reply.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' });
      reply.flushHeaders();
      requestReceived();
    });
  });
  const send = delta => response.write(`data: ${JSON.stringify({
    id: 'fixture-chat', object: 'chat.completion.chunk', created: 1, model: 'fixture-model',
    choices: [{ index: 0, delta: { content: delta }, finish_reason: null }],
  })}\n\n`);
  return {
    async listen() { await new Promise(resolve => server.listen(11435, '127.0.0.1', resolve)); },
    requested,
    count: () => count,
    send,
    finish() {
      response.write(`data: ${JSON.stringify({
        id: 'fixture-chat', object: 'chat.completion.chunk', created: 1, model: 'fixture-model',
        choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
      })}\n\ndata: [DONE]\n\n`);
      response.end();
    },
    async close() { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); },
  };
}

test('the user sees separate text increments before the reply finishes', async ({ page }) => {
  const model = controlledModel();
  await model.listen();
  try {
    await page.goto('/');
    await page.getByRole('textbox', { name: '发送消息' }).fill('请说两句话');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await Promise.race([
      model.requested,
      new Promise((_, reject) => setTimeout(() => reject(new Error('model request did not arrive')), 10_000)),
    ]);

    model.send('第一段');
    await expect(page.getByText('第一段', { exact: false }).last()).toBeVisible();
    await page.getByRole('textbox', { name: '发送消息' }).fill('不得并发执行');
    await page.getByRole('textbox', { name: '发送消息' }).press('Enter');
    await page.waitForTimeout(500);
    expect(model.count()).toBe(1);
    model.send('第二段');
    await expect(page.getByText('第一段第二段', { exact: false }).last()).toBeVisible();
    model.finish();
  } finally {
    await model.close();
  }
});
