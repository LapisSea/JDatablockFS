package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.LateInit;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.ArrayList;

import static com.lapissea.cfs.logging.Log.trace;
import static com.lapissea.cfs.logging.Log.warn;
import static com.lapissea.cfs.type.StagedInit.STATE_DONE;
import static org.testng.Assert.assertEquals;

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
				trace("deleting ok session {}", sessionName);
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
			var c=Cluster.init(mem);
			try{
				session.accept(c);
			}catch(Throwable e){
				throw new RuntimeException("Failed cluster session", e);
			}
			c.rootWalker().walk(true, r->{});
		});
	}
	
	private static String getSessionName(TestInfo info){
		return info.getName();
	}
	
	static <T extends IOInstance.Unmanaged<T>> void complexObjectIntegrityTest(
		TestInfo info, int initalCapacity,
		Struct.Unmanaged.Constr<T> constr,
		TypeLink typeDef,
		UnsafeConsumer<T, IOException> session,
		boolean useCluster
	) throws IOException{
		UnsafeConsumer<DataProvider, IOException> ses=provider->{
			var chunk=AllocateTicket.bytes(initalCapacity).submit(provider);
			var ref  =chunk.getPtr().makeReference(0);
			
			T obj=constr.create(provider, ref, typeDef);
			
			var actualSize=ContiguousStructPipe.sizeOfUnknown(provider, obj, WordSpace.BYTE);
			
			if(actualSize>initalCapacity){
				warn("Initial capacity is {} but object has allocated {}", initalCapacity, actualSize);
			}
			
			if(provider instanceof Cluster c){
				c.getRootProvider().provide(obj, new ObjectID("test_obj"));
			}
			
			session.accept(obj);
			
			T read;
			try{
				read=constr.create(provider, ref, typeDef);
			}catch(Throwable e){
				throw new RuntimeException("Failed to read object with data", e);
			}
			
			assertEquals(read, obj);
		};
		
		if(useCluster){
			testCluster(info, ses::accept);
		}else{
			testChunkProvider(info, ses);
		}
	}
	
	public static <T> void checkCompliance(T test, T compliance){
		if(!test.equals(compliance)){
			throw new RuntimeException(test.getClass().getSimpleName()+" is not compliant!\n"
			                           +test+" different to: \n"
			                           +compliance);
		}
	}
	
	static <E, T extends IOInstance.Unmanaged<T>&IOList<E>> void ioListComplianceSequence(
		TestInfo info, int initalCapacity,
		Struct.Unmanaged.Constr<T> constr,
		TypeLink typeDef,
		UnsafeConsumer<IOList<E>, IOException> session, boolean useCluster
	) throws IOException{
		complexObjectIntegrityTest(info, initalCapacity, constr, typeDef, list->{
			var splitter=Splitter.list(list, IOList.wrap(new ArrayList<>()), TestUtils::checkCompliance);
			session.accept(splitter);
		}, useCluster);
	}
	
	
	static <K, V, T extends IOInstance.Unmanaged<T>&IOMap<K, V>> void ioMapComplianceSequence(
		TestInfo info,
		Struct.Unmanaged.Constr<T> constr,
		TypeLink typeDef,
		UnsafeConsumer<IOMap<K, V>, IOException> session
	) throws IOException{
		int initial=(int)ContiguousStructPipe.of(Struct.ofUnknown(typeDef.getTypeClass(null)), STATE_DONE).getSizeDescriptor().getMax(WordSpace.BYTE).orElse(8);
		complexObjectIntegrityTest(info, initial, constr, typeDef, map->{
			var splitter=Splitter.map(map, new ReferenceMemoryIOMap<>(), TestUtils::checkCompliance);
			session.accept(splitter);
		}, false);
	}
	
}
