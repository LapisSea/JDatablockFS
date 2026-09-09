package com.lapissea.jorth.repro;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.TypeStack;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for TypeStack.peek(int) bounds checking: an out-of-range
 * position must surface as the checked MalformedJorth (same contract as
 * pop()/peekLast()/requireElements()), not an unchecked IndexOutOfBoundsException.
 */
public class ReproJorthPeekBoundsTests{
	
	@DataProvider
	Object[][] outOfBoundsPositions(){
		// stack size is always 1 in these tests, so the last valid index is 0
		return new Object[][]{
			{1},   // one past the last valid index
			{10},  // far beyond the stack
			{-1},  // negative index
		};
	}
	
	@Test(dataProvider = "outOfBoundsPositions",
	      expectedExceptions = MalformedJorth.class,
	      expectedExceptionsMessageRegExp = "peek position .+ is out of bounds for the stack of size 1")
	void peekOutOfBoundsShouldThrowMalformedJorth(int pos) throws MalformedJorth{
		var stack = new TypeStack(null);
		stack.push(GenericType.INT);
		stack.peek(pos);
	}
	
	@Test
	void peekInBoundsWorks() throws MalformedJorth{
		var stack = new TypeStack(null);
		stack.push(GenericType.INT);
		assertThat(stack.peek(0))
			.as("peek(0) on a one-element stack must return the pushed value without throwing")
			.isEqualTo(GenericType.INT);
	}
}
