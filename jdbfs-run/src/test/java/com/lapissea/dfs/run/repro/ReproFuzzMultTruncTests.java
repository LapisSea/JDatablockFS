package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.FuzzSequenceSource;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * BUG-05 (fuzz-mult-trunc): the quick-run multiplier truncates small iteration counts to zero.
 *
 * What the code is supposed to do:
 *   The documented quick-run flag {@code -Dfuzz.iterationMultiplier=0.2} scales the fuzz
 *   iteration count down so local test runs finish fast. In {@link FuzzSequenceSource.LenSeed},
 *   {@code modIterCount} (Fuzzer/src/main/java/com/lapissea/fuzz/FuzzSequenceSource.java:68-84)
 *   applies the multiplier to the requested {@code totalIterations}. A positive count must stay
 *   positive after scaling: the source's own guard (FuzzSequenceSource.java:19-21) states
 *   "Total iterations must be greater than zero", and a scaled-down run must still execute at
 *   least one sequence.
 *
 * What it actually does:
 *   FuzzSequenceSource.java:77 computes
 *       mul.multiply(new BigDecimal(iterCount), MathContext.DECIMAL64).toBigInteger().longValueExact()
 *   {@code BigDecimal.toBigInteger()} truncates the fractional part toward zero. With multiplier
 *   0.2 and {@code totalIterations} in 1..4 the product is 0.2..0.8, which truncates to 0, so the
 *   constructed LenSeed silently carries {@code totalIterations() == 0}. Because the guard at
 *   :19-21 checks the RAW parameter BEFORE the multiplier is applied at :26, the
 *   "Total iterations must be greater than zero" exception never fires for counts 1..4; instead
 *   {@code all()} (FuzzSequenceSource.java:95, Math.ceilDiv(0, sequenceLength) == 0) produces an
 *   EMPTY stream and any fuzz run driven by such a source silently executes zero sequences
 *   instead of yielding a reduced count >= 1.
 *
 * Why this test fails:
 *   With the multiplier set to the documented 0.2, it builds LenSeed sources for counts 1..4 and
 *   asserts the scaled count is >= 1 (and that at least one sequence is produced). The truncation
 *   at FuzzSequenceSource.java:77 yields 0, so the assertions fail. The control assertions
 *   (count 1000 -> 200, count 5 -> 1) pass, proving the multiplier is active and that only
 *   counts 1..4 are hit by the truncation.
 */
public final class ReproFuzzMultTruncTests{
	
	// The documented quick-run flag. modIterCount reads the property at LenSeed construction
	// time, so setting it here (before any LenSeed is built) is equivalent to the mvn
	// -Dfuzz.iterationMultiplier=0.2 flag and makes the test robust either way.
	static{
		System.setProperty("fuzz.iterationMultiplier", "0.2");
	}
	
	@Test
	public void scaledCount_mustStayPositive_forSmallCounts(){
		final long seed = 12345L;
		
		// Control: proves the multiplier is active in this JVM (0.2 * 1000 = 200)
		var control = new FuzzSequenceSource.LenSeed(seed, 1000, 100);
		assertEquals(control.totalIterations(), 200L,
			"control failed: fuzz.iterationMultiplier=0.2 is not active in this JVM");
		
		// Boundary: 0.2 * 5 = 1.0 -> 1, so counts >= 5 are not affected by the truncation
		var boundary = new FuzzSequenceSource.LenSeed(seed, 5, 100);
		assertEquals(boundary.totalIterations(), 1L,
			"boundary failed: 0.2*5 must not be truncated to 0");
		
		for(long count = 1; count <= 4; count++){
			var source = new FuzzSequenceSource.LenSeed(seed, count, 100);
			// Bug: FuzzSequenceSource.java:77 truncates 0.2*count (0.2..0.8) to 0, so the
			// reduced count is 0 instead of >= 1.
			assertTrue(source.totalIterations() >= 1,
				"totalIterations=" + count + " under multiplier 0.2 must yield a reduced count >= 1 "
					+ "but FuzzSequenceSource.java:77 truncated it to " + source.totalIterations());
		}
	}
	
	@Test
	public void smallCount_sourceMustYieldAtLeastOneSequence(){
		var source = new FuzzSequenceSource.LenSeed(12345L, 3, 100);
		var sequences = source.all().count();
		// Consequence of the same truncation: totalIterations()==0 makes all()
		// (FuzzSequenceSource.java:95) an empty stream, so the source silently runs
		// zero sequences instead of a reduced run.
		assertTrue(sequences >= 1,
			"a source requested for 3 iterations (x0.2) must still yield at least one sequence "
				+ "but produced " + sequences + " (silent no-op caused by truncation at FuzzSequenceSource.java:77)");
	}
}
