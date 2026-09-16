/* Windows development regression: real Edge DOM interaction for approval paging. */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');
const root = path.resolve(__dirname, '..');
const staticRoot = path.join(root, 'frontend');
const edge = process.env.IPAM_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const webPort = 19191, debugPort = 19222;
let browser, server;

function fail(message) { throw new Error('BROWSER_APPROVAL_PAGING_REGRESSION: ' + message); }
function wait(ms) { return new Promise(resolve => setTimeout(resolve, ms)); }
async function until(check, message) { for (let i = 0; i < 80; i++) { const value = await check(); if (value) return value; await wait(50); } fail(message); }
function appResponse(url) {
  const pathName = url.replace(/^.*\/api\/v1\//, '');
  if (pathName === 'auth/login') return { id: 1, username: 'admin', realName: '审批回归管理员', role: 'ADMIN', mustChangePassword: false, csrfToken: 'regression-csrf' };
  if (pathName === 'admin/dashboard') return { branchCount: 1, outletCount: 1, unboundOutletCount: 0, availableIpCount: 253, pendingCount: 1 };
  if (pathName.indexOf('admin/applications?') === 0) return { items: [{ id: 42, status: 'PENDING', createdAt: '2026-09-16T00:00:00Z', applicantUsername: 'tech', applicantName: '科技管理员', branchName: '回归支行', outletName: '回归网点', assetType: 'TECH', assetName: '双关联资产', requiresUser: 1, requiresResourceCode: 1, userName: '张三', userUnifiedAuthId: 'U001', assetCode: 'R001', recommendedRangeType: 'OFFICE' }] };
  if (pathName.indexOf('admin/applications/42/candidates') === 0) {
    const page = Number((url.match(/[?&]page=(\d+)/) || [, '1'])[1]);
    const range = decodeURIComponent((url.match(/[?&]rangeType=([^&]+)/) || [, 'OFFICE'])[1]);
    const total = 253, start = (page - 1) * 50, items = [];
    for (let i = start; i < Math.min(total, start + 50); i++) items.push({ id: i + 1, ipAddress: '108.56.207.' + (i + 1), cidr: '108.56.207.0/24', rangeType: range });
    return { items, total, page, pageSize: 50, rangeType: range, recommendedRangeType: 'OFFICE' };
  }
  if (pathName.indexOf('admin/ip-pool/') === 0 && pathName.indexOf('/reachability') > 0) return { ipAddress: '108.56.207.1', status: 'UNREACHABLE', elapsedMs: 1 };
  return {};
}
function fakeXhrScript() { return `
window.__approvalRequests=[];
window.XMLHttpRequest=function(){this.readyState=0;this.status=0;this.responseText='';this.headers={};};
window.XMLHttpRequest.prototype.open=function(method,url){this.method=method;this.url=url;this.readyState=1;};
window.XMLHttpRequest.prototype.setRequestHeader=function(k,v){this.headers[k]=v;};
window.XMLHttpRequest.prototype.send=function(){var x=this,u=x.url,p=u.replace(/^.*\\/api\\/v1\\//,'');window.__approvalRequests.push(p);var data=window.__approvalResponse(u);setTimeout(function(){x.status=200;x.responseText=JSON.stringify(data);x.readyState=4;if(x.onreadystatechange)x.onreadystatechange();},0);};
`; }
function makeServer() { return new Promise(resolve => { server = http.createServer((req, res) => { let file = req.url.split('?')[0]; if (file === '/') file = '/index.html'; const target = path.resolve(staticRoot, '.' + file); if (!target.startsWith(staticRoot) || !fs.existsSync(target)) { res.writeHead(404); res.end(); return; } const ext = path.extname(target); res.writeHead(200, { 'Content-Type': ext === '.js' ? 'application/javascript' : ext === '.css' ? 'text/css' : 'text/html' }); fs.createReadStream(target).pipe(res); }); server.listen(webPort, '127.0.0.1', resolve); }); }
async function json(url) { const response = await fetch(url); return response.json(); }
function connect(wsUrl) { return new Promise((resolve, reject) => { const ws = new WebSocket(wsUrl); ws.onopen = () => resolve(ws); ws.onerror = reject; }); }
async function main() {
  if (!fs.existsSync(edge)) fail('Microsoft Edge not found: ' + edge);
  await makeServer();
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'ipam-edge-regression-'));
  browser = spawn(edge, ['--headless=new', '--disable-gpu', '--remote-debugging-port=' + debugPort, '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true });
  const targets = await until(() => json('http://127.0.0.1:' + debugPort + '/json').catch(() => null), 'Edge DevTools did not start');
  const page = targets.find(x => x.type === 'page'); if (!page) fail('Edge page target missing');
  const ws = await connect(page.webSocketDebuggerUrl), pending = new Map(); let sequence = 0;
  ws.onmessage = event => { const msg = JSON.parse(event.data); if (msg.id && pending.has(msg.id)) { const p = pending.get(msg.id); pending.delete(msg.id); p.resolve(msg.result); } };
  function cdp(method, params) { return new Promise((resolve, reject) => { const id = ++sequence; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params: params || {} })); }); }
  async function evaluate(expression) { const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }); if (result.exceptionDetails) fail(result.exceptionDetails.text || 'runtime evaluation failed'); return result.result.value; }
  await cdp('Page.enable'); await cdp('Runtime.enable');
  await cdp('Page.addScriptToEvaluateOnNewDocument', { source: 'window.__approvalResponse=' + appResponse.toString() + ';' + fakeXhrScript() });
  await cdp('Page.navigate', { url: 'http://127.0.0.1:' + webPort + '/' });
  await until(() => evaluate("!!document.querySelector('#go')"), 'login form missing');
  await evaluate("document.querySelector('#username').value='admin';document.querySelector('#password').value='test';document.querySelector('#go').click();true");
  await until(() => evaluate("!!document.querySelector('[data-v=applications]')"), 'admin navigation missing');
  await evaluate("document.querySelector('[data-v=applications]').click();true");
  await until(() => evaluate("!!document.querySelector('[data-approve]')"), 'pending application missing');
  await evaluate("document.querySelector('[data-approve]').click();true");
  await until(() => evaluate("document.querySelector('#candidateNext') && document.querySelector('#candidateList').textContent.indexOf('第 1 / 6 页')>=0"), 'candidate page 1 missing');
  await evaluate("document.querySelector('#candidateNext').click();true");
  await until(() => evaluate("document.querySelector('#candidateList').textContent.indexOf('第 2 / 6 页')>=0"), 'next page did not load page 2');
  await evaluate("document.querySelector('#candidateList input.radio').click();true");
  const selected = await evaluate("document.querySelector('#allocationSummary').textContent.indexOf('108.56.207.51')>=0"); if (!selected) fail('page 2 selected IP was not retained in summary');
  await evaluate("document.querySelector('#candidateNext').click();true");
  await until(() => evaluate("document.querySelector('#candidateList').textContent.indexOf('第 3 / 6 页')>=0"), 'next page did not load page 3');
  await evaluate("document.querySelector('#candidatePrev').click();true");
  await until(() => evaluate("document.querySelector('#candidateList').textContent.indexOf('第 2 / 6 页')>=0 && document.querySelector('#candidateList input.radio').checked"), 'selected IP did not survive page round-trip');
  await evaluate("document.querySelector('#candidatePage').value='6';document.querySelector('#candidateGo').click();true");
  await until(() => evaluate("document.querySelector('#candidateList').textContent.indexOf('第 6 / 6 页')>=0"), 'page jump did not reach final page');
  await evaluate("document.querySelector('[data-candidate-ping]').click();true");
  await until(() => evaluate("document.querySelector('#approvalPing').textContent.indexOf('PING')>=0 && window.__approvalRequests.some(function(x){return x.indexOf('reachability')>=0;})"), 'PING request missing');
  await evaluate("document.querySelector('#candidatePrev').click();true");
  await until(() => evaluate("document.querySelector('#candidateList').textContent.indexOf('第 5 / 6 页')>=0"), 'pagination stopped after PING');
  await evaluate("document.querySelector('#finalType').value='TEST';document.querySelector('#finalType').dispatchEvent(new Event('change'));true");
  await until(() => evaluate("document.querySelector('#candidateList').textContent.indexOf('第 1 / 6 页')>=0 && document.querySelector('#allocationSummary').textContent.indexOf('尚未选择 IP')>=0 && document.querySelector('#overrideReasonWrap').style.display!=='none'"), 'range change did not reset selection and require reason');
  const invalid = await evaluate("window.__approvalRequests.some(function(x){return x.indexOf('ip-pool/null/reachability')>=0;})"); if (invalid) fail('pagination requested ip-pool/null/reachability');
  console.log('PASS|BROWSER_APPROVAL_PAGING_PING|page1-page2-page6|selection-retained|range-reset|no-null-ping');
  ws.close();
}
main().catch(error => { console.error(error.stack || error); process.exitCode = 1; }).finally(() => { if (browser) browser.kill(); if (server) server.close(); });
