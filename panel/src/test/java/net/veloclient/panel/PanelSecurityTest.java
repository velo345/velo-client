package net.veloclient.panel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PanelSecurityTest {

	@Test
	void totpMatchesRfc6238Vectors() {
		byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
		// RFC 6238 appendix B (SHA-1), last six digits.
		assertEquals("287082", Auth.totp(key, 59L / 30));
		assertEquals("081804", Auth.totp(key, 1111111109L / 30));
		assertEquals("005924", Auth.totp(key, 1234567890L / 30));
	}

	@Test
	void base32RoundTrips() {
		byte[] data = {1, 2, 3, (byte) 250, 99, 0, 17, 42, 7, 8};
		assertArrayEquals(data, Auth.unbase32(Auth.base32(data)));
	}

	@Test
	void codeIsCheckedAgainstTheSecret() {
		Auth.User user = new Auth.User();
		user.totpSecret = Auth.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
		long now = 1234567890L * 1000;
		assertNotNull(Auth.totpStep(user, "005924", now));
		assertNull(Auth.totpStep(user, "005925", now));
		assertNull(Auth.totpStep(user, "abc", now));
		assertNull(Auth.totpStep(user, "005924", now + 120_000));
	}

	@Test
	void filesStayInsideTheirRoot(@TempDir Path dir) throws Exception {
		Path root = Files.createDirectories(dir.resolve("data"));
		Files.writeString(dir.resolve("secret.txt"), "nope");
		Files.writeString(root.resolve("ok.json"), "{}");
		Files.createSymbolicLink(root.resolve("escape"), dir);
		FileManager files = new FileManager("Data=" + root);
		assertEquals("{}", files.read("r0", "ok.json").get("text"));
		for (String bad : new String[] {"../secret.txt", "/etc/passwd", "a/../../secret.txt", "escape/secret.txt", "..", "x\0y"}) {
			assertThrows(Http.Fail.class, () -> files.read("r0", bad), bad);
		}
		assertThrows(Http.Fail.class, () -> files.delete("r0", "", true));
		files.write("r0", "new.txt", "hi".getBytes(StandardCharsets.UTF_8), null);
		assertEquals("hi", Files.readString(root.resolve("new.txt")));
		assertThrows(Http.Fail.class, () -> new FileManager("Data=" + root + ":ro").write("r0", "x.txt", new byte[0], null));
	}
}
