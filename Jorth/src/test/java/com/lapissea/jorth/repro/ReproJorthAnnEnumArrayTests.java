package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * JORTH-B10: enum element inside an annotation array crashes codegen with
 * IllegalArgumentException.
 *
 * What the code is supposed to do:
 *   Attaching an annotation with an array-typed member (e.g. @MyAnn(cs={RED, GREEN}))
 *   must emit each element in the correct form for its Java type: primitives as
 *   constant values, and enum elements as 'e' (enum) element-value entries carrying
 *   the enum descriptor + constant name - exactly what ASM's dedicated
 *   visitEnum(String, String, String) writes for top-level enum members. The class
 *   must build (getClassFile()), load, and the annotation must be readable via
 *   reflection with the element values intact.
 *
 * What it actually does:
 *   AnnotationDefinition.visit(AnnotationVisitor)
 *   (Jorth/src/main/java/com/lapissea/jorth/AnnotationDefinition.java:43-62) handles
 *   a TOP-LEVEL (non-array) enum value correctly via annWriter.visitEnum(...)
 *   (AnnotationDefinition.java:54-55), but for ARRAY values it forwards every element
 *   to ASM verbatim (AnnotationDefinition.java:47-52):
 *     var arrAnn = annWriter.visitArray(argName);
 *     for(int i = 0; i<Array.getLength(argValue); i++){
 *         arrAnn.visit(null, Array.get(argValue, i));
 *     }
 *   ASM's AnnotationWriter.visit(String, Object) (asm 9.9.1, AnnotationWriter.java:190-262)
 *   has branches only for String, Byte, Boolean, Character, Short, Type, byte[],
 *   Integer, Long, Float, Double, Class - and NO branch for Enum. An enum element
 *   therefore falls through to symbolTable.addConstant(value), which only accepts the
 *   constant-pool types (String, Integer, Long, Float, Double, org.objectweb.asm.Type)
 *   and throws IllegalArgumentException("value RED") for an enum instance. So
 *   @MyAnn(cs={RED, GREEN}) crashes getClassFile(), while @MyAnn(c=RED) works.
 *
 * Why the tests fail:
 *   enumArrayAnnotationMemberCrashesCodegen generates a class annotated with
 *   @MyAnn(cs={RED, GREEN}) (enum ARRAY member) and asserts the INTENDED correct
 *   behavior: getClassFile() succeeds, the class loads, and the annotation is
 *   readable via reflection with cs() == [RED, GREEN]. Against the current code the
 *   array-element path throws IllegalArgumentException: value RED during
 *   getClassFile(), so the test fails. topLevelEnumAnnotationMemberWorks shows that
 *   @MyAnn(c=RED) (top-level, NON-array enum) builds and reads back correctly with
 *   the current code - it takes the visitEnum branch
 *   (AnnotationDefinition.java:55) - proving the harness is sound and the bug is
 *   specific to enum elements INSIDE arrays. nonEnumArrayAnnotationMemberWorks shows
 *   that a non-enum array member (int[]) works, proving arrays in general are fine
 *   and only enum elements in arrays are broken.
 */
public class ReproJorthAnnEnumArrayTests{
	
	// Fixture enum: the element type of the annotation's array member.
	public enum Color{
		RED, GREEN
	}
	
	// Fixture annotation: c is a top-level enum member (the CORRECT visitEnum path),
	// cs is an enum ARRAY member (the BUGGY array path), ints is a non-enum array
	// member (control). Defaults let each test set only the member it exercises.
	@Retention(RetentionPolicy.RUNTIME)
	public @interface MyAnn{
		Color  c()    default Color.RED;
		Color[] cs()   default {};
		int[]  ints() default {};
	}
	
	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthAnnEnumArrayTests.class.getClassLoader()){
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
	void enumArrayAnnotationMemberCrashesCodegen() throws Exception{
		// BUG (JORTH-B10): cs is an enum[] member. The array path
		// (AnnotationDefinition.java:47-52) forwards each element verbatim to ASM
		// via arrAnn.visit(null, element) (line 50). ASM's
		// AnnotationWriter.visit(String, Object) has no Enum branch, so the first
		// element (Color.RED) falls through to symbolTable.addConstant and throws
		// IllegalArgumentException("value RED") inside getClassFile().
		assertThatCode(() -> {
			var cls = generateAndLoad("reproannenumarray.EnumArrayAnn", cd -> {
				cd.annotation(MyAnn.class, a -> a.arg("cs", new Color[]{Color.RED, Color.GREEN}));
			});
			// Intended correct behavior: the class builds, loads, and the annotation
			// is readable via reflection with the enum array exactly as written.
			MyAnn ann = cls.getAnnotation(MyAnn.class);
			assertThat(ann)
			    .as("the generated class must carry the runtime-visible @MyAnn annotation")
			    .isNotNull();
			assertThat(ann.cs())
			    .as("the cs() enum-array member must be [RED, GREEN]")
			    .containsExactly(Color.RED, Color.GREEN);
		})
		.as("a class annotated with @MyAnn(cs={RED, GREEN}) must build and read back; "
		  + "the array path (AnnotationDefinition.java:49-51) forwards enum elements to "
		  + "ASM's AnnotationWriter.visit(String,Object), which has no Enum branch and "
		  + "falls through to symbolTable.addConstant -> IllegalArgumentException: value RED")
		.doesNotThrowAnyException();
	}
	
	@Test
	void topLevelEnumAnnotationMemberWorks() throws Exception{
		// Positive control: c is a TOP-LEVEL (non-array) enum member.
		// AnnotationDefinition.visit (AnnotationDefinition.java:54-55) routes it to
		// annWriter.visitEnum(...) - the correct ASM path. This must PASS against the
		// current code, proving the harness is sound and the bug is specific to enum
		// elements INSIDE arrays.
		var cls = generateAndLoad("reproannenumarray.TopLevelEnumAnn", cd -> {
			cd.annotation(MyAnn.class, a -> a.arg("c", Color.RED));
		});
		MyAnn ann = cls.getAnnotation(MyAnn.class);
		assertThat(ann)
		    .as("the generated class must carry the runtime-visible @MyAnn annotation")
		    .isNotNull();
		assertThat(ann.c())
		    .as("the c() top-level enum member must be RED (visitEnum path)")
		    .isEqualTo(Color.RED);
	}
	
	@Test
	void nonEnumArrayAnnotationMemberWorks() throws Exception{
		// Control: ints is a non-enum (int[]) array member. Its elements are
		// Integers, which ASM's AnnotationWriter.visit(String, Object) handles via
		// the Integer branch. This must PASS, proving arrays in general are fine and
		// only ENUM elements inside arrays are broken.
		var cls = generateAndLoad("reproannenumarray.NonEnumArrayAnn", cd -> {
			cd.annotation(MyAnn.class, a -> a.arg("ints", new int[]{1, 2, 3}));
		});
		MyAnn ann = cls.getAnnotation(MyAnn.class);
		assertThat(ann)
		    .as("the generated class must carry the runtime-visible @MyAnn annotation")
		    .isNotNull();
		assertThat(ann.ints())
		    .as("the ints() int-array member must be [1, 2, 3]")
		    .containsExactly(1, 2, 3);
	}
}
