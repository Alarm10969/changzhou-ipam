/* Windows development regression: real DOM/browser checks for phase four. */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const root = path.resolve(__dirname, '..');
const staticRoot = path.join(root, 'frontend');
const edge = process.env.IPAM_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const webPort = 19192, debugPort = 19223;
let browser, server;

function fail(message) { throw new Error('BROWSER_PHASE4_REGRESSION: ' + message); }
function wait(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
async function until(check, message) { for (let i = 0; i < 100; i++) { const value = await check(); if (value) return value; await wait(50); } fail(message); }
function responseFor(url, method, body) {
  const name = url.replace(/^.*\/api\/v1\//, '');
  if (name === 'auth/login') {
    const input = body ? JSON.parse(body) : {};
    return input.username === 'tech' ? { id: 2, username: 'tech', realName: '科技管理员', role: 'TECH_ADMIN', mustChangePassword: false, csrfToken: 'tech-csrf' } : { id: 1, username: 'admin', realName: '管理员', role: 'ADMIN', mustChangePassword: false, csrfToken: 'admin-csrf' };
  }
  if (name === 'admin/dashboard') return { branchCount: 1, outletCount: 1, unboundOutletCount: 0, availableIpCount: 253, pendingCount: 1 };
  if (name === 'admin/audit') return { items: [{ id: 9, action: 'UPDATE_SWITCH_PORT', targetType: 'IP_ASSIGNMENT', targetId: '77', result: 'SUCCESS', createdAt: '2026-09-16T00:00:00Z', actor: 'admin', switchPortBefore: 'GE0/0/1', switchPortAfter: 'Eth-Trunk1', detailJson: '{"password":"SHOULD_NOT_RENDER"}' }] };
  if (name === 'admin/outlets' || name === 'outlets' || name === 'branches') return { items: [{ id: 1, branchId: 1, branchName: '回归支行', outletName: '回归网点', name: '回归网点', code: 'O4', active: 1, rangeId: 1 }] };
  if (name === 'asset-categories') return { items: [] };
  if (name.indexOf('admin/applications?') === 0) return { items: [{ id: 42, status: 'PENDING', createdAt: '2026-09-16T00:00:00Z', applicantUsername: 'tech', applicantName: '科技管理员', branchName: '回归支行', outletName: '回归网点', assetType: 'NON_TECH', assetName: '端口回归资产', requiresUser: 0, requiresResourceCode: 0, recommendedRangeType: 'OFFICE' }] };
  if (name.indexOf('admin/applications/42/candidates') === 0) return { items: [{ id: 77, ipAddress: '108.56.0.10', cidr: '108.56.0.0/24', rangeType: 'OFFICE' }], total: 1, page: 1, pageSize: 50, rangeType: 'OFFICE', recommendedRangeType: 'OFFICE' };
  if (name.indexOf('admin/ip-pool-v2?') === 0) return { items: [{ id: 10, ipAddress: '108.56.0.10', state: 'ASSIGNED', editable: 0, assignmentId: 77, assignmentVersion: 3, switchPort: 'GE0/0/1', allocationSummary: '端口回归资产', branchName: '回归支行', outletName: '回归网点', rangeType: 'OFFICE', cidr: '108.56.0.0/24', gateway: '108.56.0.1', dnsPrimary: '114.114.114.114', applicantUsername: 'tech', reviewer: 'admin' }], total: 1, page: 1, pageSize: 50 };
  if (name.indexOf('/reachability') > 0) return { ipAddress: '108.56.0.10', status: 'NO_REPLY', elapsedMs: 1 };
  if (name === 'applications') return { items: [{ id: 42, status: 'APPROVED', createdAt: '2026-09-16T00:00:00Z', branchName: '回归支行', outletName: '回归网点', assetType: 'NON_TECH', assetName: '端口回归资产', finalIpAddress: '108.56.0.10', finalRangeType: 'OFFICE', switchPort: 'SECRET-PORT' }] };
  if (name === 'my-assignments') return { items: [{ id: 77, status: 'ASSIGNED', assignedAt: '2026-09-16T00:00:00Z', ipAddress: '108.56.0.10', subnetMask: '255.255.255.0', gateway: '108.56.0.1', branchName: '回归支行', outletName: '回归网点', assetType: 'NON_TECH', switchPort: 'SECRET-PORT' }] };
  if (name === 'notifications') return { items: [{ id: 1, message: '管理员已处理一条记录', createdAt: '2026-09-16T00:00:00Z', isRead: 1, switchPort: 'SECRET-PORT' }] };
  return {};
}
function fakeXhrScript() { return `
window.__phase4Requests=[];
window.XMLHttpRequest=function(){this.readyState=0;this.status=0;this.responseText='';this.headers={};};
window.XMLHttpRequest.prototype.open=function(method,url){this.method=method;this.url=url;this.readyState=1;};
window.XMLHttpRequest.prototype.setRequestHeader=function(k,v){this.headers[k]=v;};
window.XMLHttpRequest.prototype.send=function(data){var x=this,u=x.url,p=u.replace(/^.*\\/api\\/v1\\//,'');var record={path:p,method:x.method,body:null};try{record.body=data?JSON.parse(data):null;}catch(ignore){}window.__phase4Requests.push(record);var result=window.__phase4Response(u,x.method,data);setTimeout(function(){x.status=200;x.responseText=JSON.stringify(result);x.readyState=4;if(x.onreadystatechange)x.onreadystatechange();},0);};
`; }
function makeServer() { return new Promise(resolve => { server = http.createServer((req, res) => { let file = req.url.split('?')[0]; if (file === '/') file = '/index.html'; const target = path.resolve(staticRoot, '.' + file); if (!target.startsWith(staticRoot) || !fs.existsSync(target)) { res.writeHead(404); res.end(); return; } const ext = path.extname(target); res.writeHead(200, { 'Content-Type': ext === '.js' ? 'application/javascript' : ext === '.css' ? 'text/css' : 'text/html' }); fs.createReadStream(target).pipe(res); }); server.listen(webPort, '127.0.0.1', resolve); }); }
async function json(url) { const response = await fetch(url); return response.json(); }
function connect(wsUrl) { return new Promise((resolve, reject) => { const ws = new WebSocket(wsUrl); ws.onopen = () => resolve(ws); ws.onerror = reject; }); }
async function main() {
  if (!fs.existsSync(edge)) fail('Microsoft Edge not found: ' + edge);
  await makeServer();
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'ipam-edge-phase4-'));
  browser = spawn(edge, ['--headless=new', '--disable-gpu', '--remote-debugging-port=' + debugPort, '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true });
  const targets = await until(() => json('http://127.0.0.1:' + debugPort + '/json').catch(() => null), 'Edge DevTools did not start');
  const page = targets.find(x => x.type === 'page'); if (!page) fail('Edge page target missing');
  const ws = await connect(page.webSocketDebuggerUrl), pending = new Map(); let sequence = 0;
  ws.onmessage = event => { const msg = JSON.parse(event.data); if (msg.id && pending.has(msg.id)) { const p = pending.get(msg.id); pending.delete(msg.id); p.resolve(msg.result); } };
  function cdp(method, params) { return new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params: params || {} })); }); }
  async function evaluate(expression) { const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (result.exceptionDetails) fail(result.exceptionDetails.text || 'runtime evaluation failed'); return result.result.value; }
  await cdp('Page.enable'); await cdp('Runtime.enable'); await cdp('Emulation.setDeviceMetricsOverride', { width: 640, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: 'window.__phase4Response=' + responseFor.toString() + ';' + fakeXhrScript() });
  await cdp('Page.navigate', { url: 'http://127.0.0.1:' + webPort + '/' });
  await until(() => evaluate("!!document.querySelector('#go')"), 'login form missing');
  await evaluate("document.querySelector('#username').value='admin';document.querySelector('#password').value='test';document.querySelector('#go').click();true");
  await until(() => evaluate("!!document.querySelector('[data-v=applications]')"), 'admin navigation missing');
  await evaluate("document.querySelector('[data-v=applications]').click();true");
  await until(() => evaluate("!!document.querySelector('[data-approve]')"), 'pending application missing');
  await evaluate("document.querySelector('[data-approve]').click();true");
  await until(() => evaluate("!!document.querySelector('#switchPort') && !!document.querySelector('[data-candidate-ping]')"), 'approval switch-port control missing');
  await evaluate("document.querySelector('input.radio').click();document.querySelector('#switchPort').value='bad\\u0001port';document.querySelector('#switchPort').dispatchEvent(new Event('input'));true");
  const retained = await evaluate("document.querySelector('input.radio').checked && document.querySelector('#switchPortHint').textContent.indexOf('控制字符')>=0"); if (!retained) fail('invalid port feedback did not retain selected IP');
  await evaluate("document.querySelector('#switchPort').value='GE0/0/1';document.querySelector('#switchPort').dispatchEvent(new Event('input'));document.querySelector('#saveEdit').click();true");
  await until(() => evaluate("!document.querySelector('#editModal')"), 'approval dialog did not close');
  const approval = await evaluate("window.__phase4Requests.find(function(x){return x.path.indexOf('admin/applications/42/approve')>=0;})"); if (!approval || !approval.body || approval.body.switchPort !== 'GE0/0/1') fail('approval request did not carry switch port');
  const narrowApproval = await evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"); if (!narrowApproval) fail('approval page overflows at narrow width');
  await evaluate("document.querySelector('[data-v=pool]').click();true");
  await until(() => evaluate("!!document.querySelector('[data-port]')"), 'pool switch-port action missing');
  await evaluate("document.querySelector('[data-port]').click();true");
  await until(() => evaluate("!!document.querySelector('#switchPort')"), 'pool switch-port dialog missing');
  await evaluate("document.querySelector('#switchPort').value='Eth-Trunk1';document.querySelector('#switchPort').dispatchEvent(new Event('input'));document.querySelector('#saveEdit').click();true");
  await until(() => evaluate("!document.querySelector('#editModal')"), 'pool switch-port dialog did not close');
  await until(() => evaluate("document.querySelector('#notice') && document.querySelector('#notice').textContent.indexOf('交换机端口已保存')>=0"), 'pool switch-port success feedback missing');
  const poolUpdate = await evaluate("window.__phase4Requests.find(function(x){return x.path.indexOf('admin/ip-pool/10/switch-port')>=0;})"); if (!poolUpdate || !poolUpdate.body || poolUpdate.body.switchPort !== 'Eth-Trunk1' || poolUpdate.body.assignmentVersion !== 3) fail('pool switch-port request did not carry guarded update');
  const narrowPool = await evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"); if (!narrowPool) fail('pool page overflows at narrow width');
  await evaluate("document.querySelector('[data-v=audit]').click();true");
  await until(() => evaluate("document.querySelector('#table') && document.querySelector('#table').textContent.indexOf('Eth-Trunk1')>=0"), 'audit switch-port summary missing');
  const auditSafe = await evaluate("document.body.textContent.indexOf('SHOULD_NOT_RENDER')<0 && document.body.textContent.indexOf('detailJson')<0"); if (!auditSafe) fail('audit page rendered raw detail data');
  await evaluate("document.querySelector('#out').click();true");
  await until(() => evaluate("!!document.querySelector('#username')"), 'logout did not return to login');
  await evaluate("document.querySelector('#username').value='tech';document.querySelector('#password').value='test';document.querySelector('#go').click();true");
  await until(() => evaluate("!!document.querySelector('[data-v=mine]')"), 'tech navigation missing');
  await evaluate("document.querySelector('[data-v=mine]').click();true"); await until(() => evaluate("document.querySelector('#table') && document.querySelector('#table').textContent.indexOf('端口回归资产')>=0"), 'tech application page missing');
  if (await evaluate("document.body.textContent.indexOf('交换机端口')<0") === false) fail('tech application page leaked switch port');
  await evaluate("document.querySelector('[data-v=myips]').click();true"); await until(() => evaluate("document.querySelector('#table') && document.querySelector('#table').textContent.indexOf('108.56.0.10')>=0"), 'tech IP page missing');
  if (await evaluate("document.body.textContent.indexOf('交换机端口')<0") === false) fail('tech IP page leaked switch port');
  await evaluate("document.querySelector('[data-v=notifications]').click();true"); await until(() => evaluate("document.querySelector('#table') && document.querySelector('#table').textContent.indexOf('管理员已处理')>=0"), 'tech notification page missing');
  if (await evaluate("document.body.textContent.indexOf('交换机端口')<0") === false) fail('tech notification page leaked switch port');
  const leakedRequest = await evaluate("window.__phase4Requests.some(function(x){return JSON.stringify(x.body||{}).indexOf('switchPort')>=0 && x.path.indexOf('admin/')<0;})"); if (leakedRequest) fail('tech request carried switchPort field');
  console.log('PASS|BROWSER_PHASE4|password-hints|approval-port|pool-port-feedback|audit-summary|narrow-screen|tech-port-isolation');
  ws.close();
}
main().catch(error => { console.error(error.stack || error); process.exitCode = 1; }).finally(() => { if (browser) browser.kill(); if (server) server.close(); });
