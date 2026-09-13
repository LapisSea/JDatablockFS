package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class LinkedListInitializationTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }

	@IOValue
	public static class Element extends IOInstance.Managed<Element>{
		private int value;
		public Element(){}
		public int value(){ return value; }
		public void setValue(int value){ this.value = value; }
	}

	@DataProvider
	public Object[][] counts(){
		return new Object[][]{{0}, {1}, {5}};
	}

	@Test(dataProvider = "counts")
	public void defaultInitializationUpdatesSize(int count) throws IOException{
		LinkedIOList<Element> list = Cluster.emptyMem().roots().request("list", LinkedIOList.class, Element.class);
		list.addMultipleNew(count);
		assertThat(list.size()).isEqualTo(count);
		assertThat(list.iterator().toList().stream().map(Element::value).toList())
			.containsExactlyElementsOf(Collections.nCopies(count, 0));
	}

	@Test(dataProvider = "counts")
	public void initializedElementsAreDistinctAndAppendedInOrder(int count) throws IOException{
		LinkedIOList<Element> list = Cluster.emptyMem().roots().request("list", LinkedIOList.class, Element.class);
		list.addNew(element -> element.setValue(-1));
		var initialized = Collections.newSetFromMap(new IdentityHashMap<Element, Boolean>());
		var sequence = new AtomicInteger();
		list.addMultipleNew(count, element -> {
			assertThat(initialized.add(element)).as("initializer receives a fresh instance").isTrue();
			assertThat(element.value()).isZero();
			element.setValue(sequence.getAndIncrement());
		});

		var expected = new ArrayList<Integer>();
		expected.add(-1);
		for(int i = 0; i<count; i++) expected.add(i);
		assertThat(sequence.get()).isEqualTo(count);
		assertThat(list.size()).isEqualTo(count + 1);
		assertThat(list.iterator().toList().stream().map(Element::value).toList()).containsExactlyElementsOf(expected);
	}
}

