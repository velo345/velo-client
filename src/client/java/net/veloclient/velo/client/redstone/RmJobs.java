package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Placement jobs run a slice per server tick, so even huge pastes, erases, stacks and undos never
 * freeze the game for long: "Paste Speed" blocks per tick (0 = everything at once). Order: blocks
 * first (silently - no updates, observers stay quiet), then block entities, then the redstone
 * wake-up (pending ticks back, neighbour updates), and entities last, once every block is in place.
 * The blocks a job replaces are remembered sparsely for undo. End cancels the running job.
 */
public final class RmJobs {

	/** What to place: dense ({@code indices == null}) or sparse (indices into the box). */
	static final class Source {
		final int sx;
		final int sy;
		final int sz;
		final int[] indices;
		final Object[] states;
		final Object[] blockEntities;
		/** Scheduled ticks to restore: {box index, delay, priority, fluid} (see {@link Clip#ticks}). */
		final List<int[]> ticks;
		final List<Object[]> entities;

		Source(int sx, int sy, int sz, int[] indices, Object[] states, Object[] blockEntities, List<int[]> ticks, List<Object[]> entities) {
			this.sx = sx;
			this.sy = sy;
			this.sz = sz;
			this.indices = indices;
			this.states = states;
			this.blockEntities = blockEntities;
			this.ticks = ticks == null ? List.of() : ticks;
			this.entities = entities;
		}

		static Source of(Clip clip) {
			return new Source(clip.sx, clip.sy, clip.sz, null, clip.states, clip.blockEntities, clip.ticks, clip.entities);
		}

		int count() {
			return indices != null ? indices.length : states.length;
		}

		int boxIndex(int i) {
			return indices != null ? indices[i] : i;
		}
	}

	/** Sparse record of what a job replaced (for undo). */
	static final class Changes {
		int size;
		int[] indices = new int[256];
		Object[] states = new Object[256];
		Object[] blockEntities = new Object[256];

		void add(int index, Object state, Object be) {
			if (size == indices.length) {
				int n = size * 2;
				indices = java.util.Arrays.copyOf(indices, n);
				states = java.util.Arrays.copyOf(states, n);
				blockEntities = java.util.Arrays.copyOf(blockEntities, n);
			}
			indices[size] = index;
			states[size] = state;
			blockEntities[size] = be;
			size++;
		}

		Source asSource(int sx, int sy, int sz) {
			return new Source(sx, sy, sz, java.util.Arrays.copyOf(indices, size), java.util.Arrays.copyOf(states, size),
					java.util.Arrays.copyOf(blockEntities, size), null, List.of());
		}
	}

	static final class Job {
		final Object level;
		final Source source;
		final int x0;
		final int y0;
		final int z0;
		final boolean skipAir;
		final boolean keepRunning;
		final boolean recordUndo;
		final String label;
		final Runnable onDone;
		/** Jobs sharing a non-zero group (one stack, one move) become a single undo step. */
		int group;
		final Changes changes = new Changes();
		int phase;
		int cursor;
		boolean cancelled;

		Job(Object level, Source source, int x0, int y0, int z0, boolean skipAir, boolean keepRunning, boolean recordUndo, String label,
				Runnable onDone) {
			this.level = level;
			this.source = source;
			this.x0 = x0;
			this.y0 = y0;
			this.z0 = z0;
			this.skipAir = skipAir;
			this.keepRunning = keepRunning;
			this.recordUndo = recordUndo;
			this.label = label;
			this.onDone = onDone;
		}
	}

	/**
	 * Neighbour updates from every redstone-relevant block just outside the pasted box, plus from the
	 * box's own outer shell: pasted dust next to a power source picks it up, dust that was powered by
	 * something outside the copy turns off, pistons outside react. Skipped for enormous boxes.
	 */
	private static void wakeBorder(Object level, int x0, int y0, int z0, int sx, int sy, int sz) {
		long shell = 2L * ((long) (sx + 2) * (sy + 2) + (long) (sx + 2) * (sz + 2) + (long) (sy + 2) * (sz + 2));
		if (shell > 2_000_000) {
			return;
		}
		for (int x = x0 - 1; x <= x0 + sx; x++) {
			for (int y = y0 - 1; y <= y0 + sy; y++) {
				for (int z = z0 - 1; z <= z0 + sz; z++) {
					boolean outside = x < x0 || y < y0 || z < z0 || x >= x0 + sx || y >= y0 + sy || z >= z0 + sz;
					if (!outside) {
						continue;
					}
					Object state = RmWorld.state(level, x, y, z);
					if (!RmWorld.isAir(state)) {
						RmWorld.neighborUpdate(level, x, y, z);
					}
				}
			}
		}
		// The shell's own redstone parts: they update their neighbours, which includes the outside.
		for (int x = x0; x < x0 + sx; x++) {
			for (int y = y0; y < y0 + sy; y++) {
				for (int z = z0; z < z0 + sz; z++) {
					boolean edge = x == x0 || y == y0 || z == z0 || x == x0 + sx - 1 || y == y0 + sy - 1 || z == z0 + sz - 1;
					if (!edge) {
						z = z0 + sz - 2;
						continue;
					}
					Object state = RmWorld.state(level, x, y, z);
					if (!RmWorld.isAir(state) && RmOps.isRedstone(RmWorld.id(state))) {
						RmWorld.neighborUpdate(level, x, y, z);
					}
				}
			}
		}
	}

	private static final Deque<Job> QUEUE = new ArrayDeque<>();
	private static volatile String status;
	private static volatile double progress;

	private RmJobs() {
	}

	/** "Pasting 42%" etc. for the overlay, or null when idle. */
	public static String status() {
		return status;
	}

	public static double progress() {
		return progress;
	}

	/** Server thread. */
	static synchronized void submit(Job job) {
		QUEUE.addLast(job);
		status = job.label + " 0%";
	}

	/** End key: stops the running job (what's placed so far stays and can be undone). */
	public static void cancelCurrent() {
		RmWorld.onServer(level -> {
			synchronized (RmJobs.class) {
				Job job = QUEUE.peekFirst();
				if (job != null) {
					job.cancelled = true;
				}
			}
		});
	}

	public static synchronized void cancelAll() {
		QUEUE.clear();
		status = null;
	}

	/** Server thread, every level tick. */
	public static void serverTick(Object level) {
		Job job;
		synchronized (RmJobs.class) {
			job = QUEUE.peekFirst();
		}
		if (job == null || job.level != level) {
			return;
		}
		int speed = RmSettings.get().pasteSpeed;
		long budget = speed <= 0 ? Long.MAX_VALUE : speed;
		Source src = job.source;
		int n = src.count();
		int layer = src.sy * src.sz;
		if (job.cancelled) {
			job.phase = 4;
		}
		while (budget > 0 && job.phase < 4) {
			switch (job.phase) {
				case 0 -> {
					while (budget > 0 && job.cursor < n) {
						int i = job.cursor++;
						Object state = src.states[i];
						if (state == null || job.skipAir && RmWorld.isAir(state)) {
							budget -= 0;
							continue;
						}
						int b = src.boxIndex(i);
						int x = job.x0 + b / layer;
						int y = job.y0 + (b / src.sz) % src.sy;
						int z = job.z0 + b % src.sz;
						Object old = RmWorld.state(level, x, y, z);
						if (old == state) {
							continue;
						}
						if (job.recordUndo) {
							job.changes.add(b, old, RmWorld.isAir(old) ? null : RmWorld.saveBe(level, x, y, z));
						}
						RmWorld.set(level, x, y, z, state, RmWorld.SILENT);
						budget--;
					}
					if (job.cursor >= n) {
						job.phase = 1;
						job.cursor = 0;
					}
				}
				case 1 -> {
					while (budget > 0 && job.cursor < n) {
						int i = job.cursor++;
						Object tag = src.blockEntities == null ? null : src.blockEntities[i];
						if (tag != null) {
							int b = src.boxIndex(i);
							RmWorld.loadBe(level, job.x0 + b / layer, job.y0 + (b / src.sz) % src.sy, job.z0 + b % src.sz, tag);
							budget -= 4;
						}
					}
					if (job.cursor >= n) {
						job.phase = job.keepRunning ? 2 : 3;
						job.cursor = 0;
					}
				}
				case 2 -> {
					// Wake-up: the pasted build is a consistent snapshot, so its inside is left exactly
					// as captured - only the ticks it had scheduled come back (same delay, priority
					// and order), then the blocks around its outside get a neighbour update so the
					// build connects to (or disconnects from) whatever it was pasted next to.
					for (int[] t : src.ticks) {
						int b = t[0];
						RmWorld.scheduleExact(level, job.x0 + b / layer, job.y0 + (b / src.sz) % src.sy, job.z0 + b % src.sz, t[1], t[2], t[3] == 1);
					}
					budget -= src.ticks.size();
					if (src.indices == null) {
						wakeBorder(level, job.x0, job.y0, job.z0, src.sx, src.sy, src.sz);
					} else {
						for (int i = 0; i < n; i++) {
							int b = src.boxIndex(i);
							RmWorld.neighborUpdate(level, job.x0 + b / layer, job.y0 + (b / src.sz) % src.sy, job.z0 + b % src.sz);
						}
					}
					job.phase = 3;
					job.cursor = 0;
				}
				case 3 -> {
					for (Object[] e : src.entities) {
						RmWorld.spawnEntity(level, e[4], job.x0 + (double) e[0], job.y0 + (double) e[1], job.z0 + (double) e[2], (float) e[3]);
					}
					job.phase = 4;
				}
				default -> job.phase = 4;
			}
		}
		double done = job.phase >= 4 ? 1 : job.phase == 0 ? job.cursor / (double) Math.max(1, n) * 0.8
				: 0.8 + job.phase * 0.05;
		progress = done;
		status = job.label + " " + Math.round(done * 100) + "%";
		if (job.phase >= 4) {
			synchronized (RmJobs.class) {
				QUEUE.pollFirst();
				status = QUEUE.isEmpty() ? null : QUEUE.peekFirst().label + " 0%";
			}
			if (job.recordUndo && job.changes.size > 0) {
				RmOps.rememberJob(job, src.sx, src.sy, src.sz);
			}
			boolean cancelled = job.cancelled;
			MinecraftClient.getInstance().execute(() -> {
				if (cancelled) {
					RmState.error(job.label + " stopped - what was placed stays (Ctrl+Z undoes it)");
				} else if (job.onDone != null) {
					job.onDone.run();
				}
			});
		}
	}
}
