package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.FunctionDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JORTH-B09: TypeSource cache key omits array dims -> false ClassInfo reuse ->
 * spurious MalformedJorth.
 *
 * What the code is supposed to do:
 *   TypeSource.OfClassLoader (Jorth/src/main/java/com/lapissea/jorth/lang/type/TypeSource.java)
 *   resolves a GenericType to a ClassInfo and caches the result so repeated lookups are
 *   cheap. The cache key must uniquely identify the type INCLUDING its array dims, so
 *   that e.g. java.lang.Integer (dims=0) and java.lang.Integer[] (dims=1) get DISTINCT
 *   ClassInfo entries (ClassInfo.OfClass vs ClassInfo.OfArray).
 *
 * What it actually does:
 *   The cache key at TypeSource.java:29 is `type.raw().dotted()` - the raw (component)
 *   dotted name WITHOUT the dims. So Integer, Integer[] and Integer[][] ALL map to the
 *   SAME key "java.lang.Integer". The FIRST lookup's ClassInfo is cached (TypeSource.java:33-34)
 *   and then returned for EVERY dim variant (TypeSource.java:30-31). If the first lookup
 *   is the ARRAY type, its ClassInfo.OfArray (TypeSource.java:46) is cached under
 *   "java.lang.Integer" and later handed out for the plain Integer lookup.
 *   ClassInfo.OfArray.getField (Jorth/src/main/java/com/lapissea/jorth/lang/type/ClassInfo.java:36-38)
 *   throws for ANY field name, so `get(Integer.class, "MAX_VALUE")` performed after an
 *   Integer[]-resolving op in the same TypeSource fails with the spurious
 *   MalformedJorth "Array type has no field: MAX_VALUE" - even though java.lang.Integer
 *   clearly has a MAX_VALUE field.
 *
 * Triggering order (IMPORTANT):
 *   The array-resolving op must run FIRST, inside the SAME ClassDefinition - the cache
 *   lives on the ClassDefinition's single shared TypeSource (ClassDefinition.java:46,61),
 *   so two different ClassDefinitions never collide. Here `newObj(Integer[].class)` alone
 *   does NOT touch the TypeSource (NewOp.simulate, Insn.java:582-598, only manipulates the
 *   stack); the op that actually primes the cache is a `.call(...)` whose RECEIVER is
 *   Integer[]: CodeBlock.call (CodeBlock.java:312-322) resolves the receiver via
 *   typeSource.byType(...), which stores the OfArray ClassInfo under "java.lang.Integer".
 *   The reverse order (non-array lookup first, then array) is blocked by the InvokeOp
 *   receiver check (Insn.java:745-751) and does not yield this spurious field error, so
 *   the array-FIRST order is the reliable trigger (verified manually, TsourceProbe
 *   scenario A).
 *
 * Why the tests fail:
 *   arrayThenNonArraySameRawTypeCollides builds ONE ClassDefinition with one method body:
 *   `val(3); newObj(Integer[].class); call("hashCode"); pop(); get(Integer.class, "MAX_VALUE"); return`.
 *   The call("hashCode") step primes the shared TypeSource cache with the OfArray
 *   ClassInfo under key "java.lang.Integer"; the subsequent get(Integer.class, "MAX_VALUE")
 *   then hits that cache entry and the reused OfArray ClassInfo throws
 *   "Array type has no field: MAX_VALUE" during generation, so the test fails with the
 *   spurious MalformedJorth instead of building a class whose method returns
 *   Integer.MAX_VALUE.
 *   nonArrayAloneWorks and arrayAloneWorks are positive controls: each uses its OWN
 *   ClassDefinition (a fresh TypeSource cache) with ONLY the non-array lookup or ONLY the
 *   array op, so no cross-dim collision can happen and both pass against the current code,
 *   proving the harness is sound and isolating the failure to the shared-cache
 *   array-first ordering.
 */
public class ReproJorthTypeSourceDimsTests{
	
	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthTypeSourceDimsTests.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(name.equals(className)){
					return defineClass(name, bytes, 0, bytes.length);
				}
				return super.findClass(name);
			}
		};
		return Class.forName(className, true, loader);
	}
	
	@Test
	void arrayThenNonArraySameRawTypeCollides() throws Exception{
		// BUG TRIGGER: both ops are in ONE ClassDefinition (one shared TypeSource,
		// ClassDefinition.java:46,61), in ONE method body, ARRAY FIRST:
		var cls = generateAndLoad("reprotypesource.Collision", cd -> {
			FunctionDefinition fn = cd.function("maxValue").staticAcc().returns(int.class);
			fn.body()
			  .val(3)                     // push the array size (int)
			  .newObj(Integer[].class)    // new Integer[3] -> pushes Integer[] on the stack.
			                             // NewOp.simulate (Insn.java:582) does NOT touch the TypeSource,
			                             // so the cache is still empty here.
			  .call("hashCode")           // receiver is Integer[] -> CodeBlock.call (CodeBlock.java:316)
			                             // does typeSource.byType(Integer[]) -> cache MISS under key
			                             // "java.lang.Integer" (TypeSource.java:29: raw().dotted(), NO dims)
			                             // -> maybeByType0 stores ClassInfo.OfArray under that same key
			                             // (TypeSource.java:33-34,46). This is the dims-less cache key bug:
			                             // the OfArray entry now sits where the Integer (dims=0) entry belongs.
			                             // ("hashCode" is resolved fine because OfArray delegates
			                             //  function lookups to Object, ClassInfo.java:40-42.)
			  .pop()                      // discard the hashCode result; stack is empty again
			  .get(Integer.class, "MAX_VALUE") // typeSource.byName(Integer) -> key "java.lang.Integer"
			                             // -> cache HIT -> returns the cached OfArray ClassInfo (WRONG:
			                             //    must be a ClassInfo.OfClass for java.lang.Integer)
			                             // -> OfArray.getField (ClassInfo.java:36-38) throws the spurious
			                             //    MalformedJorth "Array type has no field: MAX_VALUE"
			  .returnOp();
		});
		// Intended behavior: the class builds and the method returns Integer.MAX_VALUE.
		// Against the current code generateAndLoad throws the spurious
		// MalformedJorth "Array type has no field: MAX_VALUE" instead.
		assertThat(cls.getMethod("maxValue").invoke(null))
		    .as("get(Integer.class, \"MAX_VALUE\") after an Integer[] op in the same TypeSource must still resolve Integer.MAX_VALUE")
		    .isEqualTo(Integer.MAX_VALUE);
	}
	
	@Test
	void nonArrayAloneWorks() throws Exception{
		// Positive control: a FRESH ClassDefinition (fresh TypeSource cache) with ONLY the
		// non-array lookup. No array op ever primes the cache, so the Integer lookup
		// misses, creates the correct ClassInfo.OfClass(java.lang.Integer) and resolves
		// MAX_VALUE. Must pass against the current code, proving the field lookup itself
		// is fine and the bug needs the prior array op in the same TypeSource.
		var cls = generateAndLoad("reprotypesource.NonArrayAlone", cd -> {
			FunctionDefinition fn = cd.function("maxValue").staticAcc().returns(int.class);
			fn.body()
			  .get(Integer.class, "MAX_VALUE") // typeSource.byName(Integer) -> cache MISS -> OfClass(Integer) -> getField OK
			  .returnOp();
		});
		assertThat(cls.getMethod("maxValue").invoke(null))
		    .as("a plain get(Integer.class, \"MAX_VALUE\") in a fresh TypeSource must return Integer.MAX_VALUE")
		    .isEqualTo(Integer.MAX_VALUE);
	}
	
	@Test
	void arrayAloneWorks() throws Exception{
		// Positive control: a FRESH ClassDefinition (fresh TypeSource cache) with ONLY the
		// array op. Integer[] has a reference component, so NewOp.visit (Insn.java:601-611)
		// emits ANEWARRAY java/lang/Integer - the CORRECT instruction for object arrays -
		// the class loads, verifies and runs. Must pass, proving the array op itself is
		// valid and the collision test failure is not an artifact of an invalid array op.
		var cls = generateAndLoad("reprotypesource.ArrayAlone", cd -> {
			FunctionDefinition fn = cd.function("makeArray").staticAcc().returns(Integer[].class);
			fn.body()
			  .val(3)              // push the array length
			  .newObj(Integer[].class) // new Integer[3] -> ANEWARRAY java/lang/Integer (valid)
			  .returnOp();
		});
		Object result = cls.getMethod("makeArray").invoke(null);
		assertThat(result)
		    .as("new Integer[3] in generated code must load, verify and run")
		    .isInstanceOf(Integer[].class);
		assertThat((Integer[]) result)
		    .as("the created array must have the requested length")
		    .hasSize(3);
	}
}
