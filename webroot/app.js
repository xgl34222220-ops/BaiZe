import { exec, toast } from './ksu.js';

const MODULE = '/data/adb/modules/safesweep';
const ctl = `${MODULE}/webctl.sh`;
let state = {};
let snackTimer;
let monitoring = false;
let formDirty = false;

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];

const CONTROL_GROUPS = {
  'app-cache': ['clean_app_cache', 'clean_external_cache'],
  'empty-items': ['clean_empty_files', 'clean_empty_dirs'],
  'rule-junk': ['clean_app_rules', 'clean_hidden_junk', 'clean_system_logs']
};

function syncGroupToggles() {
  Object.entries(CONTROL_GROUPS).forEach(([name, keys]) => {
    const master = $(`[data-group-toggle="${name}"]`);
    if (!master) return;
    const controls = keys.map((key) => $(`[data-key="${key}"]`)).filter(Boolean);
    const enabled = controls.filter((input) => input.checked).length;
    master.checked = enabled === controls.length;
    master.indeterminate = enabled > 0 && enabled < controls.length;
    master.classList.toggle('mixed', master.indeterminate);
  });
}

function syncNotificationMode() {
  const enabled = $('[data-key="notify_on_complete"]')?.checked;
  const zero = $('[data-key="notify_zero_result"]')?.checked;
  const mode = $('#notification-mode');
  if (mode) mode.value = !enabled ? 'off' : (zero ? 'always' : 'result');
}

function formatBytes(bytes) {
  const n = Number(bytes || 0);
  if (n < 1024) return `${n} B`;
  if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1073741824) return `${(n / 1048576).toFixed(1)} MB`;
  return `${(n / 1073741824).toFixed(2)} GB`;
}

function formatStorageGB(kilobytes) {
  const n = Number(kilobytes || 0);
  return n > 0 ? `${(n / 1048576).toFixed(2)} GB` : '—';
}

function message(text) {
  const el = $('#snackbar');
  el.textContent = text;
  el.classList.add('show');
  clearTimeout(snackTimer);
  snackTimer = setTimeout(() => el.classList.remove('show'), 2400);
  toast(text);
}

function setBusy(show, title = '正在处理') {
  $('#busy-title').textContent = title;
  $('#busy').hidden = !show;
}

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const nextPaint = () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));

async function command(args) {
  if (!window.ksu?.exec) throw new Error('当前管理器未提供 WebUI 命令接口');
  const result = await exec(`sh ${ctl} ${args}`);
  if (result.errno !== 0) throw new Error((result.stderr || result.stdout || '命令执行失败').trim());
  return result.stdout.trim();
}

function fillState() {
  $('#device').textContent = `${state.brand || 'Android'} ${state.model || ''} · Android ${state.android || ''}`;
  if (state.running) {
    $('#last-size').textContent = '…';
    const runningLabels = {
      'cache-clean': '缓存清理中', 'empty-clean': '空文件清理中', 'rules-clean': '规则清理中',
      'fragment-clean': '碎片清理中', 'fragment-scan': '碎片扫描中',
      'deep-clean': '深度清理中', 'deep-scan': '深度扫描中',
      'corpse-clean': '残留清理中', 'corpse-scan': '残留扫描中', clean: '清理中', scan: '扫描中'
    };
    $('#last-kind').textContent = runningLabels[state.run_mode] || '处理中';
  } else {
    $('#last-size').textContent = formatBytes(state.total_bytes);
    $('#last-kind').textContent = Number(state.total_runs || 0) > 0 ? '累计已清理' : '等待清理';
  }
  $('.hero').classList.toggle('running', Boolean(state.running));
  const ringCurrent = Number(state.run_progress_current || 0);
  const ringTotal = Number(state.run_progress_total || 0);
  const ringProgress = state.running && ringTotal > 0 ? Math.max(4, Math.min(100, (ringCurrent / ringTotal) * 100)) : 72;
  $('.hero-ring').style.setProperty('--ring-progress', `${ringProgress}%`);
  $('#last-result').textContent = state.running ? (state.run_phase || '正在处理') : (state.last_result || '尚未运行');
  const runningSeconds = Math.max(0, Math.floor(Date.now() / 1000) - Number(state.run_started || 0));
  if (state.running) {
    const progressCurrent = Number(state.run_progress_current || 0);
    const progressTotal = Number(state.run_progress_total || 0);
    const progressText = progressTotal > 0 ? ` · ${progressCurrent}/${progressTotal}` : '';
    const currentPath = String(state.run_current_path || '');
    const shortPath = currentPath.length > 54 ? `…${currentPath.slice(-53)}` : currentPath;
    $('#last-time').textContent = `已运行 ${runningSeconds} 秒${progressText}${shortPath ? ` · ${shortPath}` : ' · 后台任务可随时切换页面'}`;
    $('#last-time').title = currentPath;
  } else {
    $('#last-time').textContent = state.last_time === '从未运行' ? '首屏不会自动扫描存储' : state.last_time;
    $('#last-time').removeAttribute('title');
  }
  $('#metric-files').textContent = Number(state.total_regular_files || 0).toLocaleString();
  $('#metric-fragments').textContent = Number(state.total_fragment_files || 0).toLocaleString();
  $('#metric-empty-files').textContent = Number(state.total_empty_files || 0).toLocaleString();
  $('#metric-empty').textContent = Number(state.total_empty_dirs || 0).toLocaleString();
  $('#metric-period').textContent = `${Number(state.total_runs || 0)}次`;
  $('#metric-hidden').textContent = Number(state.total_hidden_items || 0).toLocaleString();
  $('#log-result').textContent = state.last_result || '暂无';
  $('#log-size').textContent = formatBytes(state.last_bytes);
  const percent = Math.max(0, Math.min(100, Number(state.data_percent || 0)));
  $('#storage-free').textContent = `剩余 ${formatStorageGB(state.data_free_kb)}`;
  $('#storage-percent').textContent = `${percent}%`;
  $('#storage-bar').style.width = `${percent}%`;
  $('#storage-used').textContent = formatStorageGB(state.data_used_kb);
  $('#storage-total').textContent = formatStorageGB(state.data_total_kb);
  const schedulerLabels = { running: '定时任务正在执行', completed: '定时服务正常', waiting: '定时任务正在等待条件', interrupted: '定时任务已中断', missed: '定时补做窗口已错过', disabled: '定时任务已关闭', failed: '定时任务执行失败', unknown: '定时服务尚未就绪' };
  const schedulerState = state.scheduler_state || 'unknown';
  $('#scheduler-title').textContent = schedulerLabels[schedulerState] || '定时服务状态未知';
  const groupNames = { daily: '每日定时', cache: '缓存', empty: '空文件', rules: '规则垃圾', fragment: '残留碎片', deep: '深度安全项', multiple: '多个任务' };
  const schedulerWhen = Number(state.scheduler_updated || 0) > 0 ? ` · 更新于 ${new Date(Number(state.scheduler_updated) * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}` : '';
  $('#scheduler-detail').textContent = `${state.scheduler_group ? `${groupNames[state.scheduler_group] || state.scheduler_group}：` : ''}${state.scheduler_reason || '—'}${schedulerWhen}`;
  $('#scheduler-dot').className = `scheduler-dot ${schedulerState}`;

  $('#scan').disabled = Boolean(state.running);
  $('#clean').disabled = Boolean(state.running);
  $('#stop-run').hidden = !state.running && !state.stop_requested;
  $('#stop-run').disabled = false;
  $('#stop-run strong').textContent = state.stop_requested ? '恢复定时任务' : '停止当前任务';
  $('#stop-run small').textContent = state.stop_requested ? '解除停止标记并恢复定时' : '不会把中断任务记为成功';
  $('#fragment-scan').disabled = Boolean(state.running);
  $('#fragment-clean').disabled = Boolean(state.running);
  $('#deep-action').disabled = Boolean(state.running);
  $('#corpse-action').disabled = Boolean(state.running);
  $('#deep-rule-summary').textContent = `${Number(state.deep_rule_count || 0).toLocaleString()} 条规则 · 去重 ${Number(state.deep_rule_unique || 0).toLocaleString()} · 全部保留`;
  const now = Math.floor(Date.now() / 1000);
  const deepAge = now - Number(state.deep_scan_epoch || 0);
  const corpseAge = now - Number(state.corpse_scan_epoch || 0);
  const deepRecent = deepAge >= 0 && deepAge <= 1800;
  const corpseRecent = corpseAge >= 0 && corpseAge <= 1800;
  $('#deep-action').textContent = deepRecent ? '确认清理' : '开始扫描';
  if (deepRecent) {
    const deepWarnings = Number(state.deep_scan_slow_items || 0) + Number(state.deep_scan_mount_items || 0);
    const partial = Number(state.deep_scan_truncated || 0) === 1;
    $('#deep-status').textContent = `${formatBytes(state.deep_scan_bytes)} · ${Math.max(1, Math.ceil((1800 - deepAge) / 60))} 分钟${partial ? ' · 部分' : (deepWarnings > 0 ? ` · 跳过${deepWarnings}` : '')}`;
  } else {
    $('#deep-status').textContent = '需先扫描';
  }
  $('#corpse-action').textContent = corpseRecent ? '确认清理' : '开始扫描';
  $('#corpse-status').textContent = corpseRecent ? `${Number(state.corpse_scan_items || 0).toLocaleString()} 项 · ${Math.max(1, Math.ceil((1800 - corpseAge) / 60))} 分钟` : '需先扫描';
  const highRiskEnabled = Boolean(Number(state.deep_high_risk_enabled || 0));
  $('#deep-risk-note').textContent = highRiskEnabled
    ? '定时仅执行低/中风险安全项；完整深度清理会在扫描后放行高风险与关键规则。'
    : '定时仅执行低/中风险安全项；完整规则库保留，高风险与关键规则默认只扫描。';
  $('#report-count').textContent = Number(state.report_lines || 0).toLocaleString();

  if (!formDirty) {
    $$('[data-key]').forEach((input) => {
      const value = state[input.dataset.key];
      if (value === undefined) return;
      if (input.type === 'checkbox') input.checked = Boolean(Number(value));
      else input.value = value;
    });
    syncGroupToggles();
    syncNotificationMode();
  }
}

async function loadStatus(showError = true) {
  try {
    state = JSON.parse(await command('status'));
    fillState();
    return state;
  } catch (error) {
    if (showError) message(error.message);
    $('#device').textContent = '无法连接模块服务';
    return null;
  }
}

async function loadWhitelist() {
  try { $('#whitelist').value = await command('whitelist'); }
  catch (error) { message(error.message); }
}

async function loadLog() {
  try { $('#log').textContent = await command('log 260') || '暂无日志'; }
  catch (error) { $('#log').textContent = error.message; }
}

async function loadReport() {
  try { $('#report').textContent = await command('report 800') || '暂无扫描报告'; }
  catch (error) { $('#report').textContent = error.message; }
}

async function loadHistory() {
  try { $('#history').textContent = await command('history 100') || '暂无历史记录'; }
  catch (error) { $('#history').textContent = error.message; }
}

async function run(mode) {
  if (monitoring) return;
  const launchLabels = {
    'deep-scan': '正在启动深度扫描', 'deep-clean': '正在启动深度清理',
    'fragment-scan': '正在启动碎片扫描', 'fragment-clean': '正在启动碎片清理',
    'corpse-scan': '正在启动卸载残留扫描', 'corpse-clean': '正在启动卸载残留清理',
    scan: '正在启动安全扫描', clean: '正在启动日常清理'
  };
  setBusy(true, launchLabels[mode] || '正在启动任务');
  await nextPaint();
  try {
    const output = await command(`start ${mode}`);
    if (output !== 'started' && output !== 'busy') throw new Error(output || '任务启动失败');
    setBusy(false);
    message(output === 'busy' ? '已有任务正在后台运行' : '任务已启动，可继续使用页面');
    await monitorRun();
  } catch (error) {
    message(error.message);
    setBusy(false);
  }
}

async function monitorRun() {
  if (monitoring) return;
  monitoring = true;
  let seenRunning = false;
  try {
    const maxPolls = Math.max(180, Math.ceil(((Number(state.max_run_minutes || 45) * 60) + 180) / 2));
    for (let i = 0; i < maxPolls; i += 1) {
      const current = await loadStatus(false);
      if (!current) throw new Error('无法读取清理状态');
      if (current.running) {
        seenRunning = true;
      } else if (seenRunning || i >= 2) {
        await loadStatus(false);
        await loadLog();
        await loadReport();
        await loadHistory();
        const exitCode = Number(current.job_exit || 0);
        if (exitCode === 9) {
          message(current.last_result || '任务已停止，本周期不会记为完成');
          return;
        }
        if (exitCode !== 0) {
          throw new Error(`任务失败（代码 ${exitCode}），请查看日志`);
        }
        message(current.last_result || '任务完成');
        return;
      }
      await delay(2000);
    }
    throw new Error('任务运行时间过长，请查看日志');
  } catch (error) {
    message(error.message);
  } finally {
    monitoring = false;
  }
}

function utf8Base64(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}

async function saveRules() {
  const controls = $$('[data-key]');
  const deepSchedule = $('[data-key="schedule_deep_enabled"]');
  if (deepSchedule?.checked && !Number(state.schedule_deep_enabled || 0)) {
    const accepted = window.confirm('深度安全项定时只会执行低风险和中风险规则，高风险与关键规则永远不会被定时任务放行。确认开启吗？');
    if (!accepted) { deepSchedule.checked = false; return; }
  }
  const highRisk = $('[data-key="deep_high_risk_enabled"]');
  if (highRisk?.checked && !Number(state.deep_high_risk_enabled || 0)) {
    const accepted = window.confirm('这不会删除任何规则，但会允许手动深度清理执行高风险与关键路径。每次仍必须先扫描，并在 30 分钟内手动确认；定时永远不会放行。确认开启吗？');
    if (!accepted) { highRisk.checked = false; return; }
  }
  setBusy(true, '正在保存规则');
  try {
    const lines = [];
    for (const input of controls) {
      const value = input.type === 'checkbox' ? (input.checked ? '1' : '0') : String(Number(input.value));
      if (input.type === 'number') {
        const min = Number(input.min);
        const max = Number(input.max);
        if (!Number.isInteger(Number(value)) || Number(value) < min || Number(value) > max) {
          throw new Error(`数值应在 ${min}–${max} 之间`);
        }
      }
      lines.push(`${input.dataset.key}=${value}`);
    }
    await command(`save-config ${utf8Base64(lines.join('\n'))}`);
    await command(`save-whitelist ${utf8Base64($('#whitelist').value)}`);
    await loadStatus(false);
    formDirty = false;
    $('#save-rules').classList.remove('dirty');
    $('#save-rules').textContent = '已保存';
    setTimeout(() => { if (!formDirty) $('#save-rules').textContent = '保存'; }, 1200);
    message('规则已保存');
  } catch (error) {
    message(error.message);
  } finally {
    setBusy(false);
  }
}

function markDirty() {
  formDirty = true;
  $('#save-rules').textContent = '保存修改';
  $('#save-rules').classList.add('dirty');
}

async function copyText(id) {
  const text = $(`#${id}`)?.textContent || '';
  if (!text || text.startsWith('暂无')) { message('暂无可复制内容'); return; }
  try {
    await navigator.clipboard.writeText(text);
    message('已复制到剪贴板');
  } catch (_) {
    const area = document.createElement('textarea');
    area.value = text;
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    document.execCommand('copy');
    area.remove();
    message('已复制到剪贴板');
  }
}

function openPage(name) {
  $$('.page').forEach((page) => page.classList.toggle('active', page.id === `page-${name}`));
  $$('.dock button').forEach((button, index) => {
    const active = button.dataset.page === name;
    button.classList.toggle('active', active);
    if (active) $('.dock-indicator').style.transform = `translateX(${index * 100}%)`;
  });
  if (name === 'logs') { loadLog(); loadReport(); loadHistory(); }
  if (name === 'rules' && !$('#whitelist').dataset.loaded) {
    $('#whitelist').dataset.loaded = '1';
    loadWhitelist();
  }
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

$$('.dock button').forEach((button) => button.addEventListener('click', () => openPage(button.dataset.page)));
$('#refresh').addEventListener('click', () => loadStatus());
$('#scan').addEventListener('click', () => run('scan'));
$('#clean').addEventListener('click', () => run('clean'));
$('#fragment-scan').addEventListener('click', () => run('fragment-scan'));
$('#fragment-clean').addEventListener('click', () => run('fragment-clean'));
$('#deep-action').addEventListener('click', () => {
  const highRiskEnabled = Boolean(Number(state.deep_high_risk_enabled || 0));
  const age = Math.floor(Date.now() / 1000) - Number(state.deep_scan_epoch || 0);
  const recent = age >= 0 && age <= 1800;
  if (!recent) { run('deep-scan'); return; }
  let warning = `深度规则共 ${Number(state.deep_rule_count || 0).toLocaleString()} 条，全部保留。`;
  warning += highRiskEnabled
    ? ' 本次会执行低/中/高/关键风险候选，请确认已经查看扫描审计报告。'
    : ' 本次只删除低风险与中风险候选，高风险与关键规则继续受保护。';
  if (window.confirm(`${warning}

确认执行本次深度清理吗？`)) run('deep-clean');
});
$('#corpse-action').addEventListener('click', () => {
  const age = Math.floor(Date.now() / 1000) - Number(state.corpse_scan_epoch || 0);
  const recent = age >= 0 && age <= 1800;
  if (!recent) { run('corpse-scan'); return; }
  if (window.confirm('只会处理扫描确认未安装的 Android/data 与 OBB 包名目录。确认清理吗？')) run('corpse-clean');
});
$$('[data-group-toggle]').forEach((master) => master.addEventListener('change', () => {
  const keys = CONTROL_GROUPS[master.dataset.groupToggle] || [];
  keys.forEach((key) => { const input = $(`[data-key="${key}"]`); if (input) input.checked = master.checked; });
  master.indeterminate = false;
  master.classList.remove('mixed');
}));
Object.values(CONTROL_GROUPS).flat().forEach((key) => {
  const input = $(`[data-key="${key}"]`);
  input?.addEventListener('change', syncGroupToggles);
});
$('#notification-mode').addEventListener('change', (event) => {
  const mode = event.target.value;
  $('[data-key="notify_on_complete"]').checked = mode !== 'off';
  $('[data-key="notify_zero_result"]').checked = mode === 'always';
});

$('#save-rules').addEventListener('click', saveRules);
$('#refresh-log').addEventListener('click', loadLog);
$('#refresh-report').addEventListener('click', loadReport);
$('#refresh-history').addEventListener('click', loadHistory);
$('#stop-run').addEventListener('click', async () => {
  try { message(await command(state.stop_requested ? 'resume' : 'stop')); await loadStatus(false); }
  catch (error) { message(error.message); }
});
$('#reset-stats').addEventListener('click', async () => {
  if (!window.confirm('只重置累计统计，不会删除手机文件。确认继续吗？')) return;
  try {
    message(await command('reset-stats'));
    await loadStatus(false);
  } catch (error) { message(error.message); }
});
$('#test-notification').addEventListener('click', async () => {
  try { message(await command('notify-test')); }
  catch (error) { message(error.message); }
});
$$('[data-copy]').forEach((button) => button.addEventListener('click', () => copyText(button.dataset.copy)));
$$('[data-key], [data-group-toggle], #notification-mode, #whitelist').forEach((control) => {
  control.addEventListener(control.tagName === 'TEXTAREA' ? 'input' : 'change', markDirty);
});

document.addEventListener('visibilitychange', () => {
  if (!document.hidden && !monitoring) loadStatus(false);
});
setInterval(() => {
  if (!document.hidden && !monitoring) loadStatus(false);
}, 30000);

loadStatus().then((current) => {
  if (current?.running) monitorRun();
});
