/* Views. Each returns HTML for #view; actions use data-act attributes handled in app.js. */
(function () {
  const M = window.M;
  const V = M.views = {};

  /* ------------------------------------------------------------ Home */
  V.home = async () => {
    const o = await M.get('/overview');
    const apps = o.apps.map((a) => {
      const warn = a.health.length;
      const stats = [];
      if (a.queue !== undefined) stats.push(`<div><div class="stat">${a.queue}</div><div class="muted small">in queue</div></div>`);
      if (a.missing !== undefined) stats.push(`<div><div class="stat">${a.missing}</div><div class="muted small">missing</div></div>`);
      return `<div class="card">
        <h3>${M.tag(a.app, a.label)}<span class="spacer"></span>${warn ? `<span class="tag warn">${warn} warning${warn > 1 ? 's' : ''}</span>` : '<span class="tag ok">healthy</span>'}</h3>
        <div class="stats">${stats.join('')}</div>
        ${a.health.slice(0, 3).map((h) => `<div class="msg">${M.esc(h.message)}</div>`).join('')}
        ${a.link ? `<div style="margin-top:10px"><a class="btn sm" href="${M.esc(a.link)}" target="_blank" rel="noopener">Open ${M.esc(a.label)} ↗</a></div>` : ''}
      </div>`;
    }).join('');
    const disks = o.disks.map((d) => {
      const used = 100 * (1 - d.free / d.total);
      return `<div class="card"><h3>💾 ${M.esc(d.label)}</h3>
        <div class="row"><div class="stat">${M.bytes(d.free)}<small>free</small></div><span class="spacer"></span><span class="muted small">of ${M.bytes(d.total)}</span></div>
        <div class="bar ${used < 85 ? 'green' : ''}"><i style="width:${used.toFixed(1)}%"></i></div></div>`;
    }).join('');
    const playing = o.playing.length
      ? o.playing.map((s) => `<div class="item"><div class="body"><div class="title">${M.esc(s.title)}</div>
          <div class="sub">${M.esc(s.user)} · ${M.esc(s.state)} · ${M.esc(s.decision || '')} ${M.esc(s.quality || '')}</div>
          <div class="bar"><i style="width:${Number(s.progress) || 0}%"></i></div></div></div>`).join('')
      : '<div class="empty">Nothing playing right now.</div>';
    return `<h1>Overview</h1><p class="lede">Everything on jerry at a glance.</p>
      <div class="grid">
        <a class="card" href="#/requests" style="text-decoration:none"><h3>✚ Requests</h3><div class="stat">${o.pendingRequests}<small>pending in Seerr</small></div></a>
        ${disks}
      </div>
      <h2>Apps</h2><div class="grid">${apps}</div>
      <h2>Now playing</h2><div class="list">${playing}</div>`;
  };

  /* ------------------------------------------------------------ Queue */
  const queueRow = (q) => {
    const flagged = q.health && q.health !== 'ok';
    const state = q.state === 'downloading' ? `${q.progress}%${q.timeleft ? ` · ${M.esc(q.timeleft)} left` : ''}` : M.esc(q.state || q.status || '');
    return `<div class="item ${flagged ? 'flag' : ''}">
      ${M.art(q.poster)}
      <div class="body">
        <div class="title">${M.esc(q.title)}</div>
        <div class="sub">${M.esc(q.sub)}</div>
        <div class="meta">${M.tag(q.app, q.label)}${q.quality ? `<span class="tag dim">${M.esc(q.quality)}</span>` : ''}
          <span class="tag dim">${M.esc(q.client || q.protocol || '')}</span><span class="muted small">${state} · ${M.bytes(q.size)}</span></div>
        ${q.state === 'downloading' ? `<div class="bar"><i style="width:${q.progress}%"></i></div>` : ''}
        ${q.messages.map((m) => `<div class="msg">${M.esc(m)}</div>`).join('')}
        <div class="muted small" title="${M.esc(q.release)}" style="white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:3px">${M.esc(q.release)}</div>
      </div>
      <div class="actions">
        <button class="btn sm danger" data-act="q-remove" data-app="${q.app}" data-id="${q.id}" title="Remove, blocklist this release and search for another">Remove &amp; retry</button>
      </div></div>`;
  };

  V.queue = async (params) => {
    const data = await M.get('/queue');
    const filter = params.app || 'all';
    const apps = Object.keys(data);
    const counts = Object.fromEntries(apps.map((a) => [a, Array.isArray(data[a]) ? data[a].length : 0]));
    const chips = ['all', ...apps].map((a) => `<button class="chip ${filter === a ? 'on' : ''}" data-act="filter" data-key="app" data-val="${a}">
      ${a === 'all' ? 'All' : M.esc({ sonarr: 'Sonarr', radarr: 'Radarr', radarr4k: 'Radarr 4K' }[a])} ${a === 'all' ? Object.values(counts).reduce((x, y) => x + y, 0) : counts[a]}</button>`).join('');
    let rows = [];
    for (const a of apps) {
      if (filter !== 'all' && filter !== a) continue;
      if (!Array.isArray(data[a])) { rows.push(M.svcErr(data[a])); continue; }
      rows.push(...data[a].map(queueRow));
    }
    const flagged = apps.flatMap((a) => (Array.isArray(data[a]) ? data[a] : [])).filter((q) => q.health && q.health !== 'ok').length;
    return `<h1>Queue</h1><p class="lede">Downloads from each app, tagged by where they came from${flagged ? ` · <b style="color:var(--amber)">${flagged} need attention</b>` : ''}.</p>
      <div class="chips">${chips}</div>
      <div class="list">${rows.join('') || '<div class="empty">Nothing downloading.</div>'}</div>`;
  };

  /* ------------------------------------------------------------ Calendar */
  V.calendar = async (params) => {
    const days = Number(params.days || 14);
    const { items } = await M.get(`/calendar?days=${days}`);
    const groups = {};
    for (const it of items) (groups[M.dayKey(it.when)] = groups[M.dayKey(it.when)] || []).push(it);
    const today = M.dayKey(new Date());
    const html = Object.keys(groups).sort().map((k) => `<div class="day ${k === today ? 'today' : ''}">
      <div class="dh">${M.esc(M.dayLabel(groups[k][0].when))}</div><div class="list">
      ${groups[k].map((it) => `<div class="item">${M.art(it.poster)}<div class="body">
        <div class="title">${M.esc(it.title)}</div><div class="sub">${M.esc(it.sub)}</div>
        <div class="meta">${M.tag(it.app, it.label)}${it.app === 'sonarr' ? `<span class="muted small">${M.time(it.when)}${it.network ? ' · ' + M.esc(it.network) : ''}</span>` : ''}
          ${it.hasFile ? '<span class="tag ok">downloaded</span>' : it.monitored ? '' : '<span class="tag dim">unmonitored</span>'}</div>
      </div></div>`).join('')}</div></div>`).join('');
    const chips = [7, 14, 30].map((d) => `<button class="chip ${days === d ? 'on' : ''}" data-act="filter" data-key="days" data-val="${d}">${d} days</button>`).join('');
    return `<h1>Calendar</h1><p class="lede">What's airing and releasing.</p><div class="chips">${chips}</div>
      ${html || '<div class="empty">Nothing scheduled.</div>'}`;
  };

  /* ------------------------------------------------------------ Requests */
  V.requests = async (params) => {
    const filter = params.filter || 'pending';
    const { items } = await M.get(`/requests?filter=${filter}&take=40`);
    const statusTag = (r) => {
      if (r.status === 'pending') return '<span class="tag warn">pending approval</span>';
      if (r.status === 'declined') return '<span class="tag err">declined</span>';
      if (r.mediaStatus === 'available') return '<span class="tag ok">available</span>';
      return `<span class="tag dim">${M.esc(r.mediaStatus)}</span>`;
    };
    const rows = items.map((r) => `<div class="item">${M.art(r.poster)}<div class="body">
      <div class="title">${M.esc(r.title)} <span class="muted">${M.esc(r.year)}</span></div>
      <div class="sub">${M.esc(r.by)} · ${M.ago(r.created)}${r.type === 'tv' && r.seasons.length ? ` · season${r.seasons.length > 1 ? 's' : ''} ${r.seasons.join(', ')}` : ''}</div>
      <div class="meta">${statusTag(r)}<span class="tag dim">${r.type === 'tv' ? 'TV' : 'Movie'}${r.is4k ? ' · 4K' : ''}</span></div></div>
      ${r.status === 'pending' ? `<div class="actions"><button class="btn sm primary" data-act="req" data-id="${r.id}" data-do="approve">Approve</button>
        <button class="btn sm danger" data-act="req" data-id="${r.id}" data-do="decline">Decline</button></div>` : ''}</div>`).join('');
    const chips = [['pending', 'Pending'], ['processing', 'Processing'], ['available', 'Available'], ['all', 'All']]
      .map(([k, l]) => `<button class="chip ${filter === k ? 'on' : ''}" data-act="filter" data-key="filter" data-val="${k}">${l}</button>`).join('');
    return `<h1>Requests</h1><p class="lede">From Seerr — everyone else requests there.</p><div class="chips">${chips}</div>
      <div class="list">${rows || '<div class="empty">No requests here.</div>'}</div>`;
  };

  /* ------------------------------------------------------------ Activity */
  V.activity = async () => {
    const { playing, history } = await M.get('/activity');
    const now = playing.map((s) => `<div class="item"><div class="body"><div class="title">${M.esc(s.title)}</div>
      <div class="sub">${M.esc(s.user)} on ${M.esc(s.player)} · ${M.esc(s.state)}</div>
      <div class="meta"><span class="tag ${s.decision === 'transcode' ? 'warn' : 'ok'}">${M.esc(s.decision || 'direct')}</span>
        <span class="tag dim">${M.esc(s.quality || '')}</span></div>
      <div class="bar"><i style="width:${Number(s.progress) || 0}%"></i></div></div></div>`).join('');
    const hist = history.map((h) => `<div class="item"><div class="body"><div class="title">${M.esc(h.title)}</div>
      <div class="sub">${M.esc(h.user)} · ${M.ago(h.when)}${h.watched === 1 ? ' · watched' : ''}</div></div></div>`).join('');
    return `<h1>Activity</h1><p class="lede">From Tautulli.</p>
      <h2>Now playing</h2><div class="list">${now || '<div class="empty">Nothing playing.</div>'}</div>
      <h2>Recently watched</h2><div class="list">${hist || '<div class="empty">No history.</div>'}</div>`;
  };

  /* ------------------------------------------------------------ Library */
  V.library = async () => {
    const { recent, missing } = await M.get('/library');
    const labels = { sonarr: 'Sonarr', radarr: 'Radarr', radarr4k: 'Radarr 4K' };
    const cards = Object.entries(missing).map(([a, v]) => `<div class="card"><h3>${M.tag(a, labels[a])}</h3>
      <div class="stats"><div><div class="stat">${v.missing ?? '–'}</div><div class="muted small">missing</div></div>
      <div><div class="stat">${v.cutoff ?? '–'}</div><div class="muted small">below cutoff</div></div></div></div>`).join('');
    const rows = recent.map((r) => `<div class="item">${M.art(r.poster)}<div class="body"><div class="title">${M.esc(r.title)}</div>
      <div class="sub">${M.esc(r.sub)}</div><div class="meta">${M.tag(r.app, r.label)}${r.quality ? `<span class="tag dim">${M.esc(r.quality)}</span>` : ''}
      <span class="muted small">${M.ago(r.when)}</span></div></div></div>`).join('');
    return `<h1>Library</h1><p class="lede">What's missing, and what just arrived.</p>
      <div class="grid">${cards}</div><h2>Recently imported</h2><div class="list">${rows || '<div class="empty">Nothing yet.</div>'}</div>`;
  };

  /* ------------------------------------------------------------ Settings */
  V.settings = async () => {
    const [links, me] = await Promise.all([M.get('/links'), M.api('/auth/me')]);
    const theme = localStorage.getItem('mq-theme') || 'auto';
    const linkRows = Object.entries(links).map(([k, l]) => `<a class="item" href="${M.esc(l.url)}" target="_blank" rel="noopener" style="text-decoration:none">
      <div class="body"><div class="title">${M.esc(l.label)}</div><div class="sub">${M.esc(l.url)}</div></div><span class="muted">↗</span></a>`).join('');
    return `<h1>Settings</h1><p class="lede">Signed in as <b>${M.esc(me.user)}</b>.</p>
      <h2>Theme</h2><div class="chips">${['auto', 'dark', 'light'].map((t) => `<button class="chip ${theme === t ? 'on' : ''}" data-act="theme" data-val="${t}">${t}</button>`).join('')}</div>
      <h2>Open the full apps</h2><div class="list">${linkRows || '<div class="empty">No links configured.</div>'}</div>
      <h2>Account</h2><button class="btn danger" data-act="logout">Sign out</button>`;
  };

  /* ------------------------------------------------------------ Sign-in */
  V.signin = () => `<div class="signin">
    <div class="mark"><svg viewBox="0 0 32 32" width="64" height="64" aria-hidden="true"><rect x="4" y="8" width="24" height="17" rx="3" fill="none" stroke="currentColor" stroke-width="2.6"/><path d="M10 4l6 4 6-4" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/><path d="M13.5 13v7l6-3.5z" fill="currentColor"/></svg></div>
    <h1>Marquee</h1><p>Sign in with Plex to continue.</p>
    <button class="btn primary" data-act="signin" style="padding:11px 20px;font-size:15px">Sign in with Plex</button>
    <p id="signin-status" class="small"></p></div>`;
})();
