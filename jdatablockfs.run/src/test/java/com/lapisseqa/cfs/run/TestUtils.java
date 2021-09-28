package com.lapisseqa.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.util.LateInit;
import com.lapissea.util.function.UnsafeConsumer;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class TestUtils{
	
	private static final LateInit<DataLogger> LOGGER=LoggedMemoryUtils.createLoggerFromConfig();
	
	
	static void testChunkProvider(TestInfo info, UnsafeConsumer<ChunkDataProvider, IOException> session) throws IOException{
		
		var mn=info.getTestMethod().map(Method::getName).orElse(null);
		String sessionName=info.getDisplayName();
		if(mn!=null&&!sessionName.contains(mn)){
			sessionName=mn+": "+sessionName;
		}
		
		MemoryData<?> mem=LoggedMemoryUtils.newLoggedMemory(sessionName, LOGGER);
		mem.write(true, Cluster.getMagicId());
		
		try{
			session.accept(ChunkDataProvider.newVerySimpleProvider(mem));
		}finally{
			LOGGER.get().getSession(sessionName).finish();
		}
	}
	
	static <T extends IOInstance.Unmanaged<?>> void complexObjectEqualityTest(
		TestInfo info, int initalCapacity,
		Struct.Unmanaged.Constr<T> constr,
		TypeDefinition typeDef,
		UnsafeConsumer<T, IOException> session
	) throws IOException{
		testChunkProvider(info, provider->{
			var chunk=AllocateTicket.bytes(initalCapacity).submit(provider);
			var ref  =chunk.getPtr().makeReference(0);
			
			T obj=constr.create(provider, ref, typeDef);
			
			session.accept(obj);
			
			T read=constr.create(provider, ref, typeDef);
			
			assertEquals(obj, read);
		});
	}
	
}