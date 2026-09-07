package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.objects.ChunkPointer;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Reproduction for BUG-21 (chunkset-addall).
 *
 * What the code is supposed to do:
 *   ChunkSet is a Set&lt;ChunkPointer&gt;. {@code addAll} (ChunkSet.java:270-285), like
 *   repeated {@code add(ChunkPointer)} calls, must leave the set consistent: the
 *   cached size/start/end fields (ChunkSet.java:193-195) must agree with the
 *   roaring-bitmap index so that size()/isEmpty()/contains()/min()/max() match
 *   what iteration yields.
 *
 * What it actually does:
 *   addAll() only updates the index (ChunkSet.java:281-282: {@code index = toAdd}
 *   / {@code index.or(toAdd)}) and NEVER updates size/start/end, unlike
 *   add(long) (ChunkSet.java:381-392) which maintains all three. Two effects:
 *   (1) On a fresh set (index == null) the Collection ctor (ChunkSet.java:200-202)
 *       and ChunkPointer... varargs ctor (:212-214) NPE inside ptrsToIndex
 *       (ChunkSet.java:299): the exhaustive switch on the sealed Index type
 *       rejects a null selector, so the "if(index == null) index = toAdd" branch
 *       at ChunkSet.java:281 is dead code.
 *   (2) On a set whose index is non-null but empty (size == 0 — the state
 *       removeAll leaves behind at ChunkSet.java:367-373), addAll fills the index
 *       but keeps size == 0 and start == end == -1. The set then reports
 *       size() == 0, isEmpty() == true (the size==0 check at :235) and
 *       contains() == false for every element (rageSize() == 0 short-circuit at
 *       :484-485) while iteration (:494-500) returns ALL elements — exactly the
 *       reported inconsistency.
 *
 * Why the test fails:
 *   addAllOnEmptyIndexedSetLeavesSizeStartEndStale encodes the CORRECT expected
 *   behavior (size 3, !isEmpty, contains true, min/max correct) and fails, while
 *   its iteration assertion shows the elements ARE in the index. The NPE test
 *   documents effect (1); the control test proves the add()-only path is
 *   consistent, so the failures are specific to the addAll code path.
 */
public class ReproChunkSetAddAllTests{
	
	private static final List<ChunkPointer> PTRS = List.of(
		ChunkPointer.of(10),
		ChunkPointer.of(20),
		ChunkPointer.of(30)
	);
	
	@Test
	public void addAllOnEmptyIndexedSetLeavesSizeStartEndStale(){
		var set = new ChunkSet();
		set.add(ChunkPointer.of(10));
		// removeAll empties the bitmap but keeps it non-null and drops size to 0
		// (ChunkSet.java:367-373) — the state addAll's own null-check (:281) expects
		assertTrue(set.removeAll(List.of(ChunkPointer.of(10))), "setup: remove the single element");
		assertTrue(set.isEmpty(), "setup: set must be empty before addAll");
		
		// BUG: addAll (ChunkSet.java:270-285) updates only the bitmap index,
		// never the size/start/end fields
		assertTrue(set.addAll(PTRS), "addAll should report a change");
		
		// iteration reads the index directly, so it still works:
		var iterated = set.stream().map(ChunkPointer::getValue).sorted().toList();
		assertEquals(iterated, List.of(10L, 20L, 30L), "iteration should yield all 3 elements");
		
		var problems = new ArrayList<String>();
		// BUG: the size field was never incremented by addAll, still 0
		if(set.size() != 3) problems.add("size()=" + set.size() + ", expected 3");
		// BUG: isEmpty() is the size==0 check (ChunkSet.java:235)
		if(set.isEmpty()) problems.add("isEmpty()=true, expected false");
		// BUG: contains() short-circuits false via rageSize()==0 (ChunkSet.java:484-485)
		for(var p : PTRS){
			if(!set.contains(p)) problems.add("contains(" + p + ")=false, expected true");
		}
		// BUG: min()/max() read the stale start/end fields (ChunkSet.java:508-514)
		if(set.min() != 10) problems.add("min()=" + set.min() + ", expected 10");
		if(set.max() != 30) problems.add("max()=" + set.max() + ", expected 30");
		
		// Single failing assertion so the message shows the whole inconsistent state:
		// size/isEmpty/contains say "empty" while the iteration above returned all 3.
		assertTrue(problems.isEmpty(),
			"ChunkSet after addAll is internally inconsistent: " + String.join("; ", problems)
			+ " — but iteration returned " + iterated);
	}
	
	@Test
	public void collectionCtorNpesBecausePtrsToIndexCannotHandleNullIndex(){
		// The Collection ctor (ChunkSet.java:200-202) and ChunkPointer... varargs
		// ctor (:212-214) delegate to addAll on a FRESH set (index == null).
		// addAll's "if(index == null) index = toAdd" branch (:281) is dead code:
		// ptrsToIndex (:298-321) switches on the null index and NPEs, so a set
		// cannot be built from a collection at all.
		NullPointerException npe = null;
		try{
			new ChunkSet(PTRS);
		}catch(NullPointerException e){
			npe = e;
		}
		assertTrue(npe != null, "Collection ctor on a fresh ChunkSet should NPE (addAll cannot handle a null index)");
	}
	
	@Test
	public void controlSingleAddPathIsConsistent(){
		// Control: the add(long) path (ChunkSet.java:378) maintains size/start/end,
		// so this must PASS and proves the harness is sound — the failures above are
		// specific to the addAll code path.
		var set = new ChunkSet();
		for(var p : PTRS) set.add(p);
		
		assertEquals(set.size(), 3);
		assertFalse(set.isEmpty());
		for(var p : PTRS) assertTrue(set.contains(p), "contains(" + p + ") must be true");
		assertEquals(set.min(), 10);
		assertEquals(set.max(), 30);
	}
}
