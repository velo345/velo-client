package net.veloclient.velo.client.emotes;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The emotes' 3D props (a boombox, a teacup, a pumpkin...): small Meshy models remeshed to a couple of thousand
 * triangles (tools/cosmetics/emotes/import_props.py), shipped as {@code assets/velo-client/cosmetics/emote/props/<id>.vprop}
 * with their texture in {@code textures/cosmetics/emote/props/<id>.png}. Model space: y up, front towards +Z, standing
 * on y = 0, largest side 1 long. Plain Java - loaded once, on first use.
 */
public final class EmoteProps {

	/** {x, y, z, nx, ny, nz, u, v} per vertex; triangles index them. */
	public record Mesh(float[] vertices, int[] triangles) {
	}

	private static final Map<String, Mesh> CACHE = new ConcurrentHashMap<>();
	private static final Mesh MISSING = new Mesh(new float[0], new int[0]);

	private EmoteProps() {
	}

	/** The prop's mesh, or null if there is no such prop. */
	public static Mesh get(String id) {
		if (id == null || !id.matches("[a-z0-9_]{1,40}")) {
			return null;
		}
		Mesh mesh = CACHE.computeIfAbsent(id, EmoteProps::load);
		return mesh == MISSING ? null : mesh;
	}

	public static String texturePath(String id) {
		return "textures/cosmetics/emote/props/" + id + ".png";
	}

	private static Mesh load(String id) {
		try (InputStream raw = EmoteProps.class.getResourceAsStream("/assets/velo-client/cosmetics/emote/props/" + id + ".vprop")) {
			return raw == null ? MISSING : read(raw);
		} catch (IOException | RuntimeException e) {
			return MISSING;
		}
	}

	public static Mesh read(InputStream raw) throws IOException {
		DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
		byte[] magic = new byte[4];
		in.readFully(magic);
		if (!"VPRP".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII)) || in.readInt() != 1) {
			throw new IOException("Not a prop mesh");
		}
		int count = in.readInt();
		if (count < 0 || count > 100_000) {
			throw new IOException("Damaged prop mesh");
		}
		float[] vertices = new float[count * 8];
		for (int i = 0; i < vertices.length; i++) {
			vertices[i] = in.readFloat();
		}
		int indices = in.readInt();
		if (indices < 0 || indices % 3 != 0 || indices > 300_000) {
			throw new IOException("Damaged prop mesh");
		}
		int[] triangles = new int[indices];
		for (int i = 0; i < indices; i++) {
			int v = in.readInt();
			if (v < 0 || v >= count) {
				throw new IOException("Damaged prop mesh");
			}
			triangles[i] = v;
		}
		return new Mesh(vertices, triangles);
	}
}
