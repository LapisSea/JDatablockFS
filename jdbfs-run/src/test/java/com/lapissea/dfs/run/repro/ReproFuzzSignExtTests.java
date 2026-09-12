package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.FuzzSequence;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproFuzzSignExtTests{

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
