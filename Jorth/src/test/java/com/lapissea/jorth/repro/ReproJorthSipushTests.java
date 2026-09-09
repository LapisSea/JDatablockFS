package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthSipushTests{

	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthSipushTests.class.getClassLoader()){
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
	Object[][] constants(){
		return new Object[][]{
			{Integer.MIN_VALUE}, {-70000}, {-32769}, {-32768}, {-129}, {-128}, {-2}, {-1},
			{0}, {1}, {2}, {3}, {4}, {5}, {6}, {127}, {128}, {32767}, {32768},
			{40000}, {70000}, {Integer.MAX_VALUE}
		};
	}

	@Test(dataProvider = "constants")
	void integerConstantsRoundTrip(int value) throws Exception{
		var cls = generateAndLoad("test.IntegerConstant", cd ->
			cd.function("value").staticAcc().returns(int.class).body().val(value));
		assertThat(cls.getMethod("value").invoke(null)).isEqualTo(value);
	}

	@Test(dataProvider = "constants")
	void incrementsPreserveConstant(int value) throws Exception{
		var cls = generateAndLoad("test.IntegerIncrement", cd ->
			cd.function("add").staticAcc().arg(int.class, "x").returns(int.class)
			  .body().get("x").add(value));
		assertThat(cls.getMethod("add", int.class).invoke(null, 7)).isEqualTo(7 + value);
	}
}
