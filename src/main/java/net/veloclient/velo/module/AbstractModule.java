package net.veloclient.velo.module;

/** Base implementation handling the enabled/disabled bookkeeping every module needs. */
public abstract class AbstractModule implements Module {

	private final String id;
	private final String displayName;
	private final String description;
	private final ModuleCategory category;
	private final SafetyTag safetyTag;
	private final boolean defaultEnabled;
	private boolean enabled;

	protected AbstractModule(String id, String displayName, String description,
			ModuleCategory category, SafetyTag safetyTag, boolean defaultEnabled) {
		this.id = id;
		this.displayName = displayName;
		this.description = description;
		this.category = category;
		this.safetyTag = safetyTag;
		this.defaultEnabled = defaultEnabled;
		this.enabled = defaultEnabled;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String displayName() {
		return displayName;
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public ModuleCategory category() {
		return category;
	}

	@Override
	public SafetyTag safetyTag() {
		return safetyTag;
	}

	@Override
	public boolean defaultEnabled() {
		return defaultEnabled;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Whether {@link #onEnable()} has actually run. A default-on module starts out {@code enabled}
	 * without it (constructors run during mod init, too early for most modules' startup code) -
	 * previously a saved profile that also said "enabled" made {@link #setEnabled} a no-op, so
	 * such a module (e.g. Velo Network) never started at all until toggled off and on by hand.
	 */
	private boolean started;

	@Override
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		if (enabled && !started) {
			started = true;
			onEnable();
		} else if (!enabled && started) {
			started = false;
			onDisable();
		}
	}

	/** Runs {@link #onEnable()} for a module that's on but was never started - called once after startup settles. */
	public void startIfEnabled() {
		if (enabled && !started) {
			started = true;
			onEnable();
		}
	}
}
