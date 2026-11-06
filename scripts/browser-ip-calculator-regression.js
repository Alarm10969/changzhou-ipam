const fs=require('fs'),path=require('path'),http=require('http'),assert=require('assert'),os=require('os');
const {chromium}=require(process.env.IPAM_PLAYWRIGHT||path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));
const root=path.resolve(__dirname,'..'), output=path.join(root,'outputs/ip-calculator-browser');
let server,browser,requests=0;const errors=[];
async function run(){
 fs.mkdirSync(output,{recursive:true});
 server=http.createServer((req,res)=>{
  if(req.url.startsWith('/api/')){requests++;let b='';req.on('data',x=>b+=x);req.on('end',()=>{let data={items:[]};if(req.url.endsWith('/auth/login')){const u=JSON.parse(b);data={id:1,realName:'计算器测试',role:u.username==='tech'?'TECH_ADMIN':'ADMIN',mustChangePassword:false,csrfToken:'test'};}else if(req.url.endsWith('/admin/dashboard')){data={pendingCount:0,branchCount:0,outletCount:0,availableIpCount:0};}else if(req.url.endsWith('/auth/me')){res.writeHead(401,{'Content-Type':'application/json'});res.end('{}');return;}res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify(data));});return;}
  const f=path.join(root,'frontend',req.url==='/'?'index.html':req.url.split('?')[0]);if(!fs.existsSync(f)){res.writeHead(404);res.end();return;}res.writeHead(200,{'Content-Type':f.endsWith('.js')?'application/javascript':f.endsWith('.css')?'text/css':'text/html'});res.end(fs.readFileSync(f));
 });await new Promise(r=>server.listen(0,'127.0.0.1',r));
 browser=await chromium.launch({executablePath:process.env.IPAM_EDGE||(process.platform==='darwin'?'/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge':'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'),headless:true});
 for(const role of ['admin','tech']){
  const ctx=await browser.newContext({viewport:{width:1440,height:1100}}),p=await ctx.newPage();p.on('pageerror',e=>errors.push(e.message));
  await p.goto('http://127.0.0.1:'+server.address().port);await p.fill('#username',role);await p.fill('#password','Test1234');await p.click('#go');await p.waitForSelector('[data-v="calculator"]');
  await p.click('[data-v="calculator"]');await p.waitForSelector('#calcForm');const before=requests;await ctx.setOffline(true);
  await p.fill('#calcIp','192.168.1.100/26');assert.equal(await p.inputValue('#calcMask'),'255.255.255.192');assert.equal(await p.inputValue('#calcWildcard'),'0.0.0.63');
  await p.press('#calcIp','Enter');await p.waitForSelector('#calcResults');assert((await p.textContent('#calcValues')).includes('192.168.1.126'));
  assert.equal(await p.locator('#calcBinary code').count(),4);assert.equal(await p.locator('#calcBinary code').first().textContent(),'11000000 10101000 00000001 01000000');
  await p.click('#calcCopy');assert((await p.textContent('#calcNotice')).includes('已复制'));
  if(role==='admin')for(const w of [1440,700,390]){await p.setViewportSize({width:w,height:1100});await p.screenshot({path:path.join(output,'calculator-'+w+'.png'),fullPage:true});assert(await p.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'page horizontal overflow');}
  await p.evaluate(()=>document.execCommand=function(){return false;});await p.click('#calcCopy');assert(await p.isVisible('#calcCopyFallback'));assert((await p.inputValue('#calcCopyFallback')).includes('3232235903'));
  await p.fill('#calcMask','255.0.255.0');assert(!await p.isVisible('#calcResults'));await p.click('#calcRun');assert((await p.textContent('#calcNotice')).includes('连续'));
  await p.fill('#calcWildcard','0.0.0.1');assert.equal(await p.inputValue('#calcCidr'),'/31');await p.click('#calcRun');assert((await p.textContent('#calcValues')).includes('不适用'));assert((await p.textContent('#calcNote')).includes('点对点'));
  await p.fill('#calcCidr','/32');await p.click('#calcRun');assert((await p.textContent('#calcNote')).includes('单一主机'));
  await p.fill('#calcCidr','/0');await p.click('#calcRun');assert((await p.textContent('#calcValues')).includes('4294967296'));
  await p.click('[data-calc-prefix="27"]');assert.equal(await p.inputValue('#calcCidr'),'/27');
  await p.fill('#calcIp','<img src=x onerror=alert(1)>');await p.click('#calcRun');assert(!await p.isVisible('#calcResults'));assert.equal(await p.locator('#calcNotice img').count(),0);
  await p.click('#calcReset');assert.equal(await p.inputValue('#calcIp'),'');assert.equal(await p.inputValue('#calcCidr'),'/24');assert(!await p.isVisible('#calcResults'));await p.click('#mode6');
  await p.fill('#ip6','ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/0'); await p.press('#ip6','Enter');
  assert((await p.textContent('#result6')).includes('340282366920938463463374607431768211456'));
  await p.setViewportSize({width:390,height:900});
  assert(await p.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'IPv6 page horizontal overflow');
  await p.click('#copy6'); assert(await p.isVisible('#result6 textarea'));
  await p.click('#mode4'); await p.click('#mode6'); assert(await p.isVisible('#result6'));
  await p.fill('#ip6','2001::db8::1'); await p.press('#ip6','Enter');
  assert(!(await p.isVisible('#result6')) && (await p.textContent('#notice6')).includes('IPv6'));
  assert.equal(requests,before,'calculator made network requests');await ctx.close();
 }
 assert.deepEqual(errors,[]);console.log('PASS|BROWSER_IP_CALCULATOR|both-roles|offline|cidr-mask-wildcard|boundaries|copy-fallback|enter-reset|invalid-stale|32-bits|1440-700-390');
}
run().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();if(server)await new Promise(r=>server.close(r));});
