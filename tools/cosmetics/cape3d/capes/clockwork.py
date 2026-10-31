"""Clockwork's gear train: where each gear sits, its size and teeth, and the rotation offset that makes its teeth mesh
with the gear driving it. Every gear advances the same number of teeth per loop, so the train turns and still loops."""
import math

MODULE = 0.24          # cape units of pitch radius per tooth / 2
TEETH_PER_LOOP = 2     # how many teeth each gear advances per loop
MAX = 6

# (teeth, which gear drives it (-1: none), direction from the driver in degrees)
TRAIN = [
	(28, -1, 0),
	(14, 0, -35),
	(20, 1, -110),
	(10, 0, 150),
	(18, 0, 238),
	(12, 2, -40),
]
FIRST = (-0.6, 2.4)


def uniforms():
	gears = []   # (cx, cy, pitch radius, teeth)
	motion = []  # (offset, direction, 0, 0)
	for i, (teeth, driver, deg) in enumerate(TRAIN):
		r = teeth * MODULE / 2
		if driver < 0:
			gears.append((*FIRST, r, teeth))
			motion.append((0.0, 1.0, 0.0, 0.0))
			continue
		dx, dy, dr, dn = gears[driver]
		off_a, dir_a = motion[driver][0], motion[driver][1]
		th = math.radians(deg)
		cx, cy = dx + math.cos(th) * (dr + r), dy + math.sin(th) * (dr + r)
		# Driver's tooth phase at the contact direction (0 = tooth centre), with its rotation at 0.
		s0 = ((th - off_a) * dn / (2 * math.pi)) % 1.0
		# The driven gear turns the other way; its phase at the contact (seen from it, th + pi) must be half a tooth out.
		off_b = th + math.pi - (0.5 - s0) * 2 * math.pi / teeth
		gears.append((cx, cy, r, teeth))
		motion.append((off_b, -dir_a, 0.0, 0.0))
	return {"uGears": gears, "uGearMotion": motion, "uGearCount": len(gears), "uTeethPerLoop": float(TEETH_PER_LOOP)}


if __name__ == "__main__":
	for g, m in zip(uniforms()["uGears"], uniforms()["uGearMotion"]):
		print([round(v, 2) for v in g], [round(v, 2) for v in m[:2]])
