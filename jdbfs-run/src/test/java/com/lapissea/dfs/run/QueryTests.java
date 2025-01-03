package com.lapissea.dfs.run;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.query.Query;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class QueryTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
	
	@IOInstance.Order({"a", "b", "someData"})
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
	
	@IOValue
	static class NumberedString extends IOInstance.Managed<NumberedString>{
		private final int    num;
		private final String val;
		public NumberedString(int num, String val){
			this.num = num;
			this.val = val;
		}
		public int num()   { return num; }
		public String val(){ return val; }
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
	
	@Test(expectedExceptions = IllegalArgumentException.class)
	void badFieldRef(){
		Query.<FF, Float>byField(aa -> aa.a() + 1, m -> m>10);
	}
	
	@Test
	void ffByField(){
		Query.byField(FF::a, m -> m>10);
	}
	@Test
	void numstrByField(){
		Query.byField(NumberedString::num, m -> m == 10);
	}
	
	@Test
	void findNumStrByA() throws IOException{
		
		IOList<NumberedString> list = Cluster.emptyMem().roots().request(1, IOList.class, NumberedString.class);
		list.addAll(List.of(
			new NumberedString(1, "hello"),
			new NumberedString(2, "world"),
			new NumberedString(10, "HELLO"),
			new NumberedString(11, "WORLD")
		));
		
		var query = Query.byFieldEq(NumberedString::num, 10).map(NumberedString::val);
		
		var match = query.findFirst(list);
		
		assertThat(match).hasValue("HELLO");
	}
	
	@Test(dataProvider = "ffLists")
	void comparison(IOList<FF> list) throws IOException{
		fillFF(list);
		
		
		assertThat(Query.byField(FF::a, a -> a>1).map(FF::a).findFirst(list)).hasValue(2F);
		
		assertThat(Query.byFieldEq(FF::a, 1F).findFirst(list)).hasValue(FF.of(1, 5));
		assertThat(Query.byFieldEq(FF::b, 1F).findFirst(list)).hasValue(FF.of(5, 1));
		
		assertThat(Query.byFieldNEq(FF::a, 1F).findFirst(list)).hasValue(FF.of(2, 4));
		
		assertThat(Query.byField(FF::a, a -> a>2).findFirst(list)).hasValue(FF.of(3, 3));

//		assertThat(list.query("a >= 3 && b <= 2").first()).hasValue(FF.of(4, 2));
//		assertThat(list.query("a >= 3").filter("b <= 2").first()).hasValue(FF.of(4, 2));
//
//		for(long i = 0; i<list.size(); i++){
//			assertThat(list.query("a == {}", i + 1).first()).hasValue(list.get(i));
//			assertThat(list.query("a == {}+1", i).first()).hasValue(list.get(i));
//		}
//
//		assertThat(list.query("a > 2").count()).isEqualTo(3);
//		assertThat(list.query("a == 2 || a == 3").count()).isEqualTo(2);
//
//		assertThat(list.query("a == b").any()).hasValue(FF.of(3, 3));
//
//		assertThat(list.query("a%2==0").first()).hasValue(FF.of(2, 4));
//		assertThat(list.query("a%2==0").count()).isEqualTo(2);
//		assertThat(list.query("a%2!=0").count()).isEqualTo(3);
//
//		assertThat(list.query("a%2==0").all().toList()).isEqualTo(List.of(FF.of(2, 4), FF.of(4, 2)));
//
//		assertThat(list.query("a%2==0").<List<Float>>map("a").all().toList()).isEqualTo(List.of(2F, 4F));
	}
//
//	@Test(dataProvider = "stringyLists")
//	void inTest(IOList<StringyBoi> list) throws IOException{
//		fillStringy(list);
//		assertThat(list.query("str in strs").count()).isEqualTo(1);
//		assertThat(list.query("'mama' in str").count()).isEqualTo(2);
//		assertThat(list.query("str is null").any()).isEmpty();
//	}

}
