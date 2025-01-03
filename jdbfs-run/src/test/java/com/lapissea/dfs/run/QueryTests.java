package com.lapissea.dfs.run;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.DataProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class QueryTests{
	
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
	
	
}
