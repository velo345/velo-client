package net.veloclient.velo.devbuild;

/**
 * Only packed into dev builds (./build-dev.sh): its presence switches on the unreleased features that live in production
 * code behind {@link net.veloclient.velo.client.Preview#ON}.
 */
public final class DevBuild {

	private DevBuild() {
	}
}
