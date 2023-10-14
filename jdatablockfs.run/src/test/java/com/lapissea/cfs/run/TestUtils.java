package com.lapissea.cfs.run;

import com.lapissea.cfs.MagicID;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.run.checked.CheckIOList;
import com.lapissea.cfs.run.checked.CheckMap;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.IOType;
import com.lapissea.cfs.type.MemoryWalker;
import com.lapissea.cfs.type.NewUnmanaged;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.util.LateInit;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

import static com.lapissea.cfs.logging.Log.trace;
import static com.lapissea.cfs.logging.Log.warn;
import static com.lapissea.cfs.type.StagedInit.STATE_DONE;
import static org.testng.Assert.assertEquals;

public final class TestUtils{
	
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
		IOType typeDef,
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
				c.roots().provide("test_obj", obj);
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
		IOType typeDef,
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
		IOType typeDef,
		UnsafeConsumer<IOMap<K, V>, IOException> session
	) throws IOException{
		int initial = (int)StandardStructPipe.of(Struct.ofUnknown(typeDef.getTypeClass(null)), STATE_DONE).getSizeDescriptor().getMax(WordSpace.BYTE).orElse(8);
		complexObjectIntegrityTest(info, initial, constr, typeDef, map -> {
			var checked = new CheckMap<>(map);
			session.accept(checked);
		}, true);
	}
	
	
	public interface Task{
		void run(RandomGenerator r, long iter);
	}
	
	public static void randomBatch(int totalTasks, int batch, Task task){
		var fuz = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.of(
			(rand, actionIndex, mark) -> task.run(rand, actionIndex)
		), r -> null);
		
		fuz.runAndAssert(69, totalTasks, batch);
	}
	
	public static <T> T callWithClassLoader(ClassLoader classLoader, String sesName) throws ReflectiveOperationException{
		Class<?> cl = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass();
		var      fn = cl.getDeclaredMethod(sesName);
		fn.setAccessible(true);
		return callWithClassLoader(classLoader, fn);
	}
	@SuppressWarnings("unchecked")
	public static <T> T callWithClassLoader(ClassLoader classLoader, Method session) throws ReflectiveOperationException{
		
		var orgClass = session.getDeclaringClass();
		var cname    = orgClass.getName();
		var fname    = session.getName();
		
		var cl = classLoader.loadClass(cname);
		var fn = cl.getDeclaredMethod(fname);
		fn.setAccessible(true);
		var res = fn.invoke(null);
		return (T)res;
	}
	
	public static ClassLoader makeShadowClassLoader(Map<String, UnsafeFunction<String, byte[], Exception>> shadowingTypes){
		var mapping = Map.copyOf(shadowingTypes);
		
		class ShadowClassLoader extends ClassLoader{
			static{ registerAsParallelCapable(); }
			
			private final Map<Object, Lock> lockMap = new ConcurrentHashMap<>();
			static        long              ay;
			
			@Override
			protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
				if(name.startsWith("java.lang")) return getParent().loadClass(name);
				{
					Class<?> c = findLoadedClass(name);
					if(c != null) return c;
				}
				var lock = lockMap.computeIfAbsent(getClassLoadingLock(name), o -> new ReentrantLock());
				lock.lock();
				try{
					Class<?> c = findLoadedClass(name);
					if(c != null) return c;
					
					var fn = mapping.get(name);
					if(fn == null){
						var cls1 = super.loadClass(name, resolve);
						if(cls1.getClassLoader() == getParent()){
							var r = getParent().getResourceAsStream(name.replace('.', '/') + ".class");
							if(r == null) return getParent().loadClass(name);
							try(r){
								var bb = r.readAllBytes();
								return defineClass(name, bb, 0, bb.length);
							}catch(IOException e){
								throw new RuntimeException(e);
							}
						}
						return cls1;
					}
					
					try{
						var bytecode = fn.apply(name);
						return defineClass(name, bytecode, 0, bytecode.length);
					}catch(Exception e){
						throw new RuntimeException("Failed to generate shadow class", e);
					}
				}finally{
					lock.unlock();
				}
			}
		}
		
		return new ShadowClassLoader();
	}
}
