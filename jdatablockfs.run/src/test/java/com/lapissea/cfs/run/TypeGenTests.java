package com.lapissea.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.annotations.IOType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class TypeGenTests{
	
	interface SimpleType extends IOInstance.Def<SimpleType>{
		int getNum();
		void setNum(int num);
		
		@IOType.Dynamic
		Object getDyn();
		void setDyn(Object a);
	}
	
	@Test
	void iTypeFields(){
		Struct<SimpleType> struct=Struct.of(SimpleType.class);
		
		var simpleInstance=struct.emptyConstructor().make();
		{
			var a=69;
			var b=420;
			simpleInstance.setNum(a);
			assertEquals(simpleInstance.getNum(), a);
			simpleInstance.setNum(b);
			assertEquals(simpleInstance.getNum(), b);
		}
		{
			var a="Hello object";
			var b=Double.valueOf(420.69);
			simpleInstance.setDyn(a);
			assertEquals(simpleInstance.getDyn(), a);
			simpleInstance.setDyn(b);
			assertEquals(simpleInstance.getDyn(), b);
		}
	}
	
}
