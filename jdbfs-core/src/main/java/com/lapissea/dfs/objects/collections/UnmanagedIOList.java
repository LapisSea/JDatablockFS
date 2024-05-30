package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.RuntimeType;
import com.lapissea.dfs.type.TypeCheck;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Objects;
import java.util.StringJoiner;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public abstract class UnmanagedIOList<T, SELF extends UnmanagedIOList<T, SELF>> extends IOInstance.Unmanaged<SELF> implements IOList<T>{
	
	private IOField<SELF, ?> sizeField;
	
	public UnmanagedIOList(DataProvider provider, Chunk identity, IOType typeDef, TypeCheck check){ super(provider, identity, typeDef, check); }
	public UnmanagedIOList(DataProvider provider, Chunk identity, IOType typeDef)                 { super(provider, identity, typeDef); }
	
	public abstract RuntimeType<T> getElementType();
	
	@Override
	public boolean equals(Object o){
		if(this == o){
			return true;
		}
		if(!(o instanceof IOList<?> that)){
			return false;
		}
		
		var siz = size();
		if(siz != that.size()){
			return false;
		}
		
		if(that instanceof UnmanagedIOList<?, ?> ioL &&
		   !getElementType().equals(ioL.getElementType())){
			return false;
		}
		
		var iThis = iterator();
		var iThat = that.iterator();
		
		for(long i = 0; i<siz; i++){
			try{
				var vThis = iThis.ioNext();
				var vThat = iThat.ioNext();
				
				if(!Objects.equals(vThis, vThat)){
					return false;
				}
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		return true;
	}
	
	@Override
	public int hashCode(){
		int result = 1;
		result = 31*result + getPointer().hashCode();
		result = 31*result + getElementType().hashCode();
		return result;
	}
	
	@NotNull
	protected String getStringPrefix(){ return ""; }
	
	private String freeStr(){
		return getStringPrefix() + "{size: " + size() + ", at: " + getPointer() + " deallocated}";
	}
	
	@Override
	public String toString(){
		if(isFreed()){
			return freeStr();
		}
		
		StringJoiner sj = new StringJoiner(", ", getStringPrefix() + "{size: " + size() + "}" + "[", "]");
		IOList.elementSummary(sj, this);
		return sj.toString();
	}
	@Override
	public String toShortString(){
		if(isFreed()){
			return freeStr();
		}
		
		StringJoiner sj = new StringJoiner(", ", "[", "]");
		IOList.elementSummary(sj, this);
		return sj.toString();
	}
	
	@Override
	public boolean isFreed(){
		if(super.isFreed()) return true;
		if(DEBUG_VALIDATION && getDataProvider().getMemoryManager() != null){
			boolean dataFreed;
			try{
				dataFreed = getDataProvider().getMemoryManager().getFreeChunks().contains(getPointer());
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			if(dataFreed){
				throw new IllegalStateException();
			}
		}
		return false;
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		throw new UnsupportedOperationException();
	}
	@Override
	public void add(T value) throws IOException{
		throw new UnsupportedOperationException();
	}
	@Override
	public void remove(long index) throws IOException{
		throw new UnsupportedOperationException();
	}
	
	@Override
	public T addNew(UnsafeConsumer<T, IOException> initializer) throws IOException{
		T val = getElementType().make();
		if(initializer != null){
			initializer.accept(val);
		}
		add(val);
		if(DEBUG_VALIDATION){
			get(size() - 1);
		}
		return val;
	}
	
	protected void deltaSize(long delta) throws IOException{
		if(sizeField == null){
			var pipe = newPipe();
			sizeField = pipe.getSpecificFields().requireByName("size");
		}
		setSize(size() + delta);
		writeManagedField(sizeField);
	}
	
	protected abstract void setSize(long size);
	
	protected final void checkSize(long index){
		checkSize(index, 0);
	}
	protected final void checkSize(long index, long sizeMod){
		Objects.checkIndex(index, size() + sizeMod);
	}
}
