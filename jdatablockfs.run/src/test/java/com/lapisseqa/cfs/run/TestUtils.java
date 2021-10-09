package com.lapisseqa.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class TestUtils{
	
	private static final LateInit<DataLogger> LOGGER=LoggedMemoryUtils.createLoggerFromConfig();
	
	
	static void testRawMem(TestInfo info, UnsafeConsumer<IOInterface, IOException> session) throws IOException{
		
		boolean shouldDeleteOk=false;
		try{
			shouldDeleteOk=Boolean.parseBoolean(System.getProperty("deleteOk"));
		}catch(Throwable e){}
		
		
		String sessionName=getSessionName(info);
		
		IOInterface mem     =LoggedMemoryUtils.newLoggedMemory(sessionName, LOGGER);
		boolean     deleting=false;
		try{
			session.accept(mem);
			if(shouldDeleteOk){
				deleting=true;
			}
		}finally{
			var ses=LOGGER.get().getSession(sessionName);
			if(deleting){
				LogUtil.println("deleting ok session", sessionName);
				ses.delete();
			}else{
				ses.finish();
			}
		}
	}
	
	static void testChunkProvider(TestInfo info, UnsafeConsumer<ChunkDataProvider, IOException> session) throws IOException{
		testRawMem(info, mem->{
			mem.write(true, Cluster.getMagicId());
			session.accept(ChunkDataProvider.newVerySimpleProvider(mem));
		});
	}
	
	static void testCluster(TestInfo info, UnsafeConsumer<Cluster, IOException> session) throws IOException{
		testRawMem(info, mem->{
			Cluster.init(mem);
			session.accept(new Cluster(mem));
		});
	}
	
	private static String getSessionName(TestInfo info){
		var    mn         =info.getTestMethod().map(Method::getName).orElse(null);
		String sessionName=info.getDisplayName();
		if(mn!=null&&!sessionName.contains(mn)){
			return mn+": "+sessionName;
		}
		return sessionName;
	}
	
	static <T extends IOInstance.Unmanaged<?>> void complexObjectIntegrityTest(
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
			
			T read;
			try{
				read=constr.create(provider, ref, typeDef);
			}catch(Throwable e){
				throw new RuntimeException("Failed to read object with data", e);
			}
			
			assertEquals(obj, read);
		});
	}
	
	private static <T> void checkCompliance(T test, T compliance){
		if(!test.equals(compliance)){
			throw new RuntimeException(test.getClass().getSimpleName()+" is not compliant!\n"
			                           +test+" different to: \n"
			                           +compliance);
		}
	}
	
	static <E, T extends IOInstance.Unmanaged<?>&IOList<E>> void ioListComplianceSequence(
		TestInfo info, int initalCapacity,
		Struct.Unmanaged.Constr<T> constr,
		TypeDefinition typeDef,
		UnsafeConsumer<IOList<E>, IOException> session
	) throws IOException{
		complexObjectIntegrityTest(info, initalCapacity, constr, typeDef, list->{
			var splitter=Splitter.list(list, new ReferenceMemoryIOList<>(), TestUtils::checkCompliance);
			session.accept(splitter);
		});
	}
	
	
	static <K, V, T extends IOInstance.Unmanaged<?>&IOMap<K, V>> void ioMapComplianceSequence(
		TestInfo info,
		Struct.Unmanaged.Constr<T> constr,
		TypeDefinition typeDef,
		UnsafeConsumer<IOMap<K, V>, IOException> session
	) throws IOException{
		TestUtils.complexObjectIntegrityTest(info, 8, constr, typeDef, map->{
			var splitter=Splitter.map(map, new ReferenceMemoryIOMap<>(), TestUtils::checkCompliance);
			session.accept(splitter);
		});
	}
	
}
