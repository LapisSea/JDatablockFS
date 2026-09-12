package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.FuzzSequenceSource;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public final class ReproFuzzMultTruncTests{
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
}
