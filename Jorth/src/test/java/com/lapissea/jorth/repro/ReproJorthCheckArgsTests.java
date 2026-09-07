package com.lapissea.jorth.repro;

import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JORTH-B18: ClassInfo.OfClass.checkArgs early-returns inside the per-argument
 * loop the moment it sees an Object-typed requested argument -> the remaining
 * arguments are never checked -> false positive in overload (constructor)
 * resolution.
 *
 * What the code is supposed to do:
 *   checkArgs (ClassInfo.java:232-255) decides whether a candidate constructor's
 *   declared parameter types (margs, Class<?>[]) match a requested signature's
 *   argument types (args, List<JType>) position by position:
 *     - the arity must be equal (ClassInfo.java:233)
 *     - at each position i: argC = margs[i] (candidate side),
 *       argS = args.get(i).asGeneric() (requested side); a position matches when
 *       the base types agree (ClassInfo.java:243) and, for reference types, argC
 *       is instanceOf argS (ClassInfo.java:244-252)
 *     - it returns true only after EVERY position matched (ClassInfo.java:254)
 *   It is called from ClassInfo.OfClass.getFunction for <init> lookups
 *   (ClassInfo.java:111-118), so a false positive means a constructor that does
 *   NOT match the requested signature is accepted during overload resolution.
 *
 * What it actually does:
 *   Inside the per-argument loop (ClassInfo.java:234-253) there is
 *       if(argS.equals(GenericType.OBJECT)){
 *           return argC == Object.class;      // <- the early return, ClassInfo.java:238-240
 *       }
 *   Because this `return` is INSIDE the loop, the moment the REQUESTED side of a
 *   position is Object the method returns the result of that single comparison
 *   and SKIPS ALL REMAINING ARGUMENTS. If that Object position itself matches
 *   (the candidate parameter is exactly Object.class) the method returns true
 *   even when a LATER position mismatches -> false positive.
 *
 * Why the test fails:
 *   objectArgBeforeMismatchingArgShouldFailCheck mirrors the production call
 *   site (ClassInfo.java:111-118): the candidate constructor is
 *   Candidate(Object, int) and the requested signature is <init>(Object,
 *   String). Position 0 is Object on BOTH sides (a match); position 1
 *   mismatches (requested String vs declared int). The intended result is a
 *   mismatch (false). Against the current code the loop hits the Object branch
 *   at i=0 (ClassInfo.java:238) and returns `argC == Object.class` == true
 *   before position 1 is ever examined, so the assertion fails with
 *   "expected: false but was: true". The controls pass, isolating the failure
 *   to the Object early-return: noObjectArgMismatchDetected shows the loop DOES
 *   detect the same kind of mismatch when no Object-typed position is present
 *   (mismatch found at ClassInfo.java:243), and allArgsMatchReturnsTrue shows a
 *   fully matching list (with an Object position) returns true as intended.
 *
 * Note: this is a masked/edge bug. It only manifests when an Object-typed
 * requested argument precedes a mismatching later argument, and checkArgs is a
 * PRIVATE instance method of ClassInfo.OfClass (ClassInfo.java:232), so this
 * test constructs the public ClassInfo.OfClass (public constructor,
 * ClassInfo.java:85) and invokes checkArgs reflectively with setAccessible.
 */
public class ReproJorthCheckArgsTests{
	
	// The candidate: a constructor with declared parameter types (Object, int) -
	// these are the "expected parameter list" (margs) in checkArgs's terminology.
	private static final class Candidate{
		Candidate(Object first, int second){
		}
	}
	
	private static final Method CHECK_ARGS;
	
	static{
		try{
			// checkArgs is private (ClassInfo.java:232) -> reflection + setAccessible.
			CHECK_ARGS = ClassInfo.OfClass.class.getDeclaredMethod("checkArgs", Class[].class, List.class, Throwable[].class);
			CHECK_ARGS.setAccessible(true);
		}catch(NoSuchMethodException e){
			throw new ExceptionInInitializerError(e);
		}
	}
	
	/**
	 * White-box invocation of the private checkArgs (ClassInfo.java:232-255).
	 * candidateParams = margs (constructor parameter classes), requested = the
	 * requested signature's argument types, exactly as passed from
	 * ClassInfo.OfClass.getFunction (ClassInfo.java:114-115).
	 */
	private static boolean checkArgs(Class<?>[] candidateParams, List<JType> requested) throws Exception{
		// ClassInfo.OfClass has a public constructor (ClassInfo.java:85); the
		// TypeSource is only consulted in the reference-type instanceOf path
		// (ClassInfo.java:247), which these cases do not need to reach.
		var source = TypeSource.of(null, ReproJorthCheckArgsTests.class.getClassLoader());
		var info   = new ClassInfo.OfClass(source, Candidate.class);
		var fail   = new Throwable[1];
		return (boolean)CHECK_ARGS.invoke(info, candidateParams, requested, fail);
	}
	
	@Test
	void objectArgBeforeMismatchingArgShouldFailCheck() throws Exception{
		// Candidate constructor (Object, int) - taken from the actual declared
		// constructor, exactly like ClassInfo.java:114 (ctor.getParameterTypes()).
		var candidateParams = Candidate.class.getDeclaredConstructor(Object.class, int.class).getParameterTypes();
		// Requested signature <init>(Object, String): position 0 is Object on BOTH
		// sides (a match), position 1 mismatches (requested String, declared int).
		var requested = new FunctionInfo.Signature("<init>", List.of(GenericType.OBJECT, GenericType.STRING)).args();
		
		// Intended: checkArgs must return the MISMATCH (false) because position 1
		// does not match - a correct loop checks every position and only returns
		// true after the last one (ClassInfo.java:254).
		// Bug: the loop hits the Object branch at i=0 (ClassInfo.java:238-240) and
		// returns `argC == Object.class` == true, skipping position 1 entirely.
		assertThat(checkArgs(candidateParams, requested))
		    .as("candidate (Object, int) must NOT match requested (Object, String): the mismatch at position 1 (String vs int) must be detected")
		    .isFalse();
	}
	
	@Test
	void noObjectArgMismatchDetected() throws Exception{
		// Control: the SAME kind of mismatch (a later position of a different base
		// type) but WITHOUT any Object-typed requested position, so the buggy
		// early-return (ClassInfo.java:238) is never hit. The loop reaches position
		// 1: BaseType.of("int") == INT != OBJ (String's base type) -> false
		// (ClassInfo.java:243). This must pass, proving the loop DOES detect
		// mismatches when it is not short-circuited by the Object branch.
		assertThat(checkArgs(new Class<?>[]{int.class, int.class}, new FunctionInfo.Signature("<init>", List.of(GenericType.INT, GenericType.STRING)).args()))
		    .as("candidate (int, int) must not match requested (int, String): the loop detects the mismatch when no Object position is present")
		    .isFalse();
	}
	
	@Test
	void allArgsMatchReturnsTrue() throws Exception{
		// Control: a fully matching list, including an Object position: candidate
		// (Object, String) vs requested (Object, String). Intended result: true.
		// (Against the buggy code the Object branch at i=0 also returns true here,
		// so this passes either way - it pins the match result for a list where the
		// early return happens to be correct.)
		assertThat(checkArgs(new Class<?>[]{Object.class, String.class}, new FunctionInfo.Signature("<init>", List.of(GenericType.OBJECT, GenericType.STRING)).args()))
		    .as("candidate (Object, String) must match requested (Object, String)")
		    .isTrue();
	}
}
