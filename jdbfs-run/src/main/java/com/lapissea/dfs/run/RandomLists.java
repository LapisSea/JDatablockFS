package com.lapissea.dfs.run;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.CursorIOData;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.Plan;
import com.lapissea.fuzz.RunMark;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NanoTimer;
import com.lapissea.util.UtilL;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public final class RandomLists{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
	public static void main(String[] args){
//		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER|LogUtil.Init.USE_CALL_THREAD);
		Configuration conf = new Configuration();
		conf.load(new Configuration.Loader.DashedNameValueArgs(args));
		NanoTimer t = new NanoTimer.Simple();
		t.start();
		main(conf.getView());
		t.end();
		LogUtil.println(t.s());
	}
	
	public static void main(Configuration.View conf){
		var logger = LoggedMemoryUtils.createLoggerFromConfig();
		var err    = new Throwable[1];
		try(var exec = Executors.newVirtualThreadPerTaskExecutor()){
			for(int i = conf.getInt("min", 1), max = conf.getInt("max", 20); i<=max; i++){
				var listCount = i;
				
				var mem = LoggedMemoryUtils.newLoggedMemory("l" + i, logger);
				if(conf.getBoolean("async", true)){
					exec.execute(() -> {
						if(err[0] != null) return;
						try{
							runRandomLists(listCount, logger, false, mem, 500);
						}catch(Throwable t){
							err[0] = t;
						}
					});
					UtilL.sleep(10);
				}else{
					runRandomLists(listCount, logger, false, mem, 500);
				}
			}
		}finally{
			logger.get().destroy();
		}
		if(err[0] != null) throw UtilL.uncheckedThrow(err[0]);
	}
	
	private static void fuzz(){
		
		record Work(int lists, int iters){ }
		
		var runner = new FuzzingRunner<>(new FuzzingStateEnv.Marked<Work, Object, RuntimeException>(){
			@Override
			public void applyAction(Work work, long actionIndex, Object o, RunMark mark){
				LateInit.Safe<DataLogger> logger;
				CursorIOData              mem;
				if(mark.hasSequence()){
					//Load up debugging tools for when error is found
					logger = LoggedMemoryUtils.createLoggerFromConfig();
					mem = LoggedMemoryUtils.newLoggedMemory("l" + work, logger);
				}else{
					//Fuzzing stage
					mem = MemoryData.empty();
					logger = new LateInit.Safe<>(() -> DataLogger.Blank.INSTANCE, Runnable::run);
				}
				
				runRandomLists(work.lists, logger, true, mem, work.iters);
			}
			@Override
			public Work create(RandomGenerator random, long sequenceIndex, RunMark mark) throws RuntimeException{
				return new Work(1 + random.nextInt(20), random.nextInt(2000));
			}
		}, FuzzingRunner::noopAction);
		
		
		Plan.start(runner, 69, 50000, 1)
		    .configMod(c -> c.withLogTimeout(Duration.ofSeconds(1)))
		    .loadFail(new File("tmpfail"))
		    .ifHasFail(p -> p.stableFail(3)
		                     .report()
		                     .clearUnstable()
		                     .runMark()
		                     .assertFail())
		    .runAll()
		    .stableFail(10)
		    .saveFail()
		    .runMark()
		    .report();
	}
	
	private static void lotsOfFIles(int count, boolean virtual){
		Executor exec = virtual? Thread.ofVirtual().name("WORKER-", 0)::start : ForkJoinPool.commonPool();
		var      path = new File("X:\\");
		
		IntStream.range(0, count).mapToObj(i -> {
			return CompletableFuture.runAsync(() -> {
				var rand = new RawRandom(i*1000L);
				runOnFile(1 + rand.nextInt(30), rand.nextInt(1500), path);
			}, exec);
		}).toList().forEach(CompletableFuture::join);
	}
	
	private static int fileIndex;
	private static void runOnFile(int listCount, int iters, File path){
		File file;
		synchronized(RandomLists.class){
			file = new File(path, "l" + (fileIndex++));
		}
		try(var memD = new FileMemoryMappedData(file)){
			runRandomLists(listCount, null, false, memD, iters);
		}catch(IOException e){
			throw new RuntimeException(e);
		}finally{
			file.delete();
		}
	}
	@IOValue
	public static class Item extends IOInstance.Managed<Item>{
		int id, count;
	}
	
	@IOValue
	public static class Entity extends IOInstance.Managed<Entity>{
		public float      pos;
		public List<Item> inventory = new ArrayList<>();
	}
	
	private static void runRandomLists(int listCount, LateInit.Safe<DataLogger> logger, boolean close, CursorIOData mem, int iters){
		Log.info("{#purpleStarting: {} lists#}", listCount);
		try{
			var rand = new RawRandom((long)listCount<<4);
			try{
				var cl = Cluster.init(mem);
				var p  = cl.roots();
				for(int i = 0; i<iters; i++){
					IOList<Entity> m = p.request("list" + rand.nextInt(listCount), ContiguousIOList.class, Entity.class);
					m.addNew(e -> {
						e.inventory.add(new Item());
					});
				}
				cl.defragment();
			}finally{
				if(logger != null){
					logger.block();
					var h = mem.getHook();
					if(h != null) h.writeEvent(mem, LongStream.of());
				}
			}
		}catch(Throwable e){
			throw new RuntimeException("AAAAA " + listCount, e);
		}finally{
			if(logger != null && close) logger.get().destroy();
		}
	}
	
}
