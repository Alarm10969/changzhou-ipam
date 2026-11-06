/* V025 real-browser regression. Uses an isolated mock server; never touches a live database. */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const assert = require('assert');
let playwright;
try { playwright = require('playwright'); } catch (error) {
  playwright = require(process.env.IPAM_PLAYWRIGHT || path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
}
const frontend = path.resolve(__dirname, '../frontend');
const edge = process.env.IPAM_EDGE || (process.platform === 'darwin' ? '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge' : 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe');
const requests = [], errors = [];
const network = { branchId: 1, branchName: '中吴支行', outletName: '中吴支行营业室', rangeId: 1, rangeType: 'MONITORING', cidr: '108.56.204.0/24', gateway: '108.56.204.254', dnsPrimary: '108.56.1.1' };
let pool = [
  { ...network, id: 10, ipAddress: '108.56.204.20', state: 'ASSIGNED', editable: 0, assignmentId: 77, assignmentVersion: 3, version: 1, requiresResourceCode: 1, assetName: '视频会议终端', assetCode: 'R001', requiresUser: 0, switchIp: '108.56.195.250', switchPort: '36', macAddress: ' aa bb-cc.旧格式 ', serialNumber: 'SN-001', assignmentSource: 'APPLICATION', allocationSummary: '视频会议终端 · R001 · MAC aa bb-cc.旧格式' },
  { ...network, id: 11, ipAddress: '108.56.204.21', state: 'AVAILABLE', editable: 1, version: 0 },
  { ...network, id: 12, ipAddress: '108.56.204.22', state: 'OCCUPIED', editable: 1, version: 1 },
  { ...network, id: 13, ipAddress: '108.56.204.254', state: 'SYSTEM_RESERVED', editable: 0, version: 0 },
  { ...network, id: 14, ipAddress: '108.56.204.23', state: 'ASSIGNED', editable: 0, assignmentId: 78, assignmentVersion: 1, version: 0, requiresResourceCode: 0, assetName: '办公 PC', switchIp: null, switchPort: '14', assignmentSource: 'APPLICATION', requiresUser: 1, userName: '张三' },
  { ...network, id: 15, ipAddress: '108.56.204.24', state: 'AVAILABLE', editable: 1, version: 0 }
];
let pingIndex = 0, backupDone = false, previewIndex = 0, restoreIndex = 0, polls = 0;
function reply(res, code, value, headers = {}) { res.writeHead(code, { 'Content-Type': 'application/json', ...headers }); res.end(typeof value === 'string' ? value : JSON.stringify(value)); }
function api(name, method, body, res) {
  const payload = body.length && !name.startsWith('admin/backups/restore-preview?') ? JSON.parse(body.toString()) : null;
  requests.push({ name, method, body: payload, bytes: body.length });
  if (name === 'auth/login') return reply(res, 200, { id: payload.username === 'tech' ? 2 : 1, realName: payload.username === 'tech' ? '科技管理员' : '管理员', role: payload.username === 'tech' ? 'TECH_ADMIN' : 'ADMIN', mustChangePassword: false, csrfToken: 'test-csrf' });
  if (name === 'auth/logout') return reply(res, 200, {});
  if (name.startsWith('admin/resource-overview?')) return reply(res, 200, { addressTotal: 10, availableCount: 5, assignedCount: 2, occupiedCount: 3, releasedTotal: 0, pendingCount: 1, branches: [{ branchName: '中吴支行', availableCount: 5, assignedCount: 2, occupiedCount: 3 }], outlets: [{ branchName: '中吴支行', outletName: '营业室', availableCount: 5 }], branchPage: 1, outletPage: 1, branchTotal: 1, outletTotal: 1 });
  if (name === 'admin/dashboard') return reply(res, 200, { branchCount: 1, outletCount: 1, unboundOutletCount: 0, availableIpCount: 2, pendingCount: 1 });
  if (name === 'admin/backups/status') return reply(res, 200, { lastBackupAt: backupDone ? '2026-10-09T00:00:00Z' : null, lastBackupBy: backupDone ? 'admin' : null, reminderDue: !backupDone, reminderDays: 7 });
  if (name === 'admin/backups/download') { backupDone = true; return reply(res, 200, 'isolated-mock-backup', { 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="ipam-test-backup.zip"' }); }
  if (name.startsWith('admin/backups/restore-preview?')) {
    if (decodeURIComponent(name).includes('invalid.zip')) return reply(res, 400, { code: 'BACKUP_INVALID', message: '备份文件无效，未修改当前数据。' });
    return reply(res, 200, { uploadId: 'preview-' + (++previewIndex), backupCreatedAt: '2026-10-01T00:00:00Z', sourceSchema: 22, targetSchema: 22, expiresAt: '2026-10-09T03:00:00Z', counts: { users: 3, branches: 1, outlets: 1, ipAddresses: 254 }, requiresRelogin: true });
  }
  if (name.startsWith('admin/backups/restore-preview/') && method === 'DELETE') return reply(res, 200, {});
  if (name === 'admin/backups/restore-confirm') { restoreIndex++; polls = 0; return reply(res, 202, { jobId: 'job-' + restoreIndex, status: 'QUEUED' }); }
  if (name.startsWith('backup-restore-status/')) { polls++; return reply(res, 200, { status: polls < 2 ? 'RESTORING' : restoreIndex === 1 ? 'FAILED' : 'SUCCEEDED', message: polls < 2 ? '正在恢复，请稍候。' : restoreIndex === 1 ? '恢复失败，当前数据已保留。' : '恢复成功。' }); }
  if (name === 'admin/outlets' || name === 'outlets') return reply(res, 200, { items: [{ id: 1, name: '中吴支行营业室', code: 'O1', branchId: 1, branchName: '中吴支行', active: 1 }] });
  if (name === 'branches') return reply(res, 200, { items: [{ id: 1, name: '中吴支行', code: 'B1', active: 1 }] });
  if (name === 'assets') return reply(res, 200, { items: [] });
  if (name === 'admin/ip-pool-asset-options') return reply(res, 200, { items: [] });
  if (name.startsWith('admin/ip-pool-v2?')) {
    const page = Number((name.match(/[?&]page=(\d+)/) || [, 1])[1]);
    let rows = page === 1 ? pool : [{ ...network, id: 101, ipAddress: '108.56.204.100', state: 'AVAILABLE', editable: 1, version: 0 }];
    const state = decodeURIComponent((name.match(/[?&]state=([^&]+)/) || [, ''])[1]); if (state) rows = rows.filter(x => x.state === state);
    return reply(res, 200, { items: rows, total: 51, page, pageSize: 50 });
  }
  if (name === 'admin/ip-pool/status') { for (const id of payload.ids) { const item = pool.find(x => x.id === id); if (item) { item.state = 'OCCUPIED'; item.version++; } } return reply(res, 200, { updated: payload.ids.length }); }
  if (/^admin\/ip-pool\/\d+$/.test(name) && method === 'PUT') { Object.assign(pool.find(x => x.id === Number(name.split('/')[2])), payload); return reply(res, 200, {}); }
  if (/^admin\/assignments\/\d+\/information$/.test(name)) {
    const item = pool.find(x => x.assignmentId === Number(name.split('/')[2]));
    if (payload.resourceCode === 'STALE') return reply(res, 409, { code: 'ALLOCATION_STALE', message: '当前分配已被其他管理员更新，请刷新。' });
    Object.assign(item, payload, { assetCode: payload.resourceCode || item.assetCode, assignmentVersion: item.assignmentVersion + 1 }); return reply(res, 200, { changed: true });
  }
  if (/^admin\/ip-pool\/\d+\/reachability$/.test(name)) return reply(res, 200, { ipAddress: '108.56.204.20', status: ['REACHABLE', 'NO_REPLY', 'TIMEOUT', 'CHECK_FAILED'][pingIndex++ % 4], elapsedMs: 2 });
  if (/^admin\/ip-pool\/\d+\/manual-assignment$/.test(name)) {
    const id = Number(name.split('/')[2]);
    if (method === 'GET') return reply(res, 200, { pool: { ...pool.find(x => x.id === id), sourceState: pool.find(x => x.id === id).state }, assets: [{ id: 1, assetType: 'TECH', name: '交换机', requiresResourceCode: 1, requiresUser: 0 }], responsibleTechAdmins: [] });
    Object.assign(pool.find(x => x.id === id), { assignmentId: 90 + id, assignmentVersion: 0, state: 'ASSIGNED', assignmentSource: 'MANUAL_REGISTRATION', manualAssetName: '交换机', manualAssetCode: payload.assetCode, requiresResourceCode: 1, ...payload }); return reply(res, 201, {});
  }
  if (name.startsWith('admin/applications?')) return reply(res, 200, { items: [{ id: 42, status: 'PENDING', createdAt: '2026-10-01T00:00:00Z', applicantUsername: 'tech', applicantName: '科技管理员', branchName: '中吴支行', outletName: '营业室', assetType: 'TECH', assetName: '交换机', requiresResourceCode: 1, assetCode: 'R-app', recommendedRangeType: 'OFFICE' }] });
  if (name.startsWith('admin/applications/42/candidates')) return reply(res, 200, { items: [{ id: 11, ipAddress: '108.56.204.21', cidr: '108.56.204.0/24' }], total: 51, page: 1, pageSize: 50 });
  if (name === 'admin/applications/42/approve') return reply(res, 200, {});
  if (name === 'applications') return reply(res, 200, { items: [{ id: 42, status: 'APPROVED', assetType: 'TECH', assetName: '科技管理员设备', finalIpAddress: '108.56.204.21', recommendedRangeType: 'OFFICE', switchPort: 'PRIVATE_PORT', macAddress: 'PRIVATE_MAC', serialNumber: 'PRIVATE_SERIAL', branchName: '中吴支行', outletName: '营业室' }] });
  if (name === 'my-assignments') return reply(res, 200, { items: [{ id: 77, ipAddress: '108.56.204.21', status: 'ASSIGNED', assetName: '科技管理员设备', branchName: '中吴支行', outletName: '营业室', switchPort: 'PRIVATE_PORT', macAddress: 'PRIVATE_MAC', serialNumber: 'PRIVATE_SERIAL' }] });
  if (name === 'admin/audit') return reply(res, 200, { items: [{ action: 'UPDATE_ASSIGNMENT_INFORMATION', changeBefore: '资源编码 R001', changeAfter: '资源编码 R002', targetType: 'IP_ASSIGNMENT', targetId: 77, actor: 'admin', result: 'SUCCESS', createdAt: '2026-10-09T00:00:00Z' }] });
  return reply(res, 200, { items: [] });
}
let server, browser;
async function main() {
  server = http.createServer((req, res) => {
    if (req.url.startsWith('/api/v1/')) { const chunks = []; req.on('data', c => chunks.push(c)); req.on('end', () => { try { api(req.url.substring(8), req.method, Buffer.concat(chunks), res); } catch (e) { reply(res, 500, { message: e.message }); } }); return; }
    const file = path.join(frontend, req.url === '/' ? 'index.html' : req.url.split('?')[0]);
    if (!file.startsWith(frontend) || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': file.endsWith('.js') ? 'application/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html' }); fs.createReadStream(file).pipe(res);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  browser = await playwright.chromium.launch({ executablePath: edge, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
  page.on('pageerror', e => errors.push(e.message));
  await page.goto('http://127.0.0.1:' + server.address().port);
  async function login(name) { await page.fill('#username', name); await page.fill('#password', 'Test1234'); await page.click('#go'); await page.waitForSelector('#nav'); }
  async function navigate(name) { await page.click('[data-v="' + name + '"]'); }
  await login('admin'); await page.waitForSelector('#dismissBackupReminder');
  await page.click('#dismissBackupReminder'); assert.equal(await page.locator('.backup-reminder').count(), 0);
  await navigate('pool'); await page.waitForSelector('.pool-table');
  assert.equal(await page.locator('#table tbody input.pool-check:not([disabled])').count(), 2);
  assert.equal(await page.locator('#table').textContent().then(t => (t.match(/MAC：/g) || []).length), 2);
  assert.equal(await page.locator('[data-port]').count(), 0); assert.equal(await page.locator('button:has-text("不可编辑")').count(), 0);
  await page.click('#poolSelectAll'); assert.equal(await page.locator('#table tbody input.pool-check:checked').count(), 2);
  await page.click('#poolPagerNext'); await page.waitForSelector('input.pool-check[value="101"]'); assert.equal(await page.locator('#table tbody input.pool-check:checked').count(), 0);
  await page.click('#poolPagerPrev'); await page.waitForSelector('input.pool-check[value="11"]'); await page.click('input.pool-check[value="11"]');
  await page.click('#batchOccupied'); await page.click('#confirmSystem'); await page.waitForSelector('#systemModal', { state: 'detached' });
  const occupied = requests.find(x => x.name === 'admin/ip-pool/status'); assert.deepEqual(occupied.body.ids, [11]); assert.equal(occupied.body.state, 'OCCUPIED');
  await page.waitForFunction(() => document.querySelector('input[value="11"]').disabled);
  await page.click('[data-action="information"][data-row="0"]'); await page.waitForSelector('#resourceCode');
  assert.equal(await page.inputValue('#switchIp'), '108.56.195.250'); assert.equal(await page.inputValue('#switchPort'), '36');
  const sameRow = await page.locator('.switch-connection').evaluate(n => Math.abs(n.children[0].getBoundingClientRect().top - n.children[1].getBoundingClientRect().top) < 1); assert(sameRow);
  await page.fill('#resourceCode', 'R002'); await page.fill('#macAddress', '  aa.bb arbitrary MAC  '); await page.fill('#serialNumber', 'SN-002'); await page.click('#saveEdit');
  await page.waitForSelector('#editModal', { state: 'detached' });
  const information = requests.find(x => x.name === 'admin/assignments/77/information'); assert.equal(information.body.expectedVersion, 3); assert.equal(information.body.poolId, 10); assert.equal(information.body.macAddress, '  aa.bb arbitrary MAC  '); assert.equal(information.body.serialNumber, 'SN-002');
  await page.click('[data-action="information"][data-row="4"]'); await page.waitForSelector('#switchPort'); assert.equal(await page.locator('#resourceCode').count(), 0); assert.equal(await page.inputValue('#switchIp'), ''); assert.equal(await page.inputValue('#switchPort'), '14'); await page.click('#saveEdit'); await page.waitForSelector('#editModal', { state: 'detached' });
  assert(!('resourceCode' in requests.find(x => x.name === 'admin/assignments/78/information').body));
  for (const status of ['连通', '不通（无响应）', '检测超时', '检测失败']) { await page.click('[data-action="ping"][data-row="0"]'); await page.waitForFunction(t => document.querySelector('[data-action="ping"][data-row="0"]').textContent === t, status); }
  await page.click('[data-action="information"][data-row="0"]'); await page.fill('#resourceCode', 'STALE'); await page.click('#saveEdit'); await page.waitForSelector('#refreshAllocation'); await page.click('#refreshAllocation'); await page.waitForSelector('#editModal', { state: 'detached' });
  await page.click('[data-action="available"][data-row="2"]'); await page.click('#confirmSystem'); await page.waitForSelector('#systemModal', { state: 'detached' }); assert.equal(requests.find(x => x.name === 'admin/ip-pool/12' && x.method === 'PUT').body.state, 'AVAILABLE');
  await page.click('[data-action="manual"][data-row="1"]'); await page.selectOption('#manualAssetId', '1'); await page.fill('#manualAssetCode', 'SW-001'); await page.fill('#manualSwitchIp', '108.56.195.250'); await page.fill('#manualPort', 'Eth-Trunk1'); await page.fill('#manualMac', ' 任意 MAC 格式 '); await page.fill('#manualSerialNumber', 'SN-manual'); await page.click('#confirmSystem'); assert.equal(await page.locator('#manualMac').isDisabled(), true); await page.click('#confirmSystem'); await page.waitForSelector('#systemModal', { state: 'detached' });
  const manual = requests.find(x => x.name === 'admin/ip-pool/11/manual-assignment' && x.method === 'POST'); assert.equal(manual.body.switchIp, '108.56.195.250'); assert.equal(manual.body.serialNumber, 'SN-manual'); assert.equal(manual.body.macAddress, ' 任意 MAC 格式 ');
  await page.screenshot({ path: path.join(os.tmpdir(), 'ipam-v025-pool-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 700, height: 950 }); assert(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1));
  await navigate('applications'); await page.click('[data-approve]'); await page.waitForSelector('#switchIp'); await page.click('#candidateList input.radio'); await page.fill('#switchIp', '108.56.195.250'); await page.fill('#switchPort', '36'); await page.fill('#macAddress', ' a.b-c raw '); await page.fill('#serialNumber', 'SN-approval');
  assert(await page.locator('.switch-connection').evaluate(n => Math.abs(n.children[0].getBoundingClientRect().top - n.children[1].getBoundingClientRect().top) < 1));
  await page.screenshot({ path: path.join(os.tmpdir(), 'ipam-v025-approval-mobile.png'), fullPage: true }); await page.click('#saveEdit'); await page.waitForSelector('#editModal', { state: 'detached' });
  const approve = requests.find(x => x.name === 'admin/applications/42/approve'); assert.equal(approve.body.macAddress, ' a.b-c raw '); assert.equal(approve.body.switchIp, '108.56.195.250'); assert.equal(approve.body.serialNumber, 'SN-approval');
  await page.setViewportSize({ width: 1440, height: 1000 }); await navigate('audit'); await page.waitForFunction(() => document.querySelector('#table').textContent.includes('资源编码 R002'));
  await navigate('overview'); await page.waitForFunction(() => document.querySelector('#branchTable').textContent.includes('50%')); assert((await page.locator('#overviewStats').textContent()).includes('已占用3')); assert((await page.locator('#branchTable').textContent()).includes('已占用'));
  await navigate('exports'); await page.selectOption('#exportType', 'STATISTICS'); assert.equal(await page.locator('#exportFieldList input[value="occupiedCount"]').count(), 1);
  await navigate('backups'); await page.waitForSelector('#downloadBackup'); await page.waitForFunction(() => document.querySelector('#backupStatus').textContent.includes('尚无备份记录')); await page.screenshot({ path: path.join(os.tmpdir(), 'ipam-v025-backups.png'), fullPage: true });
  const download = page.waitForEvent('download'); await page.click('#downloadBackup'); assert.equal((await download).suggestedFilename(), 'ipam-test-backup.zip'); await page.waitForFunction(() => document.querySelector('#backupStatus').textContent.includes('admin'));
  async function preview(name) { await page.setInputFiles('#restoreFile', { name, mimeType: 'application/zip', buffer: Buffer.from('mock-backup') }); await page.click('#previewRestore'); }
  await preview('invalid.zip'); await page.waitForFunction(() => document.querySelector('#restoreNotice').textContent.includes('无效')); assert.equal(await page.locator('#confirmRestore').count(), 0);
  assert.equal(await page.locator('#restoreFile').getAttribute('accept'), '.zip,.db');
  await preview('good.db'); await page.waitForSelector('#confirmRestore'); assert(requests.some(x => x.name.includes('fileName=good.db'))); await page.click('#confirmRestore'); await page.click('#cancelSystem'); await page.waitForSelector('#systemModal', { state: 'detached' }); assert(requests.some(x => x.method === 'DELETE' && x.name.startsWith('admin/backups/restore-preview/')));
  await preview('good.zip'); await page.waitForSelector('#confirmRestore'); await page.click('#confirmRestore'); await page.click('#confirmSystem'); assert.equal(requests.filter(x => x.name === 'admin/backups/restore-confirm').length, 0); await page.check('#acknowledgeRestore'); await page.click('#confirmSystem');
  await page.waitForFunction(() => document.querySelector('#restoreNotice') && document.querySelector('#restoreNotice').textContent.includes('恢复失败')); assert.equal(await page.locator('#nav').count(), 1);
  await preview('good.zip'); await page.waitForSelector('#confirmRestore'); await page.click('#confirmRestore'); await page.check('#acknowledgeRestore'); await page.click('#confirmSystem'); await page.waitForSelector('#go'); assert((await page.locator('#notice').textContent()).includes('备份中的账号和密码'));
  await login('tech'); assert.equal(await page.locator('[data-v="backups"]').count(), 0); assert.equal(await page.locator('.backup-reminder').count(), 0);
  await navigate('mine'); await page.waitForFunction(() => document.querySelector('#table') && document.querySelector('#table').textContent.includes('科技管理员设备'));
  assert(!/PRIVATE_|序列号|MAC 地址|交换机端口/.test(await page.locator('body').textContent()));
  await navigate('myips'); await page.waitForFunction(() => document.querySelector('#table') && document.querySelector('#table').textContent.includes('108.56.204.21'));
  assert(!/PRIVATE_|序列号|MAC 地址|交换机端口/.test(await page.locator('body').textContent()));
  assert.deepEqual(errors, []);
  console.log('PASS|BROWSER_V025|selection-current-page|batch-occupied|assigned-edit|legacy-port|mac-verbatim|serial-admin-only|ping-chinese|occupied-statistics|backup-download|restore-db-invalid-cancel-fail-success|desktop-mobile');
}
main().catch(error => { console.error(error.stack || error); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); if (server) await new Promise(resolve => server.close(resolve)); });
