package com.lapissea.cfs.objects;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.IOTransaction;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;

import java.io.IOException;
import java.util.StringJoiner;

public final class Blob extends IOInstance.Unmanaged<Blob> implements IOInterface{
	
	public static Blob request(DataProvider provider, long capacity) throws IOException{
		var ch = AllocateTicket.bytes(capacity).submit(provider);
		return new Blob(provider, ch.getPtr().makeReference(), TypeLink.of(Blob.class));
	}
	
	public Blob(DataProvider provider, Reference reference, TypeLink typeDef){
		super(provider, reference, typeDef);
		assert StandardStructPipe.of(getThisStruct()).getSizeDescriptor().getMin() == 0;
	}
	
	@Override
	public boolean isReadOnly(){
		return readOnly;
	}
	
	//TODO: Implement local transaction locking and custom RandomIO
	@Override
	public IOTransaction openIOTransaction(){
		return getDataProvider().getSource().openIOTransaction();
	}
	
	@Override
	public RandomIO io() throws IOException{
		return selfIO().localTransactionBuffer(true);
	}
	
	@Override
	public String toString(){
		var res = new StringJoiner(", ", "Blob", "}");
		try(var io = selfIO()){
			res.add("size: " + io.getSize());
			res.add("capacity: " + io.getCapacity());
		}catch(IOException e){
			res.add("CORRUPTED: " + e);
		}
		return res.toString();
	}
}
