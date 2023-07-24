package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.OutOfBitDepth;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDef;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_CALL_THREAD;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;
import static com.lapissea.util.LogUtil.Init.USE_TIME_DELTA;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class GeneralTests{
	
	@BeforeSuite
	void init() throws IOException{
		LogUtil.Init.attach(USE_CALL_POS|USE_CALL_THREAD|USE_TIME_DELTA|USE_TABULATED_HEADER);
		
		List<Struct<?>> tasks = new ArrayList<>();
		
		try{
			for(var typ : List.of(
				Chunk.class, Reference.class, AutoText.class, ContiguousIOList.class,
				LinkedIOList.class, HashIOMap.class, TypeDef.class, TypeLink.class, ObjectID.class
			)){
				tasks.add(Struct.ofUnknown(typ));
			}
			
			tasks.forEach(t -> {
				LogUtil.println("waiting for", t);
				t.waitForStateDone();
			});
			
			for(var prov : List.of(DataProvider.newVerySimpleProvider(), Cluster.emptyMem())){
				try(var dummy = AllocateTicket.bytes(10).submit(prov).io()){
					dummy.write(1);
					dummy.writeInt4(69);
					dummy.write(new byte[5]);
				}
			}
		}catch(Throwable e){
			e.printStackTrace();
		}
	}
	
	@Test
	void signedIO() throws IOException{
		for(NumberSize numberSize : NumberSize.FLAG_INFO){
			signedIO(numberSize, 0);
			signedIO(numberSize, numberSize.signedMaxValue);
			signedIO(numberSize, numberSize.signedMinValue);
			if(numberSize != NumberSize.VOID){
				var iter = new Random(10).longs(numberSize.signedMinValue, numberSize.signedMaxValue).limit(1000).iterator();
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
		
		assertEquals(read, value, value + " was not read or written correctly with " + numberSize);
	}
	
	@org.testng.annotations.DataProvider(name = "chunkSizeNumbers")
	public static Object[][] chunkSizeNumbers(){
		return new Object[][]{{0}, {10}, {255}, {256}, {1000}, {10000}};
	}
	
	@Test(dataProvider = "chunkSizeNumbers")
	void chunkHeadIntegrity(long capacity) throws IOException{
		TestUtils.testChunkProvider(TestInfo.of(capacity), provider -> {
			var chunk = AllocateTicket.bytes(capacity).submit(provider);
			
			var providerRead = DataProvider.newVerySimpleProvider(provider.getSource());
			var readChunk    = providerRead.getChunk(chunk.getPtr());
			
			assertEquals(readChunk, chunk);
		});
	}
	
	@Test(dataProvider = "chunkSizeNumbers")
	void chunkBodyIntegrity(long capacity) throws IOException{
		TestUtils.testChunkProvider(TestInfo.of(capacity), provider -> {
			var chunkSecond = AllocateTicket.bytes(1).submit(provider);
			var chunk       = AllocateTicket.bytes(capacity).withNext(chunkSecond).submit(provider);
//			var chunk      =AllocateTicket.bytes(capacity).submit(provider);
			
			byte[] bodyData = new byte[(int)capacity];
			for(int i = 0; i<bodyData.length; i++){
				bodyData[i] = (byte)i;
			}
			
			chunk.write(true, bodyData);
			
			var readBody = chunk.readAll();
			assertEquals(readBody, bodyData);
		});
	}
	
	@Test(dataProvider = "chunkSizeNumbers")
	void chunkBodyChainedIntegrity(long capacity) throws IOException{
		TestUtils.testChunkProvider(TestInfo.of(capacity), provider -> {
			
			var ticket      = AllocateTicket.bytes(capacity/2);
			var chunkSecond = ticket.submit(provider);
			var chunk       = ticket.withNext(chunkSecond).submit(provider);
			
			byte[] bodyData = new byte[(int)capacity];
			for(int i = 0; i<bodyData.length; i++){
				bodyData[i] = (byte)i;
			}
			
			chunk.write(true, bodyData);
			
			var readBody = chunk.readAll();
			assertEquals(readBody, bodyData);
		});
	}
	
	@Test
	void blankCluster() throws IOException{
		TestUtils.testCluster(TestInfo.of(), ses -> { });
	}
	
	@org.testng.annotations.DataProvider(name = "lists")
	public static Object[][] lists(){
		return new Object[][]{{ContiguousIOList.class}, {LinkedIOList.class}};
	}
	
	@Test(dataProvider = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<Integer>> void listTestIntAdd(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Integer.class, list -> {
			list.add(69);
			list.add(420);
		}, true);
	}
	
	@Test(dataProvider = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<Dummy>> void listTestSimpleAdd(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Dummy.class, list -> {
			list.add(new Dummy(69));
			list.add(new Dummy(420));
		}, false);
	}
	
	@Test(dataProvider = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<Dummy>> void listSingleAdd(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Dummy.class, list -> {
			list.add(Dummy.first());
		}, false);
	}
	
	@Test(dataProvider = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<BooleanContainer>> void listBitValue(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, BooleanContainer.class, list -> {
			var rand = new Random(1);
			var vals = IntStream.range(0, 100)
			                    .mapToObj(i -> new BooleanContainer(rand.nextBoolean()))
			                    .collect(Collectors.toList());
			list.addAll(vals);
		}, true);
	}
	
	@Test(dataProvider = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<Dummy>> void listInsert(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Dummy.class, list -> {
			list.add(new Dummy('1'));
			list.add(new Dummy('2'));
			list.add(new Dummy('3'));
			list.add(new Dummy('4'));
			
			list.add(1, new Dummy('5'));
		}, false);
	}
	
	@Test(dataProvider = "lists")
	<L extends IOInstance.Unmanaged<L> & IOList<Dummy>> void listIndexRemove(Class<L> listType) throws IOException{
		listEqualityTest(TestInfo.of(listType), listType, Dummy.class, list -> {
			list.add(new Dummy(69));
			list.add(new Dummy(360));
			list.add(new Dummy(420));
			list.remove(1);
			list.remove(1);
		}, false);
	}
	
	@Test(dataProvider = "lists")
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
	
	@Test
	void testHashIOMap() throws IOException{
		TestUtils.ioMapComplianceSequence(
			TestInfo.of(),
			HashIOMap<Integer, Integer>::new,
			TypeLink.of(HashIOMap.class, Integer.class, Integer.class),
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
	
	@Test
	void stringTest() throws IOException{
		TestUtils.testChunkProvider(TestInfo.of(), provider -> {
			String data = "this is a test!";
			
			StructPipe<StringContainer> pipe = StandardStructPipe.of(StringContainer.class);
			
			var chunk = AllocateTicket.bytes(64).submit(provider);
			
			var text = new StringContainer(data);
			
			pipe.write(provider, chunk, text);
			var read = pipe.readNew(chunk, null);
			
			assertEquals(text, read);
		});
	}
	
	
	@org.testng.annotations.DataProvider(name = "strings")
	public static Object[][] strings(){
		return new Object[][]{
			{""},
			{"ABC123"},
			{"this works"},
			{"hey does this work"},
			{"dgasf_gfao124581z523tg eagdgisndgim315   qTGE254ghaerza573q6 wr gewr2$afas -.,/7-+41561552030,15.ds"},
			{"I ❤️ you"},
			{"\u00ff"},
			{IntStream.range(0, 1000).mapToObj(i -> "loong string!? (" + i + ")").collect(Collectors.joining(", "))},
			};
	}
	
	@Test(dataProvider = "strings")
	void autoTextTest(String data) throws IOException{
		TestUtils.testChunkProvider(TestInfo.of(data), provider -> {
			StructPipe<AutoText> pipe = StandardStructPipe.of(AutoText.class);
			
			var chunk = AllocateTicket.bytes(64).submit(provider);
			
			var text = new AutoText(data);
			
			pipe.write(provider, chunk, text);
			var read = pipe.readNew(chunk, null);
			
			assertEquals(text, read);
		});
	}
	
	@Test(dataProvider = "strings")
	void ioUTF(String text) throws IOException{
		var mem = MemoryData.empty();
		mem.writeUTF(true, text);
		var read = mem.readUTF(0);
		assertEquals(text, read);
	}
	
	@SuppressWarnings("unchecked")
	static <T extends IOInstance<T>> void linkedListEqualityTest(TestInfo info, Class<T> typ, UnsafeConsumer<IOList<T>, IOException> session, boolean useCluster) throws IOException{
		listEqualityTest(info, LinkedIOList.class, typ, session, useCluster);
	}
	@SuppressWarnings("unchecked")
	static <T extends IOInstance<T>> void contiguousListEqualityTest(TestInfo info, Class<T> typ, UnsafeConsumer<IOList<T>, IOException> session, boolean useCluster) throws IOException{
		listEqualityTest(info, ContiguousIOList.class, typ, session, useCluster);
	}
	
	static <T, L extends IOInstance.Unmanaged<L> & IOList<T>> void listEqualityTest(TestInfo info, Class<L> listType, Class<T> typ, UnsafeConsumer<IOList<T>, IOException> session, boolean useCluster) throws IOException{
		TestUtils.ioListComplianceSequence(
			info, 64,
			(provider, reference, typeDef) -> {
				var lTyp = Struct.Unmanaged.ofUnmanaged(listType);
				return lTyp.make(provider, reference, typeDef);
			},
			TypeLink.of(listType, typ),
			session, useCluster
		);
	}
	
	@Test
	void lastChunkMoveOnFree() throws IOException{
		TestUtils.testCluster(TestInfo.of(), cluster -> {
			
			var frees = List.of(AllocateTicket.bytes(1).submit(cluster),
			                    AllocateTicket.bytes(1).submit(cluster),
			                    AllocateTicket.bytes(1).submit(cluster),
			                    AllocateTicket.bytes(1).submit(cluster),
			                    AllocateTicket.bytes(1).submit(cluster));
			AllocateTicket.bytes(1).submit(cluster);
			var c1 = AllocateTicket.bytes(20).submit(cluster);
			var mm = cluster.getMemoryManager();
			assertEquals(mm.getFreeChunks().size(), 0);
			mm.free(List.of(frees.get(0), frees.get(2), frees.get(4)));
			assertEquals(mm.getFreeChunks().size(), 3);
			mm.free(List.of(frees.get(1), frees.get(3)));
			assertEquals(mm.getFreeChunks().size(), 1);
			assertTrue(cluster.getSource().getIOSize()>c1.getPtr().getValue());
			c1.freeChaining();
			assertEquals(mm.getFreeChunks().size(), 1);
			assertEquals(cluster.getSource().getIOSize(), c1.getPtr().getValue());
		});
	}
	
	@Test
	void allocateByChainWalkUpDefragment() throws IOException{
		TestUtils.testChunkProvider(TestInfo.of(), data -> {
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
			
			assertEquals(first.chainLength(), 2);
			assertEquals(first.readAll(), bb);
		});
	}
}
