package com.lapissea.dfs.run;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeBiFunction;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.dfs.type.StagedInit.STATE_DONE;
import static com.lapissea.util.UtilL.async;
import static org.assertj.core.api.Assertions.assertThat;

public class GenericFieldTests{
	
	@AfterMethod
	public void cleanup(ITestResult method){ TestUtils.cleanup(method); }
	
	@Test
	void simpleGenericTest() throws IOException{
		//noinspection unchecked
		var pipe = StandardStructPipe.of((Class<GenericContainer<Object>>)(Class<?>)GenericContainer.class, STATE_DONE);
		
		var provider = TestUtils.testChunkProvider();
		var chunk    = AllocateTicket.bytes(64).submit(provider);
		
		var container = new GenericContainer<>();
		
		container.value = new Dummy(123);
		TestUtils.checkPipeInOutEquality(chunk, pipe, container);
		
		container.value = "This is a test.";
		TestUtils.checkPipeInOutEquality(chunk, pipe, container);
	}
	
	record Gen<T>(UnsafeBiFunction<RandomGenerator, DataProvider, T, IOException> gen, Class<T> type, String name){
		Gen(UnsafeBiFunction<RandomGenerator, com.lapissea.dfs.core.DataProvider, T, IOException> gen, Class<T> type){
			this(gen, type, Utils.typeToHuman(type));
		}
		@Override
		public String toString(){
			return name;
		}
	}
	
	private static List<Gen<?>> makeBaseGens(){
		List<Gen<?>> gens = new ArrayList<>(List.of(
			new Gen<>((r1, d1) -> new Dummy(r1.nextInt()), Dummy.class),
			new Gen<>((r, d) -> new GenericContainer<>(r.nextInt()), GenericContainer.class),
			new Gen<>((r, d) -> {
				var l = new ContiguousIOList<Integer>(d, AllocateTicket.bytes(16).submit(d), IOType.of(ContiguousIOList.class, int.class));
				l.addAll(r.ints().limit(r.nextInt(20)).boxed().toList());
				return l;
			}, ContiguousIOList.class),
			new Gen<>((r, d) -> r.nextDouble(), double.class),
			new Gen<>((r, d) -> (char)r.nextInt(Character.MAX_VALUE), char.class),
			new Gen<>((r, d) -> r.nextFloat(), float.class),
			new Gen<>((r, d) -> r.nextLong(), long.class),
			new Gen<>((r, d) -> r.nextInt(), int.class),
			new Gen<>((r, d) -> (short)r.nextInt(Short.MIN_VALUE, Short.MAX_VALUE), short.class),
			new Gen<>((r, d) -> (byte)r.nextInt(Byte.MIN_VALUE, Byte.MAX_VALUE), byte.class),
			new Gen<>((r, d) -> r.nextBoolean(), boolean.class),
			new Gen<>((r, d) -> IntStream.range(0, r.nextInt(20))
			                             .mapToObj(i -> ((char)r.nextInt(200)) + "")
			                             .filter(StandardCharsets.UTF_8.newEncoder()::canEncode)
			                             .collect(Collectors.joining("")), String.class),
			new Gen<>((r, d) -> Instant.ofEpochMilli(r.nextLong(Long.MAX_VALUE)), Instant.class),
			new Gen<>((r, d) -> LocalDate.ofEpochDay(r.nextLong(-365243219162L, 365241780471L)), LocalDate.class),
			new Gen<>((r, d) -> Duration.ofMillis(r.nextLong(Long.MAX_VALUE)), Duration.class)
		));
		
		var cp = List.copyOf(gens);
		gens.add(new Gen<>((r, d) -> cp.get(r.nextInt(cp.size())).gen.apply(r, d), Object.class));
		return gens;
	}

//	@DataProvider
//	Object[][] genericCollections(){
//		List<Gen<?>> gens = makeBaseGens();
//
//		return Stream.of(
//			             gens.stream(),
//			             gens.stream().flatMap(g1 -> wrappGenInCollections(g1, 20)),
//			             gens.stream().flatMap(g1 -> wrappGenInCollections(g1, 20)).flatMap(g1 -> wrappGenInCollections(g1, 10))
//		             ).flatMap(a -> a.sorted(Comparator.comparing(Gen::name)))
//
//		             .map(g -> new Object[]{g}).toArray(Object[][]::new);
//	}
	
	private static Stream<Gen<?>> wrappGenInCollections(Gen<?> gen, int maxLen){
		return Stream.concat(wrappGenInCollections(gen, maxLen, false), wrappGenInCollections(gen, maxLen, true));
	}
	private static Stream<Gen<?>> wrappGenInCollections(Gen<?> gen, int maxLen, boolean nulls){
		var name = gen.name + (nulls? "?" : "");
		var lGen = new Gen<>((r, d) -> {
			var s = r.nextInt(maxLen);
			var l = new ArrayList<Object>(s);
			for(int i = 0; i<s; i++){
				l.add(nulls && r.nextBoolean()? null : gen.gen.apply(r, d));
			}
			if(!nulls && r.nextBoolean()){
				return List.copyOf(l);
			}
			return l;
		}, List.class, "List<" + name + ">");
		if(!nulls || !gen.type.isPrimitive()){
			var arrGen = new Gen<>((r, d) -> {
				var s = r.nextInt(maxLen);
				var l = Array.newInstance(gen.type, s);
				for(int i = 0; i<s; i++){
					Array.set(l, i, nulls && r.nextBoolean()? null : gen.gen.apply(r, d));
				}
				return l;
			}, (Class<Object>)gen.type.arrayType(), name + "[]");
			return Stream.of(lGen, arrGen);
		}
		return Stream.of(lGen);
	}
	
	private static <T> void generateSequences(List<T> elements, Consumer<List<T>> use){
		var l = new ArrayList<T>(elements.size());
		use.accept(l);
		generateSequences(elements, 0, l, use);
	}
	private static <T> void generateSequences(List<T> elements, int startIndex, List<T> currentSequence, Consumer<List<T>> use){
		if(startIndex == elements.size()){
			use.accept(currentSequence);
			return;
		}
		
		currentSequence.add(elements.get(startIndex));
		generateSequences(elements, startIndex + 1, new ArrayList<>(currentSequence), use);
		currentSequence.removeLast();
		
		generateSequences(elements, startIndex + 1, currentSequence, use);
	}
	
	
	@org.testng.annotations.DataProvider
	Object[][] genericCollectionsL1(){
		List<Gen<?>> gens = makeBaseGens();
		return gens.stream()
		           .sorted(Comparator.comparing(Gen::name))
		           .map(g -> new Object[]{g}).toArray(Object[][]::new);
	}
	@org.testng.annotations.DataProvider
	Object[][] genericCollectionsL2(){
		List<Gen<?>> gens = makeBaseGens();
		return gens.stream().flatMap(g1 -> wrappGenInCollections(g1, 20))
		           .sorted(Comparator.comparing(Gen::name))
		           .map(g -> new Object[]{g}).toArray(Object[][]::new);
	}
	@org.testng.annotations.DataProvider
	Object[][] genericCollectionsL3(){
		List<Gen<?>> gens = makeBaseGens();
		return gens.stream().flatMap(g1 -> wrappGenInCollections(g1, 20)).flatMap(g1 -> wrappGenInCollections(g1, 10))
		           .sorted(Comparator.comparing(Gen::name))
		           .map(g -> new Object[]{g}).toArray(Object[][]::new);
	}
	
	@Test(dependsOnGroups = "rootProvider", dependsOnMethods = "simpleGenericTest", ignoreMissingDependencies = true, dataProvider = "genericCollectionsL1")
	void genericStoreL1(Gen<?> generator) throws IOException{ genericStore(generator); }
	@Test(dependsOnMethods = {"simpleGenericTest", "genericStoreL1"}, dataProvider = "genericCollectionsL2")
	void genericStoreL2(Gen<?> generator) throws IOException{ genericStore(generator); }
	@Test(dependsOnMethods = {"simpleGenericTest", "genericStoreL1", "genericStoreL2"}, dataProvider = "genericCollectionsL3")
	void genericStoreL3(Gen<?> generator) throws IOException{ genericStore(generator); }
	
	private static final ThreadLocal<WeakReference<Cluster>> STRUCT_CACHE = ThreadLocal.withInitial(() -> new WeakReference<>(Cluster.emptyMem()));
	static               Cluster                             a;
	private static Cluster getCluster(){
		var d = STRUCT_CACHE.get().get();
		if(d == null){
			d = Cluster.emptyMem();
			STRUCT_CACHE.set(new WeakReference<>(d));
		}
		return d;
	}

//
//	private static final LateInit<DataLogger, RuntimeException> LOGGER       = LoggedMemoryUtils.createLoggerFromConfig();
//	private static final ThreadLocal<Cluster>                   STRUCT_CACHE = ThreadLocal.withInitial(() -> {
//		IOInterface mem = LoggedMemoryUtils.newLoggedMemory(Thread.currentThread().getName(), LOGGER);
//		try{
//			return Cluster.init(mem);
//		}catch(IOException e){
//			throw new RuntimeException(e);
//		}
//	});
//	private static Cluster getCluster(){
//		return STRUCT_CACHE.get();
//	}
	
	void genericStore(Gen<?> generator) throws IOException{
		//noinspection unchecked
		var pip = StandardStructPipe.of((Class<GenericContainer<Object>>)(Object)GenericContainer.class);
		
		record find(long siz, long seed, Throwable e){ }
		var oErrSeed = new RawRandom(generator.name.hashCode()).longs().limit(100).mapToObj(r -> {
			return async(() -> {
				var d = getCluster();
				
				Object value;
				try{
					value = generator.gen.apply(new RawRandom(r), d);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
				var wrapVal = new GenericContainer<>(value);
				try(var chD = AllocateTicket.bytes(64).submitAsTempMem(d)){
					TestUtils.checkPipeInOutEquality(chD.chunk(), pip, wrapVal);
					return null;
				}catch(Throwable e){
					long siz = -1;
					try{
						siz = StandardStructPipe.sizeOfUnknown(d, wrapVal, WordSpace.BYTE);
					}catch(Throwable ignore){ }
					if(siz == -1) try{
						siz = TextUtil.toString(value).length();
					}catch(Throwable ignore){ }
					if(siz == -1) siz = Long.MAX_VALUE;
					return new find(siz, r, e);
				}
			});
		}).toList().stream().map(CompletableFuture::join).filter(Objects::nonNull).reduce((a, b) -> a.siz<b.siz? a : b);
		
		if(oErrSeed.isEmpty()){
			LogUtil.println(generator, "ok");
			return;
		}
		
		var errVal = oErrSeed.get();
		var d      = getCluster();
		
		var value = generator.gen.apply(new RawRandom(errVal.seed), d);
		var err   = errVal.e;
		
		LogUtil.println("Writing Type:", generator.name, "value:\n", value);
		
		if(value instanceof List<?> l && l.stream().noneMatch(e -> e instanceof IOInstance.Unmanaged)){
			record siz(long siz, int strSiz, Object val, Throwable e){ }
			var res = new siz[1];
			
			try(var exec = Executors.newWorkStealingPool()){
				var backlog = new AtomicInteger();
				generateSequences(l, a -> {
					if(a.size() == l.size()) return;
					var arr = new ArrayList<>(a);
					backlog.incrementAndGet();
					UtilL.sleepWhile(() -> backlog.get()>32);
					exec.submit(() -> {
						backlog.decrementAndGet();
						var val = new GenericContainer<Object>(arr);
						var cl  = getCluster();
						var siz = StandardStructPipe.sizeOfUnknown(cl, val, WordSpace.BYTE);
						synchronized(res){
							if(res[0] != null && res[0].siz<=siz){
								return;
							}
						}
						try(var chD = AllocateTicket.bytes(64).submitAsTempMem(cl)){
							TestUtils.checkPipeInOutEquality(chD.chunk(), pip, val);
						}catch(Throwable e1){
							var strSiz = TextUtil.toString(arr).length();
							synchronized(res){
								if(res[0] == null || res[0].siz>siz || (res[0].siz == siz && res[0].strSiz>strSiz)){
									res[0] = new siz(siz, strSiz, arr, e1);
								}
							}
						}
					});
				});
			}
			
			if(res[0] != null){
				LogUtil.println("Found minified error:\n", res[0].val);
				err = res[0].e;
				value = res[0].val;
			}
		}
		
		err.printStackTrace();
		LogUtil.println("===================================");
		
		try(var chD = AllocateTicket.bytes(64).submitAsTempMem(d)){
			var ch = chD.chunk();
			var c  = new GenericContainer<>(value);
			pip.write(ch, c);
			var generic = pip.readNew(ch, null);
			assertThat(generic.value).isEqualTo(value);
			
			throw new AssertionError("Expected to fail");
		}
	}
	
}
