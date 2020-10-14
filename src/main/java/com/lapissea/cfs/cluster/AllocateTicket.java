package com.lapissea.cfs.cluster;

import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.function.Predicate;

public record AllocateTicket(long bytes, boolean disableResizing, boolean userData, Predicate<Chunk> approve, UnsafeConsumer<Chunk, IOException> dataPopulator){
	
	public static final AllocateTicket DEFAULT=new AllocateTicket(0, false, false, null, null);
	
	public AllocateTicket{
		assert bytes>=0;
	}
	
	public static AllocateTicket fitAndPopulate(IOInstance objectToFit){
		return fitTo(objectToFit).withDataPopulated(objectToFit::writeStruct);
	}
	
	public static AllocateTicket fitTo(IOInstance objectToFit){
		boolean fixedSize=objectToFit.getStruct().getKnownSize().isPresent();
		
		AllocateTicket ticket=bytes(objectToFit.getInstanceSize());
		if(fixedSize){
			ticket=ticket.withDisabledResizing();
		}
		return ticket;
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
		return new AllocateTicket(bytes, disableResizing, userData, approve, dataPopulator);
	}
	
	public AllocateTicket withApproval(Predicate<Chunk> approve){
		return new AllocateTicket(bytes, disableResizing, userData, approve, dataPopulator);
	}
	
	public AllocateTicket withDataPopulated(UnsafeBiConsumer<Cluster, RandomIO, IOException> dataPopulator){
		return withDataPopulated(c->{
			try(var io=c.io()){
				dataPopulator.accept(c.cluster, io);
			}
		});
	}
	
	public AllocateTicket withDataPopulated(UnsafeConsumer<Chunk, IOException> dataPopulator){
		return new AllocateTicket(bytes, disableResizing, userData, approve, dataPopulator);
	}
	
	public AllocateTicket withBytes(long requestedBytes){
		if(this.bytes==requestedBytes) return this;
		return new AllocateTicket(requestedBytes, disableResizing, userData, approve, dataPopulator);
	}
	
	public AllocateTicket asUserData(){
		return new AllocateTicket(bytes, disableResizing, true, approve, dataPopulator);
	}
	
	public Chunk submit(Cluster target) throws IOException{
		return target.alloc(this);
	}
	
	boolean approve(Chunk chunk){
		return approve==null||approve.test(chunk);
	}
	
	void populate(Chunk chunk) throws IOException{
		if(dataPopulator==null) return;
		dataPopulator.accept(chunk);
	}
	
}
