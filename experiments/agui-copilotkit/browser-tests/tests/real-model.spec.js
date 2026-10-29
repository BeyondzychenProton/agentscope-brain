import { test, expect } from '@playwright/test';

test('real configured model streams and remembers a previous turn', async ({ page }) => {
  test.skip(process.env.REAL_MODEL !== '1', 'Requires the configured real model service');
  test.setTimeout(120_000);
  await page.goto('/');
  const input = page.getByRole('textbox', { name: '发送消息' });
  await input.fill('请记住暗号“蓝色星河”，然后用中文写一段约一百字的短文。');
  await page.evaluate(() => {
    window.__observed = [];
    const target = document.querySelector('[data-testid="copilot-message-list"]');
    new MutationObserver(() => window.__observed.push({time: Date.now(), text: target.innerText})).observe(target, { childList: true, subtree: true, characterData: true });
  });
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.getByRole('button', { name: '停止生成' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: '停止生成' })).toHaveCount(0, { timeout: 90_000 });
  await expect(page.getByText('生成失败，请新建临时聊天后重试')).toHaveCount(0);
  const observations = await page.evaluate(() => window.__observed.map(item => item.text).filter((text, i, all) => i === 0 || text !== all[i - 1]));
  expect(observations.length).toBeGreaterThan(2);
  const before = (await page.getByTestId('copilot-message-list').innerText()).match(/蓝色星河/g)?.length ?? 0;
  await input.fill('我刚才让你记住的暗号是什么？只回答暗号。');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  await expect(page.getByRole('button', { name: '停止生成' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('button', { name: '停止生成' })).toHaveCount(0, { timeout: 90_000 });
  await expect(page.getByText('生成失败，请新建临时聊天后重试')).toHaveCount(0);
  const text = await page.getByTestId('copilot-message-list').innerText();
  expect(text.match(/蓝色星河/g)?.length ?? 0).toBeGreaterThan(before);
  console.log(`Real model: ${observations.length} distinct visible text updates in the first turn; second turn recalled the synthetic phrase.`);
});
