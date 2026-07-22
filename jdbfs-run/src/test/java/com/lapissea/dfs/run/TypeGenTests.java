package com.lapissea.dfs.run;

import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.lang.invoke.MethodHandles;

import static org.assertj.core.api.Assertions.assertThat;

public class TypeGenTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
	interface DefaultImplType extends IOInstance.Def<DefaultImplType>{
		@IONullability(IONullability.Mode.NULLABLE)
		IOList<Integer> list();
	}
	
	interface SimpleType extends IOInstance.Def<SimpleType>{
		int getNum();
		void setNum(int num);
		
		@IOValue.Generic
		Object getDyn();
		void setDyn(Object a);
	}
	
	@IOInstance.StrFormat.Custom("@num")
	interface MissingGetter extends IOInstance.Def<MissingGetter>{
		void setNum(int num);
	}
	
	@Test
	void overrideType(){
		assertThat(IOList.class).hasAnnotation(IOValue.OverrideType.DefaultImpl.class);
		var struct = Struct.of(DefaultImplType.class, Struct.STATE_DONE);
		struct.emptyConstructor().make();
	}
	
	@Test
	void iTypeFields(){
		Struct<SimpleType> struct = Struct.of(SimpleType.class);
		
		var simpleInstance = struct.emptyConstructor().make();
		{
			var a = 69;
			var b = 420;
			simpleInstance.setNum(a);
			assertThat(simpleInstance).extracting("num").isEqualTo(a);
			simpleInstance.setNum(b);
			assertThat(simpleInstance).extracting("num").isEqualTo(b);
		}
		{
			var a = "Hello object";
			var b = Double.valueOf(420.69);
			simpleInstance.setDyn(a);
			assertThat(simpleInstance).extracting("dyn").isEqualTo(a);
			simpleInstance.setDyn(b);
			assertThat(simpleInstance).extracting("dyn").isEqualTo(b);
		}
	}
	
	@Test
	void missingGetter(){
		Struct<MissingGetter> struct = Struct.of(MissingGetter.class);
		
		var simpleInstance = struct.emptyConstructor().make();
		
		var a = 69;
		var b = 420;
		simpleInstance.setNum(a);
		assertThat(simpleInstance.toString()).isEqualTo(a + "");
		simpleInstance.setNum(b);
		assertThat(simpleInstance.toString()).isEqualTo(b + "");
	}
	
}
