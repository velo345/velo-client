package net.veloclient.panel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProofOfWorkTest {

	private static ProofOfWork.Solution solve(ProofOfWork.Challenge c) {
		for (int n = 0; n <= c.maxnumber(); n++) {
			if (ProofOfWork.sha256(c.salt() + n).equals(c.challenge())) {
				return new ProofOfWork.Solution(c.salt(), n, c.challenge(), c.signature());
			}
		}
		throw new AssertionError("unsolvable");
	}

	@Test
	void solvedOnceThenRefused() {
		ProofOfWork pow = new ProofOfWork();
		ProofOfWork.Solution s = solve(pow.issue());
		assertTrue(pow.verify(s));
		assertFalse(pow.verify(s), "a solution works once");
	}

	@Test
	void wrongAnswersAndForgedChallengesFail() {
		ProofOfWork pow = new ProofOfWork();
		ProofOfWork.Challenge c = pow.issue();
		ProofOfWork.Solution s = solve(c);
		assertFalse(pow.verify(new ProofOfWork.Solution(s.salt(), s.number() + 1, s.challenge(), s.signature())));
		assertFalse(pow.verify(new ProofOfWork.Solution(s.salt(), s.number(), s.challenge(), "00" + s.signature().substring(2))));
		// A challenge someone made up themselves (with a number they know) isn't signed by us.
		String fake = ProofOfWork.sha256("x?expires=" + (System.currentTimeMillis() + 60_000) + 1);
		assertFalse(pow.verify(new ProofOfWork.Solution("x?expires=" + (System.currentTimeMillis() + 60_000), 1, fake, s.signature())));
		assertFalse(new ProofOfWork().verify(s), "another process's key");
		assertFalse(pow.verify(null));
	}
}
