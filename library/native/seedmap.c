// Velo Library seed map helper - a tiny CLI around cubiomes (MIT, https://github.com/Cubitect/cubiomes).
// The library service runs it as a child process with a timeout; it never touches the network or disk.
//
//   seedmap tile <mc> <seed> <dim> <scale> <x> <z> <w> <h>        -> w*h*3 bytes RGB (biome colours) on stdout
//   seedmap structs <mc> <seed> <dim> <x0> <z0> <x1> <z1> <types>  -> "type x z" lines (viable positions only)
//   seedmap strongholds <mc> <seed> <count>                       -> "x z" lines
//   seedmap spawn <mc> <seed>                                     -> "x z"
//
// <mc> is a cubiomes version string ("1.21", "1.20", "1.8"), <dim> 0 overworld / -1 nether / 1 end.
#include "finders.h"
#include "util.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *NAMES[] = {"feature", "desert_pyramid", "jungle_temple", "swamp_hut", "igloo", "village", "ocean_ruin",
	"shipwreck", "monument", "mansion", "outpost", "ruined_portal", "ruined_portal_n", "ancient_city", "treasure",
	"mineshaft", "desert_well", "geode", "fortress", "bastion", "end_city", "end_gateway", "end_island", "trail_ruins",
	"trial_chambers"};

static int typeOf(const char *name) {
	for (int i = 0; i < FEATURE_NUM; i++) {
		if (strcmp(NAMES[i], name) == 0) return i;
	}
	return -1;
}

static int version(const char *s) {
	int mc = str2mc(s);
	return mc < 0 ? MC_NEWEST : mc;
}

int main(int argc, char **argv) {
	if (argc < 4) {
		fprintf(stderr, "usage: seedmap tile|structs|strongholds|spawn ...\n");
		return 2;
	}
	int mc = version(argv[2]);
	uint64_t seed = (uint64_t) strtoll(argv[3], NULL, 10);
	Generator g;
	setupGenerator(&g, mc, 0);

	if (strcmp(argv[1], "tile") == 0 && argc == 10) {
		int dim = atoi(argv[4]), scale = atoi(argv[5]), x = atoi(argv[6]), z = atoi(argv[7]), w = atoi(argv[8]), h = atoi(argv[9]);
		if (w <= 0 || h <= 0 || w > 512 || h > 512) return 2;
		applySeed(&g, dim, seed);
		Range r = {scale, x, z, w, h, scale == 1 ? 63 : 15, 1};
		int *ids = allocCache(&g, r);
		if (!ids || genBiomes(&g, ids, r)) return 1;
		unsigned char colors[256][3];
		initBiomeColors(colors);
		unsigned char *rgb = malloc((size_t) w * h * 3);
		for (int i = 0; i < w * h; i++) {
			int id = ids[i] & 255;
			rgb[i * 3] = colors[id][0];
			rgb[i * 3 + 1] = colors[id][1];
			rgb[i * 3 + 2] = colors[id][2];
		}
		fwrite(rgb, 1, (size_t) w * h * 3, stdout);
		return 0;
	}
	if (strcmp(argv[1], "structs") == 0 && argc == 10) {
		int dim = atoi(argv[4]), x0 = atoi(argv[5]), z0 = atoi(argv[6]), x1 = atoi(argv[7]), z1 = atoi(argv[8]);
		applySeed(&g, dim, seed);
		char *list = strdup(argv[9]);
		int found = 0;
		for (char *tok = strtok(list, ","); tok; tok = strtok(NULL, ",")) {
			int type = typeOf(tok);
			StructureConfig sc;
			if (type < 0 || !getStructureConfig(type, mc, &sc) || sc.dim != dim) continue;
			int span = sc.regionSize * 16;
			for (int rz = (int) floor((double) z0 / span); rz <= (int) floor((double) z1 / span); rz++) {
				for (int rx = (int) floor((double) x0 / span); rx <= (int) floor((double) x1 / span); rx++) {
					Pos p;
					if (!getStructurePos(type, mc, seed, rx, rz, &p)) continue;
					if (p.x < x0 || p.x > x1 || p.z < z0 || p.z > z1) continue;
					if (!isViableStructurePos(type, &g, p.x, p.z, 0)) continue;
					printf("%s %d %d\n", NAMES[type], p.x, p.z);
					if (++found >= 4000) return 0;
				}
			}
		}
		return 0;
	}
	if (strcmp(argv[1], "strongholds") == 0 && argc == 5) {
		applySeed(&g, DIM_OVERWORLD, seed);
		StrongholdIter sh;
		initFirstStronghold(&sh, mc, seed);
		int n = atoi(argv[4]);
		for (int i = 0; i < n && i < 128; i++) {
			if (nextStronghold(&sh, &g) <= 0 && i > 0) break;
			printf("%d %d\n", sh.pos.x, sh.pos.z);
		}
		return 0;
	}
	if (strcmp(argv[1], "spawn") == 0) {
		applySeed(&g, DIM_OVERWORLD, seed);
		Pos p = estimateSpawn(&g, NULL);
		printf("%d %d\n", p.x, p.z);
		return 0;
	}
	return 2;
}
