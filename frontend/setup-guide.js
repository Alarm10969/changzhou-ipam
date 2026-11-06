(function () {
  'use strict';
  window.IpamSetup = { mount: function (root, api) {
    var alive = {}, state, rule, step = api.step || 0, e = api.escape;
    root._setupInstance = alive;
    function current() { return root._setupInstance === alive && document.getElementById('setupGuide'); }
    function go(page) { api.navigate(page, step); }
    function load() {
      root.innerHTML = api.head('首次使用', '分行初始化向导', '按本行实际网络完成配置；每步保存后可随时离开，回来继续检查。') + '<section id="setupGuide" class="card"><div id="notice"></div><p>正在检查配置…</p></section>';
      api.request('GET', 'admin/setup', null, function (r) {
        if (!current()) return;
        state = r;
        api.request('GET', 'admin/settings', null, function (s) { if (current()) { rule = s; draw(); } });
      });
    }
    function check(ok, label, detail) {
      return '<div class="setup-check"><span class="status ' + (ok ? 'status-approved' : 'status-pending') + '">' + (ok ? '已具备' : '待完成') + '</span><div><b>' + e(label) + '</b><p>' + e(detail) + '</p></div></div>';
    }
    function action(page, label) { return '<button class="secondary" data-setup-page="' + page + '">' + label + '</button>'; }
    function draw() {
      var labels = ['地址规则', '机构与资产', '账号与地址池', '检查与试运行'], h = '<div class="setup-steps" role="group" aria-label="初始化步骤">', i, segment;
      for (i = 0; i < labels.length; i++) h += '<button data-setup-step="' + i + '"' + (i === step ? ' class="active" aria-current="step"' : '') + '><b>' + (i + 1) + '</b>' + labels[i] + '</button>';
      h += '</div><div id="notice"></div>';
      if (step === 0) {
        h += '<h3>确认本行地址规划规则</h3><p>当前访问地址：<b>' + e(window.location.origin || (window.location.protocol + '//' + window.location.host)) + '</b>；服务监听 ' + e(state.bindAddress) + '，端口 ' + e(state.port) + '。</p><p class="hint">服务器 IP 由运维配置。修改访问端口或监听地址，请编辑安装目录 ipam.conf 并重启服务；这里配置的是设备地址池。</p>';
        h += '<div class="grid"><div><label for="setupGlobal">本行管理地址范围（CIDR）</label><input id="setupGlobal" value="' + e(rule.globalCidr) + '" placeholder="例如 10.0.0.0/8"><small class="hint">填写网络地址及前缀；必须覆盖全部已有网段。</small></div><div><label for="setupPrefix">默认单类网段前缀</label><input id="setupPrefix" type="number" min="24" max="30" value="' + rule.segmentPrefix + '"></div><div><label for="setupDns">主 DNS</label><input id="setupDns" value="' + e(rule.dnsPrimary) + '"></div><div><label for="setupDns2">备用 DNS（选填）</label><input id="setupDns2" value="' + e(rule.dnsSecondary) + '"></div></div>';
        h += '<p>六类网段均需保留。第三段偏移按 256 个地址为单位，网关取每段最后一个可用地址。请用实际网点规划核对，避免沿用不适用的默认值。</p><div class="table-scroll"><table><thead><tr><th>网段</th><th>显示名称</th><th>第三段偏移</th></tr></thead><tbody>';
        for (i = 0; i < rule.segments.length; i++) { segment = rule.segments[i]; h += '<tr><td>' + e(segment.name) + '</td><td><input aria-label="' + e(segment.name) + '显示名称" id="setupName' + i + '" value="' + e(segment.name) + '"></td><td><input aria-label="' + e(segment.name) + '第三段偏移" id="setupOffset' + i + '" type="number" min="0" value="' + segment.relativeOffset + '"></td></tr>'; }
        h += '</tbody></table></div><label for="setupReason">确认或变更说明</label><input id="setupReason" maxlength="500" placeholder="例如 按本行实际网络规划核对默认规则"><button id="setupSaveRule" class="primary">保存规则并继续</button><p class="hint">保存生成新的规则版本，不重建已有地址池，也不修改网络设备。</p>';
      } else if (step === 1) {
        h += '<h3>建立机构和设备资产</h3>' + check(state.branchCount > 0, '支行', '已有 ' + state.branchCount + ' 个启用支行。先创建支行，再创建所属网点。') + action('branches', '维护支行');
        h += check(state.outletCount > 0, '网点', '已有 ' + state.outletCount + ' 个启用网点。可单条创建，也可按模板批量导入。') + action('outlets', '维护网点');
        h += check(state.assetCount > 0, '资产', '已有 ' + state.assetCount + ' 个启用资产。请核对资产名称、是否关联使用人和资源编码、推荐网段。') + action('settings', '维护资产');
        h += '<p class="hint">“网点信息”展示库与业务网点独立。申请和地址规划使用的是这里的“支行”和“网点”。</p>';
      } else if (step === 2) {
        h += '<h3>准备首个试运行网点</h3>' + check(state.techUserCount > 0, '科技管理员账号', '已有 ' + state.techUserCount + ' 个启用账号。首次登录需要修改初始密码。') + action('users', '创建使用账号');
        h += check(state.plannedOutletCount > 0 && state.poolCount > 0, '网点六类地址池', '已有 ' + state.plannedOutletCount + ' 个启用网点具备六类地址段。先预览实际派生地址，再确认生成。') + action('ranges', '规划网点地址');
        h += '<p>已有设备请先核对地址池，再在资源池补录已分配。新设备按申请审批流程办理。台账状态不会自动更改电脑或交换机配置。</p>' + action('pool', '查看资源池与存量补录');
      } else {
        h += '<h3>准备情况检查</h3>' + check(state.rulesReviewed, '本行规则已确认', '地址范围、DNS 和六类网段偏移已经保存确认。') + check(state.branchCount > 0 && state.outletCount > 0, '业务机构', '至少有一个启用支行及所属启用网点。') + check(state.assetCount > 0, '可申请资产', '至少有一个启用资产，具体字段规则需由管理员核对。') + check(state.techUserCount > 0, '使用账号', '至少有一个启用科技管理员。') + check(state.plannedOutletCount > 0 && state.poolCount > 0, '首个网点地址池', '至少一个启用网点具备六类地址段及地址池。');
        h += '<p>检查通过表示具备开始试运行的基础条件。请实际完成一次申请、审批、查询和释放，并下载首份完整备份；还需核验真实网络连通和终端浏览器。</p><button id="setupComplete" class="primary"' + (state.ready ? '' : ' disabled') + '>确认准备完成</button> ' + action('backups', '下载完整备份');
        if (state.completedAt) h += '<p class="hint">上次确认时间：' + e(new Date(state.completedAt).toLocaleString()) + '。上述状态按当前数据重新检查。</p>';
      }
      h += '<div class="setup-footer"><button id="setupRefresh" class="secondary">重新检查</button><div>' + (step ? '<button id="setupPrev" class="secondary">上一步</button> ' : '') + (step < 3 ? '<button id="setupNext" class="secondary">' + (step === 0 ? '查看后续步骤' : '下一步') + '</button>' : '') + '</div></div>';
      document.getElementById('setupGuide').innerHTML = h;
      var buttons = document.getElementById('setupGuide').getElementsByTagName('button');
      for (i = 0; i < buttons.length; i++) {
        if (buttons[i].getAttribute('data-setup-step') !== null) buttons[i].onclick = function () { step = Number(this.getAttribute('data-setup-step')); draw(); };
        if (buttons[i].getAttribute('data-setup-page')) buttons[i].onclick = function () { go(this.getAttribute('data-setup-page')); };
      }
      document.getElementById('setupRefresh').onclick = load;
      if (step) document.getElementById('setupPrev').onclick = function () { step--; draw(); };
      if (step < 3) document.getElementById('setupNext').onclick = function () { step++; draw(); };
      if (step === 0) document.getElementById('setupSaveRule').onclick = function () {
        var segments = [], j, data, button = this;
        for (j = 0; j < rule.segments.length; j++) segments.push({ code: rule.segments[j].code, name: document.getElementById('setupName' + j).value, relativeOffset: Number(document.getElementById('setupOffset' + j).value), enabled: true });
        data = { globalCidr: document.getElementById('setupGlobal').value, segmentPrefix: Number(document.getElementById('setupPrefix').value), dnsPrimary: document.getElementById('setupDns').value, dnsSecondary: document.getElementById('setupDns2').value, changeReason: document.getElementById('setupReason').value, segments: segments };
        api.request('PUT', 'admin/settings', data, function () { if (current()) { step = 1; load(); } }, null, button, '保存中…');
      };
      if (step === 3) document.getElementById('setupComplete').onclick = function () {
        api.request('POST', 'admin/setup/complete', {}, function (r) {
          if (!current()) return;
          state = r; draw(); document.getElementById('notice').innerHTML = '<div class="notice">基础准备已确认，可开始首个网点试运行。请完成真实业务核验并下载备份。</div>';
        }, function () { if (current()) document.getElementById('setupComplete').disabled = true; }, this, '重新核验中…');
      };
    }
    load();
  }};
}());
