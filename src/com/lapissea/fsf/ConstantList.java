package com.lapissea.fsf;

import com.lapissea.util.*;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class ConstantList<T extends FileObject&Comparable<T>> extends AbstractList<T> implements ShadowChunks{
	
	public static void init(ContentOutputStream out, long[] pos, int size) throws IOException{
		Chunk.init(out, pos[0], NumberSize.SHORT, Integer.BYTES+size);
	}
	
	private final Chunk       chunk;
	private final IOInterface data;
	private final int         bytesPerElement;
	private       int         size;
	
	private final Supplier<T> newT;
	
	private final WeakValueHashMap<Integer, T> cache=new WeakValueHashMap<>();
	
	public ConstantList(Chunk chunk, int bytesPerElement, Supplier<T> newT) throws IOException{
		this.chunk=chunk;
		data=chunk.io();
		this.bytesPerElement=bytesPerElement;
		this.newT=newT;
		
		try(var in=data.read()){
			size=in.readInt();
		}catch(EOFException e){
			size=0;
		}
	}
	
	@Override
	public List<Chunk> getShadowChunks(){
		return List.of(chunk);
	}
	
	@Override
	public int size(){
		return size;
	}
	
	private void setSize(int size) throws IOException{
		this.size=size;
		try(var out=data.write(false)){
			out.writeInt(size);
		}
	}
	
	private long calcPos(int index){
		return Integer.BYTES+bytesPerElement*index;
	}
	
	public T getElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		
		Integer ind=index;
		
		var cached=cache.get(ind);
		if(cached!=null) return cached;
		
		T t=newT.get();
		try(var in=data.read(calcPos(index))){
			t.read(in);
		}
		
		cache.put(ind, t);
		
		return t;
	}
	
	@Override
	@Deprecated
	public boolean add(T t){
		try{
			addElement(t);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
		return true;
	}
	
	public void addElement(T t) throws IOException{
		int index=size();
		setSize(index+1);
		
		try(var out=data.write(calcPos(index), true)){
			t.write(out);
		}
	}
	
	@Override
	@Deprecated
	public T get(int index){
		try{
			return getElement(index);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	public void setElement(int index, T element) throws IOException{
		if(index==size()){
			addElement(element);
			return;
		}
		
		Objects.checkIndex(index, size());
		var arr =new byte[bytesPerElement];
		var buff=ByteBuffer.wrap(arr);
		
		element.write(new ContentOutputStream.Wrapp(new ByteBufferBackedOutputStream(buff)));
		
		try(var in=data.write(calcPos(index), false)){
			in.write(arr);
		}
		
		Integer iob   =index;
		var     cached=cache.get(iob);
		if(cached!=null){
			if(cached!=element){
				buff.position(0);
				cached.read(new ContentInputStream.Wrapp(new ByteBufferBackedInputStream(buff)));
			}
		}else cache.put(iob, element);
	}
	
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		int newSize=size()-1;
		
		//removing last element can be done by simply declaring it not inside list
		if(newSize==index){
			setSize(newSize);
			return;
		}
		
		T last=getElement(newSize);
		setSize(newSize);
		
		setElement(index, last);
	}
	
	@Override
	public T remove(int index){
		try{
			T old=getElement(index);
			removeElement(index);
			return old;
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	@Deprecated
	public T set(int index, T element){
		try{
			T old=getElement(index);
			setElement(index, element);
			return old;
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@NotNull
	@Override
	public Iterator<T> iterator(){
		return new Iterator<>(){
			int index;
			
			@Override
			public boolean hasNext(){
				return index<size();
			}
			
			@Override
			public T next(){
				try{
					return getElement(index++);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		};
	}
}
