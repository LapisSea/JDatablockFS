package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Array;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthPrimArrayTests{

	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthPrimArrayTests.class.getClassLoader()){
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
	Object[][] arrays(){
		var cases = new ArrayList<Object[]>();
		for(var type : new Class<?>[]{boolean[].class, byte[].class, short[].class, char[].class,
		                            int[].class, long[].class, float[].class, double[].class, String[].class}){
			for(int length : new int[]{0, 4}) cases.add(new Object[]{type, length});
		}
		return cases.toArray(Object[][]::new);
	}

	@Test(dataProvider = "arrays")
	void createsArrayWithCorrectTypeAndLength(Class<?> type, int length) throws Exception{
		var cls = generateAndLoad("test.ArrayAllocation", cd ->
			cd.function("make").staticAcc().returns(type).body().val(length).newObj(type));
		var result = cls.getMethod("make").invoke(null);
		assertThat(result.getClass()).isEqualTo(type);
		assertThat(Array.getLength(result)).isEqualTo(length);
		var expected = Array.newInstance(type.getComponentType(), length);
		for(int i = 0; i<length; i++){
			assertThat(Array.get(result, i)).isEqualTo(Array.get(expected, i));
		}
	}
}
