package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthNullPrimitiveTests{

	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthNullPrimitiveTests.class.getClassLoader()){
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
	
	@DataProvider
	Object[][] invalidNullTypes(){
		return new Object[][]{
			{boolean.class}, {byte.class}, {short.class}, {char.class}, {int.class},
			{long.class}, {float.class}, {double.class}, {void.class}
		};
	}

	@Test(dataProvider = "invalidNullTypes", expectedExceptions = MalformedJorth.class,
	      expectedExceptionsMessageRegExp = "For null constant, the type must be object but is: .*")
	void nullRejectsPrimitiveAndVoidTypes(Class<?> type) throws MalformedJorth{
		var cd = new ClassDefinition(null).name(ClassName.dotted("test.InvalidNull"));
		cd.function("run").staticAcc().body().nullVal(type);
	}

	@DataProvider
	Object[][] referenceTypes(){
		return new Object[][]{
			{Object.class}, {String.class}, {Runnable.class}, {int[].class},
			{boolean[].class}, {String[].class}, {int[][].class}
		};
	}

	@Test(dataProvider = "referenceTypes")
	void nullReferenceLoadsAndReturns(Class<?> type) throws Exception{
		var cls = generateAndLoad("test.ReferenceNull", cd ->
			cd.function("value").staticAcc().returns(type).body().nullVal(type));
		assertThat(cls.getMethod("value").invoke(null)).isNull();
	}
}
