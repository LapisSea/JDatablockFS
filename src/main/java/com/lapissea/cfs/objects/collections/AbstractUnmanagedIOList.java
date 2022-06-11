package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.RuntimeType;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Consumer;

public abstract class AbstractUnmanagedIOList<T, SELF extends AbstractUnmanagedIOList<T, SELF>> extends IOInstance.Unmanaged<SELF> implements IOList<T>{
	
	private IOField<SELF, ?> sizeField;
	
	public AbstractUnmanagedIOList(DataProvider provider, Reference reference, TypeLink typeDef, TypeLink.Check check){super(provider, reference, typeDef, check);}
	public AbstractUnmanagedIOList(DataProvider provider, Reference reference, TypeLink typeDef)                      {super(provider, reference, typeDef);}
	
	public abstract RuntimeType<T> getElementType();
	
	@Override
	public boolean equals(Object o){
		if(this==o){
			return true;
		}
		if(!(o instanceof IOList<?> that)){
			return false;
		}
		
		var siz=size();
		if(siz!=that.size()){
			return false;
		}
		
		if(that instanceof AbstractUnmanagedIOList<?, ?> ioL&&
		   !getElementType().equals(ioL.getElementType())){
			return false;
		}
		
		var iThis=iterator();
		var iThat=that.iterator();
		
		for(long i=0;i<siz;i++){
			var vThis=iThis.next();
			var vThat=iThat.next();
			
			if(!vThis.equals(vThat)){
				return false;
			}
		}
		
		return true;
	}
	
	@Override
	public int hashCode(){
		int result=1;
		result=31*result+Long.hashCode(size());
		result=31*result+getElementType().hashCode();
		return result;
	}
	
	@NotNull
	protected String getStringPrefix(){return "";}
	
	
	private void elementsStr(StringJoiner sb) throws IOException{
		var iter=iterator();
		
		var push=new Consumer<String>(){
			private String repeatBuff=null;
			private int repeatCount=0;
			private long repeatIndexStart;
			private long count=0;
			
			@Override
			public void accept(String str){
				if(repeatBuff==null){
					repeatBuff=str;
					repeatCount=1;
					repeatIndexStart=count;
					return;
				}
				if(repeatBuff.equals(str)){
					repeatCount++;
					return;
				}
				
				if(repeatCount!=0){
					flush();
				}
				
				accept(str);
			}
			private void flush(){
				if(repeatCount>4){
					if(repeatIndexStart==0) sb.add(repeatBuff+" ("+repeatCount+" times)");
					else sb.add(repeatBuff+" ("+repeatCount+" times @"+repeatIndexStart+")");
				}else{
					for(int i=0;i<repeatCount;i++){
						sb.add(repeatBuff);
					}
				}
				repeatBuff=null;
				repeatCount=0;
				repeatIndexStart=0;
			}
		};
		
		while(true){
			if(!iter.hasNext()){
				push.flush();
				return;
			}
			if(sb.length()+(push.repeatCount==0?0:(push.repeatCount*(push.repeatBuff.length()+2)+8))>300){
				push.flush();
				sb.add("... "+(size()-push.count)+" more");
				return;
			}
			var e=iter.ioNext();
			push.count++;
			
			push.accept(Utils.toShortString(e));
		}
	}
	
	@Override
	public String toString(){
		StringJoiner sj=new StringJoiner(", ", getStringPrefix()+"{size: "+size()+"}"+"[", "]");
		try{
			elementsStr(sj);
		}catch(IOException e){
			throw new RuntimeException("Failed to create toString of IOList elements", e);
		}
		return sj.toString();
	}
	@Override
	public String toShortString(){
		StringJoiner sj=new StringJoiner(", ", "[", "]");
		try{
			elementsStr(sj);
		}catch(IOException e){
			throw new RuntimeException("Failed to create toString of IOList elements", e);
		}
		return sj.toString();
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
		T val=getElementType().requireEmptyConstructor().get();
		if(initializer!=null){
			initializer.accept(val);
		}
		add(val);
		return val;
	}
	
	protected void deltaSize(long delta) throws IOException{
		if(sizeField==null){
			var pipe=newPipe();
			sizeField=pipe.getSpecificFields().byName("size").orElseThrow();
		}
		setSize(size()+delta);
		writeManagedField(sizeField);
	}
	
	protected abstract void setSize(long size);
	
	protected final void checkSize(long index){
		checkSize(index, 0);
	}
	protected final void checkSize(long index, long sizeMod){
		Objects.checkIndex(index, size()+sizeMod);
	}
}
