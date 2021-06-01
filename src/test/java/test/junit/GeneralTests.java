package test.junit;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@Execution(ExecutionMode.CONCURRENT)
public class GeneralTests{
	
	@BeforeAll
	static void init(){
		
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
	
	private IOInterface blankData(){
		return new MemoryData.Arr();
	}
	
}
