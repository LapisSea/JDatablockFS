package com.lapissea.cfs.run;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.NumberSize;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.StagedInit.STATE_DONE;
import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GeneralTests{
	
	@BeforeAll
	static void init() throws IOException{
		if(GlobalConfig.configFlag("test.tabPrint", false)){
			LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		}
		
		if(GlobalConfig.configFlag("test.standardInit", false)){
			Stream.of(Chunk.class, Reference.class, AutoText.class, Cluster.RootRef.class, ContiguousIOList.class, LinkedIOList.class, HashIOMap.class, TypeDef.class)
			      .map(c->{
				      try{
					      return Struct.ofUnknown(c);
				      }catch(Throwable e){
					      e.printStackTrace();
					      return null;
				      }
			      })
			      .filter(Objects::nonNull)
			      .toList()
			      .forEach(c->c.waitForState(STATE_DONE));
		}
		
		if(GlobalConfig.configFlag("test.earlyRunCode", true)){
			try(var dummy=AllocateTicket.bytes(1).submit(DataProvider.newVerySimpleProvider()).io()){
				dummy.write(1);
			}
			AllocateTicket.bytes(10).submit(Cluster.init(MemoryData.builder().build()));
		}
	}
	
	@Test
	void signedIO() throws IOException{
		for(NumberSize numberSize : NumberSize.FLAG_INFO){
			signedIO(numberSize, 0);
			signedIO(numberSize, numberSize.signedMaxValue);
			signedIO(numberSize, numberSize.signedMinValue);
			if(numberSize!=NumberSize.VOID){
				var iter=new Random(10).longs(numberSize.signedMinValue, numberSize.signedMaxValue).limit(1000).iterator();
				while(iter.hasNext()){
					signedIO(numberSize, iter.nextLong());
				}
			}
		}
	}
	
	void signedIO(NumberSize numberSize, long value) throws IOException{
		byte[] buf=new byte[8];
		numberSize.writeSigned(new ContentOutputStream.BA(buf), value);
		var read=numberSize.readSigned(new ContentInputStream.BA(buf));
		
		assertEquals(value, read, ()->value+" was not read or written correctly with "+numberSize);
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
	<L extends IOInstance.Unmanaged<L>&IOList<Integer>> void listTestIntAdd(Class<L> listType, TestInfo info) throws IOException{
		listEqualityTest(info, listType, Integer.class, list->{
			list.add(69);
			list.add(420);
		}, true);
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
			list.add(new Dummy('1'));
			list.add(new Dummy('2'));
			list.add(new Dummy('3'));
			list.add(new Dummy('4'));
			
			list.add(1, new Dummy('5'));
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
		"I ❤️ you",
		"\u00ff"
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
	
	static <T, L extends IOInstance.Unmanaged<L>&IOList<T>> void listEqualityTest(TestInfo info, Class<L> listType, Class<T> typ, UnsafeConsumer<IOList<T>, IOException> session, boolean useCluster) throws IOException{
		TestUtils.ioListComplianceSequence(
			info, 64,
			(provider, reference, typeDef)->{
				var lTyp=Struct.Unmanaged.ofUnmanaged(listType);
				return lTyp.getUnmanagedConstructor().create(provider, reference, typeDef);
			},
			TypeLink.of(listType, typ),
			session, useCluster
		);
	}
	
	@Test
	void checkMemoryWalk(TestInfo info) throws IOException{
		TestUtils.testCluster(info, cluster->{
			
			var prov=cluster.getRootProvider();
			
			var dummies=prov.builder()
			                .withGenerator(()->new GenericContainer<>(IntStream.range(0, 3).mapToObj(Dummy::new).toArray(Dummy[]::new)))
			                .withId("dummy_array")
			                .request();
			
			LogUtil.println(dummies.toString());
			
		});
	}
}
