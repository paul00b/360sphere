/*
 * Glue Photo Sphere Viewer ↔ application Android.
 *   Android → JS : window.app.load(data), updateNode(node), setMode(mode), getPosition(), goBack(),
 *                  goToNode(id), previewCorrection(pitch, roll), reanchorPortals(pitch, roll),
 *                  commitCorrection(node)
 *   JS → Android : Android.onReady(), onViewerReady(), onNodeChanged(id), onSphereTapped(yaw, pitch),
 *                  onPortalTapped(portalId), onError(msg), log(msg)
 * Sans pont Android (tests navigateur), les événements sont aussi émis en CustomEvent "sphere360".
 */
(function () {
  'use strict';

  var bridge = window.Android || null;
  var PSV = window.PSV;

  var state = {
    viewer: null, tour: null, markers: null,
    mode: 'view', nodes: {}, currentId: null, history: [], navigatingBack: false
  };

  function notify(name) {
    var args = Array.prototype.slice.call(arguments, 1);
    try {
      if (bridge && typeof bridge[name] === 'function') bridge[name].apply(bridge, args);
    } catch (e) { console.error('bridge ' + name, e); }
    try {
      document.dispatchEvent(new CustomEvent('sphere360', { detail: { name: name, args: args } }));
    } catch (e) { /* ignore */ }
  }

  function log(msg) { notify('log', String(msg)); console.log(msg); }

  function showMessage(text) {
    var el = document.getElementById('message');
    if (!text) { el.hidden = true; return; }
    el.textContent = text; el.hidden = false;
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function labelHtml(label) {
    return '<div class="portal-chip"><span class="portal-chip-icon"></span>' +
      '<span class="portal-chip-text">' + escapeHtml(label || '') + '</span></div>';
  }

  /**
   * Correction d'assiette : rotation du maillage de la sphère, appliquée par le GPU au rendu.
   * Redresser un horizon penché ne demande donc aucun réassemblage de l'image.
   */
  function correctionOf(n) {
    return { pan: 0, tilt: n.correctionPitch || 0, roll: n.correctionRoll || 0 };
  }

  /** Nœud applicatif → nœud VirtualTourPlugin (liens = flèches 3D, marqueurs = libellés visibles). */
  function toPsvNode(n, ids) {
    var links = (n.links || []).filter(function (l) { return ids[l.nodeId]; });
    return {
      id: n.id,
      name: n.name || '',
      panorama: n.panorama,
      thumbnail: n.thumbnail,
      sphereCorrection: correctionOf(n),
      data: { defaultYaw: n.defaultYaw || 0, defaultPitch: n.defaultPitch || 0 },
      links: links.map(function (l) {
        return { nodeId: l.nodeId, position: { yaw: l.yaw, pitch: l.pitch }, data: { portalId: l.portalId, label: l.label } };
      }),
      markers: links.map(function (l) {
        return {
          id: 'portal-' + l.portalId,
          position: { yaw: l.yaw, pitch: l.pitch },
          html: labelHtml(l.label),
          anchor: 'center center',
          className: 'portal-marker',
          zIndex: 10,
          data: { portalId: l.portalId, nodeId: l.nodeId }
        };
      })
    };
  }

  function applyMode() {
    document.body.classList.toggle('edit', state.mode === 'edit');
  }

  function init(data) {
    if (typeof data === 'string') data = JSON.parse(data);
    state.mode = data.mode || 'view';
    state.nodes = {};
    var ids = {};
    (data.nodes || []).forEach(function (n) { state.nodes[n.id] = n; ids[n.id] = true; });
    var nodes = (data.nodes || []).map(function (n) { return toPsvNode(n, ids); });
    var start = state.nodes[data.startId] || (data.nodes || [])[0];
    if (!start) { showMessage('Aucune sphère à afficher'); notify('onError', 'no node'); return; }

    if (state.viewer) { try { state.viewer.destroy(); } catch (e) { /* ignore */ } state.viewer = null; }
    state.history = [];
    state.currentId = start.id;
    showMessage(null);

    var viewer = new PSV.Viewer({
      container: document.getElementById('viewer'),
      navbar: false,
      defaultYaw: start.defaultYaw || 0,
      defaultPitch: start.defaultPitch || 0,
      sphereCorrection: correctionOf(start),
      defaultZoomLvl: 25,
      minFov: 30,
      maxFov: 100,
      mousewheel: true,
      touchmoveTwoFingers: false,
      moveInertia: true,
      loadingTxt: 'Chargement…',
      canvasBackground: '#000000',
      plugins: [
        [PSV.MarkersPlugin, {}],
        [PSV.VirtualTourPlugin, {
          renderMode: '3d',
          nodes: nodes,
          startNodeId: start.id,
          preload: true,
          showLinkTooltip: false,
          transitionOptions: { speed: '20rpm', effect: 'fade', rotation: true, showLoader: false },
          arrowsPosition: { minPitch: 0.3, maxPitch: 1.3, linkOverlapAngle: 0.6 }
        }]
      ]
    });
    state.viewer = viewer;
    state.tour = viewer.getPlugin(PSV.VirtualTourPlugin);
    state.markers = viewer.getPlugin(PSV.MarkersPlugin);

    viewer.addEventListener('ready', function () { notify('onViewerReady'); }, { once: true });
    viewer.addEventListener('panorama-error', function (e) {
      showMessage('Impossible de charger la sphère.');
      notify('onError', (e && e.error && e.error.message) || 'panorama-error');
    });
    viewer.addEventListener('click', function (e) {
      if (state.mode !== 'edit') return;
      if (e.data.rightclick) return;
      notify('onSphereTapped', e.data.yaw, e.data.pitch);
    });
    state.tour.addEventListener('node-changed', function (e) {
      var from = e.data && e.data.fromNode ? e.data.fromNode.id : null;
      if (from && !state.navigatingBack) state.history.push(from);
      state.navigatingBack = false;
      state.currentId = e.node.id;
      notify('onNodeChanged', e.node.id);
    });
    state.markers.addEventListener('select-marker', function (e) {
      var d = (e.marker && e.marker.config && e.marker.config.data) || {};
      if (!d.portalId) return;
      if (state.mode === 'edit') { notify('onPortalTapped', d.portalId); return; }
      goToPortal(d.portalId);
    });
    applyMode();
  }

  function goToPortal(portalId) {
    var node = state.tour.getCurrentNode();
    var link = (node.links || []).filter(function (l) { return l.data && l.data.portalId === portalId; })[0];
    if (!link) return;
    state.tour.setCurrentNode(link.nodeId, {}, link);
  }

  window.app = {
    load: function (data) {
      try { init(data); } catch (e) { console.error(e); showMessage('Erreur du viewer : ' + e.message); notify('onError', e.message); }
    },
    updateNode: function (n) {
      if (typeof n === 'string') n = JSON.parse(n);
      if (!state.tour) return;
      state.nodes[n.id] = n;
      var ids = {}; Object.keys(state.nodes).forEach(function (k) { ids[k] = true; });
      var psv = toPsvNode(n, ids);
      // Volontairement sans sphereCorrection : le plugin recharge le panorama dès qu'on la lui
      // passe, ce qui ferait clignoter la sphère à chaque portail ajouté.
      state.tour.updateNode({ id: psv.id, name: psv.name, links: psv.links, markers: psv.markers });
    },

    /** Aperçu immédiat d'une correction d'assiette, sans recharger ni rien enregistrer. */
    previewCorrection: function (pitch, roll) {
      if (!state.viewer) return;
      try {
        state.viewer.setOption('sphereCorrection', { pan: 0, tilt: pitch || 0, roll: roll || 0 });
      } catch (e) { console.error('sphereCorrection', e); }
    },

    /**
     * Recalcule la position des portails du nœud courant pour une nouvelle correction d'assiette,
     * et applique cette correction à l'affichage.
     *
     * Photo Sphere Viewer place les marqueurs dans le repère du monde, alors que la correction fait
     * tourner le maillage de la sphère : redresser l'horizon décale donc l'image sous les portails,
     * qui ne pointent plus la porte qu'ils désignaient. On les rattache à ce qui compte vraiment,
     * un point de l'image, en passant par les coordonnées de texture avant puis après le
     * changement. Le calcul est fait par le viewer lui-même, ce qui évite de redériver sa
     * convention d'angles d'Euler.
     */
    reanchorPortals: function (pitch, roll) {
      if (!state.viewer) return '[]';
      var appNode = state.nodes[state.currentId];
      var links = (appNode && appNode.links) || [];
      var helper = state.viewer.dataHelper;
      var anchors = [];
      links.forEach(function (l) {
        try {
          var t = helper.sphericalCoordsToTextureCoords({ yaw: l.yaw, pitch: l.pitch });
          // L'adaptateur renvoie des coordonnées indéfinies pour un point hors de l'image
          // recadrée : dans ce cas il n'y a pas de pixel auquel rattacher le portail.
          if (t.textureX === undefined || t.textureY === undefined) return;
          anchors.push({ portalId: l.portalId, textureX: t.textureX, textureY: t.textureY });
        } catch (e) { /* pas de données de texture : le portail reste où il est */ }
      });
      window.app.previewCorrection(pitch, roll);
      var out = [];
      anchors.forEach(function (a) {
        try {
          var p = helper.textureCoordsToSphericalCoords({ textureX: a.textureX, textureY: a.textureY });
          out.push({ portalId: a.portalId, yaw: p.yaw, pitch: p.pitch });
        } catch (e) { /* ignore */ }
      });
      return JSON.stringify(out);
    },

    /** Enregistre la correction dans le nœud : le plugin recharge le panorama redressé. */
    commitCorrection: function (n) {
      if (typeof n === 'string') n = JSON.parse(n);
      if (!state.tour) return;
      state.nodes[n.id] = n;
      var ids = {}; Object.keys(state.nodes).forEach(function (k) { ids[k] = true; });
      var psv = toPsvNode(n, ids);
      state.tour.updateNode({
        id: psv.id, name: psv.name, links: psv.links, markers: psv.markers,
        sphereCorrection: psv.sphereCorrection
      });
    },
    setMode: function (mode) { state.mode = mode; applyMode(); },
    getPosition: function () {
      if (!state.viewer) return null;
      var p = state.viewer.getPosition();
      return JSON.stringify({ yaw: p.yaw, pitch: p.pitch });
    },
    goBack: function () {
      if (!state.tour || state.history.length === 0) return false;
      var prev = state.history.pop();
      state.navigatingBack = true;
      state.tour.setCurrentNode(prev, { rotation: false, effect: 'fade' });
      return true;
    },
    goToNode: function (id) { if (state.tour) state.tour.setCurrentNode(id); },
    goToPortal: goToPortal,
    state: state
  };

  window.addEventListener('load', function () {
    if (!PSV) { showMessage('Bibliothèque du viewer introuvable'); notify('onError', 'PSV missing'); return; }
    notify('onReady');
  });
})();
