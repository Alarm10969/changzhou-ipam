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
const sourceClasses = path.resolve(option('--classes', path.join(repository, '.local-v025-build/classes')));
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
    assert(result.ok(), 'Isolated fixture request failed with status ' + result.status()); return result.json();
  }
  let csrf = await login(initialPassword); csrf = await changePassword(initialPassword, savedPassword);
  await request('POST', 'admin/branches', csrf, { code: 'UI_BASELINE', name: '恢复基线支行' });
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
  const rawPreview = await preview(rawDatabase); assert(rawPreview.uploadId && rawPreview.sourceSchema === 22, 'Complete .db preview missing schema or capability');
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
