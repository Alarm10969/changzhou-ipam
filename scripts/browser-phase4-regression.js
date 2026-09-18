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
  if (name === 'admin/audit') return { items: [{ id: 10, action: 'UPDATE_MAC', targetType: 'IP_ASSIGNMENT', targetId: '77', result: 'SUCCESS', createdAt: '2026-09-16T00:00:00Z', actor: 'admin', macAddressBefore: 'AA:BB:CC:DD:EE:00', macAddressAfter: '11:22:33:44:55:66' }, { id: 9, action: 'UPDATE_SWITCH_PORT', targetType: 'IP_ASSIGNMENT', targetId: '77', result: 'SUCCESS', createdAt: '2026-09-16T00:00:00Z', actor: 'admin', switchPortBefore: 'GE0/0/1', switchPortAfter: 'Eth-Trunk1', detailJson: '{"password":"SHOULD_NOT_RENDER"}' }] };
  if (name === 'admin/outlets' || name === 'outlets' || name === 'branches') return { items: [{ id: 1, branchId: 1, branchName: '回归支行', outletName: '回归网点', name: '回归网点', code: 'O4', active: 1, rangeId: 1 }] };
  if (name === 'admin/ip-ranges') return { items: [] };
  if (name === 'admin/outlet-plans/preview') {
    const production = JSON.parse(body).productionCidr;
    const free = production === '108.56.32.32/27';
    return { segments: [{ type: 'PRODUCTION', cidr: production, gateway: free ? '108.56.32.62' : '108.56.32.30' }], conflicts: free ? [] : [{ candidateType: 'PRODUCTION', candidateCidr: production, occupiedType: 'PRODUCTION', occupiedCidr: '108.56.32.0/24', occupiedBy: '其他支行 / 其他网点（O9）' }], suggestedProductionCidr: free ? null : '108.56.32.32/27' };
  }
  if (name === 'admin/outlet-plan-import') return { status: 'FAILED', totalRows: 2, validRows: 0, errorRows: 2, previews: [], errors: [{ rowNumber: 2, message: '108.56.48.0/27 与导入第 3 行的 108.56.48.0/24 重叠；建议生产网段 108.56.49.0/27。' }] };
  if (name === 'asset-categories') return { items: [] };
  if (name.indexOf('admin/applications?') === 0) return { items: [{ id: 42, status: 'PENDING', createdAt: '2026-09-16T00:00:00Z', applicantUsername: 'tech', applicantName: '科技管理员', branchName: '回归支行', outletName: '回归网点', assetType: 'NON_TECH', assetName: '端口回归资产', requiresUser: 0, requiresResourceCode: 0, recommendedRangeType: 'OFFICE' }] };
  if (name.indexOf('admin/applications/42/candidates') === 0) return { items: [{ id: 77, ipAddress: '108.56.0.10', cidr: '108.56.0.0/24', rangeType: 'OFFICE' }], total: 1, page: 1, pageSize: 50, rangeType: 'OFFICE', recommendedRangeType: 'OFFICE' };
  if (name.indexOf('admin/ip-pool-v2?') === 0) return { items: [
    { id: 10, ipAddress: '108.56.0.10', state: 'ASSIGNED', editable: 0, assignmentId: 77, assignmentVersion: 3, switchPort: 'GE0/0/1', macAddress: 'AA:BB:CC:DD:EE:FF', assignmentSource: 'APPLICATION', allocationSummary: '端口回归资产', branchName: '回归支行', outletName: '回归网点', rangeId: 1, rangeType: 'OFFICE', cidr: '108.56.0.0/24', gateway: '108.56.0.1', dnsPrimary: '114.114.114.114', applicantUsername: 'tech', reviewer: 'admin' },
    { id: 11, ipAddress: '108.56.0.11', state: 'AVAILABLE', editable: 1, assignmentId: null, assignmentVersion: null, allocationSummary: '—', branchName: '回归支行', outletName: '回归网点', rangeId: 1, rangeType: 'OFFICE', cidr: '108.56.0.0/24', gateway: '108.56.0.1', dnsPrimary: '114.114.114.114' },
    { id: 12, ipAddress: '108.56.0.12', state: 'DISABLED', editable: 1, assignmentId: null, assignmentVersion: null, allocationSummary: '—', branchName: '回归支行', outletName: '回归网点', rangeId: 1, rangeType: 'OFFICE', cidr: '108.56.0.0/24', gateway: '108.56.0.1', dnsPrimary: '114.114.114.114' },
    { id: 13, ipAddress: '108.56.0.13', state: 'MANUAL_RESERVED', editable: 1, assignmentId: null, assignmentVersion: null, allocationSummary: '—', branchName: '回归支行', outletName: '回归网点', rangeId: 1, rangeType: 'OFFICE', cidr: '108.56.0.0/24', gateway: '108.56.0.1', dnsPrimary: '114.114.114.114' },
    { id: 14, ipAddress: '108.56.0.1', state: 'SYSTEM_RESERVED', editable: 0, assignmentId: null, assignmentVersion: null, allocationSummary: '—', branchName: '回归支行', outletName: '回归网点', rangeId: 1, rangeType: 'OFFICE', cidr: '108.56.0.0/24', gateway: '108.56.0.1', dnsPrimary: '114.114.114.114' }
  ], total: 5, page: 1, pageSize: 50 };
  if (name.indexOf('admin/ip-pool/') === 0 && name.indexOf('/manual-assignment') > 0) {
    if (method === 'POST') { window.__manualSaved = true; return { id: 88, ipAddress: '108.56.0.11', assignmentSource: 'MANUAL_REGISTRATION', registeredAt: '2026-09-17T00:00:00Z' }; }
    return { pool: { id: 11, ipAddress: '108.56.0.11', sourceState: 'AVAILABLE', state: 'AVAILABLE', branchName: '回归支行', outletName: '回归网点', cidr: '108.56.0.0/24', rangeId: 1 }, assets: [{ id: 1, assetType: 'TECH', name: '服务器', requiresUser: 0, requiresResourceCode: 1 }, { id: 2, assetType: 'NON_TECH', name: '摄像机', requiresUser: 1, requiresResourceCode: 0 }], responsibleTechAdmins: [{ id: 2, username: 'tech', realName: '科技管理员' }] };
  }
  if (name.indexOf('admin/ip-pool/10/mac') === 0 && method === 'PUT') { window.__macSaved = true; return { changed: true, macAddress: '11:22:33:44:55:66', assignmentVersion: 4 }; }
  if (name.indexOf('admin/ip-ranges/1/gateway-options') === 0) return { range: { id: 1, cidr: '108.56.0.0/24', gateway: '108.56.0.1', branchName: '回归支行', outletName: '回归网点' }, items: [{ id: 11, ipAddress: '108.56.0.11', state: 'AVAILABLE', eligible: true }, { id: 12, ipAddress: '108.56.0.12', state: 'DISABLED', eligible: true }, { id: 13, ipAddress: '108.56.0.13', state: 'MANUAL_RESERVED', eligible: true }, { id: 14, ipAddress: '108.56.0.1', state: 'SYSTEM_RESERVED', eligible: false }] };
  if (name.indexOf('admin/ip-ranges/1/gateway') === 0) { if (method === 'POST') { window.__gatewaySaved = true; if (window.__gatewayStale) return { __status: 409, code: 'GATEWAY_STALE', message: '当前网关已被其他管理员更新，请刷新网关选项后重新确认。' }; } return { changed: true, oldGateway: '108.56.0.1', gateway: '108.56.0.12', oldState: 'AVAILABLE', newState: 'SYSTEM_RESERVED' }; }
  if (name.indexOf('/reachability') > 0) return { ipAddress: '108.56.0.10', status: 'NO_REPLY', elapsedMs: 1 };
  if (name === 'applications') return { items: [{ id: 42, status: 'APPROVED', createdAt: '2026-09-16T00:00:00Z', branchName: '回归支行', outletName: '回归网点', assetType: 'NON_TECH', assetName: '端口回归资产', finalIpAddress: '108.56.0.10', finalRangeType: 'OFFICE', switchPort: 'SECRET-PORT', macAddress: 'SECRET-MAC' }] };
  if (name === 'my-assignments') return { items: [{ id: 77, status: 'ASSIGNED', assignedAt: '2026-09-16T00:00:00Z', ipAddress: '108.56.0.10', subnetMask: '255.255.255.0', gateway: '108.56.0.1', branchName: '回归支行', outletName: '回归网点', assetType: 'NON_TECH', switchPort: 'SECRET-PORT', macAddress: 'SECRET-MAC' }] };
  if (name === 'notifications') return { items: [{ id: 1, message: '管理员已处理一条记录', createdAt: '2026-09-16T00:00:00Z', isRead: 1, switchPort: 'SECRET-PORT', macAddress: 'SECRET-MAC' }] };
  return {};
}
function fakeXhrScript() { return `
window.__phase4Requests=[];
window.__phase4Errors=[];
window.addEventListener('error',function(e){window.__phase4Errors.push(String(e.message||e.error||'window error'));});
window.addEventListener('unhandledrejection',function(e){window.__phase4Errors.push(String(e.reason||'unhandled rejection'));});
window.XMLHttpRequest=function(){this.readyState=0;this.status=0;this.responseText='';this.headers={};};
window.XMLHttpRequest.prototype.open=function(method,url){this.method=method;this.url=url;this.readyState=1;};
window.XMLHttpRequest.prototype.setRequestHeader=function(k,v){this.headers[k]=v;};
window.XMLHttpRequest.prototype.send=function(data){var x=this,u=x.url,p=u.replace(/^.*\\/api\\/v1\\//,'');var record={path:p,method:x.method,body:null};try{record.body=data?JSON.parse(data):null;}catch(ignore){}window.__phase4Requests.push(record);var result=window.__phase4Response(u,x.method,data),status=result&&result.__status?result.__status:200;setTimeout(function(){x.status=status;x.responseText=JSON.stringify(result);x.readyState=4;if(x.onreadystatechange)x.onreadystatechange();},0);};
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
  const displayedVersion = await evaluate("document.querySelector('.brand em').textContent"); if (displayedVersion !== '3.8.1 · V023') fail('page header version is not V023');
  await evaluate("document.querySelector('[data-v=applications]').click();true");
  await until(() => evaluate("!!document.querySelector('[data-approve]')"), 'pending application missing');
  await evaluate("document.querySelector('[data-approve]').click();true");
  await until(() => evaluate("!!document.querySelector('#switchPort') && !!document.querySelector('[data-candidate-ping]')"), 'approval switch-port control missing');
  await evaluate("document.querySelector('input.radio').click();document.querySelector('#switchPort').value='bad\\u0001port';document.querySelector('#switchPort').dispatchEvent(new Event('input'));true");
  const retained = await evaluate("document.querySelector('input.radio').checked && document.querySelector('#switchPortHint').textContent.indexOf('控制字符')>=0"); if (!retained) fail('invalid port feedback did not retain selected IP');
  await evaluate("document.querySelector('#switchPort').value='GE0/0/1';document.querySelector('#switchPort').dispatchEvent(new Event('input'));document.querySelector('#macAddress').value='aabbccddeeff';document.querySelector('#macAddress').dispatchEvent(new Event('input'));document.querySelector('#saveEdit').click();true");
  await until(() => evaluate("!document.querySelector('#editModal')"), 'approval dialog did not close');
  const approval = await evaluate("window.__phase4Requests.find(function(x){return x.path.indexOf('admin/applications/42/approve')>=0;})"); if (!approval || !approval.body || approval.body.switchPort !== 'GE0/0/1' || approval.body.macAddress !== 'aabbccddeeff') fail('approval request did not carry switch port and MAC');
  const narrowApproval = await evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"); if (!narrowApproval) fail('approval page overflows at narrow width');
  await evaluate("document.querySelector('[data-v=pool]').click();true");
  await until(() => evaluate("!!document.querySelector('[data-port]') && !!document.querySelector('[data-mac]')"), 'pool switch-port and MAC actions missing');
  await evaluate("document.querySelector('[data-port]').click();true");
  await until(() => evaluate("!!document.querySelector('#switchPort')"), 'pool switch-port dialog missing');
  await evaluate("document.querySelector('#switchPort').value='Eth-Trunk1';document.querySelector('#switchPort').dispatchEvent(new Event('input'));document.querySelector('#saveEdit').click();true");
  await until(() => evaluate("!document.querySelector('#editModal')"), 'pool switch-port dialog did not close');
  await until(() => evaluate("document.querySelector('#notice') && document.querySelector('#notice').textContent.indexOf('交换机端口已保存')>=0"), 'pool switch-port success feedback missing');
  const poolUpdate = await evaluate("window.__phase4Requests.find(function(x){return x.path.indexOf('admin/ip-pool/10/switch-port')>=0;})"); if (!poolUpdate || !poolUpdate.body || poolUpdate.body.switchPort !== 'Eth-Trunk1' || poolUpdate.body.assignmentVersion !== 3) fail('pool switch-port request did not carry guarded update');
  await evaluate("document.querySelector('[data-mac]').click();true");
  await until(() => evaluate("!!document.querySelector('#macAddress')"), 'pool MAC dialog missing');
  await evaluate("document.querySelector('#macAddress').value='112233445566';document.querySelector('#macAddress').dispatchEvent(new Event('input'));document.querySelector('#saveEdit').click();true");
  await until(() => evaluate("!document.querySelector('#editModal')"), 'pool MAC dialog did not close');
  const macUpdate = await evaluate("window.__phase4Requests.find(function(x){return x.path.indexOf('admin/ip-pool/10/mac')>=0;})"); if (!macUpdate || !macUpdate.body || macUpdate.body.macAddress !== '112233445566' || macUpdate.body.assignmentVersion !== 3) fail('pool MAC request did not carry guarded update');
  await evaluate("document.querySelector('[data-manual]').click();true");
  await wait(200); const manualState = await evaluate("({modal:!!document.querySelector('#manualAssetType'),requests:window.__phase4Requests.slice(-3),manualButtons:document.querySelectorAll('[data-manual]').length,errors:window.__phase4Errors})"); if (!manualState.modal) fail('manual assignment dialog missing: ' + JSON.stringify(manualState));
  await evaluate("document.querySelector('#manualAssetType').value='TECH';document.querySelector('#manualAssetType').dispatchEvent(new Event('change'));document.querySelector('#manualAssetId').value='1';document.querySelector('#manualAssetId').dispatchEvent(new Event('change'));true");
  await until(() => evaluate("!!document.querySelector('#manualAssetCode')"), 'manual dynamic resource-code field missing');
  await evaluate("document.querySelector('#manualAssetCode').value='SERVER-001';document.querySelector('#manualMac').value='AA-BB-CC-DD-EE-FF';document.querySelector('#manualNote').value='现场核对';document.querySelector('#confirmSystem').click();true");
  await until(() => evaluate("document.querySelector('#manualReview') && document.querySelector('#manualReview').textContent.indexOf('补录已分配')>=0"), 'manual confirmation summary missing');
  await evaluate("document.querySelector('#confirmSystem').click();true");
  await until(() => evaluate("!document.querySelector('#systemModal')"), 'manual assignment dialog did not close');
  const manualRequest = await evaluate("window.__phase4Requests.find(function(x){return x.path.indexOf('admin/ip-pool/11/manual-assignment')>=0 && x.method==='POST';})"); if (!manualRequest || !manualRequest.body || manualRequest.body.assetCode !== 'SERVER-001' || manualRequest.body.assetType !== 'TECH' || manualRequest.body.macAddress !== 'AA-BB-CC-DD-EE-FF') fail('manual assignment request did not carry dynamic asset and MAC fields');
  await evaluate("document.querySelector('[data-gateway]').click();true");
  await until(() => evaluate("!!document.querySelector('#newGateway')"), 'gateway adjustment dialog missing');
  await evaluate("document.querySelector('#newGateway').value='108.56.0.12';document.querySelector('#gatewayNote').value='路由器切换';document.querySelector('#confirmSystem').click();true");
  await until(() => evaluate("!document.querySelector('#systemModal')"), 'gateway adjustment dialog did not close');
  const gatewayRequest = await evaluate("window.__phase4Requests.find(function(x){return x.path.indexOf('admin/ip-ranges/1/gateway')>=0 && x.method==='POST';})"); if (!gatewayRequest || !gatewayRequest.body || gatewayRequest.body.newGateway !== '108.56.0.12' || gatewayRequest.body.expectedGateway !== '108.56.0.1') fail('gateway adjustment request did not carry guarded target');
  const manualFeedback = await evaluate("document.querySelector('#notice') && document.querySelector('#notice').textContent.indexOf('网关已调整')>=0"); if (!manualFeedback) fail('gateway success feedback missing');
  await evaluate("window.__gatewayStale=true;document.querySelector('[data-gateway]').click();true");
  await until(() => evaluate("!!document.querySelector('#newGateway')"), 'stale gateway dialog missing');
  await evaluate("document.querySelector('#newGateway').value='108.56.0.13';document.querySelector('#confirmSystem').click();true");
  await until(() => evaluate("!!document.querySelector('#gatewayRefresh')"), 'stale gateway refresh prompt missing');
  const staleFeedback = await evaluate("document.querySelector('#gatewayReview').textContent.indexOf('已被其他管理员更新')>=0 && document.querySelector('#gatewayReview').textContent.indexOf('刷新')>=0"); if (!staleFeedback) fail('stale gateway conflict feedback missing');
  await evaluate("document.querySelector('#gatewayRefresh').click();true");
  const narrowPool = await evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1"); if (!narrowPool) fail('pool page overflows at narrow width');
  await evaluate("document.querySelector('[data-v=audit]').click();true");
  await until(() => evaluate("document.querySelector('#table') && document.querySelector('#table').textContent.indexOf('11:22:33:44:55:66')>=0"), 'audit MAC summary missing');
  const auditSafe = await evaluate("document.body.textContent.indexOf('SHOULD_NOT_RENDER')<0 && document.body.textContent.indexOf('detailJson')<0"); if (!auditSafe) fail('audit page rendered raw detail data');
  await evaluate("document.querySelector('[data-v=ranges]').click();true");
  await until(() => evaluate("!!document.querySelector('#planPreview')"), 'range planning page missing');
  await evaluate("document.querySelector('#planBranch').value='1';document.querySelector('#planBranch').dispatchEvent(new Event('change'));document.querySelector('#planOutlet').value='1';document.querySelector('#planProduction').value='108.56.32.0/27';document.querySelector('#planPreview').click();true");
  await until(() => evaluate("!!document.querySelector('#applyPlanSuggestion')"), 'range conflict or suggestion missing');
  const conflictVisible = await evaluate("document.querySelector('#planResult').textContent.indexOf('其他网点')>=0 && document.querySelector('#planSave').disabled"); if (!conflictVisible) fail('range conflict owner or save guard missing');
  await evaluate("document.querySelector('#applyPlanSuggestion').click();true");
  await until(() => evaluate("document.querySelector('#planNotice').textContent.indexOf('未与现有地址段重叠')>=0"), 'suggested production did not re-preview');
  const suggested = await evaluate("document.querySelector('#planProduction').value==='108.56.32.32/27' && !document.querySelector('#planSave').disabled"); if (!suggested) fail('suggestion was not applied safely');
  await evaluate("var dt=new DataTransfer();dt.items.add(new File(['branch_code,outlet_code,production_cidr'], 'test.csv', {type:'text/csv'}));document.querySelector('#planImportFile').files=dt.files;document.querySelector('#planImportPreview').click();true");
  await until(() => evaluate("document.querySelector('#planImportResult').textContent.indexOf('导入第 3 行')>=0"), 'batch overlap detail not visible');
  const batchGuard = await evaluate("!document.querySelector('#planImportConfirm') && document.querySelector('#batchNotice').textContent.indexOf('整批不会写入')>=0"); if (!batchGuard) fail('batch overlap did not block confirmation');
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
  console.log('PASS|BROWSER_PHASE4|v023-header|password-hints|approval-port-mac|pool-port-mac-feedback|manual-assignment-mac|gateway-adjustment|gateway-stale-refresh|audit-summary|range-conflict-suggestion|narrow-screen|tech-port-mac-isolation');
  ws.close();
}
main().catch(error => { console.error(error.stack || error); process.exitCode = 1; }).finally(() => { if (browser) browser.kill(); if (server) server.close(); });
