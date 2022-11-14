package com.lapissea.cfs.run;

import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Optional;

import static org.testng.Assert.assertEquals;

public class QueryTests{
	
	static class FF extends IOInstance.Managed<FF>{
		@IOValue
		float a, b;
		
		public FF(){}
		public FF(float a, float b){
			this.a=a;
			this.b=b;
		}
	}
	
	@Test
	void comparison() throws IOException{
		var list=IOList.of(
			new FF(1, 5),
			new FF(2, 4),
			new FF(3, 3),
			new FF(4, 2),
			new FF(5, 1)
		);
		
		assertEquals(Optional.of(new FF(3, 3)), list.query("a > 2").first());
		assertEquals(Optional.of(new FF(4, 2)), list.query("a>=3 && b<=2").first());
	}
	
}
