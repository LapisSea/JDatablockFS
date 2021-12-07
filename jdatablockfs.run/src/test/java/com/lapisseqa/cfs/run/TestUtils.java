package com.lapisseqa.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
	
	static void testChunkProvider(TestInfo info, UnsafeConsumer<DataProvider, IOException> session) throws IOException{
		testRawMem(info, mem->{
			mem.write(true, Cluster.getMagicId());
			session.accept(DataProvider.newVerySimpleProvider(mem));
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
	
	static <T extends IOInstance.Unmanaged<T>> void complexObjectIntegrityTest(
		TestInfo info, int initalCapacity,
		Struct.Unmanaged.Constr<T> constr,
		TypeDefinition typeDef,
		UnsafeConsumer<T, IOException> session,
		boolean useCluster
	) throws IOException{
		UnsafeConsumer<DataProvider, IOException> ses=provider->{
			var chunk=AllocateTicket.bytes(initalCapacity).submit(provider);
			var ref  =chunk.getPtr().makeReference(0);
			
			T obj=constr.create(provider, ref, typeDef);
			
			var actualSize=ContiguousStructPipe.sizeOfUnknown(provider, obj, WordSpace.BYTE);
			
			if(actualSize>initalCapacity){
				LogUtil.printlnEr("WARNING: initial capacity is", initalCapacity, "but object has allocated", actualSize);
			}
			
			if(provider instanceof Cluster c){
				c.getTemp().put("test_obj", obj);
			}
			
			session.accept(obj);
			
			T read;
			try{
				read=constr.create(provider, ref, typeDef);
			}catch(Throwable e){
				throw new RuntimeException("Failed to read object with data", e);
			}
			
			assertEquals(obj, read);
		};
		
		if(useCluster){
			testCluster(info, ses::accept);
		}else{
			testChunkProvider(info, ses);
		}
	}
	
	private static <T> void checkCompliance(T test, T compliance){
		if(!test.equals(compliance)){
			throw new RuntimeException(test.getClass().getSimpleName()+" is not compliant!\n"
			                           +test+" different to: \n"
			                           +compliance);
		}
	}
	
	static <E, T extends IOInstance.Unmanaged<T>&IOList<E>> void ioListComplianceSequence(
		TestInfo info, int initalCapacity,
		Struct.Unmanaged.Constr<T> constr,
		TypeDefinition typeDef,
		UnsafeConsumer<IOList<E>, IOException> session, boolean useCluster
	) throws IOException{
		complexObjectIntegrityTest(info, initalCapacity, constr, typeDef, list->{
			var splitter=Splitter.list(list, IOList.wrap(new ArrayList<>(), ()->{throw new UnsupportedOperationException();}), TestUtils::checkCompliance);
			session.accept(splitter);
		}, useCluster);
	}
	
	
	static <K, V, T extends IOInstance.Unmanaged<T>&IOMap<K, V>> void ioMapComplianceSequence(
		TestInfo info,
		Struct.Unmanaged.Constr<T> constr,
		TypeDefinition typeDef,
		UnsafeConsumer<IOMap<K, V>, IOException> session
	) throws IOException{
		int initial=(int)ContiguousStructPipe.of(Struct.ofUnknown(typeDef.getTypeClass(null))).getSizeDescriptor().getMax(WordSpace.BYTE).orElse(8);
		complexObjectIntegrityTest(info, initial, constr, typeDef, map->{
			var splitter=Splitter.map(map, new ReferenceMemoryIOMap<>(), TestUtils::checkCompliance);
			session.accept(splitter);
		}, false);
	}
	
}
