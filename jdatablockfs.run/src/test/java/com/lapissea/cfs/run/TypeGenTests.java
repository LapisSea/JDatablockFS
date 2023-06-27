package com.lapissea.cfs.run;

import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TypeGenTests{
	
	interface DefaultImplType extends IOInstance.Def<DefaultImplType>{
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
		var type = DefaultImplType.class;
		assertTrue(IOList.class.isAnnotationPresent(IOValue.OverrideType.DefaultImpl.class));
		var struct = Struct.of(type, Struct.STATE_DONE);
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
			assertEquals(simpleInstance.getNum(), a);
			simpleInstance.setNum(b);
			assertEquals(simpleInstance.getNum(), b);
		}
		{
			var a = "Hello object";
			var b = Double.valueOf(420.69);
			simpleInstance.setDyn(a);
			assertEquals(simpleInstance.getDyn(), a);
			simpleInstance.setDyn(b);
			assertEquals(simpleInstance.getDyn(), b);
		}
	}
	
}
