// Smoke tests for the main student journeys, against the real published data in web/data.
import { test, expect } from '@playwright/test';

const countOf = async (page) => Number((await page.locator('#count b').textContent()).replace(/[^\d]/g, ''));

test('browse: default view lists fresher-friendly posts with apply links', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#results .card').first()).toBeVisible();
  expect(await page.locator('#results .card').count()).toBeGreaterThan(5);
  const href = await page.locator('#results .card a[href^="http"]').first().getAttribute('href');
  expect(href).toMatch(/^https?:\/\//);
});

test('search: a query narrows the results', async ({ page }) => {
  await page.goto('/');
  await expect(page.locator('#results .card').first()).toBeVisible();
  const all = await countOf(page);
  await page.fill('#q', 'java');
  await expect.poll(() => countOf(page)).toBeLessThan(all);
  expect(await countOf(page)).toBeGreaterThan(0);
});

test('search still works when the search library fails to load', async ({ page }) => {
  await page.route('**/minisearch*', (route) => route.abort());
  await page.goto('/');
  await expect(page.locator('#results .card').first()).toBeVisible();
  const all = await countOf(page);
  await page.fill('#q', 'java');
  await expect.poll(() => countOf(page)).toBeLessThan(all);
  expect(await countOf(page)).toBeGreaterThan(0);
});

test('best fit: keyboard picks a suggestion and ranked matches appear', async ({ page }) => {
  await page.goto('/?view=fit');
  const input = page.locator('#skillInput');
  await input.click();
  await input.pressSequentially('jav');
  await expect(page.locator('#skillSuggest li').first()).toBeVisible();
  await input.press('ArrowDown');
  await expect(input).toHaveAttribute('aria-activedescendant', 'sg0');
  await expect(page.locator('#sg0')).toHaveAttribute('aria-selected', 'true');
  await input.press('Enter');
  await expect(page.locator('#myChips .my-skill')).toHaveCount(1);
  await expect(input).not.toHaveAttribute('aria-activedescendant', /.+/);
  await expect(page.locator('#results .card').first()).toBeVisible();
});

test('mobile filter sheet is modal and returns focus when closed', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.locator('#results .card').first()).toBeVisible();
  await page.click('#filterToggle');
  await expect(page.locator('#filters')).toHaveAttribute('aria-modal', 'true');
  await expect(page.locator('#role')).toBeFocused();
  expect(await page.locator('header.topbar').evaluate((el) => el.inert)).toBe(true);
  // Tab from the last control wraps to the first instead of leaving the sheet.
  for (let i = 0; i < 12; i++) await page.keyboard.press('Tab');
  expect(await page.evaluate(() => !!document.activeElement.closest('#filters'))).toBe(true);
  await page.keyboard.press('Escape');
  await expect(page.locator('#filterToggle')).toBeFocused();
  expect(await page.locator('header.topbar').evaluate((el) => el.inert)).toBe(false);
});

test('stats page shows run numbers', async ({ page }) => {
  await page.goto('/stats.html');
  await expect(page.locator('#kpis .stat')).toHaveCount(5);
  await expect(page.locator('#sources tbody tr').first()).toBeVisible();
});

test('stats page: a run with no API tagging and a failed source says so', async ({ page }) => {
  // Same published stats, edited to look like a degraded run where only Claude tags were imported.
  await page.route('**/data/stats.json', async (route) => {
    const res = await route.fetch();
    const stats = await res.json();
    Object.assign(stats.lastRun.llm, { costPer1000PostsUsd: null, paidEquivalentPer1000PostsUsd: null,
      newlyTagged: 0, calls: 0, byModel: {}, claudeImported: 42 });
    Object.assign(stats.lastRun, { status: 'degraded', lastSuccessfulCollectionAt: '2026-11-18T00:31:00Z' });
    stats.lastRun.sources = [{ name: 'adzuna', status: 'error', failures: [{ unit: 'all', category: 'timeout' }] }];
    await route.fulfill({ response: res, json: stats });
  });
  await page.goto('/stats.html');
  const cost = page.locator('#kpis .stat').nth(3);
  await expect(cost.locator('.value')).toHaveText('–');            // not "$0.00" for "nothing measured"
  await expect(cost).toContainText('No API tagging this run');
  await expect(cost).toContainText('42 Claude routine tags imported');
  await expect(page.locator('#updated')).toContainText('Some sources failed');
  await expect(page.locator('#models')).toContainText('42 tags came from the Claude routine');
  await expect(page.locator('#sources')).toContainText('Error');
});
