package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.field.access.VirtualAccessor;

import java.io.IOException;

public abstract class IOInstance<SELF extends IOInstance<SELF>>{
	
	public abstract static class Unmanaged<SELF extends Unmanaged<SELF>> extends IOInstance<SELF>{
		
		private final ChunkDataProvider provider;
		private final Reference         reference;
		
		private StructPipe<SELF> pipe;
		
		public Unmanaged(ChunkDataProvider provider, Reference reference){
			this.provider=provider;
			this.reference=reference;
		}
		
		public ChunkDataProvider getProvider(){
			return provider;
		}
		
		protected RandomIO selfIO() throws IOException{
			return reference.io(provider);
		}
		
		private StructPipe<SELF> getPipe(){
			if(pipe==null) pipe=ContiguousStructPipe.of(getThisStruct());
			return pipe;
		}
		
		protected void writeManagedFields() throws IOException{
			try(var io=selfIO()){
				getPipe().write(io, self());
			}
		}
		protected void readManagedFields() throws IOException{
			try(var io=selfIO()){
				getPipe().read(io, self());
			}
		}
		protected long calcSize(){
			long siz=getPipe().getSizeDescriptor().variable(self());
			return siz;
		}
	}
	
	private final Struct<SELF> thisStruct;
	private final Object[]     virtualFields;
	
	public IOInstance(){
		this.thisStruct=Struct.of(getClass());
		virtualFields=allocVirtual();
	}
	public IOInstance(Struct<SELF> thisStruct){
		this.thisStruct=thisStruct;
		virtualFields=allocVirtual();
	}
	
	private Object[] allocVirtual(){
		var count=(int)getThisStruct().getVirtualFields().stream().filter(c->((VirtualAccessor<SELF>)c.getAccessor()).getAccessIndex()!=-1).count();
		return count==0?null:new Object[count];
	}
	
	public Struct<SELF> getThisStruct(){
		return thisStruct;
	}
	
	//used in VirtualAccessor
	private Object accessVirtual(VirtualAccessor<SELF> accessor){
		int index=accessor.getAccessIndex();
		if(index==-1) return null;
		return virtualFields[index];
	}
	private void accessVirtual(VirtualAccessor<SELF> accessor, Object value){
		int index=accessor.getAccessIndex();
		if(index==-1) return;
		virtualFields[index]=value;
	}
	
	protected final SELF self(){return (SELF)this;}
	
	@Override
	public String toString(){
		return getThisStruct().instanceToString(self(), false);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		
		if(!(o instanceof IOInstance<?> that)) return false;
		var struct=getThisStruct();
		if(that.getThisStruct()!=struct) return false;
		
		for(var field : struct.getFields()){
			if(!field.instancesEqual(self(), (SELF)that)) return false;
		}
		
		return true;
	}
	@Override
	public int hashCode(){
		int result=1;
		
		for(var field : thisStruct.getFields()){
			result=31*result+field.instanceHashCode(self());
		}
		
		return result;
	}
}
