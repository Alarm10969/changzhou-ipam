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
const sourceClasses = path.resolve(option('--classes', path.join(repository, '.local-v029-build/classes')));
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
    assert(result.ok(), 'Isolated fixture request ' + name + ' failed with status ' + result.status());
    const text = await result.text(); return text ? JSON.parse(text) : null;
  }
  let csrf = await login(initialPassword); csrf = await changePassword(initialPassword, savedPassword);
  await page.click('[data-v="setup"]');
  await page.waitForSelector('#setupGlobal');
  const output=path.join(repository,'outputs/v029-branch-setup');fs.mkdirSync(output,{recursive:true});
  await page.screenshot({path:path.join(output,'01-rules.png'),fullPage:true});
  await page.click('[data-setup-step="3"]');
  assert(await page.isDisabled('#setupComplete'),'fresh setup must not be completable');
  await page.click('[data-setup-step="0"]');
  await page.fill('#setupGlobal','10.0.0.0/8');await page.fill('#setupDns','10.0.0.53');
  await page.fill('#setupReason','按测试分行实际规划确认');
  let saved=page.waitForResponse(r=>r.url().endsWith('/admin/settings')&&r.request().method()==='PUT');
  await page.click('#setupSaveRule');assert.equal((await saved).status(),200);
  await page.waitForSelector('[data-setup-page="branches"]');
  await page.click('[data-setup-page="branches"]');await page.waitForSelector('#backToSetup');
  await page.click('#backToSetup');await page.waitForSelector('[data-setup-page="branches"]');
  const branch=await request('POST','admin/branches',csrf,{code:'SETUP',name:'示例分行'});
  const outlet=await request('POST','admin/outlets',csrf,{branchId:branch.id,code:'SETUP_O',name:'示例营业室'});
  await request('POST','admin/assets',csrf,{assetType:'TECH',name:'示例办公设备',requiresUser:0,requiresResourceCode:0,recommendedRangeType:'OFFICE',active:1});
  await request('POST','admin/users',csrf,{username:'setup_tech',realName:'示例科技管理员',initialPassword:password(),role:'TECH_ADMIN'});
  await request('POST','admin/outlet-plans',csrf,{branchId:branch.id,outletId:outlet.id,productionCidr:'10.10.0.0/27'});
  await page.click('#setupRefresh');await page.waitForSelector('[data-setup-step="3"]');await page.click('[data-setup-step="3"]');
  assert(!(await page.isDisabled('#setupComplete')),'complete fixture must be ready');
  saved=page.waitForResponse(r=>r.url().endsWith('/admin/setup/complete')&&r.request().method()==='POST');
  await page.click('#setupComplete');assert.equal((await saved).status(),200);
  await page.waitForFunction(()=>document.getElementById('notice').textContent.includes('基础准备已确认'));
  await page.screenshot({path:path.join(output,'02-ready.png'),fullPage:true});
  await page.setViewportSize({width:760,height:1000});await page.screenshot({path:path.join(output,'03-narrow.png'),fullPage:true});
  assert(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'narrow layout overflow');
  await page.reload();await page.waitForSelector('#go');csrf=await login(savedPassword);await page.waitForSelector('[data-v="setup"]');await page.click('[data-v="setup"]');await page.waitForSelector('#setupGlobal');
  assert.equal(await page.inputValue('#setupGlobal'),'10.0.0.0/8','custom range not persisted');
  await page.fill('#setupGlobal','192.168.0.0/16');await page.fill('#setupReason','测试禁止排除已有网段');
  saved=page.waitForResponse(r=>r.url().endsWith('/admin/settings')&&r.request().method()==='PUT');await page.click('#setupSaveRule');
  assert.equal((await saved).status(),400);await page.waitForFunction(()=>document.getElementById('notice').textContent.includes('不能排除已有网段'));
  assert.deepEqual(errors,[],'browser errors');
  console.log('PASS|BROWSER_V029|fresh,save-custom-range,navigate-return,ready,complete,reload,protect-existing,narrow');
}

run().catch(error => { console.error('FAIL|BROWSER_V029_SETUP|' + error.message); process.exitCode = 1; }).finally(async () => {
  if (browser) await browser.close();
  if (service && service.exitCode === null && service.signalCode === null) {
    service.kill('SIGTERM');
    const deadline = Date.now() + 10000; while (service.exitCode === null && service.signalCode === null && Date.now() < deadline) await pause(100);
    if (service.exitCode === null && service.signalCode === null) { service.kill('SIGKILL'); await new Promise(resolve => service.once('exit', resolve)); }
  }
  if (logDescriptor !== undefined) fs.closeSync(logDescriptor);
  if (work) fs.rmSync(work, { recursive: true, force: true });
  console.log('PASS|BROWSER_V029_SETUP|temporary-java-pid-stopped|isolated-classes-data-cleaned');
});
