package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.core.chunk.FreeChunk;
import com.lapissea.dfs.core.chunk.PhysicalExtentWalker;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.run.TestUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Collection oracles exercise compaction through normal persisted roots and fresh reopened handles.
 */
public class CompactionCollectionTests{
	private static final int COUNT  = 1000;
	private static final int CYCLES = 3;
	
	@IOValue
	public static class Payload extends IOInstance.Managed<Payload>{
		public long   id;
		public String text;
		public byte[] bytes;
		public Payload(){ }
		Payload(long id, Random random){
			this.id = id;
			text = text(id, random);
			bytes = new byte[80 + random.nextInt(120)];
			random.nextBytes(bytes);
		}
	}
	
	@DataProvider
	public Object[][] listFlavors(){ return new Object[][]{{false}, {true}}; }
	
	@Test(dataProvider = "listFlavors", timeOut = 600_000)
	public void listChurnPreservesValuesAcrossCompactionAndReopen(boolean dynamic) throws Exception{
		var            cluster     = Cluster.emptyMem();
		var            random      = new Random(dynamic? 0x517ACEL : 0x517A11L);
		Class<?>       elementType = dynamic? Object.class : String.class;
		var            expected    = new ArrayList<Object>();
		IOList<Object> list        = requestList(cluster, elementType);
		long           nextId      = 0;
		for(int cycle = 0; cycle<CYCLES; cycle++){
			while(expected.size()<COUNT){
				Object value = listValue(nextId++, random, dynamic);
				list.add(value);
				expected.add(value);
			}
			assertList(list, expected);
			pruneList(list, expected, random);
			cluster = compactListCheckpoint(cluster, expected, elementType, false);
			list = requestList(cluster, elementType);
		}
		while(expected.size()<COUNT){
			Object value = listValue(nextId++, random, dynamic);
			list.add(value);
			expected.add(value);
		}
		pruneList(list, expected, random);
		compactListCheckpoint(cluster, expected, elementType, false);
		assertThat(expected).hasSize(COUNT/2);
	}
	
	@DataProvider
	public Object[][] mapFlavors(){ return new Object[][]{{false}, {true}}; }
	
	@Test(dataProvider = "mapFlavors", timeOut = 600_000)
	public void hashMapChurnPreservesEntriesAcrossCompactionAndReopen(boolean managed) throws Exception{
		var                   cluster   = Cluster.emptyMem();
		var                   random    = new Random(managed? 0x4A6A0BL : 0x4A6A11L);
		Class<?>              valueType = managed? Payload.class : String.class;
		var                   expected  = new LinkedHashMap<String, Object>();
		var                   removed   = new HashSet<String>();
		IOMap<String, Object> map       = requestMap(cluster, valueType);
		long                  nextId    = 0;
		for(int cycle = 0; cycle<CYCLES; cycle++){
			while(expected.size()<COUNT){
				long   id    = nextId++;
				String key   = "key-" + id + "-" + Long.toUnsignedString(random.nextLong(), 36);
				Object value = managed? new Payload(id, random) : text(id, random);
				map.put(key, value);
				expected.put(key, value);
			}
			assertMap(map, expected, removed);
			pruneMap(map, expected, removed, random);
			cluster = compactMapCheckpoint(cluster, expected, removed, valueType);
			map = requestMap(cluster, valueType);
		}
		while(expected.size()<COUNT){
			long   id    = nextId++;
			String key   = "key-" + id + "-" + Long.toUnsignedString(random.nextLong(), 36);
			Object value = managed? new Payload(id, random) : text(id, random);
			map.put(key, value);
			expected.put(key, value);
		}
		pruneMap(map, expected, removed, random);
		compactMapCheckpoint(cluster, expected, removed, valueType);
		assertThat(expected).hasSize(COUNT/2);
	}
	
	@Test(timeOut = 600_000)
	public void finalHalfPruneCompactionShrinksFileWithLargeEarlierHole() throws Exception{
		var cluster = Cluster.emptyMem();
		// A known earlier hole guarantees space to relocate collection chunks. The
		// allocation stays live until the final prune, so earlier operations cannot use it.
		var            reserved = AllocateTicket.bytes(512*1024).submit(cluster);
		var            random   = new Random(0x5A71AAL);
		var            expected = new ArrayList<Object>();
		IOList<Object> list     = requestList(cluster, String.class);
		long           nextId   = 0;
		for(int cycle = 0; cycle<CYCLES; cycle++){
			while(expected.size()<COUNT){
				var value = text(nextId++, random);
				list.add(value);
				expected.add(value);
			}
			assertList(list, expected);
			pruneList(list, expected, random);
		}
		cluster.getMemoryManager().free(reserved);
		compactListCheckpoint(cluster, expected, String.class, true);
		assertThat(expected).hasSize(COUNT/2);
	}
	
	private static Object listValue(long id, Random random, boolean dynamic){
		if(!dynamic) return text(id, random);
		return switch((int)(id%3)){
			case 0 -> text(id, random);
			case 1 -> id*7919L;
			default -> new Payload(id, random);
		};
	}
	private static String text(long id, Random random){
		int length = 96 + random.nextInt(240);
		var result = new StringBuilder("value-" + id + ":");
		for(int i = 0; i<length; i++) result.append((char)('a' + random.nextInt(26)));
		return result.toString();
	}
	private static void pruneList(IOList<Object> list, List<Object> expected, Random random) throws Exception{
		var indexes = new ArrayList<Integer>();
		for(int i = 0; i<expected.size(); i++) indexes.add(i);
		Collections.shuffle(indexes, random);
		var remove = new ArrayList<>(indexes.subList(0, expected.size()/2));
		remove.sort(Collections.reverseOrder());
		for(int index : remove){
			list.remove(index);
			expected.remove(index);
		}
	}
	private static void pruneMap(IOMap<String, Object> map, Map<String, Object> expected, Set<String> removed, Random random) throws Exception{
		var keys = new ArrayList<>(expected.keySet());
		Collections.shuffle(keys, random);
		for(var key : keys.subList(0, expected.size()/2)){
			assertThat(map.remove(key)).as("pruned key existed").isTrue();
			expected.remove(key);
			removed.add(key);
		}
	}
	private static Cluster compactListCheckpoint(Cluster cluster, List<Object> expected, Class<?> elementType, boolean shrink) throws Exception{
		assertList(requestList(cluster, elementType), expected);
		assertFreeExtents(cluster);
		long before = cluster.getSource().getIOSize();
		cluster.defragment();
		long after = cluster.getSource().getIOSize();
		if(shrink) assertThat(after).as("compaction with a large earlier hole shrinks the file").isLessThan(before);
		else assertThat(after).as("list compaction never grows the file").isLessThanOrEqualTo(before);
		assertList(requestList(cluster, elementType), expected);
		assertFreeExtents(cluster);
		var reopened = TestUtils.reopenMem(cluster);
		assertList(requestList(reopened, elementType), expected);
		assertFreeExtents(reopened);
		return reopened;
	}
	private static Cluster compactMapCheckpoint(Cluster cluster, Map<String, Object> expected, Set<String> removed, Class<?> valueType) throws Exception{
		assertMap(requestMap(cluster, valueType), expected, removed);
		assertFreeExtents(cluster);
		long before = cluster.getSource().getIOSize();
		cluster.defragment();
		assertThat(cluster.getSource().getIOSize()).as("map compaction never grows the file").isLessThanOrEqualTo(before);
		assertMap(requestMap(cluster, valueType), expected, removed);
		assertFreeExtents(cluster);
		var reopened = TestUtils.reopenMem(cluster);
		assertMap(requestMap(reopened, valueType), expected, removed);
		assertFreeExtents(reopened);
		return reopened;
	}
	private static IOList<Object> requestList(Cluster cluster, Class<?> elementType) throws Exception{
		return cluster.roots().request("values", IOList.class, elementType);
	}
	private static IOMap<String, Object> requestMap(Cluster cluster, Class<?> valueType) throws Exception{
		return cluster.roots().request("values", IOMap.class, String.class, valueType);
	}
	private static void assertList(IOList<Object> list, List<Object> expected) throws Exception{
		assertThat(list.size()).isEqualTo(expected.size());
		var actual = new ArrayList<String>();
		for(long i = 0; i<list.size(); i++) actual.add(canonical(list.get(i)));
		assertThat(actual).containsExactlyElementsOf(expected.stream().map(CompactionCollectionTests::canonical).toList());
	}
	private static void assertMap(IOMap<String, Object> map, Map<String, Object> expected, Set<String> removed) throws Exception{
		assertThat(map.size()).isEqualTo(expected.size());
		var actual = new LinkedHashMap<String, String>();
		for(var entry : map){
			assertThat(actual.put(entry.getKey(), canonical(entry.getValue()))).as("unique persisted key").isNull();
		}
		var oracle = new LinkedHashMap<String, String>();
		expected.forEach((key, value) -> oracle.put(key, canonical(value)));
		assertThat(actual).isEqualTo(oracle);
		for(var entry : oracle.entrySet()) assertThat(canonical(map.get(entry.getKey()))).isEqualTo(entry.getValue());
		for(var key : removed) assertThat(map.get(key)).as("pruned key stays absent").isNull();
	}
	private static String canonical(Object value){
		if(value instanceof Payload payload){
			return "payload:" + payload.id + ":" + payload.text + ":" + HexFormat.of().formatHex(payload.bytes);
		}
		return value == null? "null" : value.getClass().getName() + ":" + value;
	}
	
	/**
	 * List growth reallocates buffers and leaves scattered small free extents. Compaction must coalesce
	 * them and shrink the file rather than only reclaiming a handful of bytes.
	 */
	@Test
	public void realisticListChurnCompactsScatteredFreeSpace() throws Exception{
		var rand = new Random(0x5EED);
		var cl   = Cluster.emptyMem();
		for(int i = 0; i<1200; i++){
			IOList<String> m = cl.roots().builder("list" + rand.nextInt(4)).withType(ContiguousIOList.class, String.class).request();
			m.add("x".repeat(10 + rand.nextInt(500)));
		}
		long beforeSize = cl.getSource().getIOSize();
		long beforeFree = freeBytes(cl);
		assertThat(beforeFree).as("list churn should leave scattered free space").isGreaterThan(1000);
		
		cl.defragment();
		
		assertThat(cl.getSource().getIOSize()).as("defragment shrinks the file").isLessThan(beforeSize);
		assertThat(cl.getMemoryManager().getFreeChunks().size()).as("defragment consolidates scattered free space").isLessThan(10);
		assertFreeExtents(cl);
	}
	
	private static long freeBytes(Cluster cluster) throws Exception{
		long bytes = 0;
		for(var extent : new PhysicalExtentWalker(cluster, cluster.getFirstChunk().getPtr())){
			if(extent instanceof FreeChunk) bytes += extent.totalSize();
		}
		return bytes;
	}
	private static void assertFreeExtents(Cluster cluster) throws Exception{
		var linked = new HashSet<ChunkPointer>();
		for(var pointer = cluster.getFirstFreeChunkPointer(); !pointer.isNull(); ){
			assertThat(linked.add(pointer)).as("free chain is acyclic").isTrue();
			pointer = FreeChunk.read(cluster, pointer).getNextPtr();
		}
		var  physical = new HashSet<ChunkPointer>();
		long end      = cluster.getFirstChunk().getPtr().getValue();
		for(var extent : new PhysicalExtentWalker(cluster, cluster.getFirstChunk().getPtr())){
			assertThat(extent.getPtr().getValue()).isEqualTo(end);
			if(extent instanceof FreeChunk) physical.add(extent.getPtr());
			end = extent.dataEnd();
		}
		assertThat(end).isEqualTo(cluster.getSource().getIOSize());
		assertThat(linked).containsExactlyInAnyOrderElementsOf(physical);
		assertThat(cluster.getMemoryManager().getFreeChunks()).containsExactlyInAnyOrderElementsOf(physical);
		cluster.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
	}
}
