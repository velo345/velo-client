package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

/** The overlay's drop-down menus. */
final class RmMenus {

	private static final RmHud.MenuItem SEPARATOR = new RmHud.MenuItem("", null, null, null);

	private RmMenus() {
	}

	static List<RmHud.MenuItem> file() {
		List<RmHud.MenuItem> items = new ArrayList<>();
		items.add(RmHud.MenuItem.of("Load schematic...", () -> MinecraftClient.getInstance().setScreen(new SchematicPickScreen(null))));
		items.add(new RmHud.MenuItem("Save selection as schematic...", null, () -> {
			if (RmState.hasSelection()) {
				MinecraftClient.getInstance().setScreen(new SchematicSaveScreen());
			} else {
				RmState.error("Select an area first");
			}
		}, null));
		items.add(RmHud.MenuItem.of("Open schematics folder", () -> {
			try {
				java.nio.file.Files.createDirectories(SchematicIO.veloFolder());
			} catch (java.io.IOException ignored) {
				// Opening reports it.
			}
			net.veloclient.velo.client.gui.FileManagerOpener.open(SchematicIO.veloFolder().toFile(), RmState::info);
		}));
		items.add(SEPARATOR);
		items.add(RmHud.MenuItem.of("Community schematics...", () -> MinecraftClient.getInstance()
				.setScreen(new net.veloclient.velo.client.gui.CommunitySchematicsScreen(null))));
		items.add(RmHud.MenuItem.of("Share a schematic...", () -> MinecraftClient.getInstance()
				.setScreen(new net.veloclient.velo.client.gui.ShareSchematicScreen(null))));
		items.add(SEPARATOR);
		items.add(RmHud.MenuItem.of("Remove all schematic previews", Placements::clear));
		return items;
	}

	static List<RmHud.MenuItem> edit() {
		List<RmHud.MenuItem> items = new ArrayList<>();
		items.add(new RmHud.MenuItem("Undo", "Ctrl+Z", RmOps::undo, null));
		items.add(new RmHud.MenuItem("Redo", "Ctrl+Y", RmOps::redo, null));
		items.add(SEPARATOR);
		items.add(new RmHud.MenuItem("Copy", "Ctrl+C", () -> RmOps.copy(false), null));
		items.add(new RmHud.MenuItem("Cut", "Ctrl+X", () -> RmOps.copy(true), null));
		items.add(new RmHud.MenuItem("Paste", "Ctrl+V", RmInput::startPaste, null));
		items.add(new RmHud.MenuItem("Erase selection", "Delete", RmOps::erase, null));
		items.add(RmHud.MenuItem.of("Replace in selection...", () -> {
			if (RmState.hasSelection()) {
				MinecraftClient.getInstance().setScreen(new ReplaceScreen());
			} else {
				RmState.error("Select an area first");
			}
		}));
		items.add(new RmHud.MenuItem("Clear selection", "Backspace", RmState::clearSelection, null));
		items.add(SEPARATOR);
		items.add(RmHud.MenuItem.of("Stop running paste", RmJobs::cancelCurrent));
		return items;
	}

	static List<RmHud.MenuItem> view() {
		RmSettings.Data s = RmSettings.get();
		List<RmHud.MenuItem> items = new ArrayList<>();
		items.add(new RmHud.MenuItem("Signal strength numbers", null, () -> {
			s.signalNumbers = !s.signalNumbers;
			RmSettings.save();
		}, () -> s.signalNumbers));
		items.add(new RmHud.MenuItem("Clock speeds", null, () -> {
			s.clockLabels = !s.clockLabels;
			RmSettings.save();
		}, () -> s.clockLabels));
		items.add(new RmHud.MenuItem("Quasi-connectivity / BUD marks", null, () -> {
			s.qcMarkers = !s.qcMarkers;
			RmSettings.save();
		}, () -> s.qcMarkers));
		items.add(new RmHud.MenuItem("Item rate meters", null, () -> {
			s.rateMeters = !s.rateMeters;
			RmSettings.save();
		}, () -> s.rateMeters));
		items.add(new RmHud.MenuItem("Dust range warnings", null, () -> {
			s.dustWarnings = "Off".equals(s.dustWarnings) ? "Always" : "Off";
			RmSettings.save();
		}, () -> !"Off".equals(s.dustWarnings)));
		items.add(SEPARATOR);
		items.add(RmHud.MenuItem.of("Add rate meter where I look", RmAnalysis::addMeterAtLook));
		items.add(RmHud.MenuItem.of("Remove rate meters", RmAnalysis::clearMeters));
		items.add(new RmHud.MenuItem("Hide overlay", "F9", RedstoneMode::toggleOverlay, null));
		return items;
	}

	private static final int[] SPEEDS = {1000, 5000, 20_000, 100_000, 0};

	static List<RmHud.MenuItem> settings() {
		RmSettings.Data s = RmSettings.get();
		List<RmHud.MenuItem> items = new ArrayList<>();
		items.add(new RmHud.MenuItem("Paste speed", s.pasteSpeed <= 0 ? "Instant" : s.pasteSpeed + " blocks/tick", () -> {
			int i = 0;
			while (i < SPEEDS.length && SPEEDS[i] != s.pasteSpeed) {
				i++;
			}
			s.pasteSpeed = SPEEDS[(i + 1) % SPEEDS.length];
			RmSettings.save();
		}, null));
		items.add(new RmHud.MenuItem("Keep redstone running when pasting", null, () -> {
			s.keepRunning = !s.keepRunning;
			RmSettings.save();
		}, () -> s.keepRunning));
		items.add(new RmHud.MenuItem("Recordings kept", String.valueOf(s.keepRecordings), () -> {
			s.keepRecordings = s.keepRecordings >= 20 ? 1 : s.keepRecordings + (s.keepRecordings < 5 ? 1 : 5);
			RmSettings.save();
		}, null));
		items.add(new RmHud.MenuItem("Longest recording", s.timelineFrames / 20 + " s", () -> {
			s.timelineFrames = s.timelineFrames >= 6000 ? 200 : s.timelineFrames * 2;
			RmSettings.save();
		}, null));
		items.add(SEPARATOR);
		items.add(RmHud.MenuItem.of("Edit overlay layout...", () -> MinecraftClient.getInstance().setScreen(
				new net.veloclient.velo.client.gui.HudEditScreen(null))));
		items.add(RmHud.MenuItem.of("Hopper Speed module...", () -> openModule("hopper-speed")));
		items.add(RmHud.MenuItem.of("Fast Grow module...", () -> openModule("fast-grow")));
		return items;
	}

	private static void openModule(String id) {
		net.veloclient.velo.module.ModuleRegistry.get(id).ifPresent(module -> MinecraftClient.getInstance().setScreen(
				new net.veloclient.velo.client.gui.window.ModuleConfigScreen(module, () -> MinecraftClient.getInstance().setScreen(null))));
	}

	static List<RmHud.MenuItem> recordings() {
		List<RmHud.MenuItem> items = new ArrayList<>();
		List<Timeline.Recording> list = Timeline.recordings();
		if (list.isEmpty()) {
			items.add(RmHud.MenuItem.of("No recordings yet - select an area and press K", () -> { }));
			return items;
		}
		for (int i = list.size() - 1; i >= 0; i--) {
			Timeline.Recording r = list.get(i);
			items.add(new RmHud.MenuItem("Replay #" + r.number() + "  (" + r.frames() + " ticks)", r.size(), () -> Timeline.openReplay(r), null));
		}
		items.add(SEPARATOR);
		items.add(RmHud.MenuItem.of("Delete all recordings", Timeline::discardAll));
		return items;
	}
}
