package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DefragmentManager;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOHashSet;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.objects.collections.IOTreeSet;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.annotations.IOCompression;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NanoTimer;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.cfs.logging.Log.info;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;


public class SlowTests{
	
	private interface Task{
		void run(Random r, int iter, boolean tick);
	}
	
	private void randomBatch(int totalTasks, Task task){
		var cores = Runtime.getRuntime().availableProcessors();
		IntStream.range(0, cores).parallel().map(i -> i*10000).forEach(new IntConsumer(){
			int index;
			long lastTime = 0;
			@Override
			public void accept(int seed){
				Random r     = new Random(seed);
				var    batch = totalTasks/cores;
				for(int i = 0; i<batch; i++){
					int     iter;
					boolean tick = false;
					synchronized(this){
						iter = index;
						index++;
						if(System.currentTimeMillis() - lastTime>1000){
							lastTime = System.currentTimeMillis();
							tick = true;
						}
					}
					task.run(r, iter, tick);
				}
			}
		});
	}
	
	@Test
	void ioMultiWrite() throws IOException{
		var logger = new LateInit<>(() -> DataLogger.Blank.INSTANCE);
//		var logger=LoggedMemoryUtils.createLoggerFromConfig();
		
		byte[] baked;
		{
			var d = MemoryData.builder().build();
			Cluster.init(d);
			baked = d.readAll();
		}
		
		randomBatch(500000, (r, iter, tick) -> {
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
	
	
	@DataProvider(name = "comps", parallel = true)
	Object[][] comps(){
		return Arrays.stream(IOCompression.Type.values()).map(t -> new Object[]{t}).toArray(Object[][]::new);
	}
	
	@Test(dataProvider = "comps")
	void compressionIntegrity(IOCompression.Type type){
		NanoTimer t = new NanoTimer.Simple();
		t.start();
		randomBatch(200000, (r, iter, tick) -> {
			if(tick){
				info("iteration: {}", iter);
			}
			
			try{
				byte[] raw;
				if(iter == 0){
					raw = new byte[10];
					Arrays.fill(raw, (byte)(1));
				}else{
					raw = new byte[r.nextInt(1000)];
					for(int i = 0; i<raw.length; ){
						if(r.nextFloat()<0.2){
							for(int to = Math.min(i + r.nextInt(300) + 1, raw.length); i<to; i++){
								raw[i] = (byte)r.nextInt(256);
							}
						}else{
							var b = (byte)r.nextInt(256);
							for(int to = Math.min(i + r.nextInt(200) + 1, raw.length); i<to; i++){
								raw[i] = b;
							}
						}
					}
				}
				
				byte[] compressed   = type.pack(raw);
				byte[] uncompressed = type.unpack(compressed);
				
				assertEquals(uncompressed, raw, "Failed on " + iter);
			}catch(AssertionError e){
				throw e;
			}catch(Throwable e){
				throw new RuntimeException(iter + "", e);
			}
		});
		t.end();
		LogUtil.println("time: ", t.ms());
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
			
			var map = provider.getRootProvider().<IOMap<Object, Object>>builder().withType(TypeLink.of(HashIOMap.class, Object.class, Object.class)).withId("map").request();
			if(mode == Mode.CHECKPOINT){
				info("Starting on step", map.size());
			}
			
			IOMap<Object, Object> splitter;
			if(compliantCheck){
				var ref = new ReferenceMemoryIOMap<>();
				for(var entry : map){
					ref.put(entry.getKey(), entry.getValue());
				}
				splitter = Splitter.map(map, ref, TestUtils::checkCompliance);
			}else{
				splitter = map;
			}
			
			var size = (compliantCheck? NumberSize.SHORT.maxSize : NumberSize.SMALL_INT.maxSize/2);
			
			var  inst = Instant.now();
			long i    = splitter.size();
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
					if(splitter.size()>=1023*2) return;
					splitter.put(i, ("int(" + i + ")").repeat(new Random(provider.getSource().getIOSize() + i).nextInt(20)));
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
	
	@Test
	void fuzzIOSet() throws IOException{
		TestUtils.testCluster(TestInfo.of(), provider -> {
			var set      = provider.getRootProvider().<IOHashSet<Object>>request("hi", IOHashSet.class);
			var checkSet = new HashSet<>();
			
			var r = new Random(69);
			
			for(int i = 0; i<10000; i++){
				Integer num = r.nextInt(400);
				
				switch(r.nextInt(3)){
					case 0 -> {
						var added1 = set.add(num);
						var added2 = checkSet.add(num);
//						LogUtil.println("ADD", num);
						assertEquals(added1, added2);
					}
					case 1 -> {
						var removed1 = set.remove(num);
						var removed2 = checkSet.remove(num);
//						LogUtil.println("REM", num);
						assertEquals(removed1, removed2);
					}
					case 2 -> {
						var c1 = set.contains(num);
						var c2 = checkSet.contains(num);
						assertEquals(c1, c2, "contains fail at " + i + " on " + num);
					}
				}
				
				assertEquals(set.size(), checkSet.size());
				
				var tmp = HashSet.newHashSet(checkSet.size());
				
				var iter = set.iterator();
				while(iter.hasNext()){
					var e = iter.ioNext();
					assertTrue(tmp.add(e), "duplicated " + e + " at " + i);
				}
				assertEquals(tmp, checkSet, i + "");
				
			}
		});
	}
	
	
	@Test
	void fuzzTreeSet() throws IOException{
		TestUtils.testCluster(TestInfo.of(), provider -> {
			var set = provider.getRootProvider().<IOTreeSet<Integer>>request("hi", IOTreeSet.class, Integer.class);
			
			var checkSet = new HashSet<Integer>();
			
			var r    = new Random(69);
			var iter = 300000;
			for(int i = 0; i<iter; i++){
				try{
					if(i%10000 == 0) LogUtil.println(i/((double)iter));
					Integer num = r.nextInt(200);
					
					provider.scanGarbage(DefragmentManager.FreeFoundAction.ERROR);
					
					if(r.nextInt(1000) == 1){
						set.clear();
						checkSet.clear();
					}
					if(r.nextInt(1000) == 1){
						var oldSet = set;
						set = provider.getRootProvider().request("hi", IOTreeSet.class, Integer.class);
						if(!set.equals(oldSet)){
							throw new IllegalStateException(
								"\n" +
								set + "\n" +
								oldSet
							);
						}
					}
					
					switch(r.nextInt(3)){
						case 0 -> {
							var added1 = set.add(num);
							var added2 = checkSet.add(num);
							if(added1 != added2){
								throw new IllegalStateException(num + "");
							}
						}
						case 1 -> {
//							var removed1 = set.remove(num);
//							var removed2 = checkSet.remove(num);
//							if(removed1 != removed2){
//								throw new IllegalStateException(num + "");
//							}
						}
						case 2 -> {
							var c1 = set.contains(num);
							var c2 = checkSet.contains(num);
							if(c1 != c2){
								throw new IllegalStateException(num + "");
							}
						}
					}
					
					if(set.size() != checkSet.size()) throw new IllegalStateException(set.size() + " != " + checkSet.size());
					
					var copy = HashSet.newHashSet(checkSet.size());
					var it   = set.iterator();
					while(it.hasNext()){
						var v = it.ioNext();
						if(!copy.add(v)){
							throw new IllegalStateException(v + " duplicate");
						}
					}
					if(!checkSet.equals(copy)){
						throw new IllegalStateException("\n" + copy + "\n" + checkSet);
					}
				}catch(Throwable e){
					throw new RuntimeException(i + "", e);
				}
			}
			
		});
	}
	
}
