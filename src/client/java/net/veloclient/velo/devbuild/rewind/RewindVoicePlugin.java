package net.veloclient.velo.devbuild.rewind;

import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * Simple Voice Chat calls this (the "voicechat" entrypoint) when it's installed: the voices it plays and what your
 * microphone sends go to {@link RewindAudio} for Velo Rewind recordings. Nothing is changed - the audio is only copied.
 */
public final class RewindVoicePlugin implements VoicechatPlugin {

	@Override
	public String getPluginId() {
		return "velo_rewind";
	}

	@Override
	public void registerEvents(EventRegistration events) {
		RewindAudio.voicechat = true;
		events.registerEvent(ClientReceiveSoundEvent.EntitySound.class, e -> RewindAudio.voice(e.getEntityId(), null, e.getRawAudio()));
		events.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, e -> RewindAudio.voice(null,
				new double[] {e.getPosition().getX(), e.getPosition().getY(), e.getPosition().getZ()}, e.getRawAudio()));
		events.registerEvent(ClientReceiveSoundEvent.StaticSound.class, e -> RewindAudio.voice(null, null, e.getRawAudio()));
		events.registerEvent(ClientSoundEvent.class, e -> RewindAudio.ownVoice(e.getRawAudio()));
	}
}
