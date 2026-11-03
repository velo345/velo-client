package net.veloclient.velo.client.emotes;

/**
 * Plays the emotes' own sounds and music (assets/velo-client/sounds/emote, made by tools/cosmetics/emotes) - in 3D at
 * the emoting player, who they follow, or flat for previews. Music loops by itself (its file is a whole number of the
 * dance's loops long, so it stays on the beat), sits in the Jukebox/Note Blocks volume and fades out when the emote
 * ends; effect sounds are Players sounds.
 */
public final class EmoteSounds {

	/** A playing sound: fades out on {@link #fadeOut()}, volume changeable while it plays. */
	public interface Handle {
		void fadeOut();

		void volume(float volume);

		boolean done();
	}

	private EmoteSounds() {
	}

	/** A one-shot effect sound at the player ({@code player} null = a flat UI sound, for previews). */
	public static void play(Object player, String id, float volume, float pitch) {
		start(player, id, volume, pitch, false, false);
	}

	/** A one-shot flat UI sound that can be stopped ({@link Handle#fadeOut()}) - previews that end when you leave them. */
	public static Handle ui(String id, float volume) {
		return start(null, id, volume, 1f, false, false);
	}

	/** A one-shot effect sound at a fixed spot in the world (kill effects: the victim is gone a moment later). */
	public static void playAt(double x, double y, double z, String id, float volume, float pitch) {
		if (id == null || volume <= 0.001f) {
			return;
		}
		try {
			Sound sound = new Sound(id, null, volume, pitch, false, false, false);
			sound.fixAt(x, y, z);
			play(sound);
		} catch (RuntimeException e) {
			// unknown id / no sound engine
		}
	}

	/** Music for an emote, looping until stopped ({@code loop}) or once; null when it can't play. */
	public static Handle music(Object player, String id, float volume, boolean loop) {
		return start(player, id, volume, 1f, true, loop);
	}

	/**
	 * Background music for menus (the title track): Music volume slider, loops, fades in over ~2 s and out on
	 * {@link Handle#fadeOut()}.
	 */
	public static Handle menuMusic(String id, float volume) {
		if (id == null || volume <= 0.001f) {
			return null;
		}
		try {
			Sound sound = new Sound(id, null, volume, 1f, true, true, true);
			play(sound);
			return sound;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Handle start(Object player, String id, float volume, float pitch, boolean music, boolean loop) {
		if (id == null || volume <= 0.001f) {
			return null;
		}
		try {
			Sound sound = new Sound(id, player, volume, pitch, music, loop, false);
			play(sound);
			return sound;
		} catch (RuntimeException e) {
			return null; // unknown id / no sound engine
		}
	}

	//? if <26.1 {
	private static void play(Sound sound) {
		net.minecraft.client.MinecraftClient.getInstance().getSoundManager().play(sound);
	}

	private static final class Sound extends net.minecraft.client.sound.MovingSoundInstance implements Handle {
		private final net.minecraft.entity.Entity follow;
		private float target;
		private float fade = 1f;
		private boolean fading;
		/** Menu music: plays in the Music category and fades in instead of starting at full volume. */
		private boolean rising;


		Sound(String id, Object player, float volume, float pitch, boolean music, boolean loop, boolean menu) {
			super(net.minecraft.sound.SoundEvent.of(net.minecraft.util.Identifier.of(id)),
					menu ? net.minecraft.sound.SoundCategory.MUSIC : music ? net.minecraft.sound.SoundCategory.RECORDS : net.minecraft.sound.SoundCategory.PLAYERS,
					net.minecraft.util.math.random.Random.create());
			if (menu) {
				this.rising = true;
				this.fade = 0.02f;
			}
			this.follow = player instanceof net.minecraft.entity.Entity e ? e : null;
			this.target = volume;
			this.volume = Math.max(0.0001f, volume * fade);
			this.pitch = pitch;
			this.repeat = loop;
			this.repeatDelay = 0;
			if (follow == null) {
				this.relative = true;
				this.attenuationType = net.minecraft.client.sound.SoundInstance.AttenuationType.NONE;
			} else {
				this.attenuationType = net.minecraft.client.sound.SoundInstance.AttenuationType.LINEAR;
				move();
			}
		}

		private void move() {
			this.x = follow.getX();
			this.y = follow.getY() + 1.0;
			this.z = follow.getZ();
		}

		/** Plays in 3D at (x, y, z) instead of following someone. */
		void fixAt(double x, double y, double z) {
			this.relative = false;
			this.attenuationType = net.minecraft.client.sound.SoundInstance.AttenuationType.LINEAR;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		@Override
		public void tick() {
			if (follow != null) {
				if (follow.isRemoved()) {
					setDone();
					return;
				}
				move();
			}
			if (fading) {
				fade -= rising ? 0.05f : 0.125f; // 1 s for menu music, 0.4 s otherwise
				if (fade <= 0f) {
					setDone();
					return;
				}
			} else if (rising && fade < 1f) {
				fade = Math.min(1f, fade + 0.025f); // 2 s
			}
			this.volume = Math.max(0.0001f, target * fade);
		}

		@Override
		public boolean shouldAlwaysPlay() {
			return true;
		}

		@Override
		public void fadeOut() {
			fading = true;
		}

		@Override
		public void volume(float volume) {
			target = volume;
		}

		@Override
		public boolean done() {
			return isDone();
		}
	}
	//?} else {
	/*private static void play(Sound sound) {
		net.minecraft.client.Minecraft.getInstance().getSoundManager().play(sound);
	}

	private static final class Sound extends net.minecraft.client.resources.sounds.AbstractTickableSoundInstance implements Handle {
		private final net.minecraft.world.entity.Entity follow;
		private float target;
		private float fade = 1f;
		private boolean fading;
		// Menu music: plays in the Music category and fades in instead of starting at full volume.
		private boolean rising;


		Sound(String id, Object player, float volume, float pitch, boolean music, boolean loop, boolean menu) {
			super(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.parse(id)),
					menu ? net.minecraft.sounds.SoundSource.MUSIC : music ? net.minecraft.sounds.SoundSource.RECORDS : net.minecraft.sounds.SoundSource.PLAYERS,
					net.minecraft.util.RandomSource.create());
			if (menu) {
				this.rising = true;
				this.fade = 0.02f;
			}
			this.follow = player instanceof net.minecraft.world.entity.Entity e ? e : null;
			this.target = volume;
			this.volume = Math.max(0.0001f, volume * fade);
			this.pitch = pitch;
			this.looping = loop;
			if (follow == null) {
				this.relative = true;
				this.attenuation = net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE;
			} else {
				this.attenuation = net.minecraft.client.resources.sounds.SoundInstance.Attenuation.LINEAR;
				move();
			}
		}

		private void move() {
			this.x = follow.getX();
			this.y = follow.getY() + 1.0;
			this.z = follow.getZ();
		}

		// Plays in 3D at (x, y, z) instead of following someone.
		void fixAt(double x, double y, double z) {
			this.relative = false;
			this.attenuation = net.minecraft.client.resources.sounds.SoundInstance.Attenuation.LINEAR;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		@Override
		public void tick() {
			if (follow != null) {
				if (follow.isRemoved()) {
					stop0();
					return;
				}
				move();
			}
			if (fading) {
				fade -= rising ? 0.05f : 0.125f; // 1 s for menu music, 0.4 s otherwise
				if (fade <= 0f) {
					stop0();
					return;
				}
			} else if (rising && fade < 1f) {
				fade = Math.min(1f, fade + 0.025f); // 2 s
			}
			this.volume = Math.max(0.0001f, target * fade);
		}

		private void stop0() {
			super.stop();
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void fadeOut() {
			fading = true;
		}

		@Override
		public void volume(float volume) {
			target = volume;
		}

		@Override
		public boolean done() {
			return isStopped();
		}
	}
	*///?}
}
