package com.lapissea.fsf;

import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.WeakValueHashMap;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

@SuppressWarnings("AutoBoxing")
public class FixedLenList<H extends FileObject&FixedLenList.ElementHead<H, E>, E> extends AbstractList<E> implements ShadowChunks{
	
	public interface ElementHead<SELF, E>{
		SELF copy();
		
		boolean willChange(E element) throws IOException;
		
		void update(E element) throws IOException;
		
		
		int getElementSize();
		
		E newElement();
		
		
		void readElement(ContentInputStream src, E dest) throws IOException;
		
		void writeElement(ContentOutputStream dest, E src) throws IOException;
	}
	
	public static <H extends FileObject&ElementHead<H, ?>> void init(ContentOutputStream out, H header, int initialCapacity) throws IOException{
		Chunk.init(out, NumberSize.SHORT, (header.length())+initialCapacity*header.getElementSize(), header::write);
	}
	
	public static <T, H extends FileObject&ElementHead<H, T>> void init(ContentOutputStream out, H header, List<T> initialElements) throws IOException{
		Chunk.init(out, NumberSize.SHORT, (header.length())+initialElements.size()*header.getElementSize(), dest->{
			header.write(dest);
			for(T e : initialElements){
				header.writeElement(dest, e);
			}
		});
	}
	
	private final ChunkIO data;
	private final H       header;
	private       int     size;
	
	
	private final WeakValueHashMap<Integer, E> cache=new WeakValueHashMap<>();
	
	/**
	 * @param initialHead Object that serves to define how to read/write and possibly call for reformation of all elements in this list.
	 *                    Its size must not change as the list does not support dynamic size headers. The list will not defend against this and corruption will possibly occur.
	 * @param chunk       Chunk where the data will be read and written to. (and any chunks that are a part of a {@link ChunkChain} whos root is this chunk.
	 */
	public FixedLenList(H initialHead, Chunk chunk) throws IOException{
		header=initialHead;
		data=chunk.io();
		
		try(var in=data.read()){
			header.read(in);
		}
		calcSize();
	}
	
	public int getElementSize(){
		return header.getElementSize();
	}
	
	@Override
	public List<Chunk> getShadowChunks(){
		return List.of(data.getRoot());
	}
	
	@Override
	public int size(){
		return size;
	}
	
	private long calcDataSize() throws IOException{
		return data.getSize()-header.length();
	}
	
	private void calcSize() throws IOException{
		size=Math.toIntExact(calcDataSize()/header.getElementSize());
	}
	
	private void setSize(int size) throws IOException{
		this.size=size;
		
		data.setCapacity(calcPos(size));
		
		calcSize();
		Assert(size==this.size);
	}
	
	private long calcPos(int index){
		return header.length()+header.getElementSize()*index;
	}
	
	public E getElement(int index) throws IOException{
		return getElement(header, index);
	}
	
	private E getElement(H header, int index) throws IOException{
		Objects.checkIndex(index, size());
		
		var cached=cache.get(index);
		if(!DEBUG_VALIDATION){
			if(cached!=null) return cached;
		}
		
		E e=header.newElement();
		try(var in=data.read(calcPos(index))){
			header.readElement(in, e);
		}
		
		if(DEBUG_VALIDATION){
			if(cached!=null){
				if(!e.equals(cached)){
					Map<Integer, E> disk=new LinkedHashMap<>();
					for(int i=0;i<size();i++){
						E d=header.newElement();
						try(var in=data.read(calcPos(i))){
							header.readElement(in, d);
						}
						disk.put(i, d);
					}
					
					throw new AssertionError("\n"+TextUtil.toTable("cache mismatch", List.of(disk, cache)));
				}
				return cached;
			}
		}
		
		cache.put(index, e);
		
		return e;
	}
	
	private void updateHeader(E change) throws IOException{
		if(!header.willChange(change)) return;
		
		var oldCapacity=capacity();
		var oldHeader  =header.copy();
		
		//TODO: Performance - lower memory footprint algorithm needed
		var elementBuffer=new ArrayList<>(this);
		
		header.update(change);
		
		var newSiz=header.getElementSize();
		var oldSiz=oldHeader.getElementSize();
		
		byte[] buffer   =new byte[newSiz];
		var    bufferOut=new ContentOutputStream.BA(buffer);
		
		try(var out=data.write(true)){
			header.write(out);
			
			
			for(E e : elementBuffer){
				header.writeElement(bufferOut, e);
				bufferOut.reset();
				
				out.write(buffer);
			}
		}
		
		var oldSize=size;
		calcSize();
		Assert(oldSize==size, oldSize+" "+size);
	}
	
	public void addElement(E e) throws IOException{
		updateHeader(e);
		
		int index  =size();
		int newSize=index+1;
		
		cache.put(index, e);
		
		try(var out=data.write(calcPos(index), true)){

//			byte[] buffer=new byte[header.getElementSize()];
//			var dest=new ContentOutputStream.BA(buffer);
			header.writeElement(out, e);
//			out.write(buffer);
			
		}
		
		calcSize();
	}
	
	public void setElement(int index, E element) throws IOException{
		Objects.requireNonNull(element);
		
		if(index==size()){
			addElement(element);
			return;
		}
		
		Objects.checkIndex(index, size());
		
		updateHeader(element);
		
		var buffer=new byte[header.getElementSize()];
		
		header.writeElement(new ContentOutputStream.BA(buffer), element);
		
		Integer iob   =index;
		var     cached=cache.get(iob);
		if(cached!=null){
			if(cached!=element){//if element not same object then sync existing to reflect change in references
				header.readElement(new ContentInputStream.BA(buffer), cached);
			}
		}else cache.put(iob, element);
		
		try(var in=data.write(calcPos(index), false)){
			in.write(buffer);
		}
	}
	
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		int newSize=size()-1;
		
		//removing last element can be done by simply declaring it not inside list
		if(newSize==index){
			setSize(newSize);
			cache.remove(newSize);
			return;
		}
		
		E last=getElement(newSize);
		setSize(newSize);
		cache.remove(newSize);
		
		setElement(index, last);
	}
	
	@NotNull
	@Override
	public Iterator<E> iterator(){
		return new Iterator<>(){
			int index;
			
			@Override
			public boolean hasNext(){
				return index<size();
			}
			
			@Override
			public E next(){
				try{
					return getElement(index++);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		};
	}
	
	public long capacity() throws IOException{
		var dataCapacity=data.getCapacity()-header.length();
		return dataCapacity/header.getElementSize();
	}
	
	public boolean ensureElementCapacity(int capacity) throws IOException{
		if(size() >= capacity) return false;
		var neededCapacity=capacity*header.getElementSize()+header.length();
		if(data.getCapacity() >= neededCapacity) return false;
		data.setCapacity(neededCapacity);
		
		return true;
	}
	
	/////////////////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////////////////
	
	@Override
	@Deprecated
	public boolean add(E t){
		try{
			addElement(t);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
		return true;
	}
	
	@Override
	@Deprecated
	public E get(int index){
		try{
			return getElement(index);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	@Deprecated
	public E remove(int index){
		try{
			E old=getElement(index);
			removeElement(index);
			return old;
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	@Deprecated
	public E set(int index, E element){
		try{
			E old=getElement(index);
			setElement(index, element);
			return old;
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	static{
		TextUtil.CUSTOM_TO_STRINGS.register(FixedLenList.class, FixedLenList::toString);
	}
	
	@Override
	public String toString(){
		return header+" -> ["+stream().map(Object::toString).collect(Collectors.joining(", "))+"]";
	}
}
