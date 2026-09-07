package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOInstance;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Bug reproduction: IOListRangeView.clear() deletes elements OUTSIDE the view's window.
 *
 * What the code is supposed to do:
 *   An IOListRangeView (obtained via IOList#subListView, IOList.java:629) wraps a backing
 *   IOList in a window [from, to). clear() is supposed to remove only the elements inside
 *   the window, i.e. backing indices [from, to), leaving elements before `from` AND
 *   elements at indices >= `to` intact.
 *
 * What the code actually does:
 *   IOListRangeView.clear() at
 *   jdbfs-core/src/main/java/com/lapissea/dfs/objects/collections/listtools/IOListRangeView.java:197
 *   loops `for(long i = data.size() - 1; i >= from; i--) data.remove(i)`, which removes
 *   backing indices [from, data.size()) — everything from the window start to the END of
 *   the backing list — instead of [from, to). The `to` bound is never used.
 *
 * Why the test fails:
 *   With backing list [0..9] and view [2,7), a correct clear() leaves [0,1,7,8,9].
 *   The buggy loop also removes indices 7, 8 and 9, leaving [0,1]. The assertion on the
 *   backing list therefore fails, demonstrating data loss outside the view window.
 */
public class ReproRangeViewClearTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }

	@Test
	void clearOfRangeViewMustNotDeleteElementsBeyondWindow() throws IOException{
		// backing list [0..9] backed by a real cluster (persistent IOList)
		ContiguousIOList<Integer> data = Cluster.emptyMem().roots().request("list", ContiguousIOList.class, Integer.class);
		for(int i = 0; i < 10; i++){
			data.add(i);
		}

		// window covering backing indices 2..6 (elements 2,3,4,5,6)
		var view = data.subListView(2, 7);

		view.clear();

		// Bug (IOListRangeView.java:197): clear() removes [from, data.size()) = [2,10)
		// instead of [from, to) = [2,7), so elements 7, 8, 9 are deleted from the
		// backing list even though they are OUTSIDE the view window.
		assertThat(readAll(data))
		           .as("backing list must keep elements before 'from' AND beyond 'to'; only [from,to) may be cleared")
		           .containsExactly(0, 1, 7, 8, 9);
	}

	@Test
	void clearOfRangeViewMustNotDeleteElementsBeyondWindow_wrappedList() throws IOException{
		// same scenario over an in-memory wrapped list: proves the defect is in the
		// range view itself, independent of the backing list implementation
		var data = IOList.wrap(new ArrayList<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)));

		var view = data.subListView(2, 7);

		view.clear();

		// same bug: 7, 8, 9 (beyond 'to') are wrongly removed from the backing list
		assertThat(readAll(data))
		           .as("wrapped backing list must keep elements beyond 'to' after view.clear()")
		           .containsExactly(0, 1, 7, 8, 9);
	}

	private static List<Integer> readAll(IOList<Integer> list) throws IOException{
		var out = new ArrayList<Integer>();
		var it = list.iterator();
		while(it.hasNext()){
			out.add(it.ioNext());
		}
		return out;
	}
}
