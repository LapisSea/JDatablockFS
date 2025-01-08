package com.lapissea.dfs.run;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.run.checked.CheckIOList;
import com.lapissea.dfs.run.checked.CheckMap;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.NewUnmanaged;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.StoragePool;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import com.lapissea.fuzz.FuzzConfig;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.AssertionsForClassTypes;
import org.assertj.core.api.OptionalAssert;
import org.testng.ITestResult;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

import static com.lapissea.dfs.logging.Log.trace;
import static com.lapissea.dfs.logging.Log.warn;
import static com.lapissea.dfs.type.StagedInit.STATE_DONE;

public final class TestUtils{
	
	private static final LateInit<DataLogger, RuntimeException> LOGGER = LoggedMemoryUtils.createLoggerFromConfig();
	
	private static MemoryData makeRawMem(TestInfo info){
		return LoggedMemoryUtils.newLoggedMemory(info.name(), LOGGER);
	}
	
	private static void closeSession(String name, boolean deleting){
		var ses = LOGGER.get().getSession(name);
		if(deleting){
			if(ses != DataLogger.Session.Blank.INSTANCE){
				trace("deleting ok session {}", name);
			}
			ses.delete();
		}else{
			ses.finish();
		}
	}
	
	private static boolean shouldDeleteOk(){
		boolean shouldDeleteOk = false;
		try{
			shouldDeleteOk = Boolean.parseBoolean(System.getProperty("deleteOk"));
		}catch(Throwable ignored){ }
		return shouldDeleteOk;
	}
	
	private static Cluster      cluster;
	private static DataProvider provider;
	private static TestInfo     testInfo;
	
	public static Cluster testCluster() throws IOException              { return testCluster(TestInfo.ofDepth(2)); }
	public static Cluster testCluster(Object... args) throws IOException{ return testCluster(TestInfo.ofDepth(2, args)); }
	public static Cluster testCluster(TestInfo info) throws IOException{
		if(cluster != null) return checkBadData(info, cluster);
		testInfo = info;
		var mem = makeRawMem(info);
		return cluster = Cluster.init(mem);
	}
	private static <T> T checkBadData(TestInfo info, T obj){
		if(!info.methodName().equals(testInfo.methodName())){
			throw new IllegalStateException("Data not cleaned up");
		}
		return obj;
	}
	
	public static DataProvider testChunkProvider() throws IOException              { return testChunkProvider(TestInfo.ofDepth(2)); }
	public static DataProvider testChunkProvider(Object... args) throws IOException{ return testChunkProvider(TestInfo.ofDepth(2, args)); }
	public static DataProvider testChunkProvider(TestInfo info) throws IOException{
		if(provider != null) return checkBadData(info, provider);
		testInfo = info;
		var mem = makeRawMem(info);
		mem.write(true, MagicID.get());
		return provider = DataProvider.newVerySimpleProvider(mem);
	}
	
	public static void cleanup(ITestResult ctx){
		if(testInfo == null || !ctx.getName().equals(testInfo.methodName())) return;
		closeSession(testInfo.name(), ctx.isSuccess() && shouldDeleteOk());
		LogUtil.println("Cleaned up session " + testInfo.name());
		provider = null;
		cluster = null;
		testInfo = null;
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
			
			T obj = constr.make(provider, chunk, typeDef);
			
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
				read = constr.make(provider, chunk, typeDef);
			}catch(Throwable e){
				throw new RuntimeException("Failed to read object with data", e);
			}
			
			Assertions.assertThat(read).as("Complex object check must result in equality").isEqualTo(obj);
		};
		
		var mem = makeRawMem(info);
		mem.write(true, MagicID.get());
		boolean deleting = false;
		try{
			if(useCluster){
				var c = Cluster.init(mem);
				try{
					ses.accept(c);
				}catch(Throwable e){
					throw new RuntimeException("Failed cluster session", e);
				}
				c.rootWalker(MemoryWalker.PointerRecord.NOOP, false).walk();
			}else{
				mem.write(true, MagicID.get());
				ses.accept(DataProvider.newVerySimpleProvider(mem));
			}
			if(shouldDeleteOk()) deleting = true;
		}finally{
			closeSession(info.name(), deleting);
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
	
	
	public interface Task<E extends Exception>{
		void run(RandomGenerator r, long iter) throws E;
	}
	
	public static <E extends Exception> void randomBatch(int totalTasks, Task<E> task){
		var name = StackWalker.getInstance().walk(s -> s.skip(1).findFirst().orElseThrow().getMethodName());
		randomBatch(name, totalTasks, task);
	}
	
	public static <E extends Exception> void randomBatch(String name, int totalTasks, Task<E> task){
		var fuz = new FuzzingRunner<RandomGenerator, Object, E>(FuzzingStateEnv.JustRandom.of(
			(rand, actionIndex, mark) -> task.run(rand, actionIndex)
		), FuzzingRunner::noopAction);
		var conf  = new FuzzConfig().withName(name);
		var fails = fuz.run(conf, name.hashCode(), totalTasks, totalTasks/conf.maxWorkers());
		if(fails.isEmpty()) return;
		throw new RuntimeException("Failed fuzzing action", fails.getFirst().e());
	}
	
	
	public record Prop(String name, Type type, Object val){ }
	
	public static Class<?> generateIOManagedClass(String className, List<Prop> props){
		var ioVal = Annotations.make(IOValue.class);
		return TempClassGen.gen(new TempClassGen.ClassGen(
			className,
			Iters.from(props).map(p -> new TempClassGen.FieldGen(
				p.name, TempClassGen.VisiblityGen.PUBLIC, false, p.type, List.of(ioVal), null
			)).toList(),
			Set.of(new TempClassGen.CtorType.Empty(Iters.from(props).nonNullProps(Prop::val).toMap(Prop::name, Prop::val))),
			IOInstance.Managed.class,
			List.of()
		));
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
	
	public static Cluster optionallyLogged(boolean logged, String name) throws IOException{
		return Cluster.init(optionallyLoggedMemory(logged, name));
	}
	public static IOInterface optionallyLoggedMemory(boolean logged, String name) throws IOException{
		if(!logged) return MemoryData.builder().withRaw(new byte[MagicID.size()]).build();
		class Lazy{
			private static final LateInit.Safe<DataLogger> LOGGER = LoggedMemoryUtils.createLoggerFromConfig();
			
			static{ LogUtil.println("DataLogger made"); }
		}
		var mem = LoggedMemoryUtils.newLoggedMemory(name, Lazy.LOGGER);
		mem.write(true, new byte[MagicID.size()]);
		return mem;
	}
	
	public static <VALUE> OptionalAssert<VALUE> assertThat(OptionalPP<VALUE> actual){
		return AssertionsForClassTypes.assertThat(actual.opt());
	}
	
	public static <T extends IOInstance<T>> void checkPipeInOutEquality(StructPipe<T> pipe, T value) throws IOException{
		var ch     = DataProvider.newVerySimpleProvider();
		var memory = AllocateTicket.bytes(128).submit(ch);
		checkPipeInOutEquality(memory.getDataProvider(), memory, pipe, value, null);
	}
	
	public static <Prov extends DataProvider.Holder & RandomIO.Creator, T extends IOInstance<T>>
	void checkPipeInOutEquality(Prov memory, StructPipe<T> pipe, T value) throws IOException{
		checkPipeInOutEquality(memory.getDataProvider(), memory, pipe, value, null);
	}
	public static <Prov extends DataProvider.Holder & RandomIO.Creator, T extends IOInstance<T>>
	void checkPipeInOutEquality(Prov memory, StructPipe<T> pipe, T value, FieldSet<T> fields) throws IOException{
		var provider = memory.getDataProvider();
		if(fields != null && !fields.isEmpty()){
			checkPipeInOutEqualityPartial(provider, memory, pipe, value, fields);
			return;
		}
		checkPipeInOutEquality(provider, memory, pipe, value, fields);
	}
	public static <T extends IOInstance<T>>
	void checkPipeInOutEquality(DataProvider provider, RandomIO.Creator memory, StructPipe<T> pipe, T value, FieldSet<T> fields) throws IOException{
		if(fields != null && !fields.isEmpty()){
			checkPipeInOutEqualityPartial(provider, memory, pipe, value, fields);
			return;
		}
		
		try{
			try(var io = memory.io()){
				pipe.write(provider, io, value);
				io.trim();
			}
		}catch(IOException e){
			throw new IOException("Failed to write " + value + " for comparison", e);
		}
		T read;
		try{
			read = pipe.readNew(provider, memory, null);
		}catch(IOException e){
			throw new IOException("Failed to read data for comparison", e);
		}
		Assertions.assertThat(read)
		          .as("read value not the same").isEqualTo(value)
		          .as("values should not of same identity").isNotSameAs(value);
	}
	
	private static <T extends IOInstance<T>>
	void checkPipeInOutEqualityPartial(DataProvider provider, RandomIO.Creator memory, StructPipe<T> pipe, T value, FieldSet<T> fields) throws IOException{
		if(fields.filtered(f -> f.isVirtual(StoragePool.IO))
		         .joinAsOptionalStrM(", ", "Can not check IO fields [", "]", IOField::getName) instanceof Some(var msg)){
			throw new IllegalArgumentException(msg);
		}
		
		var ticket = pipe.getFieldDependency().getDeps(fields);
		try{
			try(var io = memory.io()){
				pipe.write(provider, io, value);
				io.trim();
			}
			var expected = memory.readAll();
			try(var io = memory.io()){
				pipe.writeDeps(provider, io, ticket, value);
			}
			var actual = memory.readAll();
			Assertions.assertThat(actual).containsExactly(expected);
		}catch(Throwable e){
			throw new IOException("Failed to write " + value + " for comparison. Fields: " + fields, e);
		}
		T read;
		try{
			read = pipe.readNewSelective(provider, memory, ticket, null);
		}catch(IOException e){
			throw new IOException("Failed to read data for comparison. Fields: " + fields, e);
		}
		for(IOField<T, ?> field : fields){
			if(!field.instancesEqual(null, value, null, read)){
				throw new AssertionError(
					"Field " + field.getName() + " not equal!\n" +
					"  Expected: " + field.get(null, value) +
					"  Actual:   " + field.get(null, read)
				);
			}
		}
	}
	
	public static <T extends IOInstance<T>> void checkRootsInOutEquality(T value) throws IOException{
		var cluster = Cluster.emptyMem();
		checkRootsInOutEquality(cluster, 1, value);
	}
	public static <T extends IOInstance<T>> void checkRootsInOutEquality(Cluster cluster, int id, T value) throws IOException{
		Objects.requireNonNull(value);
		try{
			cluster.roots().provide(id, value);
		}catch(IOException e){
			throw new IOException("Failed to provide root value: " + value, e);
		}
		T read;
		try{
			read = cluster.roots().request(id, value.getThisStruct().getType());
		}catch(IOException e){
			throw new IOException("Failed to retrieve root value with id: " + id, e);
		}
		Assertions.assertThat(read)
		          .as("retrieved root value not the same").isEqualTo(value)
		          .as("values should not of same identity").isNotSameAs(value);
	}
}
