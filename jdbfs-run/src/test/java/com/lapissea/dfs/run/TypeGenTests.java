package com.lapissea.dfs.run;

import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class TypeGenTests{
	@StructPipe.Special
	static class JustAnInt extends IOInstance.Managed<JustAnInt>{
		@IOValue
		int theValue;
	}
	
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
	
	@Test
	void overrideType(){
		assertThat(IOList.class).hasAnnotation(IOValue.OverrideType.DefaultImpl.class);
		var struct = Struct.of(DefaultImplType.class, Struct.STATE_DONE);
		struct.emptyConstructor().make();
	}
	
	@Test
	void justAnInt() throws IOException{
		var val = new JustAnInt();
		val.theValue = 69;
		var struct = StandardStructPipe.of(JustAnInt.class, StagedInit.STATE_DONE);
		TestUtils.checkPipeInOutEquality(struct, val);
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
	
}
