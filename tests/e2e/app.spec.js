import { test, expect } from '@playwright/test';
import { CFG } from '../../js/config.js';

test.describe('FindMyCar app', () => {
  test.beforeEach(async ({ page }) => {
    // Every test gets a fresh browser profile, so fmc_seen_version_v1 is unset
    // and js/app.js correctly treats the run as a first install — which opens
    // the "מה חדש" modal 1.8s in, over the whole UI. Its backdrop then
    // intercepts every click, so any test that clicks anything times out. Mark
    // the current version as already seen before the app boots, so these tests
    // exercise the normal returning-user state rather than first-launch.
    await page.addInitScript(v => {
      try { localStorage.setItem('fmc_seen_version_v1', JSON.stringify(v)); } catch { /* ignore */ }
    }, CFG.version);
    await page.goto('/');
    // Wait for loading screen to disappear
    await page.waitForSelector('#loadingScreen.fade-out', { timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(1200);
  });

  test('shows the app header with brand name', async ({ page }) => {
    await expect(page.locator('.brand-name')).toHaveText('FindMyCar');
  });

  test('shows no-parking empty state initially', async ({ page }) => {
    // Clear any previous parking data
    await page.evaluate(() => {
      Object.keys(localStorage).forEach(k => {
        if (k.startsWith('fmc_')) localStorage.removeItem(k);
      });
    });
    await page.reload();
    await page.waitForTimeout(1500);
    await expect(page.locator('#noParkingState')).toBeVisible();
  });

  test('status chip shows "אין חניה" by default', async ({ page }) => {
    await page.evaluate(() => {
      Object.keys(localStorage).forEach(k => {
        if (k.startsWith('fmc_')) localStorage.removeItem(k);
      });
    });
    await page.reload();
    await page.waitForTimeout(1500);
    await expect(page.locator('#statusLabel')).toHaveText('אין חניה');
  });

  test('navigates to history view', async ({ page }) => {
    await page.locator('#navBtnHistory').click();
    await expect(page.locator('#historyView')).toHaveClass(/active/);
  });

  test('navigates to vehicles settings view', async ({ page }) => {
    await page.locator('#navBtnSettings').click();
    await expect(page.locator('#settingsView')).toHaveClass(/active/);
  });

  test('settings view shows vehicles list and add button', async ({ page }) => {
    await page.locator('#navBtnSettings').click();
    await expect(page.locator('#vehiclesList')).toBeVisible();
    await expect(page.locator('#addVehicleBtn')).toBeVisible();
  });

  test('theme toggle switches data-theme attribute', async ({ page }) => {
    const initialTheme = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
    await page.locator('#themeToggleBtn').click();
    const newTheme = await page.evaluate(() => document.documentElement.getAttribute('data-theme'));
    expect(newTheme).not.toBe(initialTheme);
  });

  test('opens navigation modal when navigate button is clicked with active parking', async ({ page }) => {
    // Inject a mock parking into the vehicle storage
    await page.evaluate(() => {
      const id = 'test-vehicle-id';
      localStorage.setItem('fmc_vehicles_v1', JSON.stringify([{ id, name: 'Test', icon: '🚗' }]));
      localStorage.setItem('fmc_active_v1', id);
      localStorage.setItem('fmc_cur_' + id, JSON.stringify({
        id: 'p1',
        timestamp: new Date().toISOString(),
        location: { lat: 32.0853, lng: 34.7818, accuracy: 10 },
        address: null,
        description: null,
        photo: null,
        voice: null,
        voiceDuration: 0,
      }));
    });
    await page.reload();
    await page.waitForTimeout(1500);
    // An active parking makes ReturnModal auto-open ("חזרה לרכב") over the whole
    // UI, exactly as a returning user sees it — and its backdrop swallows the
    // click below. Dismiss it the way a user would, rather than clicking through
    // a modal that is genuinely in the way.
    await page.locator('#returnContinueBtn').click();
    await expect(page.locator('#returnModal')).toBeHidden();
    await page.locator('#navigateBtn').click();
    await expect(page.locator('#navModal')).toBeVisible();
  });
});
