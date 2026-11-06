/* IPv4 arithmetic uses exact unsigned Number values, including the /0 boundary. ES5 / offline. */
(function (root) {
  'use strict';
  var MAX = 4294967295;
  function ipv4(text) {
    var parts = String(text).split('.'), n = 0, i;
    if (parts.length !== 4) throw new Error('请输入四段十进制 IPv4 地址，例如 192.168.1.100。');
    for (i = 0; i < 4; i++) {
      if (!/^(0|[1-9][0-9]{0,2})$/.test(parts[i]) || Number(parts[i]) > 255) throw new Error('IPv4 每段须为 0–255，且不含前导零。');
      n = n * 256 + Number(parts[i]);
    }
    return n;
  }
  function dotted(n) { var a = [], i; for (i = 3; i >= 0; i--) a.push(Math.floor(n / Math.pow(256, i)) % 256); return a.join('.'); }
  function prefix(text) {
    var s = String(text).replace(/^\s+|\s+$/g, '').replace(/^\//, '');
    if (!/^(0|[1-9][0-9]?)$/.test(s) || Number(s) > 32) throw new Error('CIDR 须为 /0 至 /32 的整数。');
    return Number(s);
  }
  function maskPrefix(text, wildcard) {
    var n = ipv4(String(text).replace(/^\s+|\s+$/g, '')), p;
    if (wildcard) n = MAX - n;
    for (p = 0; p <= 32; p++) if (n === 4294967296 - Math.pow(2, 32 - p)) return p;
    throw new Error(wildcard ? '反掩码须对应连续子网掩码；不支持非连续通配符掩码。' : '子网掩码须为连续的网络位，例如 255.255.255.192。');
  }
  function masks(p) { p = prefix(p); var size = Math.pow(2, 32 - p); return { prefix: p, mask: dotted(4294967296 - size), wildcard: dotted(size - 1) }; }
  function bits(n) { var s = n.toString(2); while (s.length < 32) s = '0' + s; return s; }
  function type(n) {
    var ranges = [
      ['0.0.0.0',32,'未指定地址'], ['255.255.255.255',32,'受限广播地址'],
      ['10.0.0.0',8,'私有地址'], ['172.16.0.0',12,'私有地址'], ['192.168.0.0',16,'私有地址'],
      ['127.0.0.0',8,'环回地址'], ['169.254.0.0',16,'链路本地地址'], ['100.64.0.0',10,'共享地址空间'],
      ['192.0.2.0',24,'文档示例地址'], ['198.51.100.0',24,'文档示例地址'], ['203.0.113.0',24,'文档示例地址'],
      ['198.18.0.0',15,'基准测试地址'], ['224.0.0.0',4,'组播地址'], ['240.0.0.0',4,'保留地址'],
      ['0.0.0.0',8,'本网络地址'], ['192.0.0.0',24,'IETF 协议专用地址'],
      ['192.88.99.0',24,'6to4 / 6a44 特殊用途地址'], ['192.31.196.0',24,'AS112 特殊用途地址'],
      ['192.52.193.0',24,'AMT 特殊用途地址'], ['192.175.48.0',24,'AS112 特殊用途地址']
    ], i, base;
    for (i = 0; i < ranges.length; i++) { base = ipv4(ranges[i][0]); if (n >= base && n < base + Math.pow(2, 32 - ranges[i][1])) return ranges[i][2] + '（' + ranges[i][0] + '/' + ranges[i][1] + '）'; }
    return '其他单播地址（未判定公网可达性）';
  }
  function calculate(address, p) {
    var parts = String(address).replace(/^\s+|\s+$/g, '').split('/');
    if (parts.length > 2) throw new Error('IP/CIDR 格式不正确。');
    if (parts.length === 2) p = prefix(parts[1]);
    p = prefix(p);
    var ip = ipv4(parts[0]), size = Math.pow(2, 32 - p), start = Math.floor(ip / size) * size, end = start + size - 1, m = masks(p);
    return { ip: dotted(ip), prefix: p, mask: m.mask, wildcard: m.wildcard, network: dotted(start), broadcast: p >= 31 ? '不适用' : dotted(end),
      first: dotted(p >= 31 ? start : start + 1), last: dotted(p >= 31 ? end : end - 1), total: size, usable: p >= 31 ? size : size - 2,
      networkDecimal: start, broadcastDecimal: p >= 31 ? null : end, type: type(ip),
      binary: [bits(start), p >= 31 ? null : bits(end), bits(4294967296 - size), bits(size - 1)],
      note: p === 31 ? '/31 按点对点链路计算：两个端点均可用，无定向广播地址。' : p === 32 ? '/32 表示单一主机地址，无独立广播地址。' : '主机数量按子网规则计算，未扣除网关、保留及特殊用途地址；实际空闲情况以 IP 资源池为准。' };
  }
  function mount(container) {
    var source = 'cidr', copyText = '', prefixes = [8,12,16,20,24,25,26,27,28,29,30], h = '', i;
    for (i = 0; i < prefixes.length; i++) h += '<button type="button" class="secondary" data-calc-prefix="' + prefixes[i] + '">/' + prefixes[i] + '</button>';
    container.innerHTML = '<div class="head"><p>实用工具</p><h2>IP地址计算器</h2><span>输入 IPv4 地址与掩码，查看子网范围。计算在当前浏览器内完成。</span></div>' +
      '<section class="card ip-calculator"><form id="calcForm" novalidate><div id="calcNotice" role="status" aria-live="polite"></div><label for="calcIp">IP地址</label><input id="calcIp" autocomplete="off" spellcheck="false" placeholder="192.168.1.100 或 192.168.1.100/26" maxlength="32">' +
      '<div class="form-pair"><div><label for="calcMask">子网掩码</label><input id="calcMask" autocomplete="off" spellcheck="false" maxlength="15"></div><div><label for="calcCidr">CIDR</label><input id="calcCidr" autocomplete="off" spellcheck="false" maxlength="3"></div></div>' +
      '<label for="calcWildcard">反掩码（通配符掩码）</label><input id="calcWildcard" autocomplete="off" spellcheck="false" maxlength="15"><p class="hint">掩码、CIDR、反掩码任填一种，其他两项自动换算；以最近编辑的字段为准。</p>' +
      '<div class="calc-presets" aria-label="常用掩码"><span>常用掩码</span>' + h + '</div><div class="calc-actions"><button class="primary" type="submit" id="calcRun">计算</button><button class="secondary" type="button" id="calcReset">重置</button></div></form>' +
      '<section id="calcResults" style="display:none" aria-live="polite"><div class="card-title-row"><h3>计算结果</h3><button id="calcCopy" type="button" class="secondary" disabled>复制结果</button></div><p id="calcNote" class="hint"></p><dl id="calcValues" class="calc-values"></dl><div id="calcBinary" class="calc-binary"></div><p class="hint"><span class="calc-network">蓝色＝网络位</span>，灰色＝主机位；每行完整显示 32 位。</p></section><textarea id="calcCopyFallback" readonly aria-label="计算结果文本，可手动复制" style="display:none"></textarea></section>';
    function el(id) { return document.getElementById('calc' + id); }
    function notice(text, bad) { el('Notice').className = text ? 'notice' + (bad ? ' bad' : '') : ''; el('Notice').textContent = text; }
    function invalidate() { copyText = ''; el('Results').style.display = 'none'; el('Copy').disabled = true; el('CopyFallback').style.display = 'none'; notice('', false); }
    function setMasks(p) { var m = masks(p); el('Mask').value = m.mask; el('Cidr').value = '/' + p; el('Wildcard').value = m.wildcard; el('Mask').removeAttribute('aria-invalid'); el('Cidr').removeAttribute('aria-invalid'); el('Wildcard').removeAttribute('aria-invalid'); }
    function currentPrefix() { return source === 'mask' ? maskPrefix(el('Mask').value, false) : source === 'wildcard' ? maskPrefix(el('Wildcard').value, true) : prefix(el('Cidr').value); }
    function sync(kind) {
      source = kind; invalidate();
      if (el('Ip').value.indexOf('/') >= 0) el('Ip').value = el('Ip').value.split('/')[0];
      try { setMasks(currentPrefix()); } catch (err) { el(kind === 'mask' ? 'Mask' : kind === 'wildcard' ? 'Wildcard' : 'Cidr').setAttribute('aria-invalid', 'true'); }
    }
    el('Mask').oninput = function () { sync('mask'); }; el('Cidr').oninput = function () { sync('cidr'); }; el('Wildcard').oninput = function () { sync('wildcard'); };
    el('Ip').oninput = function () { invalidate(); var a = this.value.trim().split('/'); if (a.length === 2) { try { setMasks(prefix(a[1])); source = 'cidr'; } catch (ignore) {} } };
    function binaryRow(name, value, p) {
      if (value === null) return '<div><b>' + name + '</b><span>不适用</span></div>';
      var result = '<div><b>' + name + '</b><code>', j;
      for (j = 0; j < 32; j++) { if (j && j % 8 === 0) result += ' '; result += '<span class="' + (j < p ? 'calc-network' : 'calc-host') + '">' + value.charAt(j) + '</span>'; }
      return result + '</code></div>';
    }
    el('Form').onsubmit = function (event) {
      event.preventDefault(); invalidate();
      var r;
      try { r = calculate(el('Ip').value, currentPrefix()); } catch (err) { notice(err.message, true); return false; }
      setMasks(r.prefix);
      var rows = [['输入IP',r.ip],['网络地址',r.network],['广播地址',r.broadcast],['子网掩码',r.mask],['反掩码',r.wildcard],['CIDR','/' + r.prefix],['可用主机范围',r.first + ' – ' + r.last],['可用主机数量',r.usable + ' 个'],['总IP数量',r.total + ' 个'],['IP类型（输入地址）',r.type],['网络地址（十进制）',r.networkDecimal],['广播地址（十进制）',r.broadcastDecimal === null ? '不适用' : r.broadcastDecimal]], html = '', k;
      copyText = 'IP地址计算结果\r\n';
      for (k = 0; k < rows.length; k++) { html += '<div><dt>' + rows[k][0] + '</dt><dd>' + rows[k][1] + '</dd></div>'; copyText += rows[k][0] + '：' + rows[k][1] + '\r\n'; }
      el('Values').innerHTML = html; el('Note').textContent = r.note;
      var labels = ['网络地址','广播地址','子网掩码','反掩码']; html = '';
      for (k = 0; k < 4; k++) { html += binaryRow(labels[k],r.binary[k],r.prefix); copyText += labels[k] + '（二进制）：' + (r.binary[k] === null ? '不适用' : r.binary[k].match(/.{8}/g).join(' ')) + '\r\n'; }
      copyText += r.note + '\r\n可用主机数量不等于资源池空闲数量。'; el('Binary').innerHTML = html; el('Results').style.display = 'block'; el('Copy').disabled = false; return false;
    };
    el('Reset').onclick = function () { invalidate(); source = 'cidr'; el('Ip').value = ''; setMasks(24); el('Ip').focus(); };
    var buttons = container.querySelectorAll('[data-calc-prefix]');
    for (i = 0; i < buttons.length; i++) buttons[i].onclick = function () { el('Cidr').value = '/' + this.getAttribute('data-calc-prefix'); sync('cidr'); };
    el('Copy').onclick = function () {
      var area = el('CopyFallback'), success = false; area.value = copyText; area.style.display = 'block'; area.focus(); area.select();
      try { success = document.execCommand('copy'); } catch (ignore) {}
      if (success) { area.style.display = 'none'; el('Copy').focus(); notice('计算结果已复制。', false); } else notice('浏览器未允许自动复制，请复制下方已选中的结果文本。', false);
    };
    setMasks(24);
  }
  function prefix6(value) {
    var text = String(value).trim().replace(/^\//, '');
    if (!/^(0|[1-9][0-9]{0,2})$/.test(text) || Number(text) > 128) throw new Error('IPv6 前缀长度须为 0 至 128 的整数。');
    return Number(text);
  }
  function words6(text) {
    function invalid() { throw new Error('请输入有效的 IPv6 地址。每组为 1 至 4 位十六进制数，:: 最多出现一次。'); }
    if (text.indexOf('.') >= 0) {
      var colon = text.lastIndexOf(':');
      if (colon < 0) invalid();
      var tail = ipv4(text.slice(colon + 1));
      text = text.slice(0, colon + 1) + Math.floor(tail / 65536).toString(16) + ':' + (tail % 65536).toString(16);
    }
    var sides = text.split('::'), left = sides[0] ? sides[0].split(':') : [];
    var right = sides[1] ? sides[1].split(':') : [], missing = 8 - left.length - right.length;
    if (sides.length > 2 || (sides.length === 1 ? missing !== 0 : missing < 1)) invalid();
    while (missing-- > 0) left.push('0');
    return left.concat(right).map(function (word) {
      if (!/^[0-9a-f]{1,4}$/i.test(word)) invalid();
      return parseInt(word, 16);
    });
  }
  function format6(words) {
    var best = -1, length = 1, run = 0;
    words.forEach(function (word, index) {
      run = word === 0 ? run + 1 : 0;
      if (run > length) { best = index + 1 - run; length = run; }
    });
    var hex = words.map(function (word) { return word.toString(16); });
    return best < 0 ? hex.join(':') : hex.slice(0, best).join(':') + '::' + hex.slice(best + length).join(':');
  }
  function mask6(prefixLength) {
    var mask = [], i;
    for (i = 0; i < 8; i++) {
      var used = Math.min(16, Math.max(0, prefixLength - i * 16));
      mask.push(65536 - Math.pow(2, 16 - used));
    }
    return mask;
  }
  // Decimal doubling keeps all 128 bits exact on browsers without BigInt.
  function addressCount6(hostBits) {
    var digits = [1], i, j;
    for (i = 0; i < hostBits; i++) {
      var carry = 0;
      for (j = 0; j < digits.length; j++) {
        var doubled = digits[j] * 2 + carry;
        digits[j] = doubled % 10;
        carry = Math.floor(doubled / 10);
      }
      if (carry) digits.push(carry);
    }
    return digits.reverse().join('');
  }
  function type6(words) {
    var ip = format6(words);
    if (ip === '::') return '未指定地址';
    if (ip === '::1') return '环回地址';
    if (words[5] === 65535 && words.slice(0, 5).every(function (word) { return word === 0; })) return 'IPv4 映射地址';
    if ((words[0] & 0xff00) === 0xff00) return '组播地址';
    if ((words[0] & 0xffc0) === 0xfe80) return '链路本地单播地址';
    if ((words[0] & 0xfe00) === 0xfc00) return '唯一本地地址';
    if ((words[0] === 0x2001 && words[1] === 0xdb8) || (words[0] === 0x3fff && (words[1] & 0xf000) === 0)) return '文档示例地址';
    if ((words[0] & 0xe000) === 0x2000) return '全球单播范围';
    return '其他 IPv6 地址';
  }
  function calculate6(address, prefixLength) {
    var parts = String(address).trim().split('/');
    if (parts.length > 2) throw new Error('请输入一个 IPv6 地址和一个前缀长度。');
    var words = words6(parts[0]), p = prefix6(parts.length === 2 ? parts[1] : prefixLength), mask = mask6(p);
    var first = words.map(function (word, i) { return word & mask[i]; });
    var last = first.map(function (word, i) { return word + 65535 - mask[i]; });
    return { ip: format6(words), prefix: p, network: format6(first), last: format6(last), mask: format6(mask),
      total: addressCount6(128 - p), type: type6(words), words: words };
  }
  function mountCalculator(container) {
    mount(container);
    var ipv4Panel = container.querySelector('.ip-calculator');
    container.querySelector('.head span').textContent = '选择地址版本，计算子网范围。计算在当前浏览器内完成。';
    ipv4Panel.insertAdjacentHTML('beforebegin', '<div class="calc-presets calc-modes" role="group" aria-label="地址版本"><button type="button" id="mode4" aria-pressed="true">IPv4</button><button type="button" id="mode6" aria-pressed="false">IPv6</button></div>');
    var panel = document.createElement('section');
    panel.className = 'card ip-calculator';
    panel.style.display = 'none';
    container.appendChild(panel);
    var presets = [32,48,56,60,64,127,128].map(function (p) {
      return '<button type="button" class="secondary" data-prefix6="' + p + '">/' + p + '</button>';
    }).join('');
    panel.innerHTML = '<form novalidate><label for="ip6">IPv6 地址</label><input id="ip6" autocomplete="off" spellcheck="false" placeholder="例如 2001:db8:85a3::1/64"><p class="hint">支持完整地址、:: 压缩写法及带前缀的地址。</p>' +
      '<div class="form-pair"><div><label for="prefix6">前缀长度（CIDR）</label><input id="prefix6" value="/64" maxlength="4"></div><div><label for="mask6">子网掩码（自动换算）</label><input id="mask6" readonly></div></div>' +
      '<div class="calc-presets"><span>常用前缀</span>' + presets + '</div><div class="calc-actions"><button class="primary">计算</button><button type="reset" class="secondary">重置</button></div></form>' +
      '<div id="notice6" role="status"></div><section id="result6" style="display:none" aria-live="polite"><div class="card-title-row"><h3>计算结果</h3><button id="copy6" class="secondary">复制结果</button></div><dl class="calc-values"></dl>' +
      '<p class="hint">地址总数包括段内所有地址，实际可分配数量取决于本行规划。IPv6 无广播地址。</p><div class="calc-binary"></div><p class="hint">蓝色为网络位，灰色为主机位。每组 16 位。</p><textarea readonly style="display:none" aria-label="手动复制 IPv6 结果"></textarea></section>';
    var ip = panel.querySelector('#ip6'), cidr = panel.querySelector('#prefix6'), maskField = panel.querySelector('#mask6');
    var result = panel.querySelector('#result6'), notice = panel.querySelector('#notice6'), area = panel.querySelector('textarea');
    function clear() { notice.className = ''; result.style.display = 'none'; area.style.display = 'none'; notice.textContent = ''; }
    function syncMask() {
      try { maskField.value = format6(mask6(prefix6(cidr.value))); }
      catch (ignore) { maskField.value = ''; }
    }
    function changePrefix() { clear(); ip.value = ip.value.split('/')[0]; syncMask(); }
    cidr.oninput = changePrefix;
    ip.oninput = function () {
      clear();
      var parts = ip.value.split('/');
      if (parts.length === 2) { cidr.value = '/' + parts[1]; syncMask(); }
    };
    panel.querySelector('.calc-presets').onclick = function (event) {
      var value = event.target.getAttribute('data-prefix6');
      if (value !== null) { cidr.value = '/' + value; changePrefix(); }
    };
    panel.querySelector('form').onreset = function (event) {
      event.preventDefault(); ip.value = ''; cidr.value = '/64'; changePrefix(); ip.focus();
    };
    panel.querySelector('form').onsubmit = function (event) {
      event.preventDefault(); clear();
      var r;
      try { r = calculate6(ip.value, cidr.value); }
      catch (error) { notice.className = 'notice bad'; notice.textContent = error.message; return; }
      cidr.value = '/' + r.prefix; syncMask();
      var rows = [['IPv6 地址',r.ip],['网络地址',r.network + '/' + r.prefix],['子网掩码',r.mask],['段内首地址',r.network],
        ['段内末地址',r.last],['地址总数',r.total],['输入地址类型',r.type]];
      panel.querySelector('dl').innerHTML = rows.map(function (row) { return '<div><dt>' + row[0] + '</dt><dd>' + row[1] + '</dd></div>'; }).join('');
      area.value = rows.map(function (row) { return row.join('：'); }).join('\r\n');
      panel.querySelector('.calc-binary').innerHTML = '<b>输入地址的网络位与主机位</b><div class="v6-bits">' + r.words.map(function (word, group) {
        var bits = ('0000000000000000' + word.toString(2)).slice(-16);
        var n = Math.max(0, Math.min(16, r.prefix - group * 16));
        return '<code><span class="calc-network">' + bits.slice(0,n) + '</span><span class="calc-host">' + bits.slice(n) + '</span></code>';
      }).join(' ') + '</div>';
      result.style.display = 'block';
    };
    panel.querySelector('#copy6').onclick = function () {
      area.style.display = 'block'; area.focus(); area.select();
      var copied = false;
      try { copied = document.execCommand('copy'); } catch (ignore) {}
      if (copied) { area.style.display = 'none'; this.focus(); }
      notice.className = 'notice'; notice.textContent = copied ? '计算结果已复制。' : '请手动复制下方已选中的文本。';
    };
    syncMask();
    var mode4 = container.querySelector('#mode4'), mode6 = container.querySelector('#mode6');
    function switchMode(v6) {
      ipv4Panel.style.display = v6 ? 'none' : 'block'; panel.style.display = v6 ? 'block' : 'none';
      mode4.setAttribute('aria-pressed', String(!v6)); mode6.setAttribute('aria-pressed', String(v6));
    }
    mode4.onclick = function () { switchMode(false); }; mode6.onclick = function () { switchMode(true); };
  }
  root.IpamCalculator = { calculate: calculate, ipv4: ipv4, maskPrefix: maskPrefix, masks: masks, bits: bits, calculate6: calculate6, mount: mountCalculator };
}(typeof window !== 'undefined' ? window : module.exports));
