package net.veloclient.velo.client.modules.queue;

//? if <26.1 {
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.common.CookieRequestS2CPacket;
import net.minecraft.network.packet.s2c.common.CustomReportDetailsS2CPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;
import net.minecraft.network.packet.s2c.common.ServerLinksS2CPacket;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import net.minecraft.network.packet.s2c.common.StoreCookieS2CPacket;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.EnterReconfigurationS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListHeaderS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TeamS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.common.ClientboundCustomReportDetailsPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.chat.Component;
*///?}

/**
 * Decides what happens to each packet that arrives on a backgrounded ("ghost") connection - see
 * {@link BackgroundQueueManager}. The ghost has no world attached, so most packets can't be applied
 * right away without either crashing (a null level) or leaking into whatever you're playing now
 * (chat, titles, the tab list and boss bars all write straight into the shared HUD):
 * <ul>
 * <li>{@link Kind#PASS} - safe without a world and only touches the ghost's own state: keep-alives
 * and pings (so the server keeps you connected), cookies, and the scoreboard (kept per connection,
 * so the session menu can show it live).</li>
 * <li>Chat / action bar / titles / tab header - captured as text for the session menu and the chat
 * mirror, never shown by vanilla.</li>
 * <li>{@link Kind#BUFFER} - everything that changes the world (chunks, entities, your position...)
 * plus signed player chat (its sequence numbers must stay in order) - queued and replayed through
 * the normal handlers, in order, the moment you switch back.</li>
 * <li>A fresh login (a proxy moving you to another backend - typically the queue popping) resets
 * the buffer, because everything before it is obsolete.</li>
 * </ul>
 */
final class GhostPackets {

	enum Kind { PASS, BUFFER, LOGIN, CONFIG_START, CHAT, OVERLAY, TITLE, SUBTITLE, CLEAR_TITLE, TAB_LIST, DISCONNECT, TRANSFER, DROP }

	/** {@code text}/{@code extra} carry whatever readable text the packet had (chat line, title, tab header+footer...). */
	record Result(Kind kind, String text, String extra) {
		static Result of(Kind kind) {
			return new Result(kind, null, null);
		}
	}

	private GhostPackets() {
	}

	//? if <26.1 {
	/** The connection behind a client-side listener, or null if it isn't one of ours. */
	static ClientConnection connectionOf(PacketListener listener) {
		return listener instanceof ClientCommonNetworkHandler
				? ((net.veloclient.velo.client.mixin.CommonListenerConnectionAccessor) listener).velo$connection() : null;
	}

	static boolean isPlayListener(PacketListener listener) {
		return listener instanceof ClientPlayNetworkHandler;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static void apply(Packet<?> packet, PacketListener listener) {
		((Packet) packet).apply(listener);
	}

	static Result classify(Packet<?> packet, boolean play) {
		if (packet instanceof KeepAliveS2CPacket || packet instanceof CommonPingS2CPacket || packet instanceof CookieRequestS2CPacket
				|| packet instanceof StoreCookieS2CPacket || packet instanceof ServerLinksS2CPacket || packet instanceof CustomReportDetailsS2CPacket) {
			return Result.of(Kind.PASS);
		}
		if (packet instanceof DisconnectS2CPacket disconnect) {
			return new Result(Kind.DISCONNECT, disconnect.reason().getString(), null);
		}
		if (packet instanceof ServerTransferS2CPacket) {
			return Result.of(Kind.TRANSFER);
		}
		if (!play) {
			// Configuration phase (a proxy switching backends): let it run - nothing there touches the foreground.
			return Result.of(Kind.PASS);
		}
		if (packet instanceof GameJoinS2CPacket) {
			return Result.of(Kind.LOGIN);
		}
		if (packet instanceof EnterReconfigurationS2CPacket) {
			return Result.of(Kind.CONFIG_START);
		}
		if (packet instanceof GameMessageS2CPacket message) {
			return new Result(message.overlay() ? Kind.OVERLAY : Kind.CHAT, message.content().getString(), null);
		}
		if (packet instanceof ProfilelessChatMessageS2CPacket message) {
			return new Result(Kind.CHAT, decorate(() -> message.chatType().applyChatDecoration(message.message()), message.message()), null);
		}
		if (packet instanceof ChatMessageS2CPacket chat) {
			// Buffered (the handler's chat index must see every one in order), but previewed now.
			Text content = chat.unsignedContent() != null ? chat.unsignedContent() : Text.literal(chat.body().content());
			return new Result(Kind.BUFFER, decorate(() -> chat.serializedParameters().applyChatDecoration(content), content), "chat");
		}
		if (packet instanceof OverlayMessageS2CPacket overlay) {
			return new Result(Kind.OVERLAY, overlay.text().getString(), null);
		}
		if (packet instanceof TitleS2CPacket title) {
			return new Result(Kind.TITLE, title.text().getString(), null);
		}
		if (packet instanceof SubtitleS2CPacket subtitle) {
			return new Result(Kind.SUBTITLE, subtitle.text().getString(), null);
		}
		if (packet instanceof ClearTitleS2CPacket) {
			return Result.of(Kind.CLEAR_TITLE);
		}
		if (packet instanceof TitleFadeS2CPacket) {
			return Result.of(Kind.DROP);
		}
		if (packet instanceof PlayerListHeaderS2CPacket tab) {
			return new Result(Kind.TAB_LIST, tab.header().getString(), tab.footer().getString());
		}
		if (packet instanceof ScoreboardObjectiveUpdateS2CPacket || packet instanceof ScoreboardScoreUpdateS2CPacket
				|| packet instanceof ScoreboardScoreResetS2CPacket || packet instanceof ScoreboardDisplayS2CPacket
				|| packet instanceof TeamS2CPacket) {
			return Result.of(Kind.PASS);
		}
		return Result.of(Kind.BUFFER);
	}

	private static String decorate(java.util.function.Supplier<Text> decorated, Text fallback) {
		try {
			return decorated.get().getString();
		} catch (RuntimeException e) {
			return fallback.getString();
		}
	}
	//?} else {
	/*static Connection connectionOf(PacketListener listener) {
		return listener instanceof ClientCommonPacketListenerImpl
				? ((net.veloclient.velo.client.mixin.CommonListenerConnectionAccessor) listener).velo$connection() : null;
	}

	static boolean isPlayListener(PacketListener listener) {
		return listener instanceof ClientPacketListener;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	static void apply(Packet<?> packet, PacketListener listener) {
		((Packet) packet).handle(listener);
	}

	static Result classify(Packet<?> packet, boolean play) {
		if (packet instanceof ClientboundKeepAlivePacket || packet instanceof ClientboundPingPacket || packet instanceof ClientboundCookieRequestPacket
				|| packet instanceof ClientboundStoreCookiePacket || packet instanceof ClientboundServerLinksPacket
				|| packet instanceof ClientboundCustomReportDetailsPacket) {
			return Result.of(Kind.PASS);
		}
		if (packet instanceof ClientboundDisconnectPacket disconnect) {
			return new Result(Kind.DISCONNECT, disconnect.reason().getString(), null);
		}
		if (packet instanceof ClientboundTransferPacket) {
			return Result.of(Kind.TRANSFER);
		}
		if (!play) {
			return Result.of(Kind.PASS);
		}
		if (packet instanceof ClientboundLoginPacket) {
			return Result.of(Kind.LOGIN);
		}
		if (packet instanceof ClientboundStartConfigurationPacket) {
			return Result.of(Kind.CONFIG_START);
		}
		if (packet instanceof ClientboundSystemChatPacket message) {
			return new Result(message.overlay() ? Kind.OVERLAY : Kind.CHAT, message.content().getString(), null);
		}
		if (packet instanceof ClientboundDisguisedChatPacket message) {
			return new Result(Kind.CHAT, decorate(() -> message.chatType().decorate(message.message()), message.message()), null);
		}
		if (packet instanceof ClientboundPlayerChatPacket chat) {
			Component content = chat.unsignedContent() != null ? chat.unsignedContent() : Component.literal(chat.body().content());
			return new Result(Kind.BUFFER, decorate(() -> chat.chatType().decorate(content), content), "chat");
		}
		if (packet instanceof ClientboundSetActionBarTextPacket overlay) {
			return new Result(Kind.OVERLAY, overlay.text().getString(), null);
		}
		if (packet instanceof ClientboundSetTitleTextPacket title) {
			return new Result(Kind.TITLE, title.text().getString(), null);
		}
		if (packet instanceof ClientboundSetSubtitleTextPacket subtitle) {
			return new Result(Kind.SUBTITLE, subtitle.text().getString(), null);
		}
		if (packet instanceof ClientboundClearTitlesPacket) {
			return Result.of(Kind.CLEAR_TITLE);
		}
		if (packet instanceof ClientboundSetTitlesAnimationPacket) {
			return Result.of(Kind.DROP);
		}
		if (packet instanceof ClientboundTabListPacket tab) {
			return new Result(Kind.TAB_LIST, tab.header().getString(), tab.footer().getString());
		}
		if (packet instanceof ClientboundSetObjectivePacket || packet instanceof ClientboundSetScorePacket
				|| packet instanceof ClientboundResetScorePacket || packet instanceof ClientboundSetDisplayObjectivePacket
				|| packet instanceof ClientboundSetPlayerTeamPacket) {
			return Result.of(Kind.PASS);
		}
		return Result.of(Kind.BUFFER);
	}

	private static String decorate(java.util.function.Supplier<Component> decorated, Component fallback) {
		try {
			return decorated.get().getString();
		} catch (RuntimeException e) {
			return fallback.getString();
		}
	}
	*///?}
}
