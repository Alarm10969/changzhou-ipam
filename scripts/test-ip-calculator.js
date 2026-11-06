/* Independent Python ipaddress oracle for every prefix and unsigned IPv4 boundaries. */
const assert = require('assert');
const {spawnSync} = require('child_process');
const c = require('../frontend/ip-calculator.js').IpamCalculator;
const oracle = spawnSync(process.env.IPAM_PYTHON || 'python3', ['-c', `
import ipaddress,json,random
r=random.Random(3021); out=[]
for p in range(33):
 for n in [0,1,2147483647,2147483648,4294967294,4294967295]+[r.randrange(2**32) for _ in range(20)]:
  a=ipaddress.IPv4Address(n); net=ipaddress.IPv4Network(str(a)+'/'+str(p),strict=False)
  out.append([str(a),p,str(net.network_address),str(net.broadcast_address),str(net.netmask),str(net.hostmask),net.num_addresses,str(next(iter(net.hosts()))) if p>=31 else str(net.network_address+1),str(net.broadcast_address) if p>=31 else str(net.broadcast_address-1)])
print(json.dumps(out))
`], {encoding:'utf8'});
assert.equal(oracle.status,0,oracle.stderr);
for(const [ip,p,network,broadcast,mask,wildcard,total,first,last] of JSON.parse(oracle.stdout)) {
 const r=c.calculate(ip,p); assert.equal(r.network,network);assert.equal(r.broadcast,p>=31?'不适用':broadcast);
 assert.equal(r.mask,mask);assert.equal(r.wildcard,wildcard);assert.equal(r.total,total);assert.equal(r.usable,p>=31?total:total-2);
 assert.equal(c.maskPrefix(mask,false),p);assert.equal(c.maskPrefix(wildcard,true),p);
 assert.equal(r.networkDecimal,c.ipv4(network)); assert(r.networkDecimal>=0);assert.equal(r.binary[0].length,32);
 assert.equal(parseInt(r.binary[0],2),r.networkDecimal);
 assert.equal(r.first,first);assert.equal(r.last,last);
}
const sample=c.calculate('192.168.1.100/26',24);
assert.deepEqual([sample.network,sample.broadcast,sample.first,sample.last,sample.usable,sample.total,sample.networkDecimal,sample.broadcastDecimal],['192.168.1.64','192.168.1.127','192.168.1.65','192.168.1.126',62,64,3232235840,3232235903]);
for(const ip of ['','1.2.3','256.1.1.1','01.2.3.4','1.2.3.-1','1.2.3.4/33','1.2.3.4/','1.2.3.4/24/25','<img src=x>','::1']) assert.throws(()=>c.calculate(ip,24));
for(const p of [-1,33,'1.5','abc','0x10','']) assert.throws(()=>c.calculate('1.2.3.4',p));
for(const mask of ['255.0.255.0','255.255.255.1']) assert.throws(()=>c.maskPrefix(mask,false));
assert.throws(()=>c.maskPrefix('0.0.5.255',true));
assert(c.calculate('172.31.255.255',24).type.includes('私有'));assert(!c.calculate('172.32.0.0',24).type.includes('私有'));
assert(c.calculate('224.0.0.1',24).type.includes('组播'));assert(c.calculate('127.0.0.1',8).type.includes('环回'));
console.log('PASS|IP_CALCULATOR|858-python-oracle-cases|all-prefixes|unsigned-boundaries|sample|invalid-inputs|classification');
