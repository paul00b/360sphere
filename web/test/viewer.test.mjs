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

// correction d'assiette : aperçu immédiat, puis recalage des portails sur leur point d'image
await page.evaluate(() => window.app.setMode('view'));
const beforeTexture = await page.evaluate(() => {
  const h = window.app.state.viewer.dataHelper;
  return h.sphericalCoordsToTextureCoords({ yaw: 1.2, pitch: 0.1 });
});
const reanchored = JSON.parse(await page.evaluate(() => window.app.reanchorPortals(0.12, -0.05)));
await page.waitForTimeout(300);
const applied = await page.evaluate(() => {
  const c = window.app.state.viewer.renderer.sphereCorrection;
  return { tilt: c.x, roll: c.z, pan: c.y };
});
check(Math.abs(Math.abs(applied.tilt) - 0.12) < 1e-3 && Math.abs(Math.abs(applied.roll) - 0.05) < 1e-3,
  `previewCorrection applique la rotation au maillage (${JSON.stringify(applied)})`);
const p1 = reanchored.find(r => r.portalId === 'p1');
check(!!p1 && (Math.abs(p1.yaw - 1.2) > 0.01 || Math.abs(p1.pitch - 0.1) > 0.01),
  `reanchorPortals déplace le portail avec l'image (${p1 ? p1.yaw.toFixed(3) + ', ' + p1.pitch.toFixed(3) : 'absent'})`);
const afterTexture = await page.evaluate((pos) => {
  const h = window.app.state.viewer.dataHelper;
  return h.sphericalCoordsToTextureCoords({ yaw: pos.yaw, pitch: pos.pitch });
}, p1);
// C'est tout l'intérêt du recalage : le portail désigne toujours le même pixel de l'image.
check(Math.abs(afterTexture.textureX - beforeTexture.textureX) < 3 &&
  Math.abs(afterTexture.textureY - beforeTexture.textureY) < 3,
  `le portail reste sur le même point d'image (${beforeTexture.textureX},${beforeTexture.textureY} -> ` +
  `${afterTexture.textureX},${afterTexture.textureY})`);

// commitCorrection : la correction est mémorisée dans le nœud, donc conservée à la navigation
await page.evaluate(() => window.app.commitCorrection({
  id: 'A', name: 'Salon', panorama: window.app.state.nodes.A.panorama,
  defaultYaw: 0, defaultPitch: 0, correctionPitch: 0.12, correctionRoll: -0.05,
  links: window.app.state.nodes.A.links
}));
await page.waitForTimeout(1200);
const stored = await page.evaluate(() => window.app.state.tour.datasource.nodes.A.sphereCorrection);
check(!!stored && Math.abs(stored.tilt - 0.12) < 1e-6 && Math.abs(stored.roll + 0.05) < 1e-6,
  `commitCorrection enregistre l'assiette dans le nœud (${JSON.stringify(stored)})`);
await page.screenshot({ path: path.join(shots, '6-assiette-corrigee.png') });

await browser.close();
server.close();
console.log(failures.length ? `\n${failures.length} ÉCHEC(S)` : '\nTOUS LES TESTS PASSENT');
process.exit(failures.length ? 1 : 0);
