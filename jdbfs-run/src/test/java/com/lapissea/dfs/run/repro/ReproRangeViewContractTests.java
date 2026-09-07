package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.objects.collections.IOList;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

/**
 * BUG-11 (rangeview-contract): IOListRangeView violates the List contract in three ways.
 *
 * IOListRangeView (jdbfs-core .../objects/collections/listtools/IOListRangeView.java) is a fixed window
 * [from, to) over a backing IOList, obtained via IOList#subListView (IOList.java:629-631). It should behave
 * like a List of exactly (to - from) elements: out-of-range indices throw IndexOutOfBoundsException,
 * add(T) appends, and spliterator() visits exactly the view's elements.
 *
 * What it actually does, for a view [2,7) over a 10-element backing list [0..9]:
 *  (a) get/set/add(index) never check index >= to. toGlobalIndex (IOListRangeView.java:36-39) only rejects
 *      negative indices, so get(5) — local index 5, OUTSIDE the 5-element view — maps to backing index 7 and
 *      silently returns that element (IOListRangeView.java:60-62).
 *  (b) add(T) (IOListRangeView.java:72-78) throws IndexOutOfBoundsException whenever data.size() > to - 1,
 *      which is true in the NORMAL state (backing list at least as long as the window: 10 > 6) — so appending
 *      through the view is always impossible.
 *  (c) spliterator() (IOListRangeView.java:93-95) returns data.spliterator(from), i.e. the BACKING list's
 *      spliterator starting at 2, which runs to the END of the backing list: it yields 8 elements (2..9)
 *      instead of the view's 5.
 *
 * The three test methods below each assert the correct contract behavior; each one FAILS because of the
 * corresponding sub-bug. The sanity method verifies the harness setup itself (the view's own iterator(),
 * which IS correctly bounded, IOListRangeView.java:97-117).
 */
public class ReproRangeViewContractTests{
	
	private record Setup(IOList<Long> backing, IOList<Long> view){}
	
	private static Setup setup(){
		// Backing list [0..9] (mutable), view = window [2,7) -> view elements [2,3,4,5,6], size 5.
		var backing = IOList.wrap(new ArrayList<>(List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)));
		var view = backing.subListView(2, 7);
		return new Setup(backing, view);
	}
	
	/**
	 * Harness sanity: the setup is correct and the view's own iterator() (the correctly-bounded path)
	 * sees exactly the 5 window elements. This method is expected to PASS.
	 */
	@Test
	public void sanityViewContents() throws IOException{
		var s = setup();
		assertEquals(s.backing.size(), 10L, "backing list must have 10 elements");
		assertEquals(s.view.size(), 5L, "view [2,7) must report size 5");
		var got = s.view.iterator().toList();
		assertEquals(got, List.of(2L, 3L, 4L, 5L, 6L), "view's own iterator must yield exactly the 5 window elements");
	}
	
	/**
	 * (a) A view is a list of exactly (to - from) elements, so get(5) on a 5-element view must throw
	 * IndexOutOfBoundsException. Bug: IOListRangeView.get (IOListRangeView.java:60-62) never checks
	 * index >= to and silently returns the backing element at global index 7.
	 */
	@Test
	public void getBeyondViewEndMustThrow() throws IOException{
		var s = setup();
		// Local index 5 is OUTSIDE the view (valid local indices are 0..4).
		try{
			var v = s.view.get(5);
			// Bug (a): no bounds check against the view end -> silently returns backing element 7.
			fail("view.get(5) must throw IndexOutOfBoundsException (5 >= view size 5) but silently returned "
			     + v + " (the backing element at global index 7)");
		}catch(IndexOutOfBoundsException expected){
			// correct contract behavior
		}
	}
	
	/**
	 * (b) List#add(T) appends. Bug: IOListRangeView.add(T) (IOListRangeView.java:72-78) throws
	 * IndexOutOfBoundsException whenever data.size() > to - 1 — here 10 > 6 — i.e. in the normal state,
	 * so appending through the view is impossible.
	 */
	@Test
	public void appendThroughViewMustWork() throws IOException{
		var s = setup();
		// Contract: append 100L through the view; the backing list must grow by 1 and contain the value.
		s.view.add(100L); // Bug (b): always throws IndexOutOfBoundsException (data.size()=10 > to-1=6)
		assertEquals(s.backing.size(), 11L, "backing list must grow by 1 after appending through the view");
		assertEquals(s.backing.indexOf(100L) >= 0, true, "appended value must be stored in the backing list");
	}
	
	/**
	 * (c) The view's spliterator must visit exactly the view's 5 elements. Bug: IOListRangeView.spliterator()
	 * (IOListRangeView.java:93-95) delegates to the backing list's spliterator at index 2, which iterates
	 * to the end of the backing list -> 8 elements (2..9) instead of 5.
	 */
	@Test
	public void spliteratorMustVisitOnlyViewRange() throws IOException{
		var s = setup();
		var got = new ArrayList<Long>();
		s.view.spliterator().forEachRemaining(got::add);
		// Bug (c): yields [2..9] (8 elements, to end of backing list) instead of [2..6] (5 elements).
		assertEquals(got, List.of(2L, 3L, 4L, 5L, 6L),
		               "spliterator of view [2,7) must yield exactly the 5 view elements but yielded " + got.size() + ": " + got);
	}
}
