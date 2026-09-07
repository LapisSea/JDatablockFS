package com.lapissea.jorth.repro;

import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.Visibility;
import org.testng.annotations.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JORTH-B17: FunctionInfo.OfConstructor.visibility maps a PUBLIC constructor to
 * Visibility.PRIVATE (inverted).
 *
 * What the code is supposed to do:
 *   OfConstructor.visibility() (FunctionInfo.java:163-171) should mirror
 *   OfMethod.visibility() (FunctionInfo.java:81-89), mapping a member's JVM
 *   access flags to the Visibility enum (Visibility.java:7-11, values
 *   PRIVATE, PROTECTED, PUBLIC) as:
 *       public    -> Visibility.PUBLIC
 *       protected -> Visibility.PROTECTED
 *       otherwise -> Visibility.PRIVATE
 *   (there is no PACKAGE/DEFAULT enum value, so package-private falls through
 *   to PRIVATE, same as the method path). Any code path that inspects a
 *   constructor's visibility therefore expects a public constructor to yield
 *   Visibility.PUBLIC.
 *
 * What it actually does:
 *   The constructor path (FunctionInfo.java:163-171) is
 *       public Visibility visibility(){
 *           if(Modifier.isPublic(ctor.getModifiers())){
 *               return Visibility.PRIVATE;   // <- INVERTED: public -> PRIVATE
 *           }
 *           if(Modifier.isProtected(ctor.getModifiers())){
 *               return Visibility.PROTECTED;
 *           }
 *           return Visibility.PRIVATE;
 *       }
 *   The public branch returns Visibility.PRIVATE instead of Visibility.PUBLIC,
 *   so a public constructor is reported as PRIVATE. The protected and
 *   non-public (private/package-private) branches are correct, so the
 *   inversion only manifests for the public case.
 *
 * Why the bug is masked in practice:
 *   Constructor invocation in generated code uses INVOKESPECIAL and Jorth
 *   performs no access checks on constructor lookup (see B08:
 *   ReproJorthAccessCheckTests), so the (wrong) Visibility value is never
 *   consulted on the main code paths and the generated code still works. The
 *   computed Visibility is simply wrong for anything that inspects it.
 *
 * Why the tests fail:
 *   publicConstructorVisibilityShouldBePublic builds an OfConstructor from the
 *   java.lang.reflect.Constructor of a real PUBLIC nested-class constructor and
 *   asserts the INTENDED Visibility.PUBLIC; the current code returns
 *   Visibility.PRIVATE, so it fails with "expected: PUBLIC but was: PRIVATE".
 *   publicConstructorVisibility_pinsActualPrivate pins the actual (buggy)
 *   behavior with the SAME public constructor (passes today; would fail once
 *   the bug is fixed), and nonPublicConstructorVisibilityCorrect shows the
 *   non-public branches (protected, private) map correctly, isolating the bug
 *   to the public branch at FunctionInfo.java:164-165.
 *
 * Note: FunctionInfo.OfConstructor is a public nested class
 * (FunctionInfo.java:128) with a public constructor taking
 * (TypeSource, Constructor<?>) (FunctionInfo.java:134); the equivalent static
 * factory is FunctionInfo.of(TypeSource, Constructor<?>)
 * (FunctionInfo.java:206-208). Both are directly reachable from this test -
 * no reflection tricks needed. TypeSource.of(null, classLoader)
 * (TypeSource.java:63-65) accepts a null parent (TypeSource.java:25-28).
 */
public class ReproJorthCtorVisibilityTests{
	
	// Real fixture classes whose constructors we inspect via reflection.
	
	public static class PublicCtor{
		public PublicCtor(){
		}
	}
	
	static class ProtectedCtor{
		protected ProtectedCtor(){
		}
	}
	
	static class PrivateCtor{
		private PrivateCtor(){
		}
	}
	
	/**
	 * Build a FunctionInfo.OfConstructor white-box from a real
	 * java.lang.reflect.Constructor, exactly as
	 * FunctionInfo.of(TypeSource, Constructor<?>) (FunctionInfo.java:206-208)
	 * does. TypeSource.of(null, loader) resolves the declaring class via
	 * Class.forName (TypeSource.java:53-56).
	 */
	private static FunctionInfo.OfConstructor ofCtor(Constructor<?> ctor){
		var source = TypeSource.of(null, ctor.getDeclaringClass().getClassLoader());
		return new FunctionInfo.OfConstructor(source, ctor);
	}
	
	@Test
	void publicConstructorVisibilityShouldBePublic() throws NoSuchMethodException{
		// Fixture: a nested class with a PUBLIC no-arg constructor.
		var ctor = PublicCtor.class.getDeclaredConstructor();
		assertThat(Modifier.isPublic(ctor.getModifiers()))
		    .as("precondition: the fixture constructor IS public")
		    .isTrue();
		// Build the OfConstructor from the real reflected constructor.
		var info = ofCtor(ctor);
		// Intended behavior (mirror of OfMethod.visibility, FunctionInfo.java:82-84):
		// a public constructor must map to Visibility.PUBLIC.
		// Bug: FunctionInfo.java:164-165 does
		//     if(Modifier.isPublic(ctor.getModifiers())) return Visibility.PRIVATE;
		// so a public constructor is reported as PRIVATE and this assertion
		// fails with "expected: PUBLIC but was: PRIVATE".
		assertThat(info.visibility())
		    .as("a public constructor must map to Visibility.PUBLIC")
		    .isEqualTo(Visibility.PUBLIC);
	}
	
	@Test
	void publicConstructorVisibility_pinsActualPrivate() throws NoSuchMethodException{
		// Pin: the SAME public constructor, asserting what the buggy code
		// ACTUALLY returns today (FunctionInfo.java:164-165: public ->
		// Visibility.PRIVATE). This passes against the current code, proving
		// the inversion; it would fail once the bug is fixed, at which point
		// the primary test above passes.
		var ctor = PublicCtor.class.getDeclaredConstructor();
		assertThat(Modifier.isPublic(ctor.getModifiers()))
		    .as("precondition: the fixture constructor IS public")
		    .isTrue();
		var info = ofCtor(ctor);
		// Buggy behavior pinned: the public ctor is currently reported as PRIVATE.
		assertThat(info.visibility())
		    .as("current buggy behavior: FunctionInfo.java:164-165 maps public -> PRIVATE")
		    .isEqualTo(Visibility.PRIVATE);
	}
	
	@Test
	void nonPublicConstructorVisibilityCorrect() throws NoSuchMethodException{
		// Control: the NON-public branches of OfConstructor.visibility
		// (FunctionInfo.java:167-170) are correct and must keep passing,
		// proving the harness is sound and isolating the bug to the public
		// branch at FunctionInfo.java:164-165.
		var protectedCtor = ProtectedCtor.class.getDeclaredConstructor();
		assertThat(Modifier.isProtected(protectedCtor.getModifiers()))
		    .as("precondition: the fixture constructor IS protected")
		    .isTrue();
		assertThat(ofCtor(protectedCtor).visibility())
		    .as("a protected constructor must map to Visibility.PROTECTED (branch at FunctionInfo.java:167-168 is correct)")
		    .isEqualTo(Visibility.PROTECTED);
		var privateCtor = PrivateCtor.class.getDeclaredConstructor();
		assertThat(Modifier.isPrivate(privateCtor.getModifiers()))
		    .as("precondition: the fixture constructor IS private")
		    .isTrue();
		assertThat(ofCtor(privateCtor).visibility())
		    .as("a private constructor must map to Visibility.PRIVATE (fallthrough at FunctionInfo.java:170 is correct)")
		    .isEqualTo(Visibility.PRIVATE);
	}
}
