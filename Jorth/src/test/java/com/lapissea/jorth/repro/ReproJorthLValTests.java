package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthLValTests{
	
	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthLValTests.class.getClassLoader()){
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
			{0L}, {1L}, {-1L}, {2L}, {1L<<32}, {(1L<<32) + 1},
			{-(1L<<32)}, {-(1L<<32) + 1}, {1234567890123L},
			{Long.MIN_VALUE}, {Long.MIN_VALUE + 1}, {Long.MAX_VALUE}
		};
	}
	
	@Test(dataProvider = "constants")
	void longConstantsRoundTrip(long value) throws Exception{
		var cls = generateAndLoad("test.LongConstant", cd -> {
			cd.function("value").staticAcc().returns(long.class).body().val(value);
			cd.function("increment").staticAcc().returns(long.class).body().val(value).add(7);
		});
		assertThat(cls.getMethod("value").invoke(null)).isEqualTo(value);
		assertThat(cls.getMethod("increment").invoke(null)).isEqualTo(value + 7);
	}
}
