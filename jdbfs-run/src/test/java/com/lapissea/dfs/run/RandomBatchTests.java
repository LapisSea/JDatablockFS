package com.lapissea.dfs.run;

import com.lapissea.fuzz.FuzzConfig;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class RandomBatchTests{
	
	@Test
	public void localBatchesKeepAbsoluteIndexes(){
		// Small jobs used to produce a zero sequence length; larger jobs include a partial last batch.
		// The run is scaled by fuzz.iterationMultiplier, so it covers [0, iterations) absolute indexes.
		for(int total : new int[]{1, 7, 255, 10003}){
			int iterations = iterations(total);
			var visits     = new AtomicIntegerArray(iterations);
			TestUtils.randomBatch("indexCoverage", total, (random, index) -> {
				assertTrue(index>=0 && index<iterations, "Out-of-range index " + index);
				visits.incrementAndGet((int)index);
			});
			for(int i = 0; i<iterations; i++){ assertEquals(visits.get(i), 1, "Index " + i); }
		}
	}
	
	@Test
	public void failuresKeepOriginalIndex(){
		var failure = expectThrows(RuntimeException.class, () -> TestUtils.randomBatch("indexFailure", 10003, (random, index) -> {
			if(index == 17){ throw new IllegalStateException("index=" + index); }
		}));
		assertEquals(failure.getCause().getMessage(), "index=17");
	}
	
	private static int iterations(int totalTasks){
		var scaled = FuzzConfig.iterationMultiplier().orElse(BigDecimal.ONE)
		                     .multiply(BigDecimal.valueOf(totalTasks), MathContext.DECIMAL64)
		                     .toBigInteger();
		return scaled.signum()<1? 1 : scaled.intValueExact();
	}
}
