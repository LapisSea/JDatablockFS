package com.lapissea.cfs.objects;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.Objects;

public final class Reference extends IOInstance<Reference>{
	
	@IOValue
	@IODependency.VirtualNumSize(name="ptrSize")
	private ChunkPointer ptr;
	@IOValue
	@IODependency.VirtualNumSize(name="offsetSize")
	private long         offset;
	
	public Reference(){
		this(ChunkPointer.NULL, 0);
	}
	
	public Reference(ChunkPointer ptr, long offset){
		this.ptr=Objects.requireNonNull(ptr);
		this.offset=offset;
		if(offset<0) throw new IllegalArgumentException("Offset can not be negative");
	}
	
	public RandomIO io(ChunkDataProvider provider) throws IOException{
		ptr.requireNonNull();
		return ptr.dereference(provider).ioAt(offset);
	}
	
	public ChunkPointer getPtr(){return ptr;}
	public long getOffset()     {return offset;}
	
	@Override
	public boolean equals(Object obj){
		if(obj==this) return true;
		if(obj==null||obj.getClass()!=this.getClass()) return false;
		var that=(Reference)obj;
		return Objects.equals(this.ptr, that.ptr)&&
		       this.offset==that.offset;
	}
	@Override
	public int hashCode(){
		return Objects.hash(ptr, offset);
	}
	@Override
	public String toString(){
		return ptr+"+"+offset;
	}
	
	public boolean isNull(){
		return ptr.isNull();
	}
	
	public Reference requireNonNull(){
		ptr.requireNonNull();
		return this;
	}
	
	public Reference addOffset(long offset){
		return new Reference(getPtr(), getOffset()+offset);
	}
	public long calcGlobalOffset(ChunkDataProvider provider) throws IOException{
		try(var io=new ChunkChainIO(getPtr().dereference(provider))){
			io.setPos(getOffset());
			return io.calcGlobalPos();
		}
	}
}
