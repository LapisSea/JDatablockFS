package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthIncrementNarrowTests{
	
	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthIncrementNarrowTests.class.getClassLoader()){
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
	Object[][] narrowTypes(){
		return new Object[][]{
			{byte.class, (byte)100},
			{short.class, (short)100},
			{char.class, (char)100}
		};
	}
	
	@Test(dataProvider = "narrowTypes")
	void incrementProducesInt(Class<?> type, Object value) throws Exception{
		var cls = generateAndLoad("test.IncrementResult", cd -> {
			cd.function("direct").staticAcc().arg(type, "value").returns(int.class)
			  .body().get("value").add(200).returnOp();
			cd.function("local").staticAcc().arg(type, "value").returns(int.class)
			  .body().var(int.class, "result").get("value").add(200).set("result").get("result");
			cd.function("zero").staticAcc().arg(type, "value").returns(int.class)
			  .body().get("value").add(0);
		});
		assertThat(cls.getMethod("direct", type).invoke(null, value)).isEqualTo(300);
		assertThat(cls.getMethod("local", type).invoke(null, value)).isEqualTo(300);
		assertThat(cls.getMethod("zero", type).invoke(null, value)).isEqualTo(100);
	}
	
	@Test(dataProvider = "narrowTypes", expectedExceptions = MalformedJorth.class)
	void incrementRequiresCastToNarrowLocal(Class<?> type, Object value) throws Exception{
		generateAndLoad("test.IncrementNarrowStore", cd ->
			                                             cd.function("run").staticAcc().arg(type, "value").body()
			                                               .get("value").add(200).set("value"));
	}
	
	@Test(dataProvider = "narrowTypes")
	void explicitCastAllowsNarrowStore(Class<?> type, Object value) throws Exception{
		var cls = generateAndLoad("test.IncrementCast", cd ->
			                                                cd.function("run").staticAcc().arg(type, "value").returns(type).body()
			                                                  .get("value").add(200).cast(type).set("value").get("value"));
		Object expected;
		if(type == byte.class) expected = (byte)300;
		else if(type == short.class) expected = (short)300;
		else expected = (char)300;
		assertThat(cls.getMethod("run", type).invoke(null, value)).isEqualTo(expected);
	}
}
