package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOHashSet;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.objects.collections.IOSet;
import com.lapissea.dfs.objects.collections.IOTreeSet;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.run.TestUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.fuzz.distributed.master.DistributedSession;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.random.RandomGenerator;

/**
 * Deterministic single-threaded fuzzing of commit/rollback over a mixed set of growing roots.
 * Every root is touched by one thread only, so a failure isolates the transaction rollback
 * path rather than any shared-object race. Mirrors the workload of
 * {@code ClusterConcurrencyTortureTests} without the threads.
 *
 * <p>Roots deliberately vary the element kind: boxed integers, variable-length strings, a managed
 * instance type, and a heterogeneous {@code Object} list. That exercises fixed, varying and
 * reference element storage, hash and comparison based collections, and type-database growth
 * inside transactions.
 */
public class TransactionRollbackFuzzTests{
	
	private static final int KEY_RANGE = 200;
	private static final int MAX_LIST  = 64;
	
	@BeforeClass(alwaysRun = true)
	public void preloadFuzzHelpers(){
		DistributedSession.preload();
	}
	
	@Test
	public void rollbackAfterStructureGrowthStaysConsistent(){
		long only = Long.getLong("dfs.repro.index", -1);
		if(only>=0){
			TestUtils.randomBatch(100_000, (rand, run) -> {
				if(run == only){
					runOne(rand);
				}
			});
			return;
		}
		TestUtils.distributedRandomBatch(100_000, (rand, run) -> runOne(rand));
	}
	
	@IOValue
	public static class Rec extends IOInstance.Managed<Rec>{
		
		public int    number;
		public String text;
		
		public Rec(){ }
		public Rec(int number, String text){
			this.number = number;
			this.text = text;
		}
		
		@Override
		public boolean equals(Object o){
			return o instanceof Rec rec && number == rec.number && Objects.equals(text, rec.text);
		}
		@Override
		public int hashCode(){
			return Objects.hash(number, text);
		}
		@Override
		public String toString(){
			return "Rec[" + number + ", " + text + "]";
		}
	}
	
	private interface Vals<T>{
		T next(RandomGenerator rnd);
	}
	
	private static String randomString(RandomGenerator rnd){
		var len = rnd.nextInt(0, 24);
		var sb  = new StringBuilder(len);
		for(int i = 0; i<len; i++){
			sb.append((char)('a' + rnd.nextInt(6)));
		}
		return sb.toString();
	}
	
	private static final Vals<Integer> INT_VALS      = r -> r.nextInt(KEY_RANGE);
	private static final Vals<String>  STR_POOL_VALS = r -> "s" + r.nextInt(KEY_RANGE);
	private static final Vals<String>  STR_VAR_VALS  = TransactionRollbackFuzzTests::randomString;
	private static final Vals<Rec>     REC_VALS      = r -> new Rec(r.nextInt(KEY_RANGE), randomString(r));
	private static final Vals<Object>  MIXED_VALS    = r -> switch(r.nextInt(3)){
		case 0 -> r.nextInt(KEY_RANGE);
		case 1 -> randomString(r);
		default -> new Rec(r.nextInt(KEY_RANGE), randomString(r));
	};
	
	private static void runOne(RandomGenerator rand) throws IOException{
		var cluster = Cluster.emptyMem();
		var source  = cluster.getSource();
		var state   = State.create(cluster, rand);
		
		for(int step = 0, steps = 1 + rand.nextInt(12); step<steps; step++){
			var     snapshot = state.snapshot();
			boolean commit   = rand.nextInt(3) != 0;
			try(var tx = source.startTransaction()){
				applyOps(state, rand);
				state.verify();
				if(commit){
					tx.commit();
				}
			}
			if(!commit){
				state.restore(snapshot);
			}
			state.verify();
		}
	}
	
	private static void applyOps(State s, RandomGenerator rnd) throws IOException{
		for(int i = 0, ops = 1 + rnd.nextInt(8); i<ops; i++){
			switch(rnd.nextInt(11)){
				case 0 -> listOp(s.contiguous, s.modelContiguous, rnd, INT_VALS);
				case 1 -> listOp(s.linked, s.modelLinked, rnd, INT_VALS);
				case 2 -> mapOp(s.map, s.modelMap, rnd, INT_VALS, INT_VALS);
				case 3 -> setOp(s.hashSet, s.modelHash, rnd, INT_VALS);
				case 4 -> setOp(s.treeSet, s.modelTree, rnd, INT_VALS);
				case 5 -> listOp(s.strList, s.modelStrList, rnd, STR_VAR_VALS);
				case 6 -> listOp(s.recList, s.modelRecList, rnd, REC_VALS);
				case 7 -> listOp(s.mixedList, s.modelMixedList, rnd, MIXED_VALS);
				case 8 -> mapOp(s.strMap, s.modelStrMap, rnd, STR_POOL_VALS, STR_POOL_VALS);
				case 9 -> setOp(s.strSet, s.modelStrSet, rnd, STR_POOL_VALS);
				case 10 -> blobOp(s, rnd);
			}
		}
	}
	
	private static <T> void listOp(IOList<T> disk, List<T> model, RandomGenerator rnd, Vals<T> vals) throws IOException{
		switch(rnd.nextInt(6)){
			case 0 -> {
				if(model.size()>=MAX_LIST){
					disk.remove(0);
					model.remove(0);
				}
				var value = vals.next(rnd);
				disk.add(value);
				model.add(value);
			}
			case 1 -> {
				var  value = vals.next(rnd);
				long index = disk.indexOf(value);
				if(index != -1){
					disk.remove(index);
					model.remove((int)index);
				}
			}
			case 2 -> {
				if(!model.isEmpty()){
					int index = rnd.nextInt(model.size());
					var value = vals.next(rnd);
					disk.set(index, value);
					model.set(index, value);
				}
			}
			case 3 -> {
				if(!model.isEmpty()){
					int index = rnd.nextInt(model.size());
					disk.remove(index);
					model.remove(index);
				}
			}
			case 4 -> {
				if(!model.isEmpty()){
					disk.clear();
					model.clear();
				}
			}
			case 5 -> {
				var value = vals.next(rnd);
				if(disk.contains(value) != model.contains(value)){
					throw new AssertionError("list contains(" + value + ") mismatch");
				}
			}
		}
	}
	
	private static <K, V> void mapOp(IOMap<K, V> disk, Map<K, V> model, RandomGenerator rnd, Vals<K> keys, Vals<V> vals) throws IOException{
		switch(rnd.nextInt(5)){
			case 0 -> {
				if(rnd.nextInt(40) == 0){
					disk.clear();
					model.clear();
					return;
				}
				var key   = keys.next(rnd);
				var value = vals.next(rnd);
				disk.put(key, value);
				model.put(key, value);
			}
			case 1 -> {
				var data = new HashMap<K, V>();
				for(int i = 0, n = 1 + rnd.nextInt(4); i<n; i++){
					data.put(keys.next(rnd), vals.next(rnd));
				}
				disk.putAll(data);
				model.putAll(data);
			}
			case 2 -> {
				var key = keys.next(rnd);
				disk.remove(key);
				model.remove(key);
			}
			case 3 -> {
				var key = keys.next(rnd);
				if(disk.containsKey(key) != model.containsKey(key)){
					throw new AssertionError("map containsKey(" + key + ") mismatch");
				}
			}
			case 4 -> {
				var key = keys.next(rnd);
				if(!Objects.equals(disk.get(key), model.get(key))){
					throw new AssertionError("map get(" + key + ") mismatch");
				}
			}
		}
	}
	
	private static <T> void setOp(IOSet<T> disk, Set<T> model, RandomGenerator rnd, Vals<T> vals) throws IOException{
		switch(rnd.nextInt(4)){
			case 0 -> {
				var value = vals.next(rnd);
				disk.add(value);
				model.add(value);
			}
			case 1 -> {
				var value = vals.next(rnd);
				disk.remove(value);
				model.remove(value);
			}
			case 2 -> {
				if(rnd.nextInt(40) == 0){
					disk.clear();
					model.clear();
				}
			}
			case 3 -> {
				var value = vals.next(rnd);
				if(disk.contains(value) != model.contains(value)){
					throw new AssertionError("set contains(" + value + ") mismatch");
				}
			}
		}
	}
	
	private static void blobOp(State s, RandomGenerator rnd) throws IOException{
		if(rnd.nextInt(20) == 0){
			long size   = s.modelBlob.getIOSize();
			long target = size == 0? 0 : rnd.nextInt((int)size + 1);
			s.modelBlob.ioAt(target, RandomIO::trim);
			s.blob.ioAt(target, RandomIO::trim);
			return;
		}
		var data = new byte[rnd.nextInt(1, 160)];
		rnd.nextBytes(data);
		long size   = s.modelBlob.getIOSize();
		long offset = size == 0? 0 : rnd.nextInt((int)size);
		s.modelBlob.write(offset, false, data);
		s.blob.write(offset, false, data);
	}
	
	private record Snapshot(
		List<Integer> contiguous,
		List<Integer> linked,
		Map<Integer, Integer> map,
		Set<Integer> hash,
		Set<Integer> tree,
		List<String> strList,
		List<Rec> recList,
		List<Object> mixedList,
		Map<String, String> strMap,
		Set<String> strSet,
		MemoryData blob
	){ }
	
	private static final class State{
		
		private ContiguousIOList<Integer>   contiguous;
		private LinkedIOList<Integer>       linked;
		private HashIOMap<Integer, Integer> map;
		private IOHashSet<Integer>          hashSet;
		private IOTreeSet<Integer>          treeSet;
		private ContiguousIOList<String>    strList;
		private ContiguousIOList<Rec>       recList;
		private ContiguousIOList<Object>    mixedList;
		private HashIOMap<String, String>   strMap;
		private IOHashSet<String>           strSet;
		private Blob                        blob;
		
		private List<Integer>         modelContiguous = new ArrayList<>();
		private List<Integer>         modelLinked     = new ArrayList<>();
		private Map<Integer, Integer> modelMap        = new HashMap<>();
		private Set<Integer>          modelHash       = new HashSet<>();
		private Set<Integer>          modelTree       = new TreeSet<>();
		private List<String>          modelStrList    = new ArrayList<>();
		private List<Rec>             modelRecList    = new ArrayList<>();
		private List<Object>          modelMixedList  = new ArrayList<>();
		private Map<String, String>   modelStrMap     = new HashMap<>();
		private Set<String>           modelStrSet     = new HashSet<>();
		private MemoryData            modelBlob       = MemoryData.empty();
		
		static State create(Cluster cluster, RandomGenerator rnd) throws IOException{
			var s = new State();
			s.contiguous = cluster.roots().builder("contiguous").withType(ContiguousIOList.class, Integer.class).request();
			s.linked = cluster.roots().builder("linked").withType(LinkedIOList.class, Integer.class).request();
			s.map = cluster.roots().builder("map").withType(HashIOMap.class, Integer.class, Integer.class).request();
			s.hashSet = cluster.roots().builder("hash").withType(IOHashSet.class, Integer.class).request();
			s.treeSet = cluster.roots().builder("tree").withType(IOTreeSet.class, Integer.class).request();
			s.strList = cluster.roots().builder("str-list").withType(ContiguousIOList.class, String.class).request();
			s.recList = cluster.roots().builder("rec-list").withType(ContiguousIOList.class, Rec.class).request();
			s.mixedList = cluster.roots().builder("mixed-list").withType(ContiguousIOList.class, Object.class).request();
			s.strMap = cluster.roots().builder("str-map").withType(HashIOMap.class, String.class, String.class).request();
			s.strSet = cluster.roots().builder("str-set").withType(IOHashSet.class, String.class).request();
			s.blob = cluster.roots().builder("blob").withType(Blob.class).request();
			
			for(int v = 0; v<8; v++){
				s.contiguous.add(v);
				s.modelContiguous.add(v);
				s.linked.add(v);
				s.modelLinked.add(v);
				s.map.put(v, v);
				s.modelMap.put(v, v);
				s.hashSet.add(v);
				s.modelHash.add(v);
				s.treeSet.add(v);
				s.modelTree.add(v);
				var str = "s" + v;
				s.strList.add(str);
				s.modelStrList.add(str);
				var rec = new Rec(v, str);
				s.recList.add(rec);
				s.modelRecList.add(rec);
				s.mixedList.add(v);
				s.modelMixedList.add(v);
				s.strMap.put(str, str);
				s.modelStrMap.put(str, str);
				s.strSet.add(str);
				s.modelStrSet.add(str);
			}
			var initial = new byte[16];
			rnd.nextBytes(initial);
			s.blob.write(true, initial);
			s.modelBlob = MemoryData.of(initial);
			
			return s;
		}
		
		private void verify() throws IOException{
			verifyList(contiguous, modelContiguous, "contiguous");
			verifyList(linked, modelLinked, "linked");
			verifyMap(map, modelMap, "map");
			verifySet(hashSet, modelHash, "hash");
			verifySet(treeSet, modelTree, "tree");
			verifyList(strList, modelStrList, "str-list");
			verifyList(recList, modelRecList, "rec-list");
			verifyList(mixedList, modelMixedList, "mixed-list");
			verifyMap(strMap, modelStrMap, "str-map");
			verifySet(strSet, modelStrSet, "str-set");
			
			long size = blob.getIOSize();
			if(size != modelBlob.getIOSize()){
				throw new AssertionError("blob size " + size + " != " + modelBlob.getIOSize());
			}
			if(!Arrays.equals(blob.readAll(), modelBlob.readAll())){
				throw new AssertionError("blob content mismatch");
			}
		}
		
		private Snapshot snapshot() throws IOException{
			return new Snapshot(
				new ArrayList<>(modelContiguous),
				new ArrayList<>(modelLinked),
				new HashMap<>(modelMap),
				new HashSet<>(modelHash),
				new TreeSet<>(modelTree),
				new ArrayList<>(modelStrList),
				new ArrayList<>(modelRecList),
				new ArrayList<>(modelMixedList),
				new HashMap<>(modelStrMap),
				new HashSet<>(modelStrSet),
				MemoryData.of(modelBlob.readAll())
			);
		}
		
		private void restore(Snapshot snapshot) throws IOException{
			modelContiguous = new ArrayList<>(snapshot.contiguous);
			modelLinked = new ArrayList<>(snapshot.linked);
			modelMap = new HashMap<>(snapshot.map);
			modelHash = new HashSet<>(snapshot.hash);
			modelTree = new TreeSet<>(snapshot.tree);
			modelStrList = new ArrayList<>(snapshot.strList);
			modelRecList = new ArrayList<>(snapshot.recList);
			modelMixedList = new ArrayList<>(snapshot.mixedList);
			modelStrMap = new HashMap<>(snapshot.strMap);
			modelStrSet = new HashSet<>(snapshot.strSet);
			modelBlob = MemoryData.of(snapshot.blob.readAll());
		}
		
		private static <T> void verifyList(IOList<T> disk, List<T> model, String name) throws IOException{
			long size = disk.size();
			if(size != model.size()){
				throw new AssertionError(name + " size " + size + " != " + model.size());
			}
			for(int i = 0; i<size; i++){
				var actual = disk.get(i);
				var expect = model.get(i);
				if(!Objects.equals(actual, expect)){
					throw new AssertionError(name + "[" + i + "] is " + actual + ", expected " + expect);
				}
			}
		}
		
		private static <K, V> void verifyMap(IOMap<K, V> disk, Map<K, V> model, String name) throws IOException{
			long size = disk.size();
			if(size != model.size()){
				throw new AssertionError(name + " size " + size + " != " + model.size());
			}
			for(var entry : model.entrySet()){
				var actual = disk.get(entry.getKey());
				if(!Objects.equals(actual, entry.getValue())){
					throw new AssertionError(name + "[" + entry.getKey() + "] is " + actual + ", expected " + entry.getValue());
				}
			}
		}
		
		private static <T> void verifySet(IOSet<T> disk, Set<T> model, String name) throws IOException{
			long size = disk.size();
			if(size != model.size()){
				throw new AssertionError(name + " size " + size + " != " + model.size());
			}
			for(var value : model){
				if(!disk.contains(value)){
					throw new AssertionError(name + " missing " + value);
				}
			}
		}
	}
}
