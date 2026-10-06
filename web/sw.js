/* App-shell cache only. API and auth calls always go to the network (live data, session cookies). */
const CACHE = 'marquee-v1';
const SHELL = ['./', './index.html', './css/app.css', './js/api.js', './js/ui.js', './js/views.js', './js/app.js',
  './manifest.json', './icons/icon.svg'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin || /\/(api|auth)\//.test(url.pathname)) return;
  // network first so updates land immediately; cache is the offline fallback
  e.respondWith(fetch(e.request).then((res) => {
    const copy = res.clone();
    caches.open(CACHE).then((c) => c.put(e.request, copy));
    return res;
  }).catch(() => caches.match(e.request)));
});
