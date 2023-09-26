package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.config.ConfigDefs;
import com.lapissea.cfs.exceptions.IllegalAnnotation;
import com.lapissea.cfs.exceptions.IllegalField;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.FixedStructPipe;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.tools.utils.ToolUtils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.IOType;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.compilation.FieldCompiler;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IOCompression;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.IterablePP;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.StagedInit.STATE_DONE;
import static com.lapissea.cfs.type.field.annotations.IOCompression.Type.RLE;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.internal.junit.ArrayAsserts.assertArrayEquals;

public class GeneralTypeHandlingTests{
	
	@IOValue
	public static class Deps extends IOInstance.Managed<Deps>{
		
		@IODependency("b")
		public int a;
		
		@IODependency("c")
		public int b;
		
		public int c;
		
		@IODependency({"c", "b"})
		public int d;
	}
	
	public static class Arr extends IOInstance.Managed<Arr>{
		@IOValue
		public float[] arr;
	}
	
	@DataProvider(name = "genericObjects")
	static Object[][] genericObjects(){
		var deps = new Deps();
		deps.a = 1;
		deps.b = 2;
		deps.c = 3;
		deps.d = 4;
		var arr = new Arr();
		arr.arr = new float[]{1, 2, 3, 4, 5};
		return new Object[][]{{Dummy.first()}, {deps}, {arr}};
	}
	
	@Test(dataProvider = "genericObjects", dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	<T extends IOInstance<T>> void genericStorage(T obj) throws IOException{
		TestUtils.testCluster(TestInfo.of(obj), ses -> {
			var ls = ses.getRootProvider().<IOList<GenericContainer<?>>>builder("list").withType(IOType.ofFlat(
				LinkedIOList.class,
				GenericContainer.class, Object.class
			)).request();
			
			var c = new GenericContainer<>(obj);
			ls.clear();
			ls.add(c);
			var read = ls.get(0).value;
			assertEquals(obj, read);
		});
	}
	
	@Test
	void genericTest() throws IOException{
		TestUtils.testChunkProvider(TestInfo.of(), provider -> {
			var pipe = StandardStructPipe.of(GenericContainer.class);
			
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
		Cluster.init(data).getRootProvider().request("hello!", EnumContainer.class);
		return data.readAll();
	}
	public static void use(byte[] data) throws IOException{
		var cluster = new Cluster(MemoryData.builder().withRaw(data).build());
		
		var r         = cluster.getRootProvider().builder("hello!").withGenerator(() -> { throw new RuntimeException(); });
		var container = (IOInstance<?>)r.request();
		
		IOField f = container.getThisStruct().getFields().byName("r").orElseThrow();
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
			provider.getRootProvider().provide(new ObjectID("obj"), blob);
			var read = provider.getRootProvider().request("obj", NamedBlob.class);

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
		var data = com.lapissea.cfs.chunk.DataProvider.newVerySimpleProvider();
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
			
			IOList<Seal> list = provider.getRootProvider().request("list", IOList.class, Seal.class);
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
		
		var cluster = Cluster.emptyMem();
		var roots   = cluster.getRootProvider();
		
		var typ = new OrderTestType();
		typ.commands = List.of(OrderTestType.EnumThing.SET_CLASS_NAME, OrderTestType.EnumThing.SET_METHOD_NAME);
		typ.sizes = List.of(NumberSize.BYTE, NumberSize.LONG, NumberSize.VOID);
		typ.numbers = new byte[]{1, 2, 3};
		typ.head = 3;
		typ.ignoredFrames = 0;
		typ.diffBottomCount = 10_000;
		
		roots.provide("foo", typ);
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
		
		var prov = com.lapissea.cfs.chunk.DataProvider.newVerySimpleProvider();
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
		var d    = com.lapissea.cfs.chunk.DataProvider.newVerySimpleProvider();
		var data = IOInstance.Def.of(GenericArg.class);
		data.allocateNulls(d, null);
		var strings = data.strings().list();
		strings.add("foo");
		strings.add("bar");
		assertEquals(List.of("foo", "bar"), strings.collectToList());
		assertEquals(IOType.of(ContiguousIOList.class, String.class), strings.getTypeDef());
	}
	
	static{
		ConfigDefs.LOG_LEVEL.set(Log.LogLevel.TRACE);
	}
	
	@Test(dependsOnMethods = "genericPropagation", dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void genericStore() throws IOException{
		var d = Cluster.emptyMem();
		var data = d.getRootProvider().request(new ObjectID("obj"), () -> {
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
}
