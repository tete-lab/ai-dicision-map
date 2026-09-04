import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";
import { RoundedBoxGeometry } from "three/addons/geometries/RoundedBoxGeometry.js";
import { mergeGeometries } from "three/addons/utils/BufferGeometryUtils.js";
import { balanceContributions, sceneMetric, weightKind, type SceneResult, type SceneTheme, type WeightKind } from "@/lib/decisionScene";

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
type Marker = { anchor: THREE.Object3D; element: HTMLButtonElement; selection?: Selection; ring?: THREE.Mesh; always: boolean; line: SVGLineElement };
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
  let environmentTarget: THREE.WebGLRenderTarget | undefined;
  const leaders = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  leaders.classList.add("world-leaders"); leaders.setAttribute("aria-hidden", "true"); labels.append(leaders);
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
  const physical = (color: number, metalness: number, roughness: number, clearcoat = .7) => {
    const m = new THREE.MeshPhysicalMaterial({ color, metalness, roughness, clearcoat, clearcoatRoughness: .18, envMapIntensity: 1.15 }); materials.add(m); return m;
  };
  const white = physical(0xf6f1f3, .12, .28), silver = physical(0xcac5ce, .86, .22), asphalt = material(0x777781, .05, .9);
  const accents = colors.map((c) => physical(c, .3, .24));
  const foliage = material(0x849d8b), glass = physical(0x82939f, .48, .19), facade = material(0xe9e4e0), pavement = material(0xd7d3cf);
  function mesh(geometry: THREE.BufferGeometry, mat: THREE.Material, parent: THREE.Object3D, x = 0, y = 0, z = 0) {
    geometries.add(geometry); const item = new THREE.Mesh(geometry, mat); item.position.set(x, y, z); item.castShadow = true; item.receiveShadow = true; parent.add(item); return item;
  }
  function box(parent: THREE.Object3D, x: number, y: number, z: number, w: number, h: number, d: number, mat: THREE.Material = white) {
    return mesh(new THREE.BoxGeometry(w, h, d), mat, parent, x, y, z);
  }
  function cylinder(parent: THREE.Object3D, x: number, y: number, z: number, radius: number, height: number, mat: THREE.Material = white) {
    return mesh(new THREE.CylinderGeometry(radius, radius, height, 40), mat, parent, x, y, z);
  }
  function lathe(parent: THREE.Object3D, profile: number[][], mat: THREE.Material, x = 0, y = 0, z = 0) {
    return mesh(new THREE.LatheGeometry(profile.map(([r, h]) => new THREE.Vector2(r, h)), 64), mat, parent, x, y, z);
  }
  function strut(parent: THREE.Object3D, from: THREE.Vector3, to: THREE.Vector3, radius: number, mat: THREE.Material) {
    const center = from.clone().add(to).multiplyScalar(.5);
    const rod = cylinder(parent, center.x, center.y, center.z, radius, from.distanceTo(to), mat);
    rod.quaternion.setFromUnitVectors(vector(0, 1, 0), to.clone().sub(from).normalize()); return rod;
  }
  function ring(parent: THREE.Object3D, x: number, y: number, z: number, radius: number, color: number) {
    const m = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .65, side: THREE.DoubleSide }); materials.add(m);
    const item = mesh(new THREE.RingGeometry(radius, radius + .045, 64), m, parent, x, y, z); item.rotation.x = -Math.PI / 2; item.castShadow = false; return item;
  }
  function marker(parent: THREE.Object3D, text: string, x: number, y: number, z: number, selection?: Selection, always = false, highlight?: THREE.Mesh) {
    const anchor = new THREE.Object3D(); anchor.position.set(x, y, z); parent.add(anchor);
    const element = document.createElement("button"); element.type = "button"; element.className = always ? "world-marker world-marker-option" : "world-marker"; element.title = text;
    const side = pair.findIndex((o) => o.id === selection?.optionId);
    element.dataset.side = String(side);
    if (selection && !always) {
      const metric = sceneMetric(result, selection.optionId, selection.criterionId);
      const name = document.createElement("span"), score = document.createElement("b"), detail = document.createElement("small");
      const index = result.criteria.findIndex((c) => c.id === selection.criterionId);
      name.textContent = `${String(index + 1).padStart(2, "0")} ${text}`;
      score.textContent = metric.scoreLabel; detail.textContent = metric.weightLabel;
      element.append(name, score, detail);
      element.dataset.criterion = selection.criterionId;
    } else element.textContent = text;
    element.hidden = false;
    if (selection) { element.addEventListener("click", () => onSelect(selection)); element.setAttribute("aria-label", `${pair.find((o) => o.id === selection.optionId)?.name ?? ""} · ${text}`); }
    else { element.disabled = true; element.classList.add("world-marker-origin"); }
    const line = document.createElementNS("http://www.w3.org/2000/svg", "line"); line.setAttribute("stroke", side === 1 ? "#76ada1" : "#aa96cc"); leaders.append(line);
    labels.append(element); markers.push({ anchor, element, selection, ring: highlight, always, line });
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
    const m = new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 2.5, roughness: .3 }); materials.add(m);
    mesh(new THREE.TubeGeometry(curve, 60, .045, 8, false), m, scene);
    const glow = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: .08, depthWrite: false }); materials.add(glow);
    mesh(new THREE.TubeGeometry(curve, 60, .16, 8, false), glow, scene).castShadow = false;
    for (let i = 0; i < 7; i++) particles.push({ mesh: mesh(new THREE.SphereGeometry(.065, 8, 8), m, scene), curve, phase: i / 7 });
  }
  function building(parent: THREE.Object3D, x: number, z: number, height: number, mat: THREE.Material, selection?: Selection) {
    box(parent, x, .09, z, 1.3, .18, 1.25, pavement);
    if (height <= 0) return;
    const body = box(parent, x, height / 2 + .18, z, 1.02, height, .96, selection ? mat : glass);
    // Facade floors/mullions share one merged mesh per tower, including the side faces.
    const frames: THREE.BufferGeometry[] = [];
    const frameBox = (px: number, py: number, pz: number, w: number, h: number, d: number) => frames.push(new THREE.BoxGeometry(w, h, d).translate(px, py, pz));
    for (let floor = .2; floor < height; floor += .24) frameBox(x, floor + .18, z, 1.065, .045, 1.005);
    for (const dx of [-.49, -.245, 0, .245, .49]) frameBox(x + dx, height / 2 + .18, z, .027, height, 1.005);
    for (const dz of [-.45, -.22, 0, .22, .45]) frameBox(x, height / 2 + .18, z + dz, 1.065, height, .022);
    const merged = mergeGeometries(frames); frames.forEach((g) => g.dispose()); if (merged) mesh(merged, facade, parent);
    box(parent, x, height + .22, z, 1.14, .08, 1.08, white);
    box(parent, x, height + .32, z, .65, .15, .6, selection ? mat : silver);
    if (selection) { ring(parent, x, .2, z, .83, colors[pair.findIndex((o) => o.id === selection.optionId)]); }
    if (selection) { body.userData.selection = selection; selectable.push(body); }
  }
  function tree(parent: THREE.Object3D, x: number, z: number, base = 0) {
    cylinder(parent, x, base + .28, z, .055, .5, silver);
    mesh(new THREE.IcosahedronGeometry(.26, 1), foliage, parent, x, base + .6, z);
  }
  function island(x: number, z: number, side: number) {
    const shape = new THREE.Shape();
    for (let i = 0; i <= 40; i++) {
      const a = i / 40 * Math.PI * 2, r = 1 + Math.sin(a * 3 + side) * .13 + Math.cos(a * 5 + side) * .06;
      const px = Math.cos(a) * 3.5 * r, py = Math.sin(a) * 3.6 * r;
      if (i === 0) shape.moveTo(px, py); else shape.lineTo(px, py);
    }
    for (let layer = 0; layer < 8; layer++) {
      const tint = new THREE.Color(side ? 0xb5d3ca : 0xb7a6cc).lerp(new THREE.Color(0xf7f3f7), layer / 8);
      const item = mesh(new THREE.ExtrudeGeometry(shape, { depth: .1, bevelEnabled: true, bevelSegments: 3, steps: 1, bevelSize: .08, bevelThickness: .035 }), material(tint.getHex(), .12, .6), scene, x, -.12 + layer * .14, z);
      item.rotation.x = -Math.PI / 2; item.scale.set(1 - layer * .018, 1 - layer * .018, 1);
      const contour = shape.getPoints(100).map((p) => vector(x + p.x * (1 - layer * .018), .035 + layer * .14, z - p.y * (1 - layer * .018)));
      const geo = new THREE.BufferGeometry().setFromPoints(contour); geometries.add(geo);
      const lineMat = new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: .5 }); materials.add(lineMat); scene.add(new THREE.LineLoop(geo, lineMat));
    }
    // Small shoreline gardens/crystals add terrain detail without pretending to be real locations.
    for (let i = 0; i < 24; i++) {
      const a = i / 24 * Math.PI * 2, r = 2.65 + Math.sin(a * 3 + side) * .22;
      const px = x + Math.cos(a) * r, pz = z + Math.sin(a) * r;
      const rock = mesh(new THREE.IcosahedronGeometry(.12 + (i % 3) * .035, 0), i % 3 ? white : accents[side], scene, px, 1.1, pz);
      rock.scale.y = 1.5; rock.rotation.set(i, a, .2);
      if (i % 3 === 0) {
        const bush = mesh(new THREE.IcosahedronGeometry(.13, 1), foliage, scene, px + .18, 1.13, pz + .12); bush.scale.set(1, .7, 1);
      }
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
    // All weight shells have identical volume before scaling. Different symbols are engraved-sized accents.
    const weight = mesh(new THREE.SphereGeometry(.5, 32, 24), mat, group, 0, .44, 0); weight.scale.set(.9, .88, .42);
    const emblem = new THREE.Group(); emblem.position.set(0, .35, .24); emblem.scale.setScalar(.55); group.add(emblem);
    if (kind === "coin") {
      const circle = mesh(new THREE.TorusGeometry(.24, .025, 8, 32), silver, emblem, 0, .14, 0);
      circle.rotation.z = .1; box(emblem, 0, .14, .02, .025, .32, .03, silver);
    } else if (kind === "clock") {
      mesh(new THREE.TorusGeometry(.25, .023, 8, 32), silver, emblem, 0, .14, 0);
      box(emblem, 0, .22, .03, .025, .17, .03, silver); box(emblem, .07, .14, .03, .14, .025, .03, silver);
    } else if (kind === "home") {
      box(emblem, 0, .08, 0, .3, .28, .035, silver); const roof = mesh(new THREE.ConeGeometry(.26, .22, 3), silver, emblem, 0, .32, 0); roof.scale.z = .16;
    } else if (kind === "heart") {
      mesh(new THREE.SphereGeometry(.14, 12, 8), silver, emblem, -.09, .19, 0).scale.z = .2; mesh(new THREE.SphereGeometry(.14, 12, 8), silver, emblem, .09, .19, 0).scale.z = .2;
      const tip = mesh(new THREE.ConeGeometry(.21, .27, 3), silver, emblem, 0, .06, 0); tip.rotation.z = Math.PI; tip.scale.z = .15;
    } else mesh(new THREE.OctahedronGeometry(.25), silver, emblem, 0, .16, 0).scale.z = .16;
    group.scale.setScalar(size); return { group, weight };
  }
  function buildWorld() {
    box(scene, 0, -.3, 0, 42, .35, 32, material(theme === "travel" ? 0xe2dceb : 0xdfdcd7));
    if (theme === "career") {
      // A continuous urban fabric, not isolated chess pieces: blocks, boulevards and a civic roundabout.
      for (const z of [-8, -4.5, 0, 4.2, 8.8]) box(scene, 0, -.08, z, 38, .07, 1.3, asphalt);
      for (const x of [-12, -8.8, 0, 8.8, 12]) box(scene, x, -.08, -1, 1.3, .07, 25, asphalt);
      const seed = (i: number) => Math.abs(Math.sin(i * 127.1 + 311.7));
      for (let i = 0; i < 64; i++) {
        const x = (i % 16 - 7.5) * 2.25, z = Math.floor(i / 16) * 3.6 - 12;
        if (Math.abs(x) < 8.4 && z > -6) continue;
        const group = new THREE.Group(); group.position.set(x, 0, z); group.scale.set(.8 + seed(i) * .3, 1, .85); scene.add(group);
        building(group, 0, 0, .65 + seed(i + 10) * 2.8, facade);
      }
      for (const side of [-1, 1]) for (let i = 0; i < 4; i++) {
        const group = new THREE.Group(); group.position.set(side * (8 + i % 2 * 2), 0, 4.8 + Math.floor(i / 2) * 2.5); scene.add(group);
        building(group, 0, 0, .75 + i * .18, facade); tree(scene, group.position.x - .7, group.position.z + .6);
      }
      for (let i = 0; i < 24; i++) {
        const a = i / 24 * Math.PI * 2;
        if (i % 3 === 0) tree(scene, Math.cos(a) * 2.3, 5.4 + Math.sin(a) * 2.3);
      }
      cylinder(scene, 0, -.03, 5.4, 2.65, .08, asphalt);
      cylinder(scene, 0, .02, 5.4, 1.65, .16, pavement);
      ring(scene, 0, .026, 5.4, 2.2, 0xf2eee8);
      for (let i = 0; i < 24; i++) {
        const x = (i % 2 ? -1 : 1) * (4.68 + (i % 3) * .22), z = -7 + Math.floor(i / 2) * 1.2;
        const car = new THREE.Group(); car.position.set(x, .12, z); scene.add(car);
        box(car, 0, .1, 0, .18, .12, .35, i % 3 ? white : accents[i % 2]); box(car, 0, .18, -.02, .14, .07, .17, glass);
      }
    }
    if (theme === "travel") {
      for (let j = 0; j < 12; j++) {
        const pts = Array.from({ length: 61 }, (_, i) => vector(-18 + i * .6, -.1, j * 1.2 - 8 + Math.sin(i / 9 + j / 2) * .65));
        const geo = new THREE.BufferGeometry().setFromPoints(pts); geometries.add(geo);
        const mat = new THREE.LineBasicMaterial({ color: 0xffffff, opacity: .3, transparent: true }); materials.add(mat); scene.add(new THREE.Line(geo, mat));
      }
      const origin = new THREE.Group(); origin.position.set(0, .15, 5.4); scene.add(origin);
      for (let i = 0; i < 5; i++) cylinder(origin, 0, i * .13, 0, 1.35 - i * .05, .15, i % 2 ? white : silver);
      const stars = Array.from({ length: 70 }, (_, i) => {
        const px = Math.sin(i * 127.1) * 12, pz = Math.cos(i * 91.7) * 9;
        return vector(px, .03, pz);
      });
      const starGeometry = new THREE.BufferGeometry().setFromPoints(stars); geometries.add(starGeometry);
      const starMaterial = new THREE.PointsMaterial({ color: 0xffffff, size: .05, transparent: true, opacity: .7, depthWrite: false }); materials.add(starMaterial); scene.add(new THREE.Points(starGeometry, starMaterial));
    }
    cylinder(scene, 0, .16, 5.4, 1, .13, white); ring(scene, 0, .24, 5.4, .85, 0x9d88ce);
    marker(scene, "나의 고민", 0, .7, 5.4, undefined, true);
    pair.forEach((option, side) => {
      const x = side === 0 ? -5 : 5;
      const selection = { optionId: option.id, criterionId: result.criteria[0]?.id ?? "" };
      const routeY = theme === "travel" ? 1.2 : .1;
      road([vector(0, routeY, 5.4), vector(x * .55, routeY, 3.9), vector(x, routeY, 1), vector(x, routeY, -5.3)], theme === "career" ? 1.95 : .48, colors[side], theme !== "travel");
      if (theme === "travel") island(x, -1, side);
      if (theme === "career") {
        for (const z of [-4.6, -.2, 3.4]) box(scene, x, .03, z, 7.3, .05, .75, asphalt);
        for (let i = 0; i < 12; i++) { const bx = x + (i % 2 ? 3.4 : -3.4), bz = -5 + Math.floor(i / 2) * 1.6; tree(scene, bx, bz); }
        for (let i = 0; i < 5; i++) box(scene, x - .56 + i * .28, .084, 2.25, .12, .016, .53, white);
        const car = new THREE.Group(); car.position.set(x + .45, .1, -.1); scene.add(car);
        box(car, 0, .12, 0, .24, .15, .46, accents[side]); box(car, 0, .24, 0, .2, .12, .23, silver);
      }
      if (theme === "housing") {
        house(scene, x, -2.4, accents[side]);
        for (const dx of [-2.8, 2.8]) { tree(scene, x + dx, 2.4); tree(scene, x + dx, -3.7); }
      }
      if (theme === "purchase") product(scene, x, -2.9, option.name, accents[side]);
      marker(scene, option.name, x, 1, -6.1, selection, true);
      // Every criterion is visible and is mapped with the same 0–100 scale on both sides.
      result.criteria.forEach((criterion, i) => {
        const count = result.criteria.length, rows = Math.ceil(count / 2);
        const z = -3.4 + (rows === 1 ? 1.2 : i >> 1) * (6 / Math.max(1, rows - 1));
        const bx = x + (i % 2 === 0 ? -1.9 : 1.9);
        const sy = theme === "travel" ? 1.06 : .15;
        const group = new THREE.Group(); group.position.set(bx, sy, z); scene.add(group);
        const sel = { optionId: option.id, criterionId: criterion.id };
        const metric = sceneMetric(result, option.id, criterion.id);
        let labelY = 1;
        if (theme === "career") { const h = metric.height ?? 0; building(group, 0, 0, h, accents[side], sel); labelY = h + .65; }
        else if (theme === "housing") { house(group, 0, 0, accents[side]); const h = metric.height ?? 0; cylinder(group, .85, h / 2, 0, .09, Math.max(.01, h), accents[side]); labelY = Math.max(1.8, h + .4); }
        else if (theme === "travel") {
          cylinder(group, 0, .13, 0, .5, .22);
          const h = (metric.height ?? 0) * .5;
          if (metric.score !== null) {
            cylinder(group, 0, .24 + h / 2, 0, .075, Math.max(.01, h), silver);
            mesh(new THREE.SphereGeometry(.23, 24, 16), accents[side], group, 0, .45 + h, 0);
            mesh(new THREE.ConeGeometry(.13, .3, 16), accents[side], group, 0, .25 + h, 0).rotation.z = Math.PI;
          }
          ring(group, 0, .26, 0, .43, colors[side]); labelY = .85 + h;
        } else {
          const h = (metric.height ?? 0) * .4;
          cylinder(group, 0, h / 2, 0, .48, Math.max(.03, h));
          const t = token(group, weightKind(criterion.name), accents[side], .9); t.group.position.y = h; labelY = h + 1;
        }
        if (metric.score === null) {
          const mat = new THREE.MeshBasicMaterial({ color: 0xa69aad, wireframe: true }); materials.add(mat);
          mesh(new THREE.OctahedronGeometry(.35), mat, group, 0, .65, 0);
        }
        group.traverse((object) => { if (object instanceof THREE.Mesh) { object.userData.selection = sel; selectable.push(object); } });
        const halo = ring(group, 0, .08, 0, .72, colors[side]); halo.visible = false;
        marker(group, criterion.name, 0, labelY, 0, sel, false, halo);
        if (theme === "travel") tree(scene, bx + .6, z + .35, .75);
      });
    });
  }
  function buildBalance() {
    box(scene, 0, -.65, 0, 36, .2, 24, physical(0xf0e6ed, .18, .36));
    lathe(scene, [[0, -.5], [1.75, -.5], [1.9, -.38], [1.9, -.18], [1.75, -.02], [1.45, .05], [0, .05]], silver);
    cylinder(scene, 0, .07, 0, 1.65, .14, accents[0]);
    lathe(scene, [[0, .1], [.95, .1], [.95, .23], [.7, .38], [.48, .65], [.38, .8], [.32, 3.9], [.48, 4.02], [.5, 4.15], [0, 4.15]], silver);
    cylinder(scene, 0, 2.4, 0, .35, 2.9, accents[0]);
    for (let i = 0; i < 16; i++) { const a = i * Math.PI / 8; cylinder(scene, Math.cos(a) * .35, 2.4, Math.sin(a) * .35, .014, 2.85, silver); }
    const hub = cylinder(scene, 0, 4.2, .04, .52, .35, silver); hub.rotation.x = Math.PI / 2;
    const jewel = mesh(new THREE.SphereGeometry(.3, 32, 24), accents[0], scene, 0, 4.2, .27); jewel.scale.z = .4;
    const bezel = mesh(new THREE.TorusGeometry(.34, .045, 12, 48), silver, scene, 0, 4.2, .32); bezel.castShadow = false;
    mesh(new THREE.SphereGeometry(.13, 20, 16), silver, scene, 0, 4.95, 0);
    beam = new THREE.Group(); beam.position.y = 4.2; scene.add(beam);
    mesh(new RoundedBoxGeometry(9.25, .16, .23, 3, .07), silver, beam);
    pair.forEach((option, side) => {
      const pan = new THREE.Group(); pan.position.x = side === 0 ? -4.35 : 4.35; beam!.add(pan); pans.push(pan);
      mesh(new THREE.SphereGeometry(.14, 20, 16), silver, pan);
      for (const a of [-Math.PI / 2, Math.PI / 6, Math.PI * 5 / 6]) {
        strut(pan, vector(0, -.06, 0), vector(Math.cos(a) * 1.83, -2.18, Math.sin(a) * 1.83), .026, silver);
      }
      lathe(pan, [[0, -2.67], [1.1, -2.67], [1.5, -2.6], [1.82, -2.4], [1.98, -2.14], [1.98, -2.09], [1.87, -2.12], [1.72, -2.35], [1.35, -2.5], [0, -2.5]], white);
      ring(pan, 0, -2.1, 0, 1.94, colors[side]);
      marker(pan, option.name, 0, -3.05, 1.25, { optionId: option.id, criterionId: result.criteria[0]?.id ?? "" }, true);
      result.criteria.forEach((criterion, i) => {
        const c = balance.contributions.find((item) => item.criterionId === criterion.id);
        const contribution = c ? (side === 0 ? c.left : c.right) : null;
        const maximum = Math.max(...balance.contributions.flatMap((v) => [v.left, v.right]), .001);
        const size = 1.3 * Math.min(1, Math.sqrt(5 / Math.max(1, result.criteria.length))) * Math.cbrt((contribution ?? 0) / maximum);
        const t = token(pan, weightKind(criterion.name), accents[side], size);
        const columns = Math.ceil(Math.sqrt(result.criteria.length));
        const rows = Math.ceil(result.criteria.length / columns);
        const tx = (i % columns - (columns - 1) / 2) * Math.min(.95, 2.25 / Math.max(1, columns - 1));
        const tz = (Math.floor(i / columns) - (rows - 1) / 2) * Math.min(.85, 2.25 / Math.max(1, rows - 1));
        t.group.position.set(tx, -2.48, tz);
        const sel = { optionId: option.id, criterionId: criterion.id };
        t.group.traverse((object) => { if (object instanceof THREE.Mesh) { object.userData.selection = sel; selectable.push(object); } });
        if (contribution !== null && contribution > 0) drops.push({ mesh: t.group, end: -2.48, at: balance.contributions.indexOf(c!) * 2.8 / Math.max(1, balance.contributions.length), left: c!.left, right: c!.right });
        else {
          // An empty outline is not a weight. Both missing and zero remain labelled, but never add torque.
          const m = new THREE.MeshBasicMaterial({ color: 0xb4a8bf, wireframe: true }); materials.add(m);
          mesh(new THREE.SphereGeometry(.2, 12, 8), m, pan, tx, -2.3, tz);
        }
        const halo = ring(pan, t.group.position.x, -2.5, t.group.position.z, .35, colors[side]); halo.visible = false;
        // Stable pan-space anchors avoid jumping labels during the drop; leader lines point to each weight.
        marker(pan, criterion.name, tx, -2.48 + Math.max(.2, size * .9), tz, sel, false, halo);
      });
    });
    marker(scene, "나에게 중요한 것의 균형", 0, -.2, 2, undefined, true);
  }

  function updateLabels() {
    const bounds = host.getBoundingClientRect();
    scene.updateMatrixWorld(true);
    leaders.setAttribute("viewBox", `0 0 ${bounds.width} ${bounds.height}`);
    for (const m of markers) {
      const activeCriterion = m.selection?.criterionId === selected.criterionId;
      const active = activeCriterion && m.selection?.optionId === selected.optionId;
      if (m.ring) m.ring.visible = active;
      m.element.hidden = false;
      m.element.setAttribute("aria-pressed", String(m.always ? m.selection?.optionId === selected.optionId : active));
      m.anchor.getWorldPosition(tmp); tmp.project(camera);
      const x = (tmp.x + 1) * bounds.width / 2, y = (1 - tmp.y) * bounds.height / 2;
      const outside = tmp.z < -1 || tmp.z > 1 || x < 15 || x > bounds.width - 15 || y < 5 || y > bounds.height - 10;
      m.element.style.visibility = outside ? "hidden" : "visible";
      m.line.style.visibility = outside || m.always ? "hidden" : "visible";
      let labelX = x, labelY = y - 14;
      if (!m.always && m.selection) {
        const index = result.criteria.findIndex((c) => c.id === m.selection!.criterionId);
        const side = pair.findIndex((o) => o.id === m.selection!.optionId);
        const row = Math.floor(index / 2), rows = Math.ceil(result.criteria.length / 2);
        // Four reserved label lanes keep *all* points readable, even for long Korean names.
        // Leaders preserve the association with projected buildings/weights rather than hiding overlaps.
        const lane = side * 2 + index % 2;
        labelX = bounds.width * [.115, .315, .685, .885][lane];
        const gap = Math.min(152, (bounds.height - 290) / Math.max(1, rows));
        labelY = (theme === "balance" ? 142 : 157) + row * gap;
        if (theme !== "balance") labelY = Math.max(labelY, Math.min(labelY + 32, y - 24));
      } else if (m.selection && theme !== "balance") {
        labelX = bounds.width * (pair.findIndex((o) => o.id === m.selection!.optionId) === 0 ? .25 : .75); labelY = 54;
      }
      labelX = Math.max(90, Math.min(bounds.width - 90, labelX));
      labelY = Math.max(m.element.offsetHeight + 12, Math.min(bounds.height - 70, labelY));
      m.element.style.left = `${labelX}px`; m.element.style.top = `${labelY}px`;
      m.line.setAttribute("x1", String(labelX)); m.line.setAttribute("y1", String(labelY));
      m.line.setAttribute("x2", String(x)); m.line.setAttribute("y2", String(y));
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
    const aspect = width / height, halfWidth = theme === "balance" ? 8.3 : 10.7;
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
  function reset() { controls?.target.set(0, theme === "balance" ? 2 : .4, 0); camera.position.set(0, theme === "balance" ? 8 : 16, theme === "balance" ? 19 : 18); camera.zoom = 1; camera.updateProjectionMatrix(); controls?.update(); requestRender(); }
  function dispose() {
    if (disposed) return; disposed = true; cancelAnimationFrame(frame); observer?.disconnect(); visibility?.disconnect(); controls?.dispose();
    document.removeEventListener("visibilitychange", onVisibility); renderer.domElement.removeEventListener("webglcontextlost", contextLost);
    renderer.domElement.removeEventListener("pointerdown", pointerDown); renderer.domElement.removeEventListener("pointerup", pointerUp);
    markers.forEach((m) => m.element.remove()); leaders.remove(); geometries.forEach((g) => g.dispose()); materials.forEach((m) => m.dispose()); environmentTarget?.dispose();
    scene.traverse((object) => { if (object instanceof THREE.DirectionalLight) object.shadow.dispose(); });
    renderer.dispose(); renderer.forceContextLoss(); renderer.domElement.remove();
  }
  try {
    renderer.setClearColor(theme === "travel" ? 0xeee8f5 : 0xf5f0f5); renderer.outputColorSpace = THREE.SRGBColorSpace; renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.toneMappingExposure = 1;
    renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    const room = new RoomEnvironment(), pmrem = new THREE.PMREMGenerator(renderer);
    try { environmentTarget = pmrem.fromScene(room, .04); scene.environment = environmentTarget.texture; scene.environmentIntensity = .65; }
    finally { room.dispose(); pmrem.dispose(); }
    renderer.domElement.setAttribute("aria-hidden", "true"); host.prepend(renderer.domElement);
    scene.add(new THREE.HemisphereLight(0xffffff, 0xb1a9c3, 1.2)); const sun = new THREE.DirectionalLight(0xfff6ea, 2.5); sun.position.set(-7, 15, 6); sun.castShadow = true; sun.shadow.mapSize.set(1536, 1536); sun.shadow.camera.left = -14; sun.shadow.camera.right = 14; sun.shadow.camera.top = 14; sun.shadow.camera.bottom = -14; sun.shadow.normalBias = .025; scene.add(sun);
    const rim = new THREE.DirectionalLight(0xb7dfe2, 1.4); rim.position.set(7, 6, -8); scene.add(rim);
    if (theme === "balance") buildBalance(); else buildWorld();
    controls = new OrbitControls(camera, renderer.domElement); controls.enableRotate = false; controls.enableDamping = false; controls.minZoom = .8; controls.maxZoom = 2.5; controls.screenSpacePanning = true; controls.enabled = false; controls.addEventListener("change", requestRender);
    controls.mouseButtons.LEFT = THREE.MOUSE.PAN; controls.touches.ONE = THREE.TOUCH.PAN; controls.touches.TWO = THREE.TOUCH.DOLLY_PAN;
    reset(); renderer.domElement.style.touchAction = "auto";
    renderer.domElement.addEventListener("webglcontextlost", contextLost); renderer.domElement.addEventListener("pointerdown", pointerDown); renderer.domElement.addEventListener("pointerup", pointerUp);
    observer = new ResizeObserver(resize); observer.observe(host);
    visibility = new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; if (!visible) { cancelAnimationFrame(frame); frame = 0; } else requestRender(); }); visibility.observe(host);
    document.addEventListener("visibilitychange", onVisibility); resize();
  } catch (error) { dispose(); throw error; }
  return {
    select(optionId, criterionId) { selected = { optionId, criterionId }; requestRender(); },
    setMotion(enabled) { if (enabled && !motion) elapsed = 0; motion = enabled; requestRender(); },
    setExplore(enabled) { exploring = enabled; if (controls) controls.enabled = exploring; renderer.domElement.style.touchAction = exploring ? "none" : "auto"; },
    replay() { if (motion) { start = performance.now(); previous = start; elapsed = 0; requestRender(); } },
    finish() { elapsed = animationDuration + 1; requestRender(); },
    zoom(factor) { camera.zoom = THREE.MathUtils.clamp(camera.zoom * factor, .8, 2.5); camera.updateProjectionMatrix(); requestRender(); },
    reset, dispose,
  };
}
