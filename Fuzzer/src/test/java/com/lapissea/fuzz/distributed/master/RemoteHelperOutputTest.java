package com.lapissea.fuzz.distributed.master;

import com.lapissea.fuzz.distributed.RemoteSequenceResult;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Regression for a helper's captured output accumulating across results: every result carried all output
 * since the job started, growing without bound and mis-attributing earlier sequences' output.
 */
public class RemoteHelperOutputTest{
	
	@Test
	public void capturedOutputIsConsumedPerResult(){
		var helper = new DistributedSession.RemoteHelper("helper", 1, null);
		
		helper.log(false, "first");
		helper.log(true, "err1");
		assertEquals(helper.consumeOutput(), new RemoteSequenceResult.CapturedOutput("first", "err1"));
		assertEquals(helper.consumeOutput(), RemoteSequenceResult.CapturedOutput.EMPTY);
		
		helper.log(false, "second");
		assertEquals(helper.consumeOutput().stdout(), "second");
	}
}
