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

	@Override
	public void setEnabled(boolean enabled) {
		if (this.enabled == enabled) {
			return;
		}
		this.enabled = enabled;
		if (enabled) {
			onEnable();
		} else {
			onDisable();
		}
	}
}
