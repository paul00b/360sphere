// Parcours de la visite de démo (3 pièces) dans Chromium headless : navigation par portails
// dans les deux sens, vue d'arrivée, captures d'écran face à chaque portail.
import { chromium } from 'playwright-core';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const assets = path.resolve(here, '../../app/src/main/assets');
const shots = path.resolve(process.argv[2] || '/tmp/shots-demo');
fs.mkdirSync(shots, { recursive: true });
const CHROME = process.env.CHROME || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome';
const demo = JSON.parse(fs.readFileSync(path.join(assets, 'demo/demo.json'), 'utf8'));
const byId = Object.fromEntries(demo.spheres.map(s => [s.id, s]));

const MIME = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.jpg': 'image/jpeg', '.png': 'image/png' };
const server = http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split('?')[0]);
  let file = null;
  if (url.startsWith('/viewer/')) file = path.join(assets, url);
  else if (url.startsWith('/spheres/')) { const [, , id, name] = url.split('/'); const s = byId[id]; if (s) file = path.join(assets, 'demo', name === 'thumb.jpg' ? s.thumb : s.file); }
  if (!file || !fs.existsSync(file)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

const failures = [];
const check = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) failures.push(m); };

const browser = await chromium.launch({ executablePath: CHROME, args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--no-sandbox'] });
const page = await browser.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true });
page.on('pageerror', e => console.log('[pageerror]', e.message));
const events = [];
await page.exposeFunction('__evt', (name, args) => events.push({ name, args }));
await page.addInitScript(() => document.addEventListener('sphere360', e => window.__evt(e.detail.name, e.detail.args)));
const waitEvent = async (name, timeout = 15000) => { const t0 = Date.now(); while (Date.now() - t0 < timeout) { const e = events.find(ev => ev.name === name); if (e) return e; await page.waitForTimeout(50); } throw new Error('timeout ' + name); };
const clear = () => { events.length = 0; };

// même transformation que ViewerActivity.nodeJson
const tour = { mode: 'view', startId: 'demo-salon', nodes: demo.spheres.map(s => ({
  id: s.id, name: s.name, panorama: `${base}/spheres/${s.id}/equirect.jpg`, thumbnail: `${base}/spheres/${s.id}/thumb.jpg`,
  defaultYaw: s.defaultYaw, defaultPitch: s.defaultPitch,
  links: s.portals.map(p => ({ portalId: p.id, nodeId: p.to, yaw: p.yaw, pitch: p.pitch, label: p.label })) })) };

await page.goto(base + '/viewer/index.html');
await page.waitForFunction(() => window.app && window.PSV);
await page.evaluate(t => window.app.load(t), tour);
await waitEvent('onViewerReady');
await page.waitForTimeout(600);
await page.screenshot({ path: path.join(shots, 'd1-salon-entree.png') });
let nb = await page.evaluate(() => window.app.state.markers.getNbMarkers());
check(nb === 2, `Salon : 2 libellés (${nb})`);

const rotate = (yaw, pitch) => page.evaluate(([y, p]) => window.app.state.viewer.rotate({ yaw: y, pitch: p }), [yaw, pitch]);
const pos = () => page.evaluate(() => window.app.state.viewer.getPosition());
const norm = a => Math.atan2(Math.sin(a), Math.cos(a));

// Salon → Cuisine (porte est, +90°)
const pSC = byId['demo-salon'].portals.find(p => p.label === 'Cuisine');
await rotate(pSC.yaw, pSC.pitch); await page.waitForTimeout(400);
await page.screenshot({ path: path.join(shots, 'd2-salon-vers-cuisine.png') });
clear(); await page.click('.portal-chip:has-text("Cuisine")');
let ev = await waitEvent('onNodeChanged'); check(ev.args[0] === 'demo-cuisine', `Salon → Cuisine (${ev.args[0]})`);
await page.waitForTimeout(1500);
let p = await pos(); check(Math.abs(norm(p.yaw - pSC.yaw)) < 0.05, `arrivée Cuisine face à l'est (yaw=${p.yaw.toFixed(2)})`);
await page.screenshot({ path: path.join(shots, 'd3-cuisine-arrivee.png') });
nb = await page.evaluate(() => window.app.state.markers.getNbMarkers()); check(nb === 1, `Cuisine : 1 libellé (${nb})`);

// Cuisine → Salon (porte ouest, −90°) : on ressort face à l'ouest, la porte de la chambre est en face
const pCS = byId['demo-cuisine'].portals[0];
await rotate(pCS.yaw, pCS.pitch); await page.waitForTimeout(400);
await page.screenshot({ path: path.join(shots, 'd4-cuisine-vers-salon.png') });
clear(); await page.click('.portal-chip:has-text("Salon")');
ev = await waitEvent('onNodeChanged'); check(ev.args[0] === 'demo-salon', `Cuisine → Salon (${ev.args[0]})`);
await page.waitForTimeout(1500);
p = await pos(); check(Math.abs(norm(p.yaw - pCS.yaw)) < 0.05, `retour Salon face à l'ouest (yaw=${p.yaw.toFixed(2)})`);
await page.screenshot({ path: path.join(shots, 'd5-salon-retour-face-chambre.png') });
const chips = await page.evaluate(() => Array.from(document.querySelectorAll('.portal-chip-text')).map(e => e.textContent));
check(chips.includes('Chambre') && chips.includes('Cuisine'), `libellés du Salon : ${JSON.stringify(chips)}`);

// Salon → Chambre → Salon
clear(); await page.click('.portal-chip:has-text("Chambre")');
ev = await waitEvent('onNodeChanged'); check(ev.args[0] === 'demo-chambre', `Salon → Chambre (${ev.args[0]})`);
await page.waitForTimeout(1500);
await page.screenshot({ path: path.join(shots, 'd6-chambre-arrivee.png') });
const pChS = byId['demo-chambre'].portals[0];
await rotate(pChS.yaw, pChS.pitch); await page.waitForTimeout(400);
await page.screenshot({ path: path.join(shots, 'd7-chambre-vers-salon.png') });
clear(); await page.click('.portal-chip:has-text("Salon")');
ev = await waitEvent('onNodeChanged'); check(ev.args[0] === 'demo-salon', `Chambre → Salon (${ev.args[0]})`);

// historique : 3 retours possibles puis plus rien
let backs = 0; for (let i = 0; i < 5; i++) { const ok = await page.evaluate(() => window.app.goBack()); if (!ok) break; backs++; await page.waitForTimeout(900); }
check(backs === 4, `goBack() x${backs} (attendu 4)`);

await browser.close(); server.close();
console.log(failures.length ? `\n${failures.length} ÉCHEC(S)` : '\nPARCOURS DÉMO OK');
process.exit(failures.length ? 1 : 0);
