package net.veloclient.velo.client.store;

/** A tab in the Store's left-hand category list. Only {@link #CAPES} exists today - built to grow. */
public enum StoreCategory {

	CAPES("Capes");

	private final String displayName;

	StoreCategory(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
