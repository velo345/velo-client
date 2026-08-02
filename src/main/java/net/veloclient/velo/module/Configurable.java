package net.veloclient.velo.module;

import java.util.List;

/** Implemented by modules that expose adjustable settings beyond on/off - shown behind the gear icon in the panel. */
public interface Configurable {

	List<ConfigField> configFields();
}
