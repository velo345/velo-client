package net.veloclient.launcher.sandbox;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

/** One of the Home arcade's games (the world, Snake, Blockfall), driven and drawn by {@link Arcade}. */
interface MiniGame {

	String id();

	String music();

	void update(double dt);

	void render(GraphicsContext g, double w, double h, double t);

	void key(KeyCode code, boolean down);

	default void mouse(MouseEvent event) {
	}

	default void scroll(ScrollEvent event) {
	}

	/** True when nothing is happening and the Home GUI may come back. */
	boolean restful();

	/** The GUI came back: settle into a calm pose (the player turns to face you). */
	default void rest() {
	}

	default void save(com.google.gson.JsonObject out) {
	}

	default void load(com.google.gson.JsonObject in) {
	}

	/** True while the game must keep running behind menus (party co-op). */
	default boolean keepsRunning() {
		return false;
	}

	default void dispose() {
	}
}
