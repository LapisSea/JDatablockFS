package com.lapissea.fuzz;

import org.testng.annotations.Test;

import java.util.stream.Stream;

import static org.testng.Assert.*;

/**
 * Regression for {@link FuzzingRunner} crashing with {@code NoSuchElementException} when no call-stack
 * frame named {@code run} is present, e.g. {@code runLocal(null, ...)} called directly.
 */
public class FuzzingRunnerTaskNameTests{
	
	@Test
	public void fallsBackToUnnamedWhenStackHasNoCaller(){
		assertEquals(FuzzingRunner.taskName(Stream.empty()), "unnamed");
	}
	
	@Test
	public void resolvesATaskNameFromTheLiveStack(){
		var name = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(FuzzingRunner::taskName);
		assertNotNull(name);
		assertFalse(name.isBlank());
	}
}
