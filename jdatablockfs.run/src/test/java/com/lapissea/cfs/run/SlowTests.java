package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.config.ConfigDefs;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.Blob;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOHashSet;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.objects.collections.IOSet;
import com.lapissea.cfs.objects.collections.IOTreeSet;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.run.checked.CheckIOList;
import com.lapissea.cfs.run.checked.CheckMap;
import com.lapissea.cfs.run.checked.CheckSet;
import com.lapissea.cfs.run.fuzzing.FuzzFail;
import com.lapissea.cfs.run.fuzzing.FuzzSequence;
import com.lapissea.cfs.run.fuzzing.FuzzingRunner;
import com.lapissea.cfs.run.fuzzing.Plan;
import com.lapissea.cfs.run.fuzzing.RNGEnum;
import com.lapissea.cfs.run.fuzzing.RNGType;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.cfs.chunk.DefragmentManager.FreeFoundAction.ERROR;
import static com.lapissea.cfs.logging.Log.info;
import static com.lapissea.cfs.run.TestUtils.randomBatch;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;


public class SlowTests{
	
	@Test
	void ioMultiWrite() throws IOException{
		var logger = new LateInit<>(() -> DataLogger.Blank.INSTANCE);
//		var logger=LoggedMemoryUtils.createLoggerFromConfig();
		
		byte[] baked;
		{
			var d = MemoryData.empty();
			Cluster.init(d);
			baked = d.readAll();
		}
		randomBatch(300000, (r, iter, tick) -> {
			if(tick){
				info("iteration: {}", iter);
			}
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
					
					mem.write(true, baked);
					Cluster c = new Cluster(mem);
					
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
					
					head = chunks.get(0);
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
	void ioTransaction() throws IOException{
		int cap = 50;
		
		ThreadLocal<IOInterface> dataLocal   = ThreadLocal.withInitial(() -> MemoryData.builder().withCapacity(cap + 10).build());
		ThreadLocal<IOInterface> mirrorLocal = ThreadLocal.withInitial(() -> MemoryData.builder().withCapacity(cap + 10).build());
		
		
		
		//Dumb brute force all possible edge cases
		randomBatch(50000, (rand, run, tick) -> {
			if(tick) info("{}", run);
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
	
	@Test
	void bigMapCompliant() throws IOException{
		bigMapRun(true);
	}
	@Test
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
			
			var map = provider.getRootProvider().<IOMap<Object, Object>>builder("map").withType(TypeLink.of(HashIOMap.class, Object.class, Object.class)).request();
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
		var set = provider.getRootProvider().<IOSet<Integer>>request("hi", type, Integer.class);
		session.accept(provider, new CheckSet<>(set));
		provider.scanGarbage(ERROR);
	}
	
	void runSetFuzz(int iterations, @SuppressWarnings("rawtypes") Class<? extends IOSet> type){
		ConfigDefs.DISABLE_TRANSACTIONS.set(true);

//		var ay = new FuzzSequenceSource.LenSeed(69, 100000, 1);
//
//		for(int i = 0; i<100000; i++){
//			if(i%10000 == 0) LogUtil.println(i);
//			ay.all().forEach(e -> { });
//		}
//
//		if(true) return;
		
		record State(Cluster cluster, IOSet<Integer> set) implements Serializable{
			
			record StateForm(byte[] data) implements Serializable{
				@SuppressWarnings({"rawtypes", "unchecked"})
				@Serial
				private Object readResolve() throws IOException{
					var cluster = new Cluster(MemoryData.builder().withRaw(data).build());
					return new State(cluster, new CheckSet<>(cluster.getRootProvider().require("hi", IOSet.class)));
				}
			}
			
			@Serial
			private Object writeReplace() throws IOException{
				return new StateForm(State.this.cluster.getSource().readAll());
			}
			
		}
		enum Type{ADD, REMOVE, CONTAINS, CLEAR}
		record Action(Type type, int num) implements Serializable{
			@Override
			public String toString(){ return type == Type.CLEAR? type.toString() : type + "-" + num; }
		}
		
		
		var rnr = new FuzzingRunner.StateEnv.Marked<State, Action, IOException>(){
			@Override
			public State create(Random random, long sequenceIndex, FuzzingRunner.Mark mark) throws IOException{
				var cluster = optionallyLogged(mark.sequence(sequenceIndex), "map-fuzz" + sequenceIndex);
				return new State(cluster, new CheckSet<>(cluster.getRootProvider().request("hi", type, Integer.class)));
			}
			@Override
			public void applyAction(State state, long actionIndex, Action action, FuzzingRunner.Mark mark) throws IOException{
				if(mark.action(actionIndex)){
//					LogUtil.println(action);
					int a = 0;
				}
				switch(action.type){
					case ADD -> state.set.add(action.num);
					case REMOVE -> state.set.remove(action.num);
					case CONTAINS -> state.set.contains(action.num);
					case CLEAR -> state.set.clear();
				}
				if(List.of(Type.REMOVE, Type.CLEAR).contains(action.type)){
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
		
		Plan.<State, Action>start(68, iterations, 2000)
		    .loadFail(new File("runSetFuzzFail"))
		    .ifHasFail(p -> p.stableFail(8).report().runMark().assertFail())
		    .runAll()
		    .report()
		    .stableFail(8)
		    .saveFail()
		    .runMark()
		    .assertFail()
		    .execute(runner);
	}
	
	@Test
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
	
	@Test(dependsOnMethods = "simpleHashSet")
	void fuzzIOSet(){
		runSetFuzz(100000, IOHashSet.class);
	}
	
	@Test
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
	
	@Test(dependsOnMethods = "simpleTreeSet")
	void fuzzTreeSet(){
		runSetFuzz(200000, IOTreeSet.class);
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
			{new ListMaker("cached wrapper", 1, () -> IOList.wrap(new ArrayList<Integer>()).cachedView(40))},
			{new ListMaker("contiguous list", 0.5, () -> Cluster.emptyMem().getRootProvider().request("list", ContiguousIOList.class, Integer.class))},
			{new ListMaker("linked list", 0.1, () -> Cluster.emptyMem().getRootProvider().request("list", LinkedIOList.class, Integer.class))},
			};
	}
	
	@Test(dataProvider = "listMakers")
	void fuzzIOList(ListMaker maker){
		var runner = new FuzzingRunner<IOList<Integer>, ListAction, IOException>(new FuzzingRunner.StateEnv<>(){
			@Override
			public boolean shouldRun(FuzzSequence sequence, FuzzingRunner.Mark mark){
//				return sequence.index() == 6;
				return true;
			}
			@Override
			public IOList<Integer> create(Random random, long sequenceIndex, FuzzingRunner.Mark mark) throws IOException{
				return new CheckIOList<>(maker.make());
			}
			@Override
			public void applyAction(IOList<Integer> state, long actionIndex, ListAction action, FuzzingRunner.Mark mark) throws IOException{
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
		
		runner.runAndAssert(69_420, (long)(400_000L*maker.weight), 5000);
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
		
		var runner = new FuzzingRunner<State, Action, IOException>(new FuzzingRunner.StateEnv<>(){
			@Override
			public boolean shouldRun(FuzzSequence sequence, FuzzingRunner.Mark mark){
//				return sequence.index() == 12;
				return true;
			}
			@Override
			public State create(Random random, long sequenceIndex, FuzzingRunner.Mark mark) throws IOException{
				var s = new CheckIOList<>(maker.make());
				for(int i = 0, j = random.nextInt(50); i<j; i++){
					s.add(random.nextInt(200));
				}
				return new State(s.listIterator());
			}
			@Override
			public void applyAction(State state, long actionIndex, Action action, FuzzingRunner.Mark mark) throws IOException{
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
	
	@Test
	void fuzzHashMap(){
		record MapState(Cluster provider, IOMap<Object, Object> map){ }
		var rnr = new FuzzingRunner.StateEnv.Marked<MapState, MapAction, IOException>(){
			@Override
			public void applyAction(MapState state, long actionIndex, MapAction action, FuzzingRunner.Mark mark) throws IOException{
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
				
				if(!(action instanceof MapAction.ContainsKey)){
					state.provider.scanGarbage(ERROR);
				}
			}
			
			@Override
			public MapState create(Random random, long sequenceIndex, FuzzingRunner.Mark mark) throws IOException{
				Cluster provider = optionallyLogged(mark.sequence(sequenceIndex), sequenceIndex + "");
				var map = provider.getRootProvider().<IOMap<Object, Object>>builder("map")
				                  .withType(TypeLink.of(HashIOMap.class, Object.class, Object.class))
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
		
		var stability = runner.establishFailStability(fails.get(0), 15);
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
	
	@Test
	void fuzzBlobIO(){//TODO: do better IO testing, this is not super robust
		record BlobState(IOInterface blob, IOInterface mem){ }
		var runner = new FuzzingRunner<BlobState, BlobAction, IOException>(new FuzzingRunner.StateEnv<>(){
			
			@Override
			public boolean shouldRun(FuzzSequence sequence, FuzzingRunner.Mark mark){
				return true;
			}
			
			@Override
			public void applyAction(BlobState state, long actionIndex, BlobAction action, FuzzingRunner.Mark mark) throws IOException{
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
			public BlobState create(Random random, long sequenceIndex, FuzzingRunner.Mark mark) throws IOException{
				var initial = new byte[random.nextInt(0, 100)];
				random.nextBytes(initial);
				
				var cl   = Cluster.emptyMem();
				var blob = cl.getRootProvider().request("blob", Blob.class);
				blob.write(true, initial);
				
				return new BlobState(blob, MemoryData.builder().withRaw(initial).build());
			}
		}, RNGType.<BlobAction>of(List.of(
			r -> {
				var bb = new byte[r.nextInt(1, 50)];
				r.nextBytes(bb);
				return new BlobAction.Write(r.nextInt(500), bb);
			},
			r -> new BlobAction.Trim(r.nextInt(500))
		)).chanceFor(BlobAction.Trim.class, 1F/20));
		
		runner.runAndAssert(69, 2000000, 10000);
	}
	
	private static Cluster optionallyLogged(boolean logged, String name) throws IOException{
		if(!logged) return Cluster.emptyMem();
		class Lazy{
			private static final LateInit.Safe<DataLogger> data = LoggedMemoryUtils.createLoggerFromConfig();
			
			static{ LogUtil.println("DataLogger made"); }
		}
		var data = LoggedMemoryUtils.newLoggedMemory(name, Lazy.data);
		return Cluster.init(data);
	}
}
