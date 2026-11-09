package net.veloclient.library;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A schema-less protobuf reader: a message is field number -> values (Long for varints/fixed, byte[] for length-delimited). */
final class Proto {

	private final Map<Integer, List<Object>> fields = new HashMap<>();

	private Proto() {
	}

	static Proto parse(byte[] data) {
		return parse(data, 0, data.length);
	}

	static Proto parse(byte[] d, int off, int len) {
		Proto p = new Proto();
		int[] pos = {off};
		int end = off + len;
		while (pos[0] < end) {
			long key = varint(d, pos);
			int field = (int) (key >>> 3);
			int wire = (int) (key & 7);
			Object v;
			switch (wire) {
				case 0 -> v = varint(d, pos);
				case 1 -> {
					v = ByteBuffer.wrap(d, pos[0], 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
					pos[0] += 8;
				}
				case 2 -> {
					int n = (int) varint(d, pos);
					if (n < 0 || pos[0] + n > end) {
						throw new IllegalArgumentException("bad length");
					}
					byte[] b = new byte[n];
					System.arraycopy(d, pos[0], b, 0, n);
					pos[0] += n;
					v = b;
				}
				case 5 -> {
					v = (long) ByteBuffer.wrap(d, pos[0], 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
					pos[0] += 4;
				}
				default -> throw new IllegalArgumentException("unsupported wire type " + wire);
			}
			p.fields.computeIfAbsent(field, k -> new ArrayList<>(1)).add(v);
		}
		return p;
	}

	private static long varint(byte[] d, int[] pos) {
		long r = 0;
		for (int shift = 0; shift < 64; shift += 7) {
			byte b = d[pos[0]++];
			r |= (long) (b & 0x7f) << shift;
			if ((b & 0x80) == 0) {
				return r;
			}
		}
		throw new IllegalArgumentException("varint too long");
	}

	List<Object> all(int f) {
		return fields.getOrDefault(f, List.of());
	}

	boolean has(int f) {
		return fields.containsKey(f);
	}

	Proto msg(int f) {
		List<Object> v = all(f);
		return v.isEmpty() || !(v.get(0) instanceof byte[] b) ? null : parse(b);
	}

	List<Proto> msgs(int f) {
		List<Proto> out = new ArrayList<>();
		for (Object o : all(f)) {
			if (o instanceof byte[] b) {
				out.add(parse(b));
			}
		}
		return out;
	}

	String str(int f) {
		List<Object> v = all(f);
		return v.isEmpty() || !(v.get(0) instanceof byte[] b) ? null : new String(b, StandardCharsets.UTF_8);
	}

	long num(int f) {
		List<Object> v = all(f);
		return v.isEmpty() || !(v.get(0) instanceof Long l) ? 0 : l;
	}

	double dbl(int f) {
		return Double.longBitsToDouble(num(f));
	}

	/** Packed (or repeated) doubles. */
	double[] doubles(int f) {
		List<Double> out = new ArrayList<>();
		for (Object o : all(f)) {
			if (o instanceof byte[] b) {
				ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
				while (bb.remaining() >= 8) {
					out.add(bb.getDouble());
				}
			} else if (o instanceof Long l) {
				out.add(Double.longBitsToDouble(l));
			}
		}
		double[] r = new double[out.size()];
		for (int i = 0; i < r.length; i++) {
			r[i] = out.get(i);
		}
		return r;
	}

	/** Packed (or repeated) varint ints. */
	int[] ints(int f) {
		List<Integer> out = new ArrayList<>();
		for (Object o : all(f)) {
			if (o instanceof byte[] b) {
				int[] pos = {0};
				while (pos[0] < b.length) {
					out.add((int) varint(b, pos));
				}
			} else if (o instanceof Long l) {
				out.add((int) (long) l);
			}
		}
		int[] r = new int[out.size()];
		for (int i = 0; i < r.length; i++) {
			r[i] = out.get(i);
		}
		return r;
	}

	/** map<string,string> (entries with key 1, value 2). */
	Map<String, String> stringMap(int f) {
		Map<String, String> m = new HashMap<>();
		for (Proto e : msgs(f)) {
			String k = e.str(1);
			if (k != null) {
				m.put(k, e.str(2));
			}
		}
		return m;
	}

	/** map<string,Message>. */
	Map<String, Proto> msgMap(int f) {
		Map<String, Proto> m = new HashMap<>();
		for (Proto e : msgs(f)) {
			String k = e.str(1);
			Proto v = e.msg(2);
			if (k != null && v != null) {
				m.put(k, v);
			}
		}
		return m;
	}
}
