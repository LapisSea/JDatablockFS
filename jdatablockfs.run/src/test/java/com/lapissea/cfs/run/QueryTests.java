package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.OptionalPP;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.testng.Assert.assertEquals;

public class QueryTests{
	
	@IOInstance.Def.Order({"a", "b", "someData"})
	interface FF extends IOInstance.Def<FF>{
		static FF of(float a, float b){
			return IOInstance.Def.of(FF.class, a, b, new byte[69]);
		}
		
		float a();
		float b();
		
		byte[] someData();
	}
	
	@IOValue
	static class StringyBoi extends IOInstance.Managed<StringyBoi>{
		String       str;
		List<String> strs;
		byte[]       someData;
		
		public StringyBoi(){ }
		public StringyBoi(String str, List<String> strs){
			this.str = str;
			this.strs = strs;
			someData = new byte[69];
		}
	}
	
	<T extends IOInstance<T>> Object[][] lists(Class<T> el) throws IOException{
		var cl = Cluster.init(MemoryData.empty());
		return new Object[][]{
			{IOList.wrap(new ArrayList<>())},
			{cl.roots().request("arr", ContiguousIOList.class, el)},
			{cl.roots().request("lin", LinkedIOList.class, el)},
			};
	}
	
	@DataProvider
	Object[][] ffLists() throws IOException{
		return lists(FF.class);
	}
	@DataProvider
	Object[][] stringyLists() throws IOException{
		return lists(StringyBoi.class);
	}
	private void fillFF(IOList<FF> list) throws IOException{
		list.addAll(List.of(
			FF.of(1, 5),
			FF.of(2, 4),
			FF.of(3, 3),
			FF.of(4, 2),
			FF.of(5, 1)
		));
	}
	private void fillStringy(IOList<StringyBoi> list) throws IOException{
		list.addAll(List.of(
			new StringyBoi("Jomama", List.of("foo", "bar")),
			new StringyBoi("bar", List.of("foo", "bar")),
			new StringyBoi("420", List.of()),
			new StringyBoi("mamamia", List.of("69"))
		));
	}
	
	@Test(dataProvider = "ffLists", expectedExceptions = UnsupportedOperationException.class)
	void invalidAccess(IOList<FF> list) throws IOException{
		fillFF(list);
		if(IOList.isWrapped(list)){
			//Ignore memory impl
			throw new UnsupportedOperationException();
		}
		var badQuery = list.query(Set.of("b"), el -> el.a()>1);
		badQuery.first();
	}
	
	@Test(dataProvider = "ffLists")
	void comparison(IOList<FF> list) throws IOException{
		fillFF(list);
		
		assertEquals(OptionalPP.of(2F), list.query(Set.of("a"), el -> el.a()>1).first().map(FF::a));
		
		assertEquals(OptionalPP.of(FF.of(1, 5)), list.query("a is 1").first());
		assertEquals(OptionalPP.of(FF.of(5, 1)), list.query("b is 1").first());
		assertEquals(OptionalPP.of(FF.of(2, 4)), list.query("a is not 1").first());
		
		assertEquals(OptionalPP.of(FF.of(3, 3)), list.query("a > 2").first());
		assertEquals(OptionalPP.of(FF.of(4, 2)), list.query("a >= 3 && b <= 2").first());
		assertEquals(OptionalPP.of(FF.of(4, 2)), list.query("a >= 3").filter("b <= 2").first());
		
		for(long i = 0; i<list.size(); i++){
			assertEquals(OptionalPP.of(list.get(i)), list.query("a == {}", i + 1).first());
			assertEquals(OptionalPP.of(list.get(i)), list.query("a == {}+1", i).first());
		}
		
		assertEquals(3, list.query("a > 2").count());
		assertEquals(2, list.query("a == 2 || a == 3").count());
		
		assertEquals(OptionalPP.of(FF.of(3, 3)), list.query("a == b").any());
		
		assertEquals(OptionalPP.of(FF.of(2, 4)), list.query("a%2==0").first());
		assertEquals(2, list.query("a%2==0").count());
		assertEquals(3, list.query("a%2!=0").count());
		
		assertEquals(List.of(FF.of(2, 4), FF.of(4, 2)), list.query("a%2==0").all().toList());
		
		assertEquals(List.of(2F, 4F), list.query("a%2==0").<List<Float>>map("a").all().toList());
	}
	
	@Test(dataProvider = "stringyLists")
	void inTest(IOList<StringyBoi> list) throws IOException{
		fillStringy(list);
		assertEquals(1, list.query("str in strs").count());
		assertEquals(2, list.query("'mama' in str").count());
		assertEquals(OptionalPP.empty(), list.query("str is null").any());
	}
	
}
