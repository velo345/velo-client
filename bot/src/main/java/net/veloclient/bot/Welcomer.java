package net.veloclient.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.FileUpload;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Welcome messages: when someone joins, the bot posts a card in the welcome channel - the Velo banner art with their avatar,
 * their name and which member they are - plus a short embed pointing them to the rules, the download and account linking.
 * The card's background is made by tools/discord/make_brand.py (resources/welcome/background.png, 1100 x 360, the avatar ring
 * at (180, 180) with radius 112).
 */
final class Welcomer {

	private static final int AVATAR_X = 180;
	private static final int AVATAR_Y = 180;
	private static final int AVATAR_R = 112;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	private final BotConfig config;
	private BufferedImage background;
	private Font title;
	private Font name;
	private Font small;

	Welcomer(BotConfig config) {
		this.config = config;
	}

	void onJoin(Guild guild, Member member) {
		if (config.welcomeChannelId == null || member.getUser().isBot()) {
			return;
		}
		TextChannel channel = guild.getTextChannelById(config.welcomeChannelId);
		if (channel == null) {
			return;
		}
		CompletableFuture.runAsync(() -> post(channel, guild, member.getUser(), guild.getMemberCount()));
	}

	/** {@code /setup welcome-test}: the card for yourself, to see how it looks. */
	void test(TextChannel channel, Guild guild, User user) {
		CompletableFuture.runAsync(() -> post(channel, guild, user, guild.getMemberCount()));
	}

	private void post(TextChannel channel, Guild guild, User user, int memberNumber) {
		byte[] card;
		try {
			card = render(user, memberNumber);
		} catch (Exception e) {
			System.err.println("Couldn't draw a welcome card: " + e.getMessage());
			card = null;
		}
		StringBuilder text = new StringBuilder("Glad you're here, <@").append(user.getId()).append(">! ");
		if (config.welcomeRulesChannelId != null) {
			text.append("Have a quick look at <#").append(config.welcomeRulesChannelId).append(">, then ");
		}
		text.append("grab Velo Client and say hi.");
		if (config.linkChannelId != null) {
			text.append("\nLink your Minecraft account in <#").append(config.linkChannelId).append("> for the free **Velo Neon** cape.");
		}
		var embed = new EmbedBuilder().setColor(0xFF3440).setDescription(text);
		if (card != null) {
			embed.setImage("attachment://welcome.png");
		}
		var action = channel.sendMessage("<@" + user.getId() + ">").setEmbeds(embed.build())
				.setComponents(ActionRow.of(Button.link(InfoPosts.SITE, "Download Velo Client")))
				.setAllowedMentions(List.of(Message.MentionType.USER));
		if (card != null) {
			action = action.addFiles(FileUpload.fromData(card, "welcome.png"));
		}
		action.queue(null, e -> System.err.println("Couldn't post a welcome: " + e.getMessage()));
	}

	/** The welcome card as PNG bytes. */
	synchronized byte[] render(User user, int memberNumber) throws IOException {
		loadAssets();
		BufferedImage card = new BufferedImage(background.getWidth(), background.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = card.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(background, 0, 0, null);

		BufferedImage avatar = avatar(user);
		if (avatar != null) {
			int d = AVATAR_R * 2;
			// Draw into a circle via an alpha mask, so the edge is antialiased (setClip would be jagged).
			BufferedImage round = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
			Graphics2D r = round.createGraphics();
			r.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			r.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			r.fill(new Ellipse2D.Double(0, 0, d, d));
			r.setComposite(AlphaComposite.SrcIn);
			r.drawImage(avatar, 0, 0, d, d, null);
			r.dispose();
			g.drawImage(round, AVATAR_X - AVATAR_R, AVATAR_Y - AVATAR_R, null);
		}

		int x = 360;
		g.setFont(title);
		g.setColor(new Color(255, 92, 102));
		g.drawString(spaced("WELCOME"), x, 128);
		String display = user.getEffectiveName();
		Font nameFont = fit(g, name, display, 1100 - x - 60);
		g.setFont(nameFont);
		FontMetrics fm = g.getFontMetrics();
		g.setColor(new Color(0, 0, 0, 120));
		g.drawString(display, x + 2, 200 + 3);
		g.setColor(Color.WHITE);
		g.drawString(display, x, 200);
		g.setFont(small);
		g.setColor(new Color(255, 255, 255, 190));
		g.drawString("Member #" + String.format(java.util.Locale.ROOT, "%,d", memberNumber) + "  ·  Velo Client community", x, 268);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(card, "png", out);
		return out.toByteArray();
	}

	private static String spaced(String s) {
		return String.join(" ", s.split(""));
	}

	/** {@code font}, smaller until {@code text} fits in {@code width}. */
	private static Font fit(Graphics2D g, Font font, String text, int width) {
		Font f = font;
		while (g.getFontMetrics(f).stringWidth(text) > width && f.getSize2D() > 26) {
			f = f.deriveFont(f.getSize2D() - 2);
		}
		return f;
	}

	private static BufferedImage avatar(User user) {
		try {
			String url = user.getEffectiveAvatar().getUrl(256).replace(".gif", ".png").replace(".webp", ".png");
			HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
					HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				return null;
			}
			try (InputStream in = response.body()) {
				return ImageIO.read(in);
			}
		} catch (Exception e) {
			return null;
		}
	}

	private void loadAssets() throws IOException {
		if (background != null) {
			return;
		}
		try (InputStream in = Welcomer.class.getResourceAsStream("/welcome/background.png")) {
			background = ImageIO.read(in);
		}
		title = font("Audiowide-Regular.ttf", 30f);
		name = font("Inter-Bold.ttf", 62f);
		small = font("Inter-SemiBold.ttf", 26f);
	}

	private static Font font(String file, float size) throws IOException {
		try (InputStream in = Welcomer.class.getResourceAsStream("/welcome/" + file)) {
			return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(size);
		} catch (java.awt.FontFormatException e) {
			throw new IOException(e);
		}
	}
}
