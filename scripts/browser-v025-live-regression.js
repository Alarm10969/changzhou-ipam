/* V025 real Edge + Java integration. Creates and removes its own isolated database.
 * Usage: node scripts/browser-v025-live-regression.js --java-home <JDK11> --classes <compiled classes>
 * Test credentials are random, kept in memory, and never printed.
 */
const fs = require('fs');
const path = require('path');
const os = require('os');
const http = require('http');
const net = require('net');
const crypto = require('crypto');
const assert = require('assert');
const { spawn, spawnSync } = require('child_process');
let playwright;
try { playwright = require('playwright'); } catch (ignore) {
  playwright = require(process.env.IPAM_PLAYWRIGHT || path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
}
const args = process.argv.slice(2);
function option(name, fallback) { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : fallback; }
const repository = path.resolve(__dirname, '..');
const javaHome = option('--java-home', process.env.JAVA_HOME);
const sourceClasses = path.resolve(option('--classes', path.join(repository, '.local-v027-build/classes')));
const edge = process.env.IPAM_EDGE || (process.platform === 'darwin' ? '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge' : 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe');
const password = () => 'T' + crypto.randomBytes(16).toString('hex') + 'a9';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
let work, service, browser, logDescriptor;
async function health(base) {
  return new Promise(resolve => {
    const request = http.get(base + '/api/v1/health', response => { response.resume(); resolve(response.statusCode === 200); });
    request.setTimeout(1000, () => { request.destroy(); resolve(false); });
    request.on('error', () => resolve(false));
  });
}
async function freePort() {
  const probe = net.createServer(); await new Promise(resolve => probe.listen(0, '127.0.0.1', resolve));
  const port = probe.address().port; await new Promise(resolve => probe.close(resolve)); return port;
}
async function run() {
  assert(javaHome && fs.existsSync(path.join(javaHome, 'bin/java')), 'Java executable missing; pass --java-home');
  work = fs.mkdtempSync(path.join(os.tmpdir(), 'ipam-v025-ui-live-'));
  const classes = path.join(work, 'classes');
  if (!fs.existsSync(classes)) fs.cpSync(sourceClasses, classes, { recursive: true });
  assert(fs.existsSync(path.join(classes, 'com/icbc/changzhou/ipam/IpamApplication.class')), 'Compiled application classes missing');
  const data = path.join(work, 'data'); fs.mkdirSync(data, { mode: 0o700 });
  const initialPassword = password(), savedPassword = password(), changedPassword = password();
  const passwordFile = path.join(data, '.bootstrap-password'); fs.writeFileSync(passwordFile, initialPassword, { mode: 0o600 });
  const port = await freePort(), base = 'http://127.0.0.1:' + port;
  const env = { ...process.env, IPAM_DATA_DIR: data, IPAM_STATIC_DIR: path.join(repository, 'frontend'), IPAM_BIND: '127.0.0.1', IPAM_PORT: String(port), IPAM_ADMIN_USERNAME: 'browser_initial', IPAM_ADMIN_PASSWORD_FILE: passwordFile, IPAM_SECURE_COOKIE: 'false' };
  delete env.IPAM_ADMIN_PASSWORD;
  logDescriptor = fs.openSync(path.join(work, 'server.log'), 'w', 0o600);
  service = spawn(path.join(javaHome, 'bin/java'), ['-cp', classes + path.delimiter + path.join(repository, 'backend/lib/*'), 'com.icbc.changzhou.ipam.IpamApplication'], { cwd: repository, env, stdio: ['ignore', logDescriptor, logDescriptor] });
  const deadline = Date.now() + 20000;
  while (!(await health(base))) { assert(service.exitCode === null && service.signalCode === null, 'Isolated service exited during startup'); assert(Date.now() < deadline, 'Isolated service startup timed out'); await pause(100); }
  assert(!fs.existsSync(passwordFile), 'One-time bootstrap password file was not removed');
  browser = await playwright.chromium.launch({ executablePath: edge, headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1050 }, acceptDownloads: true });
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  /* Read test tokens where the application already reads them. Real server no-store
   * responses may be evicted from Edge's inspector cache before response.json(). */
  await page.addInitScript(() => {
    window.__testApi = {};
    const open = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (method, url) {
      const request = this;
      request.addEventListener('load', function () {
        if (request.responseType && request.responseType !== 'text') return;
        try { window.__testApi[url] = { status: request.status, body: JSON.parse(request.responseText || '{}') }; } catch (ignore) {}
      });
      return open.apply(request, arguments);
    };
  });
  await page.goto(base); await page.waitForSelector('#go');
  async function login(secret) {
    await page.evaluate(() => { delete window.__testApi['/api/v1/auth/login']; });
    await page.fill('#username', 'browser_initial'); await page.fill('#password', secret);
    const response = page.waitForResponse(r => r.url().endsWith('/auth/login') && r.request().method() === 'POST');
    await page.click('#go'); const result = await response; assert.equal(result.status(), 200, 'UI login failed');
    await page.waitForSelector('#nav'); await page.waitForFunction(() => window.__testApi['/api/v1/auth/login']);
    return page.evaluate(() => window.__testApi['/api/v1/auth/login'].body.csrfToken);
  }
  async function changePassword(oldSecret, newSecret) {
    await page.evaluate(() => { delete window.__testApi['/api/v1/auth/change-password']; });
    await page.click('[data-v="home"]'); await page.fill('#old', oldSecret); await page.fill('#new', newSecret); await page.fill('#again', newSecret);
    const response = page.waitForResponse(r => r.url().endsWith('/auth/change-password') && r.request().method() === 'POST');
    await page.click('#save'); const result = await response; assert.equal(result.status(), 200, 'UI password change failed');
    await page.waitForSelector('[data-v="backups"]'); await page.waitForFunction(() => window.__testApi['/api/v1/auth/change-password']);
    return page.evaluate(() => window.__testApi['/api/v1/auth/change-password'].body.csrfToken);
  }
  async function request(method, name, csrf, body) {
    const result = await page.request.fetch(base + '/api/v1/' + name, { method, headers: { 'X-CSRF-Token': csrf }, data: body });
    assert(result.ok(), 'Isolated fixture request failed with status ' + result.status());
    const text = await result.text(); return text ? JSON.parse(text) : null;
  }
  let csrf = await login(initialPassword); csrf = await changePassword(initialPassword, savedPassword);
  await page.click('[data-v="calculator"]');
  await page.fill('#calcIp', '192.168.1.100/26'); await page.click('#calcRun');
  await page.waitForSelector('#calcResults');
  assert((await page.textContent('#calcValues')).includes('3232235903'), 'Calculator did not load through Java static server');
  assert.equal(await page.locator('#calcBinary code').count(), 4);
  console.log('PASS|BROWSER_V028_LIVE|real-java-static-module|sample-calculation|32-bit-results');
  await page.click('#mode6');
  await page.fill('#ip6', '2001:0db8:85a3::8a2e:0370:7334/64');
  await page.press('#ip6','Enter');
  await page.waitForSelector('#result6');
  assert((await page.textContent('#result6')).includes('18446744073709551616'));
  assert((await page.textContent('#result6')).includes('2001:db8:85a3:0:ffff:ffff:ffff:ffff'));
  const imageDir = path.join(repository,'outputs/v030-local/images');
  fs.mkdirSync(imageDir,{recursive:true});
  await page.locator('#result6').screenshot({path:path.join(imageDir,'ipv6-result.png')});
  await page.click('[data-prefix6="128"]');
  assert(!(await page.isVisible('#result6')));
  await page.press('#ip6','Enter');
  assert.equal(await page.locator('#result6 dd').nth(5).textContent(),'1');
  const baselineBranch = await request('POST', 'admin/branches', csrf, { code: 'UI_BASELINE', name: '恢复基线支行' });
  const importOutlet = await request('POST', 'admin/outlets', csrf, { branchId: baselineBranch.id, code: 'UI_IMPORT', name: '批量补录浏览器网点' });
  await request('POST', 'admin/outlet-plans', csrf, { branchId: baselineBranch.id, outletId: importOutlet.id, productionCidr: '108.56.64.0/27' });
  const importAsset = await request('POST', 'admin/assets', csrf, { assetType: 'NON_TECH', name: '浏览器存量资产', requiresUser: 0, requiresResourceCode: 0, recommendedRangeType: 'OFFICE', active: 1 });
  const importPool = await request('GET', 'admin/ip-pool-v2?outletId=' + importOutlet.id + '&rangeType=OFFICE', csrf);
  const passing = importPool.items.find(row => row.state === 'AVAILABLE');
  const gateway = importPool.items.find(row => row.state === 'SYSTEM_RESERVED');
  assert(passing && gateway, 'Real assignment-import fixture is missing');
  const importFile = path.join(work, '存量设备浏览器台账.csv');
  const importHeaders = ['IP地址','资产类型','资产名称','支行名称（核对用）','网点名称（核对用）','资源编码','使用人姓名','统一认证号','负责科技管理员账号','交换机IP','交换机端口','MAC地址','设备序列号','补录说明'];
  const csvRow = ip => [ip, '非科技资产', '浏览器存量资产', '', '', '', '', '', '', '', '', '', '', '浏览器实测'];
  fs.writeFileSync(importFile, '\uFEFF' + [importHeaders, csvRow(passing.ipAddress), csvRow(gateway.ipAddress)].map(row => row.join(',')).join('\r\n'), 'utf8');
  await page.click('[data-v="pool"]');
  await page.waitForSelector('#openAssignmentImport');
  await page.selectOption('#poolType', 'OFFICE');
  await page.click('#openAssignmentImport');
  await page.waitForSelector('.assignment-import-dialog');
  await page.setInputFiles('#assignmentImportFile', importFile);
  let importResponse = page.waitForResponse(r => r.url().endsWith('/admin/assignment-imports') && r.request().method() === 'POST');
  await page.click('#previewAssignmentImport');
  let realPreview = await (await importResponse).json();
  assert.equal(realPreview.readyRows, 1); assert.equal(realPreview.errorRows, 1);
  assert.equal(realPreview.fileName, '存量设备浏览器台账.csv');
  await page.waitForFunction(() => document.querySelector('#assignmentImportImpact').textContent.includes('仅导入 1 条'));
  const excludedDownload = page.waitForEvent('download'); await page.click('#downloadAssignmentNotImported');
  const excluded = await excludedDownload, excludedPath = path.join(work, 'excluded.csv'); await excluded.saveAs(excludedPath);
  const excludedText = fs.readFileSync(excludedPath, 'utf8');
  assert(excludedText.includes(gateway.ipAddress) && !excludedText.includes(passing.ipAddress), 'Preview CSV did not contain only excluded rows');
  importResponse = page.waitForResponse(r => r.url().includes('/admin/assignment-imports/') && r.url().endsWith('/confirm') && r.request().method() === 'POST');
  await page.click('#confirmAssignmentImport');
  const realDone = await (await importResponse).json();
  assert.equal(realDone.status, 'COMPLETED'); assert.equal(realDone.importedRows, 1); assert.equal(realDone.notImportedRows, 1);
  await page.waitForFunction(ip => document.querySelector('#table').textContent.includes(ip) && document.querySelector('#table').textContent.includes('浏览器存量资产'), passing.ipAddress);
  await page.waitForFunction(() => document.querySelector('#assignmentImportPreview').textContent.includes('成功 1 条，未导入 1 条'));
  await page.setInputFiles('#assignmentImportFile', importFile);
  importResponse = page.waitForResponse(r => r.url().endsWith('/admin/assignment-imports') && r.request().method() === 'POST');
  await page.click('#previewAssignmentImport'); realPreview = await (await importResponse).json();
  assert.equal(realPreview.readyRows, 0); assert.equal(realPreview.duplicateRows, 1); assert.equal(realPreview.errorRows, 1);
  await page.waitForFunction(() => document.querySelector('#confirmAssignmentImport').disabled);
  await page.click('#closeAssignmentImport');
  console.log('PASS|BROWSER_V026_LIVE|real-java-edge|utf8-upload|partial-confirm|excluded-csv|pool-refresh|duplicate-skip|empty-confirm-disabled');
  await page.selectOption('#poolAssetType', 'NON_TECH');
  await page.selectOption('#poolAssetId', String(importAsset.id));
  await page.waitForFunction(ip => document.querySelector('#poolCount').textContent === '共 1 个地址' && document.querySelector('#table').textContent.includes(ip), passing.ipAddress);
  await request('PUT', 'admin/assets/' + importAsset.id, csrf, { assetType: 'TECH', name: '浏览器更名改类资产', requiresUser: 0, requiresResourceCode: 0, recommendedRangeType: 'OFFICE', active: 0 });
  await page.click('[data-v="pool"]');
  await page.waitForFunction(id => {
    const option = document.querySelector('#poolAssetId option[value="' + id + '"]');
    return option && option.textContent.includes('浏览器存量资产') && option.textContent.includes('已停用');
  }, importAsset.id);
  await page.waitForFunction(() => document.querySelector('#poolCount').textContent === '共 1 个地址');
  await page.selectOption('#poolAssetType', 'TECH');
  await page.selectOption('#poolAssetId', String(importAsset.id));
  await page.waitForFunction(() => document.querySelector('#poolCount').textContent === '共 0 个地址');
  await page.selectOption('#poolAssetType', '');
  await page.selectOption('#poolAssetId', String(importAsset.id));
  await page.waitForFunction(() => document.querySelector('#poolCount').textContent === '共 1 个地址');
  assert.equal(await page.locator('#poolAssetId option[value="' + importAsset.id + '"]').count(), 1, 'All-category option must merge one asset identity');
  await page.click('#poolReset');
  await page.waitForFunction(() => document.querySelector('#poolAssetType').value === '' && document.querySelector('#poolAssetId').value === '' && document.querySelector('#poolType').value === '' && !document.querySelector('#poolCount').textContent.includes('加载中'));
  console.log('PASS|BROWSER_V027_LIVE|real-java-edge|batch-filter|major-minor|renamed-retyped-inactive-snapshot|id-option-merge|reset');
  await page.click('[data-v="backups"]');
  await page.waitForFunction(() => document.querySelector('#backupStatus').textContent.includes('尚无备份记录'));
  assert.equal(await page.locator('#restoreFile').getAttribute('accept'), '.zip,.db');
  const pendingDownload = page.waitForEvent('download'); await page.click('#downloadBackup');
  const download = await pendingDownload, archive = path.join(work, 'downloaded-backup.zip');
  assert(/\.zip$/.test(download.suggestedFilename()), 'Backup download filename missing ZIP extension'); await download.saveAs(archive);
  assert(fs.statSync(archive).size > 1000, 'Backup archive is unexpectedly small');
  await page.waitForFunction(() => document.querySelector('#backupStatus').textContent.includes('browser_initial'));
  assert(!(await page.locator('#backupStatus').textContent()).includes('正在读取'));
  await page.screenshot({ path: path.join(os.tmpdir(), 'ipam-v025-backups-live.png'), fullPage: true });
  const rawDatabase = path.join(work, 'downloaded-backup.db');
  const extraction = spawnSync(process.env.IPAM_PYTHON || 'python3', ['-c', 'import sys,zipfile; open(sys.argv[2],"wb").write(zipfile.ZipFile(sys.argv[1]).read("ipam.db"))', archive, rawDatabase], { encoding: 'utf8' });
  assert.equal(extraction.status, 0, 'Cannot extract complete database fixture from ZIP');
  await request('POST', 'admin/branches', csrf, { code: 'UI_AFTER_BACKUP', name: '备份后新增支行' });
  csrf = await changePassword(savedPassword, changedPassword);
  await page.click('[data-v="backups"]');
  async function preview(file) {
    await page.setInputFiles('#restoreFile', file);
    const response = page.waitForResponse(r => r.url().includes('/admin/backups/restore-preview?') && r.request().method() === 'POST');
    await page.click('#previewRestore'); const result = await response; assert.equal(result.status(), 200, 'Real backup preview failed');
    await page.waitForSelector('#confirmRestore');
    const key = '/api/v1/admin/backups/restore-preview?fileName=' + encodeURIComponent(path.basename(file));
    await page.waitForFunction(url => window.__testApi[url], key);
    return page.evaluate(url => window.__testApi[url].body, key);
  }
  const rawPreview = await preview(rawDatabase); assert(rawPreview.uploadId && rawPreview.sourceSchema === 24, 'Complete .db preview missing schema or capability');
  await page.click('#confirmRestore');
  const cancelled = page.waitForResponse(r => r.url().endsWith('/admin/backups/restore-preview/' + rawPreview.uploadId) && r.request().method() === 'DELETE');
  await page.click('#cancelSystem'); assert((await cancelled).ok(), 'Cancelled preview was not cleaned');
  await page.waitForSelector('#systemModal', { state: 'detached' }); assert.equal(await page.locator('#confirmRestore').count(), 0);
  assert((await request('GET', 'branches', csrf)).items.some(x => x.code === 'UI_AFTER_BACKUP'), 'Cancelled preview changed current data');
  const zipPreview = await preview(archive); assert(zipPreview.requiresRelogin, 'Restore preview must disclose relogin');
  assert((await page.locator('#restorePreview').textContent()).includes('支行1'), 'Real preview count missing baseline branch');
  await page.click('#confirmRestore'); await page.check('#acknowledgeRestore');
  const confirm = page.waitForResponse(r => r.url().endsWith('/admin/backups/restore-confirm') && r.request().method() === 'POST');
  await page.click('#confirmSystem'); assert.equal((await confirm).status(), 202, 'Restore task was not accepted');
  await page.waitForSelector('#go', { timeout: 20000 });
  assert((await page.locator('#notice').textContent()).includes('备份中的账号和密码'), 'Successful restore did not explain account relogin');
  csrf = await login(savedPassword);
  const restoredBranches = (await request('GET', 'branches', csrf)).items;
  assert(restoredBranches.some(x => x.code === 'UI_BASELINE'), 'Restored baseline branch missing');
  assert(!restoredBranches.some(x => x.code === 'UI_AFTER_BACKUP'), 'Full restore retained post-backup branch');
  await page.click('[data-v="backups"]'); await page.waitForFunction(() => document.querySelector('#backupStatus').textContent !== '正在读取备份记录…');
  const testBranch = await request('POST','admin/branches',csrf,{code:'PAGE_TEST',name:'分页测试支行'});
  const testOutlets = [];
  for (let index=0; index<4; index++) {
    const outlet = await request('POST','admin/outlets',csrf,{branchId:testBranch.id,code:'PG'+index,name:'分页网点'+index});
    testOutlets.push(outlet.id);
    await request('POST','admin/outlet-plans',csrf,{branchId:testBranch.id,outletId:outlet.id,productionCidr:'108.56.80.'+index*32+'/27'});
  }
  await page.click('[data-v="ranges"]');
  await page.selectOption('#rangeBranch',String(testBranch.id));
  const pagerHas = text => page.waitForFunction(t => document.querySelector('#rangePager')?.textContent.includes(t),text);
  await pagerHas('共 24 条');
  await page.click('#rangePagerNext');
  await pagerHas('第 2 / 2 页');
  assert.equal(await page.locator('#table tbody tr').count(),4);
  await page.locator('section.card').filter({has:page.locator('#rangePager')}).screenshot({path:path.join(imageDir,'ranges.png')});
  await page.click('[data-v="pool"]');
  await page.waitForSelector('#poolPager');
  await page.selectOption('#poolBranch',String(testBranch.id));
  await page.click('#poolPagerNext');
  await page.waitForFunction(() => document.querySelector('#poolPager').textContent.includes('第 2 /'));
  const firstIP = await page.locator('.pool-ip b').first().textContent();
  await page.locator('[data-action="detail"]').first().click();
  assert((await page.locator('#systemModal,#editModal,#ipModal,#viewModal').allTextContents()).join('').includes(firstIP));
  await page.click('#confirmView');
  assert((await page.textContent('#poolPager')).includes('第 2 /'));
  await page.click('[data-v="ranges"]');
  await pagerHas('第 2 / 2 页');
  await page.selectOption('#rangeType','OFFICE');
  await pagerHas('第 1 / 1 页，共 4 条');
  await page.selectOption('#rangeType','');
  await pagerHas('共 24 条');
  await page.selectOption('#rangeSize','50');
  await pagerHas('第 1 / 1 页，共 24 条');
  await page.selectOption('#rangeSize','20');
  await pagerHas('第 1 / 2 页');
  await page.click('#rangePagerNext');
  await pagerHas('第 2 / 2 页');
  await request('DELETE','admin/outlets/'+testOutlets[3],csrf);
  await page.click('[data-v="ranges"]');
  await pagerHas('第 1 / 1 页，共 18 条');
  await page.fill('#rangeSearch','no-match');
  await page.press('#rangeSearch','Enter');
  await pagerHas('共 0 条');
  assert((await page.textContent('#table')).includes('未找到地址段'));
  await request('DELETE','admin/branches/'+testBranch.id,csrf);
  console.log('PASS|V030_LIVE|IPv6|range-pages|filter-restore|resize|last-page-deletion|empty');

  await request('POST','admin/branches',csrf,{code:'B1',name:'专线测试支行'});
  await page.click('[data-v="outletDirectory"]');
  await page.setInputFiles('#directoryFile',path.join(repository,'outputs/v031-local/test-data/directory.xlsx'));
  await page.click('#directoryUpload');
  await page.waitForSelector('#directoryConfirm');
  assert((await page.textContent('#directoryImportResult')).includes('新增 1 行'));
  const dirImage=path.join(repository,'outputs/v031-local/images');fs.mkdirSync(dirImage,{recursive:true});
  await page.locator('#directoryImportResult details').first().evaluate(el=>el.open=true);
  await page.locator('#directoryImportResult').screenshot({path:path.join(dirImage,'directory-preview.png')});
  await page.click('#directoryConfirm');
  await page.waitForFunction(()=>document.querySelector('#table').textContent.includes('1150.00 元'));
  assert((await page.textContent('#table')).includes('扁平化'));
  await page.locator('#table [data-edit]').first().click();
  await page.waitForSelector('#dir_line1_fee');
  await page.fill('#dir_line1_fee','1200.25');
  await page.fill('#dir_line2_carrier','移动');
  await page.selectOption('#dir_flat_status','非扁平化');
  await page.locator('#editModal .dialog').screenshot({path:path.join(dirImage,'directory-edit.png')});
  await page.click('#saveEdit');
  await page.waitForFunction(()=>document.querySelector('#table').textContent.includes('1200.25 元'));
  await page.locator('section.card').filter({has:page.locator('#directoryPager')}).screenshot({path:path.join(dirImage,'directory-list.png')});
  await page.setInputFiles('#directoryFile',path.join(repository,'outputs/v031-local/test-data/directory.xlsx'));
  await page.click('#directoryUpload');
  await page.waitForSelector('#directoryConfirm');
  assert((await page.textContent('#directoryImportResult')).includes('更新 1 行'));
  await page.setInputFiles('#directoryFile',[]);
  assert(!(await page.isVisible('#directoryConfirm')));

  const lastJobs = await page.evaluate(() => Object.entries(window.__testApi).filter(entry=>entry[0].endsWith('/outlet-directory/preview')).map(entry=>entry[1].body));
  const pendingId = lastJobs[0].id;
  await page.click('[data-v="backups"]');
  const lineDownload=page.waitForEvent('download'); await page.click('#downloadBackup');
  const lineArchive=path.join(work,'lines-backup.zip'); await (await lineDownload).saveAs(lineArchive);
  await preview(lineArchive);
  await page.click('#confirmRestore'); await page.check('#acknowledgeRestore'); await page.click('#confirmSystem');
  await page.waitForSelector('#go',{timeout:20000}); csrf=await login(savedPassword);
  const restoredDirectory=(await request('GET','admin/outlet-directory',csrf)).items;
  assert(restoredDirectory.some(row=>row.line1_fee==='1200.25' && row.line2_carrier==='移动' && row.flat_status==='非扁平化'));
  const staleDirectory = await page.request.post(base+'/api/v1/admin/outlet-directory/confirm',{headers:{'X-CSRF-Token':csrf},data:{id:pendingId}});
  assert.equal(staleDirectory.status(),409,'Restore did not expire pending directory import');
  console.log('PASS|V031_DIRECTORY_UI|preview-before-write|two-lines|monthly-total|edit|clear-file-preview');
  assert.deepEqual(errors, [], 'Real service UI emitted page errors');
  console.log('PASS|BROWSER_V025_LIVE|real-java-edge|status-loaded|zip-download|raw-db-preview-cancel|full-restore|backup-password-relogin|post-backup-data-removed');
}
run().catch(error => { console.error('FAIL|BROWSER_V025_LIVE|' + error.message); process.exitCode = 1; }).finally(async () => {
  if (browser) await browser.close();
  if (service && service.exitCode === null && service.signalCode === null) {
    service.kill('SIGTERM');
    const deadline = Date.now() + 10000; while (service.exitCode === null && service.signalCode === null && Date.now() < deadline) await pause(100);
    if (service.exitCode === null && service.signalCode === null) { service.kill('SIGKILL'); await new Promise(resolve => service.once('exit', resolve)); }
  }
  if (logDescriptor !== undefined) fs.closeSync(logDescriptor);
  if (work) fs.rmSync(work, { recursive: true, force: true });
  console.log('PASS|BROWSER_V025_LIVE|temporary-java-pid-stopped|isolated-classes-data-cleaned');
});
