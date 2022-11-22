package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
	
	static class StringyBoi extends IOInstance.Managed<StringyBoi>{
		@IOValue
		String str;
		
		@IOValue
		List<String> strs;
		
		public StringyBoi(){}
		public StringyBoi(String str, List<String> strs){
			this.str=str;
			this.strs=strs;
		}
	}
	
	@org.testng.annotations.DataProvider
	Object[][] fflists() throws IOException{
		var cl=Cluster.init(new MemoryData.Builder().build());
		return new Object[][]{
			{IOList.wrap(new ArrayList<>())},
			{cl.getRootProvider().request("arr", ContiguousIOList.class, FF.class)},
			{cl.getRootProvider().request("lin", LinkedIOList.class, FF.class)},
			};
	}
	
	@Test(dataProvider="fflists")
	void comparison(IOList<FF> list) throws IOException{
		list.addAll(List.of(
			new FF(1, 5),
			new FF(2, 4),
			new FF(3, 3),
			new FF(4, 2),
			new FF(5, 1)
		));
		
		assertEquals(Optional.of(new FF(1, 5)), list.query("a is 1").first());
		assertEquals(Optional.of(new FF(5, 1)), list.query("b is 1").first());
		assertEquals(Optional.of(new FF(2, 4)), list.query("a is not 1").first());
		
		assertEquals(Optional.of(new FF(3, 3)), list.query("a > 2").first());
		assertEquals(Optional.of(new FF(4, 2)), list.query("a >= 3 && b <= 2").first());
		
		for(long i=0;i<list.size();i++){
			assertEquals(Optional.of(list.get(i)), list.query("a == {}", i+1).first());
			assertEquals(Optional.of(list.get(i)), list.query("a == {}+1", i).first());
		}
		
		assertEquals(3, list.query("a > 2").count());
		assertEquals(2, list.query("a == 2 || a == 3").count());
		
		assertEquals(Optional.of(new FF(3, 3)), list.query("a == b").any());
		
		assertEquals(Optional.of(new FF(2, 4)), list.query("a%2==0").first());
		assertEquals(2, list.query("a%2==0").count());
		assertEquals(3, list.query("a%2!=0").count());
	}
	
	@Test
	void inTest() throws IOException{
		var list=IOList.of(
			new StringyBoi("Jomama", List.of("foo", "bar")),
			new StringyBoi("bar", List.of("foo", "bar")),
			new StringyBoi("420", List.of()),
			new StringyBoi("mamamia", List.of("69"))
		);
		
		assertEquals(1, list.query("str in strs").count());
		assertEquals(2, list.query("'mama' in str").count());
	}
	
}
