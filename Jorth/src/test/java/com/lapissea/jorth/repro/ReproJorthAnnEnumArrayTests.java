package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for enum annotation values, including array elements.
 */
public class ReproJorthAnnEnumArrayTests{
	
	public enum Color{
		RED, GREEN{
			@Override
			public String toString(){ return "green"; }
		}
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	public @interface MyAnn{
		Color c() default Color.RED;
		Color[] cs() default {Color.RED};
		int[] ints() default {};
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
	void enumArrayAnnotationMemberWorks() throws Exception{
		var cls = generateAndLoad("reproannenumarray.EnumArrayAnn", cd ->
			                                                            cd.annotation(MyAnn.class, a -> a.arg("cs", new Color[]{Color.RED, Color.GREEN, Color.RED}))
		);
		assertThat(cls.getAnnotation(MyAnn.class).cs()).containsExactly(Color.RED, Color.GREEN, Color.RED);
	}
	
	@Test
	void emptyEnumArrayOverridesDefault() throws Exception{
		var cls = generateAndLoad("reproannenumarray.EmptyEnumArrayAnn", cd ->
			                                                                 cd.annotation(MyAnn.class, a -> a.arg("cs", new Color[0]))
		);
		assertThat(cls.getAnnotation(MyAnn.class).cs()).isEmpty();
	}
	
	@Test
	void topLevelEnumAnnotationMemberWorks() throws Exception{
		var cls = generateAndLoad("reproannenumarray.TopLevelEnumAnn", cd -> {
			cd.annotation(MyAnn.class, a -> a.arg("c", Color.GREEN));
		});
		MyAnn ann = cls.getAnnotation(MyAnn.class);
		assertThat(ann)
			.as("the generated class must carry the runtime-visible @MyAnn annotation")
			.isNotNull();
		assertThat(ann.c())
			.as("the c() top-level enum member must be GREEN (constant with a class body)")
			.isEqualTo(Color.GREEN);
	}
	
	@Test
	void nonEnumArrayAnnotationMemberWorks() throws Exception{
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
