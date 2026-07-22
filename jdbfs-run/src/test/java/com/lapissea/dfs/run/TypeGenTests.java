package com.lapissea.dfs.run;

import com.lapissea.dfs.SyntheticParameterizedType;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.List;

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
	
	@IOInstance.StrFormat.Custom("@val @val2")
	@IOInstance.Order({"val", "val2"})
	interface MissingGetterGeneric<T, T2 extends Number> extends IOInstance.Def<MissingGetterGeneric<T, T2>>{
		@IOValue.Generic
		void setVal(T val);
		@IOValue.Generic
		void setVal2(T2 val2);
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
	@Test
	void missingGetterGeneric() throws IOException{
		var data = Cluster.emptyMem();
		
		IOList<MissingGetterGeneric<Long, Integer>> list =
			data.roots().request(0, IOList.class, SyntheticParameterizedType.of(
				MissingGetterGeneric.class,
				List.of(Long.class, Integer.class)
			));
		
		list.add(IOInstance.Def.of(MissingGetterGeneric.class, 123L, 12));
		list.add(IOInstance.Def.of(MissingGetterGeneric.class, 124L, 13));
		list.add(IOInstance.Def.of(MissingGetterGeneric.class, 125L, 14));
		
		var strs = list.mapped(Object::toString).toList();
		
		assertThat(strs).containsExactly("123 12", "124 13", "125 14");
	}
	
}
