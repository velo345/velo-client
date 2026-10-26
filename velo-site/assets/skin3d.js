/*
 * Try-on viewer for velo-client.com: a Minecraft player with 3D skin layers (every overlay pixel a small voxel, like
 * the launcher), wearing a cape (animated GIF, frame by frame), real 3D wings from their .vmesh (flapping around the
 * hinge, like in game), an aura or an emote (the player does it, with its effects). three.js, self-hosted. Exposes
 * window.velo.player3d(container, options).
 */
import * as THREE from "./vendor/three.module.min.js";

const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;

function loadImage(url) {
	return new Promise((resolve, reject) => {
		const img = new Image();
		img.crossOrigin = "anonymous";
		img.onload = () => resolve(img);
		img.onerror = () => reject(new Error("image " + url));
		img.src = url;
	});
}

function pixelTexture(source) {
	const t = new THREE.Texture(source);
	t.magFilter = THREE.NearestFilter;
	t.minFilter = THREE.NearestFilter;
	t.generateMipmaps = false;
	t.colorSpace = THREE.SRGBColorSpace;
	t.needsUpdate = true;
	return t;
}

/** Sets a BoxGeometry's UVs from skin-pixel rectangles ({x0, y0, x1, y1} per face: +x, -x, +y, -y, +z, -z). */
function mapBox(geo, rects, texW, texH) {
	const uv = geo.attributes.uv;
	rects.forEach((r, face) => {
		const [x0, y0, x1, y1] = r;
		const u0 = x0 / texW, u1 = x1 / texW, v0 = 1 - y0 / texH, v1 = 1 - y1 / texH;
		const i = face * 4;
		uv.setXY(i, u0, v0);
		uv.setXY(i + 1, u1, v0);
		uv.setXY(i + 2, u0, v1);
		uv.setXY(i + 3, u1, v1);
	});
	uv.needsUpdate = true;
}

/** The six face rectangles of a skin box at (u, v) sized w x h x d - BoxGeometry order. */
function skinRects(u, v, w, h, d) {
	return [
		[u + d + w, v + d, u + 2 * d + w, v + d + h], // +x: the character's left side
		[u, v + d, u + d, v + d + h], // -x: right side
		[u + d, v, u + d + w, v + d], // +y: top
		[u + d + w, v, u + d + 2 * w, v + d], // -y: bottom
		[u + d, v + d, u + d + w, v + d + h], // +z: front
		[u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h], // -z: back
	];
}

/** Every opaque overlay pixel of a part as a thin voxel on the outside of its box (3D skin layers). */
function overlayVoxels(pixels, size, u, v, w, h, d, out) {
	const scale = size / 64;
	const px = (x, y) => {
		const i = (Math.floor(y * scale) * size + Math.floor(x * scale)) * 4;
		return pixels[i + 3] > 100 ? [pixels[i], pixels[i + 1], pixels[i + 2]] : null;
	};
	const t = 0.55, gap = 0.04;
	const faces = [
		// [region x, y, width, height, (i, j) -> [x, y, z, sx, sy, sz]]
		[u + d, v + d, w, h, (i, j) => [-w / 2 + i + 0.5, h / 2 - j - 0.5, d / 2 + gap + t / 2, 1, 1, t]],
		[u + 2 * d + w, v + d, w, h, (i, j) => [w / 2 - i - 0.5, h / 2 - j - 0.5, -d / 2 - gap - t / 2, 1, 1, t]],
		[u, v + d, d, h, (i, j) => [-w / 2 - gap - t / 2, h / 2 - j - 0.5, -d / 2 + i + 0.5, t, 1, 1]],
		[u + d + w, v + d, d, h, (i, j) => [w / 2 + gap + t / 2, h / 2 - j - 0.5, d / 2 - i - 0.5, t, 1, 1]],
		[u + d, v, w, d, (i, j) => [-w / 2 + i + 0.5, h / 2 + gap + t / 2, -d / 2 + j + 0.5, 1, t, 1]],
		[u + d + w, v, w, d, (i, j) => [-w / 2 + i + 0.5, -h / 2 - gap - t / 2, d / 2 - j - 0.5, 1, t, 1]],
	];
	for (const [rx, ry, fw, fh, place] of faces) {
		for (let j = 0; j < fh; j++) {
			for (let i = 0; i < fw; i++) {
				const c = px(rx + i, ry + j);
				if (c) out.push([place(i, j), c]);
			}
		}
	}
}

/** Reads a .vmesh (big-endian): "VMSH", version, halves, then per half hinge xyz, span, vertices, indices. */
function parseVmesh(buffer) {
	const dv = new DataView(buffer);
	if (String.fromCharCode(dv.getUint8(0), dv.getUint8(1), dv.getUint8(2), dv.getUint8(3)) !== "VMSH") throw new Error("not a wing mesh");
	let o = 8;
	const count = dv.getInt32(o); o += 4;
	const halves = [];
	for (let hIdx = 0; hIdx < count; hIdx++) {
		const hx = dv.getFloat32(o), hy = dv.getFloat32(o + 4), hz = dv.getFloat32(o + 8), span = dv.getFloat32(o + 12); o += 16;
		const n = dv.getInt32(o); o += 4;
		const pos = new Float32Array(n * 3), nrm = new Float32Array(n * 3), uv = new Float32Array(n * 2);
		let side = 0;
		for (let i = 0; i < n; i++) {
			pos[i * 3] = dv.getFloat32(o) - hx; pos[i * 3 + 1] = dv.getFloat32(o + 4) - hy; pos[i * 3 + 2] = dv.getFloat32(o + 8) - hz;
			nrm[i * 3] = dv.getFloat32(o + 12); nrm[i * 3 + 1] = dv.getFloat32(o + 16); nrm[i * 3 + 2] = dv.getFloat32(o + 20);
			uv[i * 2] = dv.getFloat32(o + 24); uv[i * 2 + 1] = dv.getFloat32(o + 28);
			side += pos[i * 3];
			o += 32;
		}
		const ic = dv.getInt32(o); o += 4;
		const idx = new Uint32Array(ic);
		for (let i = 0; i < ic; i++) { idx[i] = dv.getInt32(o); o += 4; }
		halves.push({ pos, nrm, uv, idx, span: Math.max(1e-3, span), side: Math.sign(side) || (hIdx === 0 ? -1 : 1) });
	}
	return halves;
}

/** Frames of an animated GIF as canvases + delays (ImageDecoder where the browser has it; else the first frame). */
async function gifFrames(url) {
	if ("ImageDecoder" in window) {
		try {
			const res = await fetch(url);
			const decoder = new ImageDecoder({ data: res.body, type: "image/gif" });
			await decoder.tracks.ready;
			const count = decoder.tracks.selectedTrack.frameCount;
			const frames = [];
			for (let i = 0; i < Math.min(count, 120); i++) {
				const { image } = await decoder.decode({ frameIndex: i });
				const c = document.createElement("canvas");
				c.width = image.displayWidth; c.height = image.displayHeight;
				c.getContext("2d").drawImage(image, 0, 0);
				frames.push({ canvas: c, delay: Math.max(20, (image.duration || 100000) / 1000) });
				image.close();
			}
			if (frames.length) return frames;
		} catch (e) { /* falls back below */ }
	}
	const img = await loadImage(url);
	const c = document.createElement("canvas");
	c.width = img.naturalWidth; c.height = img.naturalHeight;
	c.getContext("2d").drawImage(img, 0, 0);
	return [{ canvas: c, delay: 1e9 }];
}

/**
 * Mounts a viewer in {@code container}. options: skin (url), slim, item (a cosmetic catalog item or null),
 * fileUrl(hash), auraUrl(id). Returns {setItem(item), setSkin(url, slim), restartEmote(), dispose()}.
 */
async function player3d(container, options) {
	const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
	renderer.setPixelRatio(Math.min(2, devicePixelRatio || 1));
	renderer.outputColorSpace = THREE.SRGBColorSpace;
	const canvas = renderer.domElement;
	canvas.className = "skin3d";
	canvas.setAttribute("role", "img");
	canvas.setAttribute("aria-label", "Your skin in 3D");
	// Aura: the particles behind the player go under the canvas (the real body covers them), the rest over it.
	const auraBack = document.createElement("img");
	auraBack.className = "skin3d-aura back";
	auraBack.alt = "";
	auraBack.hidden = true;
	const aura = document.createElement("img");
	aura.className = "skin3d-aura";
	aura.alt = "";
	aura.hidden = true;
	container.append(auraBack, canvas, aura);

	const scene = new THREE.Scene();
	const camera = new THREE.PerspectiveCamera(30, 1, 1, 400);
	camera.position.set(0, 19, 96);
	camera.lookAt(0, 15.5, 0);
	scene.add(new THREE.AmbientLight(0xffffff, 1.6));
	const sun = new THREE.DirectionalLight(0xffffff, 1.5);
	sun.position.set(-30, 50, 60);
	scene.add(sun);
	const rim = new THREE.DirectionalLight(0xff5560, 0.7);
	rim.position.set(40, 20, -50);
	scene.add(rim);

	const root = new THREE.Group();
	scene.add(root);
	let player = null, limbs = {}, worn = null, wornTick = null, emote = null;

	async function buildPlayer(skinUrl, slim) {
		const img = await loadImage(skinUrl);
		const size = img.naturalWidth;
		const legacy = img.naturalHeight * 2 === size;
		const can = document.createElement("canvas");
		can.width = size; can.height = img.naturalHeight;
		const ctx = can.getContext("2d", { willReadFrequently: true });
		ctx.drawImage(img, 0, 0);
		const pixels = ctx.getImageData(0, 0, can.width, can.height).data;
		const tex = pixelTexture(can);
		const texH = legacy ? 32 : 64;
		const mat = new THREE.MeshLambertMaterial({ map: tex, transparent: true, alphaTest: 0.5 });
		const aw = slim ? 3 : 4;
		const group = new THREE.Group();
		const voxels = [];
		const part = (name, w, h, d, u, v, ou, ov, pivot, offset) => {
			const pivotGroup = new THREE.Group();
			pivotGroup.position.set(...pivot);
			const geo = new THREE.BoxGeometry(w, h, d);
			mapBox(geo, skinRects(u, v, w, h, d), 64, texH);
			const mesh = new THREE.Mesh(geo, mat);
			mesh.position.set(...offset);
			pivotGroup.add(mesh);
			if (ou !== null && !legacy) {
				const list = [];
				overlayVoxels(pixels, size, ou, ov, w, h, d, list);
				list.forEach(([p, c]) => voxels.push({ group: pivotGroup, offset, p, c }));
			} else if (ou !== null && name === "head") {
				const list = [];
				overlayVoxels(pixels, size, ou, ov, w, h, d, list);
				list.forEach(([p, c]) => voxels.push({ group: pivotGroup, offset, p, c }));
			}
			group.add(pivotGroup);
			return pivotGroup;
		};
		limbs = {
			head: part("head", 8, 8, 8, 0, 0, 32, 0, [0, 24, 0], [0, 4, 0]),
			body: part("body", 8, 12, 4, 16, 16, 16, 32, [0, 18, 0], [0, 0, 0]),
			rArm: part("arm", aw, 12, 4, 40, 16, 40, 32, [-(4 + aw / 2), 22, 0], [0, -4, 0]),
			lArm: part("arm", aw, 12, 4, legacy ? 40 : 32, legacy ? 16 : 48, 48, 48, [4 + aw / 2, 22, 0], [0, -4, 0]),
			rLeg: part("leg", 4, 12, 4, 0, 16, 0, 32, [-2, 12, 0], [0, -6, 0]),
			lLeg: part("leg", 4, 12, 4, legacy ? 0 : 16, legacy ? 16 : 48, 0, 48, [2, 12, 0], [0, -6, 0]),
		};
		// For emotes: how far each group's origin sits from Minecraft's joint for that part (in the part's own frame).
		limbs.offsets = [[0, 0, 0], [0, -6, 0], [1 - aw / 2, 0, 0], [aw / 2 - 1, 0, 0], [-0.1, 0, 0], [0.1, 0, 0]];
		for (const k of ["head", "body", "rArm", "lArm", "rLeg", "lLeg"]) limbs[k].userData.rest = limbs[k].position.clone();
		// All voxels of one limb in one instanced mesh.
		const byGroup = new Map();
		voxels.forEach((vx) => { if (!byGroup.has(vx.group)) byGroup.set(vx.group, []); byGroup.get(vx.group).push(vx); });
		const cube = new THREE.BoxGeometry(1, 1, 1);
		const vmat = new THREE.MeshLambertMaterial();
		const m4 = new THREE.Matrix4(), q = new THREE.Quaternion(), color = new THREE.Color();
		for (const [g, list] of byGroup) {
			const inst = new THREE.InstancedMesh(cube, vmat, list.length);
			list.forEach((vx, i) => {
				const [x, y, z, sx, sy, sz] = vx.p;
				m4.compose(new THREE.Vector3(x + vx.offset[0], y + vx.offset[1], z + vx.offset[2]), q, new THREE.Vector3(sx, sy, sz));
				inst.setMatrixAt(i, m4);
				inst.setColorAt(i, color.setRGB(vx.c[0] / 255, vx.c[1] / 255, vx.c[2] / 255, THREE.SRGBColorSpace));
			});
			g.add(inst);
		}
		return group;
	}

	async function buildCape(item) {
		const frames = await gifFrames(options.fileUrl(item.files.gif));
		const first = frames[0].canvas;
		const tex = pixelTexture(first);
		if (first.width >= 512) {
			// HD art (16 texels per cape pixel) is painted, not pixel art: filter it smoothly and mipmap it, so it
			// neither turns blocky up close nor shimmers small.
			tex.magFilter = THREE.LinearFilter;
			tex.minFilter = THREE.LinearMipmapLinearFilter;
			tex.generateMipmaps = true;
			tex.anisotropy = 8;
		}
		const tw = 64, th = 64 * first.height / first.width;
		const geo = new THREE.BoxGeometry(10, 16, 1);
		// Outside of the cape (template 1,1-11,17) faces away from the body (-z).
		mapBox(geo, [[11, 1, 12, 17], [0, 1, 1, 17], [1, 0, 11, 1], [11, 0, 21, 1], [12, 1, 22, 17], [1, 1, 11, 17]], tw, th);
		const mesh = new THREE.Mesh(geo, new THREE.MeshLambertMaterial({ map: tex, transparent: true, alphaTest: 0.3 }));
		mesh.position.set(0, -8, -0.5);
		const hinge = new THREE.Group();
		hinge.position.set(0, 24, -2.2);
		hinge.add(mesh);
		let index = 0, nextAt = 0;
		const tick = (t) => {
			hinge.rotation.x = 0.16 + Math.sin(t / 900) * 0.06 + Math.sin(t / 370) * 0.015;
			if (frames.length > 1 && t >= nextAt) {
				index = (index + 1) % frames.length;
				tex.image = frames[index].canvas;
				tex.needsUpdate = true;
				nextAt = t + frames[index].delay;
			}
		};
		return { object: hinge, tick };
	}

	async function buildWings(item) {
		const [buf, img] = await Promise.all([
			fetch(options.fileUrl(item.files.mesh)).then((r) => r.arrayBuffer()),
			loadImage(options.fileUrl(item.files.texture)),
		]);
		const halves = parseVmesh(buf);
		const tex = new THREE.Texture(img);
		tex.flipY = false;
		tex.colorSpace = THREE.SRGBColorSpace;
		tex.needsUpdate = true;
		const mat = new THREE.MeshLambertMaterial({ map: tex, side: THREE.DoubleSide, transparent: true, alphaTest: 0.4 });
		const span = Math.max(...halves.map((hf) => hf.span));
		const scale = (13 * (item.scale || 1)) / span;
		const back = new THREE.Group();
		back.position.set(0, 21 + (item.offsetY || 0) * 16, -2.6);
		const pivots = halves.map((hf) => {
			const geo = new THREE.BufferGeometry();
			geo.setAttribute("position", new THREE.BufferAttribute(hf.pos, 3));
			geo.setAttribute("normal", new THREE.BufferAttribute(hf.nrm, 3));
			geo.setAttribute("uv", new THREE.BufferAttribute(hf.uv, 2));
			geo.setIndex(new THREE.BufferAttribute(hf.idx, 1));
			const mesh = new THREE.Mesh(geo, mat);
			mesh.scale.setScalar(scale);
			const pivot = new THREE.Group();
			pivot.position.x = hf.side * 0.6;
			pivot.add(mesh);
			pivot.userData.side = hf.side;
			back.add(pivot);
			return pivot;
		});
		const tick = (t) => {
			const flap = 0.28 + Math.sin(t / 420) * 0.32;
			// Each wing turns about its shoulder hinge with its tip going backwards (-z, away from the body) - the other
			// way round swung the tips forward through the player.
			pivots.forEach((p) => { p.rotation.y = p.userData.side * flap; });
		};
		return { object: back, tick };
	}

	async function setItem(item) {
		if (worn) { player.remove(worn); worn = null; wornTick = null; }
		if (emote) {
			emote.sprites.forEach((sp) => player && player.remove(sp));
			emote = null;
			for (const k of ["head", "body", "rArm", "lArm", "rLeg", "lLeg"]) {
				if (limbs[k]) { limbs[k].position.copy(limbs[k].userData.rest); limbs[k].rotation.set(0, 0, 0, "XYZ"); }
			}
		}
		aura.hidden = true;
		auraBack.hidden = true;
		if (!item) return;
		try {
			if (item.type === "cape" && item.files && item.files.gif) {
				const c = await buildCape(item);
				worn = c.object; wornTick = c.tick; player.add(worn);
			} else if (item.type === "wings" && item.files && item.files.mesh && item.files.texture) {
				const w = await buildWings(item);
				worn = w.object; wornTick = w.tick; player.add(worn);
			} else if (item.type === "emote" && options.emoteAnim) {
				emote = { anim: await options.emoteAnim(item), start: performance.now(), fired: -1, sprites: [], pose: new Float32Array(36) };
			} else if (item.type === "aura") {
				aura.src = options.auraUrl(item.id, "front");
				auraBack.src = options.auraUrl(item.id, "back");
				aura.hidden = false;
				auraBack.hidden = false;
			}
		} catch (e) {
			console.warn("try-on", e);
		}
	}

	async function setSkin(url, slim) {
		const next = await buildPlayer(url, slim);
		if (player) {
			if (worn) { player.remove(worn); next.add(worn); }
			root.remove(player);
		}
		player = next;
		root.add(player);
	}

	await setSkin(options.skin, options.slim);
	await setItem(options.item);
	const facesBack = options.item && (options.item.type === "cape" || options.item.type === "wings");
	let yaw = facesBack ? Math.PI * 0.85 : 0.45, dragging = false, lastX = 0, lastT = 0, running = true;
	canvas.addEventListener("pointerdown", (e) => { dragging = true; lastX = e.clientX; canvas.setPointerCapture(e.pointerId); });
	canvas.addEventListener("pointermove", (e) => { if (dragging) { yaw += (e.clientX - lastX) * 0.012; lastX = e.clientX; } });
	canvas.addEventListener("pointerup", () => (dragging = false));
	canvas.addEventListener("pointercancel", () => (dragging = false));

	const resize = () => {
		const w = container.clientWidth, h = container.clientHeight;
		if (!w || !h) return;
		renderer.setSize(w, h, false);
		camera.aspect = w / h;
		camera.updateProjectionMatrix();
	};
	const ro = new ResizeObserver(resize);
	ro.observe(container);
	resize();

	const frame = (t) => {
		if (!running) return;
		if (!container.isConnected) { dispose(); return; }
		const dt = lastT ? (t - lastT) / 1000 : 0;
		lastT = t;
		if (!dragging && !reduceMotion) yaw += dt * 0.35;
		root.rotation.y = yaw;
		const walk = reduceMotion ? 0 : Math.sin(t / 380) * 0.35;
		if (emote && limbs.rArm) {
			playEmote(t);
		} else if (limbs.rArm) {
			limbs.rArm.rotation.x = walk; limbs.lArm.rotation.x = -walk;
			limbs.rLeg.rotation.x = -walk; limbs.lLeg.rotation.x = walk;
			limbs.head.rotation.y = Math.sin(t / 2300) * 0.25;
			limbs.head.rotation.x = Math.sin(t / 3100) * 0.08;
		}
		if (wornTick) wornTick(reduceMotion ? 0 : t);
		renderer.render(scene, camera);
		requestAnimationFrame(frame);
	};
	requestAnimationFrame(frame);

	// ---- Emotes ----
	const REST = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -5, 2, 0, 0, 0, 0, 5, 2, 0, 0, 0, 0, -1.9, 12, 0, 0, 0, 0, 1.9, 12, 0, 0, 0, 0];
	const glyphTextures = new Map();
	const glyphTexture = (g, color) => {
		const key = g + color;
		if (!glyphTextures.has(key)) {
			const c = document.createElement("canvas");
			c.width = c.height = 64;
			const x = c.getContext("2d");
			x.font = "bold 46px system-ui, sans-serif";
			x.textAlign = "center";
			x.textBaseline = "middle";
			x.shadowColor = "rgba(0,0,0,.45)";
			x.shadowBlur = 4;
			x.fillStyle = color;
			x.fillText(g, 32, 34);
			const tex = new THREE.CanvasTexture(c);
			tex.colorSpace = THREE.SRGBColorSpace;
			glyphTextures.set(key, tex);
		}
		return glyphTextures.get(key);
	};
	const euler = new THREE.Euler(0, 0, 0, "ZYX");
	const offset = new THREE.Vector3();

	/** Poses the six parts from the emote (Minecraft model space: y down, front -z) and lets its particles rise. */
	function playEmote(t) {
		const a = emote.anim;
		const sec = reduceMotion ? 1.2 : (t - emote.start) / 1000;
		const cycle = a.duration + (a.loop ? 0 : 0.6);
		const local = sec % cycle;
		const p = emote.pose;
		for (let i = 0; i < 36; i++) p[i] = REST[i];
		if (local <= a.duration) options.emotePose(a, local * a.fps, p);
		["head", "body", "rArm", "lArm", "rLeg", "lLeg"].forEach((k, i) => {
			const o = i * 6;
			euler.set(p[o + 3], -p[o + 4], -p[o + 5], "ZYX");
			limbs[k].rotation.copy(euler);
			offset.set(...limbs.offsets[i]).applyEuler(euler);
			limbs[k].position.set(p[o] + offset.x, 24 - p[o + 1] + offset.y, -p[o + 2] + offset.z);
		});
		if (!reduceMotion) {
			for (let k = Math.floor(Math.max(0, emote.fired) / cycle); k <= Math.floor(sec / cycle); k++) {
				for (const e of a.events) {
					const at = k * cycle + e.t;
					if (!e.particle || at <= emote.fired || at > sec) continue;
					const q = options.emoteAnchor(p, e.at);
					for (let i = 0; i < Math.min(6, Math.max(1, Math.floor((e.count || 3) / 3))); i++) {
						const [g, col] = options.emoteGlyph(e.particle, e.color, e.text);
						const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: glyphTexture(g, col), transparent: true, depthWrite: false }));
						const spread = Math.max(0.5, (e.spread || 0.3) * 16);
						sp.position.set(q[0] + (Math.random() - 0.5) * spread, 24 - q[1] + (Math.random() - 0.5) * spread, -q[2] + (Math.random() - 0.5) * spread);
						sp.scale.set(3.2, 3.2, 1);
						sp.userData = { born: t, life: 900 + Math.random() * 500, vx: (Math.random() - 0.5) * 2, vy: 3 + (e.up || 0.05) * 20 + Math.random() * 2 };
						player.add(sp);
						emote.sprites.push(sp);
					}
				}
			}
			emote.fired = sec;
		}
		for (let i = emote.sprites.length - 1; i >= 0; i--) {
			const sp = emote.sprites[i];
			const age = t - sp.userData.born;
			if (age > sp.userData.life) { player.remove(sp); sp.material.dispose(); emote.sprites.splice(i, 1); continue; }
			sp.position.x += (sp.userData.vx * 16) / 1000;
			sp.position.y += (sp.userData.vy * 16) / 1000;
			sp.material.opacity = 1 - age / sp.userData.life;
		}
	}

	function dispose() {
		running = false;
		ro.disconnect();
		renderer.dispose();
	}
	/** Plays the emote from its start again (so its music, started now, is on the beat). */
	function restartEmote() {
		if (emote) { emote.start = performance.now(); emote.fired = -1; }
	}
	return { setItem, setSkin, restartEmote, dispose };
}

window.velo = window.velo || {};
window.velo.player3d = player3d;
window.dispatchEvent(new Event("velo:skin3d"));
