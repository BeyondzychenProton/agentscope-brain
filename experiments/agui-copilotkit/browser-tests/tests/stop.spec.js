import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';

test('stopping a streaming reply closes the model connection', async ({ page }) => {
  let resolveClosed;
  const closed = new Promise(resolve => { resolveClosed = resolve; });
  const server = createServer(async (req, res) => {
    for await (const _ of req) { /* consume request */ }
    res.on('close', resolveClosed);
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.write(`data: ${JSON.stringify({id:'fixture', object:'chat.completion.chunk', created:1, model:'fixture-model', choices:[{index:0, delta:{content:'未完成片段'}, finish_reason:null}]})}\n\n`);
  });
  await new Promise(resolve => server.listen(11435, '127.0.0.1', resolve));
  try {
    await page.goto('/');
    await page.getByRole('textbox', { name: '发送消息' }).fill('慢回复');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await expect(page.getByText('未完成片段', { exact: false }).last()).toBeVisible();
    await page.getByRole('button', { name: '停止生成' }).click();
    await Promise.race([closed, new Promise((_, reject) => setTimeout(() => reject(new Error('model connection stayed open')), 8000))]);
    await expect(page.getByText('未完成片段', { exact: false }).last()).toBeVisible();
    await expect(page.getByText('已中断，回复未完成', { exact: true })).toBeVisible();
    await expect(page.getByRole('textbox', { name: '发送消息' })).toBeDisabled();
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});

test('starting a fresh chat cancels the current generation', async ({ page }) => {
  let resolveClosed;
  const closed = new Promise(resolve => { resolveClosed = resolve; });
  const server = createServer(async (req, res) => {
    for await (const _ of req) { /* consume request */ }
    res.on('close', resolveClosed);
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.write(`data: ${JSON.stringify({id:'fixture',object:'chat.completion.chunk',created:1,model:'fixture-model',choices:[{index:0,delta:{content:'旧片段'},finish_reason:null}]})}\n\n`);
  });
  await new Promise(resolve => server.listen(11435, '127.0.0.1', resolve));
  try {
    await page.goto('/');
    await page.getByRole('textbox', { name: '发送消息' }).fill('慢回复');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await expect(page.getByText('旧片段', { exact: false }).last()).toBeVisible();
    await page.getByRole('button', { name: '新建临时聊天' }).click();
    await expect(page.getByText('旧片段', { exact: false })).toHaveCount(0);
    await Promise.race([closed, new Promise((_, reject) => setTimeout(() => reject(new Error('previous model connection stayed open')), 8000))]);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});

test('closing the page cancels an active model connection', async ({ page }) => {
  let resolveClosed;
  const closed = new Promise(resolve => { resolveClosed = resolve; });
  const server = createServer(async (req, res) => {
    for await (const _ of req) { /* consume request */ }
    res.on('close', resolveClosed);
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.write(`data: ${JSON.stringify({id:'fixture', object:'chat.completion.chunk', created:1, model:'fixture-model', choices:[{index:0, delta:{content:'尚未结束'}, finish_reason:null}]})}\n\n`);
  });
  await new Promise(resolve => server.listen(11435, '127.0.0.1', resolve));
  try {
    await page.goto('/');
    await page.getByRole('textbox', { name: '发送消息' }).fill('慢回复');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await expect(page.getByText('尚未结束', { exact: false }).last()).toBeVisible();
    await page.close();
    await Promise.race([closed, new Promise((_, reject) => setTimeout(() => reject(new Error('model connection stayed open after page close')), 8000))]);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});
