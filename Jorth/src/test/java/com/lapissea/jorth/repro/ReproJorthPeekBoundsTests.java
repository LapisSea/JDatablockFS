package com.lapissea.jorth.repro;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.TypeStack;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * JORTH-B16: TypeStack.peek(int pos) has no bounds check, so an out-of-range
 * pos throws an unchecked IndexOutOfBoundsException instead of the documented
 * MalformedJorth - a caller doing `try { ... } catch (MalformedJorth e) { ... }`
 * misses the failure entirely, and code that only handles MalformedJorth blows
 * up with an unchecked exception.
 *
 * What the code is supposed to do:
 *   TypeStack's stack-consuming/reading API is guarded by requireElements()
 *   (TypeStack.java:56-60), which throws the CHECKED MalformedJorth
 *   (Jorth/src/main/java/com/lapissea/jorth/exceptions/MalformedJorth.java,
 *   `extends Exception`) when the requested elements are not on the stack.
 *   pop() (TypeStack.java:37-43) and peekLast() (TypeStack.java:61-67) both
 *   call requireElements(1) first, so an underflow on either surfaces as the
 *   documented checked MalformedJorth. peek(int pos) - the positional variant
 *   of peekLast() - is part of the same API and its out-of-range failure is
 *   the same kind of malformed-state failure, so it is documented to fail
 *   with MalformedJorth as well.
 *
 * What it actually does:
 *   TypeStack.peek(int pos) (TypeStack.java:68-75) has NO bounds check and NO
 *   `throws` clause:
 *       public GenericType peek(int pos){
 *           if(parent == null){
 *               return stack.get(pos);          // <- unguarded List.get
 *           }
 *           int localPos = pos - parent.size();
 *           if(localPos<0) return parent.peek(pos);
 *           return stack.get(localPos);         // <- unguarded List.get
 *       }
 *   `stack` is a plain ArrayList (TypeStack.java:13), so stack.get(pos) with
 *   pos >= size (or pos < 0) throws the UNCHECKED IndexOutOfBoundsException -
 *   not the documented checked MalformedJorth. (A proper fix must also add
 *   `throws MalformedJorth` to peek's signature, since it currently declares
 *   nothing.)
 *
 * Why the tests fail:
 *   peekOutOfBoundsShouldThrowMalformedJorth (THE BUG-TRIGGERING TEST) pushes
 *   exactly one value (GenericType.INT) onto a root TypeStack (parent == null)
 *   and calls peek(10) - far beyond the stack size of 1. It asserts the
 *   documented contract (MalformedJorth), so it FAILS with "Expecting code to
 *   raise a throwable of type MalformedJorth but was actually
 *   IndexOutOfBoundsException" - the unguarded stack.get(10) at
 *   TypeStack.java:70 throws the unchecked exception instead.
 *   peekOutOfBounds_pinsActualIndexOutOfBounds PINS the actual buggy behavior
 *   (passes against current code): the SAME peek(10) throws the unchecked
 *   IndexOutOfBoundsException - NOT a MalformedJorth - proving the failure is
 *   not catchable as the documented type.
 *   peekInBoundsWorks (passes) is the control: peek(0) on the same one-element
 *   stack returns the pushed value without throwing - proving in-bounds peek
 *   is fine and the bug is specifically the missing out-of-range check.
 *
 * Note: this is DISTINCT from B06 (the pop()/peekLast() parent-chain asymmetry
 * "can not pop values outside the code path", already covered by
 * ReproJorthTypeStackTests). B16 is specifically the MISSING BOUNDS CHECK in
 * peek(int) producing an unchecked IndexOutOfBoundsException instead of the
 * documented MalformedJorth.
 *
 * Note: this is a white-box repro. TypeStack's constructor
 * `TypeStack(TypeStack parent)` is public (TypeStack.java:16) and the parent
 * argument may be null, so `new TypeStack(null)` is directly reachable - no
 * reflection needed. push(GenericType) (TypeStack.java:34) and peek(int)
 * (TypeStack.java:68) are both public.
 */
public class ReproJorthPeekBoundsTests{

	@Test
	void peekOutOfBoundsShouldThrowMalformedJorth(){
		// Step 1: a root TypeStack (parent == null), exactly as constructed in
		// ReproJorthTypeStackTests - no reflection needed, the constructor is public.
		var stack = new TypeStack(null);
		// Step 2: push exactly ONE value so the stack size is 1.
		stack.push(GenericType.INT);
		// Step 3: peek(10) is far beyond the stack size of 1. With the current
		// code (TypeStack.java:68-75) parent is null, so it takes the
		// `stack.get(10)` path at TypeStack.java:70 - an UNGUARDED ArrayList
		// get with no requireElements()/range check - which throws the
		// unchecked IndexOutOfBoundsException("Index 10 out of bounds for
		// length 1"). The documented contract is the checked MalformedJorth
		// (same as pop()/peekLast()/requireElements()), so this test FAILS:
		// expected MalformedJorth, actual type is IndexOutOfBoundsException.
		assertThatThrownBy(() -> stack.peek(10))
		    .as("peek(10) on a one-element stack is out of range; the documented "
		      + "MalformedJorth contract (like pop()/peekLast()/requireElements()) "
		      + "is violated - the actual type is the unchecked IndexOutOfBoundsException "
		      + "thrown by the unguarded stack.get(pos) at TypeStack.java:70")
		    .isInstanceOf(MalformedJorth.class);
	}

	@Test
	void peekOutOfBounds_pinsActualIndexOutOfBounds(){
		// Step 1-2: same setup - root TypeStack, one value pushed (size 1).
		var stack = new TypeStack(null);
		stack.push(GenericType.INT);
		// Step 3: PINS the actual buggy behavior (passes against current code):
		// the SAME out-of-range peek(10) throws the UNCHECKED
		// IndexOutOfBoundsException from the unguarded stack.get(10) at
		// TypeStack.java:70 - NOT the documented MalformedJorth - so a caller
		// doing `catch (MalformedJorth)` misses this failure entirely.
		Throwable thrown = catchThrowable(() -> stack.peek(10));
		System.out.println("[JORTH-B16] actual exception: " + thrown);
		assertThat(thrown)
		    .as("peek(int) (TypeStack.java:68-75) has no bounds check, so an "
		      + "out-of-range pos throws an unchecked IndexOutOfBoundsException "
		      + "instead of the documented MalformedJorth")
		    .isInstanceOf(IndexOutOfBoundsException.class)
		    .isNotInstanceOf(MalformedJorth.class);
		assertThat(thrown)
		    .as("the unchecked exception comes from the unguarded ArrayList.get(pos)")
		    .hasMessageContaining("10");
	}

	@Test
	void peekInBoundsWorks(){
		// Control (passes): the SAME one-element root stack, but peek(0) is
		// IN bounds. TypeStack.java:70 (parent == null path) takes
		// stack.get(0), which is valid, so the pushed value is returned
		// without throwing. This proves in-bounds peek is fine and isolates
		// the bug to the missing out-of-range check (B16), not to peek in general.
		var stack = new TypeStack(null);
		stack.push(GenericType.INT);
		assertThat(stack.peek(0))
		    .as("peek(0) on a one-element stack must return the pushed value without throwing")
		    .isEqualTo(GenericType.INT);
	}
}
