package net.veloclient.velo.client.lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Ear-clipping triangulation of a polygon with holes (after mapbox/earcut, without the z-order hash: letters have a few
 * hundred points at most). {@code data} is x,y pairs: the outer ring first, then each hole ring starting at
 * {@code holeStarts} (vertex indices). Returns triangle vertex indices.
 */
final class Earcut {

	private static final class Node {
		final int i;
		final double x;
		final double y;
		Node prev;
		Node next;
		boolean steiner;

		Node(int i, double x, double y) {
			this.i = i;
			this.x = x;
			this.y = y;
		}
	}

	private Earcut() {
	}

	static int[] triangulate(double[] data, int[] holeStarts) {
		boolean hasHoles = holeStarts.length > 0;
		int outerLen = hasHoles ? holeStarts[0] * 2 : data.length;
		Node outer = linkedList(data, 0, outerLen, true);
		List<Integer> tris = new ArrayList<>();
		if (outer == null || outer.next == outer.prev) {
			return new int[0];
		}
		if (hasHoles) {
			outer = eliminateHoles(data, holeStarts, outer);
		}
		earcutLinked(outer, tris, 0);
		int[] out = new int[tris.size()];
		for (int k = 0; k < out.length; k++) {
			out[k] = tris.get(k);
		}
		return out;
	}

	private static Node linkedList(double[] data, int start, int end, boolean clockwise) {
		Node last = null;
		if (clockwise == (signedArea(data, start, end) > 0)) {
			for (int i = start; i < end; i += 2) {
				last = insertNode(i / 2, data[i], data[i + 1], last);
			}
		} else {
			for (int i = end - 2; i >= start; i -= 2) {
				last = insertNode(i / 2, data[i], data[i + 1], last);
			}
		}
		if (last != null && equals(last, last.next)) {
			removeNode(last);
			last = last.next;
		}
		return last;
	}

	private static Node filterPoints(Node start, Node end) {
		if (start == null) {
			return null;
		}
		if (end == null) {
			end = start;
		}
		Node p = start;
		boolean again;
		do {
			again = false;
			if (!p.steiner && (equals(p, p.next) || area(p.prev, p, p.next) == 0)) {
				removeNode(p);
				p = end = p.prev;
				if (p == p.next) {
					break;
				}
				again = true;
			} else {
				p = p.next;
			}
		} while (again || p != end);
		return end;
	}

	private static void earcutLinked(Node ear, List<Integer> tris, int pass) {
		if (ear == null) {
			return;
		}
		Node stop = ear;
		while (ear.prev != ear.next) {
			Node prev = ear.prev;
			Node next = ear.next;
			if (isEar(ear)) {
				tris.add(prev.i);
				tris.add(ear.i);
				tris.add(next.i);
				removeNode(ear);
				ear = next.next;
				stop = next.next;
				continue;
			}
			ear = next;
			if (ear == stop) {
				if (pass == 0) {
					earcutLinked(filterPoints(ear, null), tris, 1);
				} else if (pass == 1) {
					ear = cureLocalIntersections(filterPoints(ear, null), tris);
					earcutLinked(ear, tris, 2);
				} else if (pass == 2) {
					splitEarcut(ear, tris);
				}
				break;
			}
		}
	}

	private static boolean isEar(Node ear) {
		Node a = ear.prev;
		Node b = ear;
		Node c = ear.next;
		if (area(a, b, c) >= 0) {
			return false;
		}
		Node p = ear.next.next;
		while (p != ear.prev) {
			if (pointInTriangle(a.x, a.y, b.x, b.y, c.x, c.y, p.x, p.y) && area(p.prev, p, p.next) >= 0) {
				return false;
			}
			p = p.next;
		}
		return true;
	}

	private static Node cureLocalIntersections(Node start, List<Integer> tris) {
		Node p = start;
		do {
			Node a = p.prev;
			Node b = p.next.next;
			if (!equals(a, b) && intersects(a, p, p.next, b) && locallyInside(a, b) && locallyInside(b, a)) {
				tris.add(a.i);
				tris.add(p.i);
				tris.add(b.i);
				removeNode(p);
				removeNode(p.next);
				p = start = b;
			}
			p = p.next;
		} while (p != start);
		return filterPoints(p, null);
	}

	private static void splitEarcut(Node start, List<Integer> tris) {
		Node a = start;
		do {
			Node b = a.next.next;
			while (b != a.prev) {
				if (a.i != b.i && isValidDiagonal(a, b)) {
					Node c = splitPolygon(a, b);
					a = filterPoints(a, a.next);
					c = filterPoints(c, c.next);
					earcutLinked(a, tris, 0);
					earcutLinked(c, tris, 0);
					return;
				}
				b = b.next;
			}
			a = a.next;
		} while (a != start);
	}

	private static Node eliminateHoles(double[] data, int[] holeStarts, Node outer) {
		List<Node> queue = new ArrayList<>();
		for (int i = 0; i < holeStarts.length; i++) {
			int start = holeStarts[i] * 2;
			int end = i < holeStarts.length - 1 ? holeStarts[i + 1] * 2 : data.length;
			Node list = linkedList(data, start, end, false);
			if (list == null) {
				continue;
			}
			if (list == list.next) {
				list.steiner = true;
			}
			queue.add(getLeftmost(list));
		}
		queue.sort((a, b) -> Double.compare(a.x, b.x));
		for (Node hole : queue) {
			outer = eliminateHole(hole, outer);
		}
		return outer;
	}

	private static Node eliminateHole(Node hole, Node outer) {
		Node bridge = findHoleBridge(hole, outer);
		if (bridge == null) {
			return outer;
		}
		Node reverse = splitPolygon(bridge, hole);
		filterPoints(reverse, reverse.next);
		return filterPoints(bridge, bridge.next);
	}

	private static Node findHoleBridge(Node hole, Node outer) {
		Node p = outer;
		double hx = hole.x;
		double hy = hole.y;
		double qx = Double.NEGATIVE_INFINITY;
		Node m = null;
		do {
			if (hy <= p.y && hy >= p.next.y && p.next.y != p.y) {
				double x = p.x + (hy - p.y) * (p.next.x - p.x) / (p.next.y - p.y);
				if (x <= hx && x > qx) {
					qx = x;
					m = p.x < p.next.x ? p : p.next;
					if (x == hx) {
						return m;
					}
				}
			}
			p = p.next;
		} while (p != outer);
		if (m == null) {
			return null;
		}
		Node stop = m;
		double mx = m.x;
		double my = m.y;
		double tanMin = Double.POSITIVE_INFINITY;
		p = m;
		do {
			if (hx >= p.x && p.x >= mx && hx != p.x
					&& pointInTriangle(hy < my ? hx : qx, hy, mx, my, hy < my ? qx : hx, hy, p.x, p.y)) {
				double tan = Math.abs(hy - p.y) / (hx - p.x);
				if (locallyInside(p, hole) && (tan < tanMin || (tan == tanMin && (p.x > m.x || (p.x == m.x && sectorContainsSector(m, p)))))) {
					m = p;
					tanMin = tan;
				}
			}
			p = p.next;
		} while (p != stop);
		return m;
	}

	private static boolean sectorContainsSector(Node m, Node p) {
		return area(m.prev, m, p.prev) < 0 && area(p.next, m, m.next) < 0;
	}

	private static Node getLeftmost(Node start) {
		Node p = start;
		Node leftmost = start;
		do {
			if (p.x < leftmost.x || (p.x == leftmost.x && p.y < leftmost.y)) {
				leftmost = p;
			}
			p = p.next;
		} while (p != start);
		return leftmost;
	}

	private static boolean pointInTriangle(double ax, double ay, double bx, double by, double cx, double cy, double px, double py) {
		return (cx - px) * (ay - py) >= (ax - px) * (cy - py) && (ax - px) * (by - py) >= (bx - px) * (ay - py)
				&& (bx - px) * (cy - py) >= (cx - px) * (by - py);
	}

	private static boolean isValidDiagonal(Node a, Node b) {
		return a.next.i != b.i && a.prev.i != b.i && !intersectsPolygon(a, b)
				&& (locallyInside(a, b) && locallyInside(b, a) && middleInside(a, b) && (area(a.prev, a, b.prev) != 0 || area(a, b.prev, b) != 0)
				|| equals(a, b) && area(a.prev, a, a.next) > 0 && area(b.prev, b, b.next) > 0);
	}

	private static double area(Node p, Node q, Node r) {
		return (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y);
	}

	private static boolean equals(Node a, Node b) {
		return a.x == b.x && a.y == b.y;
	}

	private static boolean intersects(Node p1, Node q1, Node p2, Node q2) {
		int o1 = sign(area(p1, q1, p2));
		int o2 = sign(area(p1, q1, q2));
		int o3 = sign(area(p2, q2, p1));
		int o4 = sign(area(p2, q2, q1));
		if (o1 != o2 && o3 != o4) {
			return true;
		}
		return o1 == 0 && onSegment(p1, p2, q1) || o2 == 0 && onSegment(p1, q2, q1) || o3 == 0 && onSegment(p2, p1, q2) || o4 == 0 && onSegment(p2, q1, q2);
	}

	private static boolean onSegment(Node p, Node q, Node r) {
		return q.x <= Math.max(p.x, r.x) && q.x >= Math.min(p.x, r.x) && q.y <= Math.max(p.y, r.y) && q.y >= Math.min(p.y, r.y);
	}

	private static int sign(double v) {
		return v > 0 ? 1 : v < 0 ? -1 : 0;
	}

	private static boolean intersectsPolygon(Node a, Node b) {
		Node p = a;
		do {
			if (p.i != a.i && p.next.i != a.i && p.i != b.i && p.next.i != b.i && intersects(p, p.next, a, b)) {
				return true;
			}
			p = p.next;
		} while (p != a);
		return false;
	}

	private static boolean locallyInside(Node a, Node b) {
		return area(a.prev, a, a.next) < 0 ? area(a, b, a.next) >= 0 && area(a, a.prev, b) >= 0 : area(a, b, a.prev) < 0 || area(a, a.next, b) < 0;
	}

	private static boolean middleInside(Node a, Node b) {
		Node p = a;
		boolean inside = false;
		double px = (a.x + b.x) / 2;
		double py = (a.y + b.y) / 2;
		do {
			if (((p.y > py) != (p.next.y > py)) && p.next.y != p.y && (px < (p.next.x - p.x) * (py - p.y) / (p.next.y - p.y) + p.x)) {
				inside = !inside;
			}
			p = p.next;
		} while (p != a);
		return inside;
	}

	private static Node splitPolygon(Node a, Node b) {
		Node a2 = new Node(a.i, a.x, a.y);
		Node b2 = new Node(b.i, b.x, b.y);
		Node an = a.next;
		Node bp = b.prev;
		a.next = b;
		b.prev = a;
		a2.next = an;
		an.prev = a2;
		b2.next = a2;
		a2.prev = b2;
		bp.next = b2;
		b2.prev = bp;
		return b2;
	}

	private static Node insertNode(int i, double x, double y, Node last) {
		Node p = new Node(i, x, y);
		if (last == null) {
			p.prev = p;
			p.next = p;
		} else {
			p.next = last.next;
			p.prev = last;
			last.next.prev = p;
			last.next = p;
		}
		return p;
	}

	private static void removeNode(Node p) {
		p.next.prev = p.prev;
		p.prev.next = p.next;
	}

	private static double signedArea(double[] data, int start, int end) {
		double sum = 0;
		for (int i = start, j = end - 2; i < end; i += 2) {
			sum += (data[j] - data[i]) * (data[i + 1] + data[j + 1]);
			j = i;
		}
		return sum;
	}

	static double[] concat(List<double[]> rings, int[] holeStartsOut) {
		int total = 0;
		for (double[] r : rings) {
			total += r.length;
		}
		double[] out = new double[total];
		int at = 0;
		for (int k = 0; k < rings.size(); k++) {
			if (k > 0) {
				holeStartsOut[k - 1] = at / 2;
			}
			System.arraycopy(rings.get(k), 0, out, at, rings.get(k).length);
			at += rings.get(k).length;
		}
		return out;
	}
}
