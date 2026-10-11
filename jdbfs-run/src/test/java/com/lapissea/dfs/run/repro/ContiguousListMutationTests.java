package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.CollectionInfo;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ContiguousListMutationTests{

	@IOValue
	public static class Element extends IOInstance.Managed<Element>{
		private final int value;
		public Element(int value){ this.value = value; }
		public int value(){ return value; }
	}

	@DataProvider
	public Object[][] batches(){
		return new Object[][]{
			{List.of(1, 2, 3, 4, 5, 6, 300)},
			{List.of(300, 1, 2, 3)},
			{List.of(1, 2, 300, 301, 70_000)},
			{List.of(1, 1, 2, 2, 300, 300)},
			{List.of(0, 0, 0)},
			{List.of()}
		};
	}

	@Test(dataProvider = "batches")
	public void bulkAppendPreservesOrderAndMultiplicity(List<Integer> values) throws IOException{
		ContiguousIOList<Element> list = Cluster.emptyMem().roots().request("list", ContiguousIOList.class, Element.class);
		// Values crossing storage-width boundaries exercise resizing within a buffered batch.
		list.addAll(values.stream().map(Element::new).toList());
		assertValues(list, values);
		list.addAll(List.of(new Element(7), new Element(8)));
		var expected = new ArrayList<>(values);
		expected.addAll(List.of(7, 8));
		assertValues(list, expected);
	}

	private static void assertValues(ContiguousIOList<Element> list, List<Integer> expected) throws IOException{
		assertThat(list.size()).isEqualTo(expected.size());
		assertThat(list.iterator().toList().stream().map(Element::value).toList()).containsExactlyElementsOf(expected);
	}

	@DataProvider
	public Object[][] zeroSizeIndices(){
		return new Object[][]{{0}, {1}, {2}};
	}

	@Test(dataProvider = "zeroSizeIndices")
	public void indexedMutationsSupportZeroByteElements(int index) throws IOException{
		ContiguousIOList<CollectionInfo.NullValue> list = Cluster.emptyMem().roots()
			.request("list", ContiguousIOList.class, CollectionInfo.NullValue.class);
		assertThat(StandardStructPipe.sizeOfUnknown(list.getDataProvider(), new CollectionInfo.NullValue(), WordSpace.BYTE)).isZero();
		list.addAll(List.of(new CollectionInfo.NullValue(), new CollectionInfo.NullValue(), new CollectionInfo.NullValue()));

		list.add(index, new CollectionInfo.NullValue());
		assertThat(list.size()).isEqualTo(4);
		assertThat(list.iterator().toList()).hasSize(4).doesNotContainNull();
		list.remove(index);
		assertThat(list.size()).isEqualTo(3);
		assertThat(list.iterator().toList()).hasSize(3).doesNotContainNull();
		while(!list.isEmpty()){
			list.remove(0);
		}
		assertThat(list.size()).isZero();
		assertThat(list.iterator().hasNext()).isFalse();
	}
}

