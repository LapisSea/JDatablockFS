package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.StringJoiner;

public abstract class AbstractUnmanagedIOSet<T> extends IOInstance.Unmanaged<AbstractUnmanagedIOSet<T>> implements IOSet<T>{
	
	
	private IOField<AbstractUnmanagedIOSet<T>, ?> sizeField;
	
	@IOValue
	private long size;
	
	protected AbstractUnmanagedIOSet(DataProvider provider, Reference reference, TypeLink typeDef, TypeLink.Check check){
		super(provider, reference, typeDef, check);
	}
	public AbstractUnmanagedIOSet(DataProvider provider, Reference reference, TypeLink typeDef){
		super(provider, reference, typeDef);
	}
	
	protected void deltaSize(long delta) throws IOException{
		size += delta;
		writeSize();
	}
	protected void deltaSize(int delta) throws IOException{
		size += delta;
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
}
