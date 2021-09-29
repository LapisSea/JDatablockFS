package com.lapisseqa.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.GenericContainer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class GeneralTests{
	
	@BeforeAll
	static void init() throws IOException{
//		System.setProperty("com.lapissea.cfs.GlobalConfig.printCompilation", "true");
//		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		
		ChunkDataProvider.newVerySimpleProvider();
		
		for(var c : Arrays.asList(Chunk.class, Reference.class, AutoText.class)){
			try{
				Struct.ofUnknown(c);
			}catch(Throwable e){
				LogUtil.printlnEr(e);
			}
		}
	}
	
	@ParameterizedTest
	@ValueSource(longs={0, 10, 255, 256, 1000, 10000})
	void chunkHeadIntegrity(long capacity, TestInfo info) throws IOException{
		TestUtils.testChunkProvider(info, provider->{
			var chunk=AllocateTicket.bytes(capacity).submit(provider);
			
			var providerRead=ChunkDataProvider.newVerySimpleProvider(provider.getSource());
			var readChunk   =providerRead.getChunk(chunk.getPtr());
			
			assertEquals(chunk, readChunk);
		});
	}
	
	@ParameterizedTest
	@ValueSource(longs={0, 10, 255, 256, 1000, 10000})
	void chunkBodyIntegrity(long capacity, TestInfo info) throws IOException{
		TestUtils.testChunkProvider(info, provider->{
			var chunkSecond=AllocateTicket.bytes(1).submit(provider);
			var chunk      =AllocateTicket.bytes(capacity).withNext(chunkSecond).submit(provider);
//			var chunk      =AllocateTicket.bytes(capacity).submit(provider);
			
			byte[] bodyData=new byte[(int)capacity];
			for(int i=0;i<bodyData.length;i++){
				bodyData[i]=(byte)i;
			}
			
			chunk.write(true, bodyData);
			
			var readBody=chunk.readAll();
			assertArrayEquals(bodyData, readBody);
		});
	}
	
	@ParameterizedTest
	@ValueSource(longs={10, 256, 1000, 10000})
	void chunkBodyChainedIntegrity(long capacity, TestInfo info) throws IOException{
		TestUtils.testChunkProvider(info, provider->{
			
			var ticket     =AllocateTicket.bytes(capacity/2);
			var chunkSecond=ticket.submit(provider);
			var chunk      =ticket.withNext(chunkSecond).submit(provider);
			
			byte[] bodyData=new byte[(int)capacity];
			for(int i=0;i<bodyData.length;i++){
				bodyData[i]=(byte)i;
			}
			
			chunk.write(true, bodyData);
			
			var readBody=chunk.readAll();
			assertArrayEquals(bodyData, readBody);
		});
	}
	
	@Test
	void blankCluster(TestInfo info) throws IOException{
		TestUtils.testCluster(info, ses->{});
	}
	
	@Test
	void contiguousIOList(TestInfo info) throws IOException{
		TestUtils.complexObjectEqualityTest(
			info, 64,
			ContiguousIOList<Dummy>::new,
			TypeDefinition.of(ContiguousIOList.class, Dummy.class),
			list->{
				list.add(new Dummy(69));
				list.add(new Dummy(420));
			}
		);
	}
	
	@Test
	void testHashIOMap(TestInfo info) throws IOException{
		TestUtils.complexObjectEqualityTest(
			info, 64,
			HashIOMap<Integer, Integer>::new,
			TypeDefinition.of(HashIOMap.class, Integer.class, Integer.class),
			map->{
				map.put(1, 11);
				map.put(2, 12);
				map.put(3, 13);
				map.put(16, 21);
				map.put(17, 22);
				map.put(18, 23);
			}
		);
	}
	
	@Test
	void genericTest(TestInfo info) throws IOException{
		TestUtils.testChunkProvider(info, provider->{
			var pipe=ContiguousStructPipe.of(GenericContainer.class);
			
			var chunk=AllocateTicket.bytes(64).submit(provider);
			
			var container=new GenericContainer<>(new Dummy(123));
			
			pipe.write(chunk, container);
			var read=pipe.readNew(chunk, null);
			
			assertEquals(container, read);
		});
	}
	
	@Test
	void stringTest(TestInfo info) throws IOException{
		TestUtils.testChunkProvider(info, provider->{
			String data="this is a test!";
			
			StructPipe<StringContainer> pipe=ContiguousStructPipe.of(StringContainer.class);
			
			var chunk=AllocateTicket.bytes(64).submit(provider);
			
			var text=new StringContainer(data);
			
			pipe.write(provider, chunk, text);
			var read=pipe.readNew(chunk, null);
			
			LogUtil.println(text);
			LogUtil.println(read);
			LogUtil.println(chunk);
			
			assertEquals(text, read);
		});
	}
	
	@ParameterizedTest
	@ValueSource(strings={
		"",
		"ABC123",
		"this works",
		"hey does this work?",
		"dgasf_gfao124581z523tg eagdgisndgim315   qTGE254ghaerza573q6 wr gewr2$afas -.,/7-+41561552030,15.ds",
		"I ❤️ you"
	})
	void autoTextTest(String data, TestInfo info) throws IOException{
		TestUtils.testChunkProvider(info, provider->{
			StructPipe<AutoText> pipe=ContiguousStructPipe.of(AutoText.class);
			
			var chunk=AllocateTicket.bytes(64).submit(provider);
			
			var text=new AutoText(data);
			
			pipe.write(provider, chunk, text);
			var read=pipe.readNew(chunk, null);
			
			LogUtil.println(text);
			LogUtil.println(read);
			LogUtil.println(chunk);
			
			assertEquals(text, read);
		});
	}
	
	static <T extends IOInstance<T>> void linkedListEqualityTest(TestInfo info, Class<T> typ, UnsafeConsumer<LinkedIOList<T>, IOException> session) throws IOException{
		TestUtils.complexObjectEqualityTest(
			info, 10,
			LinkedIOList<T>::new,
			TypeDefinition.of(LinkedIOList.class, typ),
			session
		);
	}
	
	@Test
	void linkedListSingleAdd(TestInfo info) throws IOException{
		linkedListEqualityTest(info, Dummy.class, list->{
			list.add(Dummy.first());
		});
	}
	
	@Test
	void linkedListMultiAdd(TestInfo info) throws IOException{
		linkedListEqualityTest(info, Dummy.class, list->{
			list.add(Dummy.first());
			list.add(Dummy.auto());
			list.add(Dummy.auto());
		});
	}
	@Test
	void linkedListAddRemove(TestInfo info) throws IOException{
		linkedListEqualityTest(info, Dummy.class, list->{
			list.add(Dummy.first());
			list.add(Dummy.auto());
			list.add(Dummy.auto());
			list.add(Dummy.auto());
			list.add(Dummy.auto());
			
			//TODO
		});
	}
}
