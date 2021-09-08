package test.junit;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.GenericContainer;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
public class GeneralTests{
	
	@BeforeAll
	static void init() throws IOException{
		System.setProperty("com.lapissea.cfs.GlobalConfig.printCompilation", "true");
//		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		
		ChunkDataProvider.newVerySimpleProvider();
	}
	
	@ParameterizedTest
	@ValueSource(longs={0, 10, 255, 256, 1000, 10000})
	void chunkHeadIntegrity(long capacity) throws IOException{
		
		var provider=ChunkDataProvider.newVerySimpleProvider();
		var chunk   =AllocateTicket.bytes(capacity).submit(provider);
		
		var providerRead=ChunkDataProvider.newVerySimpleProvider(provider.getSource());
		var readChunk   =providerRead.getChunk(chunk.getPtr());
		
		assertEquals(chunk, readChunk);
	}
	
	@ParameterizedTest
	@ValueSource(longs={0, 10, 255, 256, 1000, 10000})
	void chunkBodyIntegrity(long capacity) throws IOException{
		var provider=ChunkDataProvider.newVerySimpleProvider();
//		var provider=ChunkDataProvider.newVerySimpleProvider((data, ids)->LogUtil.println(data));
		
		var chunkSecond=AllocateTicket.bytes(1).submit(provider);
		var chunk      =AllocateTicket.bytes(capacity).withNext(chunkSecond).submit(provider);
//		var chunk=AllocateTicket.bytes(capacity).submit(provider);
		
		byte[] bodyData=new byte[(int)capacity];
		for(int i=0;i<bodyData.length;i++){
			bodyData[i]=(byte)i;
		}
		
		chunk.write(true, bodyData);
		
		var readBody=chunk.readAll();
		assertArrayEquals(bodyData, readBody);
	}
	
	@ParameterizedTest
	@ValueSource(longs={10, 256, 1000, 10000})
	void chunkBodyChainedIntegrity(long capacity) throws IOException{
		var provider=ChunkDataProvider.newVerySimpleProvider();
//		var provider=ChunkDataProvider.newVerySimpleProvider((data, ids)->LogUtil.println(data));
		
		
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
	}
	
	static class Dummy extends IOInstance<Dummy>{
		
		@IOValue
		int dummyValue;
		
		public Dummy(){
		}
		public Dummy(int dummyValue){
			this.dummyValue=dummyValue;
		}
	}
	
	@Test
	void testContiguousIOList() throws IOException{
		var provider=ChunkDataProvider.newVerySimpleProvider();
//		var provider=ChunkDataProvider.newVerySimpleProvider((data, ids)->LogUtil.println(data));
		
		
		var chunk=AllocateTicket.bytes(64).submit(provider);
		
		var ref=chunk.getPtr().makeReference(0);
		var typ=TypeDefinition.of(ContiguousIOList.class, Dummy.class);
		
		IOList<Dummy> list=new ContiguousIOList<>(provider, ref, typ);
		
		list.add(new Dummy(69));
		list.add(new Dummy(420));
		
		IOList<Dummy> read=new ContiguousIOList<>(provider, ref, typ);
		
		assertEquals(list, read);
	}
	@Test
	void testHashIOMap() throws IOException{
		var provider=ChunkDataProvider.newVerySimpleProvider();
//		var provider=ChunkDataProvider.newVerySimpleProvider((data, ids)->LogUtil.println(data));
		
		
		var chunk=AllocateTicket.bytes(64).submit(provider);
		
		var ref=chunk.getPtr().makeReference(0);
		var typ=TypeDefinition.of(HashIOMap.class, Integer.class, Integer.class);
		
		IOMap<Integer, Integer> map=new HashIOMap<>(provider, ref, typ);
		
		map.put(1, 11);
		map.put(2, 12);
		map.put(3, 13);
		map.put(16, 21);
		map.put(17, 22);
		map.put(18, 23);
		
		
		IOMap<Integer, Integer> read=new HashIOMap<>(provider, ref, typ);
		
		assertEquals(map, read);
	}
	
	@Test
	void genericTest() throws IOException{
		var pipe=ContiguousStructPipe.of(GenericContainer.class);
		
		var provider=ChunkDataProvider.newVerySimpleProvider();
		var chunk   =AllocateTicket.bytes(64).submit(provider);
		
		var container=new GenericContainer(new Dummy(123));
		
		pipe.write(chunk, container);
		var read=pipe.readNew(chunk);
		
		assertEquals(container, read);
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
	void autoTextTest(String data) throws IOException{
		StructPipe<AutoText> pipe=ContiguousStructPipe.of(AutoText.class);
		
		var provider=ChunkDataProvider.newVerySimpleProvider();
//		var provider=ChunkDataProvider.newVerySimpleProvider((d, ids)->LogUtil.println(d.hexdump()));
		
		var chunk=AllocateTicket.bytes(64).submit(provider);
		
		var text=new AutoText(data);
		
		LogUtil.println(text.getEncoding());
		pipe.write(provider, chunk, text);
		var read=pipe.readNew(chunk);
		
		LogUtil.println(text);
		LogUtil.println(read);
		LogUtil.println(chunk);
		
		assertEquals(text, read);
	}
	
}
