import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import { balanceContributions, weightKind, type SceneResult, type SceneTheme, type WeightKind } from "@/lib/decisionScene";

type Selection = { optionId: string; criterionId: string };
type SceneArgs = {
  host: HTMLElement;
  labels: HTMLElement;
  result: SceneResult;
  theme: SceneTheme;
  pair: SceneResult["options"];
  onSelect: (selection: Selection) => void;
  onFailure: () => void;
};
export type DecisionSceneController = {
  select: (optionId: string, criterionId: string) => void;
  setMotion: (enabled: boolean) => void;
  setExplore: (enabled: boolean) => void;
  replay: () => void;
  finish: () => void;
  zoom: (factor: number) => void;
  reset: () => void;
  dispose: () => void;
};
type Marker = { anchor: THREE.Object3D; element: HTMLButtonElement; selection?: Selection; ring?: THREE.Mesh; always: boolean };
type Drop = { mesh: THREE.Group; end: number; at: number; left: number; right: number };
const colors = [0x8e78d5, 0x46a99c];
const vector = (x: number, y: number, z: number) => new THREE.Vector3(x, y, z);

/** Procedural assets only: no runtime CDN, texture requests, external models or fabricated geographic data. */
export function createDecisionScene(args: SceneArgs): DecisionSceneController {
  const { host, labels, result, theme, pair, onSelect, onFailure } = args;
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: "low-power" });
  const scene = new THREE.Scene();
  const geometries = new Set<THREE.BufferGeometry>();
  const materials = new Set<THREE.Material>();
  const markers: Marker[] = [];
  const selectable: THREE.Object3D[] = [];
  const particles: { mesh: THREE.Mesh; curve: THREE.CatmullRomCurve3; phase: number }[] = [];
  const drops: Drop[] = [];
  const camera = new THREE.OrthographicCamera(-11, 11, 8, -8, 0.1, 100);
  let controls: OrbitControls | undefined;
  let observer: ResizeObserver | undefined;
  let visibility: IntersectionObserver | undefined;
  let disposed = false, failed = false, frame = 0, motion = false, exploring = false, visible = true;
  let start = performance.now(), elapsed = 8, previous = start;
  let selected = { optionId: pair[0]?.id ?? "", criterionId: result.criteria[0]?.id ?? "" };
  let beam: THREE.Group | undefined;
  const pans: THREE.Group[] = [];
  const balance = balanceContributions(result, pair[0]?.id ?? "", pair[1]?.id ?? "");
  const animationDuration = theme === "balance" ? 4.8 : 5;
  const tmp = new THREE.Vector3();
  const raycaster = new THREE.Raycaster();
  let down = { x: 0, y: 0 };

  const material = (color: number, metalness = 0, roughness = 0.7) => {
    const m = new THREE.MeshStandardMaterial({ color, metalness, roughness }); materials.add(m); return m;
  };
  const white = material(0xf4f2fa), silver = material(0xc3c4d8, .4, .32), asphalt = material(0x6c748b);
  const accents = colors.map((c) => material(c, .22, .3));
  function mesh(geometry: THREE.BufferGeometry, mat: THREE.Material, parent: THREE.Object3D, x = 0, y = 0, z = 0) {
    geometries.add(geometry); const item = new THREE.Mesh(geometry, mat); item.position.set(x, y, z); item.castShadow = true; item.receiveShadow = true; parent.add(item); return item;
  }
  function box(parent: THREE.Object3D, x: number, y: number, z: number, w: number, h: number, d: number, mat: THREE.Material = white) {
    return mesh(new THREE.BoxGeometry(w, h, d), mat, parent, x, y, z);
  }
  function cylinder(parent: THREE.Object3D, x: number, y: number, z: number, radius: number, height: number, mat: THREE.Material = white) {
    return mesh(new THREE.CylinderGeometry(radius, radius, height, 40), mat, parent, x, y, z);
  }
  function ring(parent: THREE.Object3D, x: number, y: number, z: number, radius: number, color: number) {
    const m = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .65, side: THREE.DoubleSide }); materials.add(m);
    const item = mesh(new THREE.RingGeometry(radius, radius + .045, 64), m, parent, x, y, z); item.rotation.x = -Math.PI / 2; item.castShadow = false; return item;
  }
  function marker(parent: THREE.Object3D, text: string, x: number, y: number, z: number, selection?: Selection, always = false, highlight?: THREE.Mesh) {
    const anchor = new THREE.Object3D(); anchor.position.set(x, y, z); parent.add(anchor);
    const element = document.createElement("button"); element.type = "button"; element.className = always ? "world-marker world-marker-option" : "world-marker";
    element.textContent = text; element.hidden = !always;
    if (selection) { element.addEventListener("click", () => onSelect(selection)); element.setAttribute("aria-label", `${pair.find((o) => o.id === selection.optionId)?.name ?? ""} · ${text}`); }
    else { element.disabled = true; element.classList.add("world-marker-origin"); }
    labels.append(element); markers.push({ anchor, element, selection, ring: highlight, always });
  }
  function road(points: THREE.Vector3[], width: number, color: number, paved: boolean) {
    const curve = new THREE.CatmullRomCurve3(points);
    if (paved) {
      const samples = curve.getPoints(48);
      const vertices: number[] = [], indices: number[] = [], stripes: number[] = [];
      samples.forEach((p, i) => {
        const tangent = curve.getTangent(i / (samples.length - 1));
        const normal = vector(tangent.z, 0, -tangent.x).normalize();
        for (const direction of [-1, 1]) vertices.push(p.x + normal.x * width / 2 * direction, .055, p.z + normal.z * width / 2 * direction);
        if (i < samples.length - 1) { const n = i * 2; indices.push(n, n + 2, n + 1, n + 1, n + 2, n + 3); }
        if (i % 3 === 0) for (const lane of [-.24, .24]) {
          stripes.push(p.x + normal.x * width * lane - tangent.x * .12, .078, p.z + normal.z * width * lane - tangent.z * .12,
            p.x + normal.x * width * lane + tangent.x * .12, .078, p.z + normal.z * width * lane + tangent.z * .12);
        }
      });
      const ribbon = new THREE.BufferGeometry(); ribbon.setAttribute("position", new THREE.Float32BufferAttribute(vertices, 3)); ribbon.setIndex(indices); ribbon.computeVertexNormals();
      // One road mesh and one stripe draw call rather than dozens of overlapping boxes.
      mesh(ribbon, asphalt, scene);
      const stripeGeometry = new THREE.BufferGeometry(); stripeGeometry.setAttribute("position", new THREE.Float32BufferAttribute(stripes, 3)); geometries.add(stripeGeometry);
      const stripeMaterial = new THREE.LineBasicMaterial({ color: 0xf1edf6 }); materials.add(stripeMaterial); scene.add(new THREE.LineSegments(stripeGeometry, stripeMaterial));
    }
    const m = new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: .6, roughness: .3 }); materials.add(m);
    mesh(new THREE.TubeGeometry(curve, 60, .035, 8, false), m, scene);
    for (let i = 0; i < 7; i++) particles.push({ mesh: mesh(new THREE.SphereGeometry(.065, 8, 8), m, scene), curve, phase: i / 7 });
  }
  function building(parent: THREE.Object3D, x: number, z: number, height: number, mat: THREE.Material, selection?: Selection) {
    const body = box(parent, x, height / 2 + .15, z, .86, height, .86, mat);
    box(parent, x, height + .2, z, .94, .1, .94, silver);
    box(parent, x, .12, z, 1.12, .15, 1.12, white);
    for (let floor = .5; floor < height; floor += .38) {
      box(parent, x, floor + .15, z + .437, .68, .08, .012, silver);
      box(parent, x + .437, floor + .15, z, .012, .08, .68, silver);
    }
    if (selection) { body.userData.selection = selection; selectable.push(body); }
  }
  function tree(parent: THREE.Object3D, x: number, z: number, base = 0) {
    cylinder(parent, x, base + .28, z, .055, .5, silver);
    mesh(new THREE.SphereGeometry(.23, 10, 8), material(0xb0cfc1), parent, x, base + .6, z);
  }
  function island(x: number, z: number, side: number) {
    const shape = new THREE.Shape();
    for (let i = 0; i <= 40; i++) {
      const a = i / 40 * Math.PI * 2, r = 1 + Math.sin(a * 3 + side) * .09;
      const px = Math.cos(a) * 3.5 * r, py = Math.sin(a) * 3.6 * r;
      if (i === 0) shape.moveTo(px, py); else shape.lineTo(px, py);
    }
    for (let layer = 0; layer < 3; layer++) {
      const item = mesh(new THREE.ExtrudeGeometry(shape, { depth: .2, bevelEnabled: true, bevelSegments: 2, steps: 1, bevelSize: .12, bevelThickness: .1 }), material(layer === 2 ? 0xe9f1e9 : 0xc8c6e0), scene, x, .15 + layer * .2, z);
      item.rotation.x = -Math.PI / 2; item.scale.setScalar(1 - layer * .05);
    }
  }
  function house(parent: THREE.Object3D, x: number, z: number, mat: THREE.Material) {
    box(parent, x, .55, z, 1.2, .95, 1.05);
    const roof = mesh(new THREE.ConeGeometry(1.03, .65, 4), mat, parent, x, 1.35, z); roof.rotation.y = Math.PI / 4;
    box(parent, x, .38, z + .54, .24, .5, .035, mat);
    box(parent, x + .32, .7, z + .55, .22, .22, .035, silver);
  }
  function product(parent: THREE.Object3D, x: number, z: number, text: string, mat: THREE.Material) {
    cylinder(parent, x, .25, z, 1.08, .5);
    if (/폰|phone/i.test(text)) {
      box(parent, x, 1.05, z, .58, 1.25, .12, silver); box(parent, x, 1.07, z + .07, .48, 1.02, .015, mat);
    } else if (/노트북|컴퓨터|기기|laptop/i.test(result.title + text)) {
      const screen = box(parent, x, 1.15, z, 1.35, .9, .09, silver); screen.rotation.x = -.12;
      box(parent, x, 1.15, z + .09, 1.2, .72, .015, mat); box(parent, x, .68, z + .4, 1.45, .09, .9, silver);
    } else {
      box(parent, x, 1.05, z, 1.05, 1.05, .95, mat); box(parent, x, 1.6, z, 1.13, .12, 1.03); box(parent, x, 1.14, z + .49, .15, .9, .02, white);
    }
  }
  function token(parent: THREE.Object3D, kind: WeightKind, mat: THREE.Material, size: number) {
    const group = new THREE.Group(); parent.add(group);
    const weight = cylinder(group, 0, .13, 0, .34, .24, mat);
    if (kind === "coin") {
      cylinder(group, 0, .3, 0, .29, .075, silver); cylinder(group, 0, .39, 0, .25, .075, mat);
    } else if (kind === "clock") {
      const dial = cylinder(group, 0, .32, 0, .25, .06, white); dial.rotation.x = Math.PI / 2;
      box(group, 0, .4, .04, .025, .15, .04, silver); box(group, .055, .325, .045, .12, .025, .04, silver);
    } else if (kind === "home") {
      box(group, 0, .37, 0, .27, .3, .27, white); mesh(new THREE.ConeGeometry(.25, .2, 4), mat, group, 0, .61, 0).rotation.y = Math.PI / 4;
    } else if (kind === "heart") {
      mesh(new THREE.SphereGeometry(.14, 12, 8), white, group, -.09, .37, 0); mesh(new THREE.SphereGeometry(.14, 12, 8), white, group, .09, .37, 0);
      const tip = mesh(new THREE.ConeGeometry(.21, .3, 16), white, group, 0, .23, 0); tip.rotation.z = Math.PI;
    } else mesh(new THREE.OctahedronGeometry(.22), white, group, 0, .43, 0);
    group.scale.setScalar(size); return { group, weight };
  }
  function buildWorld() {
    box(scene, 0, -.22, 0, 22, .35, 15, material(theme === "travel" ? 0xdcecef : 0xe8e8f1));
    if (theme !== "travel") {
      const grid = new THREE.GridHelper(22, 22, 0xd5d3e3, 0xdddce9); grid.position.y = -.025; scene.add(grid); geometries.add(grid.geometry); if (Array.isArray(grid.material)) grid.material.forEach((m) => materials.add(m)); else materials.add(grid.material);
    }
    cylinder(scene, 0, .06, 5.4, 1, .13, white); ring(scene, 0, .14, 5.4, .85, 0x9d88ce);
    marker(scene, "나의 고민", 0, .7, 5.4, undefined, true);
    pair.forEach((option, side) => {
      const x = side === 0 ? -5 : 5;
      const selection = { optionId: option.id, criterionId: result.criteria[0]?.id ?? "" };
      road([vector(0, .1, 5.4), vector(x * .55, .1, 3.9), vector(x, .1, 1), vector(x, .1, -3.9)], theme === "career" ? 1.65 : .48, colors[side], theme !== "travel");
      if (theme === "travel") island(x, -1, side);
      if (theme === "career") {
        for (const z of [-3.8, -.8, 2.2]) box(scene, x, .03, z, 7.3, .05, .65, asphalt);
        for (let i = 0; i < 8; i++) { const bx = x + (i % 2 ? 3 : -3), bz = -4 + Math.floor(i / 2) * 2.1; building(scene, bx, bz, .45 + (i % 3) * .2, white); }
        for (const dx of [-2.4, 2.4]) { tree(scene, x + dx, 3.1); tree(scene, x + dx, .8); }
        for (let i = 0; i < 5; i++) box(scene, x - .56 + i * .28, .084, 2.25, .12, .016, .53, white);
        const car = new THREE.Group(); car.position.set(x + .45, .1, -.1); scene.add(car);
        box(car, 0, .12, 0, .24, .15, .46, accents[side]); box(car, 0, .24, 0, .2, .12, .23, silver);
      }
      if (theme === "housing") {
        house(scene, x, -2.4, accents[side]);
        for (const dx of [-2.8, 2.8]) { tree(scene, x + dx, 2.4); tree(scene, x + dx, -3.7); }
      }
      if (theme === "purchase") product(scene, x, -2.9, option.name, accents[side]);
      marker(scene, option.name, x, theme === "travel" ? 1.05 : 1, -4.7, selection, true);
      // Each criterion has its own selectable object. Labels reveal only the active criterion to avoid collisions.
      result.criteria.forEach((criterion, i) => {
        const count = result.criteria.length, rows = Math.ceil(count / 2);
        const z = -2.9 + (rows === 1 ? 1.2 : i >> 1) * Math.min(2.1, 5.8 / Math.max(1, rows - 1));
        const bx = x + (i % 2 === 0 ? -1.7 : 1.7);
        const sy = theme === "travel" ? .9 : .15;
        const group = new THREE.Group(); group.position.set(bx, sy, z); scene.add(group);
        const sel = { optionId: option.id, criterionId: criterion.id };
        let labelY = 1;
        if (theme === "career") { const h = 1.5 + (i % 3) * .4; building(group, 0, 0, h, accents[side], sel); labelY = h + .7; }
        else if (theme === "housing") { house(group, 0, 0, accents[side]); labelY = 1.9; }
        else {
          cylinder(group, 0, .13, 0, .5, .22);
          const t = token(group, weightKind(criterion.name), accents[side], 1.2); t.group.position.y = .24; labelY = 1.2;
        }
        group.traverse((object) => { if (object instanceof THREE.Mesh) { object.userData.selection = sel; selectable.push(object); } });
        const halo = ring(group, 0, .08, 0, .72, colors[side]); halo.visible = false;
        marker(group, criterion.name, 0, labelY, 0, sel, false, halo);
        if (theme === "travel") tree(scene, bx + .6, z + .35, .75);
      });
    });
  }
  function buildBalance() {
    box(scene, 0, -.45, 0, 17, .2, 9, material(0xeeebf5));
    cylinder(scene, 0, -.15, 0, 1.5, .5); cylinder(scene, 0, 2, 0, .18, 4, silver);
    cylinder(scene, 0, .26, 0, .48, .25, accents[0]);
    mesh(new THREE.SphereGeometry(.32, 24, 16), silver, scene, 0, 4.05, 0);
    beam = new THREE.Group(); beam.position.y = 4; scene.add(beam);
    box(beam, 0, 0, 0, 8, .16, .22, silver);
    pair.forEach((option, side) => {
      const pan = new THREE.Group(); pan.position.x = side === 0 ? -3.55 : 3.55; beam!.add(pan); pans.push(pan);
      for (const z of [-.66, .66]) {
        const rod = cylinder(pan, 0, -1.3, z / 2, .028, 2.65, silver); rod.rotation.x = z > 0 ? -.25 : .25;
      }
      cylinder(pan, 0, -2.62, 0, 1.65, .13);
      ring(pan, 0, -2.53, 0, 1.57, colors[side]);
      marker(pan, option.name, 0, -3.05, .9, { optionId: option.id, criterionId: result.criteria[0]?.id ?? "" }, true);
      balance.contributions.forEach((c, i) => {
        const contribution = side === 0 ? c.left : c.right;
        if (contribution <= 0) return; // Zero contribution is not displayed as a non-zero weight.
        const maximum = Math.max(...balance.contributions.flatMap((v) => [v.left, v.right]), .001);
        const size = .9 * Math.cbrt(contribution / maximum);
        const t = token(pan, c.kind, accents[side], size);
        const angle = i * 2.39996, radius = .35 + .65 * Math.sqrt(i / Math.max(1, balance.contributions.length - 1));
        t.group.position.set(Math.cos(angle) * radius, -2.52, Math.sin(angle) * radius);
        const sel = { optionId: option.id, criterionId: c.criterionId };
        t.group.traverse((object) => { if (object instanceof THREE.Mesh) { object.userData.selection = sel; selectable.push(object); } });
        drops.push({ mesh: t.group, end: -2.52, at: i * 2.8 / Math.max(1, balance.contributions.length), left: c.left, right: c.right });
        const halo = ring(pan, t.group.position.x, -2.5, t.group.position.z, .35, colors[side]); halo.visible = false;
        marker(t.group, c.name, 0, 1, 0, sel, false, halo);
      });
    });
    marker(scene, "나에게 중요한 것의 균형", 0, .3, 1.6, undefined, true);
  }

  function updateLabels() {
    const bounds = host.getBoundingClientRect();
    scene.updateMatrixWorld(true);
    for (const m of markers) {
      const activeCriterion = m.selection?.criterionId === selected.criterionId;
      const active = activeCriterion && m.selection?.optionId === selected.optionId;
      if (m.ring) m.ring.visible = active;
      m.element.hidden = !m.always && !activeCriterion;
      m.element.setAttribute("aria-pressed", String(m.always ? m.selection?.optionId === selected.optionId : active));
      if (m.element.hidden) continue;
      m.anchor.getWorldPosition(tmp); tmp.project(camera);
      const x = (tmp.x + 1) * bounds.width / 2, y = (1 - tmp.y) * bounds.height / 2;
      const outside = tmp.z < -1 || tmp.z > 1 || x < 15 || x > bounds.width - 15 || y < 5 || y > bounds.height - 10;
      m.element.style.visibility = outside ? "hidden" : "visible";
      m.element.style.left = `${Math.max(70, Math.min(bounds.width - 70, x))}px`;
      const top = m.always && m.selection && theme !== "balance" ? Math.max(52, m.element.offsetHeight + 12) : Math.max(105, y);
      m.element.style.top = `${top}px`;
    }
  }
  function animate(t: number) {
    if (beam) {
      const totalWeight = balance.contributions.reduce((s, c) => s + c.weight, 0);
      let delta = 0;
      balance.contributions.forEach((c, i) => {
        const at = i * 2.8 / Math.max(1, balance.contributions.length);
        const progress = THREE.MathUtils.clamp((t - at - .5) / .65, 0, 1);
        delta += (c.right - c.left) * (progress * progress * (3 - 2 * progress));
      });
      const tilt = totalWeight ? -.24 * delta / totalWeight : 0;
      beam.rotation.z = t >= animationDuration ? balance.angle : tilt;
      pans.forEach((pan) => { pan.rotation.z = -beam!.rotation.z; });
      for (const d of drops) {
        const local = t - d.at;
        d.mesh.visible = local >= 0;
        const progress = THREE.MathUtils.clamp(local / .7, 0, 1);
        d.mesh.position.y = d.end + 3.3 * (1 - progress * progress);
        if (progress === 1 && local < 1.3) d.mesh.position.y += .08 * Math.sin((local - .7) * 15) * Math.exp(-(local - .7) * 5);
      }
    }
    particles.forEach((p) => { p.mesh.position.copy(p.curve.getPointAt((t * .13 + p.phase) % 1)); p.mesh.position.y += .05; });
  }
  function render() {
    frame = 0;
    if (disposed || failed || !visible || document.hidden) return;
    const now = performance.now();
    if (motion && elapsed < animationDuration) elapsed = Math.min(animationDuration, elapsed + Math.min((now - previous) / 1000, .08));
    previous = now;
    try { animate(motion ? elapsed : animationDuration + 1); updateLabels(); renderer.render(scene, camera); }
    catch { fail(); return; }
    if (motion && elapsed < animationDuration) frame = requestAnimationFrame(render);
  }
  function requestRender() { if (!frame && !disposed && !failed && visible && !document.hidden) { previous = performance.now(); frame = requestAnimationFrame(render); } }
  function fail() { if (failed || disposed) return; failed = true; cancelAnimationFrame(frame); frame = 0; onFailure(); }
  function resize() {
    const width = host.clientWidth, height = host.clientHeight;
    if (!width || !height || disposed || failed) return;
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, width < 600 ? 1.25 : 1.5)); renderer.setSize(width, height, false);
    const aspect = width / height, halfWidth = theme === "balance" ? 6.5 : 11.5;
    camera.left = -halfWidth; camera.right = halfWidth; camera.top = halfWidth / aspect; camera.bottom = -halfWidth / aspect; camera.updateProjectionMatrix(); requestRender();
  }
  function onVisibility() { if (document.hidden) { cancelAnimationFrame(frame); frame = 0; } else requestRender(); }
  function contextLost(event: Event) { event.preventDefault(); fail(); }
  function pointerDown(event: PointerEvent) { down = { x: event.clientX, y: event.clientY }; }
  function pointerUp(event: PointerEvent) {
    if (Math.hypot(event.clientX - down.x, event.clientY - down.y) > 6) return;
    const r = renderer.domElement.getBoundingClientRect();
    raycaster.setFromCamera(new THREE.Vector2((event.clientX - r.left) / r.width * 2 - 1, -(event.clientY - r.top) / r.height * 2 + 1), camera);
    const hit = raycaster.intersectObjects(selectable, false).find((h) => h.object.visible && h.object.parent?.visible);
    if (hit?.object.userData.selection) onSelect(hit.object.userData.selection as Selection);
  }
  function reset() { controls?.target.set(0, theme === "balance" ? 1.8 : 0, 0); camera.position.set(0, theme === "balance" ? 8 : 14, theme === "balance" ? 17 : 18); camera.zoom = 1; camera.updateProjectionMatrix(); controls?.update(); requestRender(); }
  function dispose() {
    if (disposed) return; disposed = true; cancelAnimationFrame(frame); observer?.disconnect(); visibility?.disconnect(); controls?.dispose();
    document.removeEventListener("visibilitychange", onVisibility); renderer.domElement.removeEventListener("webglcontextlost", contextLost);
    renderer.domElement.removeEventListener("pointerdown", pointerDown); renderer.domElement.removeEventListener("pointerup", pointerUp);
    markers.forEach((m) => m.element.remove()); geometries.forEach((g) => g.dispose()); materials.forEach((m) => m.dispose());
    scene.traverse((object) => { if (object instanceof THREE.DirectionalLight) object.shadow.dispose(); });
    renderer.dispose(); renderer.forceContextLoss(); renderer.domElement.remove();
  }
  try {
    renderer.setClearColor(theme === "travel" ? 0xeaf4f5 : 0xf5f3fa); renderer.outputColorSpace = THREE.SRGBColorSpace; renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.25;
    renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.domElement.setAttribute("aria-hidden", "true"); host.prepend(renderer.domElement);
    scene.add(new THREE.HemisphereLight(0xffffff, 0xb1a9c3, 2.8)); const sun = new THREE.DirectionalLight(0xfff6ea, 3); sun.position.set(-7, 15, 6); sun.castShadow = true; sun.shadow.mapSize.set(1024, 1024); sun.shadow.camera.left = -14; sun.shadow.camera.right = 14; sun.shadow.camera.top = 14; sun.shadow.camera.bottom = -14; sun.shadow.normalBias = .04; scene.add(sun);
    if (theme === "balance") buildBalance(); else buildWorld();
    controls = new OrbitControls(camera, renderer.domElement); controls.enableRotate = false; controls.enableDamping = false; controls.minZoom = .8; controls.maxZoom = 2.5; controls.screenSpacePanning = true; controls.enabled = false; controls.addEventListener("change", requestRender);
    controls.mouseButtons.LEFT = THREE.MOUSE.PAN; controls.touches.ONE = THREE.TOUCH.PAN; controls.touches.TWO = THREE.TOUCH.DOLLY_PAN;
    reset(); renderer.domElement.style.touchAction = "pan-y";
    renderer.domElement.addEventListener("webglcontextlost", contextLost); renderer.domElement.addEventListener("pointerdown", pointerDown); renderer.domElement.addEventListener("pointerup", pointerUp);
    observer = new ResizeObserver(resize); observer.observe(host);
    visibility = new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; if (!visible) { cancelAnimationFrame(frame); frame = 0; } else requestRender(); }); visibility.observe(host);
    document.addEventListener("visibilitychange", onVisibility); resize();
  } catch (error) { dispose(); throw error; }
  return {
    select(optionId, criterionId) { selected = { optionId, criterionId }; requestRender(); },
    setMotion(enabled) { if (enabled && !motion) elapsed = 0; motion = enabled; requestRender(); },
    setExplore(enabled) { exploring = enabled; if (controls) controls.enabled = exploring; renderer.domElement.style.touchAction = exploring ? "none" : "pan-y"; },
    replay() { if (motion) { start = performance.now(); previous = start; elapsed = 0; requestRender(); } },
    finish() { elapsed = animationDuration + 1; requestRender(); },
    zoom(factor) { camera.zoom = THREE.MathUtils.clamp(camera.zoom * factor, .8, 2.5); camera.updateProjectionMatrix(); requestRender(); },
    reset, dispose,
  };
}
