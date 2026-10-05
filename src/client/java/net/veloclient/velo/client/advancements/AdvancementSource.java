package net.veloclient.velo.client.advancements;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds the Velo advancements screen: listens to the client's advancement data the same way
 * vanilla's screen does (only one listener at a time - attach while the screen is open, detach on
 * close) and turns it into plain {@link Node}s and {@link Progress} the screen can draw. Also tells
 * the server which tab is being looked at, like vanilla.
 */
public final class AdvancementSource {

	public enum Frame { TASK, GOAL, CHALLENGE }

	public record Node(String id, String parentId, String rootId, String title, String description, ItemStack icon, Frame frame,
			float x, float y) {
	}

	public record Progress(boolean done, float percent, String fraction, List<String> obtained, List<String> remaining) {
	}

	public final Map<String, Node> nodes = new LinkedHashMap<>();
	public final Map<String, Progress> progress = new LinkedHashMap<>();
	public final List<String> roots = new ArrayList<>();
	private final Map<String, Object> handles = new LinkedHashMap<>();
	private Runnable onChange = () -> { };

	public void onChange(Runnable onChange) {
		this.onChange = onChange;
	}

	private void changed() {
		onChange.run();
	}

	/** "3/5" style fraction only when an advancement has several criteria (like vanilla's bar). */
	private static Progress progressOf(boolean done, float percent, List<String> obtained, List<String> remaining) {
		int total = obtained.size() + remaining.size();
		return new Progress(done, percent, total > 1 ? obtained.size() + "/" + total : "", obtained, remaining);
	}

	private static List<String> list(Iterable<String> criteria) {
		List<String> out = new ArrayList<>();
		criteria.forEach(out::add);
		return out;
	}

	//? if <26.1 {
	private final net.minecraft.client.network.ClientAdvancementManager.Listener listener = new net.minecraft.client.network.ClientAdvancementManager.Listener() {
		@Override
		public void onRootAdded(net.minecraft.advancement.PlacedAdvancement root) {
			add(root);
		}

		@Override
		public void onRootRemoved(net.minecraft.advancement.PlacedAdvancement root) {
			remove(root);
		}

		@Override
		public void onDependentAdded(net.minecraft.advancement.PlacedAdvancement dependent) {
			add(dependent);
		}

		@Override
		public void onDependentRemoved(net.minecraft.advancement.PlacedAdvancement dependent) {
			remove(dependent);
		}

		@Override
		public void onClear() {
			nodes.clear();
			progress.clear();
			roots.clear();
			handles.clear();
			changed();
		}

		@Override
		public void setProgress(net.minecraft.advancement.PlacedAdvancement advancement, net.minecraft.advancement.AdvancementProgress p) {
			progress.put(advancement.getAdvancementEntry().id().toString(), progressOf(p.isDone(), p.getProgressBarPercentage(),
					list(p.getObtainedCriteria()), list(p.getUnobtainedCriteria())));
			changed();
		}

		@Override
		public void selectTab(net.minecraft.advancement.AdvancementEntry advancement) {
		}
	};

	private void add(net.minecraft.advancement.PlacedAdvancement placed) {
		var display = placed.getAdvancement().display();
		if (display.isEmpty()) {
			return;
		}
		var d = display.get();
		String id = placed.getAdvancementEntry().id().toString();
		String parent = placed.getParent() == null ? null : placed.getParent().getAdvancementEntry().id().toString();
		String root = placed.getRoot().getAdvancementEntry().id().toString();
		Frame frame = switch (d.getFrame()) {
			case GOAL -> Frame.GOAL;
			case CHALLENGE -> Frame.CHALLENGE;
			default -> Frame.TASK;
		};
		nodes.put(id, new Node(id, parent, root, d.getTitle().getString(), d.getDescription().getString(), d.getIcon(), frame, d.getX(), d.getY()));
		handles.put(id, placed.getAdvancementEntry());
		if (parent == null && !roots.contains(id)) {
			roots.add(id);
		}
		changed();
	}

	private void remove(net.minecraft.advancement.PlacedAdvancement placed) {
		String id = placed.getAdvancementEntry().id().toString();
		nodes.remove(id);
		progress.remove(id);
		roots.remove(id);
		handles.remove(id);
		changed();
	}

	public void attach() {
		var handler = MinecraftClient.getInstance().getNetworkHandler();
		if (handler != null) {
			handler.getAdvancementHandler().setListener(listener);
		}
	}

	public void detach() {
		var handler = MinecraftClient.getInstance().getNetworkHandler();
		if (handler != null) {
			handler.getAdvancementHandler().setListener(null);
			handler.sendPacket(net.minecraft.network.packet.c2s.play.AdvancementTabC2SPacket.close());
		}
	}

	/** Tells the server this tab is open (like vanilla, so "seen" state stays right). */
	public void selectTab(String rootId) {
		var handler = MinecraftClient.getInstance().getNetworkHandler();
		Object entry = handles.get(rootId);
		if (handler != null && entry instanceof net.minecraft.advancement.AdvancementEntry advancement) {
			handler.getAdvancementHandler().selectTab(advancement, true);
		}
	}
	//?} else {
	/*private final net.minecraft.client.multiplayer.ClientAdvancements.Listener listener = new net.minecraft.client.multiplayer.ClientAdvancements.Listener() {
		@Override
		public void onAddAdvancementRoot(net.minecraft.advancements.AdvancementNode root) {
			add(root);
		}

		@Override
		public void onRemoveAdvancementRoot(net.minecraft.advancements.AdvancementNode root) {
			remove(root);
		}

		@Override
		public void onAddAdvancementTask(net.minecraft.advancements.AdvancementNode task) {
			add(task);
		}

		@Override
		public void onRemoveAdvancementTask(net.minecraft.advancements.AdvancementNode task) {
			remove(task);
		}

		@Override
		public void onAdvancementsCleared() {
			nodes.clear();
			progress.clear();
			roots.clear();
			handles.clear();
			changed();
		}

		@Override
		public void onUpdateAdvancementProgress(net.minecraft.advancements.AdvancementNode node, net.minecraft.advancements.AdvancementProgress p) {
			progress.put(node.holder().id().toString(), progressOf(p.isDone(), p.getPercent(),
					list(p.getCompletedCriteria()), list(p.getRemainingCriteria())));
			changed();
		}

		@Override
		public void onSelectedTabChanged(net.minecraft.advancements.AdvancementHolder holder) {
		}
	};

	private void add(net.minecraft.advancements.AdvancementNode node) {
		var display = node.advancement().display();
		if (display.isEmpty()) {
			return;
		}
		var d = display.get();
		String id = node.holder().id().toString();
		String parent = node.parent() == null ? null : node.parent().holder().id().toString();
		String root = node.root().holder().id().toString();
		Frame frame = switch (d.getType()) {
			case GOAL -> Frame.GOAL;
			case CHALLENGE -> Frame.CHALLENGE;
			default -> Frame.TASK;
		};
		nodes.put(id, new Node(id, parent, root, d.getTitle().getString(), d.getDescription().getString(), d.getIcon().create(), frame,
				d.getX(), d.getY()));
		handles.put(id, node.holder());
		if (parent == null && !roots.contains(id)) {
			roots.add(id);
		}
		changed();
	}

	private void remove(net.minecraft.advancements.AdvancementNode node) {
		String id = node.holder().id().toString();
		nodes.remove(id);
		progress.remove(id);
		roots.remove(id);
		handles.remove(id);
		changed();
	}

	public void attach() {
		var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.getAdvancements().setListener(listener);
		}
	}

	public void detach() {
		var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.getAdvancements().setListener(null);
			connection.send(net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket.closedScreen());
		}
	}

	public void selectTab(String rootId) {
		var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
		Object holder = handles.get(rootId);
		if (connection != null && holder instanceof net.minecraft.advancements.AdvancementHolder advancement) {
			connection.getAdvancements().setSelectedTab(advancement, true);
		}
	}
	*///?}
}
