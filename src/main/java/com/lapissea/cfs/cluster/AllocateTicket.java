package com.lapissea.cfs.cluster;

import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.util.function.UnsafeBiConsumer;

import java.io.IOException;
import java.util.function.Predicate;

public record AllocateTicket(long requestedBytes, boolean disableResizing, boolean userData, Predicate<Chunk> approve, UnsafeBiConsumer<Cluster, RandomIO, IOException> dataPopulator){
	
	public static final AllocateTicket DEFAULT=new AllocateTicket(0, false, false, null, null);
	
	public AllocateTicket{
		assert requestedBytes>=0;
	}
	
	public static AllocateTicket fitAndPopulate(IOInstance objectToFit){
		return fitTo(objectToFit).withDataPopulated(objectToFit::writeStruct);
	}
	
	public static AllocateTicket fitTo(IOInstance objectToFit){
		if(objectToFit.getStruct().getKnownSize().isEmpty()){
			return bytes(objectToFit.getInstanceSize()).withDisabledResizing();
		}
		return bytes(objectToFit.getInstanceSize());
	}
	
	public static AllocateTicket bytes(long requestedBytes){
		return DEFAULT.withBytes(requestedBytes);
	}
	
	public static AllocateTicket user(){
		return DEFAULT.asUserData();
	}
	
	public AllocateTicket withDisabledResizing(){
		return shouldDisableResizing(true);
	}
	
	public AllocateTicket shouldDisableResizing(boolean disableResizing){
		if(this.disableResizing==disableResizing) return this;
		return new AllocateTicket(requestedBytes, disableResizing, userData, approve, dataPopulator);
	}
	
	public AllocateTicket withApproval(Predicate<Chunk> approve){
		return new AllocateTicket(requestedBytes, disableResizing, userData, approve, dataPopulator);
	}
	
	public AllocateTicket withDataPopulated(UnsafeBiConsumer<Cluster, RandomIO, IOException> dataPopulator){
		return new AllocateTicket(requestedBytes, disableResizing, userData, approve, dataPopulator);
	}
	
	public AllocateTicket withBytes(long requestedBytes){
		if(this.requestedBytes==requestedBytes) return this;
		return new AllocateTicket(requestedBytes, disableResizing, userData, approve, dataPopulator);
	}
	
	public AllocateTicket asUserData(){
		return new AllocateTicket(requestedBytes, disableResizing, true, approve, dataPopulator);
	}
	
	public Chunk submit(Cluster target) throws IOException{
		return target.alloc(this);
	}
	
	boolean approve(Chunk chunk){
		return approve==null||approve.test(chunk);
	}
	
	void populate(Chunk chunk) throws IOException{
		if(dataPopulator==null) return;
		try(var io=chunk.io()){
			dataPopulator.accept(chunk.cluster, io);
		}
	}
	
}
