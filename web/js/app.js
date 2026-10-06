/* Router, sign-in flow, and action handling. Routes: #/<view>?key=val */
(function () {
  const M = window.M;
  const view = document.getElementById('view');
  const chrome = ['topnav', 'tabbar', 'refresh-btn', 'settings-btn'].map((id) => document.getElementById(id));
  let authed = false;

  const parse = () => {
    const [path, qs] = (location.hash.replace(/^#\/?/, '') || '').split('?');
    return { name: path || 'home', params: Object.fromEntries(new URLSearchParams(qs || '')) };
  };

  const applyTheme = () => {
    const t = localStorage.getItem('mq-theme');
    if (t === 'dark' || t === 'light') document.documentElement.dataset.theme = t;
    else delete document.documentElement.dataset.theme;
  };

  M.signedOut = () => { authed = false; chrome.forEach((c) => { c.hidden = true; }); view.innerHTML = M.views.signin(); };

  async function render() {
    const { name, params } = parse();
    if (!authed) return M.signedOut();
    const fn = M.views[name] || M.views.home;
    document.querySelectorAll('[data-nav]').forEach((a) => a.classList.toggle('active', a.dataset.nav === (M.views[name] ? name : 'home')));
    if (!view.dataset.view || view.dataset.view !== name) view.innerHTML = M.skeleton();
    view.dataset.view = name;
    try {
      const html = await fn(params);
      if (parse().name === name) view.innerHTML = html;
    } catch (e) {
      if (e.message !== 'signed out') view.innerHTML = `<h1>${M.esc(name)}</h1>${M.errBox(e)}`;
    }
  }
  M.render = render;

  /* -------- sign-in with Plex (PIN flow): open plex.tv, poll until the PIN is claimed */
  async function signIn(btn) {
    const status = document.getElementById('signin-status');
    btn.disabled = true;
    const popup = window.open('', 'plexauth', 'width=600,height=720');
    try {
      const pin = await M.api('/auth/pin', { method: 'POST' });
      sessionStorage.setItem('mq-pin', pin.id);  // survives a same-tab redirect (phones block popups)
      if (popup) popup.location = pin.authUrl; else location.href = pin.authUrl;
      status.textContent = 'Waiting for Plex…';
      for (let i = 0; i < 150; i++) {
        await new Promise((r) => setTimeout(r, 2000));
        let r;
        try { r = await M.api(`/auth/pin/${pin.id}`); } catch (e) {
          if (/403/.test(e.message) || /denied/.test(e.message)) { status.textContent = 'That Plex account isn’t allowed here.'; break; }
          continue;
        }
        if (r.status === 'ok') { sessionStorage.removeItem('mq-pin'); if (popup) popup.close(); authed = true; boot(); return; }
      }
    } catch (e) { status.textContent = e.message; if (popup) popup.close(); }
    btn.disabled = false;
  }

  /* -------- actions */
  document.addEventListener('click', async (ev) => {
    const el = ev.target.closest('[data-act]');
    if (!el) return;
    const act = el.dataset.act;
    if (act === 'signin') return signIn(el);
    if (act === 'filter') {
      const { name, params } = parse();
      params[el.dataset.key] = el.dataset.val;
      location.hash = `#/${name}?${new URLSearchParams(params)}`;
      return;
    }
    if (act === 'theme') { localStorage.setItem('mq-theme', el.dataset.val); applyTheme(); return render(); }
    if (act === 'logout') { await M.api('/auth/logout', { method: 'POST' }); return M.signedOut(); }
    el.disabled = true;
    try {
      if (act === 'q-remove') {
        if (!confirm('Remove this download, blocklist the release, and search for another?')) { el.disabled = false; return; }
        await M.post(`/queue/${el.dataset.app}/${el.dataset.id}/remove?blocklist=true&search=true`);
        M.toast('Removed — searching for another release');
      } else if (act === 'req') {
        await M.post(`/requests/${el.dataset.id}/${el.dataset.do}`);
        M.toast(el.dataset.do === 'approve' ? 'Approved' : 'Declined');
      }
      render();
    } catch (e) { M.toast(e.message); el.disabled = false; }
  });

  document.getElementById('refresh-btn').addEventListener('click', async (e) => {
    const b = e.currentTarget; b.classList.add('spin'); await render(); b.classList.remove('spin');
  });

  window.addEventListener('hashchange', render);

  async function finishPendingSignIn() {
    // Came back from plex.tv in the same tab (or this is the popup itself): claim the PIN here.
    const id = sessionStorage.getItem('mq-pin');
    if (!id) return false;
    for (let i = 0; i < 10; i++) {
      try {
        const r = await M.api(`/auth/pin/${id}`);
        if (r.status === 'ok') { sessionStorage.removeItem('mq-pin'); if (window.opener) window.close(); return true; }
      } catch (_) { sessionStorage.removeItem('mq-pin'); return false; }
      await new Promise((r) => setTimeout(r, 1500));
    }
    return false;
  }

  async function boot() {
    applyTheme();
    try { await M.api('/auth/me'); authed = true; } catch (_) { authed = await finishPendingSignIn(); }
    if (location.hash.startsWith('#/signed-in')) history.replaceState(null, '', '#/');
    chrome.forEach((c) => { c.hidden = !authed; });
    render();
  }

  if ('serviceWorker' in navigator) navigator.serviceWorker.register('./sw.js').catch(() => {});
  boot();
  setInterval(() => { if (authed && !document.hidden && ['home', 'queue', 'activity'].includes(parse().name)) render(); }, 30000);
})();
