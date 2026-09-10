package com.lapissea.jorth.repro;

import com.lapissea.jorth.AccessSet;
import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthFinalTests{
	
	@DataProvider
	Object[][] classAccess(){
		return new Object[][]{{AccessSet.DEFAULT}, {AccessSet.FINAL}, {AccessSet.ABSTRACT}};
	}
	
	@Test(dataProvider = "classAccess")
	void emittedModifiersMatchClassAccess(AccessSet access) throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reprofinal.Base"));
		cd.access(access);
		var cls = loadSingleClass("reprofinal.Base", cd.getClassFile());
		assertThat(Modifier.isFinal(cls.getModifiers())).isEqualTo(access.isFinal());
		assertThat(Modifier.isAbstract(cls.getModifiers())).isEqualTo(access.isAbstract());
		assertThat(cls.isSealed()).isFalse();
	}
	
	@Test
	void finalAccEmitsFinalClass() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted("reprofinal.Final"));
		cd.finalAcc();
		assertThat(Modifier.isFinal(loadSingleClass("reprofinal.Final", cd.getClassFile()).getModifiers())).isTrue();
	}
	
	@Test
	void generatedBaseClassCanBeExtended() throws Exception{
		var loader = newLoader(Set.of("reprofinal.HierBase", "reprofinal.HierChild"), (name, cd) -> {
			if(name.equals("reprofinal.HierChild")){
				cd.extendsType(ClassName.dotted("reprofinal.HierBase"));
			}
		});
		var base  = Class.forName("reprofinal.HierBase", true, loader);
		var child = Class.forName("reprofinal.HierChild", true, loader);
		assertThat(child.getSuperclass()).isSameAs(base);
		assertThat(child.getConstructor().newInstance()).isInstanceOf(base);
	}
	
	private static Class<?> loadSingleClass(String name, byte[] bytes) throws Exception{
		var loader = new ClassLoader(ReproJorthFinalTests.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String cn) throws ClassNotFoundException{
				if(cn.equals(name)){
					return defineClass(cn, bytes, 0, bytes.length);
				}
				return super.findClass(cn);
			}
		};
		return loader.loadClass(name);
	}
	
	private static ClassLoader newLoader(Set<String> names, BiConsumer<String, ClassDefinition> generator){
		return new ClassLoader(ReproJorthFinalTests.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(names.contains(name)){
					byte[] bytes;
					try{
						var cd = new ClassDefinition(this);
						cd.name(ClassName.dotted(name));
						generator.accept(name, cd);
						bytes = cd.getClassFile();
					}catch(Throwable e){
						throw new RuntimeException(e);
					}
					return defineClass(name, bytes, 0, bytes.length);
				}
				return super.findClass(name);
			}
		};
	}
}
