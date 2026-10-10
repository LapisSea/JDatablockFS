package com.lapissea.fuzz;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.testng.Assert.*;

/**
 * Regression for {@link Plan#saveFail(java.io.File)} overwriting an existing save file with a partial /
 * empty buffer when the failure cannot be serialized.
 */
public class PlanSaveFailTests{
	
	@Test
	public void saveFailDoesNotClobberExistingFileWhenNotSerializable() throws Exception{
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			// Any multiplier scales the generated sequence length, so fail on the first action to keep this
			// test independent of the global iteration count.
			if(index == 0) throw new IllegalStateException("action boom");
		}), random -> new Object());
		
		var file = Files.createTempFile("fuzz-save-fail", ".bin");
		try{
			var original = "PRESERVE-ME".getBytes(StandardCharsets.UTF_8);
			Files.write(file, original);
			
			Plan.start(runner, new FuzzConfig().dontLog(), new FuzzSequenceSource.LenSeed(7, 5, 5))
			    .runAll()
			    .saveFail(file.toFile());
			
			assertEquals(Files.readAllBytes(file), original);
		}finally{
			Files.deleteIfExists(file);
		}
	}
}
