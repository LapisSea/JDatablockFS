package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.FuzzSequenceSource;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;

public class FuzzSequenceTests{

	private static final String MULTIPLIER = "fuzz.iterationMultiplier";

	private static FuzzSequenceSource.LenSeed source(long count, String multiplier){
		var previous = System.getProperty(MULTIPLIER);
		try{
			if(multiplier == null) System.clearProperty(MULTIPLIER);
			else System.setProperty(MULTIPLIER, multiplier);
			return new FuzzSequenceSource.LenSeed(12345L, count, 100);
		}finally{
			if(previous == null) System.clearProperty(MULTIPLIER);
			else System.setProperty(MULTIPLIER, previous);
		}
	}

	@DataProvider
	Object[][] scaledCounts(){
		return new Object[][]{
			{1L, "0.2", 1L},
			{2L, "0.2", 1L},
			{3L, "0.2", 1L},
			{4L, "0.2", 1L},
			{5L, "0.2", 1L},
			{9L, "0.2", 1L},
			{10L, "0.2", 2L},
			{1000L, "0.2", 200L},
			{1L, "1E-100", 1L},
			{3L, "1.5", 4L},
			{7L, null, 7L},
			{7L, "", 7L},
			{7L, "0", 7L},
			{7L, "-1", 7L},
			{7L, "invalid", 7L},
			{Long.MAX_VALUE, "2", Long.MAX_VALUE}
		};
	}

	@Test(dataProvider = "scaledCounts")
	void scaledCountPreservesPositiveRun(long count, String multiplier, long expected){
		var previous = System.getProperty(MULTIPLIER);
		var source = source(count, multiplier);
		assertEquals(System.getProperty(MULTIPLIER), previous);
		assertEquals(source.totalIterations(), expected);
		if(expected<=1000){
			var sequences = source.all().toList();
			assertEquals(sequences.size(), (int)Math.ceilDiv(expected, 100));
			long position = 0;
			for(var sequence : sequences){
				assertEquals(sequence.startIndex(), position);
				assertEquals(sequence.iterations(), (int)Math.min(100, expected - position));
				position += sequence.iterations();
			}
			assertEquals(position, expected);
		}
	}


	@DataProvider(name = "negativeSeeds")
	Object[][] negativeSeeds(){
		return new Object[][]{
			{-5L},
			{-1L},
			{-129L},
			{-300L},
			{-123456L},
			{-999999999L},
			{Long.MIN_VALUE},
		};
	}

	@Test(dataProvider = "negativeSeeds")
	void negativeSeedRoundTrip(long seed){
		var original = new FuzzSequence(0, 0, seed, 100);
		var stick = original.makeDataStick();
		var back = FuzzSequence.fromDataStick(stick);
		assertThat(back.seed())
			.as("round-tripped seed (original=%d, stick=%s)", seed, stick)
			.isEqualTo(seed);
		assertThat(back)
			.as("full sequence round trip (stick=%s)", stick)
			.isEqualTo(original);
	}

	@DataProvider(name = "signedBoundaries")
	Object[][] signedBoundaries(){
		var values = new java.util.ArrayList<Object[]>();
		for(int bytes = 1; bytes<Long.BYTES; bytes++){
			long boundary = 1L<<(bytes*Byte.SIZE - 1);
			for(long value : new long[]{-boundary - 1, -boundary, -boundary + 1,
			                            boundary - 1, boundary, boundary + 1}){
				values.add(new Object[]{value});
			}
		}
		for(long value : new long[]{Long.MIN_VALUE, Long.MAX_VALUE, -1, 0, 1}){
			values.add(new Object[]{value});
		}
		return values.toArray(Object[][]::new);
	}

	@Test(dataProvider = "signedBoundaries")
	void signedBoundaryRoundTrip(long value){
		var original = new FuzzSequence(Long.MAX_VALUE - Integer.MAX_VALUE, value, value, Integer.MAX_VALUE);
		assertThat(FuzzSequence.fromDataStick(original.makeDataStick())).isEqualTo(original);
	}

	@Test
	void existingCompactNegativeSeedStick(){
		assertThat(FuzzSequence.fromDataStick("EREAAPtk"))
			.isEqualTo(new FuzzSequence(0, 0, -5, 100));
	}

	@Test
	void positiveSeedRoundTripSanity(){
		var original = new FuzzSequence(0, 0, 5L, 100);
		var stick = original.makeDataStick();
		assertThat(FuzzSequence.fromDataStick(stick))
			.as("positive seed sanity (stick=%s)", stick)
			.isEqualTo(original);
	}
}
