package com.lapissea.jorth.repro;

import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.type.GenericType;
import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JORTH-B13: FunctionInfo.Signature constructor's generic-arg-strip loop is a no-op
 * (inverted condition) -> parameter type-args are never stripped.
 *
 * What the code is supposed to do:
 *   FunctionInfo.Signature is the normalized (name, parameter types) key used for
 *   function lookup: FunctionDefinition.makeSignature (FunctionDefinition.java:151-152)
 *   builds the definition-side signature from plain Class arguments (no type-args),
 *   while call sites (CodeBlock.java:355, CodeBlock.java:485) build the lookup
 *   signature from stack types, which CAN carry type-args (e.g. a List<String> value).
 *   ClassInfo.getFunction (ClassInfo.java:104-108) resolves the call by an equality
 *   map lookup `functions.get(signature)`, so the Signature constructor
 *   (FunctionInfo.java:24-36) must normalize the parameter types by STRIPPING generic
 *   type-args from EVERY parameter: List<String> must be recorded as List, so that a
 *   call-side signature equals the definition-side one.
 *
 * What it actually does:
 *   The strip loop (FunctionInfo.java:28-33) is
 *       for(int i = 0; i<argTmp.size(); i++){
 *           if(argTmp.get(i).hasArgs()) continue;          // <- inverted condition
 *           if(!copy) argTmp = new ArrayList<>(argTmp);
 *           copy = true;
 *           argTmp.set(i, argTmp.get(i).withoutArgs());
 *       }
 *   It `continue`s (skips) exactly the parameters that HAVE type-args, and applies
 *   withoutArgs() only to parameters that DON'T have type-args - where it is a no-op
 *   (GenericType.withoutArgs, GenericType.java:260-264, returns `this` when args is
 *   empty). So the intended stripping never happens and a parameter like
 *   List<String> is kept verbatim in the computed Signature.
 *
 * Why the tests fail:
 *   genericParamTypeArgsAreStripped and mixedParamsGenericStrippedPlainKept construct
 *   a Signature with a List<String> parameter and assert the INTENDED behavior: the
 *   stored parameter has no type-args (hasArgs() == false, i.e. List<String> is
 *   normalized to List). Against the current code the args are retained, so the
 *   assertion fails with "expected: [java.util.List] but was:
 *   [java.util.List<java.lang.String>]", demonstrating the no-op strip loop. The
 *   control (non-generic parameters such as int) passes, isolating the failure to the
 *   inverted condition at FunctionInfo.java:29.
 *
 * Note: this is a masked/edge bug. It is hard to trigger through the public DSL -
 * Java cannot overload methods differing only in parameter type-args, and most DSL
 * call paths pass plain Class types without type-args - so a white-box unit test of
 * the Signature constructor is the cleanest demonstration. FunctionInfo.Signature is
 * a public nested record (FunctionInfo.java:20) with a public constructor
 * (FunctionInfo.java:24), directly reachable from this test's package.
 */
public class ReproJorthSignatureStripTests{
	
	@Test
	void controlNonGenericParamsAreUnchanged(){
		// Control: parameters WITHOUT type-args (int, String) are already in their
		// normalized form. withoutArgs() on them is a no-op (GenericType.java:262),
		// so they must pass through unchanged. This must pass against the current
		// code, proving the harness is sound and isolating the failures to the
		// inverted strip condition.
		var sig = new FunctionInfo.Signature("control", List.of(GenericType.INT, GenericType.STRING));
		assertThat(sig.name())
		    .as("the signature name must be stored as-is")
		    .isEqualTo("control");
		assertThat(sig.args())
		    .as("non-generic parameters must be stored unchanged")
		    .containsExactly(GenericType.INT, GenericType.STRING);
		assertThat(sig.args().get(0).hasArgs())
		    .as("int carries no type-args")
		    .isFalse();
	}
	
	@Test
	void genericParamTypeArgsAreStripped(){
		// A parameter declared as List<String> (i.e. it HAS generic type-args).
		var listOfString = GenericType.of(List.class).withArgs(String.class);
		assertThat(listOfString.hasArgs())
		    .as("precondition: the input parameter DOES carry type-args")
		    .isTrue();
		// The Signature constructor must strip the type-args (intended behavior of
		// the loop at FunctionInfo.java:28-33): List<String> normalized to List.
		var sig = new FunctionInfo.Signature("takeList", List.of(listOfString));
		// Bug: FunctionInfo.java:29 does `if(argTmp.get(i).hasArgs()) continue;` -
		// it skips the very parameter that has type-args, so List<String> is stored
		// verbatim instead of being normalized to List.
		assertThat(sig.args())
		    .as("parameter List<String> must be normalized to List (type-args stripped)")
		    .containsExactly(GenericType.of(List.class));
		assertThat(sig.args().get(0).hasArgs())
		    .as("the stored parameter must have NO type-args after normalization")
		    .isFalse();
	}
	
	@Test
	void mixedParamsGenericStrippedPlainKept(){
		// Mixed parameter list: List<String> (must be stripped) followed by int
		// (must be kept). Shows the intended loop normalizes every position: type-args
		// removed where present, values unchanged where absent.
		var listOfString = GenericType.of(List.class).withArgs(String.class);
		var sig = new FunctionInfo.Signature("mixed", List.of(listOfString, GenericType.INT));
		// Bug: List<String> retains its type-args because of the inverted
		// `if(hasArgs()) continue` at FunctionInfo.java:29; int passes through.
		assertThat(sig.args())
		    .as("List<String> must be stripped to List, int must stay int")
		    .containsExactly(GenericType.of(List.class), GenericType.INT);
		assertThat(sig.args().get(1))
		    .as("the non-generic int parameter must pass through unchanged")
		    .isEqualTo(GenericType.INT);
	}
}
