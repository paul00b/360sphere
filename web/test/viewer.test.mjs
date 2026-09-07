// Test d'intégration du viewer (Photo Sphere Viewer + glue) dans Chromium headless.
// Usage : node test/viewer.test.mjs <dossier_panoramas> <dossier_screenshots>
import { chromium } from 'playwright-core';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const assets = path.resolve(here, '../../app/src/main/assets');
const panos = path.resolve(process.argv[2] || '/tmp/panos');
const shots = path.resolve(process.argv[3] || '/tmp/shots');
fs.mkdirSync(shots, { recursive: true });
const CHROME = process.env.CHROME || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome';

const MIME = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.jpg': 'image/jpeg', '.png': 'image/png' };
const server = http.createServer((req, res) => {
  const url = decodeURIComponent(req.url.split('?')[0]);
  let file = null;
  if (url.startsWith('/viewer/')) file = path.join(assets, url);
  else if (url.startsWith('/spheres/')) { const [, , id, name] = url.split('/'); file = path.join(panos, id === 'A' ? 'room_a.jpg' : 'room_b.jpg'); if (name === 'thumb.jpg') file = file; }
  if (!file || !fs.existsSync(file)) { res.writeHead(404); res.end('not found'); return; }
  res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Access-Control-Allow-Origin': '*' });
  fs.createReadStream(file).pipe(res);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

const failures = [];
function check(cond, msg) { console.log((cond ? 'PASS ' : 'FAIL ') + msg); if (!cond) failures.push(msg); }

const browser = await chromium.launch({
  executablePath: CHROME,
  args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--no-sandbox']
});
const page = await browser.newPage({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2, hasTouch: true, isMobile: true });
page.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') console.log('[console:' + m.type() + ']', m.text()); });
page.on('pageerror', e => console.log('[pageerror]', e.message));
const events = [];
await page.exposeFunction('__evt', (name, args) => events.push({ name, args }));
await page.addInitScript(() => document.addEventListener('sphere360', e => window.__evt(e.detail.name, e.detail.args)));
const waitEvent = (name, timeout = 15000) => page.waitForFunction(
  () => true, null, { timeout: 0 }).then(async () => {
  const t0 = Date.now();
  while (Date.now() - t0 < timeout) { const e = events.find(ev => ev.name === name); if (e) return e; await page.waitForTimeout(50); }
  throw new Error('timeout waiting ' + name);
});
const clearEvents = () => { events.length = 0; };

await page.goto(base + '/viewer/index.html');
await page.waitForFunction(() => window.app && window.PSV);
await waitEvent('onReady');
check(true, 'page chargée, PSV présent, onReady émis');

const tour = {
  mode: 'view', startId: 'A',
  nodes: [
    { id: 'A', name: 'Salon', panorama: base + '/spheres/A/equirect.jpg', thumbnail: base + '/spheres/A/thumb.jpg', defaultYaw: 0, defaultPitch: 0,
      links: [{ portalId: 'p1', nodeId: 'B', yaw: 1.2, pitch: 0.1, label: 'Cuisine' }] },
    { id: 'B', name: 'Cuisine', panorama: base + '/spheres/B/equirect.jpg', thumbnail: base + '/spheres/B/thumb.jpg', defaultYaw: 0.3, defaultPitch: 0,
      links: [{ portalId: 'p2', nodeId: 'A', yaw: -2.0, pitch: -0.05, label: 'Salon' }] }
  ]
};
await page.evaluate((t) => window.app.load(t), tour);
await waitEvent('onViewerReady');
await page.waitForTimeout(800);
check(true, 'onViewerReady émis (panorama chargé, WebGL OK)');
const glOk = await page.evaluate(() => !!document.querySelector('#viewer canvas'));
check(glOk, 'canvas WebGL présent');
await page.screenshot({ path: path.join(shots, '1-vue-initiale.png') });

let nb = await page.evaluate(() => window.app.state.markers.getNbMarkers());
check(nb === 1, `1 marqueur de libellé sur le nœud A (trouvé ${nb})`);
const chipText = await page.evaluate(() => Array.from(document.querySelectorAll('.portal-chip-text')).map(e => e.textContent));
check(chipText.length === 1 && chipText[0] === 'Cuisine', `libellé visible « Cuisine » (${JSON.stringify(chipText)})`);
const arrows = await page.evaluate(() => document.querySelectorAll('.psv-virtual-tour-arrows, .psv-virtual-tour-arrow, [class*="virtual-tour"]').length);
check(arrows > 0, `flèches 3D rendues (${arrows} éléments virtual-tour)`);

// convention de yaw : +90° doit montrer le repère « +90° droite » (partie droite de l'image)
await page.evaluate(() => window.app.state.viewer.rotate({ yaw: Math.PI / 2, pitch: 0 }));
await page.waitForTimeout(300);
await page.screenshot({ path: path.join(shots, '2-yaw-plus-90.png') });

// navigation via le libellé (clic DOM réel)
await page.evaluate(() => window.app.state.viewer.rotate({ yaw: 1.2, pitch: 0.1 }));
await page.waitForTimeout(400);
await page.screenshot({ path: path.join(shots, '3-portail-centre.png') });
clearEvents();
await page.click('.portal-chip');
const changed = await waitEvent('onNodeChanged');
check(changed.args[0] === 'B', `clic sur le libellé → nœud B (${changed.args[0]})`);
await page.waitForTimeout(1500);
const pos = await page.evaluate(() => window.app.state.viewer.getPosition());
check(Math.abs(pos.yaw - 1.2) < 0.05 && Math.abs(pos.pitch - 0.1) < 0.05, `vue d'arrivée = direction du portail (yaw=${pos.yaw.toFixed(3)}, pitch=${pos.pitch.toFixed(3)})`);
await page.screenshot({ path: path.join(shots, '4-apres-navigation-B.png') });
nb = await page.evaluate(() => window.app.state.markers.getNbMarkers());
check(nb === 1, `1 marqueur sur B (${nb})`);

// retour arrière
clearEvents();
const back = await page.evaluate(() => window.app.goBack());
check(back === true, 'goBack() → true');
const backEvt = await waitEvent('onNodeChanged');
check(backEvt.args[0] === 'A', `retour au nœud A (${backEvt.args[0]})`);
await page.waitForTimeout(800);
const back2 = await page.evaluate(() => window.app.goBack());
check(back2 === false, 'goBack() sans historique → false');

// mode édition : tap sur la sphère → onSphereTapped
await page.evaluate(() => window.app.setMode('edit'));
clearEvents();
await page.mouse.click(206, 500);
const tapped = await waitEvent('onSphereTapped');
check(typeof tapped.args[0] === 'number' && typeof tapped.args[1] === 'number', `tap édition → yaw/pitch (${tapped.args.map(a => a.toFixed(3)).join(', ')})`);
await page.screenshot({ path: path.join(shots, '5-mode-edition.png') });

// mise à jour d'un nœud : nouveau portail → 2 libellés sans recharger le panorama
await page.evaluate(() => window.app.updateNode({ id: 'A', name: 'Salon', panorama: window.app.state.nodes.A.panorama, defaultYaw: 0, defaultPitch: 0,
  links: [{ portalId: 'p1', nodeId: 'B', yaw: 1.2, pitch: 0.1, label: 'Cuisine' }, { portalId: 'p3', nodeId: 'B', yaw: -0.4, pitch: 0.0, label: 'Terrasse' }] }));
await page.waitForTimeout(300);
nb = await page.evaluate(() => window.app.state.markers.getNbMarkers());
check(nb === 2, `updateNode → 2 marqueurs (${nb})`);
// getPosition
const gp = JSON.parse(await page.evaluate(() => window.app.getPosition()));
check(typeof gp.yaw === 'number', 'getPosition() renvoie un JSON yaw/pitch');
// tap sur un libellé en mode édition → onPortalTapped
await page.evaluate(() => window.app.state.viewer.rotate({ yaw: -0.4, pitch: 0 }));
await page.waitForTimeout(300);
clearEvents();
await page.click('.portal-chip:has-text("Terrasse")');
const pt = await waitEvent('onPortalTapped');
check(pt.args[0] === 'p3', `tap libellé en édition → onPortalTapped(${pt.args[0]})`);

await browser.close();
server.close();
console.log(failures.length ? `\n${failures.length} ÉCHEC(S)` : '\nTOUS LES TESTS PASSENT');
process.exit(failures.length ? 1 : 0);
