package net.veloclient.velo.client.cosmetics.wings;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A pair of 3D wings loaded from a bundled {@code .vmesh} (made from a Meshy .glb by
 * {@code tools/cosmetics/import_wings.py}): two halves, each with its own shoulder hinge, already
 * converted to the body's axes (x sideways, y down, z backwards, in blocks) so the animator only
 * has to rotate them around their hinge.
 */
public final class WingModel {

	/** One wing. Positions are relative to the hinge; {@code weight} is 0 at the shoulder, 1 at the tip. */
	public static final class Half {
		/** Side in body space: -1 = the wearer's right (body -x), +1 = the left. */
		public final int side;
		/** {x, y, z, nx, ny, nz} per vertex, relative to the hinge, body axes. */
		public final float[] geometry;
		public final float[] uv;
		public final float[] weight;
		public final int[] triangles;

		Half(int side, float[] geometry, float[] uv, float[] weight, int[] triangles) {
			this.side = side;
			this.geometry = geometry;
			this.uv = uv;
			this.weight = weight;
			this.triangles = triangles;
		}

		public int vertexCount() {
			return uv.length / 2;
		}
	}

	public final Half[] halves;

	private WingModel(Half[] halves) {
		this.halves = halves;
	}

	/** Reads {@code /assets/velo-client/cosmetics/wings/<id>.vmesh}, scaled to blocks. */
	public static WingModel load(String id, float scale) throws IOException {
		String path = "/assets/velo-client/cosmetics/wings/" + id + ".vmesh";
		try (InputStream raw = WingModel.class.getResourceAsStream(path)) {
			if (raw == null) {
				throw new IOException("Missing " + path);
			}
			DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
			byte[] magic = new byte[4];
			in.readFully(magic);
			if (!"VMSH".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII)) || in.readInt() != 1) {
				throw new IOException("Not a wing mesh: " + path);
			}
			int count = in.readInt();
			if (count < 1 || count > 4) {
				throw new IOException("Damaged wing mesh: " + path);
			}
			Half[] halves = new Half[count];
			for (int h = 0; h < count; h++) {
				float hx = in.readFloat();
				float hy = in.readFloat();
				float hz = in.readFloat();
				float span = Math.max(1e-3f, in.readFloat());
				int vertices = in.readInt();
				if (vertices < 0 || vertices > 200_000) {
					throw new IOException("Damaged wing mesh: " + path);
				}
				float[] geometry = new float[vertices * 6];
				float[] uv = new float[vertices * 2];
				float[] weight = new float[vertices];
				float sideSum = 0;
				for (int v = 0; v < vertices; v++) {
					float px = in.readFloat() - hx;
					float py = in.readFloat() - hy;
					float pz = in.readFloat() - hz;
					float nx = in.readFloat();
					float ny = in.readFloat();
					float nz = in.readFloat();
					// Model space (y up, +z towards the wearer) -> body space (y down, +z backwards):
					// a half turn around x, which keeps left and right where they are.
					geometry[v * 6] = px * scale;
					geometry[v * 6 + 1] = -py * scale;
					geometry[v * 6 + 2] = -pz * scale;
					geometry[v * 6 + 3] = nx;
					geometry[v * 6 + 4] = -ny;
					geometry[v * 6 + 5] = -nz;
					uv[v * 2] = in.readFloat();
					uv[v * 2 + 1] = in.readFloat();
					weight[v] = Math.min(1f, Math.abs(px) / span);
					sideSum += px;
				}
				int indexCount = in.readInt();
				if (indexCount < 0 || indexCount % 3 != 0 || indexCount > 600_000) {
					throw new IOException("Damaged wing mesh: " + path);
				}
				int[] triangles = new int[indexCount];
				for (int i = 0; i < indexCount; i++) {
					int index = in.readInt();
					if (index < 0 || index >= vertices) {
						throw new IOException("Damaged wing mesh: " + path);
					}
					triangles[i] = index;
				}
				halves[h] = new Half(sideSum < 0 ? -1 : 1, geometry, uv, weight, triangles);
			}
			return new WingModel(halves);
		}
	}
}
