import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';

test('a model failure is visible and is not retried', async ({ page }) => {
  let requests = 0;
  const server = createServer((req, res) => {
    requests++;
    req.resume();
    res.writeHead(503, { 'Content-Type': 'application/json' }).end('{"error":"fixture failure"}');
  });
  await new Promise(resolve => server.listen(11435, '127.0.0.1', resolve));
  try {
    await page.goto('/');
    await page.getByRole('textbox', { name: '发送消息' }).fill('失败测试');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await expect(page.getByText('生成失败，请新建临时聊天后重试', { exact: true })).toBeVisible();
    await expect(page.getByRole('textbox', { name: '发送消息' })).toBeDisabled();
    await page.waitForTimeout(1000);
    expect(requests).toBe(1);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});

test('a transport failure is shown without automatic resubmission', async ({ page }) => {
  let attempts = 0;
  await page.route('**/api/copilotkit/agent/default/run', async route => {
    attempts++;
    await route.abort('connectionreset');
  });
  await page.goto('/');
  await page.getByRole('textbox', { name: '发送消息' }).fill('网络失败');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.getByText('生成失败，请新建临时聊天后重试', { exact: true })).toBeVisible();
  await page.waitForTimeout(1000);
  expect(attempts).toBe(1);
  await page.getByRole('button', { name: '新建临时聊天' }).click();
  await expect(page.getByRole('textbox', { name: '发送消息' })).toBeEnabled();
});

test('an empty model reply fails without another model request', async ({ page }) => {
  let requests = 0;
  const server = createServer(async (req, res) => {
    requests++;
    for await (const _ of req) { /* consume */ }
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.end(`data: ${JSON.stringify({id:'fixture',object:'chat.completion.chunk',created:1,model:'fixture-model',choices:[{index:0,delta:{},finish_reason:'stop'}]})}\n\ndata: [DONE]\n\n`);
  });
  await new Promise(resolve => server.listen(11435, '127.0.0.1', resolve));
  try {
    await page.goto('/');
    await page.getByRole('textbox', { name: '发送消息' }).fill('空回复测试');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await expect(page.getByText('生成失败，请新建临时聊天后重试', { exact: true })).toBeVisible({timeout: 15000});
    expect(requests).toBe(1);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});

test('a truncated model stream remains incomplete', async ({ page }) => {
  let requests = 0;
  const server = createServer(async (req, res) => {
    requests++;
    for await (const _ of req) { /* consume */ }
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.end(`data: ${JSON.stringify({id:'fixture', object:'chat.completion.chunk', created:1, model:'fixture-model', choices:[{index:0, delta:{content:'截断片段'}, finish_reason:null}]})}\n\n`);
  });
  await new Promise(resolve => server.listen(11435, '127.0.0.1', resolve));
  try {
    await page.goto('/');
    await page.getByRole('textbox', { name: '发送消息' }).fill('截断测试');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await expect(page.getByText('截断片段', { exact: false }).last()).toBeVisible();
    await expect(page.getByText('生成失败，请新建临时聊天后重试', { exact: true })).toBeVisible();
    expect(requests).toBe(1);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});
