import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';

test('successive submissions send each completed turn once', async ({ page }) => {
  const requests = [];
  const server = createServer(async (req, res) => {
    const parts = [];
    for await (const part of req) parts.push(part);
    requests.push(JSON.parse(Buffer.concat(parts).toString()));
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    const text = requests.length === 1 ? '回答一' : '回答二';
    const chunk = (delta, finish_reason) => `data: ${JSON.stringify({id:'fixture', object:'chat.completion.chunk', created:1, model:'fixture-model', choices:[{index:0, delta, finish_reason}]})}\n\n`;
    res.end(chunk({content:text}, null) + chunk({}, 'stop') + 'data: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(11435, '127.0.0.1', resolve));
  try {
    await page.goto('/');
    await expect(page.getByRole('textbox', { name: '发送消息' })).toBeVisible();
    await page.waitForTimeout(1000);
    expect(requests).toHaveLength(0);
    await page.getByRole('textbox', { name: '发送消息' }).fill('问题一');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await expect(page.getByText('回答一', { exact: false }).last()).toBeVisible();
    await page.getByRole('textbox', { name: '发送消息' }).fill('问题二');
    await expect(page.getByRole('button', { name: '发送', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await expect(page.getByText('回答二', { exact: false }).last()).toBeVisible();
    await expect.poll(() => requests.length).toBe(2);
    expect(requests[1].messages.filter(m => m.role !== 'system').map(m => [m.role, m.content])).toEqual([
      ['user', '问题一'], ['assistant', '回答一'], ['user', '问题二'],
    ]);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});

test('new chat, refresh and separate pages do not share history', async ({ page, context }) => {
  const requests = [];
  const server = createServer(async (req, res) => {
    const parts = [];
    for await (const part of req) parts.push(part);
    requests.push(JSON.parse(Buffer.concat(parts).toString()));
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    const chunk = (delta, finish_reason) => `data: ${JSON.stringify({id:'fixture',object:'chat.completion.chunk',created:1,model:'fixture-model',choices:[{index:0,delta,finish_reason}]})}\n\n`;
    res.end(chunk({content:`回复${requests.length}`}, null) + chunk({}, 'stop') + 'data: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(11435, '127.0.0.1', resolve));
  const send = async (target, text, answer) => {
    await target.getByRole('textbox', { name: '发送消息' }).fill(text);
    await target.getByRole('button', { name: '发送', exact: true }).click();
    await expect(target.getByText(answer, { exact: false }).last()).toBeVisible();
  };
  try {
    await page.goto('/');
    await send(page, '旧聊天', '回复1');
    await page.getByRole('button', { name: '新建临时聊天' }).click();
    await expect(page.getByText('旧聊天', { exact: true })).toHaveCount(0);
    await send(page, '新聊天', '回复2');
    await page.reload();
    await expect(page.getByText('新聊天', { exact: true })).toHaveCount(0);
    await send(page, '刷新聊天', '回复3');
    const other = await context.newPage();
    await other.goto('/');
    await send(other, '另一页面', '回复4');
    expect(requests.map(request => request.messages.filter(m => m.role !== 'system').map(m => m.content))).toEqual([
      ['旧聊天'], ['新聊天'], ['刷新聊天'], ['另一页面'],
    ]);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});
