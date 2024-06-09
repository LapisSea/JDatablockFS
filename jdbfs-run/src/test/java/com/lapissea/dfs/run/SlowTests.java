package com.lapissea.dfs.run;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.PhysicalChunkWalker;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOHashSet;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.objects.collections.IOSet;
import com.lapissea.dfs.objects.collections.IOTreeSet;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.run.checked.CheckIOList;
import com.lapissea.dfs.run.checked.CheckMap;
import com.lapissea.dfs.run.checked.CheckSet;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.type.IOType;
import com.lapissea.fuzz.FuzzConfig;
import com.lapissea.fuzz.FuzzFail;
import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.FuzzSequenceSource;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.Plan;
import com.lapissea.fuzz.RNGEnum;
import com.lapissea.fuzz.RNGType;
import com.lapissea.fuzz.RunMark;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeSupplier;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.dfs.core.DefragmentManager.FreeFoundAction.ERROR;
import static com.lapissea.dfs.logging.Log.info;
import static com.lapissea.dfs.run.FuzzingUtils.stableRun;
import static com.lapissea.dfs.run.FuzzingUtils.stableRunAndSave;
import static com.lapissea.dfs.run.TestUtils.optionallyLogged;
import static com.lapissea.dfs.run.TestUtils.optionallyLoggedMemory;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

public class SlowTests{
	
	@Test
	void ioMultiWrite() throws IOException{
		var logger = new LateInit<>(() -> DataLogger.Blank.INSTANCE);
//		var logger=LoggedMemoryUtils.createLoggerFromConfig();
		
		TestUtils.randomBatch(300000, 1000, (r, iter) -> {
			try{
				List<RandomIO.WriteChunk> allWrites;
				if(iter == 0){
					var b = new byte[10];
					Arrays.fill(b, (byte)(1));
					allWrites = List.of(new RandomIO.WriteChunk(1, b));
				}else allWrites = IntStream.range(0, r.nextInt(10) + 1).mapToObj(i -> {
					var bytes = new byte[15];
					Arrays.fill(bytes, (byte)(i + 1));
					return new RandomIO.WriteChunk(r.nextInt(20), bytes);
				}).toList();
				
				Chunk head;
				
				IOInterface mem = LoggedMemoryUtils.newLoggedMemory("default", logger);
				try(var ignored = mem.openIOTransaction()){
					var chunks = new ArrayList<Chunk>();
					
					mem.write(true, new byte[MagicID.size()]);
					var c = com.lapissea.dfs.core.DataProvider.newVerySimpleProvider(mem);
					
					var chunkCount = r.nextInt(5) + 1;
					for(int i = 0; i<chunkCount; i++){
						chunks.add(AllocateTicket.bytes(r.nextInt(10) + 1).withNext(ChunkPointer.of(1000)).submit(c));
					}
					
					for(int i = 0; i<100; i++){
						var ai = r.nextInt(chunks.size());
						var bi = r.nextInt(chunks.size());
						
						var a = chunks.get(ai);
						var b = chunks.get(bi);
						
						chunks.set(ai, b);
						chunks.set(bi, a);
					}
					
					head = chunks.getFirst();
					var last = head;
					for(int i = 1; i<chunks.size(); i++){
						var next = chunks.get(i);
						last.setNextPtr(next.getPtr());
						last.syncStruct();
						last = next;
					}
					last.setNextPtr(ChunkPointer.NULL);
					last.syncStruct();
				}
				
				for(int i = 0; i<allWrites.size(); i++){
					var writes = allWrites.subList(0, i + 1);
					
					try(var io = head.io()){
						io.writeAtOffsets(writes);
					}
					
					var read = head.readAll();
					
					var valid = new byte[Math.toIntExact(writes.stream().mapToLong(RandomIO.WriteChunk::ioEnd).max().orElseThrow())];
					for(RandomIO.WriteChunk write : writes){
						System.arraycopy(write.data(), 0, valid, Math.toIntExact(write.ioOffset()), write.dataLength());
					}
					if(!Arrays.equals(read, valid)){
						fail(iter + "\n" +
						     IntStream.range(0, valid.length).mapToObj(a -> (valid[a] + "")).collect(Collectors.joining()) + "\n" +
						     IntStream.range(0, read.length).mapToObj(a -> (read[a] + "")).collect(Collectors.joining()));
					}
					
				}
			}catch(AssertionError e){
				throw e;
			}catch(Throwable e){
				throw new RuntimeException(iter + "", e);
			}
		});
		
	}
	
	@Test
	void ioTransaction(){
		int cap = 50;
		
		ThreadLocal<IOInterface> dataLocal   = ThreadLocal.withInitial(() -> MemoryData.builder().withCapacity(cap + 10).build());
		ThreadLocal<IOInterface> mirrorLocal = ThreadLocal.withInitial(() -> MemoryData.builder().withCapacity(cap + 10).build());
		
		
		
		//Dumb brute force all possible edge cases
		TestUtils.randomBatch(50000, 1000, (rand, run) -> {
			int runIndex = 0;
			
			IOInterface data   = dataLocal.get();
			IOInterface mirror = mirrorLocal.get();
			
			try(var ignored = data.openIOTransaction()){
				var runSize = rand.nextInt(40);
				for(int j = 1; j<runSize + 1; j++){
					runIndex++;
					var failS = "failed on run " + run + " " + runIndex;
					
					if(rand.nextFloat()<0.1){
						var newSiz = rand.nextInt(cap*2) + 21;
						
						try(var io = mirror.io()){
							io.setCapacity(newSiz);
						}
						try(var io = data.io()){
							io.setCapacity(newSiz);
						}
					}else{
						
						int off = rand.nextInt((int)data.getIOSize() - 10);
						int siz = rand.nextInt(10);
						
						byte[] buf = new byte[siz];
						Arrays.fill(buf, (byte)j);
						
						mirror.write(off, false, buf);
						data.write(off, false, buf);
						
						assertEquals(
							data.read(off + 1, 9),
							mirror.read(off + 1, 9),
							failS
						);
					}
					
					assertEquals(data.getIOSize(), mirror.getIOSize(), failS);
					
					for(int i = 0; i<100; i++){
						var rSiz = rand.nextInt(20);
						var rOff = rand.nextInt((int)(data.getIOSize() - rSiz));
						if(!Arrays.equals(data.read(rOff, rSiz), mirror.read(rOff, rSiz))){
							fail(failS + " " + i);
						}
					}
					
					check(mirror, data, failS);
				}
			}catch(Throwable e){
				throw new RuntimeException("failed on run " + run + " " + runIndex, e);
			}
			check(mirror, data, "failed after cycle " + run);
		});
	}
	
	private void check(IOInterface expected, IOInterface data, String s){
		byte[] m, d;
		try{
			m = expected.readAll();
			d = data.readAll();
		}catch(Throwable e){
			throw new RuntimeException(s, e);
		}
		if(!Arrays.equals(m, d)){
			fail(s + "\n" +
			     HexFormat.of().formatHex(m) + "\n" +
			     HexFormat.of().formatHex(d) + "\n");
		}
	}
	
	@Test(dependsOnGroups = "hashMap", ignoreMissingDependencies = true)
	void bigMapCompliant() throws IOException{
		bigMapRun(true);
	}
	@Test(dependsOnGroups = "hashMap", ignoreMissingDependencies = true)
	void bigMap() throws IOException{
		bigMapRun(false);
	}
	
	void bigMapRun(boolean compliantCheck) throws IOException{
		TestUtils.testCluster(TestInfo.of(compliantCheck), provider -> {
			enum Mode{
				DEFAULT, CHECKPOINT, MAKE_CHECKPOINT
			}
			
			Mode mode;
			{
				var prop = System.getProperty("chmode");
				if(prop != null) mode = Mode.valueOf(prop);
				else mode = Mode.DEFAULT;
			}
			
			var checkpointFile = new File("bigmap.bin");
			
			long checkpointStep;
			{
				var prop = System.getProperty("checkpointStep");
				if(prop != null){
					checkpointStep = Integer.parseInt(prop);
					if(checkpointStep<0) throw new IllegalArgumentException();
				}else checkpointStep = -1;
			}
			
			read:
			if(mode == Mode.CHECKPOINT){
				info("loading checkpoint from:", checkpointFile.getAbsoluteFile());
				if(!checkpointFile.exists()){
					if(checkpointStep == -1) throw new IllegalStateException("No checkpointStep defined");
					info("No checkpoint, making checkpoint...");
					mode = Mode.MAKE_CHECKPOINT;
					break read;
				}
				try(var f = new DataInputStream(new FileInputStream(checkpointFile))){
					var step = f.readLong();
					if(checkpointStep != -1 && step != checkpointStep){
						info("Outdated checkpoint, making checkpoint...");
						mode = Mode.MAKE_CHECKPOINT;
						break read;
					}
					
					provider.getSource().write(true, f.readAllBytes());
				}
				
				provider.getSource().write(false, provider.getSource().read(0, 1));
				provider = new Cluster(provider.getSource());
			}
			
			var map = provider.roots().<IOMap<Object, Object>>builder("map").withType(IOType.of(HashIOMap.class, Object.class, Object.class)).request();
			if(mode == Mode.CHECKPOINT){
				info("Starting on step", map.size());
			}
			
			IOMap<Object, Object> mapC;
			if(compliantCheck){
				var ref = new ReferenceMemoryIOMap<>();
				for(var entry : map){
					ref.put(entry.getKey(), entry.getValue());
				}
				mapC = new CheckMap<>(map);
			}else{
				mapC = map;
			}
			
			var size = (compliantCheck? NumberSize.SHORT.maxSize : NumberSize.SMALL_INT.maxSize/2);
			
			var  inst = Instant.now();
			long i    = mapC.size();
			while(provider.getSource().getIOSize()<size){
				IOException e1 = null;
				
				if(i == checkpointStep){
					if(mode == Mode.MAKE_CHECKPOINT){
						try(var f = new DataOutputStream(new FileOutputStream(checkpointFile))){
							f.writeLong(checkpointStep);
							provider.getSource().transferTo(f);
						}
						info("Saved checkpoint to", checkpointFile.getAbsoluteFile());
						System.exit(0);
					}
				}
				try{
					if(mapC.size()>=1023*2) return;
					mapC.put(i, ("int(" + i + ")").repeat(new Random(provider.getSource().getIOSize() + i).nextInt(20)));
					i++;
				}catch(Throwable e){
					e1 = new IOException("failed to put element: " + i, e);
				}
				
				if(Duration.between(inst, Instant.now()).toMillis()>1000 || e1 != null){
					inst = Instant.now();
					info("iter {}, {}%", i, (provider.getSource().getIOSize()/(float)size)*100);
				}
				if(e1 != null){
					throw e1;
				}
			}
			info("iter {}, {}%", i, (provider.getSource().getIOSize()/(float)size)*100);
		});
	}
	
	void checkSet(@SuppressWarnings("rawtypes") Class<? extends IOSet> type, UnsafeBiConsumer<Cluster, IOSet<Integer>, IOException> session, boolean log) throws IOException{
		if(log){
			TestUtils.testCluster(TestInfo.of(), provider -> doCheckSet(type, session, provider));
		}else{
			doCheckSet(type, session, Cluster.emptyMem());
		}
	}
	private static void doCheckSet(@SuppressWarnings("rawtypes") Class<? extends IOSet> type, UnsafeBiConsumer<Cluster, IOSet<Integer>, IOException> session, Cluster provider) throws IOException{
		var set = provider.roots().<IOSet<Integer>>request(1, type, Integer.class);
		session.accept(provider, new CheckSet<>(set));
		provider.scanGarbage(ERROR);
	}
	
	void runSetFuzz(int iterations, @SuppressWarnings("rawtypes") Class<? extends IOSet> type){
		record State(Cluster cluster, IOSet<Integer> set) implements Serializable{
			
			record StateForm(byte[] data) implements Serializable{
				@SuppressWarnings({"rawtypes", "unchecked"})
				@Serial
				private Object readResolve() throws IOException{
					var cluster = new Cluster(MemoryData.of(data));
					return new State(cluster, new CheckSet<>(cluster.roots().require(1, IOSet.class)));
				}
			}
			
			@Serial
			private Object writeReplace() throws IOException{
				return new StateForm(State.this.cluster.getSource().readAll());
			}
			
			@Override
			public boolean equals(Object obj){
				try{
					return obj instanceof State that &&
					       Arrays.equals(this.cluster.getSource().readAll(), that.cluster.getSource().readAll());
				}catch(IOException e){
					throw new UncheckedIOException(e);
				}
			}
		}
		
		enum Type{ADD, REMOVE, CONTAINS, CLEAR}
		record Action(Type type, int num) implements Serializable{
			@Override
			public String toString(){ return type == Type.CLEAR? type.toString() : type + "-" + num; }
		}
		
		
		var rnr = new FuzzingStateEnv.Marked<State, Action, IOException>(){
			@Override
			public State create(RandomGenerator random, long sequenceIndex, RunMark mark) throws IOException{
				var cluster = optionallyLogged(mark.sequence(sequenceIndex), "set-fuzz" + sequenceIndex);
				return new State(cluster, new CheckSet<>(cluster.roots().request(1, type, Integer.class)));
			}
			@Override
			public void applyAction(State state, long actionIndex, Action action, RunMark mark) throws IOException{
				if(mark.action(actionIndex)){
					int a = 0;
				}
				
				switch(action.type){
					case ADD -> state.set.add(action.num);
					case REMOVE -> state.set.remove(action.num);
					case CONTAINS -> state.set.contains(action.num);
					case CLEAR -> state.set.clear();
				}
				if(actionIndex%2 == 1 && List.of(Type.REMOVE, Type.CLEAR, Type.ADD).contains(action.type)){
					state.cluster.scanGarbage(ERROR);
				}
			}
		};
		
		var runner = new FuzzingRunner<State, Action, IOException>(
			rnr,
			RNGEnum.of(Type.class)
			       .chanceFor(Type.CLEAR, 1F/1000)
			       .map((e, rand) -> new Action(e, rand.nextInt(200)))
		);
		
		stableRunAndSave(
			Plan.start(runner, 69, iterations, 2000),
			"run" + type.getSimpleName()
		);
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void simpleHashSet() throws IOException{
		checkSet(IOHashSet.class, (d, set) -> {
			set.add(2);
			set.add(1);
			set.add(3);
			set.add(4);
			set.remove(2);
			set.contains(2);
			set.contains(3);
			set.add(5);
			set.remove(5);
		}, false);
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void fuzzIOHashSet(){
		runSetFuzz(100000, IOHashSet.class);
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void simpleTreeSet() throws IOException{
		checkSet(IOTreeSet.class, (d, set) -> {
			set.add(2);
			set.add(1);
			set.add(3);
			set.add(4);
			set.remove(2);
			set.contains(2);
			set.contains(3);
			set.add(5);
			set.remove(5);
		}, false);
	}
	
	@Test(dependsOnMethods = "simpleTreeSet", ignoreMissingDependencies = true)
	void fuzzTreeSet(){
		runSetFuzz(20000, IOTreeSet.class);
	}
	
	interface ListAction{
		enum NumT{ADD, REMOVE, CONTAINS}
		
		enum Num2T{ADD, REMOVE}
		
		record Num(NumT type, int num) implements ListAction{
			@Override
			public String toString(){ return type + "(" + num + ")"; }
		}
		
		record Clear() implements ListAction{
			@Override
			public String toString(){ return "CLEAR"; }
		}
		
		record Num2(Num2T type, int index, int val) implements ListAction{
			@Override
			public String toString(){ return type + "(" + index + " -> " + val + ")"; }
		}
	}
	
	static final class ListMaker{
		
		final String                                       name;
		final double                                       weight;
		final UnsafeSupplier<IOList<Integer>, IOException> make;
		
		ListMaker(String name, double weight, UnsafeSupplier<IOList<Integer>, IOException> make){
			this.name = name;
			this.weight = weight;
			this.make = make;
		}
		IOList<Integer> make() throws IOException{
			return make.get();
		}
		@Override
		public String toString(){
			return name;
		}
	}
	
	@DataProvider
	Object[][] listMakers(){
		return new Object[][]{
			{new ListMaker("memory wrap", 1, () -> IOList.wrap(new ArrayList<>()))},
			{new ListMaker("cached wrapper", 1, () -> IOList.wrap(new ArrayList<Integer>()).cachedView(40, 20))},
			{new ListMaker("contiguous list", 0.5, () -> Cluster.emptyMem().roots().request("list", ContiguousIOList.class, Integer.class))},
			{new ListMaker("linked list", 0.1, () -> Cluster.emptyMem().roots().request("list", LinkedIOList.class, Integer.class))},
			};
	}
	
	@Test(dataProvider = "listMakers", dependsOnGroups = "lists", ignoreMissingDependencies = true)
	void fuzzIOList(ListMaker maker){
		var runner = new FuzzingRunner<IOList<Integer>, ListAction, IOException>(new FuzzingStateEnv.Marked<>(){
			@Override
			public IOList<Integer> create(RandomGenerator random, long sequenceIndex, RunMark mark) throws IOException{
				return new CheckIOList<>(maker.make());
			}
			@Override
			public void applyAction(IOList<Integer> state, long actionIndex, ListAction action, RunMark mark) throws IOException{
				if(mark.action(actionIndex)){
//					LogUtil.println(action);
					int a = 0;
				}
				switch(action){
					case ListAction.Num(ListAction.NumT type, int num) -> {
						switch(type){
							case ADD -> state.add(num);
							case REMOVE -> {
								var idx = state.indexOf(num);
								if(idx != -1) state.remove(idx);
							}
							case CONTAINS -> state.contains(num);
						}
					}
					case ListAction.Clear c -> state.clear();
					case ListAction.Num2(ListAction.Num2T type, int idx, int num) -> {
						if(!state.isEmpty()){
							var i = idx%state.size();
							switch(type){
								case ADD -> state.add(i, num);
								case REMOVE -> state.remove(i);
							}
						}
					}
					default -> throw new IllegalStateException("Unexpected value: " + action);
				}
			}
		}, RNGType.<ListAction>of(List.of(
			r -> new ListAction.Clear(),
			r -> new ListAction.Num(RNGEnum.anyOf(r, ListAction.NumT.class), r.nextInt(200)),
			r -> new ListAction.Num2(RNGEnum.anyOf(r, ListAction.Num2T.class), r.nextInt(200), r.nextInt(200))
		)).chanceFor(ListAction.Clear.class, 1F/1000));
		
		
		stableRun(Plan.start(runner, 69_420, (long)(400_000L*maker.weight), 5000), maker.name);
	}
	
	@Test(dependsOnMethods = "fuzzIOList", dataProvider = "listMakers")
	void fuzzIOListIter(ListMaker maker){
		enum Type{
			NEXT, PREV, SKIP, SKIP_PREV, REMOVE, ADD, SET
		}
		record Action(Type type, int num){
			@Override
			public String toString(){
				return type + (type == Type.SET? "(" + num + ")" : "");
			}
		}
		class State{
			final IOList.IOListIterator<Integer> iter;
			boolean has;
			public State(IOList.IOListIterator<Integer> iter){
				this.iter = iter;
			}
		}
		
		var runner = new FuzzingRunner<State, Action, IOException>(new FuzzingStateEnv<>(){
			@Override
			public boolean shouldRun(FuzzSequence sequence, RunMark mark){
//				return sequence.index() == 12;
				return true;
			}
			@Override
			public State create(RandomGenerator random, long sequenceIndex, RunMark mark) throws IOException{
				var s = new CheckIOList<>(maker.make());
				for(int i = 0, j = random.nextInt(50); i<j; i++){
					s.add(random.nextInt(200));
				}
				return new State(s.listIterator());
			}
			@Override
			public void applyAction(State state, long actionIndex, Action action, RunMark mark) throws IOException{
				switch(action.type){
					case null -> { }
					case NEXT -> {
						if(state.iter.hasNext()){
							state.iter.ioNext();
							state.has = true;
						}
					}
					case PREV -> {
						if(state.iter.hasPrevious()){
							state.iter.ioPrevious();
							state.has = true;
						}
					}
					case SKIP -> {
						if(state.iter.hasNext()){
							state.iter.skipNext();
							state.has = true;
						}
					}
					case SKIP_PREV -> {
						if(state.iter.hasPrevious()){
							state.iter.skipPrevious();
							state.has = true;
						}
					}
					case REMOVE -> {
						if(state.has){
							state.has = false;
							state.iter.ioRemove();
						}
					}
					case ADD -> {
						state.has = false;
						state.iter.ioAdd(action.num);
					}
					case SET -> {
						if(state.has){
							state.iter.ioSet(action.num);
						}
					}
				}
			}
		}, RNGEnum.of(Type.class).map((t, rand) -> new Action(t, rand.nextInt(200))));
		
		runner.runAndAssert(69_420, (long)(1000_000L*maker.weight), 1000);
	}
	
	sealed interface MapAction{
		record Clear() implements MapAction{ }
		
		record Put(Object key, Object value) implements MapAction{ }
		
		record PutAll(Map<Object, Object> data) implements MapAction{ }
		
		record Remove(Object key) implements MapAction{ }
		
		record ContainsKey(Object key) implements MapAction{ }
	}
	
	@Test(dependsOnGroups = "hashMap", ignoreMissingDependencies = true)
	void fuzzHashMap(){
		record MapState(Cluster provider, IOMap<Object, Object> map){ }
		var rnr = new FuzzingStateEnv.Marked<MapState, MapAction, IOException>(){
			@Override
			public void applyAction(MapState state, long actionIndex, MapAction action, RunMark mark) throws IOException{
				boolean deb;
				deb = mark.hasAction()? mark.action(actionIndex) : mark.hasSequence();
				if(deb){
					LogUtil.println(action);
					LogUtil.println(state.map);
					int a = 0;//for breakpoint
				}
				
				switch(action){
					case MapAction.Put(var key, var value) -> state.map.put(key, value);
					case MapAction.PutAll(var data) -> state.map.putAll(data);
					case MapAction.Remove(var key) -> state.map.remove(key);
					case MapAction.ContainsKey(var key) -> state.map.containsKey(key);
					case MapAction.Clear ignored -> state.map.clear();
				}
				
				if(actionIndex%2 == 1 && !(action instanceof MapAction.ContainsKey)){
					state.provider.scanGarbage(ERROR);
				}
			}
			
			@Override
			public MapState create(RandomGenerator random, long sequenceIndex, RunMark mark) throws IOException{
				Cluster provider = optionallyLogged(mark.sequence(sequenceIndex), sequenceIndex + "");
				var map = provider.roots().<IOMap<Object, Object>>builder("map")
				                  .withType(IOType.of(HashIOMap.class, Object.class, Object.class))
				                  .request();
				
				return new MapState(provider, new CheckMap<>(map));
			}
		};
		int range = 40;
		
		var runner = new FuzzingRunner<MapState, MapAction, IOException>(rnr, RNGType.<MapAction>of(List.of(
			r -> new MapAction.Put(10 + r.nextInt(range), 10 + r.nextInt(90)),
			r -> new MapAction.PutAll(
				r.ints(10, 10 + range).distinct().limit(2 + r.nextInt(10)).boxed()
				 .collect(Collectors.toMap(i -> i, i -> 10 + r.nextInt(90)))
			),
			r -> new MapAction.Remove(10 + r.nextInt(range)),
			r -> new MapAction.ContainsKey(10 + r.nextInt(range)),
			r -> new MapAction.Clear()
		)).chanceFor(MapAction.Clear.class, 1F/500).chanceFor(MapAction.PutAll.class, 0.1F));
		
		var fails = runner.run(69, 50000, 2000);
		if(fails.isEmpty()) return;
		LogUtil.printlnEr(FuzzFail.report(fails));
		
		var stability = runner.establishFailStability(fails.getFirst(), 15);
		runner.runStable(stability);
		fail("There were fails!");
	}
	
	sealed interface BlobAction{
		record Write(int off, byte[] data) implements BlobAction{
			@Override
			public boolean equals(Object o){
				if(this == o) return true;
				if(!(o instanceof Write write)) return false;
				
				if(off != write.off) return false;
				return Arrays.equals(data, write.data);
			}
		}
		
		record Trim(int newSiz) implements BlobAction{ }
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void fuzzBlobIO(){//TODO: do better IO testing, this is not super robust
		record BlobState(IOInterface blob, IOInterface mem){ }
		var runner = new FuzzingRunner<BlobState, BlobAction, IOException>(new FuzzingStateEnv.Marked<>(){
			
			@Override
			public void applyAction(BlobState state, long actionIndex, BlobAction action, RunMark mark) throws IOException{
				if(mark.action(actionIndex)){
					LogUtil.println(action);
					int a = 0;//for breakpoint
				}
				switch(action){
					case BlobAction.Write(var off, var data) -> {
						var siz = state.mem.getIOSize();
						if(siz>0) off = (int)(off%siz);
						else off = 0;
						state.mem.write(off, false, data);
						state.blob.write(off, false, data);
					}
					case BlobAction.Trim(var siz) -> {
						var siz0 = state.mem.getIOSize();
						if(siz0>0) siz = (int)(siz%siz0);
						else siz = 0;
						state.mem.ioAt(siz, RandomIO::trim);
						state.blob.ioAt(siz, RandomIO::trim);
					}
				}
				
				assertEquals(state.blob.getIOSize(), state.mem.getIOSize());
				var a = state.blob.readAll();
				var b = state.mem.readAll();
				assertEquals(a, b);
			}
			
			@Override
			public BlobState create(RandomGenerator random, long sequenceIndex, RunMark mark) throws IOException{
				var initial = new byte[random.nextInt(0, 100)];
				random.nextBytes(initial);
				
				var cl   = optionallyLogged(mark.sequence(sequenceIndex), sequenceIndex + "fuzzBlobIO");
				var blob = cl.roots().request("blob", Blob.class);
				blob.write(true, initial);
				
				return new BlobState(blob, MemoryData.of(initial));
			}
		}, RNGType.<BlobAction>of(List.of(
			r -> {
				var bb = new byte[r.nextInt(1, 50)];
				r.nextBytes(bb);
				return new BlobAction.Write(r.nextInt(500), bb);
			},
			r -> new BlobAction.Trim(r.nextInt(500))
		)).chanceFor(BlobAction.Trim.class, 1F/20));
		
		
		stableRun(
			Plan.start(runner, 69, 500_000, 5_000),
			"runFuzzBlobIO"
		);
	}
	
	
	sealed interface AllocAction extends Serializable{
		record Alloc(byte[] data, int rootID) implements AllocAction{
			@Override
			public String toString(){
				return TextUtil.toString("Alloc{", data, rootID, "}");
			}
			@Override
			public boolean equals(Object o){
				return o instanceof Alloc alloc &&
				       rootID == alloc.rootID && Arrays.equals(data, alloc.data);
			}
		}
		
		record Dealloc(int bytes, int rootID) implements AllocAction{ }
		
		int rootID();
	}
	
	@Test
	void fuzzChainResize(){
		
		var rootCount    = 10;
		var allocMaxSize = 100;
		
		record State(
			com.lapissea.dfs.core.DataProvider dp, List<Chunk> roots, List<MemoryData> reference, long sequenceIndex
		){ }
		var runner = new FuzzingRunner<>(new FuzzingStateEnv.Marked<State, AllocAction, IOException>(){
			@Override
			public void applyAction(State state, long actionIndex, AllocAction action, RunMark mark) throws IOException{
				var root = state.roots.get(action.rootID());
				var ref  = state.reference.get(action.rootID());
				if(mark.action(actionIndex)){
					int i = 0;
				}
				switch(action){
					case AllocAction.Alloc alloc -> {
						if(root == null){
							state.roots.set(
								alloc.rootID,
								AllocateTicket.bytes(alloc.data.length)
								              .withDataPopulated(c -> c.write(false, alloc.data))
								              .submit(state.dp)
							);
						}else{
							try(var io = root.io()){
								io.skipExact(root.chainSize());
								io.write(alloc.data);
							}
						}
						ref.write(ref.getIOSize(), false, alloc.data);
					}
					case AllocAction.Dealloc dealloc -> {
						if(root != null){
							if(root.chainSize()<=dealloc.bytes){
								state.roots.set(dealloc.rootID, null);
								root.freeChaining();
							}else{
								try(var io = root.io()){
									io.setCapacity(root.chainSize() - dealloc.bytes);
								}
							}
							ref.setIOSize(Math.max(0, ref.getIOSize() - dealloc.bytes));
						}
					}
				}
				if(actionIndex%50 == 0){
					Chunk first;
					try{
						first = state.dp.getFirstChunk();
					}catch(IOException ignore){
						first = null;
					}
					if(first != null){
						var set = new PhysicalChunkWalker(first).collectToSet();
						for(var ch : set){
							if(!ch.hasNextPtr()) continue;
							var next = ch.requireNext();
							if(!set.contains(next)){
								throw new IllegalStateException("Corrupt chunk at " + next.getPtr());
							}
						}
					}
				}
				
				var expected = ref.readAll();
				var r        = state.roots.get(action.rootID());
				var actual   = r == null? new byte[0] : r.readAll();
				if(!Arrays.equals(actual, expected)){
					assertEquals(actual, expected, TextUtil.toString("\n", actual, "\n", expected));
				}
			}
			@Override
			public State create(RandomGenerator random, long sequenceIndex, RunMark mark) throws IOException{
				var data      = optionallyLoggedMemory(mark.sequence(sequenceIndex), "fuzzChainResize");
				var dp        = random.nextInt(4) == 1? com.lapissea.dfs.core.DataProvider.newVerySimpleProvider(data) : Cluster.init(data);
				var roots     = new ArrayList<Chunk>(rootCount);
				var reference = new ArrayList<MemoryData>(rootCount);
				for(int i = 0; i<10; i++){
					roots.add(null);
					reference.add(MemoryData.empty());
				}
				return new State(dp, roots, reference, sequenceIndex);
			}
		}, RNGType.<AllocAction>of(List.of(
			r -> {
				var bytes = new byte[r.nextInt(allocMaxSize)];
				r.nextBytes(bytes);
				return new AllocAction.Alloc(bytes, r.nextInt(rootCount));
			},
			r -> new AllocAction.Dealloc(r.nextInt(allocMaxSize), r.nextInt(rootCount))
		)).chanceFor(AllocAction.Dealloc.class, 1F/3));
		
		stableRun(Plan.start(
			runner, new FuzzConfig(),
			new FuzzSequenceSource.LenSeed(42069, 500_000, 500)
//			() -> Stream.of(FuzzSequence.fromDataStick("REPLACE_ME"))
		), "fuzzChainResize");
	}
	
}
