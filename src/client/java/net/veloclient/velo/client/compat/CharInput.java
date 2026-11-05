package net.veloclient.velo.client.compat;

//? if <1.21.2 {
/*/^* 1.21.1 stand-in for 1.21.9's typed-character event. ^/
public record CharInput(int codepoint, int modifiers) {

	public String asString() {
		return Character.toString(codepoint);
	}
}
*///?}
