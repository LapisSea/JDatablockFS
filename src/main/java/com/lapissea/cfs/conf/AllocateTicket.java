package com.lapissea.cfs.conf;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.objects.IOType;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.function.Predicate;

public record AllocateTicket(long bytes, boolean disableResizing, @Nullable IOType userData, @Nullable Predicate<Chunk> approve, @Nullable UnsafeConsumer<Chunk, IOException> dataPopulator){
	
	public static final AllocateTicket DEFAULT=new AllocateTicket(0, false, null, null, null);
	
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
	
	public static AllocateTicket fitTo(Class<? extends IOInstance> typeToFit){
		return fitTo(IOStruct.get(typeToFit));
	}
	
	public static AllocateTicket fitTo(IOStruct typeToFit){
		AllocateTicket ticket=bytes(typeToFit.getKnownSize().orElse(typeToFit.getMinimumSize()));
		if(typeToFit.getKnownSize().isPresent()){
			ticket=ticket.withDisabledResizing();
		}
		return ticket;
	}
	
	public static AllocateTicket bytes(long requestedBytes){
		return DEFAULT.withBytes(requestedBytes);
	}
	
	public static AllocateTicket user(@NotNull IOType type){
		return DEFAULT.asUserData(type);
	}
	
	public static AllocateTicket approved(Predicate<Chunk> approve){
		return DEFAULT.withApproval(approve);
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
	
	public AllocateTicket asUserData(IOType type){
		return new AllocateTicket(bytes, disableResizing, type, approve, dataPopulator);
	}
	
	public Chunk submit(Cluster target) throws IOException{
		return target.alloc(this);
	}
	
	public boolean approve(Chunk chunk){
		return approve==null||approve.test(chunk);
	}
	
	public void populate(Chunk chunk) throws IOException{
		if(dataPopulator==null) return;
		dataPopulator.accept(chunk);
	}
	
}
