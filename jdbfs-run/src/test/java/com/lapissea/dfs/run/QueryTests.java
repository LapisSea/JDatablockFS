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

import static com.lapissea.dfs.query.Query.Test.*;
import static org.assertj.core.api.Assertions.assertThat;

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
	private void fillFF(IOList<FF> list) throws IOException{
		list.addAll(List.of(
			FF.of(1, 5),
			FF.of(2, 4),
			FF.of(3, 3),
			FF.of(4, 2),
			FF.of(5, 1)
		));
	}
	
	@Test(expectedExceptions = IllegalArgumentException.class)
	void badFieldRef(){
		Query.Test.<FF, Float>field(aa -> aa.a() + 1, m -> m>10);
	}
	
	@Test
	void ffByField(){
		field(FF::a, m -> m>10);
	}
	@Test
	void numstrByField(){
		field(NumberedString::num, m -> m == 10);
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
		
		var query = list.where(fieldEQ(NumberedString::num, 10)).mapF(NumberedString::val);
		
		var match = query.first();
		
		assertThat(match).hasValue("HELLO");
	}
	
	@Test(dataProvider = "ffLists")
	void testAFirst(IOList<FF> list) throws IOException{
		fillFF(list);
		
		assertThat(list.where(field(FF::a, a -> a>2)).first()).hasValue(FF.of(3, 3));
	}
	@Test(dataProvider = "ffLists")
	void testAThenMapFirst(IOList<FF> list) throws IOException{
		fillFF(list);
		
		assertThat(list.where(field(FF::a, a -> a>1)).mapF(FF::a).first()).hasValue(2F);
		assertThat(list.where(field(FF::a, a -> a>1)).mapF(FF::a).first()).hasValue(2F);
	}
	
	@Test(dataProvider = "ffLists")
	void testABEqualFirst(IOList<FF> list) throws IOException{
		fillFF(list);
		
		
		assertThat(list.where(fieldEQ(FF::a, 1F)).first()).hasValue(FF.of(1, 5));
		assertThat(list.where(fieldEQ(FF::b, 1F)).first()).hasValue(FF.of(5, 1));
		
		assertThat(list.where(fieldNotEQ(FF::a, 1F)).first()).hasValue(FF.of(2, 4));
	}
	
	@Test(dataProvider = "ffLists")
	void testAThenBFirst(IOList<FF> list) throws IOException{
		fillFF(list);
		
		assertThat(
			list.where(field(FF::a, a -> a>=3))
			    .where(field(FF::b, b -> b<=2))
			    .first()
		).hasValue(FF.of(4, 2));
		
		assertThat(
			list.where(field(FF::a, a -> a>=3),
			           field(FF::b, b -> b<=2))
			    .first()
		).hasValue(FF.of(4, 2));
	}
	
	@Test(dataProvider = "ffLists")
	void testACount(IOList<FF> list) throws IOException{
		fillFF(list);
		
		assertThat(list.where(field(FF::a, a -> a>2)).count()).isEqualTo(3);
	}
	
	@Test(dataProvider = "ffLists")
	void biTestABSameFirst(IOList<FF> list) throws IOException{
		fillFF(list);
		
		assertThat(list.where(fieldMatch(FF::a, FF::b)).first()).hasValue(FF.of(3, 3));
	}
	@Test(dataProvider = "ffLists")
	void biTestABSameCount(IOList<FF> list) throws IOException{
		fillFF(list);
		
		assertThat(list.where(fields(FF::a, FF::b, (a, b) -> a == 2 || b == 3)).count()).isEqualTo(2);
	}
	@Test(dataProvider = "ffLists")
	void evenAToList(IOList<FF> list) throws IOException{
		fillFF(list);
		
		var aEven = list.where(field(FF::a, a -> a%2 == 0));
		assertThat(aEven.allToList()).containsExactly(FF.of(2, 4), FF.of(4, 2));
		assertThat(aEven.mapF(FF::a).allToList()).containsExactly(2F, 4F);
	}
	
	@Test(dataProvider = "ffLists")
	void multishotQuery(IOList<FF> list) throws IOException{
		fillFF(list);
		
		var aEven = list.where(field(FF::a, a -> a%2 == 0));
		assertThat(aEven.first()).hasValue(FF.of(2, 4));
		assertThat(aEven.count()).isEqualTo(2);
	}
	
	@Test(dataProvider = "ffLists")
	void filterFull(IOList<FF> list) throws IOException{
		fillFF(list);
		
		assertThat(list.query().filterFull(ff -> ff.a()%2 == 0).first()).hasValue(FF.of(2, 4));
	}
	
}
