/* API client. The app lives under the server's base path (e.g. /hub/), so every call is relative to it. */
const M = window.M = window.M || {};

M.base = location.pathname.replace(/\/[^/]*$/, '');  // "/hub"

M.api = async function (path, opts = {}) {
  const res = await fetch(M.base + path, {
    method: opts.method || 'GET',
    headers: opts.body ? { 'Content-Type': 'application/json' } : {},
    body: opts.body ? JSON.stringify(opts.body) : undefined,
    credentials: 'same-origin',
  });
  if (res.status === 401) { M.signedOut(); throw new Error('signed out'); }
  let data = null;
  try { data = await res.json(); } catch (_) { /* empty body */ }
  if (!res.ok) throw new Error((data && (data.error || data.detail)) || `HTTP ${res.status}`);
  return data;
};

M.get = (p) => M.api('/api' + p);
M.post = (p, body) => M.api('/api' + p, { method: 'POST', body: body || {} });
