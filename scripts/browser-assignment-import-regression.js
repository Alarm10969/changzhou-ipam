/* V026 assignment import: real browser DOM against an isolated mock; no live business data. */
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
const requests = [], errors = [], jobs = [];
let server, browser, poolReads = 0;

function clone(value) { return JSON.parse(JSON.stringify(value)); }
function reply(res, status, data, headers = {}) { res.writeHead(status, { 'Content-Type': 'application/json', ...headers }); res.end(typeof data === 'string' ? data : JSON.stringify(data)); }
function row(number, result, extra = {}) { return { rowNumber: number, ipAddress: '108.56.204.' + (number + 10), assetType: 'TECH', assetName: '态势感知采集主机', branchName: '中吴支行', outletName: '营业室', fields: { IP地址: '108.56.204.' + (number + 10), 资产类型: '科技资产', 资产名称: '态势感知采集主机', 资源编码: 'R-001', 使用人姓名: '<img src=x onerror="window.__importXss=1">', 统一认证号: 'U-001', 负责科技管理员账号: 'tech', 交换机IP: '108.56.195.250', 交换机端口: '36', MAC地址: '原始 MAC 内容', 设备序列号: 'SN-001', 补录说明: '现网设备核对' }, result, code: result === 'ERROR' ? 'IP_CONFLICT' : result === 'DUPLICATE' ? 'DUPLICATE_MATCH' : 'READY', message: result === 'ERROR' ? '该 IP 已分配给其他设备，不允许覆盖' : result === 'DUPLICATE' ? '已有相同补录，跳过' : '校验通过', ...extra }; }
function createJob(name) {
  const job = { id: 'import-' + (jobs.length + 1), fileName: name, status: 'PREVIEW_READY', previewVersion: 1, totalRows: 4, readyRows: 2, duplicateRows: 1, errorRows: 1, importedRows: 0, notImportedRows: 2, rows: [row(2, 'READY'), row(3, 'DUPLICATE'), row(4, 'ERROR'), row(5, 'READY', { assetName: '<img src=x onerror="window.__importXss=1">', message: '<script>window.__importXss=1</script>' })] };
  if (name === 'conflicts.csv') Object.assign(job, { totalRows: 2, readyRows: 0, duplicateRows: 0, errorRows: 2, notImportedRows: 2, rows: [row(2, 'ERROR'), row(3, 'ERROR')] });
  if (name === 'duplicates.csv') Object.assign(job, { totalRows: 1, readyRows: 0, duplicateRows: 1, errorRows: 0, notImportedRows: 1, rows: [row(2, 'DUPLICATE')] });
  jobs.unshift(job);
  return job;
}
function api(name, req, bytes, res) {
  const multipart = /multipart\/form-data/i.test(req.headers['content-type'] || ''), payload = !multipart && bytes.length ? JSON.parse(bytes.toString()) : null;
  requests.push({ name, method: req.method, payload, csrf: req.headers['x-csrf-token'] });
  if (name === 'auth/login') return reply(res, 200, { id: payload.username === 'tech' ? 2 : 1, username: payload.username, realName: '回归账号', role: payload.username === 'tech' ? 'TECH_ADMIN' : 'ADMIN', mustChangePassword: false, csrfToken: 'import-test-csrf' });
  if (name === 'auth/logout') return reply(res, 200, {});
  if (name === 'admin/dashboard') return reply(res, 200, { branchCount: 1, outletCount: 1, unboundOutletCount: 0, availableIpCount: 2, pendingCount: 0 });
  if (name === 'admin/backups/status') return reply(res, 200, { reminderDue: false });
  if (name === 'admin/outlets' || name === 'outlets') return reply(res, 200, { items: [{ id: 1, code: 'O1', name: '营业室', branchId: 1, branchName: '中吴支行', active: 1 }] });
  if (name === 'admin/ip-pool-asset-options') return reply(res, 200, { items: [] });
  if (name.startsWith('admin/ip-pool-v2?')) { poolReads++; return reply(res, 200, { items: [{ id: 1, branchName: '中吴支行', outletName: '营业室', rangeType: 'OFFICE', cidr: '108.56.204.0/24', gateway: '108.56.204.254', ipAddress: '108.56.204.12', state: jobs.some(j => j.status === 'COMPLETED') ? 'ASSIGNED' : 'AVAILABLE' }], total: 1, page: 1, pageSize: 50 }); }
  if (name === 'admin/assignment-imports/template.csv' || name === 'admin/assignment-imports/template.xlsx') return reply(res, 200, 'ip_address,asset_type,asset_name\n', { 'Content-Type': 'application/octet-stream', 'Content-Disposition': 'attachment; filename="assignment-template.' + (name.endsWith('xlsx') ? 'xlsx' : 'csv') + '"' });
  if (name === 'admin/assignment-imports/recent') return reply(res, 200, { items: jobs.map(j => clone(j)) });
  if (name === 'admin/assignment-imports' && req.method === 'POST') { assert(multipart, 'upload must use multipart FormData'); const found = bytes.toString().match(/filename="([^"]+)"/); return reply(res, 201, clone(createJob(found ? found[1] : 'unknown.csv'))); }
  const found = name.match(/^admin\/assignment-imports\/([^/]+)(?:\/(confirm|not-imported\.csv))?$/);
  if (found) {
    const job = jobs.find(j => j.id === found[1]); if (!job) return reply(res, 404, { message: '任务不存在' });
    if (found[2] === 'not-imported.csv') return reply(res, 200, 'ip_address,result,message\n108.56.204.13,DUPLICATE,skip\n', { 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': 'attachment; filename="not-imported.csv"' });
    if (found[2] === 'confirm') {
      assert.equal(req.headers['x-csrf-token'], 'import-test-csrf'); assert.equal(payload.acceptPartial, true); assert.equal(payload.previewVersion, job.previewVersion);
      if (job.fileName === 'http409.csv' && !job.errorSent) { job.errorSent = true; return reply(res, 409, { code: 'IMPORT_BUSY', message: '任务正在被其他管理员处理，请重新读取预览。' }); }
      if (job.fileName === 'changed.csv' && !job.reviewSent) { job.reviewSent = true; job.previewVersion++; job.readyRows = 1; job.errorRows = 2; job.notImportedRows = 3; job.rows[0] = row(2, 'ERROR', { message: '预览后被其他管理员分配，请核对' }); return reply(res, 200, { ...clone(job), requiresReview: true }); }
      job.status = 'COMPLETED'; job.importedRows = job.readyRows; job.notImportedRows = job.totalRows - job.importedRows; job.rows.forEach(r => { if (r.result === 'READY') r.result = 'IMPORTED'; }); job.readyRows = 0;
      return setTimeout(() => reply(res, 200, clone(job)), 120);
    }
    if (req.method === 'DELETE') { job.status = 'CANCELLED'; return reply(res, 204, ''); }
    return reply(res, 200, clone(job));
  }
  return reply(res, 200, { items: [] });
}
async function main() {
  server = http.createServer((req, res) => {
    if (req.url.startsWith('/api/v1/')) { const chunks = []; req.on('data', c => chunks.push(c)); req.on('end', () => { try { api(req.url.substring(8), req, Buffer.concat(chunks), res); } catch (error) { reply(res, 500, { message: error.message }); } }); return; }
    const target = path.resolve(frontend, '.' + (req.url === '/' ? '/index.html' : req.url.split('?')[0]));
    if (!target.startsWith(frontend + path.sep) || !fs.existsSync(target)) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': target.endsWith('.js') ? 'application/javascript' : target.endsWith('.css') ? 'text/css' : 'text/html' }); fs.createReadStream(target).pipe(res);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  browser = await playwright.chromium.launch({ executablePath: edge, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('http://127.0.0.1:' + server.address().port);
  async function login(username) { await page.fill('#username', username); await page.fill('#password', 'Test1234'); await page.click('#go'); await page.waitForSelector('#nav'); }
  async function preview(name) { await page.setInputFiles('#assignmentImportFile', { name, mimeType: 'text/csv', buffer: Buffer.from('ip_address,asset_type,asset_name\n') }); await page.click('#previewAssignmentImport'); await page.waitForFunction(n => document.querySelector('.assignment-import-meta') && document.querySelector('.assignment-import-meta').textContent.includes(n), name); }
  async function download(selector, expected) { const event = page.waitForEvent('download'); await page.click(selector); assert.equal((await event).suggestedFilename(), expected); }
  await login('admin'); await page.click('[data-v="pool"]'); await page.waitForSelector('.pool-table');
  assert.equal(await page.locator('#batchOccupied').isDisabled(), true);
  await page.click('#openAssignmentImport'); await page.waitForSelector('#assignmentImportFile');
  assert.equal(await page.locator('#table tbody input:checked').count(), 0, 'entry must not require page selection');
  assert.equal(await page.locator('#assignmentImportFile').getAttribute('accept'), '.csv,.xlsx');
  await download('#assignmentTemplateCsv', 'assignment-template.csv'); await download('#assignmentTemplateXlsx', 'assignment-template.xlsx');
  await preview('mixed.csv');
  assert.equal(await page.locator('#confirmAssignmentImport').isDisabled(), false);
  assert((await page.locator('#confirmAssignmentImport').textContent()).includes('2条'));
  assert((await page.locator('#assignmentImportImpact').textContent()).includes('其余 2 条不导入'));
  assert((await page.locator('#downloadAssignmentNotImported').textContent()).includes('2条'));
  assert(!(await page.locator('.assignment-import-actions').textContent()).includes('尚待确认的通过行'));
  assert.equal(await page.locator('.assignment-import-table tbody tr').count(), 4);
  await page.click('[data-assignment-row-details="0"]'); assert.equal(await page.locator('#assignmentImportFields0').isVisible(), true);
  assert((await page.locator('#assignmentImportFields0').textContent()).includes('R-001')); assert((await page.locator('#assignmentImportFields0').textContent()).includes('SN-001'));
  assert.equal(await page.locator('.assignment-import-table img, .assignment-import-table script').count(), 0);
  assert.equal(await page.evaluate(() => window.__importXss || 0), 0);
  await page.click('[data-assignment-row-details="0"]'); assert.equal(await page.locator('#assignmentImportFields0').isVisible(), false);
  await page.setViewportSize({ width: 700, height: 950 });
  assert(await page.locator('.assignment-import-dialog').evaluate(n => n.scrollWidth <= n.clientWidth + 1));
  await page.locator('.assignment-import-dialog').evaluate(n => { n.scrollTop = 0; });
  await page.screenshot({ path: path.join(os.tmpdir(), 'ipam-v026-assignment-import-mobile.png') });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.locator('.assignment-import-dialog').evaluate(n => { n.scrollTop = 0; });
  await page.screenshot({ path: path.join(os.tmpdir(), 'ipam-v026-assignment-import-desktop.png') });
  await download('#downloadAssignmentNotImported', 'not-imported.csv');
  const reads = poolReads;
  await page.click('#confirmAssignmentImport'); assert.equal(await page.locator('#previewAssignmentImport').isDisabled(), true);
  await page.waitForFunction(() => document.querySelector('#assignmentImportPreview').textContent.includes('成功 2 条，未导入 2 条'));
  await page.waitForFunction(() => document.querySelector('#assignmentImportNotice').textContent.includes('资源池已刷新'));
  assert((await page.locator('#downloadAssignmentNotImported').textContent()).includes('2条'));
  assert(poolReads > reads); assert.equal(await page.locator('#confirmAssignmentImport').count(), 0);
  assert.equal(requests.filter(r => r.name.endsWith('/confirm')).length, 1);
  assert(requests.filter(r => r.method === 'POST' && r.name.startsWith('admin/assignment-imports')).every(r => r.csrf === 'import-test-csrf'));
  await preview('conflicts.csv'); assert.equal(await page.locator('#confirmAssignmentImport').isDisabled(), true); assert((await page.locator('#assignmentImportImpact').textContent()).includes('仅导入 0 条'));
  await preview('duplicates.csv'); assert.equal(await page.locator('#confirmAssignmentImport').isDisabled(), true); assert((await page.locator('.assignment-import-table').textContent()).includes('重复跳过'));
  await page.click('#cancelAssignmentPreview'); await page.waitForFunction(() => document.querySelector('#assignmentImportPreview').textContent.includes('本任务已取消'));
  assert(requests.some(r => r.method === 'DELETE' && r.csrf === 'import-test-csrf'));
  await preview('changed.csv'); const confirmBefore = requests.filter(r => r.name.endsWith('/confirm')).length;
  await page.click('#confirmAssignmentImport'); await page.waitForFunction(() => document.querySelector('#assignmentImportNotice').textContent.includes('本次未写入任何分配'));
  assert.equal(requests.filter(r => r.name.endsWith('/confirm')).length, confirmBefore + 1);
  assert((await page.locator('#confirmAssignmentImport').textContent()).includes('1条'));
  assert((await page.locator('.assignment-import-meta').textContent()).includes('预览版本 2'));
  assert.equal(jobs[0].status, 'PREVIEW_READY'); assert.equal(jobs[0].importedRows, 0);
  await page.click('#confirmAssignmentImport'); await page.waitForFunction(() => document.querySelector('#assignmentImportPreview').textContent.includes('成功 1 条，未导入 3 条'));
  assert.equal(requests.filter(r => r.name.endsWith('/confirm')).pop().payload.previewVersion, 2);
  await preview('http409.csv'); await page.click('#confirmAssignmentImport'); await page.waitForFunction(() => document.querySelector('#assignmentImportNotice').textContent.includes('当前预览已保留'));
  assert.equal(await page.locator('#assignmentImportModal').count(), 1); assert.equal(await page.locator('#confirmAssignmentImport').isDisabled(), false);
  await page.click('#reloadAssignmentPreview'); await page.waitForFunction(() => document.querySelector('#assignmentImportNotice').textContent.includes('已读取任务'));
  await page.setInputFiles('#assignmentImportFile', { name: 'new-file.csv', mimeType: 'text/csv', buffer: Buffer.from('new') }); assert.equal(await page.locator('#confirmAssignmentImport').isDisabled(), true, 'new file must be previewed before confirm');
  await page.click('#assignmentImportHistory [data-assignment-import-id="import-1"]'); await page.waitForFunction(() => document.querySelector('.assignment-import-meta').textContent.includes('mixed.csv'));
  assert((await page.locator('#assignmentImportPreview').textContent()).includes('成功 2 条'));
  jobs[0].status = 'EXPIRED'; await page.click('#refreshAssignmentImports'); await page.waitForFunction(() => document.querySelector('#assignmentImportHistory').textContent.includes('已失效'));
  await page.click('#assignmentImportHistory [data-assignment-import-id="import-5"]'); await page.waitForFunction(() => document.querySelector('#assignmentImportPreview').textContent.includes('恢复后请重新上传'));
  assert.equal(await page.locator('#confirmAssignmentImport, #cancelAssignmentPreview').count(), 0);
  await page.setViewportSize({ width: 700, height: 950 });
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1));
  assert(await page.locator('.assignment-import-dialog').evaluate(n => n.scrollWidth <= n.clientWidth + 1));
  assert(await page.locator('.assignment-import-table').evaluate(n => n.scrollWidth > n.clientWidth));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.click('#closeAssignmentImport'); await page.click('#out'); await login('tech');
  assert.equal(await page.locator('[data-v="pool"], #openAssignmentImport, #assignmentImportModal').count(), 0);
  assert.deepEqual(errors, []);
  console.log('PASS|BROWSER_ASSIGNMENT_IMPORT|mixed-partial|duplicate-skip|all-conflicts-disabled|revalidate-reconfirm|409-retained|csrf|csv-xlsx-download|unimported-csv|cancel-recent-expired|original-fields|escaped-server-text|desktop-mobile|tech-no-entry');
}
main().catch(error => { console.error(error.stack || error); process.exitCode = 1; }).finally(async () => { if (browser) await browser.close(); if (server) await new Promise(resolve => server.close(resolve)); });
