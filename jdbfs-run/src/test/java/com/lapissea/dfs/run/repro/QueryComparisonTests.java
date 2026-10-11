package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static com.lapissea.dfs.query.Query.Test.fieldLe;
import static com.lapissea.dfs.query.Query.Test.fieldLeEq;
import static org.assertj.core.api.Assertions.assertThat;

public class QueryComparisonTests{

	@IOValue
	static class Element extends IOInstance.Managed<Element>{
		private final int number;
		public Element(int number){ this.number = number; }
		public int number(){ return number; }
	}

	@DataProvider
	public Object[][] thresholds(){
		return new Object[][]{{0}, {1}, {2}, {4}, {5}, {6}};
	}

	@Test(dataProvider = "thresholds")
	public void lessThanComparisonsRespectEquality(int threshold) throws IOException{
		var numbers = List.of(1, 2, 2, 3, 5);
		IOList<Element> list = Cluster.emptyMem().roots().request(1, IOList.class, Element.class);
		list.addAll(numbers.stream().map(Element::new).toList());

		var strict = list.where(fieldLe(Element::number, threshold)).mapF(Element::number).allToList();
		var inclusive = list.where(fieldLeEq(Element::number, threshold)).mapF(Element::number).allToList();
		assertThat(strict).containsExactlyElementsOf(numbers.stream().filter(value -> value<threshold).toList());
		assertThat(inclusive).containsExactlyElementsOf(numbers.stream().filter(value -> value<=threshold).toList());
	}
}

