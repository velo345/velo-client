package net.veloclient.velo.dev.notes;

import net.minecraft.client.MinecraftClient;

/** Ways into the notes screens (key, list, mention toast). */
public final class NotesScreens {

	private NotesScreens() {
	}

	public static void open(Note note) {
		MinecraftClient.getInstance().setScreen(new NoteEditScreen(null, note, false));
	}

	public static void list() {
		MinecraftClient.getInstance().setScreen(new NotesListScreen(null));
	}

	/** From a mention toast: the note if it's on this server, else the Mentions list. */
	static void openMention(NoteStore.Mention mention) {
		Note note = NoteStore.byId(mention.id());
		if (note != null) {
			open(note);
		} else {
			NotesListScreen screen = new NotesListScreen(null);
			screen.showMentions();
			MinecraftClient.getInstance().setScreen(screen);
		}
	}
}
