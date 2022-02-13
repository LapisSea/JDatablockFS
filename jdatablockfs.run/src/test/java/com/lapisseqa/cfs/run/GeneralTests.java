package com.lapisseqa.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.LinkedIOList;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GeneralTests{
	
	@BeforeAll
	static void init() throws IOException{
		if(Boolean.parseBoolean(UtilL.sysPropertyByClass(GeneralTests.class, "tabPrint").orElse("false"))){
			LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		}
		
		if(Boolean.parseBoolean(UtilL.sysPropertyByClass(GeneralTests.class, "standardInit").orElse("false"))){
			for(var c : Arrays.asList(Chunk.class, Reference.class, AutoText.class, Cluster.RootRef.class, ContiguousIOList.class, LinkedIOList.class, HashIOMap.class)){
				try{
					Struct.ofUnknown(c);
				}catch(Throwable e){
					LogUtil.printlnEr(e);
				}
			}
		}
		
		if(Boolean.parseBoolean(UtilL.sysPropertyByClass(GeneralTests.class, "earlyRunCode").orElse("true"))){
			try(var dummy=AllocateTicket.bytes(1).submit(DataProvider.newVerySimpleProvider()).io()){
				dummy.write(1);
			}
		}
	}
	
	@ParameterizedTest
	@ValueSource(longs={0, 10, 255, 256, 1000, 10000})
	void chunkHeadIntegrity(long capacity, TestInfo info) throws IOException{
		TestUtils.testChunkProvider(info, provider->{
			var chunk=AllocateTicket.bytes(capacity).submit(provider);
			
			var providerRead=DataProvider.newVerySimpleProvider(provider.getSource());
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
	
	@ParameterizedTest
	@ValueSource(classes={ContiguousIOList.class, LinkedIOList.class})
	<L extends IOInstance.Unmanaged<L>&IOList<Dummy>> void listTestSimpleAdd(Class<L> listType, TestInfo info) throws IOException{
		listEqualityTest(info, listType, Dummy.class, list->{
			list.add(new Dummy(69));
			list.add(new Dummy(420));
		}, false);
	}
	
	@ParameterizedTest
	@ValueSource(classes={ContiguousIOList.class, LinkedIOList.class})
	<L extends IOInstance.Unmanaged<L>&IOList<Dummy>> void listSingleAdd(Class<L> listType, TestInfo info) throws IOException{
		listEqualityTest(info, listType, Dummy.class, list->{
			list.add(Dummy.first());
		}, false);
	}
	
	@ParameterizedTest
	@ValueSource(classes={ContiguousIOList.class, LinkedIOList.class})
	<L extends IOInstance.Unmanaged<L>&IOList<BooleanContainer>> void listBitValue(Class<L> listType, TestInfo info) throws IOException{
		listEqualityTest(info, listType, BooleanContainer.class, list->{
			var rand=new Random(1);
			var vals=IntStream.range(0, 100)
			                  .mapToObj(i->new BooleanContainer(rand.nextBoolean()))
			                  .collect(Collectors.toList());
			list.addAll(vals);
		}, true);
	}
	
	@ParameterizedTest
	@ValueSource(classes={ContiguousIOList.class, LinkedIOList.class})
	<L extends IOInstance.Unmanaged<L>&IOList<Dummy>> void listInsert(Class<L> listType, TestInfo info) throws IOException{
		listEqualityTest(info, listType, Dummy.class, list->{
			list.add(new Dummy(69));
			list.add(new Dummy(420));
			
			list.add(1, new Dummy(360));
		}, false);
	}
	
	@ParameterizedTest
	@ValueSource(classes={ContiguousIOList.class, LinkedIOList.class})
	<L extends IOInstance.Unmanaged<L>&IOList<Dummy>> void listIndexRemove(Class<L> listType, TestInfo info) throws IOException{
		listEqualityTest(info, listType, Dummy.class, list->{
			list.add(new Dummy(69));
			list.add(new Dummy(360));
			list.add(new Dummy(420));
			list.remove(1);
			list.remove(1);
		}, false);
	}
	@ParameterizedTest
	@ValueSource(classes={ContiguousIOList.class, LinkedIOList.class})
	<L extends IOInstance.Unmanaged<L>&IOList<Dummy>> void listComplexIndexRemove(Class<L> listType, TestInfo info) throws IOException{
		listEqualityTest(info, listType, Dummy.class, list->{
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
	void testHashIOMap(TestInfo info) throws IOException{
		TestUtils.ioMapComplianceSequence(
			info,
			HashIOMap<Integer, Integer>::new,
			TypeLink.of(HashIOMap.class, Integer.class, Integer.class),
			map->{
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
	void genericTest(TestInfo info) throws IOException{
		TestUtils.testChunkProvider(info, provider->{
			var pipe=ContiguousStructPipe.of(GenericContainer.class);
			
			var chunk=AllocateTicket.bytes(64).submit(provider);
			
			var container=new GenericContainer<>();
			
			container.value=new Dummy(123);
			
			pipe.write(chunk, container);
			var read=pipe.readNew(chunk, null);
			
			assertEquals(container, read);
			
			
			container.value="This is a test.";
			
			pipe.write(chunk, container);
			read=pipe.readNew(chunk, null);
			
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
			
			assertEquals(text, read);
		});
	}
	
	@SuppressWarnings("unchecked")
	static <T extends IOInstance<T>> void linkedListEqualityTest(TestInfo info, Class<T> typ, UnsafeConsumer<IOList<T>, IOException> session, boolean useCluster) throws IOException{
		listEqualityTest(info, LinkedIOList.class, typ, session, useCluster);
	}
	@SuppressWarnings("unchecked")
	static <T extends IOInstance<T>> void contiguousListEqualityTest(TestInfo info, Class<T> typ, UnsafeConsumer<IOList<T>, IOException> session, boolean useCluster) throws IOException{
		listEqualityTest(info, ContiguousIOList.class, typ, session, useCluster);
	}
	
	static <T extends IOInstance<T>, L extends IOInstance.Unmanaged<L>&IOList<T>> void listEqualityTest(TestInfo info, Class<L> listType, Class<T> typ, UnsafeConsumer<IOList<T>, IOException> session, boolean useCluster) throws IOException{
		TestUtils.ioListComplianceSequence(
			info, 64,
			(provider, reference, typeDef)->{
				var lTyp=Struct.Unmanaged.ofUnmanaged(listType);
				return lTyp.requireUnmanagedConstructor().create(provider, reference, typeDef);
			},
			TypeLink.of(listType, typ),
			session, useCluster
		);
	}
	
	@ParameterizedTest
	@MethodSource("types")
	<T extends IOInstance<T>> void checkIntegrity(Struct<T> struct) throws IOException{
		if(struct instanceof Struct.Unmanaged){
			return;
		}
		
		StructPipe<T> pipe=null;
		try{
			pipe=ContiguousStructPipe.of(struct);
		}catch(MalformedStructLayout ignored){}
		if(pipe!=null){
			pipe.checkTypeIntegrity(struct.requireEmptyConstructor().get());
		}
		try{
			pipe=FixedContiguousStructPipe.of(struct);
		}catch(MalformedStructLayout ignored){}
		if(pipe!=null){
			pipe.checkTypeIntegrity(struct.requireEmptyConstructor().get());
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Stream<Struct<T>> types(){
		return (Stream<Struct<T>>)(Object)Stream
			.of(
				Reference.class,
				AutoText.class,
				Cluster.class,
				ContiguousIOList.class,
				LinkedIOList.class,
				HashIOMap.class,
				BooleanContainer.class,
				Dummy.class
			).flatMap(p->Stream.concat(Stream.of(p), Arrays.stream(p.getDeclaredClasses())))
			.filter(c->UtilL.instanceOf(c, IOInstance.class))
			.map(Struct::ofUnknown);
	}
}
