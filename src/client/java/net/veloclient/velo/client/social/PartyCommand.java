package net.veloclient.velo.client.social;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.veloclient.velo.client.network.SocialClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Makes {@code /party} a real (client) command while you're in a Velo party, so chat no longer paints it red
 * as an unknown command, and Tab completes party members' names. It still behaves like before ({@link
 * PartyChat}): the server's own party subcommands ({@code invite}, {@code leave}...) are deliberately not
 * accepted here, so they fall through to the server; and with no Velo party the command isn't there at all.
 * Party chat goes through the Velo server, so it works on any server (or none).
 */
public final class PartyCommand {

	private PartyCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCmd.literal(PartyChat.commandName())
						.requires(source -> SocialClient.party() != null)
						.executes(context -> {
							PartyChat.runLocal("");
							return 1;
						})
						.then(ClientCmd.argument("message", new MessageArgument())
								.executes(context -> {
									PartyChat.runLocal(context.getArgument("message", String.class));
									return 1;
								}))));
	}

	/** The rest of the line: a message (or "follow"); refuses the server's own party subcommands so those still reach the server. */
	private static final class MessageArgument implements ArgumentType<String> {

		@Override
		public String parse(StringReader reader) throws CommandSyntaxException {
			String text = reader.getRemaining();
			reader.setCursor(reader.getTotalLength());
			String first = text.strip().split("\\s+", 2)[0];
			if (PartyChat.isServerSubcommand(first)) {
				// "Unknown command" is what the command system lets through to the server untouched.
				throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(reader);
			}
			return text;
		}

		@Override
		public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
			String typed = builder.getRemaining();
			int space = typed.lastIndexOf(' ');
			String token = typed.substring(space + 1);
			boolean mention = token.startsWith("@");
			String prefix = (mention ? token.substring(1) : token).toLowerCase(Locale.ROOT);
			SuggestionsBuilder word = builder.createOffset(builder.getStart() + space + 1);
			List<String> options = new ArrayList<>();
			if (space < 0 && SocialClient.isPartyLeader()) {
				options.add("follow");
			}
			var party = SocialClient.party();
			if (party != null && party.has("members")) {
				for (var member : party.getAsJsonArray("members")) {
					var o = member.getAsJsonObject();
					if (o.has("name") && !o.get("name").isJsonNull()) {
						options.add(mention ? "@" + o.get("name").getAsString() : o.get("name").getAsString());
					}
				}
			}
			for (String option : options) {
				String plain = (mention ? option.substring(1) : option).toLowerCase(Locale.ROOT);
				if (plain.startsWith(prefix)) {
					word.suggest(option);
				}
			}
			return word.buildFuture();
		}
	}
}
