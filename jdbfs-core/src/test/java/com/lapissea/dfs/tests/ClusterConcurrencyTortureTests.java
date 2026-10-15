package com.lapissea.dfs.tests;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.objects.ObjectID;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOHashSet;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.objects.collections.IOSet;
import com.lapissea.dfs.objects.collections.IOTreeSet;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many threads share one Cluster. Each thread owns its own roots and only ever touches
 * those, so a failure points at shared database bookkeeping: the transaction gate, allocator,
 * chunk cache, type DB, or unmanaged registry. Operations mirror the collection fuzz actions of
 * {@code SlowTests} but run concurrently instead of through the deterministic fuzzer.
 */
public class ClusterConcurrencyTortureTests{
	
	private static final int KEY_RANGE = 200;
	private static final int MAX_LIST  = 64;
	
	@Test(timeOut = 600_000)
	public void concurrentRootMutationsStayConsistent() throws Exception{
		int  threadCount  = Integer.getInteger("dfs.torture.threads", 20);
		int  iterations   = Integer.getInteger("dfs.torture.iterations", 50);
		int  abortPercent = Integer.getInteger("dfs.torture.abortPercent", 20);
		long seed         = Long.getLong("dfs.torture.seed", 0x5EEDC0FFEE1234L);
		
		var cluster = Cluster.emptyMem();
		var source  = cluster.getSource();
		var slots   = new ArrayList<Slot>(threadCount);
		try(var setup = source.startTransaction()){
			for(int i = 0; i<threadCount; i++){
				slots.add(Slot.create(cluster, i, seed));
			}
			setup.commit();
		}
		
		var errors  = new ConcurrentLinkedQueue<Throwable>();
		var go      = new CountDownLatch(1);
		var done    = new CountDownLatch(threadCount);
		var workers = new ArrayList<Thread>(threadCount);
		for(int i = 0; i<threadCount; i++){
			var slot   = slots.get(i);
			var rnd    = new Random(seed + i*0x9E3779B97F4A7C15L);
			int finalI = i;
			workers.add(Thread.ofPlatform().name("torture-" + i).start(() -> {
				try{
					go.await();
					for(int it = 0; it<iterations; it++){
						runIteration(source, slot, rnd, abortPercent);
					}
				}catch(Throwable e){
					errors.add(e);
				}finally{
					done.countDown();
				}
			}));
		}
		
		go.countDown();
		assertThat(done.await(5, TimeUnit.MINUTES)).as("all workers finished").isTrue();
		for(var worker : workers){
			worker.join(10_000);
		}
		if(!errors.isEmpty()){
			throw new AssertionError("Concurrent torture produced " + errors.size() + " failure(s)", errors.peek());
		}
		
		verifyReopened(cluster, slots);
	}
	
	private static void runIteration(IOInterface source, Slot slot, RandomGenerator rnd, int abortPercent) throws IOException{
		boolean abort = rnd.nextInt(100)<abortPercent;
		if(abort){
			var snapshot = slot.snapshot();
			try(var tx = source.startTransaction()){
				applyOps(slot, rnd);
				slot.verify();
				// No commit: closing the root must restore every touched structure.
			}
			slot.restore(snapshot);
			slot.verify();
		}else{
			try(var tx = source.startTransaction()){
				applyOps(slot, rnd);
				slot.verify();
				tx.commit();
			}
			slot.verify();
		}
	}
	
	private static void applyOps(Slot slot, RandomGenerator rnd) throws IOException{
		int ops = 1 + rnd.nextInt(8);
		for(int i = 0; i<ops; i++){
			switch(rnd.nextInt(6)){
				case 0 -> listOp(slot.contiguous, slot.modelContiguous, rnd);
				case 1 -> listOp(slot.linked, slot.modelLinked, rnd);
				case 2 -> mapOp(slot.map, slot.modelMap, rnd);
				case 3 -> setOp(slot.hashSet, slot.modelHash, rnd);
				case 4 -> setOp(slot.treeSet, slot.modelTree, rnd);
				case 5 -> blobOp(slot, rnd);
			}
		}
	}
	
	private static void listOp(IOList<Integer> disk, List<Integer> model, RandomGenerator rnd) throws IOException{
		switch(rnd.nextInt(6)){
			case 0 -> {
				if(model.size()>=MAX_LIST){
					disk.remove(0);
					model.remove(0);
				}
				int value = rnd.nextInt(KEY_RANGE);
				disk.add(value);
				model.add(value);
			}
			case 1 -> {// By-value removal, mirroring the list fuzz action.
				int  value = rnd.nextInt(KEY_RANGE);
				long index = disk.indexOf(value);
				if(index != -1){
					disk.remove(index);
					model.remove((int)index);
				}
			}
			case 2 -> {
				if(!model.isEmpty()){
					int index = rnd.nextInt(model.size());
					int value = rnd.nextInt(KEY_RANGE);
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
			case 5 -> {// Read only path.
				int value = rnd.nextInt(KEY_RANGE);
				if(disk.contains(value) != model.contains(value)){
					throw new AssertionError("list contains(" + value + ") mismatch");
				}
			}
		}
	}
	
	private static void mapOp(IOMap<Integer, Integer> disk, Map<Integer, Integer> model, RandomGenerator rnd) throws IOException{
		switch(rnd.nextInt(6)){
			case 0 -> {
				if(rnd.nextInt(40) == 0){
					disk.clear();
					model.clear();
					return;
				}
				int key   = rnd.nextInt(KEY_RANGE);
				int value = rnd.nextInt(KEY_RANGE);
				disk.put(key, value);
				model.put(key, value);
			}
			case 1 -> {
				var data = new HashMap<Integer, Integer>();
				for(int i = 0, n = 1 + rnd.nextInt(4); i<n; i++){
					data.put(rnd.nextInt(KEY_RANGE), rnd.nextInt(KEY_RANGE));
				}
				disk.putAll(data);
				model.putAll(data);
			}
			case 2 -> {
				int key = rnd.nextInt(KEY_RANGE);
				disk.remove(key);
				model.remove(key);
			}
			case 3 -> {
				disk.clear();
				model.clear();
			}
			case 4 -> {
				int key = rnd.nextInt(KEY_RANGE);
				if(disk.containsKey(key) != model.containsKey(key)){
					throw new AssertionError("map containsKey(" + key + ") mismatch");
				}
			}
			case 5 -> {
				int key = rnd.nextInt(KEY_RANGE);
				if(!Objects.equals(disk.get(key), model.get(key))){
					throw new AssertionError("map get(" + key + ") mismatch");
				}
			}
		}
	}
	
	private static void setOp(IOSet<Integer> disk, Set<Integer> model, RandomGenerator rnd) throws IOException{
		switch(rnd.nextInt(4)){
			case 0 -> {
				int value = rnd.nextInt(KEY_RANGE);
				disk.add(value);
				model.add(value);
			}
			case 1 -> {
				int value = rnd.nextInt(KEY_RANGE);
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
				int value = rnd.nextInt(KEY_RANGE);
				if(disk.contains(value) != model.contains(value)){
					throw new AssertionError("set contains(" + value + ") mismatch");
				}
			}
		}
	}
	
	private static void blobOp(Slot slot, RandomGenerator rnd) throws IOException{
		if(rnd.nextInt(20) == 0){
			long size   = slot.modelBlob.getIOSize();
			long target = size == 0? 0 : rnd.nextInt((int)size + 1);
			slot.modelBlob.ioAt(target, RandomIO::trim);
			slot.blob.ioAt(target, RandomIO::trim);
			return;
		}
		var data = new byte[rnd.nextInt(1, 48)];
		rnd.nextBytes(data);
		long size   = slot.modelBlob.getIOSize();
		long offset = size == 0? 0 : rnd.nextInt((int)size);
		slot.modelBlob.write(offset, false, data);
		slot.blob.write(offset, false, data);
	}
	
	private static void verifyReopened(Cluster cluster, List<Slot> slots) throws IOException{
		var snapshot = MemoryData.of(cluster.getSource().readAll());
		var reader   = new Cluster(snapshot);
		for(var slot : slots){
			slot.verifyAgainst(reader);
		}
	}
	
	private static void verifyList(IOList<Integer> disk, List<Integer> model, String name) throws IOException{
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
	
	private static void verifyMap(IOMap<Integer, Integer> disk, Map<Integer, Integer> model, String name) throws IOException{
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
	
	private static void verifySet(IOSet<Integer> disk, Set<Integer> model, String name) throws IOException{
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
	
	private record Snapshot(
		List<Integer> contiguous,
		List<Integer> linked,
		Map<Integer, Integer> map,
		Set<Integer> hash,
		Set<Integer> tree,
		MemoryData blob
	){ }
	
	private static final class Slot{
		
		private final String prefix;
		
		private ContiguousIOList<Integer>   contiguous;
		private LinkedIOList<Integer>       linked;
		private HashIOMap<Integer, Integer> map;
		private IOHashSet<Integer>          hashSet;
		private IOTreeSet<Integer>          treeSet;
		private Blob                        blob;
		
		private List<Integer>         modelContiguous = new ArrayList<>();
		private List<Integer>         modelLinked     = new ArrayList<>();
		private Map<Integer, Integer> modelMap        = new HashMap<>();
		private Set<Integer>          modelHash       = new HashSet<>();
		private Set<Integer>          modelTree       = new TreeSet<>();
		private MemoryData            modelBlob       = MemoryData.empty();
		
		private Slot(String prefix){ this.prefix = prefix; }
		
		static Slot create(Cluster cluster, int index, long seed) throws IOException{
			var prefix = "torture-" + index;
			var slot   = new Slot(prefix);
			
			slot.contiguous = cluster.roots().builder(prefix + "-contiguous").withType(ContiguousIOList.class, Integer.class).request();
			slot.linked = cluster.roots().builder(prefix + "-linked").withType(LinkedIOList.class, Integer.class).request();
			slot.map = cluster.roots().builder(prefix + "-map").withType(HashIOMap.class, Integer.class, Integer.class).request();
			slot.hashSet = cluster.roots().builder(prefix + "-hash").withType(IOHashSet.class, Integer.class).request();
			slot.treeSet = cluster.roots().builder(prefix + "-tree").withType(IOTreeSet.class, Integer.class).request();
			slot.blob = cluster.roots().builder(prefix + "-blob").withType(Blob.class).request();
			
			for(int v = 0; v<8; v++){
				slot.contiguous.add(v);
				slot.modelContiguous.add(v);
				slot.linked.add(v);
				slot.modelLinked.add(v);
				slot.map.put(v, v);
				slot.modelMap.put(v, v);
				slot.hashSet.add(v);
				slot.modelHash.add(v);
				slot.treeSet.add(v);
				slot.modelTree.add(v);
			}
			var initial = new byte[16];
			new Random(seed + index).nextBytes(initial);
			slot.blob.write(true, initial);
			slot.modelBlob = MemoryData.of(initial);
			
			return slot;
		}
		
		private void verify() throws IOException{
			verifyList(contiguous, modelContiguous, prefix + "-contiguous");
			verifyList(linked, modelLinked, prefix + "-linked");
			verifyMap(map, modelMap, prefix + "-map");
			verifySet(hashSet, modelHash, prefix + "-hash");
			verifySet(treeSet, modelTree, prefix + "-tree");
			
			long size = blob.getIOSize();
			if(size != modelBlob.getIOSize()){
				throw new AssertionError(prefix + "-blob size " + size + " != " + modelBlob.getIOSize());
			}
			if(!Arrays.equals(blob.readAll(), modelBlob.readAll())){
				throw new AssertionError(prefix + "-blob content mismatch");
			}
		}
		
		private void verifyAgainst(Cluster reader) throws IOException{
			var view = new Slot(prefix);
			// Set roots normalize their type args to Object, so only the contents can be checked.
			view.contiguous = reader.roots().require(ObjectID.of(prefix + "-contiguous"), ContiguousIOList.class);
			view.linked = reader.roots().require(ObjectID.of(prefix + "-linked"), LinkedIOList.class);
			view.map = reader.roots().require(ObjectID.of(prefix + "-map"), HashIOMap.class);
			view.hashSet = reader.roots().require(ObjectID.of(prefix + "-hash"), IOHashSet.class);
			view.treeSet = reader.roots().require(ObjectID.of(prefix + "-tree"), IOTreeSet.class);
			view.blob = reader.roots().require(ObjectID.of(prefix + "-blob"), Blob.class);
			view.modelContiguous = modelContiguous;
			view.modelLinked = modelLinked;
			view.modelMap = modelMap;
			view.modelHash = modelHash;
			view.modelTree = modelTree;
			view.modelBlob = modelBlob;
			view.verify();
		}
		
		private Snapshot snapshot() throws IOException{
			return new Snapshot(
				new ArrayList<>(modelContiguous),
				new ArrayList<>(modelLinked),
				new HashMap<>(modelMap),
				new HashSet<>(modelHash),
				new TreeSet<>(modelTree),
				MemoryData.of(modelBlob.readAll())
			);
		}
		
		private void restore(Snapshot snapshot) throws IOException{
			modelContiguous = new ArrayList<>(snapshot.contiguous);
			modelLinked = new ArrayList<>(snapshot.linked);
			modelMap = new HashMap<>(snapshot.map);
			modelHash = new HashSet<>(snapshot.hash);
			modelTree = new TreeSet<>(snapshot.tree);
			modelBlob = MemoryData.of(snapshot.blob.readAll());
		}
	}
}
