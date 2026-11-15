package net.veloclient.legacy.feature;

/** One setting of a 1.8.9 module: a switch or a slider. */
public abstract class Setting {

	public final String name;

	Setting(String name) {
		this.name = name;
	}

	public static final class Toggle extends Setting {
		public boolean value;

		public Toggle(String name, boolean value) {
			super(name);
			this.value = value;
		}
	}

	public static final class Slider extends Setting {
		public final double min;
		public final double max;
		public final double step;
		public final String unit;
		public double value;

		public Slider(String name, double min, double max, double step, double value, String unit) {
			super(name);
			this.min = min;
			this.max = max;
			this.step = step;
			this.value = value;
			this.unit = unit;
		}

		public void set(double v) {
			double s = Math.round(Math.max(min, Math.min(max, v)) / step) * step;
			value = Math.round(s * 1000) / 1000.0;
		}

		public String shown() {
			return (step >= 1 ? String.valueOf((int) Math.round(value)) : String.format(java.util.Locale.ROOT, "%.1f", value)) + unit;
		}
	}
}
