package com.lapissea.dfs.run;

import com.lapissea.dfs.SyntheticParameterizedType;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.exceptions.IllegalAnnotation;
import com.lapissea.dfs.exceptions.IllegalField;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.objects.text.AutoText;
import com.lapissea.dfs.tools.utils.ToolUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IOCompression;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeBiFunction;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.dfs.type.StagedInit.STATE_DONE;
import static com.lapissea.dfs.type.field.annotations.IOCompression.Type.RLE;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static com.lapissea.util.UtilL.async;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.internal.junit.ArrayAsserts.assertArrayEquals;

public class GeneralTypeHandlingTests{
	static{ Thread.startVirtualThread(Cluster::emptyMem); }
	
	@Test
	void simpleGenericTest() throws IOException{
		var pipe = StandardStructPipe.of(GenericContainer.class, STATE_DONE);
		
		TestUtils.testChunkProvider(TestInfo.of(), provider -> {
			var chunk = AllocateTicket.bytes(64).submit(provider);
			
			var container = new GenericContainer<>();
			
			container.value = new Dummy(123);
			
			pipe.write(chunk, container);
			var read = pipe.readNew(chunk, null);
			
			assertEquals(container, read);
			
			
			container.value = "This is a test.";
			
			pipe.write(chunk, container);
			read = pipe.readNew(chunk, null);
			
			assertEquals(container, read);
		});
	}
	
	@Test(dataProvider = "types")
	<T extends IOInstance<T>> void checkIntegrity(Struct<T> struct) throws IOException{
		if(struct instanceof Struct.Unmanaged){
			return;
		}
		
		StructPipe<T> pipe;
		try{
			pipe = StandardStructPipe.of(struct, STATE_DONE);
		}catch(MalformedStruct ignored){
			pipe = null;
		}
		if(pipe != null){
			pipe.checkTypeIntegrity();
		}
		try{
			pipe = FixedStructPipe.of(struct, STATE_DONE);
		}catch(MalformedStruct ignored){
			pipe = null;
		}
		if(pipe != null){
			pipe.checkTypeIntegrity();
		}
	}
	
	@DataProvider(name = "types")
	private static Object[][] types(){
		return Stream.of(
			             Reference.class,
			             AutoText.class,
			             Cluster.class,
			             ContiguousIOList.class,
			             LinkedIOList.class,
			             HashIOMap.class,
			             BooleanContainer.class,
			             IntContainer.class,
			             LongContainer.class,
			             Dummy.class
		             ).flatMap(p -> Stream.concat(Stream.of(p), Arrays.stream(p.getDeclaredClasses())))
		             .filter(IOInstance::isInstance)
		             .map(Struct::ofUnknown)
		             .map(o -> new Object[]{o})
		             .toArray(Object[][]::new);
	}
	
	
	public enum RandomEnum{A, B, C}
	
	public static class EnumContainer extends IOInstance.Managed<EnumContainer>{
		@IOValue
		RandomEnum r = RandomEnum.A;
	}
	
	public static byte[] make() throws IOException{
		var data = MemoryData.empty();
		Cluster.init(data).roots().request("hello!", EnumContainer.class);
		return data.readAll();
	}
	public static void use(byte[] data) throws IOException{
		var cluster = new Cluster(MemoryData.of(data));
		
		var r         = cluster.roots().builder("hello!").withGenerator(() -> { throw new RuntimeException(); });
		var container = (IOInstance<?>)r.request();
		
		IOField f = container.getThisStruct().getFields().requireByName("r");
		assertEquals(f.instanceToString(null, container, false).orElse(null), "A");
	}
	
	@Test
	void enumSerialization() throws Throwable{
		ToolUtils.simulateMissingClasses(
			List.of(n -> List.of(RandomEnum.class.getName(), EnumContainer.class.getName()).contains(n)),
			GeneralTypeHandlingTests.class.getMethod("make"),
			GeneralTypeHandlingTests.class.getMethod("use", byte[].class)
		);
	}
	
	@IOInstance.Def.Order({"name", "data"})
	public interface NamedBlob extends IOInstance.Def<NamedBlob>{
		String name();
		@IOCompression(RLE)
		byte[] data();
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void compressByteArray() throws IOException{
		TestUtils.testCluster(TestInfo.of(), provider -> {
			var blob = IOInstance.Def.of(
				NamedBlob.class, "Hello world",
				"""
					aaaaaaaaaayyyyyyyyyyyyyyyyyy lmaooooooooooooooooooooo
					""".getBytes(UTF_8)
			);
			provider.roots().provide(1, blob);
			var read = provider.roots().request(1, NamedBlob.class);

//			assertTrue("Compression not working", chunk.chainSize()<64);
			
			assertEquals(blob, read);
		});
	}
	
	@DataProvider(name = "templateTypes")
	Object[][] templateTypes(){
		return new Object[][]{
			{NamedBlob.class, new Object[]{"aaa", new byte[10]}},
			};
	}
	
	@Test(dataProvider = "templateTypes")
	<T extends IOInstance.Def<T>> void newTemplate(Class<T> type, Object[] args){
		if(args == null){
			IOInstance.Def.of(type);
		}else{
			IOInstance.Def.of(type, args);
		}
	}
	
	public enum E1b{
		E0
	}
	
	public enum E2b{
		E00,
		E01,
		E11
	}
	
	public enum E3b{
		E000,
		E001,
		E010,
		E011,
		E100,
		E101,
		E110,
		E111,
	}
	
	public enum E4b{
		E0000,
		E0001,
		E0010,
		E0011,
		E0100,
		E0101,
		E0110,
		E0111,
		E1000,
		E1001,
		E1010,
		E1011,
		E1100,
		E1101,
		E1110,
		E1111,
	}
	
	public interface E1 extends IOInstance.Def<E1>{
		List<E1b> nums();
	}
	
	public interface E2 extends IOInstance.Def<E2>{
		List<E2b> nums();
	}
	
	public interface E3 extends IOInstance.Def<E3>{
		List<E3b> nums();
	}
	
	public interface E4 extends IOInstance.Def<E4>{
		List<E4b> nums();
	}
	
	@DataProvider(name = "enumHolders")
	Object[][] enumHolders(){
		return new Object[][]{
			{E1.class, E1b.class},
			{E2.class, E2b.class},
			{E3.class, E3b.class},
			{E4.class, E4b.class},
			};
	}
	
	@Test(dataProvider = "enumHolders")
	<T extends IOInstance.Def<T>, E extends Enum<E>> void enumIntegrity(Class<T> type, Class<E> eType){
		var r    = new Random(69420);
		var info = EnumUniverse.of(eType);
		var pip  = StandardStructPipe.of(type);
		var data = com.lapissea.dfs.core.DataProvider.newVerySimpleProvider();
		for(int i = 0; i<1000; i++){
			
			var set = IOInstance.Def.of(
				type,
				r.ints(r.nextLong(i + 1) + 1, 0, info.size())
				 .mapToObj(info::get)
				 .toList()
			);
			
			try(var io = data.getSource().io()){
				io.setPos(0);
				pip.write(data, io, set);
				io.setPos(0);
				var read = pip.readNew(data, io, null);
				assertEquals("Failed equality on " + i, set, read);
			}catch(IOException e){
				throw new RuntimeException(i + "", e);
			}
		}
		
		
	}
	
	record LongTyp<T extends IOInstance.Def<T>>(Class<T> typ, LongFunction<T> func){
		LongTyp{ StandardStructPipe.of(typ); }
		@Override
		public String toString(){
			return typ.getSimpleName();
		}
	}
	
	@DataProvider
	Object[][] longTypes(){
		interface Dur extends IOInstance.Def<Dur>{
			Duration val();
		}
		interface Inst extends IOInstance.Def<Inst>{
			Instant val();
		}
		interface LocDate extends IOInstance.Def<LocDate>{
			LocalDate val();
		}
		interface LocTime extends IOInstance.Def<LocTime>{
			LocalTime val();
		}
		return new Object[][]{
			{new LongTyp<>(Dur.class, l -> IOInstance.Def.of(Dur.class, Duration.ofMillis(l)))},
			{new LongTyp<>(Inst.class, l -> IOInstance.Def.of(Inst.class, Instant.ofEpochMilli(l)))},
			{new LongTyp<>(LocDate.class, l -> IOInstance.Def.of(LocDate.class, LocalDate.ofEpochDay(l%365243219162L)))},
			{new LongTyp<>(LocTime.class, l -> IOInstance.Def.of(LocTime.class, LocalTime.ofNanoOfDay(Math.abs(l)%86399999999999L)))},
			};
	}
	
	@Test(dataProvider = "longTypes")
	<T extends IOInstance.Def<T>> void testLongClass(LongTyp<T> typ) throws IOException{
		TestUtils.testChunkProvider(TestInfo.of(), provider -> {
			var hold  = StandardStructPipe.of(typ.typ);
			var chunk = AllocateTicket.bytes(64).submit(provider);
			
			for(var val : (IterablePP<T>)() -> new Random(42069).longs(10000).mapToObj(typ.func).iterator()){
				hold.write(chunk, val);
				var read = hold.readNew(chunk, null);
				
				assertEquals(read, val);
			}
		});
	}
	
	@Test
	void testLocalDateTime() throws IOException{
		interface LocDateTime extends IOInstance.Def<LocDateTime>{
			LocalDateTime val();
		}
		TestUtils.testChunkProvider(TestInfo.of(), provider -> {
			var hold  = StandardStructPipe.of(LocDateTime.class);
			var chunk = AllocateTicket.bytes(64).submit(provider);
			var rand  = new Random(42096);
			
			for(int i = 0; i<10000; i++){
				var tim = LocalDateTime.of(
					LocalDate.ofEpochDay(rand.nextLong()%365243219162L),
					LocalTime.ofNanoOfDay(Math.abs(rand.nextLong())%86399999999999L)
				);
				var val = IOInstance.Def.of(LocDateTime.class, tim);
				
				hold.write(chunk, val);
				var read = hold.readNew(chunk, null);
				
				assertEquals(read, val);
			}
		});
	}
	
	sealed interface Seal{
		
		final class A extends IOInstance.Managed<A> implements Seal{
			@IOValue
			int a;
			public A(){ }
			public A(int a){
				this.a = a;
			}
		}
		
		final class B extends IOInstance.Managed<B> implements Seal{
			@IOValue
			Instant b;
			public B(){ }
			public B(Instant b){
				this.b = b;
			}
		}
		
		final class C extends IOInstance.Managed<C> implements Seal{
			@IOValue
			int c1;
			@IOValue
			int c2;
			@IOValue
			int c3;
			
			public C(int c1, int c2, int c3){
				this.c1 = c1;
				this.c2 = c2;
				this.c3 = c3;
			}
			public C(){ }
		}
		
		final class D extends IOInstance.Managed<D> implements Seal{
			@IOValue
			float d;
			public D(){ }
			public D(float d){
				this.d = d;
			}
		}
		
	}
	
	@Test
	void wrapperListing(){
		var actual = Set.copyOf(FieldCompiler.getWrapperTypes());
		var expected = Set.of(
			byte[].class, int[].class, float[].class, boolean[].class,
			String.class, Duration.class, Instant.class,
			LocalDate.class, LocalTime.class, LocalDateTime.class
		);
		
		assertEquals(expected, actual);
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void testSealedType() throws IOException{
		TestUtils.testCluster(TestInfo.of(), provider -> {
			@IOInstance.Def.Order({"seal1", "seal2"})
			interface Container extends IOInstance.Def<Container>{
				Seal seal1();
				Seal seal2();
			}
			
			var hold  = StandardStructPipe.of(Container.class);
			var chunk = AllocateTicket.bytes(64).submit(provider);
			{
				var val = IOInstance.Def.of(Container.class, new Seal.A(69), new Seal.C(1, 2, 3));
				
				hold.write(chunk, val);
				var read = hold.readNew(chunk, null);
				
				assertEquals(read, val);
			}
			{
				var val = IOInstance.Def.of(Container.class, new Seal.C(4, 5, -6), new Seal.D(420.69F));
				
				hold.write(chunk, val);
				var read = hold.readNew(chunk, null);
				
				assertEquals(read, val);
			}
			chunk.freeChaining();
			
			IOList<Seal> list = provider.roots().request("list", IOList.class, Seal.class);
			list.add(new Seal.A(69));
			list.add(new Seal.B(Instant.now()));
			list.add(new Seal.C(4, 2, 0));
		});
	}
	
	private static class OrderTestType extends IOInstance.Managed<OrderTestType>{
		
		private enum EnumThing{
			PUSH,
			SET_CLASS_LOADER_NAME,
			SET_MODULE_NAME, SET_MODULE_VERSION,
			SET_CLASS_NAME, SET_METHOD_NAME,
			SET_FILE_NAME, SET_FILE_EXTENSION
		}
		
		private sealed interface FileName{
			record Raw(int id) implements FileName{ }
			
			record Ext(int id) implements FileName{ }
		}
		
		@IOValue
		private List<EnumThing>  commands;
		@IOValue
		private List<NumberSize> sizes;
		@IOValue
		private byte[]           numbers;
		
		@IOValue
		@IODependency.VirtualNumSize
		@IOValue.Unsigned
		private int head, ignoredFrames, diffBottomCount;
		
		public OrderTestType(){ }
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void orderTestType() throws IOException{
		var typ = new OrderTestType();
		typ.commands = List.of(OrderTestType.EnumThing.SET_CLASS_NAME, OrderTestType.EnumThing.SET_METHOD_NAME);
		typ.sizes = List.of(NumberSize.BYTE, NumberSize.LONG, NumberSize.VOID);
		typ.numbers = new byte[]{1, 2, 3};
		typ.head = 3;
		typ.ignoredFrames = 0;
		typ.diffBottomCount = 10_000;
		
		var cluster = Cluster.emptyMem();
		cluster.roots().provide(1, typ);
		var read = cluster.roots().require(1, OrderTestType.class);
		assertEquals(typ, read);
	}
	
	
	public static class ValuesAnn extends IOInstance.Managed<ValuesAnn>{
		@IOValue
		IOType       link;
		@IOValue
		String       str;
		@IOValue
		List<String> list;
		@IOValue
		int[]        ints;
	}
	
	@IOValue
	public static class ClassAnn extends IOInstance.Managed<ClassAnn>{
		IOType       link;
		String       str;
		List<String> list;
		int[]        ints;
	}
	
	@Test
	void annotationLocation() throws IOException{
		var aPipe = StandardStructPipe.of(ValuesAnn.class);
		var bPipe = StandardStructPipe.of(ClassAnn.class);
		{
			var an = aPipe.getSpecificFields().stream().map(IOField::getName).toList();
			var bn = bPipe.getSpecificFields().stream().map(IOField::getName).toList();
			assertEquals(an, bn);
		}
		
		var a = new ValuesAnn();
		a.link = IOType.of(List.class, IOType.of(String.class));
		a.str = "test";
		a.list = List.of("idk");
		a.ints = new int[1];
		
		var prov = com.lapissea.dfs.core.DataProvider.newVerySimpleProvider();
		var val  = AllocateTicket.bytes(128).submit(prov);
		aPipe.write(val, a);
		
		ClassAnn b = bPipe.readNew(val, null);
		
		assertEquals(a.link, b.link);
		assertEquals(a.str, b.str);
		assertEquals(a.list, b.list);
		assertArrayEquals(a.ints, b.ints);
	}
	
	@Test(expectedExceptions = IllegalField.class)
	void badField(){
		@IOValue
		class Foo extends IOInstance.Managed<Foo>{
			ClassLoader bad;
		}
		Struct.of(Foo.class, STATE_DONE);
	}
	
	@Test(expectedExceptions = IllegalAnnotation.class)
	void badNullability(){
		@IOValue
		class Foo extends IOInstance.Managed<Foo>{
			@IONullability(NULLABLE)
			int bad;
		}
		Struct.of(Foo.class, STATE_DONE);
	}
	
	public interface GenericChild<A> extends IOInstance.Def<GenericChild<A>>{
		ContiguousIOList<A> list();
	}
	
	
	public interface GenericArg extends IOInstance.Def<GenericArg>{
		GenericChild<String> strings();
//		GenericChild<ObjectID> ids();
//		GenericChild<T> generic();
	}
	
	@Test
	void genericPropagation() throws IOException{
		var d    = com.lapissea.dfs.core.DataProvider.newVerySimpleProvider();
		var data = IOInstance.Def.of(GenericArg.class);
		data.allocateNulls(d, null);
		var strings = data.strings().list();
		strings.add("foo");
		strings.add("bar");
		assertEquals(List.of("foo", "bar"), strings.collectToList());
		assertEquals(IOType.of(ContiguousIOList.class, String.class), strings.getTypeDef());
	}
	
	@Test(dependsOnMethods = "genericPropagation", dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void genericChildStore() throws IOException{
		var d = Cluster.emptyMem();
		var data = d.roots().request(1, () -> {
			var v = IOInstance.Def.of(GenericArg.class);
			v.allocateNulls(d, null);
			return v;
		});
		data.strings().list().addAll(List.of("foo", "bar"));
	}
	
	sealed interface RecursiveSeal{
		
		final class A extends IOInstance.Managed<A> implements RecursiveSeal{
			@IOValue
			int a;
		}
		
		final class B extends IOInstance.Managed<B> implements RecursiveSeal{
			@IOValue
			int           b;
			@IOValue
			RecursiveSeal recursiveSeal;
		}
		
	}
	
	@Test(timeOut = 2000)
	void recursiveSeal(){
		StandardStructPipe.of(RecursiveSeal.B.class).waitForStateDone();
	}
	
	@DataProvider
	Object[][] intCollections(){
		return new Object[][]{
			{int[].class, new int[]{}},
			{int[].class, new int[]{-1}},
			{int[].class, new int[]{0, 1, 412531253}},
			{Integer[].class, new Integer[]{}},
			{Integer[].class, new Integer[]{0}},
			{Integer[].class, new Integer[]{10, -156245, Integer.MAX_VALUE}},
			{SyntheticParameterizedType.of(List.class, List.of(Integer.class)), List.of()},
			{SyntheticParameterizedType.of(List.class, List.of(Integer.class)), List.of(0)},
			{SyntheticParameterizedType.of(List.class, List.of(Integer.class)), List.of(10, -156245, Integer.MAX_VALUE)},
			};
	}
	
	Map<Type, Class<?>> intCollectionsTypes = new HashMap<>();
	
	@SuppressWarnings("unchecked")
	@Test(dataProvider = "intCollections")
	<T extends IOInstance<T>> void intCollections(Type typ, Object val) throws IOException{
		var type = (Class<T>)intCollectionsTypes.computeIfAbsent(
			typ,
			t -> TestUtils.generateIOManagedClass("IntCollectionsType_" + t.getTypeName().hashCode(), List.of(
				new TestUtils.Prop("val", t, null)
			))
		);
		
		var struct = Struct.of(type);
		var pipe   = StandardStructPipe.of(struct);
		
		var instance = struct.make();
		((IOField<T, Object>)struct.getFields().requireByName("val")).set(null, instance, val);
		
		var prov  = com.lapissea.dfs.core.DataProvider.newVerySimpleProvider();
		var chunk = AllocateTicket.bytes(64).submit(prov);
		
		pipe.write(chunk, instance);
		
		var read = pipe.readNew(chunk, null);
		
		Assert.assertEquals(read, instance);
	}
	
	record Gen<T>(UnsafeBiFunction<RandomGenerator, com.lapissea.dfs.core.DataProvider, T, IOException> gen, Class<T> type, String name){
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
				var l = new ContiguousIOList<Integer>(d, AllocateTicket.bytes(64).submit(d), IOType.of(ContiguousIOList.class, int.class));
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
	
	
	@DataProvider
	Object[][] genericCollectionsL1(){
		List<Gen<?>> gens = makeBaseGens();
		return gens.stream()
		           .sorted(Comparator.comparing(Gen::name))
		           .map(g -> new Object[]{g}).toArray(Object[][]::new);
	}
	@DataProvider
	Object[][] genericCollectionsL2(){
		List<Gen<?>> gens = makeBaseGens();
		return gens.stream().flatMap(g1 -> wrappGenInCollections(g1, 20))
		           .sorted(Comparator.comparing(Gen::name))
		           .map(g -> new Object[]{g}).toArray(Object[][]::new);
	}
	@DataProvider
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
	
	void genericStore(Gen<?> generator) throws IOException{
		//noinspection unchecked
		var pip = StandardStructPipe.of((Class<GenericContainer<Object>>)(Object)GenericContainer.class);
		
		record find(long siz, long seed, Throwable e){ }
		var oErrSeed = new RawRandom(generator.name.hashCode()).longs().limit(100).mapToObj(r -> {
			return async(() -> {
				var    d = Cluster.emptyMem();
				Object value;
				try{
					value = generator.gen.apply(new RawRandom(r), d);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
				var wrapVal = new GenericContainer<>(value);
				try{
					var ch = AllocateTicket.bytes(128).submit(d);
					pip.write(ch, wrapVal);
					var read = pip.readNew(ch, null);
					Assert.assertEquals(read, wrapVal);
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
		var d      = Cluster.emptyMem();
		var value  = generator.gen.apply(new RawRandom(errVal.seed), d);
		var err    = errVal.e;
		
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
						var cl  = Cluster.emptyMem();
						var siz = StandardStructPipe.sizeOfUnknown(cl, val, WordSpace.BYTE);
						synchronized(res){
							if(res[0] != null && res[0].siz<=siz){
								return;
							}
						}
						try{
							var ch = AllocateTicket.bytes(128).submit(cl);
							pip.write(ch, val);
							var generic = pip.readNew(ch, null);
							Assert.assertEquals(generic, val);
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
		
		var ch = AllocateTicket.bytes(128).submit(d);
		var c  = new GenericContainer<>(value);
		pip.write(ch, c);
		var generic = pip.readNew(ch, null);
		Assert.assertEquals(generic.value, value);
		
		Assert.fail("Expected to fail");
	}
}
