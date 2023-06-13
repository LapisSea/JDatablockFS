package com.lapissea.cfs.run;

import com.lapissea.cfs.MagicID;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.MemoryWalker;
import com.lapissea.cfs.type.NewUnmanaged;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.LateInit;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Random;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static com.lapissea.cfs.logging.Log.trace;
import static com.lapissea.cfs.logging.Log.warn;
import static com.lapissea.cfs.type.StagedInit.STATE_DONE;
import static org.testng.Assert.assertEquals;

public class TestUtils{
	
	private static final LateInit<DataLogger, RuntimeException> LOGGER = LoggedMemoryUtils.createLoggerFromConfig();
	
	
	static void testRawMem(TestInfo info, UnsafeConsumer<IOInterface, IOException> session) throws IOException{
		
		boolean shouldDeleteOk = false;
		try{
			shouldDeleteOk = Boolean.parseBoolean(System.getProperty("deleteOk"));
		}catch(Throwable ignored){ }
		
		
		String sessionName = getSessionName(info);
		
		IOInterface mem      = LoggedMemoryUtils.newLoggedMemory(sessionName, LOGGER);
		boolean     deleting = false;
		try{
			session.accept(mem);
			if(shouldDeleteOk){
				deleting = true;
			}
		}finally{
			var ses = LOGGER.get().getSession(sessionName);
			if(deleting){
				if(ses != DataLogger.Session.Blank.INSTANCE){
					trace("deleting ok session {}", sessionName);
				}
				ses.delete();
			}else{
				ses.finish();
			}
		}
	}
	
	static void testChunkProvider(TestInfo info, UnsafeConsumer<DataProvider, IOException> session) throws IOException{
		testRawMem(info, mem -> {
			mem.write(true, MagicID.get());
			session.accept(DataProvider.newVerySimpleProvider(mem));
		});
	}
	
	static void testCluster(TestInfo info, UnsafeConsumer<Cluster, IOException> session) throws IOException{
		testRawMem(info, mem -> {
			var c = Cluster.init(mem);
			try{
				session.accept(c);
			}catch(Throwable e){
				throw new RuntimeException("Failed cluster session", e);
			}
			c.rootWalker(MemoryWalker.PointerRecord.NOOP, false).walk();
		});
	}
	
	private static String getSessionName(TestInfo info){
		return info.getName();
	}
	
	static <T extends IOInstance.Unmanaged<T>> void complexObjectIntegrityTest(
		TestInfo info, int initalCapacity,
		NewUnmanaged<T> constr,
		TypeLink typeDef,
		UnsafeConsumer<T, IOException> session,
		boolean useCluster
	) throws IOException{
		UnsafeConsumer<DataProvider, IOException> ses = provider -> {
			var chunk = AllocateTicket.bytes(initalCapacity).submit(provider);
			var ref   = chunk.getPtr().makeReference(0);
			
			T obj = constr.make(provider, ref, typeDef);
			
			var actualSize = StandardStructPipe.sizeOfUnknown(provider, obj, WordSpace.BYTE);
			
			if(actualSize>initalCapacity){
				warn("Initial capacity is {} but object has allocated {}", initalCapacity, actualSize);
			}
			
			if(provider instanceof Cluster c){
				c.getRootProvider().provide("test_obj", obj);
			}
			
			session.accept(obj);
			
			T read;
			try{
				read = constr.make(provider, ref, typeDef);
			}catch(Throwable e){
				throw new RuntimeException("Failed to read object with data", e);
			}
			
			assertEquals(read, obj);
		};
		
		if(useCluster){
			testCluster(info, ses::accept);
		}else{
			testChunkProvider(info, ses);
		}
	}
	
	static <E, T extends IOInstance.Unmanaged<T> & IOList<E>> void ioListComplianceSequence(
		TestInfo info, int initalCapacity,
		NewUnmanaged<T> constr,
		TypeLink typeDef,
		UnsafeConsumer<IOList<E>, IOException> session, boolean useCluster
	) throws IOException{
		complexObjectIntegrityTest(info, initalCapacity, constr, typeDef, list -> {
			var checked = new CheckIOList<>(list);
			session.accept(checked);
		}, useCluster);
	}
	
	
	static <K, V, T extends IOInstance.Unmanaged<T> & IOMap<K, V>> void ioMapComplianceSequence(
		TestInfo info,
		NewUnmanaged<T> constr,
		TypeLink typeDef,
		UnsafeConsumer<IOMap<K, V>, IOException> session
	) throws IOException{
		int initial = (int)StandardStructPipe.of(Struct.ofUnknown(typeDef.getTypeClass(null)), STATE_DONE).getSizeDescriptor().getMax(WordSpace.BYTE).orElse(8);
		complexObjectIntegrityTest(info, initial, constr, typeDef, map -> {
			var checked = new CheckMap<>(map);
			session.accept(checked);
		}, true);
	}
	
	
	public interface Task{
		void run(Random r, int iter, boolean tick);
	}
	
	public static void randomBatch(int totalTasks, Task task){
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
}
