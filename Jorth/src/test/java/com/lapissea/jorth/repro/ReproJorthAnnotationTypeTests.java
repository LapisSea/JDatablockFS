package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.util.function.UnsafeConsumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.testng.annotations.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for generated annotation types and their reflective use.
 */
public class ReproJorthAnnotationTypeTests{
	
	private record Generated(Class<?> cls, byte[] bytes){ }
	
	private static Generated generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		return generateAndLoad(className, ReproJorthAnnotationTypeTests.class.getClassLoader(), generator);
	}
	private static Generated generateAndLoad(String className, ClassLoader parent,
	                                         UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		var cd = new ClassDefinition(parent);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(parent){
			@Override
			protected Class<?> findClass(String cn) throws ClassNotFoundException{
				if(cn.equals(className)) return defineClass(cn, bytes, 0, bytes.length);
				return super.findClass(cn);
			}
		};
		return new Generated(loader.loadClass(className), bytes);
	}
	
	@Test
	void annotationClassMustHaveAccAnnotationFlag() throws Exception{
		var gen = generateAndLoad("reproannotationtype.Marker", cd -> {
			cd.type(ClassType.ANNOTATION);
			cd.function("value").returns(String.class);
		});
		int flags = Opcodes.ACC_ANNOTATION|Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT;
		assertThat(new ClassReader(gen.bytes()).getAccess()&flags).isEqualTo(flags);
		assertThat(new ClassReader(gen.bytes()).getInterfaces()).containsExactly("java/lang/annotation/Annotation");
		assertThat(gen.cls().isAnnotation()).isTrue();
		assertThat(gen.cls().isInterface()).isTrue();
		assertThat(Annotation.class.isAssignableFrom(gen.cls())).isTrue();
	}
	
	@Test
	void generatedAnnotationCanBeReadBack() throws Exception{
		var gen = generateAndLoad("reproannotationtype.RuntimeAnnotation", cd -> {
			cd.type(ClassType.ANNOTATION);
			cd.annotation(Retention.class, a -> a.arg("value", RetentionPolicy.RUNTIME));
			cd.function("value").returns(String.class);
		});
		var annotationType = gen.cls().asSubclass(Annotation.class);
		var target = generateAndLoad("reproannotationtype.AnnotatedClass", annotationType.getClassLoader(), cd ->
			                                                                                                    cd.annotation(annotationType, a -> a.arg("value", "hello"))
		);
		var annotation = target.cls().getAnnotation(annotationType);
		assertThat(annotation).isNotNull();
		assertThat(annotation.annotationType()).isEqualTo(annotationType);
		assertThat(annotationType.getMethod("value").invoke(annotation)).isEqualTo("hello");
	}
	
	@Test
	void interfaceClassIsNotAnnotation() throws Exception{
		var gen = generateAndLoad("reproannotationtype.PlainInterface", cd -> {
			cd.type(ClassType.INTERFACE);
			cd.function("hello").returns(String.class);
		});
		assertThat(gen.cls().isInterface()).isTrue();
		assertThat(gen.cls().isAnnotation()).isFalse();
		assertThat(new ClassReader(gen.bytes()).getAccess()&Opcodes.ACC_ANNOTATION).isZero();
		assertThat(Annotation.class.isAssignableFrom(gen.cls())).isFalse();
	}
	
	@Test
	void plainClassIsNotAnnotation() throws Exception{
		var gen = generateAndLoad("reproannotationtype.PlainClass", cd -> { });
		assertThat(gen.cls().isInterface()).isFalse();
		assertThat(gen.cls().isAnnotation()).isFalse();
		assertThat(new ClassReader(gen.bytes()).getAccess()&Opcodes.ACC_ANNOTATION).isZero();
	}
}
