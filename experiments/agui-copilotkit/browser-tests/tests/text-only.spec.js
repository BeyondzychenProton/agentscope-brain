import { test, expect } from '@playwright/test';
import { createServer } from 'node:http';

test('tool-bearing requests are rejected before calling the model', async ({ page }) => {
  let requests = 0;
  const server = createServer((req, res) => { requests++; req.resume(); res.writeHead(500).end(); });
  await new Promise(resolve => server.listen(11435, '127.0.0.1', resolve));
  try {
    await page.route('**/api/copilotkit/agent/default/run', async route => {
      const input = route.request().postDataJSON();
      input.tools = [{ name: 'unsupported_tool', description: 'unsupported', parameters: { type: 'object', properties: {} } }];
      await route.continue({ postData: JSON.stringify(input) });
    });
    await page.goto('/');
    await page.getByRole('textbox', { name: '发送消息' }).fill('文字');
    await page.getByRole('button', { name: '发送', exact: true }).click();
    await expect(page.getByText('生成失败，请新建临时聊天后重试', { exact: true })).toBeVisible();
    expect(requests).toBe(0);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});
