/* Asset filters: real frontend DOM in Edge against an isolated HTTP mock. */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const assert = require('assert');
let playwright;
try { playwright = require('playwright'); } catch (error) {
  playwright = require(process.env.IPAM_PLAYWRIGHT || path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
}
const root = path.resolve(__dirname, '..');
const frontend = path.join(root, 'frontend');
const output = path.resolve(process.env.IPAM_ASSET_FILTER_OUTPUT || path.join(root, 'outputs/pool-asset-filter-browser'));
const edge = process.env.IPAM_EDGE || (process.platform === 'darwin' ? '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge' : 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe');
const errors = [], requests = [], poolReads = [];
let browser, server, delayNextPoolRead = false;
const assets = [
  { id: 11, assetType: 'TECH', name: '社保发卡机', active: 1 },
  { id: 12, assetType: 'NON_TECH', name: '态势感知', active: 1 },
  { id: 13, assetType: 'TECH', name: '同名设备', active: 1 },
  { id: 14, assetType: 'NON_TECH', name: '同名设备', active: 1 },
  { id: 15, assetType: 'TECH', name: '停用设备', active: 0 },
  { id: 16, assetType: 'TECH', name: '<img src=x onerror="window.__assetFilterXss=1">', active: 1 },
  { id: 17, assetType: 'TECH', name: '变更后新名', active: 1 },
  { id: 17, assetType: 'NON_TECH', name: '变更前旧名', active: 1 },
  { id: 18, assetType: 'NON_TECH', name: '已删除在用设备', active: 0, deletedAt: '2026-10-28T00:00:00Z' }
];
const outlets = [
  { id: 1, branchId: 1, branchName: '中吴支行', name: '营业室', code: 'O1', active: 1 },
  { id: 2, branchId: 2, branchName: '溧阳支行', name: '燕湖路支行', code: 'O2', active: 1 }
];
const rows = [];
function addRow(asset, extra = {}) {
  const id = rows.length + 1;
  const row = { id, branchId: 1, branchName: '中吴支行', outletId: 1, outletName: '营业室', rangeId: 1, rangeType: 'OFFICE', cidr: '108.56.204.0/24', gateway: '108.56.204.254', ipAddress: '108.56.204.' + id, state: asset ? 'ASSIGNED' : 'AVAILABLE', ...extra };
  if (asset) Object.assign(row, { assignmentId: 1000 + id, assignmentSource: id % 2 ? 'APPLICATION' : 'MANUAL_REGISTRATION', assetId: asset.id, assetType: asset.assetType, assetName: asset.name, manualAssetType: asset.assetType, manualAssetName: asset.name, assetCode: 'CARD-' + id, manualAssetCode: 'CARD-' + id, assignmentVersion: 1 });
  rows.push(row);
}
addRow(null);
addRow(null, { reservationReason: '释放后恢复为可用' });
for (let i = 0; i < 103; i++) addRow(assets[0]);
for (let i = 0; i < 4; i++) addRow(assets[1]);
addRow(assets[2]);
addRow(assets[3]);
addRow(assets[4]);
addRow(assets[6]);
addRow(assets[7]);
addRow(assets[8]);

function reply(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(data));
}
function api(req, bytes, res) {
  const url = new URL(req.url, 'http://127.0.0.1');
  const name = url.pathname.substring(8);
  const params = Object.fromEntries(url.searchParams.entries());
  requests.push({ name, method: req.method, params });
  if (name === 'auth/login') return reply(res, 200, { id: 1, username: 'admin', realName: '资产筛选回归账号', role: 'ADMIN', mustChangePassword: false, csrfToken: 'asset-filter-csrf' });
  if (name === 'admin/dashboard') return reply(res, 200, { branchCount: 2, outletCount: 2, unboundOutletCount: 0, availableIpCount: 2, pendingCount: 0 });
  if (name === 'admin/backups/status') return reply(res, 200, { reminderDue: false });
  if (name === 'admin/outlets' || name === 'outlets') return reply(res, 200, { items: outlets });
  if (name === 'admin/ip-pool-asset-options') {
    return reply(res, 200, { items: assets });
  }
  if (name === 'admin/ip-pool-v2') {
    let found = rows.filter(row => (!params.branchId || String(row.branchId) === params.branchId) && (!params.outletId || String(row.outletId) === params.outletId) && (!params.rangeType || row.rangeType === params.rangeType) && (!params.state || row.state === params.state) && (!params.assetType || row.assetType === params.assetType) && (!params.assetId || String(row.assetId || '') === params.assetId) && (!params.q || (row.ipAddress + ' ' + (row.assetName || '') + ' ' + (row.assetCode || '')).toLowerCase().includes(params.q.toLowerCase())));
    const page = Math.max(1, Number(params.page) || 1), pageSize = 50;
    const result = { items: found.slice((page - 1) * pageSize, page * pageSize), total: found.length, page, pageSize };
    poolReads.push({ params, total: result.total, ids: result.items.map(row => row.id) });
    if (delayNextPoolRead) { delayNextPoolRead = false; return setTimeout(() => reply(res, 200, result), 180); }
    return reply(res, 200, result);
  }
  return reply(res, 200, { items: [] });
}

async function main() {
  fs.mkdirSync(output, { recursive: true });
  server = http.createServer((req, res) => {
    if (req.url.startsWith('/api/v1/')) {
      const chunks = [];
      req.on('data', value => chunks.push(value));
      req.on('end', () => { try { api(req, Buffer.concat(chunks), res); } catch (error) { errors.push(error.message); reply(res, 500, { message: error.message }); } });
      return;
    }
    const target = path.resolve(frontend, '.' + (req.url === '/' ? '/index.html' : req.url.split('?')[0]));
    if (!target.startsWith(frontend + path.sep) || !fs.existsSync(target)) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': target.endsWith('.js') ? 'application/javascript' : target.endsWith('.css') ? 'text/css' : 'text/html' });
    fs.createReadStream(target).pipe(res);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  browser = await playwright.chromium.launch({ executablePath: edge, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('http://127.0.0.1:' + server.address().port);
  await page.fill('#username', 'admin');
  await page.fill('#password', 'Test1234');
  await page.click('#go');
  await page.waitForSelector('#nav');
  await page.click('[data-v="pool"]');
  await page.waitForSelector('#poolAssetType');
  await page.waitForSelector('#poolAssetId option[value="11"]', { state: 'attached' });
  await page.waitForSelector('.pool-table');

  function lastRead() { return poolReads[poolReads.length - 1]; }
  async function rendered(response) {
    await response.finished();
    const expected = { total: lastRead().total, page: lastRead().params.page || '1' };
    await page.waitForFunction(value => document.querySelector('#poolCount').textContent === '共 ' + value.total + ' 个地址' && document.querySelector('#poolPagerInput').value === value.page, expected);
  }
  async function change(selector, value) {
    const response = page.waitForResponse(r => r.url().includes('/api/v1/admin/ip-pool-v2?'));
    await page.selectOption(selector, value);
    await rendered(await response);
  }
  async function clickAndRead(selector) {
    const response = page.waitForResponse(r => r.url().includes('/api/v1/admin/ip-pool-v2?'));
    await page.click(selector);
    await rendered(await response);
  }
  async function options() { return page.locator('#poolAssetId option').evaluateAll(elements => elements.map(element => ({ value: element.value, label: element.textContent }))); }
  async function selectionCleared() {
    assert.equal(await page.locator('#table tbody .pool-check:checked').count(), 0);
    assert.equal(await page.locator('#selectedCount').textContent(), '已选择 0 个空闲 IP');
    assert.equal(await page.locator('#batchOccupied').isDisabled(), true);
  }
  function assertQuery(expected) {
    const actual = lastRead().params;
    for (const key of Object.keys(expected)) assert.equal(actual[key], String(expected[key]), 'query field ' + key);
  }

  assertQuery({ assetType: '', assetId: '', page: 1 });
  assert.equal(lastRead().total, rows.length);
  assert.equal(await page.locator('label[for="poolAssetType"]').textContent(), '资产大类');
  assert((await page.locator('label[for="poolAssetId"]').textContent()).includes('资产小类'));
  let choices = await options();
  const techSame = choices.find(choice => choice.value === '13');
  const nonTechSame = choices.find(choice => choice.value === '14');
  assert(techSame.label.includes('科技资产') && nonTechSame.label.includes('非科技资产'), 'same-name assets must show their category');
  assert.notEqual(techSame.label, nonTechSame.label);
  assert(choices.some(choice => choice.value === '15'), 'inactive assets remain searchable');
  assert(choices.some(choice => choice.value === '16' && choice.label.includes('<img')));
  assert.equal(await page.locator('#poolAssetId img, #poolAssetId script').count(), 0);
  assert.equal(await page.evaluate(() => window.__assetFilterXss || 0), 0);
  assert.equal(choices.filter(choice => choice.value === '17').length, 1, 'same id with multiple snapshots needs one All option');
  assert(choices.find(choice => choice.value === '17').label.includes('变更前旧名') && choices.find(choice => choice.value === '17').label.includes('变更后新名'), 'All option describes old and new snapshots');
  assert(choices.find(choice => choice.value === '18').label.includes('已删除'), 'deleted in-use assets are marked');
  assert(requests.some(request => request.name === 'admin/ip-pool-asset-options'));

  await change('#poolAssetType', 'NON_TECH');
  choices = await options();
  assert(choices.find(choice => choice.value === '17').label.includes('变更前旧名'));
  assert(!choices.find(choice => choice.value === '17').label.includes('变更后新名'), 'original category uses the original assignment snapshot');
  await change('#poolAssetId', '17');
  assertQuery({ assetType: 'NON_TECH', assetId: 17, page: 1 });
  assert.equal(lastRead().total, 1);
  assert((await page.locator('.allocation-summary').textContent()).includes('变更前旧名'));
  await change('#poolAssetId', '18');
  assert.equal(lastRead().total, 1, 'deleted assets with an active assignment remain filterable');
  assert((await page.locator('.allocation-summary').textContent()).includes('已删除在用设备'));
  await change('#poolAssetType', 'TECH');
  choices = await options();
  assert(choices.find(choice => choice.value === '17').label.includes('变更后新名'));
  assert(!choices.find(choice => choice.value === '17').label.includes('变更前旧名'));
  await change('#poolAssetId', '17');
  assert.equal(lastRead().total, 1);
  assert((await page.locator('.allocation-summary').textContent()).includes('变更后新名'));
  await clickAndRead('#poolReset');

  await change('#poolAssetId', '14');
  assertQuery({ assetType: '', assetId: 14, page: 1 });
  assert.equal(lastRead().total, 1, 'small category can be selected independently');
  assert.equal(await page.locator('#poolAssetType').inputValue(), '');
  assert.equal(await page.locator('.pool-table tbody tr').count(), 1);
  assert((await page.locator('.allocation-summary').textContent()).includes('同名设备'));

  await change('#poolAssetType', 'TECH');
  assertQuery({ assetType: 'TECH', assetId: '', page: 1 });
  assert.equal(await page.locator('#poolAssetId').inputValue(), '', 'changing major category clears small category');
  choices = await options();
  assert(choices.some(choice => choice.value === '11') && choices.some(choice => choice.value === '15'));
  assert(!choices.some(choice => choice.value === '12' || choice.value === '14'), 'small options are scoped to the selected category');
  await change('#poolAssetId', '15');
  assertQuery({ assetType: 'TECH', assetId: 15 });
  assert.equal(lastRead().total, 1);
  assert((await page.locator('.allocation-summary').textContent()).includes('停用设备'));

  await change('#poolAssetId', '11');
  await change('#poolBranch', '1');
  await change('#poolOutlet', '1');
  await change('#poolType', 'OFFICE');
  await change('#poolState', 'ASSIGNED');
  await page.fill('#poolSearch', 'CARD');
  await clickAndRead('#poolQuery');
  assertQuery({ branchId: 1, outletId: 1, rangeType: 'OFFICE', state: 'ASSIGNED', assetType: 'TECH', assetId: 11, q: 'CARD', page: 1 });
  assert.equal(lastRead().total, 103);
  assert.equal(await page.locator('.pool-table tbody tr').count(), 50);
  await clickAndRead('#poolPagerNext');
  assertQuery({ branchId: 1, outletId: 1, rangeType: 'OFFICE', state: 'ASSIGNED', assetType: 'TECH', assetId: 11, q: 'CARD', page: 2 });
  assert.equal(await page.locator('#poolAssetType').inputValue(), 'TECH');
  assert.equal(await page.locator('#poolAssetId').inputValue(), '11');
  assert((await page.locator('#poolPager').textContent()).includes('第 2 / 3 页'));
  await change('#poolAssetType', 'NON_TECH');
  assertQuery({ assetType: 'NON_TECH', assetId: '', page: 1 });
  assert.equal(await page.locator('#poolAssetId').inputValue(), '');
  choices = await options();
  assert(choices.some(choice => choice.value === '12') && choices.some(choice => choice.value === '14'));
  assert(!choices.some(choice => choice.value === '11' || choice.value === '13' || choice.value === '15'));

  await clickAndRead('#poolReset');
  assertQuery({ branchId: '', outletId: '', rangeType: '', state: '', assetType: '', assetId: '', q: '', page: 1 });
  for (const selector of ['#poolBranch', '#poolOutlet', '#poolType', '#poolState', '#poolAssetType', '#poolAssetId', '#poolSearch']) assert.equal(await page.locator(selector).inputValue(), '', 'reset clears ' + selector);
  assert.equal(await page.locator('#poolOutlet').isDisabled(), true);
  assert.equal(lastRead().total, rows.length);
  await page.locator('#table tbody .pool-check:not([disabled])').first().check();
  assert.equal(await page.locator('#batchOccupied').isDisabled(), false);
  await change('#poolAssetType', 'TECH');
  await selectionCleared();
  await change('#poolAssetId', '11');
  await clickAndRead('#poolPagerNext');
  await change('#poolAssetId', '13');
  assertQuery({ assetType: 'TECH', assetId: 13, page: 1 });
  assert.equal(lastRead().total, 1, 'same-name TECH selection has a distinct id');

  await clickAndRead('#poolReset');
  await change('#poolState', 'AVAILABLE');
  assert.equal(lastRead().total, 2);
  await page.locator('#table tbody .pool-check:not([disabled])').first().check();
  await clickAndRead('#poolReset');
  await selectionCleared();
  await change('#poolAssetId', '14');
  await page.fill('#poolSearch', '同名设备');
  const enterResponse = page.waitForResponse(r => r.url().includes('/api/v1/admin/ip-pool-v2?'));
  await page.press('#poolSearch', 'Enter');
  await rendered(await enterResponse);
  assertQuery({ assetType: '', assetId: 14, q: '同名设备', page: 1 });
  assert.equal(lastRead().total, 1);

  await clickAndRead('#poolReset');
  await change('#poolAssetType', 'TECH');
  await change('#poolAssetId', '11');
  for (const width of [1440, 1100, 700]) {
    await page.setViewportSize({ width, height: 1100 });
    const layout = await page.evaluate(() => {
      const card = document.querySelector('.pool-filter').getBoundingClientRect();
      const controls = Array.from(document.querySelectorAll('.pool-filter select,.pool-filter input,.pool-filter button')).map(element => { const box = element.getBoundingClientRect(); return { id: element.id, left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height }; });
      const collisions = [];
      controls.forEach((a, index) => controls.slice(index + 1).forEach(b => { if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1) collisions.push(a.id + '/' + b.id); }));
      return { viewport: document.documentElement.clientWidth, documentWidth: document.documentElement.scrollWidth, card: { left: card.left, right: card.right }, controls, collisions };
    });
    assert(layout.documentWidth <= layout.viewport + 1, width + 'px page must not overflow horizontally');
    assert.deepEqual(layout.collisions, [], width + 'px filter controls must not overlap');
    assert(layout.controls.every(control => control.width > 30 && control.height > 20 && control.left >= layout.card.left && control.right <= layout.card.right), width + 'px controls must fit in the filter card');
    await page.screenshot({ path: path.join(output, 'pool-asset-filter-' + width + '.png') });
  }
  delayNextPoolRead = true;
  const delayedResponse = page.waitForResponse(r => r.url().includes('/api/v1/admin/ip-pool-v2?') && new URL(r.url()).searchParams.get('assetId') === '13');
  await page.selectOption('#poolAssetId', '13');
  await change('#poolAssetType', 'NON_TECH');
  await (await delayedResponse).finished();
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const nonTechTotal = rows.filter(row => row.assetType === 'NON_TECH').length;
  assert.equal(await page.locator('#poolCount').textContent(), '共 ' + nonTechTotal + ' 个地址', 'late response must not replace the latest category result');
  assert.equal(await page.locator('.pool-table tbody tr').count(), nonTechTotal);
  assert.equal(await page.locator('#poolAssetType').inputValue(), 'NON_TECH');
  assert.deepEqual(errors, []);
  fs.writeFileSync(path.join(output, 'browser-result.json'), JSON.stringify({ status: 'PASS', screenshots: [1440, 1100, 700].map(width => 'pool-asset-filter-' + width + '.png'), poolRequests: poolReads.length, checks: ['independent-small-category', 'same-name-category-labels', 'inactive-assets', 'renamed-recategorizied-snapshots', 'all-options-unique-id', 'deleted-active-assignment', 'category-cascade', 'combined-filters', 'pagination-retention', 'page-reset-on-change', 'full-reset', 'selection-cleared', 'enter-search', 'escaped-asset-labels', 'wide-medium-narrow-layout', 'stale-response-ignored', 'no-page-errors'], requests }, null, 2));
  console.log('PASS|BROWSER_POOL_ASSET_FILTER|independent-small|same-name-categories|inactive-assets|renamed-recategorized-snapshots|all-unique-id|deleted-in-use|cascade|combined|pagination|reset|selection-clear|enter|escaped-labels|1440-1100-700|stale-response|no-page-errors');
  console.log('OUTPUT|' + output);
}
main().catch(error => { console.error(error.stack || error); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); if (server) await new Promise(resolve => server.close(resolve)); });
