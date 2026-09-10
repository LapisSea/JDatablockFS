package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
	
	@DataProvider
	Object[][] lookupOrders(){
		return new Object[][]{
			{Integer.class, new int[]{0, 1, 2}}, {Integer.class, new int[]{2, 1, 0}},
			{int.class, new int[]{0, 1, 2}}, {int.class, new int[]{2, 1, 0}}
		};
	}
	
	@Test(dataProvider = "lookupOrders")
	void cacheDistinguishesDimensions(Class<?> component, int[] order) throws MalformedJorth{
		var source   = TypeSource.of(null, getClass().getClassLoader());
		var base     = GenericType.of(component);
		var resolved = new ClassInfo[3];
		for(int dims : order){
			resolved[dims] = source.byType(base.withDims(dims));
			assertThat(resolved[dims]).isInstanceOf(dims == 0? ClassInfo.OfClass.class : ClassInfo.OfArray.class);
		}
		assertThat(resolved[0]).isNotSameAs(resolved[1]);
		assertThat(resolved[1]).isNotSameAs(resolved[2]);
		for(int dims = 0; dims<3; dims++){
			assertThat(source.byType(base.withDims(dims))).isSameAs(resolved[dims]);
		}
	}
	
	@Test
	void arrayLookupDoesNotHideComponentFields() throws Exception{
		var cls = generateAndLoad("test.ArrayAndComponent", cd ->
			                                                    cd.function("maxValue").staticAcc().returns(int.class).body()
			                                                      .val(3).newObj(Integer[].class).call("hashCode").pop()
			                                                      .get(Integer.class, "MAX_VALUE"));
		assertThat(cls.getMethod("maxValue").invoke(null)).isEqualTo(Integer.MAX_VALUE);
	}
}
