package com.lapissea.dfs.run;

import com.lapissea.dfs.SyntheticParameterizedType;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.IllegalField;
import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.exceptions.OutOfBitDepth;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.objects.text.AutoText;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOUnsafeValue;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.function.UnsafeConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static org.assertj.core.api.Assertions.assertThat;

public class GeneralTests{
	
	private static final Logger log = LoggerFactory.getLogger(GeneralTests.class);
	@AfterMethod
	public void cleanup(ITestResult method){ TestUtils.cleanup(method); }
	
	@Test
	void signedIO() throws IOException{
		for(NumberSize numberSize : NumberSize.FLAG_INFO){
			signedIO(numberSize, 0);
			signedIO(numberSize, numberSize.signedMaxValue);
			signedIO(numberSize, numberSize.signedMinValue);
			if(numberSize != NumberSize.VOID){
				var iter = new RawRandom(10).longs(numberSize.signedMinValue, numberSize.signedMaxValue).limit(1000).iterator();
				while(iter.hasNext()){
					signedIO(numberSize, iter.nextLong());
				}
			}
		}
	}
	
	void signedIO(NumberSize numberSize, long value) throws IOException{
		byte[] buf = new byte[8];
		numberSize.writeSigned(new ContentOutputStream.BA(buf), value);
		var read = numberSize.readSigned(new ContentInputStream.BA(buf));
		
		assertThat(read).describedAs(() -> value + " was not read or written correctly with " + numberSize).isEqualTo(value);
	}
	
	@org.testng.annotations.DataProvider(name = "chunkSizeNumbers")
	public static Object[][] chunkSizeNumbers(){
		return new Object[][]{{0}, {10}, {255}, {256}, {1000}, {10000}};
	}
	
	@Test(dataProvider = "chunkSizeNumbers")
	void chunkHeadIntegrity(long capacity) throws IOException{
		var provider     = TestUtils.testChunkProvider(capacity);
		var chunk        = AllocateTicket.bytes(capacity).submit(provider);
		var providerRead = DataProvider.newVerySimpleProvider(provider.getSource());
		var readChunk    = providerRead.getChunk(chunk.getPtr());
		
		assertThat(readChunk).isEqualTo(chunk);
	}
	
	@Test(dataProvider = "chunkSizeNumbers")
	void chunkBodyIntegrity(long capacity) throws IOException{
		var provider    = TestUtils.testChunkProvider(capacity);
		var chunkSecond = AllocateTicket.bytes(1).submit(provider);
		var chunk       = AllocateTicket.bytes(capacity).withNext(chunkSecond).submit(provider);
		//var chunk =AllocateTicket.bytes(capacity).submit(provider);
		
		byte[] bodyData = new byte[(int)capacity];
		for(int i = 0; i<bodyData.length; i++){
			bodyData[i] = (byte)i;
		}
		
		chunk.write(true, bodyData);
		
		var readBody = chunk.readAll();
		assertThat(readBody).containsExactly(bodyData);
	}
	
	@Test(dataProvider = "chunkSizeNumbers")
	void chunkBodyChainedIntegrity(long capacity) throws IOException{
		var provider    = TestUtils.testChunkProvider(capacity);
		var ticket      = AllocateTicket.bytes(capacity/2);
		var chunkSecond = ticket.submit(provider);
		var chunk       = ticket.withNext(chunkSecond).submit(provider);
		
		byte[] bodyData = new byte[(int)capacity];
		for(int i = 0; i<bodyData.length; i++){
			bodyData[i] = (byte)i;
		}
		
		chunk.write(true, bodyData);
		
		var readBody = chunk.readAll();
		assertThat(readBody).containsExactly(bodyData);
	}
	
	@Test
	void blankCluster() throws IOException{
		TestUtils.testCluster();
	}
	
	@org.testng.annotations.DataProvider(name = "lists")
	public static Object[][] lists(){
		return new Object[][]{{ContiguousIOList.class}, {LinkedIOList.class}};
	}
	
	@Test(dataProvider = "lists", groups = "lists", dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	<L extends IOInstance.Unmanaged<L> & IOList<Integer>> void listTestIntAdd(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Integer.class, list -> {
			list.add(69);
			list.add(420);
		}, true);
	}
	
	@Test(dataProvider = "lists", groups = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<Dummy>> void listTestSimpleAdd(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Dummy.class, list -> {
			list.add(new Dummy(69));
			list.add(new Dummy(420));
		}, false);
	}
	
	@Test(dataProvider = "lists", groups = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<Dummy>> void listSingleAdd(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Dummy.class, list -> {
			list.add(Dummy.first());
		}, false);
	}
	
	@Test(dataProvider = "lists", groups = "lists", dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	<L extends IOInstance.Unmanaged<L> & IOList<BooleanContainer>> void listBitValue(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, BooleanContainer.class, list -> {
			var rand = new Random(1);
			var vals = IntStream.range(0, 100)
			                    .mapToObj(i -> new BooleanContainer(rand.nextBoolean()))
			                    .collect(Collectors.toList());
			list.addAll(vals);
		}, true);
	}
	
	@Test(dataProvider = "lists", groups = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<Dummy>> void listInsert(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Dummy.class, list -> {
			list.add(new Dummy('1'));
			list.add(new Dummy('2'));
			list.add(new Dummy('3'));
			list.add(new Dummy('4'));
			
			list.add(1, new Dummy('5'));
		}, false);
	}
	
	@Test(dataProvider = "lists", groups = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<Dummy>> void listIndexRemove(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Dummy.class, list -> {
			list.add(new Dummy(69));
			list.add(new Dummy(360));
			list.add(new Dummy(420));
			list.remove(1);
			list.remove(1);
		}, false);
	}
	
	@Test(dataProvider = "lists", groups = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<Dummy>> void listComplexIndexRemove(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Dummy.class, list -> {
			list.add(Dummy.first());
			list.add(Dummy.auto());
			list.add(Dummy.auto());
			list.add(Dummy.auto());
			list.add(Dummy.auto());
			
			list.remove(2);
			list.remove(3);
			list.remove(0);
			list.remove(1);
			list.remove(0);
		}, false);
	}
	
	@Test(groups = {"hashMap", "rootProvider"})
	void testHashIOMap() throws IOException{
		TestUtils.ioMapComplianceSequence(
			TestInfo.of(),
			HashIOMap<Integer, Integer>::new,
			IOType.of(HashIOMap.class, Integer.class, Integer.class),
			map -> {
				map.put(0, 10);
				map.put(0, 11);
				map.put(1, 12);
				map.put(2, 13);
				map.put(16, 21);
				map.put(17, 22);
				map.put(18, 23);
				map.put(3, 31);
				map.put(4, 32);
				map.put(5, 33);
			}
		);
	}
	
	@Test(groups = {"rootProvider"})
	void rootIntProvide() throws IOException{
		var cl = Cluster.init(MemoryData.empty());
		cl.roots().provide(1, -123);
		int val = cl.roots().require(1, int.class);
		assertThat(val).isEqualTo(-123);
	}
	
	@Test(groups = {"rootProvider"})
	void rootIntegerProvide() throws IOException{
		var cl = Cluster.init(MemoryData.empty());
		cl.roots().provide(1, -123);
		Integer val = cl.roots().require(1, Integer.class);
		assertThat(val).isEqualTo(-123);
	}
	
	@Test(groups = {"rootProvider"})
	void rootIntRequest() throws IOException{
		var cl  = Cluster.init(MemoryData.empty());
		var val = cl.roots().request(1, int.class);
		assertThat(val).isEqualTo(0);
	}
	
	@Test
	void stringTest() throws IOException{
		var    provider = TestUtils.testChunkProvider();
		String data     = "this is a test!";
		
		var pipe  = StandardStructPipe.of(StringContainer.class);
		var chunk = AllocateTicket.bytes(64).submit(provider);
		
		var text = new StringContainer(data);
		TestUtils.checkPipeInOutEquality(chunk, pipe, text);
	}
	
	
	@org.testng.annotations.DataProvider
	public static Object[][] strings(){
		var rr = new RawRandom(123);
		return Stream.concat(
			Stream.of(
				"",
				"ABC123",
				"this works",
				"hey does this work",
				"dgasf_gfao124581z523tg eagdgisndgim315   qTGE254ghaerza573q6 wr gewr2$afas -.,/7-+41561552030,15.ds",
				"I ❤️ you",
				"\u00ff",
				IntStream.range(0, 1000).mapToObj(i -> "loong string!? (" + i + ")").collect(Collectors.joining(", "))
			),
			Stream.generate(
				() -> IntStream.range(0, rr.nextInt(20))
				               .mapToObj(i1 -> ((char)rr.nextInt(300)) + "")
				               .collect(Collectors.joining(""))
			).filter(StandardCharsets.UTF_8.newEncoder()::canEncode).limit(15)
		).map(o -> new Object[]{o}).toArray(Object[][]::new);
	}
	
	@Test(dataProvider = "strings")
	void autoTextTest(String data) throws IOException{
		var provider = TestUtils.testChunkProvider(data);
		var chunk    = AllocateTicket.bytes(64).submit(provider);
		
		var text = new AutoText(data);
		
		AutoText.Info.PIPE.write(chunk, text);
		var read = AutoText.Info.PIPE.readNew(chunk, null);
		
		assertThat(read).as(() -> "Text should match. Bytes: " + Iters.rangeMap(0, data.length(), i -> (int)data.charAt(i)))
		                .isEqualTo(text);
	}
	
	@Test(dataProvider = "strings")
	void ioUTF(String text) throws IOException{
		var mem = MemoryData.empty();
		mem.writeUTF(true, text);
		var read = mem.readUTF(0);
		assertThat(read).isEqualTo(text);
	}
	
	static <T, L extends IOInstance.Unmanaged<L> & IOList<T>> void listEqualityTest(TestInfo info, Class<L> listType, Class<T> typ, UnsafeConsumer<IOList<T>, IOException> session, boolean useCluster) throws IOException{
		TestUtils.ioListComplianceSequence(
			info, 64,
			(provider, reference, typeDef) -> {
				var lTyp = Struct.Unmanaged.ofUnmanaged(listType);
				return lTyp.make(provider, reference, typeDef);
			},
			IOType.of(listType, typ),
			session, useCluster
		);
	}
	
	@Test
	void lastChunkMoveOnFree() throws IOException{
		var cluster = TestUtils.testCluster();
		
		var frees = List.of(AllocateTicket.bytes(1).submit(cluster),
		                    AllocateTicket.bytes(1).submit(cluster),
		                    AllocateTicket.bytes(1).submit(cluster),
		                    AllocateTicket.bytes(1).submit(cluster),
		                    AllocateTicket.bytes(1).submit(cluster));
		AllocateTicket.bytes(1).submit(cluster);
		var padBlock = AllocateTicket.bytes(40).submit(cluster);
		
		var mm = cluster.getMemoryManager();
		assertThat(mm.getFreeChunks().size()).as("Exact free count required").isEqualTo(0);
		
		mm.free(List.of(frees.get(0), frees.get(2), frees.get(4)));
		assertThat(mm.getFreeChunks().size()).as("Exact free count required").isEqualTo(3);
		
		mm.free(List.of(frees.get(1), frees.get(3)));
		assertThat(mm.getFreeChunks().size()).as("Exact free count required").isEqualTo(1);
		
		assertThat(cluster.getSource().getIOSize()).isGreaterThan(padBlock.getPtr().getValue());
		
		padBlock.freeChaining();
		assertThat(mm.getFreeChunks().size()).as("Exact free count required").isEqualTo(1);
		
		assertThat(cluster.getSource().getIOSize()).as("Data should free the padBlock and end there exactly")
		                                           .isEqualTo(padBlock.getPtr().getValue());
	}
	
	@Test
	void allocateByChainWalkUpDefragment() throws IOException{
		var data  = TestUtils.testChunkProvider();
		var first = AllocateTicket.bytes(16).withExplicitNextSize(Optional.of(NumberSize.SHORT)).submit(data);
		AllocateTicket.bytes(0).submit(data);
		first.modifyAndSave(c -> {
			try{
				c.setNextPtr(AllocateTicket.bytes(0).submit(data).getPtr());
			}catch(OutOfBitDepth e){
				throw new RuntimeException(e);
			}
		});
		AllocateTicket.bytes(220).submit(data);
		
		byte[] bb = new byte[18];
		for(int i = 0; i<bb.length; i++) bb[i] = (byte)i;
		
		try(var io = first.io()){
			io.write(bb);
		}
		
		assertThat(first.chainLength()).as("Chain length should have allocated. Chain length is:").isEqualTo(2);
		assertThat(first.readAll()).containsExactly(bb);
	}
	
	@Test
	void recoverFromImproperlySizedFile() throws IOException{
		var data = TestUtils.testCluster();
		var t    = AllocateTicket.bytes(16);
		for(int i = 0; i<10; i++){
			t.submit(data);
		}
		ChunkPointer desiredPtr;
		{
			var c = t.submit(data);
			desiredPtr = c.getPtr();
			data.getMemoryManager().free(c);
		}
		//add invalid blank bytes to the end of the file to simulate sudden mid-allocation shutdown
		data.getSource().ioAt(data.getSource().getIOSize(), r -> r.write(new byte[100]));
		
		var c2        = t.submit(data);
		var actualPtr = c2.getPtr();
		
		assertThat(actualPtr).isEqualTo(desiredPtr);
	}
	
	@Test
	void bySizeSignedInt(){
		NumberSize.FLAG_INFO
			.flatMapToLong(v -> Iters.ofLongs(v.signedMinValue, v.signedMaxValue, v.maxSize))
			.flatMap(Iters.range(-5, 5)::addOverflowFiltered)
			.mapToIntOverflowFiltered()
			.forEach(e -> {
				var v = NumberSize.bySizeSigned(e);
				
				if(v.signedMinValue>e || e>v.signedMaxValue){
					throw new RuntimeException(e + " " + v);
				}
				if(v.prev().isPresentAnd(p -> p.signedMinValue<=e && e<=p.signedMaxValue)){
					throw new RuntimeException(e + " prev " + v);
				}
			});
	}
	
	@Test
	void bySizeSignedLong(){
		NumberSize.FLAG_INFO
			.flatMapToLong(v -> Iters.ofLongs(v.signedMinValue, v.signedMaxValue, v.maxSize))
			.flatMap(Iters.range(-5, 5)::addOverflowFiltered)
			.forEach(e -> {
				var v = NumberSize.bySizeSigned(e);
				
				if(v.signedMinValue>e || e>v.signedMaxValue){
					throw new RuntimeException(e + " " + v);
				}
				if(v.prev().isPresentAnd(p -> p.signedMinValue<=e && e<=p.signedMaxValue)){
					throw new RuntimeException(e + " prev " + v);
				}
			});
	}
	
	@Test
	void optionalValue() throws IOException, LockedFlagSet{
		interface Foo extends IOInstance.Def<Foo>{
			Optional<String> val();
			static Foo of(Optional<String> val){ return IOInstance.Def.of(Foo.class, val); }
		}
		try(var ignore = ConfigDefs.PRINT_COMPILATION.temporarySet(ConfigDefs.CompLogLevel.NONE)){
			Foo.of(Optional.empty());
		}
		
		var c   = TestUtils.testCluster();
		Foo def = c.roots().request("default", Foo.class);
		assertThat(def).isEqualTo(Foo.of(Optional.empty()));
		
		var helloWorld = Optional.of("Hello world! :)");
		c.roots().provide("some", Foo.of(helloWorld));
		var read = c.roots().request("some", Foo.class);
		assertThat(read).isEqualTo(Foo.of(helloWorld));
		
		c.roots().provide("none", Foo.of(Optional.empty()));
		Foo none = c.roots().request("none", Foo.class);
		assertThat(none).isEqualTo(Foo.of(Optional.empty()));
	}
	
	@Test(expectedExceptions = IllegalField.class)
	void classValue(){
		interface Foo extends IOInstance.Def<Foo>{
			@IONullability(NULLABLE)
			Class<?> val();
		}
		Struct.of(Foo.class, Struct.STATE_DONE);
	}
	
	@Test()
	void classValueWithOk() throws IOException{
		interface Foo extends IOInstance.Def<Foo>{
			@IONullability(NULLABLE)
			@IOUnsafeValue
			Class<?> val();
			static Foo of(Class<?> val){ return IOInstance.Def.of(Foo.class, val); }
		}
		
		Struct.of(Foo.class, Struct.STATE_DONE);
		
		
		var c   = TestUtils.testCluster();
		Foo def = c.roots().request("default", Foo.class);
		assertThat(def).isEqualTo(Foo.of(null));
		
		var helloWorld = String.class;
		c.roots().provide("some", Foo.of(helloWorld));
		var read = c.roots().request("some", Foo.class);
		assertThat(read).isEqualTo(Foo.of(helloWorld));
		
		c.roots().provide("none", Foo.of(null));
		Foo none = c.roots().request("none", Foo.class);
		assertThat(none).isEqualTo(Foo.of(null));
	}
	
	@Test
	void typeValueWithOk() throws IOException{
		interface Foo extends IOInstance.Def<Foo>{
			@IONullability(NULLABLE)
			@IOUnsafeValue
			Type val();
			static Foo of(Type val){ return IOInstance.Def.of(Foo.class, val); }
		}
		
		Struct.of(Foo.class, Struct.STATE_DONE);
		
		var c   = TestUtils.testCluster();
		Foo def = c.roots().request("default", Foo.class);
		assertThat(def).isEqualTo(Foo.of(null));
		
		var helloWorld = SyntheticParameterizedType.of(List.class, List.of(Integer.class));
		c.roots().provide("some", Foo.of(helloWorld));
		var read = c.roots().request("some", Foo.class);
		assertThat(read).isEqualTo(Foo.of(helloWorld));
		
		c.roots().provide("none", Foo.of(null));
		Foo none = c.roots().request("none", Foo.class);
		assertThat(none).isEqualTo(Foo.of(null));
	}
	
	@IOValue
	@IOInstance.StrFormat.Custom("<@val>")
	static class Custom1 extends IOInstance.Managed<Custom1>{
		int val = 123;
		public Custom1(){ }
	}
	
	@IOValue
	@IOInstance.StrFormat(name = false, fNames = false, filter = "val")
	static class Custom2 extends IOInstance.Managed<Custom2>{
		int val  = 123;
		int val2 = 1234;
		public Custom2(){ }
	}
	
	@Test
	void toStringInst1(){
		assertThat(new Custom1().toString()).isEqualTo("<123>");
	}
	@Test
	void toStringInst2(){
		assertThat(new Custom2().toString()).isEqualTo("{123}");
	}
	
	
}
