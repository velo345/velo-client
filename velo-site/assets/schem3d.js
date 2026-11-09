/*
 * 3D schematic viewer for velo-client.com/library/schematics: every block with the game's own textures (from the
 * Velo Library), plants as crossed planes, flat things flat, instanced per block type so even big builds stay smooth.
 * Drag to orbit, right-drag to pan, scroll to zoom; a layer slider cuts the build open from the top.
 * Exposes window.velo.schem3d(container, schematic) -> {setLayer(y), dispose()}.
 */
import * as THREE from "./vendor/three.module.min.js";

const loader = new THREE.TextureLoader();
const texCache = new Map();
function tex(name) {
	if (!name) return null;
	if (!texCache.has(name)) {
		const t = loader.load("/v2/blocktex/" + name + ".png");
		t.magFilter = THREE.NearestFilter;
		t.minFilter = THREE.NearestFilter;
		t.colorSpace = THREE.SRGBColorSpace;
		texCache.set(name, t);
	}
	return texCache.get(name);
}

const idOf = (state) => String(state || "").split("[")[0].replace("minecraft:", "");
const AIR = new Set(["air", "cave_air", "void_air", "structure_void", "barrier", "light", "moving_piston"]);

async function faceInfo(ids) {
	const out = {};
	for (let i = 0; i < ids.length; i += 300) {
		const chunk = ids.slice(i, i + 300);
		try {
			const r = await fetch("/v2/blockfaces?ids=" + encodeURIComponent(chunk.join(",")));
			Object.assign(out, await r.json());
		} catch (e) { /* unknown blocks become grey boxes */ }
	}
	return out;
}

function material(name, tint, transparent) {
	const m = new THREE.MeshLambertMaterial({ map: tex(name), alphaTest: 0.4, transparent: false, side: THREE.FrontSide });
	if (tint) m.color = new THREE.Color(tint);
	if (transparent) m.side = THREE.DoubleSide;
	return m;
}

function crossGeometry() {
	const a = new THREE.PlaneGeometry(1, 1);
	a.rotateY(Math.PI / 4);
	const b = new THREE.PlaneGeometry(1, 1);
	b.rotateY(-Math.PI / 4);
	const g = new THREE.BufferGeometry();
	const pos = [], uv = [], nor = [], idx = [];
	[a, b].forEach((p, k) => {
		const base = k * 4;
		pos.push(...p.attributes.position.array);
		uv.push(...p.attributes.uv.array);
		nor.push(...p.attributes.normal.array);
		idx.push(...Array.from(p.index.array).map((i) => i + base));
	});
	g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
	g.setAttribute("uv", new THREE.Float32BufferAttribute(uv, 2));
	g.setAttribute("normal", new THREE.Float32BufferAttribute(nor, 3));
	g.setIndex(idx);
	return g;
}

async function schem3d(container, sch) {
	const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
	renderer.setPixelRatio(Math.min(2, devicePixelRatio || 1));
	renderer.outputColorSpace = THREE.SRGBColorSpace;
	const canvas = renderer.domElement;
	canvas.style.cssText = "width:100%;height:100%;display:block;touch-action:none;cursor:grab";
	canvas.setAttribute("role", "img");
	canvas.setAttribute("aria-label", "3D view of the schematic - drag to turn, scroll to zoom");
	container.replaceChildren(canvas);
	const scene = new THREE.Scene();
	const camera = new THREE.PerspectiveCamera(40, 1, 0.1, 5000);
	scene.add(new THREE.AmbientLight(0xffffff, 1.9));
	const sun = new THREE.DirectionalLight(0xffffff, 1.4);
	sun.position.set(0.6, 1, 0.35);
	scene.add(sun);
	const fill = new THREE.DirectionalLight(0xffffff, 0.5);
	fill.position.set(-0.7, 0.4, -0.5);
	scene.add(fill);

	const { sx, sy, sz } = sch;
	// which block ids exist
	const ids = new Set();
	for (let x = 0; x < sx; x++) for (let y = 0; y < sy; y++) for (let z = 0; z < sz; z++) {
		const id = idOf(sch.at(x, y, z));
		if (!AIR.has(id)) ids.add(id);
	}
	const faces = await faceInfo([...ids]);
	const opaque = (id) => { const f = faces[id]; return f && f.kind === "cube" && !f.transparent; };
	const groups = new Map(); // id -> [[x,y,z]...]
	let total = 0;
	const at = (x, y, z) => (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) ? "air" : idOf(sch.at(x, y, z));
	for (let y = 0; y < sy; y++) for (let x = 0; x < sx; x++) for (let z = 0; z < sz; z++) {
		const id = at(x, y, z);
		if (AIR.has(id)) continue;
		// fully enclosed blocks can't be seen (unless the layer slider cuts them open - kept when near the cut)
		if (opaque(at(x + 1, y, z)) && opaque(at(x - 1, y, z)) && opaque(at(x, y, z + 1)) && opaque(at(x, y, z - 1)) && opaque(at(x, y - 1, z)) && opaque(at(x, y + 1, z))
			&& total > 60000) continue;
		if (!groups.has(id)) groups.set(id, []);
		groups.get(id).push([x, y, z]);
		if (++total > 400000) break;
	}
	const cube = new THREE.BoxGeometry(1, 1, 1);
	const flat = new THREE.BoxGeometry(1, 1 / 16, 1);
	flat.translate(0, -15 / 32, 0);
	const cross = crossGeometry();
	const meshes = [];
	const matrix = new THREE.Matrix4();
	const cx = sx / 2, cz = sz / 2;
	for (const [id, list] of groups) {
		const f = faces[id];
		let geo = cube, mats;
		if (!f) {
			mats = new THREE.MeshLambertMaterial({ color: id === "water" ? 0x3f76e4 : id === "lava" ? 0xd96415 : 0x8a8a8a, transparent: id === "water", opacity: id === "water" ? 0.6 : 1 });
		} else if (f.kind === "cross") {
			geo = cross;
			mats = material(f.side || f.top, f.tint, true);
		} else if (f.kind === "flat") {
			geo = flat;
			const top = material(f.top, f.tint, true);
			mats = [top, top, top, top, top, top];
		} else {
			const tintAll = f.tint && !id.startsWith("grass_block") ? f.tint : null;
			const side = material(f.side, tintAll, f.transparent);
			const top = material(f.top, f.tint, f.transparent);
			const bottom = material(f.bottom, tintAll, f.transparent);
			mats = [side, side, top, bottom, side, side];
		}
		list.sort((a, b) => a[1] - b[1]);
		const mesh = new THREE.InstancedMesh(geo, mats, list.length);
		list.forEach(([x, y, z], i) => {
			matrix.makeTranslation(x - cx + 0.5, y + 0.5, z - cz + 0.5);
			mesh.setMatrixAt(i, matrix);
		});
		mesh.userData.ys = list.map((p) => p[1]);
		scene.add(mesh);
		meshes.push(mesh);
	}

	// camera + simple orbit controls
	const radius = Math.max(sx, sy, sz) * 1.4 + 4;
	const target = new THREE.Vector3(0, sy / 2, 0);
	let yaw = -0.75, pitch = 0.6, dist = radius;
	const place = () => {
		camera.position.set(target.x + dist * Math.cos(pitch) * Math.sin(yaw), target.y + dist * Math.sin(pitch), target.z + dist * Math.cos(pitch) * Math.cos(yaw));
		camera.lookAt(target);
	};
	let drag = null;
	canvas.addEventListener("pointerdown", (e) => { drag = { x: e.clientX, y: e.clientY, pan: e.button === 2 || e.shiftKey }; canvas.setPointerCapture(e.pointerId); canvas.style.cursor = "grabbing"; });
	canvas.addEventListener("pointermove", (e) => {
		if (!drag) return;
		const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
		drag.x = e.clientX; drag.y = e.clientY;
		if (drag.pan) {
			const right = new THREE.Vector3().subVectors(camera.position, target).cross(camera.up).normalize();
			target.addScaledVector(right, dx * dist / 800).addScaledVector(camera.up, dy * dist / 800);
		} else {
			yaw -= dx * 0.008;
			pitch = Math.max(-1.45, Math.min(1.45, pitch + dy * 0.008));
		}
		place();
		auto = false;
	});
	canvas.addEventListener("pointerup", () => { drag = null; canvas.style.cursor = "grab"; });
	canvas.addEventListener("contextmenu", (e) => e.preventDefault());
	canvas.addEventListener("wheel", (e) => { e.preventDefault(); dist = Math.max(2, Math.min(radius * 4, dist * (e.deltaY > 0 ? 1.12 : 0.89))); place(); }, { passive: false });
	place();

	let auto = true, running = true;
	const size = () => {
		const w = container.clientWidth || 600, h = container.clientHeight || 400;
		renderer.setSize(w, h, false);
		camera.aspect = w / h;
		camera.updateProjectionMatrix();
	};
	const ro = new ResizeObserver(size);
	ro.observe(container);
	size();
	const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
	(function loop() {
		if (!running) return;
		if (auto && !reduce) { yaw += 0.0025; place(); }
		renderer.render(scene, camera);
		requestAnimationFrame(loop);
	})();

	return {
		blocks: total,
		/** Only layers up to y (0-based) are drawn. */
		setLayer(y) {
			for (const m of meshes) {
				const ys = m.userData.ys;
				let n = 0;
				while (n < ys.length && ys[n] <= y) n++;
				m.count = n;
			}
		},
		dispose() {
			running = false;
			ro.disconnect();
			renderer.dispose();
			meshes.forEach((m) => m.dispose && m.dispose());
		},
	};
}

window.velo = window.velo || {};
window.velo.schem3d = schem3d;
window.dispatchEvent(new Event("velo:schem3d"));
