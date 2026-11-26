package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import net.veloclient.velo.client.namestyle.NameStyle;
import net.veloclient.launcher.data.NameStyleSetting;
import net.veloclient.launcher.data.OgBadgeSetting;
import net.veloclient.launcher.data.PlusBadgeSetting;

/**
 * The name tag over Home's player (2026-11-25 glass launcher): your real badge (OG, Velo+ or Velo - the one others see),
 * then your name in your Velo+ Name Style, animated like in game; plain white for everyone else.
 */
public final class NameTag {

	private NameTag() {
	}

	/** Which badge others see on you: "og", "plus" or "velo". */
	public static String badge(boolean plus) {
		if (OgBadgeSetting.eligible() && OgBadgeSetting.use()) {
			return "og";
		}
		return plus && PlusBadgeSetting.show() ? "plus" : "velo";
	}

	/** The badge picture, {@code height} px tall (width follows its shape: Velo 1:1, Velo+ 1.5:1, OG 2:1). */
	public static ImageView badgeImage(String badge, double height) {
		double ratio = switch (badge) {
			case "og" -> 2;
			case "plus" -> 1.5;
			default -> 1;
		};
		ImageView view = new ImageView(UiImages.resource("/net/veloclient/launcher/images/badges/" + badge + ".png",
				Math.round(height * ratio * 2), Math.round(height * 2)));
		view.setFitHeight(height);
		view.setFitWidth(height * ratio);
		view.setSmooth(true);
		return view;
	}

	public static Node create(String name, boolean plus) {
		return create(name, badge(plus), plus && NameStyleSetting.enabled());
	}

	/** Someone else's tag (the party on Home): the plain Velo badge - never your own OG / Velo+ badge or Name Style. */
	public static Node other(String name) {
		return create(name, "velo", false);
	}

	private static Node create(String name, String badgeId, boolean styled) {
		ImageView badge = badgeImage(badgeId, 18);
		Node text;
		if (styled) {
			NameStyle style = NameStyleSetting.style();
			javafx.scene.text.Font measure = javafx.scene.text.Font.font(NamePreview.family(style.font()), javafx.scene.text.FontWeight.BOLD, 19);
			double width = NamePreview.width((style.iconText().isEmpty() ? "" : style.iconText() + " ") + name, measure) + 14;
			text = new NamePreview(name, () -> style, 19, Math.min(260, width));
		} else {
			Label plain = new Label(name);
			plain.getStyleClass().add("name-tag-plain");
			text = plain;
		}
		HBox tag = new HBox(7, badge, text);
		tag.setAlignment(Pos.CENTER);
		tag.setPadding(new Insets(4, 12, 4, 8));
		tag.getStyleClass().add("name-tag");
		tag.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
		tag.setMouseTransparent(true);
		return tag;
	}
}
