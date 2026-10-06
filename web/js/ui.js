/* Small UI helpers shared by every view. */
(function () {
  const M = window.M;

  M.esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  M.h = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content; };

  M.bytes = (n) => {
    if (!n) return '0 B';
    const u = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${u[i]}`;
  };

  M.ago = (iso) => {
    if (!iso) return '';
    const t = typeof iso === 'number' ? iso * 1000 : Date.parse(iso);
    const s = Math.round((Date.now() - t) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return `${Math.floor(s / 60)}m ago`;
    if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
    if (s < 7 * 86400) return `${Math.floor(s / 86400)}d ago`;
    return new Date(t).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  };

  M.dayKey = (iso) => new Date(iso).toLocaleDateString('en-CA');
  M.dayLabel = (iso) => {
    const d = new Date(iso), today = new Date();
    const k = M.dayKey(d), tk = M.dayKey(today);
    const tomorrow = new Date(today); tomorrow.setDate(today.getDate() + 1);
    const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1);
    if (k === tk) return 'Today';
    if (k === M.dayKey(tomorrow)) return 'Tomorrow';
    if (k === M.dayKey(yesterday)) return 'Yesterday';
    return d.toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' });
  };
  M.time = (iso) => new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });

  M.tag = (app, label) => `<span class="tag ${M.esc(app)}">${M.esc(label)}</span>`;

  M.art = (url) => url
    ? `<img class="art" loading="lazy" referrerpolicy="no-referrer" src="${M.esc(url)}" alt="">`
    : '<div class="art"></div>';

  M.errBox = (e) => `<div class="err-box">${M.esc(e && e.message ? e.message : e)}</div>`;
  M.svcErr = (x) => (x && x._error ? `<div class="err-box">${M.esc(x._error)}</div>` : '');

  let toastTimer;
  M.toast = (msg) => {
    const t = document.getElementById('toast');
    t.textContent = msg; t.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, 2600);
  };

  M.skeleton = (n = 4) => '<div class="list">' + '<div class="skeleton"></div>'.repeat(n) + '</div>';
})();
