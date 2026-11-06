// Compare every supported prefix, both boundary addresses and representative inputs with Python.
const assert = require('assert');
const {execFileSync} = require('child_process');
const {calculate6} = require('../frontend/ip-calculator.js').IpamCalculator;
const cases = JSON.parse(execFileSync('python3',['-c',`
import ipaddress as ip, random, json
r = random.Random(30)
rows = []
for prefix in range(129):
    for raw in [0,2**128-1,r.getrandbits(128)]:
        addr = ip.IPv6Address(raw)
        net = ip.IPv6Network((addr,prefix),strict=False)
        rows.append([addr.exploded.upper(),prefix,str(addr),str(net.network_address),str(net.broadcast_address),str(net.netmask),str(net.num_addresses)])
print(json.dumps(rows))
`],{maxBuffer:1024*1024}));
for (const [input,prefix,ip,network,last,mask,total] of cases) {
  const actual = calculate6(input + '/' + prefix);
  assert.deepStrictEqual([actual.ip,actual.network,actual.last,actual.mask,actual.total],[ip,network,last,mask,total]);
}
for (const invalid of [':::', '::1::1', '1:2:3:4:5:6:7::8','1::g','::/129','::/','::/-1','::/0.5','::ffff:192.0.2.256','1:2:3']) {
  assert.throws(() => calculate6(invalid,64));
}
assert.strictEqual(calculate6('::ffff:192.0.2.1/112').type,'IPv4 映射地址');
console.log('PASS|IPv6|'+cases.length+' oracle cases|all prefixes|invalid forms|mapped IPv4');
