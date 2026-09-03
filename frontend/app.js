(function () {
  'use strict';

  var app = document.getElementById('app');
  var S = { u: null, c: null, v: 'home', branches: [], loginNotice: '' };
  var lastDialogFocus = null;

  function e(x) {
    return String(x == null ? '' : x)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function busy(button, active, text) {
    if (!button) return;
    if (active) {
      if (button.getAttribute('data-busy') === '1') return false;
      button.setAttribute('data-busy', '1');
      button.setAttribute('data-label', button.innerHTML);
      button.disabled = true;
      button.innerHTML = text || '处理中…';
      return true;
    }
    button.disabled = false;
    button.innerHTML = button.getAttribute('data-label') || button.innerHTML;
    button.removeAttribute('data-busy');
    button.removeAttribute('data-label');
    return true;
  }

  function sessionExpired() {
    S = { u: null, c: null, v: 'home', branches: [], loginNotice: '登录状态已失效，请重新登录' };
    closeDialog(document.getElementById('systemModal'));
    closeDialog(document.getElementById('ipModal'));
    closeDialog(document.getElementById('editModal'));
    render();
  }

  function endLoading() {
    var table = document.getElementById('table');
    var history = document.getElementById('bulkHistory');
    var count = document.getElementById('poolCount') || document.getElementById('pendingCount');
    if (table && table.innerHTML === '加载中…') table.innerHTML = empty('内容加载失败', '请根据页面提示处理后重试。');
    if (history && history.innerHTML === '加载中…') history.innerHTML = empty('记录加载失败', '请稍后重试。');
    if (count && count.innerHTML === '加载中') count.innerHTML = '加载失败';
  }

  function q(method, path, data, ok, fail, button, busyText) {
    var x = new XMLHttpRequest();
    if (button && busy(button, true, busyText) === false) return;
    x.open(method, '/api/v1/' + path, true);
    x.setRequestHeader('Accept', 'application/json');
    if (method !== 'GET' && method !== 'HEAD' && S.c) {
      x.setRequestHeader('X-CSRF-Token', S.c);
    }
    if (data !== null) {
      x.setRequestHeader('Content-Type', 'application/json');
    }
    x.onreadystatechange = function () {
      var r = {};
      if (x.readyState !== 4) return;
      try { r = JSON.parse(x.responseText || '{}'); } catch (ignore) {}
      busy(button, false);
      if (x.status >= 200 && x.status < 300) { if (ok) ok(r); return; }
      if (x.status === 401 || r.code === 'AUTH_REQUIRED' || r.code === 'SESSION_INVALID') { sessionExpired(); return; }
      if (r.code === 'PASSWORD_CHANGE_REQUIRED') {
        if (S.u) S.u.mustChangePassword = true;
        S.v = 'home';
        render();
        msg('请先修改密码，完成后开放业务功能。', 1);
        if (fail) fail(r);
        return;
      }
      endLoading();
      msg(r.message || (x.status === 0 ? '网络连接中断，请检查服务状态。' : '操作未完成，请核对输入后重试。'), 1);
      if (fail) fail(r);
    };
    x.onerror = function () { busy(button, false); endLoading(); msg('网络连接中断，请检查服务状态。', 1); if (fail) fail({ code: 'NETWORK_ERROR' }); };
    x.send(data === null ? null : JSON.stringify(data));
  }

  function upload(path, file, ok, fail, button) {
    var x = new XMLHttpRequest();
    var body = new FormData();
    body.append('file', file);
    if (button && busy(button, true, '上传校验中…') === false) return;
    x.open('POST', '/api/v1/' + path, true);
    x.setRequestHeader('Accept', 'application/json');
    x.setRequestHeader('X-CSRF-Token', S.c);
    x.onreadystatechange = function () {
      var r = {};
      if (x.readyState !== 4) return;
      try { r = JSON.parse(x.responseText || '{}'); } catch (ignore) {}
      busy(button, false);
      if (x.status >= 200 && x.status < 300) { ok(r); return; }
      if (x.status === 401 || r.code === 'AUTH_REQUIRED' || r.code === 'SESSION_INVALID') { sessionExpired(); return; }
      if (r.code === 'PASSWORD_CHANGE_REQUIRED') { if (S.u) S.u.mustChangePassword = true; S.v = 'home'; render(); msg('请先修改密码，完成后开放业务功能。', 1); return; }
      msg(r.message || '文件上传失败，请核对文件格式后重试。', 1);
      if (fail) fail(r);
    };
    x.onerror = function () { busy(button, false); msg('文件上传连接中断，请检查服务状态。', 1); if (fail) fail({ code: 'NETWORK_ERROR' }); };
    x.send(body);
  }

  function msg(text, bad) {
    var n = document.getElementById('editNotice') || document.getElementById('notice');
    if (n) n.innerHTML = '<div class="notice ' + (bad ? 'bad' : '') + '">' + e(text) + '</div>';
  }

  function val(id) {
    return document.getElementById(id).value;
  }

  function role(value) {
    return value === 'ADMIN' ? '管理员' : '科技管理员';
  }

  function statusText(value) {
    if (value === 'PENDING') return '待审批';
    if (value === 'APPROVED') return '已通过';
    if (value === 'REJECTED') return '已驳回';
    return value;
  }

  function ipStateText(value) {
    if (value === 'AVAILABLE') return '可用';
    if (value === 'DISABLED') return '停用';
    if (value === 'MANUAL_RESERVED') return '人工保留';
    if (value === 'SYSTEM_RESERVED') return '系统保留';
    if (value === 'ASSIGNED') return '已分配';
    return value;
  }

  function formatDate(value) {
    var d = new Date(value);
    function two(v) { return v < 10 ? '0' + v : String(v); }
    if (!value || isNaN(d.getTime())) return value || '—';
    return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate()) + ' ' + two(d.getHours()) + ':' + two(d.getMinutes());
  }

  function waiting(value) {
    var minutes = Math.max(0, Math.floor((new Date().getTime() - new Date(value).getTime()) / 60000));
    if (minutes < 60) return '等待 ' + minutes + ' 分钟';
    if (minutes < 1440) return '等待 ' + Math.floor(minutes / 60) + ' 小时';
    return '等待 ' + Math.floor(minutes / 1440) + ' 天';
  }

  function empty(text, action, target) {
    return '<div class="empty"><b>' + e(text) + '</b>' + (action ? '<p>' + e(action) + '</p>' : '') + (target ? '<button class="secondary" data-empty-target="' + e(target) + '">立即前往</button>' : '') + '</div>';
  }

  function reloadMessage(reload, text, keepPage) {
    var main = document.getElementById('main');
    S.flash = text;
    if (main && !keepPage) main.innerHTML = '<section class="card"><p>正在刷新结果…</p></section>';
    reload();
    showFlash(0);
  }

  function showFlash(attempt) {
    var notice;
    if (!S.flash) return;
    notice = document.getElementById('editNotice') || document.getElementById('notice');
    if (notice) { msg(S.flash, 0); S.flash = ''; return; }
    if (attempt < 30) setTimeout(function () { showFlash(attempt + 1); }, 100);
  }

  function copyText(text) {
    var area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', 'readonly');
    area.style.position = 'fixed';
    area.style.left = '-9999px';
    document.body.appendChild(area);
    area.select();
    try { document.execCommand('copy'); msg('网络参数已复制。', 0); } catch (ignore) { msg('无法自动复制，请手动选择网络参数。', 1); }
    document.body.removeChild(area);
  }

  function login() {
    var button = document.getElementById('go');
    q('POST', 'auth/login', { username: val('username'), password: val('password') }, function (r) {
      S.c = r.csrfToken;
      delete r.csrfToken;
      S.u = r;
      S.v = r.mustChangePassword ? 'home' : (r.role === 'ADMIN' ? 'home' : 'apply');
      S.loginNotice = '';
      render();
    }, null, button, '正在登录…');
  }

  function loginPage() {
    app.innerHTML = '<div class="login"><div class="login-copy"><b>IP</b><p>CHANGZHOU · INTERNAL</p><h1>IP 资源管理平台</h1><span>清晰、安全、可追溯的资源基础管理。</span></div><div class="login-box"><p>身份验证</p><h2>欢迎登录</h2><div id="notice">' + (S.loginNotice ? '<div class="notice bad">' + e(S.loginNotice) + '</div>' : '') + '</div><label for="username">账号 <span class="required">*</span></label><input id="username" autocomplete="username" placeholder="请输入登录账号"><label for="password">密码 <span class="required">*</span></label><input id="password" type="password" autocomplete="current-password" placeholder="请输入密码"><button id="go" class="primary">安全登录</button></div></div>';
    document.getElementById('go').onclick = login;
    document.getElementById('password').onkeydown = function (x) {
      if ((x || window.event).keyCode === 13) login();
    };
  }

  function nav() {
    var x = '<button data-v="home">概览与密码</button>';
    if (S.u.mustChangePassword) return x + '<small class="nav-lock">修改密码后开放其他功能</small>';
    if (S.u.role === 'ADMIN') {
      x += '<small>账号与审批</small><button data-v="users">账号管理</button><button data-v="applications">申请审批 <span id="navPending" class="nav-badge">' + (S.pendingCount == null ? '…' : S.pendingCount) + '</span></button>';
      x += '<small>基础资料</small><button data-v="branches">支行</button><button data-v="outlets">网点</button><button data-v="ranges">地址段</button><button data-v="pool">IP 资源池</button>';
      x += '<small>资源运营</small><button data-v="overview">资源概览</button><button data-v="releases">释放记录</button><button data-v="exports">数据导出</button>';
      x += '<small>安全</small><button data-v="notifications">站内通知</button><button data-v="audit">审计日志</button>';
    } else {
      x += '<small>IP 申请</small><button data-v="apply">提交申请</button><button data-v="mine">我的申请</button><button data-v="myips">我的 IP</button><button data-v="notifications">站内通知</button>';
    }
    return x;
  }

  function render() {
    var buttons;
    var i;
    if (!S.u) {
      loginPage();
      return;
    }
    app.innerHTML = '<header><div class="brand"><i>IP</i> IP 资源管理平台 <em>第三阶段</em></div><div>' + e(S.u.realName) + ' · ' + role(S.u.role) + ' <button id="out">退出</button></div></header><div class="shell"><nav id="nav">' + nav() + '</nav><main id="main"></main></div>';
    document.getElementById('out').onclick = function () {
      q('POST', 'auth/logout', {}, function () {
        S = { u: null, c: null, v: 'home', branches: [] };
        render();
      });
    };
    buttons = document.getElementById('nav').getElementsByTagName('button');
    for (i = 0; i < buttons.length; i++) {
      buttons[i].onclick = function () {
        S.v = this.getAttribute('data-v');
        view();
      };
    }
    view();
  }

  function head(kicker, title, description) {
    return '<section class="head"><p>' + kicker + '</p><h2>' + title + '</h2><span>' + description + '</span></section>';
  }

  function view() {
    if (S.u.mustChangePassword && S.v !== 'home') S.v = 'home';
    var buttons = document.getElementById('nav').getElementsByTagName('button');
    var i;
    for (i = 0; i < buttons.length; i++) {
      buttons[i].className = buttons[i].getAttribute('data-v') === S.v ? 'active' : '';
    }
    if (S.v === 'home') home();
    else if (S.v === 'users') users();
    else if (S.v === 'applications') applications();
    else if (S.v === 'branches') branches();
    else if (S.v === 'outlets') outlets();
    else if (S.v === 'ranges') ranges();
    else if (S.v === 'pool') pool();
    else if (S.v === 'apply') apply();
    else if (S.v === 'mine') mine();
    else if (S.v === 'myips') myips();
    else if (S.v === 'overview') overview();
    else if (S.v === 'releases') releases();
    else if (S.v === 'exports') exports();
    else if (S.v === 'notifications') notifications();
    else audit();
  }

  function home() {
    var lock = S.u.mustChangePassword ? '<div class="notice bad"><b>请先修改密码</b><br>修改成功前，业务功能暂不开放。</div>' : '';
    document.getElementById('main').innerHTML = head('我的工作台', '概览与密码', '检查业务准备情况并管理当前登录密码。') + lock +
      '<div id="dashboard"><div class="stats"><div>当前角色<strong>' + role(S.u.role) + '</strong></div><div>账号状态<strong class="good">正常</strong></div><div>密码规则<strong>6–72 位</strong></div></div></div>' +
      '<section class="card"><h3>修改密码</h3><p>修改后，其他设备上的会话会立即失效。</p><div id="notice"></div><div class="grid"><div><label for="old">当前密码 <span class="required">*</span></label><input id="old" type="password" autocomplete="current-password"></div><div><label for="new">新密码 <span class="required">*</span></label><input id="new" type="password" autocomplete="new-password" placeholder="6–72 位，不能与当前密码相同"></div><div><label for="again">确认新密码 <span class="required">*</span></label><input id="again" type="password" autocomplete="new-password"></div></div><button id="save" class="primary">保存新密码</button></section>';
    if (S.u.role === 'ADMIN' && !S.u.mustChangePassword) q('GET', 'admin/dashboard', null, function (r) {
      S.pendingCount = r.pendingCount;
      if (document.getElementById('navPending')) document.getElementById('navPending').innerHTML = r.pendingCount;
      document.getElementById('dashboard').innerHTML = '<div class="stats five"><div>支行<strong>' + r.branchCount + '</strong></div><div>网点<strong>' + r.outletCount + '</strong></div><div>未绑定地址段<strong class="' + (r.unboundOutletCount ? 'warn' : 'good') + '">' + r.unboundOutletCount + '</strong></div><div>可用 IP<strong class="good">' + r.availableIpCount + '</strong></div><div>待审批<strong>' + r.pendingCount + '</strong></div></div><section class="card guide"><h3>基础资料配置向导</h3><div class="guide-steps"><button data-guide="branches"><b>① 创建支行</b><span>' + (r.branchCount ? '已创建 ' + r.branchCount + ' 个' : '尚未创建') + '</span></button><button data-guide="outlets"><b>② 创建网点</b><span>' + (r.outletCount ? '已创建 ' + r.outletCount + ' 个' : '尚未创建') + '</span></button><button data-guide="ranges"><b>③ 绑定地址段</b><span>' + (r.unboundOutletCount ? r.unboundOutletCount + ' 个网点待绑定' : '绑定检查完成') + '</span></button><button data-guide="pool"><b>④ 检查资源池</b><span>当前可用 ' + r.availableIpCount + ' 个</span></button></div></section>';
      var buttons = document.getElementById('dashboard').getElementsByTagName('button');
      var i;
      for (i = 0; i < buttons.length; i++) buttons[i].onclick = function () { S.v = this.getAttribute('data-guide'); view(); };
    });
    document.getElementById('save').onclick = function () {
      var button = this;
      if (!val('old') || !val('new') || !val('again')) { msg('请完整填写三个密码字段。', 1); return; }
      if (val('new') !== val('again')) {
        msg('两次输入的新密码不一致。', 1);
        return;
      }
      q('POST', 'auth/change-password', { currentPassword: val('old'), newPassword: val('new') }, function (r) {
        S.c = r.csrfToken;
        delete r.csrfToken;
        S.u = r;
        render();
        msg('密码已修改，完整功能已开放。', 0);
      }, null, button, '保存中…');
    };
  }

  function users() {
    document.getElementById('main').innerHTML = head('账号管理', '管理员与科技管理员', '初始管理员永久受保护，其他管理员拥有相同业务权限。') +
      '<section class="card"><h3>创建账号</h3><div id="notice"></div><div class="grid"><div><label for="newRole">账号角色 <span class="required">*</span></label><select id="newRole"><option value="TECH_ADMIN">科技管理员</option><option value="ADMIN">管理员</option></select></div><div><label for="name">登录账号 <span class="required">*</span></label><input id="name" placeholder="例如 zhangsan"></div><div><label for="real">姓名 <span class="required">*</span></label><input id="real" placeholder="例如 张三"></div><div><label for="pass">初始密码 <span class="required">*</span></label><input id="pass" type="password" placeholder="6–72 位；首次登录必须修改"></div></div><button id="add" class="primary">创建账号</button></section>' +
      '<section class="card"><h3>账号列表</h3><div id="table">加载中…</div></section>';
    document.getElementById('add').onclick = function () {
      var button = this;
      if (!val('name') || !val('real') || !val('pass')) { msg('请完整填写账号、姓名和初始密码。', 1); return; }
      q('POST', 'admin/users', {
        role: val('newRole'),
        username: val('name'),
        realName: val('real'),
        initialPassword: val('pass')
      }, users, null, button, '创建中…');
    };
    q('GET', 'admin/users', null, function (r) {
      var h = '<table><tr><th>账号</th><th>姓名</th><th>角色</th><th>状态</th><th>创建信息</th><th>操作</th></tr>';
      var i;
      var x;
      var actions;
      for (i = 0; i < r.items.length; i++) {
        x = r.items[i];
        actions = '—';
        if (x.initialAdmin !== 1 && x.id !== S.u.id && !x.deletedAt) {
          actions = '<button data-reset="' + x.id + '" data-username="' + e(x.username) + '" data-realname="' + e(x.realName) + '">重置密码</button>' +
            '<button data-active="' + x.id + '" data-next="' + (x.active === 1 ? 0 : 1) + '" data-username="' + e(x.username) + '" data-realname="' + e(x.realName) + '">' + (x.active === 1 ? '停用' : '启用') + '</button>' +
            '<button class="danger" data-delete="' + x.id + '" data-username="' + e(x.username) + '" data-realname="' + e(x.realName) + '">删除</button>';
        }
        h += '<tr><td><b>' + e(x.username) + '</b>' + (x.initialAdmin === 1 ? '<span class="badge">系统初始管理员</span>' : '') + '</td><td>' + e(x.realName) + '</td><td>' + role(x.role) + '</td><td>' + (x.deletedAt ? '已删除' : (x.active === 1 ? '启用' : '停用')) + (x.mustChangePassword === 1 ? '<br><small>待修改初始/重置密码</small>' : '') + '</td><td>' + e(x.creator || '系统') + '<br><small>' + e(formatDate(x.createdAt)) + '</small></td><td>' + actions + '</td></tr>';
      }
      document.getElementById('table').innerHTML = r.items.length ? h + '</table>' : empty('暂无账号', '请使用上方表单创建账号。');
      userActions();
    }, function () { var t=document.getElementById('table'); if(t)t.innerHTML=empty('账号列表加载失败','请根据页面提示处理后重试。'); });
  }

  function userActions() {
    var buttons = document.getElementById('table').getElementsByTagName('button');
    var i;
    for (i = 0; i < buttons.length; i++) {
      buttons[i].onclick = function () {
        var id = this.getAttribute('data-reset');
        if (id) {
          (function (button, userId) {
            systemDialog('重置账号密码', '账号：' + button.getAttribute('data-username') + '（' + button.getAttribute('data-realname') + '）。重置后现有会话立即失效，下次登录必须修改密码。', '<div class="grid"><div><label for="resetPassword">新密码 <span class="required">*</span></label><input id="resetPassword" type="password" autocomplete="new-password" placeholder="6–72 位"></div><div><label for="resetAgain">再次输入 <span class="required">*</span></label><input id="resetAgain" type="password" autocomplete="new-password"></div></div><label class="check"><input id="showReset" type="checkbox"> 显示密码</label>', '确认重置', function () { if (val('resetPassword').length < 6 || val('resetPassword').length > 72) { msg('新密码长度必须为 6–72 位。', 1); return false; } if (val('resetPassword') !== val('resetAgain')) { msg('两次输入的密码不一致。', 1); return false; } return { newPassword: val('resetPassword') }; }, function (root, data, confirm) { q('POST', 'admin/users/' + userId + '/reset-password', data, function () { closeDialog(root); users(); }, null, confirm, '重置中…'); });
            document.getElementById('showReset').onclick = function () { document.getElementById('resetPassword').type = this.checked ? 'text' : 'password'; document.getElementById('resetAgain').type = this.checked ? 'text' : 'password'; };
          }(this, id));
          return;
        }
        id = this.getAttribute('data-active');
        if (id) {
          (function (button, userId) { var enabling=button.getAttribute('data-next') === '1'; systemDialog(enabling ? '启用账号' : '停用账号', '账号：' + button.getAttribute('data-username') + '（' + button.getAttribute('data-realname') + '）。' + (enabling ? '启用后可重新登录。' : '停用后该账号所有会话立即失效，无法继续办理业务。'), '', enabling ? '确认启用' : '确认停用', null, function (root, data, confirm) { q('PUT', 'admin/users/' + userId + '/active', { active: enabling }, function () { closeDialog(root); users(); }, null, confirm); }); }(this, id));
          return;
        }
        id = this.getAttribute('data-delete');
        if (id) (function (button, userId) { systemDialog('删除账号', '账号：' + button.getAttribute('data-username') + '（' + button.getAttribute('data-realname') + '）。删除采用软删除：立即停用并撤销会话，历史申请与审计记录保留，用户名不能复用。', '', '确认删除', null, function (root, data, confirm) { q('DELETE', 'admin/users/' + userId, null, function () { closeDialog(root); users(); }, null, confirm, '删除中…'); }); }(this, id));
      };
    }
  }

  function getBranches(done) {
    q('GET', S.u.role === 'ADMIN' ? 'admin/branches' : 'branches', null, function (r) {
      S.branches = r.items;
      done();
    });
  }

  function opts(selected) {
    var h = '<option value="">请选择支行</option>';
    var i;
    for (i = 0; i < S.branches.length; i++) {
      if (S.branches[i].active === 1 || S.branches[i].id === selected) h += '<option value="' + S.branches[i].id + '"' + (S.branches[i].id === selected ? ' selected' : '') + '>' + e(S.branches[i].name) + '（' + e(S.branches[i].code) + '）</option>';
    }
    return h;
  }

  function closeDialog(root) {
    if (!root) return;
    if (root.parentNode) root.parentNode.removeChild(root);
    if (lastDialogFocus && lastDialogFocus.focus) lastDialogFocus.focus();
    lastDialogFocus = null;
  }

  function wireDialog(root, closeId, initialId) {
    var dialog = root.getElementsByClassName('dialog')[0];
    var focusable;
    document.getElementById(closeId).onclick = function () { closeDialog(root); };
    root.onkeydown = function (event) {
      var ev = event || window.event;
      var i;
      if (ev.keyCode === 27) { closeDialog(root); return; }
      if (ev.keyCode !== 9) return;
      focusable = dialog.querySelectorAll('button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),a[href]');
      if (!focusable.length) return;
      for (i = 0; i < focusable.length; i++) if (focusable[i] === document.activeElement) break;
      if (ev.shiftKey && i === 0) { focusable[focusable.length - 1].focus(); if (ev.preventDefault) ev.preventDefault(); ev.returnValue = false; }
      else if (!ev.shiftKey && i === focusable.length - 1) { focusable[0].focus(); if (ev.preventDefault) ev.preventDefault(); ev.returnValue = false; }
    };
    setTimeout(function () { var target = document.getElementById(initialId) || document.getElementById(closeId); if (target) target.focus(); }, 0);
  }

  function editDialog(title, fields, save, impact) {
    var old = document.getElementById('editModal');
    var root;
    if (old) closeDialog(old);
    lastDialogFocus = document.activeElement;
    root = document.createElement('div');
    root.id = 'editModal';
    root.innerHTML = '<div class="modal"><div class="dialog edit-dialog" role="dialog" aria-modal="true" aria-labelledby="editTitle"><div class="dialog-head"><div><h3 id="editTitle">' + e(title) + '</h3><p>保存后立即生效，并记录审计日志。</p></div><button id="closeEdit" aria-label="关闭弹窗">关闭</button></div>' + (impact ? '<div class="impact"><b>影响范围</b><p>' + e(impact) + '</p></div>' : '') + '<div id="editNotice"></div><div class="grid">' + fields + '</div><div class="dialog-actions"><span class="hint">请核对关联关系和启用状态。</span><button id="saveEdit" class="primary">保存修改</button></div></div></div>';
    document.body.appendChild(root);
    wireDialog(root, 'closeEdit', 'saveEdit');
    document.getElementById('saveEdit').onclick = function () { save(root, this); };
  }

  function systemDialog(title, summary, fields, confirmText, validate, action) {
    var old = document.getElementById('systemModal');
    var root;
    if (old) closeDialog(old);
    lastDialogFocus = document.activeElement;
    root = document.createElement('div');
    root.id = 'systemModal';
    root.innerHTML = '<div class="modal"><div class="dialog" role="dialog" aria-modal="true" aria-labelledby="systemTitle"><div class="dialog-head"><div><h3 id="systemTitle">' + e(title) + '</h3><p>' + e(summary) + '</p></div><button id="closeSystem" aria-label="关闭弹窗">关闭</button></div><div id="editNotice"></div>' + (fields || '') + '<div class="dialog-actions"><button id="cancelSystem" class="secondary">取消</button><button id="confirmSystem" class="primary">' + e(confirmText) + '</button></div></div></div>';
    document.body.appendChild(root);
    wireDialog(root, 'closeSystem', 'confirmSystem');
    document.getElementById('cancelSystem').onclick = function () { closeDialog(root); };
    document.getElementById('confirmSystem').onclick = function () { var data = validate ? validate() : {}; if (data !== false) action(root, data, this); };
    return root;
  }

  function editValue(id) {
    return document.getElementById(id).value;
  }

  function bindEditButtons(container, callback) {
    var buttons = document.getElementById(container).getElementsByTagName('button');
    var i;
    for (i = 0; i < buttons.length; i++) buttons[i].onclick = function () { callback(this); };
  }

  function branches() {
    document.getElementById('main').innerHTML = head('基础资料', '支行', '一个支行可以维护多个网点；保存后仍可编辑。') +
      '<section class="card"><h3>新增支行</h3><div id="notice"></div><div class="grid"><div><label for="code">支行编号 <span class="required">*</span></label><input id="code" placeholder="例如 CZ001"></div><div><label for="name">支行名称 <span class="required">*</span></label><input id="name" placeholder="例如 常州分行营业部"></div></div><button id="add" class="primary">保存</button></section>' +
      '<section class="card"><h3>支行列表</h3><div id="table">加载中…</div></section>';
    document.getElementById('add').onclick = function () { if(!val('code')||!val('name')){msg('支行编号和名称均为必填项。',1);return;}q('POST', 'admin/branches', { code: val('code'), name: val('name') }, branches, null, this, '保存中…'); };
    q('GET', 'admin/branches', null, function (r) {
      var h = '<table><tr><th>编号</th><th>名称</th><th>网点数</th><th>状态</th><th>操作</th></tr>';
      var i;
      var x;
      for (i = 0; i < r.items.length; i++) { x = r.items[i]; h += '<tr><td>' + e(x.code) + '</td><td>' + e(x.name) + '</td><td>' + x.outletCount + '</td><td><span class="status ' + (x.active === 1 ? 'status-approved' : 'status-rejected') + '">' + (x.active === 1 ? '启用' : '停用') + '</span></td><td><button data-edit="' + i + '">编辑</button><button class="danger" data-delete="' + i + '">删除</button></td></tr>'; }
      document.getElementById('table').innerHTML = r.items.length ? h + '</table>' : empty('暂无支行', '请先使用上方表单创建支行。');
      bindEditButtons('table', function (button) {
        var deleting = button.getAttribute('data-delete');
        var item = r.items[Number(deleting !== null ? deleting : button.getAttribute('data-edit'))];
        if (deleting !== null) {
          systemDialog('删除支行', '将删除“' + item.name + '（' + item.code + '）”及其全部下属网络资料。', '<div class="delete-impact"><b>本次影响</b><span>网点 ' + item.outletCount + ' 个</span><span>地址段 ' + item.rangeCount + ' 个</span><span>IP 地址 ' + item.poolCount + ' 个</span><span>待审批申请 ' + item.pendingCount + ' 条将自动驳回</span><span>历史分配 ' + item.assignmentCount + ' 条将保留</span></div><div class="notice bad">删除后不再出现在业务列表中；历史申请、分配和审计记录仍会保留。</div>', '确认删除支行', null, function (root, data, confirmButton) { q('DELETE', 'admin/branches/' + item.id, null, function (result) { closeDialog(root); reloadMessage(branches, '支行已删除，同时处理 ' + result.outletCount + ' 个网点、' + result.rangeCount + ' 个地址段。'); }, null, confirmButton, '删除中…'); });
          return;
        }
        editDialog('编辑支行', '<div><label for="editCode">支行编号 <span class="required">*</span></label><input id="editCode" value="' + e(item.code) + '"></div><div><label for="editName">支行名称 <span class="required">*</span></label><input id="editName" value="' + e(item.name) + '"></div><div><label for="editActive">状态 <span class="required">*</span></label><select id="editActive"><option value="1"' + (item.active === 1 ? ' selected' : '') + '>启用</option><option value="0"' + (item.active !== 1 ? ' selected' : '') + '>停用</option></select></div>', function (root, button) {
          q('PUT', 'admin/branches/' + item.id, { code: editValue('editCode'), name: editValue('editName'), active: editValue('editActive') === '1' }, function () { closeDialog(root); branches(); }, null, button, '保存中…');
        }, '关联 ' + item.outletCount + ' 个网点、' + item.rangeCount + ' 个地址段、' + item.assignmentCount + ' 条有效分配。存在有效分配时不允许停用。');
      });
    });
  }

  function outlets() {
    getBranches(function () {
      document.getElementById('main').innerHTML = head('基础资料', '网点', '一个支行可对应多个网点；每个网点最多绑定一个地址段。') +
        '<section class="card"><h3>新增网点</h3><div id="notice"></div><div class="grid"><div><label for="branchId">所属支行 <span class="required">*</span></label><select id="branchId">' + opts() + '</select></div><div><label for="code">网点编号 <span class="required">*</span></label><input id="code" placeholder="例如 OUT001"></div><div><label for="name">网点名称 <span class="required">*</span></label><input id="name" placeholder="例如 新北支行营业室"></div></div><button id="add" class="primary">保存</button></section>' + bulkCard('OUTLET', '网点') +
        '<section class="card"><h3>网点列表</h3><div id="table">加载中…</div></section>';
      document.getElementById('add').onclick = function () { if(!val('branchId')||!val('code')||!val('name')){msg('所属支行、网点编号和名称均为必填项。',1);return;}q('POST', 'admin/outlets', { branchId: Number(val('branchId')), code: val('code'), name: val('name') }, outlets, null, this, '保存中…'); };
      bindBulk('OUTLET', outlets);
      q('GET', 'admin/outlets', null, function (r) {
        var h = '<table><tr><th>所属支行</th><th>网点编号</th><th>网点名称</th><th>绑定地址段</th><th>状态</th><th>操作</th></tr>';
        var i;
        var x;
        for (i = 0; i < r.items.length; i++) { x = r.items[i]; h += '<tr><td>' + e(x.branchName) + '</td><td>' + e(x.code) + '</td><td>' + e(x.name) + '</td><td>' + (x.cidr ? e(x.cidr) : '<span class="status status-pending">待绑定</span>') + '</td><td><span class="status ' + (x.active === 1 ? 'status-approved' : 'status-rejected') + '">' + (x.active === 1 ? '启用' : '停用') + '</span></td><td><button data-edit="' + i + '">编辑</button><button class="danger" data-delete="' + i + '">删除</button></td></tr>'; }
        document.getElementById('table').innerHTML = r.items.length ? h + '</table>' : empty('暂无网点', '请先创建支行，再使用上方表单创建网点。');
        bindEditButtons('table', function (button) {
          var deleting = button.getAttribute('data-delete');
          var item = r.items[Number(deleting !== null ? deleting : button.getAttribute('data-edit'))];
          if (deleting !== null) {
            systemDialog('删除网点', '将删除“' + item.branchName + ' / ' + item.name + '（' + item.code + '）”及其下属网络资料。', '<div class="delete-impact"><b>本次影响</b><span>地址段 ' + (item.rangeId ? 1 : 0) + ' 个</span><span>IP 地址 ' + item.poolCount + ' 个</span><span>待审批申请 ' + item.pendingCount + ' 条将自动驳回</span><span>历史分配 ' + item.assignmentCount + ' 条将保留</span></div><div class="notice bad">删除后不再出现在业务列表中；历史申请、分配和审计记录仍会保留。</div>', '确认删除网点', null, function (root, data, confirmButton) { q('DELETE', 'admin/outlets/' + item.id, null, function (result) { closeDialog(root); reloadMessage(outlets, '网点已删除，同时处理 ' + result.rangeCount + ' 个地址段。'); }, null, confirmButton, '删除中…'); });
            return;
          }
          editDialog('编辑网点', '<div><label for="editBranch">所属支行 <span class="required">*</span></label><select id="editBranch">' + opts(item.branchId) + '</select></div><div><label for="editCode">网点编号 <span class="required">*</span></label><input id="editCode" value="' + e(item.code) + '"></div><div><label for="editName">网点名称 <span class="required">*</span></label><input id="editName" value="' + e(item.name) + '"></div><div><label for="editActive">状态 <span class="required">*</span></label><select id="editActive"><option value="1"' + (item.active === 1 ? ' selected' : '') + '>启用</option><option value="0"' + (item.active !== 1 ? ' selected' : '') + '>停用</option></select></div>', function (root, button) {
            q('PUT', 'admin/outlets/' + item.id, { branchId: Number(editValue('editBranch')), code: editValue('editCode'), name: editValue('editName'), active: editValue('editActive') === '1' }, function () { closeDialog(root); outlets(); }, null, button, '保存中…');
          }, (item.cidr ? '已绑定地址段 ' + item.cidr + '；' : '尚未绑定地址段；') + '现有有效分配 ' + item.assignmentCount + ' 条。存在有效分配时不允许变更支行或停用。');
        });
      });
    });
  }

  function outletOptions(items, selected, onlyUnbound) {
    var h = '<option value="">请选择网点</option>';
    var i;
    var x;
    for (i = 0; i < items.length; i++) { x = items[i]; if ((x.active === 1 && (!onlyUnbound || !x.rangeId)) || x.id === selected) h += '<option value="' + x.id + '"' + (x.id === selected ? ' selected' : '') + '>' + e(x.branchName) + ' / ' + e(x.name) + '（' + e(x.code) + '）</option>'; }
    return h;
  }

  function rangeFields(prefix, outletsList, item, onlyUnbound, defaults) {
    item = item || {};
    if (!item.id && defaults) { item.dnsPrimary = defaults.dnsPrimary || ''; item.dnsSecondary = defaults.dnsSecondary || ''; }
    return '<div><label for="' + prefix + 'Outlet">绑定网点 <span class="required">*</span></label><select id="' + prefix + 'Outlet">' + outletOptions(outletsList, item.outletId, onlyUnbound) + '</select></div><div><label for="' + prefix + 'Cidr">CIDR <span class="required">*</span></label><input id="' + prefix + 'Cidr" value="' + e(item.cidr || '') + '" placeholder="例如 10.1.0.0/24"></div><div><label for="' + prefix + 'Mask">子网掩码</label><input id="' + prefix + 'Mask" value="' + e(item.subnetMask || '') + '" placeholder="留空自动推导"></div><div><label for="' + prefix + 'Gateway">网关 <span class="required">*</span></label><input id="' + prefix + 'Gateway" value="' + e(item.gateway || '') + '" placeholder="例如 10.1.0.1"></div><div><label for="' + prefix + 'Dns1">主 DNS <span class="required">*</span></label><input id="' + prefix + 'Dns1" value="' + e(item.dnsPrimary || '') + '" placeholder="例如 114.114.114.114"></div><div><label for="' + prefix + 'Dns2">备 DNS</label><input id="' + prefix + 'Dns2" value="' + e(item.dnsSecondary || '') + '" placeholder="可留空"></div>' + (prefix === 'edit' ? '<div><label for="editActive">状态 <span class="required">*</span></label><select id="editActive"><option value="1"' + (item.active === 1 ? ' selected' : '') + '>启用</option><option value="0"' + (item.active !== 1 ? ' selected' : '') + '>停用</option></select></div>' : '');
  }

  function rangeBody(prefix, includeActive) {
    var body = { outletId: Number(editValue(prefix + 'Outlet')), cidr: editValue(prefix + 'Cidr'), subnetMask: editValue(prefix + 'Mask'), gateway: editValue(prefix + 'Gateway'), dnsPrimary: editValue(prefix + 'Dns1'), dnsSecondary: editValue(prefix + 'Dns2') };
    if (includeActive) body.active = editValue('editActive') === '1';
    return body;
  }

  function ranges() {
    q('GET', 'admin/outlets', null, function (outletResponse) {
      q('GET', 'admin/network-defaults', null, function (defaults) {
        q('GET', 'admin/ip-ranges', null, function (rangeResponse) {
        var outletsList = outletResponse.items;
        var items = rangeResponse.items;
        var h = '<table><tr><th>支行 / 网点</th><th>CIDR</th><th>网关</th><th>地址池</th><th>状态</th><th>操作</th></tr>';
        var i;
        var x;
        document.getElementById('main').innerHTML = head('基础资料', '地址段', '每个网点只能绑定一个地址段；绑定后自动生成整个可用主机地址池。') +
          '<section class="card dns-default-card"><div class="card-title-row"><div><h3>默认 DNS</h3><p>新建地址段和批量导入中的空 DNS 将自动采用这里的设置，不会改写已有地址段。</p></div><span class="config-tag">系统参数</span></div><div class="dns-default-row"><div><label for="defaultDns1">默认主 DNS <span class="required">*</span></label><input id="defaultDns1" value="' + e(defaults.dnsPrimary || '') + '" placeholder="例如 114.114.114.114"></div><div><label for="defaultDns2">默认备 DNS</label><input id="defaultDns2" value="' + e(defaults.dnsSecondary || '') + '" placeholder="可留空"></div><button id="saveDefaultDns" class="secondary">保存默认值</button></div></section>' +
          '<section class="card"><h3>新增并绑定地址段</h3><div id="notice"></div><p class="hint">支持 /16–/30。网络地址和广播地址不入池，网关自动设为系统保留；DNS 已自动带入默认值，可按地址段调整。</p><div class="grid">' + rangeFields('new', outletsList, null, true, defaults) + '</div><button id="add" class="primary">保存并生成地址池</button></section>' + bulkCard('IP_RANGE', '地址段') +
          '<section class="card"><h3>地址段列表</h3><div id="table"></div></section>';
        for (i = 0; i < items.length; i++) { x = items[i]; h += '<tr><td>' + e(x.branchName) + '<br>' + (x.outletName ? e(x.outletName) + '（' + e(x.outletCode) + '）' : '<span class="status status-pending">旧数据待绑定</span>') + '</td><td><b>' + e(x.cidr) + '</b><br>' + e(x.subnetMask) + '</td><td>' + e(x.gateway) + '</td><td>共 ' + x.poolTotal + '<br><span class="good">可用 ' + x.availableTotal + '</span></td><td><span class="status ' + (x.active === 1 ? 'status-approved' : 'status-rejected') + '">' + (x.active === 1 ? '启用' : '停用') + '</span></td><td><button data-edit="' + i + '">编辑</button><button data-pool="' + x.id + '"' + (!x.outletId ? ' disabled' : '') + '>管理 IP</button></td></tr>'; }
        document.getElementById('table').innerHTML = items.length ? h + '</table>' : empty('暂无地址段', '请先创建网点，再使用上方表单绑定地址段。');
        document.getElementById('saveDefaultDns').onclick = function () { if(!val('defaultDns1')){msg('默认主 DNS 为必填项。',1);return;}q('PUT','admin/network-defaults',{dnsPrimary:val('defaultDns1'),dnsSecondary:val('defaultDns2')},function(){defaults.dnsPrimary=val('defaultDns1');defaults.dnsSecondary=val('defaultDns2');document.getElementById('newDns1').value=defaults.dnsPrimary;document.getElementById('newDns2').value=defaults.dnsSecondary;msg('默认 DNS 已保存，后续新建和导入地址段将自动使用。',0);},null,this,'保存中…'); };
        document.getElementById('add').onclick = function () { if(!val('newOutlet')||!val('newCidr')||!val('newGateway')||!val('newDns1')){msg('绑定网点、CIDR、网关和主 DNS 均为必填项。',1);return;}q('POST', 'admin/ip-ranges', rangeBody('new', false), ranges, null, this, '生成地址池中…'); };
        bindBulk('IP_RANGE', ranges);
        bindEditButtons('table', function (button) {
          var poolId = button.getAttribute('data-pool');
          var item;
          if (poolId) { S.poolRangeId = Number(poolId); S.v = 'pool'; view(); return; }
          item = items[Number(button.getAttribute('data-edit'))];
          editDialog('编辑并绑定地址段', rangeFields('edit', outletsList, item, true), function (root, button) { q('PUT', 'admin/ip-ranges/' + item.id, rangeBody('edit', true), function () { closeDialog(root); ranges(); }, null, button, '保存并检查中…'); }, '当前地址池共 ' + item.poolTotal + ' 个 IP，已有 ' + item.assignmentCount + ' 条有效分配。修改 CIDR、网关或绑定关系可能重建/迁移地址池；存在有效分配时禁止破坏性修改。');
        });
        });
      });
    });
  }

  function pool() {
    q('GET', 'admin/ip-ranges', null, function (r) {
      var bound = [];
      var i;
      var selected;
      var options = '';
      for (i = 0; i < r.items.length; i++) if (r.items[i].outletId) bound.push(r.items[i]);
      if (!bound.length) { document.getElementById('main').innerHTML = head('基础资料', 'IP 资源池', '请先为网点绑定地址段。') + '<section class="card"><div class="notice bad">当前没有已绑定网点的地址段。请先到“地址段”页面完成绑定。</div></section>'; return; }
      selected = S.poolRangeId || bound[0].id;
      for (i = 0; i < bound.length; i++) options += '<option value="' + bound[i].id + '"' + (bound[i].id === selected ? ' selected' : '') + '>' + e(bound[i].branchName) + ' / ' + e(bound[i].outletName) + ' / ' + e(bound[i].cidr) + '</option>';
      document.getElementById('main').innerHTML = head('基础资料', 'IP 资源池', '地址段绑定后默认生成全部主机地址；使用单一状态维护每个 IP。') +
        '<section class="card pool-toolbar"><div id="notice"></div><div class="grid"><div><label for="poolRange">网点 / 地址段 <span class="required">*</span></label><select id="poolRange">' + options + '</select></div><div><label for="poolSearch">搜索 IP</label><input id="poolSearch" placeholder="例如 10.1.0.25"></div></div><button id="poolQuery" class="secondary">查询</button></section>' + bulkCard('IP_POOL', 'IP 状态', '批量导入') +
        '<section class="card"><div class="card-title-row"><div><h3>地址状态</h3><p>可用、停用、人工保留互斥；系统保留与已分配为只读。</p></div><span id="poolCount" class="badge">加载中</span></div><div class="batch-bar"><span id="selectedCount">已选择 0 个</span><button id="batchAvailable" class="secondary">批量设为可用</button><button id="batchDisabled" class="secondary">批量停用</button><button id="batchReserved" class="secondary">批量人工保留</button></div><div id="table">加载中…</div><div id="poolPager" class="pager"></div></section>';
      bindBulk('IP_POOL', pool);
      function loadPool(page) {
        var rangeId = Number(val('poolRange'));
        var keyword = val('poolSearch');
        S.poolRangeId = rangeId;
        q('GET', 'admin/ip-pool?rangeId=' + rangeId + '&page=' + page + '&q=' + encodeURIComponent(keyword), null, function (response) {
          var h = '<table><tr><th><span class="visually-hidden">选择</span></th><th>IP 地址</th><th>当前状态</th><th>说明</th><th>操作</th></tr>';
          var j;
          var x;
          for (j = 0; j < response.items.length; j++) { x = response.items[j]; h += '<tr><td><input class="pool-check" type="checkbox" value="' + x.id + '" aria-label="选择 ' + e(x.ipAddress) + '"' + (x.editable !== 1 ? ' disabled' : '') + '></td><td><b>' + e(x.ipAddress) + '</b></td><td><span class="status status-' + e(x.state.toLowerCase()) + '">' + e(ipStateText(x.state)) + '</span></td><td>' + e(x.reservationReason || (x.state === 'ASSIGNED' ? '须通过合规回收流程释放' : '—')) + '</td><td><button data-index="' + j + '"' + (x.editable !== 1 ? ' disabled' : '') + '>编辑状态</button></td></tr>'; }
          document.getElementById('table').innerHTML = response.items.length ? h + '</table>' : empty('未找到符合条件的 IP', '请调整搜索条件后重试。');
          document.getElementById('poolCount').innerHTML = '共 ' + response.total + ' 个地址';
          document.getElementById('poolPager').innerHTML = '<button id="poolPrev" class="secondary"' + (page <= 1 ? ' disabled' : '') + '>上一页</button><span>第 ' + page + ' 页</span><button id="poolNext" class="secondary"' + (page * response.pageSize >= response.total ? ' disabled' : '') + '>下一页</button>';
          document.getElementById('poolPrev').onclick = function () { if (page > 1) loadPool(page - 1); };
          document.getElementById('poolNext').onclick = function () { if (page * response.pageSize < response.total) loadPool(page + 1); };
          bindEditButtons('table', function (button) {
            var item = response.items[Number(button.getAttribute('data-index'))];
            editDialog('编辑 ' + item.ipAddress, '<div><label for="editState">IP 状态 <span class="required">*</span></label><select id="editState"><option value="AVAILABLE"' + (item.state === 'AVAILABLE' ? ' selected' : '') + '>可用（允许审批分配）</option><option value="DISABLED"' + (item.state === 'DISABLED' ? ' selected' : '') + '>停用（暂不可用）</option><option value="MANUAL_RESERVED"' + (item.state === 'MANUAL_RESERVED' ? ' selected' : '') + '>人工保留（指定用途）</option></select></div><div><label for="editReason">原因/说明 <span class="required" id="reasonRequired">' + (item.state === 'MANUAL_RESERVED' ? '*' : '') + '</span></label><input id="editReason" value="' + e(item.reservationReason || '') + '" maxlength="200" placeholder="人工保留时必填，最多 200 字"></div>', function (root, button) { if(editValue('editState')==='MANUAL_RESERVED'&&!editValue('editReason')){msg('人工保留必须填写保留原因。',1);return;}q('PUT', 'admin/ip-pool/' + item.id, { state: editValue('editState'), reservationReason: editValue('editReason') }, function () { closeDialog(root); loadPool(page); }, null, button, '保存中…'); }, '状态为单一互斥值；可用表示允许审批分配，停用表示暂不可用，人工保留表示为指定用途预留。');
          });
          function checkedIds() { var inputs=document.getElementById('table').getElementsByTagName('input');var ids=[];var k;for(k=0;k<inputs.length;k++)if(inputs[k].className==='pool-check'&&inputs[k].checked)ids.push(Number(inputs[k].value));return ids; }
          function updateSelected(){document.getElementById('selectedCount').innerHTML='已选择 '+checkedIds().length+' 个';}
          var checks=document.getElementById('table').getElementsByTagName('input');
          for(j=0;j<checks.length;j++)checks[j].onclick=updateSelected;
          function batchState(state) { var ids=checkedIds(); if(!ids.length){msg('请先勾选至少一个普通、未分配 IP。',1);return;} var label=ipStateText(state); systemDialog('批量设为' + label, '将对已选择的 ' + ids.length + ' 个 IP 原子执行：任一 IP 不可修改时整批不写入。', '<div><label for="batchReason">原因/说明' + (state==='MANUAL_RESERVED'?' <span class="required">*</span>':'') + '</label><input id="batchReason" maxlength="200" placeholder="' + (state==='MANUAL_RESERVED'?'请填写指定用途':'可选') + '"></div>', '确认批量修改', function(){if(state==='MANUAL_RESERVED'&&!val('batchReason')){msg('人工保留必须填写保留原因。',1);return false;}return{ids:ids,state:state,reservationReason:val('batchReason')};}, function(root,data,button){q('PUT','admin/ip-pool/status',data,function(done){closeDialog(root);msg('已更新 '+done.updated+' 个 IP。',0);loadPool(page);},null,button,'批量处理中…');}); }
          document.getElementById('batchAvailable').onclick=function(){batchState('AVAILABLE');};
          document.getElementById('batchDisabled').onclick=function(){batchState('DISABLED');};
          document.getElementById('batchReserved').onclick=function(){batchState('MANUAL_RESERVED');};
        });
      }
      document.getElementById('poolRange').onchange = function () { loadPool(1); };
      document.getElementById('poolQuery').onclick = function () { loadPool(1); };
      document.getElementById('poolSearch').onkeydown = function (event) { if ((event || window.event).keyCode === 13) loadPool(1); };
      loadPool(1);
    });
  }

  function bulkCard(target, title, verb) {
    if (!target) return '';
    return '<section class="card import-card"><div class="card-title-row"><div><h3>' + (verb || '批量新增') + title + '</h3><p>这是单条维护的补充能力。系统先预览校验，确认后才会整批写入；任一行错误则整批不写入。</p></div><div class="template-links"><a id="csvTemplate">CSV 模板</a><a id="xlsxTemplate">XLSX 模板</a></div></div><div class="inline-upload"><label class="file-label" for="bulkFile">选择文件 <span class="required">*</span></label><input id="bulkFile" type="file" accept=".csv,.xlsx"><span id="bulkFileName">尚未选择文件</span><button id="bulkUpload" class="secondary">上传并预览</button></div><div id="bulkResult"></div><details class="import-history"><summary>最近导入记录</summary><div id="bulkHistory">加载中…</div></details></section>';
  }

  function form(url, title, desc, fields, data, thead, row, importTarget, reload) {
    document.getElementById('main').innerHTML = head('基础资料', title, desc) +
      '<section class="card"><h3>单条新增' + title + '</h3><div id="notice"></div><div class="grid">' + fields + '</div><button id="add" class="primary">保存</button></section>' +
      bulkCard(importTarget, title) +
      '<section class="card"><h3>' + title + '列表</h3><div id="table">加载中…</div></section>';
    document.getElementById('add').onclick = function () {
      q('POST', 'admin/' + url, data(), reload);
    };
    if (importTarget) bindBulk(importTarget, reload);
    q('GET', 'admin/' + url, null, function (r) {
      var h = '<table>' + thead;
      var i;
      for (i = 0; i < r.items.length; i++) h += row(r.items[i]);
      document.getElementById('table').innerHTML = h + '</table>';
    });
  }

  function bindBulk(target, reload) {
    document.getElementById('csvTemplate').href = '/api/v1/admin/import-templates/' + target + '.csv';
    document.getElementById('xlsxTemplate').href = '/api/v1/admin/import-templates/' + target + '.xlsx';
    document.getElementById('bulkFile').onchange = function () { document.getElementById('bulkFileName').innerHTML = this.files[0] ? e(this.files[0].name) : '尚未选择文件'; };
    document.getElementById('bulkUpload').onclick = function () {
      var file = document.getElementById('bulkFile').files[0];
      if (!file) {
        msg('请选择 UTF-8 CSV 或无宏 XLSX 文件。', 1);
        return;
      }
      upload('admin/imports/' + target, file, function (r) {
        r.fileName = file.name;
        showImportPreview(r, reload, target);
      }, null, this);
    };
    q('GET', 'admin/imports/recent', null, function (r) { var h='';var i;var x;for(i=0;i<r.items.length;i++){x=r.items[i];if(x.targetType===target)h+='<div class="history-row"><span>'+e(formatDate(x.createdAt))+'</span><b>'+e(x.fileName)+'</b><span>'+e(x.status)+' · '+x.validRows+' 成功 / '+x.errorRows+' 失败</span>'+(x.errorRows?'<a href="/api/v1/admin/imports/'+x.id+'/error-report.csv">下载错误报告</a>':'')+'</div>';}document.getElementById('bulkHistory').innerHTML=h||empty('暂无导入记录','完成一次预览后会在这里留下本地审计记录。'); }, function(){var h=document.getElementById('bulkHistory');if(h)h.innerHTML=empty('导入记录加载失败','请稍后重试。');});
  }

  function showImportPreview(r, reload, target) {
    var h = '<p><b>文件：</b>' + e(r.fileName || '已上传文件') + '</p><div class="import-summary"><span>总行数 <b>' + r.totalRows + '</b></span><span>预览成功 <b>' + r.validRows + '</b></span><span>预览失败 <b>' + r.errorRows + '</b></span></div>';
    var i;
    if (r.errors && r.errors.length) {
      h += '<table><tr><th>行号</th><th>字段</th><th>错误代码</th><th>说明</th></tr>';
      for (i = 0; i < r.errors.length; i++) {
        h += '<tr><td>' + r.errors[i].rowNumber + '</td><td>' + e(r.errors[i].fieldName || '—') + '</td><td>' + e(r.errors[i].errorCode) + '</td><td>' + e(r.errors[i].errorMessage) + '</td></tr>';
      }
      h += '</table><p class="hint">存在错误，本批次未写入任何业务记录。</p><a class="button-link" href="/api/v1/admin/imports/' + r.id + '/error-report.csv">下载错误报告 CSV</a>';
    } else {
      h += '<div class="confirm-bar"><span>全部校验通过。确认时将重新校验文件摘要和当前数据库状态。</span><button id="bulkConfirm" class="primary">确认整批导入</button><button id="bulkCancel" class="secondary">取消</button></div>';
    }
    document.getElementById('bulkResult').innerHTML = h;
    if (!r.errors || !r.errors.length) {
      document.getElementById('bulkConfirm').onclick = function () {
        q('POST', 'admin/imports/' + r.id + '/confirm', {}, function (done) {
          reloadMessage(reload, '批量导入完成，共写入 ' + done.insertedRows + ' 条记录。');
        }, null, this, '导入中…');
      };
      document.getElementById('bulkCancel').onclick = function () {
        q('DELETE', 'admin/imports/' + r.id, null, function () {
          document.getElementById('bulkResult').innerHTML = '<p class="hint">本次预览已取消，临时文件已清理。</p>';
        }, null, this, '取消中…');
      };
    }
  }

  function apply() {
    getBranches(function () {
      q('GET', 'outlets', null, function (outletResponse) {
        var outlets = outletResponse.items;
        document.getElementById('main').innerHTML = head('IP 申请', '提交申请', '申请提交后进入待审批状态，只能查看本人申请和分配结果。') +
          '<section class="card"><h3>申请信息</h3><div id="notice"></div><div class="grid"><div><label for="applicantName">姓名 <span class="required">*</span></label><input id="applicantName" value="' + e(S.u.realName) + '"></div><div><label for="authId">统一认证号 <span class="required">*</span></label><input id="authId" placeholder="例如 900000001"></div><div><label for="applicationBranch">支行 <span class="required">*</span></label><select id="applicationBranch">' + opts() + '</select></div><div><label for="applicationOutlet">网点/机关部室 <span class="required">*</span></label><select id="applicationOutlet"><option value="">请先选择支行</option></select></div><div><label for="assetType">资产类型 <span class="required">*</span></label><select id="assetType"><option value="TECH">科技资产</option><option value="NON_TECH">非科技资产</option></select></div><div class="tech-field"><label for="assetCode">资源编码 <span class="required">*</span></label><input id="assetCode" placeholder="例如 PC-CZ-001"></div><div class="tech-field"><label for="deviceType">设备类型 <span class="required">*</span></label><select id="deviceType"><option value="PC">PC</option><option value="OTHER">其他设备</option></select></div></div><button id="submitApplication" class="primary">提交申请</button></section>';
        function fillOutlets() {
          var branch = Number(val('applicationBranch'));
          var h = '<option value="">请选择网点/机关部室</option>';
          var i;
          for (i = 0; i < outlets.length; i++) {
            if (outlets[i].branchId === branch) h += '<option value="' + outlets[i].id + '">' + e(outlets[i].name) + '（' + e(outlets[i].code) + '）</option>';
          }
          document.getElementById('applicationOutlet').innerHTML = h;
        }
        function toggleAssetFields() {
          var fields = document.getElementsByClassName('tech-field');
          var visible = val('assetType') === 'TECH';
          var i;
          for (i = 0; i < fields.length; i++) fields[i].style.display = visible ? 'block' : 'none';
        }
        document.getElementById('applicationBranch').onchange = fillOutlets;
        document.getElementById('assetType').onchange = toggleAssetFields;
        toggleAssetFields();
        document.getElementById('submitApplication').onclick = function () {
          var tech = val('assetType') === 'TECH';
          var button = this;
          if(!val('applicantName')||!val('authId')||!val('applicationBranch')||!val('applicationOutlet')||(tech&&!val('assetCode'))){msg('请完整填写所有必填项。',1);return;}
          q('POST', 'applications', {
            name: val('applicantName'),
            unifiedAuthId: val('authId'),
            branchId: Number(val('applicationBranch')),
            outletId: Number(val('applicationOutlet')),
            assetType: val('assetType'),
            assetCode: tech ? val('assetCode') : null,
            deviceType: tech ? val('deviceType') : null
          }, function () {
            S.v = 'mine';
            reloadMessage(view, '申请已提交，当前状态为待审批。');
          }, null, button, '提交中…');
        };
      });
    });
  }

  function mine() {
    document.getElementById('main').innerHTML = head('IP 申请', '我的申请', '查看本人申请状态、驳回原因和网络分配结果。') +
      '<section class="card"><div id="table">加载中…</div></section>';
    q('GET', 'applications', null, function (r) {
      var h = '<div id="notice"></div><table><tr><th>提交时间</th><th>支行/网点</th><th>资产</th><th>进度</th><th>处理结果</th></tr>';
      var i;
      var x;
      var result;
      for (i = 0; i < r.items.length; i++) {
        x = r.items[i];
        result = '等待管理员审批';
        if (x.status === 'REJECTED') result = '<b>驳回原因：</b>' + e(x.rejectionReason) + '<br><small>请根据原因修正信息后重新提交申请。</small>';
        if (x.status === 'APPROVED') { var network='IP '+x.ipAddress+'\n子网掩码 '+x.subnetMask+'\n网关 '+x.gateway+'\nDNS '+x.dnsPrimary+(x.dnsSecondary?' / '+x.dnsSecondary:''); result = '<b>' + e(x.ipAddress) + '</b><br>掩码 ' + e(x.subnetMask) + '　网关 ' + e(x.gateway) + '<br>DNS ' + e(x.dnsPrimary) + (x.dnsSecondary ? ' / ' + e(x.dnsSecondary) : '') + '<br><button class="secondary" data-copy="' + e(network) + '">复制网络参数</button>'; }
        h += '<tr><td>' + e(formatDate(x.createdAt)) + '</td><td>' + e(x.branchName) + '<br>' + e(x.outletName) + '</td><td>' + (x.assetType === 'TECH' ? '科技资产<br>' + e(x.assetCode) : '非科技资产') + '</td><td><span class="status status-' + e(x.status.toLowerCase()) + '">' + statusText(x.status) + '</span><br><small>' + (x.status==='PENDING'?'已提交 → 等待审批':x.status==='APPROVED'?'已提交 → 已分配':'已提交 → 已驳回') + '</small></td><td>' + result + '</td></tr>';
      }
      document.getElementById('table').innerHTML = r.items.length ? h + '</table>' : empty('暂无申请', '请先到“提交申请”页面提交 IP 申请。');
      var copies=document.getElementById('table').getElementsByTagName('button');var j;for(j=0;j<copies.length;j++)copies[j].onclick=function(){copyText(this.getAttribute('data-copy'));};
    }, function(){var t=document.getElementById('table');if(t)t.innerHTML=empty('申请列表加载失败','请根据页面提示处理后重试。');});
  }

  function applications() {
    S.approval = S.approval || { branch: '', outlet: '', q: '' };
    q('GET', 'admin/outlets', null, function (outletResponse) {
      getBranches(function () {
        var branchOptions=opts(Number(S.approval.branch));var outletOptionsHtml='<option value="">全部网点</option>';var i;var o;
        for(i=0;i<outletResponse.items.length;i++){o=outletResponse.items[i];if(!S.approval.branch||String(o.branchId)===String(S.approval.branch))outletOptionsHtml+='<option value="'+o.id+'"'+(String(o.id)===String(S.approval.outlet)?' selected':'')+'>'+e(o.branchName)+' / '+e(o.name)+'</option>';}
        document.getElementById('main').innerHTML = head('审批管理', 'IP 申请审批', '默认按最早提交优先；筛选后手动选择申请网点的可用 IP。') +
          '<section class="card filter-card"><div id="notice"></div><div class="filter-heading"><div><h3>筛选待审批申请</h3><p>按组织或申请信息快速定位，按 Enter 也可查询。</p></div><span class="filter-state">当前：待审批</span></div><div class="filter-layout"><div class="filter-fields"><div class="filter-field"><label for="filterBranch">支行</label><select id="filterBranch">'+branchOptions.replace('请选择支行','全部支行')+'</select></div><div class="filter-field"><label for="filterOutlet">网点</label><select id="filterOutlet">'+outletOptionsHtml+'</select></div><div class="filter-field filter-keyword"><label for="filterKeyword">申请人或关键字</label><input id="filterKeyword" value="'+e(S.approval.q)+'" placeholder="姓名、认证号、账号或资产编码"></div></div><div class="filter-actions"><button id="applyFilter" class="primary">查询申请</button><button id="clearFilter" class="secondary">重置条件</button></div></div></section><section class="card"><div class="card-title-row"><h3>待审批申请</h3><span id="pendingCount" class="badge">加载中</span></div><div id="table">加载中…</div></section>';
        function load(){var path='admin/applications?status=PENDING&branchId='+encodeURIComponent(S.approval.branch)+'&outletId='+encodeURIComponent(S.approval.outlet)+'&q='+encodeURIComponent(S.approval.q);q('GET',path,null,function(r){var h='<table><tr><th>申请人</th><th>组织</th><th>资产信息</th><th>提交时间</th><th>操作</th></tr>';var j;var x;document.getElementById('pendingCount').innerHTML='待审批 '+r.items.length;if(!S.approval.branch&&!S.approval.outlet&&!S.approval.q){S.pendingCount=r.items.length;if(document.getElementById('navPending'))document.getElementById('navPending').innerHTML=r.items.length;}for(j=0;j<r.items.length;j++){x=r.items[j];h+='<tr><td><b>'+e(x.name)+'</b><br>'+e(x.unifiedAuthId)+'<br><small>账号：'+e(x.applicant)+'</small></td><td>'+e(x.branchName)+'<br>'+e(x.outletName)+'</td><td>'+(x.assetType==='TECH'?'科技资产<br>资源编码：'+e(x.assetCode)+'<br>设备：'+e(x.deviceType):'非科技资产')+'</td><td>'+e(formatDate(x.createdAt))+'<br><small>'+e(waiting(x.createdAt))+'</small></td><td><button data-select="'+x.id+'">选择可用 IP</button><button class="danger" data-reject="'+x.id+'">驳回</button></td></tr>';}document.getElementById('table').innerHTML=r.items.length?h+'</table>':empty('暂无待审批申请','可以调整筛选条件，或等待科技管理员提交申请。');approvalActions(r.items);},function(){var t=document.getElementById('table');if(t)t.innerHTML=empty('审批列表加载失败','请根据页面提示处理后重试。');});}
        document.getElementById('filterBranch').onchange=function(){S.approval.branch=this.value;S.approval.outlet='';applications();};
        document.getElementById('applyFilter').onclick=function(){S.approval.branch=val('filterBranch');S.approval.outlet=val('filterOutlet');S.approval.q=val('filterKeyword');load();};
        document.getElementById('clearFilter').onclick=function(){S.approval={branch:'',outlet:'',q:''};applications();};
        document.getElementById('filterKeyword').onkeydown=function(event){if((event||window.event).keyCode===13)document.getElementById('applyFilter').click();};
        load();
      });
    });
  }

  function approvalActions(items) {
    var buttons = document.getElementById('table').getElementsByTagName('button');
    var i;
    for (i = 0; i < buttons.length; i++) {
      buttons[i].onclick = function () {
        var id = this.getAttribute('data-reject');
        var item;
        var j;
        if (id) {
          for(j=0;j<items.length;j++)if(String(items[j].id)===String(id))item=items[j];
          (function(application){systemDialog('驳回 IP 申请','申请人：'+application.name+'；网点：'+application.branchName+' / '+application.outletName+'；资产：'+(application.assetCode||'非科技资产')+'。','<div><label for="rejectReason">驳回原因 <span class="required">*</span></label><textarea id="rejectReason" maxlength="500" rows="4" placeholder="请说明需要修正的内容，最多 500 字"></textarea><small id="rejectCount">0 / 500</small></div>','确认驳回',function(){var reason=val('rejectReason');if(!reason){msg('驳回原因不能为空。',1);return false;}return{reason:reason};},function(root,data,button){q('POST','admin/applications/'+application.id+'/reject',data,function(){closeDialog(root);reloadMessage(applications,'申请已驳回，原因已通知申请人。');},null,button,'驳回中…');});document.getElementById('rejectReason').onkeyup=function(){document.getElementById('rejectCount').innerHTML=this.value.length+' / 500';};}(item));
          return;
        }
        openIpPicker(this.getAttribute('data-select'), 1, '');
      };
    }
  }

  function openIpPicker(applicationId, page, keyword) {
    q('GET', 'admin/available-ips?applicationId=' + applicationId + '&page=' + page + '&q=' + encodeURIComponent(keyword), null, function (r) {
      var old = document.getElementById('ipModal');
      var root;
      var h = '<div class="modal"><div class="dialog" role="dialog" aria-modal="true" aria-labelledby="pickerTitle"><div class="dialog-head"><div><h3 id="pickerTitle">选择可用 IP</h3><p>申请人：'+e(r.application.name)+'；网点：'+e(r.application.branchName)+' / '+e(r.application.outletName)+'；资产：'+e(r.application.assetCode||'非科技资产')+'</p></div><button id="closePicker" aria-label="关闭弹窗">关闭</button></div><div id="pickerNotice"></div><div class="search-row"><label for="ipSearch" class="visually-hidden">搜索 IP</label><input id="ipSearch" value="' + e(keyword) + '" placeholder="输入 IP 地址搜索"><button id="searchIp" class="secondary">搜索</button></div><div class="table-scroll"><table><tr><th>选择</th><th>IP</th><th>地址段</th><th>子网掩码</th><th>网关</th><th>DNS</th></tr>';
      var i;
      var x;
      if (old) old.parentNode.removeChild(old);
      if (!r.items.length) {
        h += '<tr><td colspan="6"><div class="notice bad">该申请所属网点当前没有可分配 IP。请确认网点已绑定并启用地址段，具体 IP 未保留、未停用、未分配，并且申请选择的网点正确。</div></td></tr>';
      }
      for (i = 0; i < r.items.length; i++) {
        x = r.items[i];
        h += '<tr class="selectable" data-row="'+i+'"><td><input class="radio" type="radio" name="selectedIp" value="' + x.id + '" aria-label="选择 '+e(x.ipAddress)+'"></td><td><b>' + e(x.ipAddress) + '</b></td><td>' + e(x.cidr) + '</td><td>' + e(x.subnetMask) + '</td><td>' + e(x.gateway) + '</td><td>' + e(x.dnsPrimary) + (x.dnsSecondary ? '<br>' + e(x.dnsSecondary) : '') + '</td></tr>';
      }
      h += '</table></div><div class="dialog-actions"><span>共 '+r.total+' 个可用 IP / 第 '+page+' 共 '+r.pages+' 页</span><button id="previousPage" class="secondary"' + (page <= 1 ? ' disabled' : '') + '>上一页</button><button id="nextPage" class="secondary"' + (page >= r.pages ? ' disabled' : '') + '>下一页</button><button id="approveApplication" class="primary"' + (!r.items.length ? ' disabled' : '') + '>确认分配</button></div></div></div>';
      root = document.createElement('div');
      root.id = 'ipModal';
      root.innerHTML = h;
      lastDialogFocus=document.activeElement;
      document.body.appendChild(root);
      wireDialog(root,'closePicker','ipSearch');
      document.getElementById('searchIp').onclick = function () { openIpPicker(applicationId, 1, val('ipSearch')); };
      document.getElementById('previousPage').onclick = function () { if (page > 1) openIpPicker(applicationId, page - 1, keyword); };
      document.getElementById('nextPage').onclick = function () { if (page < r.pages) openIpPicker(applicationId, page + 1, keyword); };
      var rows=root.getElementsByTagName('tr');for(i=1;i<rows.length;i++)if(rows[i].getAttribute('data-row')!==null)rows[i].onclick=function(event){var radio=this.getElementsByTagName('input')[0];var all=root.getElementsByTagName('tr');var k;radio.checked=true;for(k=0;k<all.length;k++)all[k].className=all[k]===this?'selectable selected':'selectable';if(event&&event.target===radio)event.cancelBubble=true;};
      document.getElementById('approveApplication').onclick = function () {
        var radios = root.getElementsByTagName('input');
        var selected = null;
        var j;
        for (j = 0; j < radios.length; j++) if (radios[j].name === 'selectedIp' && radios[j].checked) selected = radios[j].value;
        if (!selected) {
          document.getElementById('pickerNotice').innerHTML='<div class="notice bad">请先选择一个可用 IP；系统不会默认选中。</div>';
          return;
        }
        for(j=0;j<r.items.length;j++)if(String(r.items[j].id)===String(selected))x=r.items[j];
        systemDialog('确认分配','将 '+x.ipAddress+' 分配给 '+r.application.name+' / '+(r.application.assetCode||'非科技资产')+'。提交前请再次核对网点和网络参数。','<div class="confirm-summary"><b>'+e(x.ipAddress)+'</b><span>掩码 '+e(x.subnetMask)+'</span><span>网关 '+e(x.gateway)+'</span><span>DNS '+e(x.dnsPrimary)+(x.dnsSecondary?' / '+e(x.dnsSecondary):'')+'</span></div>','确认分配',null,function(confirmRoot,data,button){q('POST','admin/applications/'+applicationId+'/approve',{poolId:Number(selected)},function(){closeDialog(confirmRoot);closeDialog(root);reloadMessage(applications,'审批通过，IP 已完成分配。');},null,button,'分配中…');});
      };
    });
  }

  function myips() {
    document.getElementById('main').innerHTML = head('我的资源', '我的 IP', '使用中的 IP 可立即释放；释放后需要重新提交申请才能再次使用。') + '<section class="card"><div id="notice"></div><div id="table">加载中…</div></section>';
    q('GET', 'my-assignments', null, function (r) {
      var h='<table><tr><th>状态</th><th>IP 与网络参数</th><th>组织与资产</th><th>分配/释放时间</th><th>操作</th></tr>',i,x;
      for(i=0;i<r.items.length;i++){x=r.items[i];h+='<tr><td><span class="status status-'+(x.status==='ASSIGNED'?'assigned':'rejected')+'">'+(x.status==='ASSIGNED'?'使用中':'已释放')+'</span></td><td><b>'+e(x.ipAddress)+'</b><br><small>掩码 '+e(x.subnetMask)+'；网关 '+e(x.gateway)+'</small></td><td>'+e(x.branchName)+' / '+e(x.outletName)+'<br>'+e(x.assetType==='TECH'?'科技资产：'+(x.assetCode||'—'):'非科技资产')+'</td><td>分配：'+e(formatDate(x.assignedAt))+(x.releasedAt?'<br>释放：'+e(formatDate(x.releasedAt)):'')+'</td><td>'+(x.status==='ASSIGNED'?'<button class="danger" data-release="'+x.id+'">释放 IP</button>':'—')+'</td></tr>';}
      document.getElementById('table').innerHTML=r.items.length?h+'</table>':empty('暂无 IP 分配记录','申请通过后会在此显示已分配 IP。');
      var bs=document.getElementById('table').getElementsByTagName('button');for(i=0;i<bs.length;i++)bs[i].onclick=function(){var id=this.getAttribute('data-release'),item,j;for(j=0;j<r.items.length;j++)if(String(r.items[j].id)===String(id))item=r.items[j];releaseDialog(item);};
    },function(){var t=document.getElementById('table');if(t)t.innerHTML=empty('我的 IP 加载失败','请根据页面提示处理后重试。');});
  }

  function releaseDialog(item) {
    var summary='<div class="release-warning"><b>请确认以下释放信息</b><p><strong>IP 地址：</strong>'+e(item.ipAddress)+'</p><p><strong>支行和网点：</strong>'+e(item.branchName)+' / '+e(item.outletName)+'</p><p><strong>资产类型：</strong>'+e(item.assetType==='TECH'?'科技资产':'非科技资产')+'</p><p><strong>科技资产资源编码：</strong>'+e(item.assetCode||'—')+'</p><p><strong>原分配时间：</strong>'+e(formatDate(item.assignedAt))+'</p><div class="notice bad">确认后立即释放，无需管理员审批。释放后该 IP 可能被分配给其他资产；如需再次使用，必须重新申请。</div></div><div><label for="releaseReason">释放原因（可选）</label><textarea id="releaseReason" maxlength="500" rows="3" placeholder="可填写本次释放说明"></textarea></div>';
    systemDialog('确认立即释放 IP', '此操作不可直接撤销，请核对分配信息。', summary, '确认立即释放', function(){return {reason:val('releaseReason')};}, function(root,data,button){q('POST','my-assignments/'+item.id+'/release',data,function(){closeDialog(root);reloadMessage(myips,'IP 已立即释放，管理员已收到站内通知。');},null,button,'释放处理中…');});
  }

  function notifications() {
    document.getElementById('main').innerHTML=head('消息中心','站内通知','释放通知仅用于告知，可标记已读并进入相关释放记录。')+'<section class="card"><div id="table">加载中…</div></section>';
    q('GET','notifications',null,function(r){var h='',i,x;for(i=0;i<r.items.length;i++){x=r.items[i];h+='<div class="history-row '+(x.isRead===1?'read':'')+'"><span>'+e(formatDate(x.createdAt))+'</span><b>'+e(x.message)+'</b>'+(x.releaseEventId?'<button data-history="1" data-read="'+x.id+'">查看释放记录</button>':'')+(x.isRead===1?'':'<button data-read="'+x.id+'">标记已读</button>')+'</div>';}document.getElementById('table').innerHTML=h||empty('暂无站内通知','释放或审批结果会在此显示。');var bs=document.getElementById('table').getElementsByTagName('button');for(i=0;i<bs.length;i++)bs[i].onclick=function(){var id=this.getAttribute('data-read'),go=this.getAttribute('data-history');q('POST','notifications/'+id+'/read',{},function(){if(go){S.v='releases';view();}else notifications();});};},function(){var t=document.getElementById('table');if(t)t.innerHTML=empty('通知加载失败','请根据页面提示处理后重试。');});
  }

  function releases() {
    S.releaseFilter=S.releaseFilter||{q:'',assetType:'',from:'',to:''};
    document.getElementById('main').innerHTML=head('资源运营','释放记录','仅供查看，不提供释放审批或恢复入口。')+'<section class="card filter-card"><div id="notice"></div><div class="filter-layout"><div class="filter-fields"><div class="filter-field filter-keyword"><label for="releaseQ">释放人或 IP</label><input id="releaseQ" value="'+e(S.releaseFilter.q)+'" placeholder="科技管理员账号或 IP"></div><div class="filter-field"><label for="releaseAsset">资产类型</label><select id="releaseAsset"><option value="">全部</option><option value="TECH"'+(S.releaseFilter.assetType==='TECH'?' selected':'')+'>科技资产</option><option value="NON_TECH"'+(S.releaseFilter.assetType==='NON_TECH'?' selected':'')+'>非科技资产</option></select></div><div class="filter-field"><label for="releaseFrom">开始日期</label><input id="releaseFrom" type="date" value="'+e(S.releaseFilter.from)+'"></div><div class="filter-field"><label for="releaseTo">结束日期</label><input id="releaseTo" type="date" value="'+e(S.releaseFilter.to)+'"></div></div><div class="filter-actions"><button id="releaseSearch" class="primary">查询记录</button></div></div></section><section class="card"><div id="table">加载中…</div></section>';
    function load(){var p='admin/release-history?q='+encodeURIComponent(S.releaseFilter.q)+'&assetType='+encodeURIComponent(S.releaseFilter.assetType)+'&from='+encodeURIComponent(S.releaseFilter.from)+'&to='+encodeURIComponent(S.releaseFilter.to);q('GET',p,null,function(r){var h='<table><tr><th>释放时间</th><th>释放人</th><th>IP / 资源编码</th><th>组织</th><th>资产类型</th><th>审计</th></tr>',i,x;for(i=0;i<r.items.length;i++){x=r.items[i];h+='<tr><td>'+e(formatDate(x.releasedAt))+'</td><td>'+e(x.releasedBy)+'</td><td><b>'+e(x.ipAddress)+'</b><br>'+e(x.resourceCode||'—')+'</td><td>'+e(x.branchName)+' / '+e(x.outletName)+'</td><td>'+e(x.assetType==='TECH'?'科技资产':'非科技资产')+'</td><td>'+e(x.auditAction||'RELEASE_IP')+'</td></tr>';}document.getElementById('table').innerHTML=r.items.length?h+'</table>':empty('暂无释放记录','调整筛选条件，或等待科技管理员释放已分配 IP。');},function(){var t=document.getElementById('table');if(t)t.innerHTML=empty('释放记录加载失败','请根据页面提示处理后重试。');});}
    document.getElementById('releaseSearch').onclick=function(){S.releaseFilter={q:val('releaseQ'),assetType:val('releaseAsset'),from:val('releaseFrom'),to:val('releaseTo')};load();};load();
  }

  function overview() {
    document.getElementById('main').innerHTML=head('资源运营','资源概览','统计仅反映数据库中的资源使用情况，不执行网络连通性检测。')+'<section class="card"><div id="overviewStats">加载中…</div></section><section class="card"><h3>支行使用率</h3><div id="usageChart" class="chart" aria-label="支行 IP 使用率图表"></div><p class="hint">图表数据同时完整列在下表中，便于旧浏览器或辅助阅读。</p><div id="table">加载中…</div></section>';
    q('GET','admin/resource-overview',null,function(r){var h='<div class="stats five"><div>地址总数<strong>'+r.addressTotal+'</strong></div><div>当前可分配<strong>'+r.availableCount+'</strong></div><div>当前已分配<strong>'+r.assignedCount+'</strong></div><div>累计已释放<strong>'+r.releasedTotal+'</strong></div><div>待审批<strong>'+r.pendingCount+'</strong></div></div><div class="stats five"><div>停用<strong>'+r.disabledCount+'</strong></div><div>人工保留<strong>'+r.manualReservedCount+'</strong></div><div>系统保留<strong>'+r.systemReservedCount+'</strong></div><div>今日释放<strong>'+r.todayReleased+'</strong></div><div>使用率<strong>'+r.usageRate+'%</strong></div></div>';document.getElementById('overviewStats').innerHTML=h;var t='<table><tr><th>支行</th><th>当前已分配</th><th>当前可用</th><th>使用率</th></tr>',i,x,total,rate,names=[],rates=[];for(i=0;i<r.branches.length;i++){x=r.branches[i];total=Number(x.availableCount||0)+Number(x.assignedCount||0);rate=total?Math.round(Number(x.assignedCount)*10000/total)/100:0;names.push(x.branchName);rates.push(rate);t+='<tr><td>'+e(x.branchName)+'</td><td>'+x.assignedCount+'</td><td>'+x.availableCount+'</td><td>'+rate+'%</td></tr>';}if(window.echarts){window.echarts.init(document.getElementById('usageChart')).setOption({animation:false,grid:{left:55,right:20,top:25,bottom:55},xAxis:{type:'category',data:names,axisLabel:{interval:0,rotate:25}},yAxis:{type:'value',max:100,axisLabel:{formatter:'{value}%'}},series:[{type:'bar',data:rates,itemStyle:{color:'#17649e'}}]});}t+='</table><h3>网点剩余 IP</h3><table><tr><th>支行</th><th>网点</th><th>剩余 IP</th></tr>';for(i=0;i<r.outlets.length;i++){x=r.outlets[i];t+='<tr><td>'+e(x.branchName)+'</td><td>'+e(x.outletName)+'</td><td>'+x.availableCount+'</td></tr>';}document.getElementById('table').innerHTML=t;},function(){document.getElementById('overviewStats').innerHTML=empty('资源统计加载失败','请检查服务状态后重试。');var t=document.getElementById('table');if(t)t.innerHTML='';});
  }

  function exports() {
    document.getElementById('main').innerHTML=head('资源运营','数据导出','仅导出经字段白名单允许的数据；统一认证号默认不在任何导出中。')+'<section class="card"><div id="notice"></div><div class="grid"><div><label for="exportType">数据类型</label><select id="exportType"><option value="POOL">IP 资源池</option><option value="APPLICATION">申请记录</option><option value="ASSIGNMENT">当前分配记录</option><option value="RELEASE">已释放记录</option><option value="STATISTICS">支行和网点资源统计</option></select></div><div><label for="exportFormat">文件格式</label><select id="exportFormat"><option value="CSV">CSV（UTF-8）</option><option value="XLSX">XLSX（无宏）</option></select></div></div><p class="hint">文件名带安全时间戳。超过最大行数请先缩小查询范围。</p><button id="downloadExport" class="primary">下载导出文件</button></section>';
    document.getElementById('downloadExport').onclick=function(){downloadExport(this,val('exportType'),val('exportFormat'));};
  }

  function downloadExport(button,type,format) { var x=new XMLHttpRequest();if(busy(button,true,'正在生成文件…')===false)return;x.open('POST','/api/v1/admin/exports',true);x.responseType='blob';x.setRequestHeader('Content-Type','application/json');x.setRequestHeader('X-CSRF-Token',S.c);x.onreadystatechange=function(){if(x.readyState!==4)return;busy(button,false);if(x.status>=200&&x.status<300){var a=document.createElement('a'),url=window.URL.createObjectURL(x.response);a.href=url;a.download='ipam-'+String(type).toLowerCase()+'.'+(format==='XLSX'?'xlsx':'csv');document.body.appendChild(a);a.click();document.body.removeChild(a);window.URL.revokeObjectURL(url);msg('导出文件已开始下载。',0);return;}var reader=new FileReader();reader.onload=function(){var r={};try{r=JSON.parse(reader.result||'{}');}catch(ignore){}msg(r.message||'导出未完成，请缩小范围后重试。',1);};reader.readAsText(x.response||new Blob());};x.onerror=function(){busy(button,false);msg('导出连接中断，请检查服务状态。',1);};x.send(JSON.stringify({type:type,format:format,fields:[]})); }

  function audit() {
    document.getElementById('main').innerHTML = head('安全中心', '审计日志', '最近 200 条操作记录。') +
      '<section class="card"><div id="table">加载中…</div></section>';
    q('GET', 'admin/audit', null, function (r) {
      var h = '<table><tr><th>时间</th><th>操作人</th><th>动作</th><th>对象</th></tr>';
      var i;
      var x;
      for (i = 0; i < r.items.length; i++) {
        x = r.items[i];
        h += '<tr><td>' + e(formatDate(x.createdAt)) + '</td><td>' + e(x.actor || '系统') + '</td><td>' + e(x.action) + '</td><td>' + e(x.targetType) + ' / ' + e(x.targetId) + '</td></tr>';
      }
      document.getElementById('table').innerHTML = r.items.length ? h + '</table>' : empty('暂无审计记录', '完成业务操作后会在这里保留审计轨迹。');
    });
  }

  render();
}());
