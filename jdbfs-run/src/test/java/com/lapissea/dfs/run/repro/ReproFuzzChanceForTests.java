package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.RNGEnum;
import org.testng.annotations.Test;

import java.util.Random;
import java.util.random.RandomGenerator;

import static org.testng.Assert.assertTrue;

/**
 * Reproduction of BUG-03 (fuzz-chancefor).
 *
 * <p>What the code is supposed to do:
 * {@code RNGEnum.of(E.class).chanceFor(v1, p1).chanceFor(v2, p2).apply(random)} is a weighted
 * pick — each value vi must be returned with (approximately) its declared probability pi. Two
 * options each declared at 0.5 must therefore each come up in ~50% of draws.
 *
 * <p>What it actually does:
 * {@code RNGEnum.apply} (Fuzzer/src/main/java/com/lapissea/fuzz/RNGEnum.java:52-56, same shape in
 * RNGType.java:46-50) rolls one INDEPENDENT {@code random.nextFloat() <= chance} gate per chance,
 * in declaration order, and returns on the first success. A chance only ever gets to roll if all
 * earlier gates failed, so the declared probabilities are neither exclusive nor normalized:
 * <ul>
 *   <li>3-value enum, A/B declared 0.5/0.5 (C left to the random pick): B only rolls when A's
 *       independent gate failed, so P(B) = (1-0.5)*0.5 = 0.25 — the "≈0.25 instead of 0.5" skew
 *       reported for a chanceFor pair.</li>
 *   <li>2-value enum, A/B declared 0.5/0.5: the residual 25% mass (both gates failed) goes to the
 *       re-roll fallback (RNGEnum.java:58-65), which is itself broken: chanceFor computes the sum
 *       as {@code var totalChance = 0} (an int, RNGEnum.java:76) and {@code totalChance +=
 *       i.chance} (RNGEnum.java:78) — a compound assignment with implicit narrowing cast — so
 *       totalChance is always 0. The fallback then computes r = nextFloat()*0 = 0, r -
 *       chance[0] &lt;= 0 always holds, and it ALWAYS returns the first declared value. Hence
 *       P(A) = 0.5 + 0.25*1.0 = 0.75, P(B) = 0.25 — instead of 0.5/0.5.</li>
 * </ul>
 *
 * <p>Why the tests fail:
 * With a seeded RNG and N=100000 draws the observed frequencies are ~0.75 and ~0.25
 * (standard error ≈0.0016), far outside the ±5% band around the declared 0.5, so the
 * "freq ≈ declared chance" assertions fail.
 */
public class ReproFuzzChanceForTests {

	private enum Two {
		A, B
	}

	private enum Three {
		A, B, C
	}

	private static final int N = 100_000;
	/** ±5% band around the declared chance, as specified by the bug report. */
	private static final double TOLERANCE = 0.05;

	private static <E extends Enum<E>> long count(E value, RNGEnum<E> rng){
		RandomGenerator random = new Random(0x5EED); // fixed seed: deterministic run
		long hits = 0;
		for(int i = 0; i<N; ++i){
			if(rng.apply(random) == value) ++hits;
		}
		return hits;
	}

	@Test
	public void twoOptionsDeclaredHalfHalf_firstOptionFrequency(){
		var rng = RNGEnum.of(Two.class)
				.chanceFor(Two.A, 0.5f)
				.chanceFor(Two.B, 0.5f);

		double freq = count(Two.A, rng) / (double)N;
		// Key assertion: A is declared at 0.5, so it must come up in ~50% of draws.
		// Bug: apply() gates each chance with an independent nextFloat() roll in declaration
		// order (RNGEnum.java:52-56); the residual mass both gates failed is re-rolled by the
		// fallback (:58-65) which — because totalChance is int-truncated to 0 in chanceFor
		// (:76-80) — always returns the FIRST declared value. So A actually comes up ~75%, the
		// assertion below fails.
		assertTrue(Math.abs(freq - 0.5) <= TOLERANCE,
		           "freq(A) must be ~0.5 (declared chance) but was " + freq + " over " + N + " draws");
	}

	@Test
	public void twoOptionsDeclaredHalfHalf_secondOptionFrequency(){
		// 3-value enum so the undeclared C keeps randomPick non-empty: this isolates the
		// declaration-order gating effect (no fallback re-roll) on the second declared option.
		var rng = RNGEnum.of(Three.class)
				.chanceFor(Three.A, 0.5f)
				.chanceFor(Three.B, 0.5f);

		double freq = count(Three.B, rng) / (double)N;
		// Key assertion: B is declared at 0.5, so it must come up in ~50% of draws.
		// Bug: B's gate (RNGEnum.java:52-56) is only reached when A's independent gate failed,
		// so P(B) = (1-0.5) * 0.5 = 0.25 — the assertion below fails.
		assertTrue(Math.abs(freq - 0.5) <= TOLERANCE,
		           "freq(B) must be ~0.5 (declared chance) but was " + freq + " over " + N + " draws");
	}
}
