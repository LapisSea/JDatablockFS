package com.lapissea.cfs.objects;

import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.util.TextUtil;
import com.lapissea.util.WeakValueHashMap;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static com.lapissea.cfs.Config.*;

public class StructFlatList<T extends IOStruct.Instance> implements IOList<T>{
	
	private Chunk data;
	private int   size;
	
	private final Supplier<T> constructor;
	
	private final int elementSize;
	
	private final Map<Integer, T> cache=new WeakValueHashMap<>();
	
	
	private static <T extends IOStruct.Instance> Supplier<T> makeConstructor(Class<T> type){
		Constructor<T> c;
		try{
			c=type.getConstructor();
			c.setAccessible(true);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
		return ()->{
			try{
				return c.newInstance();
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		};
	}
	
	public StructFlatList(Chunk data, Class<T> type) throws IOException{
		this(data, makeConstructor(type));
	}
	
	public StructFlatList(Chunk data, Supplier<T> constructor) throws IOException{
		this.data=data;
		this.constructor=constructor;
		
		IOStruct struct=constructor.get().structType();
		elementSize=Math.toIntExact(struct.requireKnownSize());
		
		calcSize();
	}
	
	private long calcElementOffset(int index){
		return /*this.getInstanceSize()+*/elementSize*index;
	}
	
	@Override
	public int size(){ return size; }
	
	private void write(int index, T value) throws IOException{
		cache.remove(index);
		data.ioAt(calcElementOffset(index), value::writeStruct);
		cache.put(index, value);
	}
	
	private T read(int index) throws IOException{
		T val=constructor.get();
		data.ioAt(calcElementOffset(index), val::readStruct);
		return val;
	}
	
	
	@Override
	public T getElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		
		validate();
		
		final Integer oIndex=index;
		
		T cached=cache.get(oIndex);
		if(DEBUG_VALIDATION){
			if(cached!=null){
				T read=read(index);
				
				assert read.equals(cached):"\n"+TextUtil.toTable("cached/read mismatch", List.of(cached, read));
			}
		}
		
		if(cached==null){
			cached=read(index);
			cache.put(oIndex, cached);
		}
		
		validate();
		
		return cached;
	}
	
	@Override
	public void setElement(int index, T value) throws IOException{
		Objects.checkIndex(index, size());
		Objects.requireNonNull(value);
		
		validate();
		
		T prev=getElement(index);
		if(prev.equals(value)) return;
		
		write(index, value);
		
		validate();
	}
	
	@Override
	public void ensureCapacity(int elementCapacity) throws IOException{
		long end=calcElementOffset(elementCapacity+1);
		
		validate();
		
		data.io(io->{
			if(io.getCapacity()>=end) return;
			io.setCapacity(end);
		});
		
		validate();
	}
	
	private void moveFromTo(int from, int to) throws IOException{
		assert from!=to:from;
		
		Integer oFrom=from;
		Integer oTo  =to;
		
		long fromOffset=calcElementOffset(from);
		long toOffset  =calcElementOffset(to);
		
		T fromEl=cache.remove(oFrom);
		cache.remove(oTo);
		
		
		data.io(io->{
			if(fromEl==null){
				byte[] lastData=io.setPos(fromOffset)
				                  .readInts1(elementSize);
				io.setPos(toOffset)
				  .write(lastData);
			}else{
				io.setPos(toOffset);
				fromEl.writeStruct(io);
				cache.put(oTo, fromEl);
			}
		});
	}
	
	@Override
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		
		if(index+1==size()){
			var newSize=calcElementOffset(index);
			data.io(io->io.setSize(newSize));
			cache.remove(index);
		}else{
			
			data.io(io->{
				int     lastIndex=size()-1;
				Integer oIndex   =index;
				
				moveFromTo(lastIndex, index);
				
				var off=calcElementOffset(lastIndex);
				io.setSize(off);
			});
		}
		
		sizeChange(-1);
		
	}
	
	@Override
	public void addElement(int index, T value) throws IOException{
		Objects.checkIndex(index, size()+1);
		Objects.requireNonNull(value);
		
		if(index<size()){
			moveFromTo(index, size());
		}
		
		write(index, value);
		
		sizeChange(1);
	}
	
	private void sizeChange(int change) throws IOException{
		if(DEBUG_VALIDATION){
			var expectedSize=size()+change;
			calcSize();
			assert expectedSize==size():expectedSize+" != "+size();
		}else{
			size+=change;
		}
	}
	
	private void calcSize() throws IOException{
		long size;
		try(var io=data.io()){
			size=io.getSize();
		}
		
		var dataSize=size/*-getInstanceSize()*/;
		
		assert dataSize%elementSize==0;
		
		var elementCount=dataSize/elementSize;
		this.size=Math.toIntExact(elementCount);
	}
	
	@Override
	public void free() throws IOException{
		clear();
		var d=data;
		data=null;
		d.freeChaining();
	}
	
	@Override
	public String toString(){
		try{
			if(size()==0) return "[]";
			
			StringBuilder result=new StringBuilder("[");
			for(int i=0;;){
				result.append(TextUtil.toString(getElement(i)));
				i++;
				if(i==size()) break;
				result.append(", ");
			}
			result.append(']');
			return result.toString();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void validate() throws IOException{
		if(!DEBUG_VALIDATION) return;
		
		sizeChange(0);
		for(var e : cache.entrySet()){
			T   cached=e.getValue();
			int index =e.getKey();
			
			if(cached==null) continue;
			
			assert index<size:index+"<"+size;
			
			T read=read(e.getKey());
			
			assert read.equals(cached):"\n"+TextUtil.toTable("cached/read mismatch", List.of(cached, read));
		}
	}
}
