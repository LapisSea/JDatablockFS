package com.lapissea.dfs.run;

import com.lapissea.dfs.SyntheticParameterizedType;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.exceptions.IllegalAnnotation;
import com.lapissea.dfs.exceptions.IllegalField;
import com.lapissea.dfs.exceptions.UnsupportedStructLayout;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.logging.Log;
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
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IOCompression;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.ref.Cleaner;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import static com.lapissea.dfs.type.StagedInit.STATE_DONE;
import static com.lapissea.dfs.type.field.annotations.IOCompression.Type.RLE;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

public class GeneralTypeHandlingTests{
	
	@AfterMethod
	public void cleanup(ITestResult method){ TestUtils.cleanup(method); }
	
	@Test(dataProvider = "types")
	<T extends IOInstance<T>> void checkIntegrity(Struct<T> struct) throws IOException{
		if(struct instanceof Struct.Unmanaged){
			return;
		}
		
		StructPipe<T> pipe;
		pipe = StandardStructPipe.of(struct, STATE_DONE);
		pipe.checkTypeIntegrity();
		try{
			pipe = FixedStructPipe.of(struct, STATE_DONE);
			pipe.checkTypeIntegrity();
		}catch(UnsupportedStructLayout e){
			Log.info("Unsupported fixed layout for {}:\n\t{}", struct, e);
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
	public static <T extends IOInstance<T>> void use(byte[] data) throws IOException{
		var cluster = new Cluster(MemoryData.of(data));
		
		var r = cluster.roots().builder("hello!").withGenerator(() -> { throw new RuntimeException(); });
		//noinspection unchecked
		var container = (T)r.request();
		
		var f = container.getThisStruct().getFields().requireByName("r");
		assertThat(f.instanceToString(null, container, false)).hasValue("A");
	}
	
	@Test
	void enumSerialization() throws Throwable{
		ToolUtils.simulateMissingClasses(
			List.of(n -> List.of(RandomEnum.class.getName(), EnumContainer.class.getName()).contains(n)),
			GeneralTypeHandlingTests.class.getMethod("make"),
			GeneralTypeHandlingTests.class.getMethod("use", byte[].class)
		);
	}
	
	@IOInstance.Order({"name", "data"})
	public interface NamedBlob extends IOInstance.Def<NamedBlob>{
		String name();
		@IOCompression(RLE)
		byte[] data();
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void compressByteArray() throws IOException{
		var provider = TestUtils.testCluster();
		var blob = IOInstance.Def.of(
			NamedBlob.class, "Hello world",
			"""
				aaaaaaaaaayyyyyyyyyyyyyyyyyy lmaooooooooooooooooooooo
				""".getBytes(UTF_8)
		);
		TestUtils.checkRootsInOutEquality(provider, 1, blob);
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
	<T extends IOInstance.Def<T>, E extends Enum<E>> void enumIntegrity(Class<T> type, Class<E> eType) throws IOException{
		var r    = new Random(69420);
		var info = EnumUniverse.of(eType);
		var pip  = StandardStructPipe.of(type);
		var data = com.lapissea.dfs.core.DataProvider.newVerySimpleProvider();
		var mem  = AllocateTicket.bytes(64).submit(data);
		for(int i = 0; i<1000; i++){
			
			var set = IOInstance.Def.of(
				type,
				r.ints(r.nextLong(i + 1) + 1, 0, info.size())
				 .mapToObj(info::get)
				 .toList()
			);
			
			try{
				TestUtils.checkPipeInOutEquality(mem, pip, set);
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
	<T extends IOInstance.Def<T>> void testLongClass(LongTyp<T> typ){
		var hold = StandardStructPipe.of(typ.typ);
		var chunks = ThreadLocal.withInitial(() -> {
			try{
				var provider = Cluster.emptyMem();
				return AllocateTicket.bytes(64).submit(provider);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		});
		TestUtils.randomBatch("testLongClass" + typ, 10_000, (r, i) -> {
			var chunk = chunks.get();
			var l     = r.nextLong();
			var val   = typ.func.apply(l);
			TestUtils.checkPipeInOutEquality(chunk, hold, val);
		});
	}
	
	@Test
	void testLocalDateTime() throws IOException{
		interface LocDateTime extends IOInstance.Def<LocDateTime>{
			LocalDateTime val();
		}
		var provider = TestUtils.testChunkProvider();
		var hold     = StandardStructPipe.of(LocDateTime.class);
		var chunk    = AllocateTicket.bytes(64).submit(provider);
		var rand     = new Random(42096);
		
		for(int i = 0; i<10000; i++){
			var tim = LocalDateTime.of(
				LocalDate.ofEpochDay(rand.nextLong()%365243219162L),
				LocalTime.ofNanoOfDay(Math.abs(rand.nextLong())%86399999999999L)
			);
			var val = IOInstance.Def.of(LocDateTime.class, tim);
			TestUtils.checkPipeInOutEquality(chunk, hold, val);
		}
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
		var actual = FieldCompiler.getWrapperTypes();
		assertThat(actual).containsExactlyInAnyOrder(
			byte[].class, int[].class, float[].class, boolean[].class,
			String.class, Duration.class, Instant.class,
			LocalDate.class, LocalTime.class, LocalDateTime.class, ByteBuffer.class
		);
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void testSealedType() throws IOException{
		@IOInstance.Order({"seal1", "seal2"})
		interface Container extends IOInstance.Def<Container>{
			Seal seal1();
			Seal seal2();
			static Container of(Seal seal1, Seal seal2){
				return IOInstance.Def.of(Container.class, seal1, seal2);
			}
		}
		
		var provider = TestUtils.testCluster();
		var pipe     = StandardStructPipe.of(Container.class);
		var chunk    = AllocateTicket.bytes(64).submit(provider);
		TestUtils.checkPipeInOutEquality(chunk, pipe, Container.of(new Seal.A(69), new Seal.C(1, 2, 3)));
		TestUtils.checkPipeInOutEquality(chunk, pipe, Container.of(new Seal.C(4, 5, -6), new Seal.D(420.69F)));
		chunk.freeChaining();
		
		var now = Instant.now();
		
		IOList<Seal> list = provider.roots().request("list", IOList.class, Seal.class);
		list.add(new Seal.A(69));
		list.add(new Seal.B(now));
		list.add(new Seal.C(4, 2, 0));
		assertThat(list).containsExactly(new Seal.A(69), new Seal.B(now), new Seal.C(4, 2, 0));
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
		
		TestUtils.checkRootsInOutEquality(typ);
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
			var an = aPipe.getSpecificFields().mapped(IOField::getName);
			var bn = bPipe.getSpecificFields().mapped(IOField::getName);
			assertThat(an).containsExactlyElementsOf(bn);
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
		
		assertThat(a).extracting("link", "str", "list", "ints")
		             .containsExactly(b.link, b.str, b.list, b.ints);
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
		void list(ContiguousIOList<A> list);
	}
	
	
	public interface GenericArg extends IOInstance.Def<GenericArg>{
		GenericChild<String> strings();
		void strings(GenericChild<String> strings);
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
		assertThat(strings).containsExactly("foo", "bar");
		assertThat(strings.getTypeDef()).isEqualTo(IOType.of(ContiguousIOList.class, String.class));
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
		
		TestUtils.checkPipeInOutEquality(pipe, instance);
	}
	
	@Test
	void classUnload(){
		var unloadedStatus = loadTestClass();
		for(int i = 0; i<20; i++){
			System.gc();
			UtilL.sleep(1);
			if(unloadedStatus[0]){
				return;
			}
			UtilL.sleep(10);
			LogUtil.println("retrying...", i);
		}
		Assert.fail("Class not unloaded!");
	}
	<T extends IOInstance<T>> boolean[] loadTestClass(){
		var def = new TempClassGen.ClassGen(
			"testunload",
			List.of(new TempClassGen.FieldGen("hi", TempClassGen.VisiblityGen.PUBLIC, false, int.class, List.of(Annotations.make(IOValue.class)), null)),
			Set.of(new TempClassGen.CtorType.Empty()),
			IOInstance.Managed.class,
			List.of()
		);
		//noinspection unchecked
		var typ = (Class<T>)TempClassGen.gen(def);
		
		var unloadedStatus = new boolean[]{false};
		Cleaner.create().register(typ, () -> unloadedStatus[0] = true);
		
		var struct = Struct.of(typ, STATE_DONE);
		StandardStructPipe.of(struct, STATE_DONE);
		return unloadedStatus;
	}
	
}
