package com.lapissea.dfs.tests;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.IOList;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Regression test for addMany batch flushing: the transfer buffer cursor must be reset together with
 * its size, or every batch after the first re-sends all previous bytes (quadratic write volume and
 * corrupted element layout past the first batch).
 */
@SuppressWarnings("unchecked")
public class AddManyIntegrityTest{
	
	private static <T> ContiguousIOList<T> cont(IOList<T> list){
		return (ContiguousIOList<T>)list;
	}
	
	@Test
	public void addManyAcrossBatchesKeepsContent() throws IOException{
		var cluster = Cluster.emptyMem();
		IOList<Integer> list = cluster.roots().request("L", IOList.class, int.class);
		
		//well over BATCH_BYTES (default 8192) to force many flushes
		int count = 5_000;
		var exp = new ArrayList<Integer>(count);
		for(int i = 0; i<count; i++) exp.add(i * 31);
		list.addAll(exp);
		
		assertEquals((long)list.size(), count);
		for(int i = 0; i<count; i++){
			assertEquals(list.get(i), exp.get(i), "element at " + i);
		}
	}
	
	@Test
	public void addManyAcrossBatchesKeepsChainSize() throws IOException{
		var cluster = Cluster.emptyMem();
		IOList<Integer> list = cluster.roots().request("L", IOList.class, int.class);
		
		int count = 10_000;
		var exp = new ArrayList<Integer>(count);
		for(int i = 0; i<count; i++) exp.add(i);
		list.addAll(exp);
		
		//chain must only hold the actual bytes: head + count * element size, plus slack for
		//capacity growth - not the quadratic sum a stale buffer cursor produces
		long chainSize = cont(list).getPointer().dereference(cont(list).getDataProvider()).chainSize();
		assertTrue(chainSize<=count*4L*2, "chain holds " + chainSize + " bytes, expected around " + (count*4L));
		
		for(int i = 0; i<count; i++) assertEquals(list.get(i), exp.get(i), "element at " + i);
	}
	
	@Test
	public void stringAddManyAcrossBatches() throws IOException{
		var cluster = Cluster.emptyMem();
		IOList<String> list = cluster.roots().request("L", IOList.class, String.class);
		
		int count = 3_000;
		var exp = new ArrayList<String>(count);
		for(int i = 0; i<count; i++) exp.add("string-number-" + i);
		list.addAll(exp);
		
		assertEquals((long)list.size(), count);
		for(int i = 0; i<count; i++) assertEquals(list.get(i), exp.get(i), "element at " + i);
	}
}
