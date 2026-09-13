package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.IOList;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ListViewTests{
	@DataProvider
	public Object[][] backingTypes(){
		return new Object[][]{{false}, {true}};
	}

	private static IOList<Integer> backing(boolean persistent) throws IOException{
		IOList<Integer> list = persistent?
			Cluster.emptyMem().roots().request("list", ContiguousIOList.class, Integer.class) :
			IOList.wrap(new ArrayList<Integer>());
		list.addAll(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9));
		return list;
	}

	@Test(dataProvider = "backingTypes")
	public void rangeTraversalIsBounded(boolean persistent) throws IOException{
		var view = backing(persistent).subListView(2, 7);
		assertThat(view.size()).isEqualTo(5);
		assertThat(view.iterator().toList()).containsExactly(2, 3, 4, 5, 6);

		var traversed = new ArrayList<Integer>();
		view.spliterator().forEachRemaining(traversed::add);
		assertThat(traversed).containsExactly(2, 3, 4, 5, 6);
	}

	@Test(dataProvider = "backingTypes")
	public void rangeIndicesAreLocal(boolean persistent) throws IOException{
		var data = backing(persistent);
		var view = data.subListView(2, 7);
		for(long index : new long[]{-1, 5, 6}){
			assertThatThrownBy(() -> view.get(index)).isInstanceOf(IndexOutOfBoundsException.class);
			assertThatThrownBy(() -> view.set(index, 100)).isInstanceOf(IndexOutOfBoundsException.class);
		}
		for(long index : new long[]{-1, 6}){
			assertThatThrownBy(() -> view.add(index, 100)).isInstanceOf(IndexOutOfBoundsException.class);
		}
		assertThat(data.iterator().toList()).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
		view.set(0, 20);
		view.set(4, 60);
		assertThat(data.iterator().toList()).containsExactly(0, 1, 20, 3, 4, 5, 60, 7, 8, 9);
	}

	@Test(dataProvider = "backingTypes")
	public void appendInsertsAtRangeEnd(boolean persistent) throws IOException{
		var data = backing(persistent);
		var view = data.subListView(2, 7);
		view.add(100);
		assertThat(data.iterator().toList()).containsExactly(0, 1, 2, 3, 4, 5, 6, 100, 7, 8, 9);
		// Range views retain their original window length after backing-list mutations.
		assertThat(view.iterator().toList()).containsExactly(2, 3, 4, 5, 6);
	}

	@DataProvider
	public Object[][] clearWindows(){
		return new Object[][]{
			{false, 2, 7}, {true, 2, 7},
			{false, 0, 3}, {true, 0, 3},
			{false, 7, 10}, {true, 7, 10},
			{false, 3, 3}, {true, 3, 3},
			{false, 0, 10}, {true, 0, 10}
		};
	}

	@Test(dataProvider = "clearWindows")
	public void clearRemovesOnlyRange(boolean persistent, int from, int to) throws IOException{
		var data = backing(persistent);
		var expected = new ArrayList<>(data.iterator().toList());
		expected.subList(from, to).clear();
		data.subListView(from, to).clear();
		assertThat(data.iterator().toList()).containsExactlyElementsOf(expected);
		assertThat(data.size()).isEqualTo(expected.size());
	}

	@DataProvider
	public Object[][] cacheStates(){
		return new Object[][]{
			{8, new long[]{}}, {8, new long[]{3}}, {8, new long[]{3, 1}},
			{2, new long[]{}}, {2, new long[]{3}}, {2, new long[]{3, 1}}
		};
	}

	@Test(dataProvider = "cacheStates")
	public void cachedIndexOfFindsFirstOccurrence(int maxLinearCache, long[] reads) throws IOException{
		// A linear-cache limit below the list size selects the sparse cache.
		var view = IOList.wrap(new ArrayList<>(List.of(1, 2, 3, 2))).cachedView(16, maxLinearCache);
		for(long index : reads){
			assertThat(view.get(index)).isEqualTo(2);
		}
		assertThat(view.indexOf(1)).isEqualTo(0);
		assertThat(view.indexOf(2)).isEqualTo(1);
		assertThat(view.indexOf(3)).isEqualTo(2);
		assertThat(view.indexOf(99)).isEqualTo(-1);
	}
}

