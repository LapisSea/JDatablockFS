package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.TypeCheck;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.StringJoiner;

public abstract class UnmanagedIOSet<T> extends IOInstance.Unmanaged<UnmanagedIOSet<T>> implements IOSet<T>{
	
	
	private IOField<UnmanagedIOSet<T>, ?> sizeField;
	
	@IOValue
	private long size;
	
	protected UnmanagedIOSet(DataProvider provider, Chunk identity, IOType typeDef, TypeCheck check){
		super(provider, identity, typeDef, check);
	}
	public UnmanagedIOSet(DataProvider provider, Chunk identity, IOType typeDef){
		super(provider, identity, typeDef);
	}
	
	protected void deltaSize(long delta) throws IOException{
		size += delta;
		writeSize();
	}
	protected void deltaSize(int delta) throws IOException{
		size += delta;
		writeSize();
	}
	protected void zeroSize() throws IOException{
		size = 0;
		writeSize();
	}
	private void writeSize() throws IOException{
		if(readOnly) throw new UnsupportedOperationException();
		if(sizeField == null) sizeField = getThisStruct().getFields().requireExactLong("size");
		writeManagedField(sizeField);
	}
	
	@Override
	public final long size(){
		return size;
	}
	
	
	@Override
	public String toShortString(){
		try{
			var j = new StringJoiner(", ", "{", "}");
			
			var iter = iterator();
			while(iter.hasNext()){
				j.add(Utils.toShortString(iter.ioNext()));
			}
			
			return j.toString();
		}catch(Throwable e){
			return "CORRUPTED_SET{" + (e.getMessage() == null? e.getClass().getSimpleName() : e.getMessage()) + "}";
		}
	}
	
	@Override
	public String toString(){
		try{
			var name = this.getClass().getSimpleName();
			if(name.startsWith("IO")) name = name.substring(2);
			if(name.endsWith("Set")) name = name.substring(0, name.length() - 3);
			
			var j = new StringJoiner(", ", name + "[size=" + size() + "]{", "}");
			
			var iter = iterator();
			while(iter.hasNext()){
				j.add(Utils.toShortString(iter.ioNext()));
			}
			
			return j.toString();
		}catch(Throwable e){
			return "CORRUPTED_SET{" + (e.getMessage() == null? e.getClass().getSimpleName() : e.getMessage()) + "}";
		}
	}
	
	@Override
	public boolean equals(Object o){
		if(o == this) return true;
		if(!(o instanceof IOSet<?> that)) return false;
		
		if(that.size() != this.size()){
			return false;
		}
		
		var thatIter = that.iterator();
		try{
			while(thatIter.hasNext()){
				//noinspection unchecked
				var e = (T)thatIter.ioNext();
				if(!this.contains(e)){
					return false;
				}
			}
		}catch(IOException e){
			throw new RuntimeException(e);
		}
		
		return true;
	}
}
