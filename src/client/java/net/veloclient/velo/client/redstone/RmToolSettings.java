package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloUi;

import java.util.List;

/** The side panel's content for the current tool: its settings, what it's about to do, and buttons. */
final class RmToolSettings {

	private RmToolSettings() {
	}

	/** Appends draw steps at x/y (width {@code w}) and returns the y below them. */
	static int layout(RmHud.Panel panel, DrawContext context, int x, int y, int w, List<Runnable> draw) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		RmSettings.Data s = RmSettings.get();
		int cy = y;
		if (RmState.pasting) {
			Clip clip = RmOps.clipboard();
			int ly = cy;
			if (clip != null) {
				draw.add(() -> context.drawTextWithShadow(textRenderer, clip.sx + " x " + clip.sy + " x " + clip.sz + "  ·  " + clip.solidCount() + " blocks",
						x, ly, RmHud.TEXT));
				cy += 14;
			}
			int by = cy;
			draw.add(() -> {
				int bx = x;
				bx += panel.textButton(context, bx, by, 0, "Rotate", false, () -> RmState.pasteRotation = (RmState.pasteRotation + 1) % 4) + 4;
				bx += panel.textButton(context, bx, by, 0, "Flip", false, () -> RmState.pasteMirror = !RmState.pasteMirror) + 4;
				panel.textButton(context, bx, by, 0, "Cancel", false, () -> RmState.pasting = false);
			});
			return cy + 18;
		}
		Placements.Placement following = Placements.following();
		if (following != null) {
			int by = cy;
			draw.add(() -> {
				int bx = x;
				bx += panel.textButton(context, bx, by, 0, "Rotate", false, () -> following.rotation = (following.rotation + 1) % 4) + 4;
				bx += panel.textButton(context, bx, by, 0, "Flip", false, () -> following.mirror = !following.mirror) + 4;
				panel.textButton(context, bx, by, 0, "Lock here", true, () -> following.locked = true);
			});
			return cy + 18;
		}
		int[] size = RmState.size();
		String selection = size == null ? "No selection" : size[0] + " x " + size[1] + " x " + size[2] + "  (" + RmState.volume() + ")";
		switch (RmState.tool) {
			case SELECT -> {
				int ly = cy;
				draw.add(() -> context.drawTextWithShadow(textRenderer, VeloUi.trim(selection, w), x, ly, 0xFF4FD8FF));
				cy += 14;
				if (size != null) {
					int by = cy;
					draw.add(() -> {
						int bx = x;
						bx += panel.textButton(context, bx, by, 0, "Copy", false, () -> RmOps.copy(false)) + 4;
						bx += panel.textButton(context, bx, by, 0, "Cut", false, () -> RmOps.copy(true)) + 4;
						panel.textButton(context, bx, by, 0, "Erase", false, RmOps::erase);
					});
					cy += 18;
					int by2 = cy;
					draw.add(() -> {
						int bx = x;
						bx += panel.textButton(context, bx, by2, 0, "Replace...", false, () -> MinecraftClient.getInstance().setScreen(new ReplaceScreen())) + 4;
						panel.textButton(context, bx, by2, 0, "Save...", false, () -> MinecraftClient.getInstance().setScreen(new SchematicSaveScreen()));
					});
					cy += 18;
					int by3 = cy;
					draw.add(() -> {
						int bx = x;
						bx += panel.textButton(context, bx, by3, 0, "Record (K)", true, Timeline::toggle) + 4;
						panel.textButton(context, bx, by3, 0, "Clear", false, RmState::clearSelection);
					});
					cy += 18;
				}
			}
			case MOVE -> {
				int[] o = RmState.moveOffset;
				String move = size == null ? "Select an area first" : "Offset " + o[0] + ", " + o[1] + ", " + o[2]
						+ (RmState.moveRotation != 0 ? "  ·  " + RmState.moveRotation * 90 + "°" : "") + (RmState.moveMirror ? "  ·  flipped" : "");
				int ly = cy;
				draw.add(() -> context.drawTextWithShadow(textRenderer, VeloUi.trim(move, w), x, ly, RmHud.TEXT));
				cy += 14;
				int by = cy;
				draw.add(() -> {
					int bx = x;
					bx += panel.textButton(context, bx, by, 0, "Rotate", false, () -> RmState.moveRotation = (RmState.moveRotation + 1) % 4) + 4;
					bx += panel.textButton(context, bx, by, 0, "Flip", false, () -> RmState.moveMirror = !RmState.moveMirror) + 4;
					bx += panel.textButton(context, bx, by, 0, "Reset", false, RmState::resetTransforms) + 4;
					panel.textButton(context, bx, by, 0, "Apply", true, RmOps::applyMove);
				});
				cy += 18;
			}
			case STACK -> {
				String stack = size == null ? "Select an area first" : RmState.stackCopies() == 0 ? "Scroll while looking along an axis"
						: RmState.stackCopies() + " copies  ·  " + stackAxes();
				int ly = cy;
				draw.add(() -> context.drawTextWithShadow(textRenderer, VeloUi.trim(stack, w), x, ly, RmHud.TEXT));
				cy += 14;
				int by = cy;
				draw.add(() -> {
					int bx = x;
					bx += panel.textButton(context, bx, by, 0, "Reset", false, RmState::resetTransforms) + 4;
					panel.textButton(context, bx, by, 0, "Apply", true, RmOps::applyStack);
				});
				cy += 18;
			}
			case WIRE -> {
				cy = row(panel, context, draw, x, cy, w, "Type", s.wireStyle, () -> {
					s.wireStyle = "Dust".equals(s.wireStyle) ? "Observer" : "Dust";
					changed();
				});
				cy = row(panel, context, draw, x, cy, w, "Block", pretty(s.wireBlock), () -> pick("Wire block", "wireBlock"));
				cy = row(panel, context, draw, x, cy, w, "Path", s.pathMode, () -> {
					s.pathMode = "Go Around".equals(s.pathMode) ? "Go Through" : "Go Around";
					changed();
				});
				cy = row(panel, context, draw, x, cy, w, "Delay mode", s.delayMode ? "On" : "Off", () -> {
					s.delayMode = !s.delayMode;
					changed();
				});
				if (s.delayMode) {
					cy = row(panel, context, draw, x, cy, w, "Repeater delay", s.delayTicks + " tick" + (s.delayTicks == 1 ? "" : "s"), () -> {
						s.delayTicks = s.delayTicks % 4 + 1;
						changed();
					});
				}
				cy = routeStatus(panel, context, draw, x, cy, w);
			}
			case WATER -> {
				cy = row(panel, context, draw, x, cy, w, "Floor", pretty(s.waterFloor), () -> pick("Stream floor", "waterFloor"));
				cy = row(panel, context, draw, x, cy, w, "Walls", pretty(s.waterWall), () -> pick("Stream walls", "waterWall"));
				cy = row(panel, context, draw, x, cy, w, "Separator", pretty(s.waterSeparator), () -> pick("Section separator", "waterSeparator"));
				cy = row(panel, context, draw, x, cy, w, "Path", s.pathMode, () -> {
					s.pathMode = "Go Around".equals(s.pathMode) ? "Go Through" : "Go Around";
					changed();
				});
				cy = routeStatus(panel, context, draw, x, cy, w);
			}
			default -> {
			}
		}
		return cy;
	}

	private static int row(RmHud.Panel panel, DrawContext context, List<Runnable> draw, int x, int y, int w, String label, String value, Runnable action) {
		draw.add(() -> panel.settingRow(context, x, y, w, label, value, action));
		return y + 17;
	}

	private static int routeStatus(RmHud.Panel panel, DrawContext context, List<Runnable> draw, int x, int y, int w) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int cy = y + 2;
		String status = Wiring.progressText() != null ? Wiring.progressText() : Wiring.summary() != null ? Wiring.summary()
				: RmState.wireStart == null ? "Left click where the signal / items start" : "Right click the target";
		List<String> lines = VeloUi.wrap(status, w);
		for (String line : lines.subList(0, Math.min(4, lines.size()))) {
			int ly = cy;
			draw.add(() -> context.drawTextWithShadow(textRenderer, line, x, ly, RmHud.MUTED));
			cy += 10;
		}
		if (!Wiring.preview().isEmpty()) {
			int by = cy + 2;
			draw.add(() -> {
				int bx = x;
				bx += panel.textButton(context, bx, by, 0, "Build", true, Wiring::build) + 4;
				panel.textButton(context, bx, by, 0, "Clear", false, Wiring::reset);
			});
			cy += 20;
		}
		return cy;
	}

	private static void changed() {
		RmSettings.save();
		if (RmState.wireStart != null && RmState.wireEnd != null) {
			Wiring.plan(RmState.tool == RmState.Tool.WATER);
		}
	}

	static String pretty(String id) {
		if (id == null || id.equals("auto")) {
			return "Auto";
		}
		return id.replace("minecraft:", "").replace('_', ' ');
	}

	private static void pick(String title, String field) {
		MinecraftClient.getInstance().setScreen(new BlockPickerScreen(title, true, id -> {
			RmSettings.Data s = RmSettings.get();
			switch (field) {
				case "wireBlock" -> s.wireBlock = id;
				case "waterFloor" -> s.waterFloor = id;
				case "waterWall" -> s.waterWall = id;
				case "waterSeparator" -> s.waterSeparator = id;
				default -> {
				}
			}
			changed();
		}));
	}

	/** "X +3  Y -1" for the Stack tool's per-axis counts. */
	private static String stackAxes() {
		StringBuilder out = new StringBuilder();
		String[] names = {"X", "Y", "Z"};
		for (int a = 0; a < 3; a++) {
			if (RmState.stack[a] != 0) {
				out.append(out.isEmpty() ? "" : "  ").append(names[a]).append(RmState.stack[a] > 0 ? " +" : " ").append(RmState.stack[a]);
			}
		}
		return out.toString();
	}
}
