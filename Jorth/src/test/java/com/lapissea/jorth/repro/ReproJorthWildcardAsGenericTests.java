package com.lapissea.jorth.repro;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * JORTH-B15: JType.WILDCARD.asGeneric() (and multi-bounded wildcards) throw an
 * unchecked UnsupportedOperationException instead of the documented MalformedJorth
 * for malformed types, so a caller doing `try { ... } catch (MalformedJorth e) { ... }`
 * misses those failures - they blow through as unchecked exceptions.
 *
 * What the code is supposed to do:
 *   JType.asGeneric (Jorth/src/main/java/com/lapissea/jorth/lang/type/JType.java:149-163)
 *   resolves any JType to a GenericType. A Wildcard (the record at JType.java:12,
 *   `record Wildcard(List<JType> lower, List<JType> upper)`) is resolvable when it
 *   has exactly ONE lower bound (yield that bound) or exactly ONE upper bound (yield
 *   that bound). A wildcard whose bounds cannot be reduced to a single type - the
 *   UNBOUNDED `?` (the JType.WILDCARD constant, JType.java:96:
 *   `Wildcard WILDCARD = new Wildcard(List.of(), List.of());` - both bound lists
 *   empty) or a MULTI-bounded wildcard (e.g. `? extends A & B`) - is a malformed-type
 *   failure. Jorth's documented failure type for malformed types is MalformedJorth
 *   (Jorth/src/main/java/com/lapissea/jorth/exceptions/MalformedJorth.java,
 *   `extends Exception` - a checked exception), the same type the surrounding Jorth
 *   API declares/throws for type errors, e.g. GenericType.instanceOf
 *   (GenericType.java:196, declares `throws MalformedJorth`) which itself calls
 *   asGeneric() (GenericType.java:219-220), or Insn.java:822 which throws
 *   MalformedJorth on an argument type mismatch.
 *
 * What it actually does:
 *   JType.asGeneric (JType.java:152-161) handles the Wildcard case:
 *     - lower non-empty and lower.size() != 1  -> throw new UnsupportedOperationException() (JType.java:155)
 *     - else upper.size() != 1                 -> throw new UnsupportedOperationException() (JType.java:159)
 *   Both are UNCHECKED RuntimeExceptions, not the documented checked MalformedJorth.
 *   So JType.WILDCARD (lower empty, upper empty -> upper.size()==0 != 1) hits the
 *   throw at JType.java:159, and a multi-bounded wildcard hits JType.java:155 or
 *   :159. A `catch (MalformedJorth)` around code that reaches asGeneric (e.g. a
 *   GenericType.instanceOf caller) misses the failure entirely.
 *   (Note: asGeneric's own signature is `default GenericType asGeneric()` with NO
 *   `throws` clause - the violated contract is Jorth's consistent, documented
 *   MalformedJorth failure type for malformed types used across its public API.)
 *
 * Why the tests fail:
 *   unboundedWildcardAsGenericShouldThrowMalformedJorth (THE BUG-TRIGGERING TEST)
 *   calls JType.WILDCARD.asGeneric() and asserts the documented contract
 *   (MalformedJorth), so it FAILS with "Expecting code to raise a throwable of type
 *   MalformedJorth but was actually UnsupportedOperationException".
 *   unboundedWildcardAsGeneric_pinsActualUOE PINS the actual buggy behavior (passes
 *   against current code): the SAME call throws the unchecked UnsupportedOperationException
 *   from JType.java:159 - not a MalformedJorth - proving it is not catchable as
 *   MalformedJorth.
 *   boundedWildcardAsGenericWorks (passes) is the control: a wildcard with EXACTLY
 *   ONE upper bound (JType.upper(String.class), JType.java:98-100) resolves to that
 *   bound's GenericType without throwing (the working path at JType.java:158-161) -
 *   proving the single-bound path is fine and the bug is specific to unbounded /
 *   multi-bounded wildcards.
 *
 * Note: this is a white-box repro. JType.WILDCARD is a public interface constant of
 * type JType.Wildcard (a public record implementing JType, JType.java:12) and
 * asGeneric() is a public default method on the JType interface (JType.java:149),
 * so both are directly reachable - no reflection needed.
 */
public class ReproJorthWildcardAsGenericTests{

	@Test
	void unboundedWildcardAsGenericShouldThrowMalformedJorth(){
		// JType.WILDCARD (JType.java:96) is the unbounded `?`: a Wildcard with both
		// bound lists empty. asGeneric (JType.java:152-161) cannot reduce it to a
		// single bound type: lower is empty, upper.size() == 0 != 1 -> the throw at
		// JType.java:159. The documented malformed-type failure is MalformedJorth,
		// but current code throws the UNCHECKED UnsupportedOperationException instead
		// -> this test FAILS: expected MalformedJorth, actual type is
		// UnsupportedOperationException.
		assertThatThrownBy(() -> JType.WILDCARD.asGeneric())
		    .as("the unbounded `?` (JType.WILDCARD) cannot be resolved to a single bound type; "
		      + "the documented malformed-type failure is MalformedJorth, but the actual type is "
		      + "UnsupportedOperationException thrown at JType.java:159")
		    .isInstanceOf(MalformedJorth.class);
	}

	@Test
	void unboundedWildcardAsGeneric_pinsActualUOE(){
		// PINS the actual buggy behavior (passes against current code): the SAME
		// call throws the unchecked UnsupportedOperationException from JType.java:159
		// - NOT the documented MalformedJorth - so a caller doing
		// `catch (MalformedJorth)` misses this failure entirely.
		Throwable thrown = catchThrowable(() -> JType.WILDCARD.asGeneric());
		System.out.println("[JORTH-B15] actual exception: " + thrown);
		assertThat(thrown)
		    .as("JType.asGeneric (JType.java:159) throws an unchecked UnsupportedOperationException "
		      + "for the unbounded `?` instead of the documented MalformedJorth")
		    .isInstanceOf(UnsupportedOperationException.class);
		assertThat(thrown)
		    .as("the unchecked UnsupportedOperationException is NOT catchable as the documented MalformedJorth")
		    .isNotInstanceOf(MalformedJorth.class);
	}

	@Test
	void boundedWildcardAsGenericWorks(){
		// Control (passes): a wildcard with EXACTLY ONE upper bound takes the
		// working path in asGeneric (JType.java:158-161): lower is empty,
		// upper.size() == 1 -> yields upper.getFirst().asGeneric(). JType.upper
		// (JType.java:98-100) builds `new Wildcard(List.of(), List.of(String))`,
		// so the resolution must yield String's GenericType without throwing. This
		// proves the single-bound path is fine and isolates the bug to the
		// unbounded / multi-bounded wildcard cases (JType.java:155 / :159).
		GenericType resolved = JType.upper(String.class).asGeneric();
		assertThat(resolved)
		    .as("? extends String must resolve to String's GenericType without throwing")
		    .isEqualTo(GenericType.STRING);
	}
}
