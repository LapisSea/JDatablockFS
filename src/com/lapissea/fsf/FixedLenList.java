package com.lapissea.fsf;

import com.lapissea.util.*;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("AutoBoxing")
public class FixedLenList<H extends FileObject&FixedLenList.ElementHead<H, E>, E> extends AbstractList<E> implements ShadowChunks{
	
	public interface ElementHead<SELF, E>{
		SELF copy();
		
		boolean willChange(E element);
		
		void update(E element);
		
		
		int getElementSize();
		
		E newElement();
		
		
		void readElement(ContentInputStream src, E dest) throws IOException;
		
		void writeElement(ContentOutputStream dest, E src) throws IOException;
	}
	
	
	private static final NumberSize LIST_SIZE=NumberSize.INT;
	
	private static final FileObject.SequenceLayout<FixedLenList<?, ?>> HEADER_LAYOUT=
		FileObject.sequenceBuilder(List.of(
			new FileObject.NumberDef<>(LIST_SIZE,
			                           FixedLenList::size,
			                           (l, val)->l.size=(int)val),
			new FileObject.ObjDef<>(l->l.header,
			                        (l, h)->{ throw new ShouldNeverHappenException(); },
			                        l->{ throw new ShouldNeverHappenException(); }
			)));
	
	public static <H extends FileObject&ElementHead<H, ?>> void init(ContentOutputStream out, long[] pos, H header, int initialCapacity) throws IOException{
		Chunk.init(out, pos[0], NumberSize.SHORT, (LIST_SIZE.bytes+header.length())+initialCapacity*header.getElementSize(), data->{
			LIST_SIZE.write(data, 0);
			header.write(data);
		});
	}
	
	private final Chunk       chunk;
	private final IOInterface data;
	private final H           header;
	private       int         size;
	
	
	private final WeakValueHashMap<Integer, E> cache=new WeakValueHashMap<>();
	
	public FixedLenList(Chunk chunk, H initialHead) throws IOException{
		this.chunk=chunk;
		data=chunk.io();
		header=initialHead;
		
		try(var in=data.read()){
			HEADER_LAYOUT.read(in, this);
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
			LIST_SIZE.write(out, size);
		}
		
		data.setCapacity(calcPos(size));
	}
	
	private long headerLength(){
		return HEADER_LAYOUT.length(this);
	}
	
	private long calcPos(int index){
		return headerLength()+header.getElementSize()*index;
	}
	
	public E getElement(int index) throws IOException{
		return getElement(header, index);
	}
	
	private E getElement(H header, int index) throws IOException{
		Objects.checkIndex(index, size());
		
		var cached=cache.get(index);
		
		E e=header.newElement();
		try(var in=data.read(calcPos(index))){
			header.readElement(in, e);
		}
		
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
				
				LogUtil.println(data);
				
				LogUtil.println(TextUtil.toTable("cache mismatch", List.of(disk, cache)));
				throw new AssertionError();
			}
			return cached;//TODO: disable sanity check
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

//		data.setCapacity(headerLength()+size()*header.getElementSize());
		
		try(var out=data.write(true)){
			HEADER_LAYOUT.write(out, this);
			
			
			for(E e : elementBuffer){
				header.writeElement(bufferOut, e);
				bufferOut.reset();
				
				out.write(buffer);
			}
		}
	}
	
	public void addElement(E e) throws IOException{
		LogUtil.println("adding", e);
		updateHeader(e);
		
		int index  =size();
		int newSize=index+1;
		
		cache.put(index, e);


//		LogUtil.println(data);
//		LogUtil.println(data.readAll());
		
		setSize(newSize);
		
		try(var out=data.write(calcPos(index), true)){
//			LogUtil.println(index, out);
			
			byte[] buffer=new byte[header.getElementSize()];
			header.writeElement(new ContentOutputStream.BA(buffer), e);
			out.write(buffer);

//			LogUtil.println("b", out);
		}
//		LogUtil.println(data.readAll());
	}
	
	public void setElement(int index, E element) throws IOException{
		Objects.requireNonNull(element);
		
		if(index==size()){
			addElement(element);
			return;
		}
		
		LogUtil.println("setting", element, "at", index);
		
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
		
		LogUtil.println("removing at", index);
		
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
		var dataSize=data.getCapacity()-headerLength();
		return dataSize/header.getElementSize();
	}
	
	public boolean ensureElementCapacity(int capacity) throws IOException{
		if(size() >= capacity) return false;
		var neededSize=capacity*header.getElementSize()+headerLength();
		if(data.getCapacity() >= neededSize) return false;
		data.setCapacity(neededSize);
		
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
		try{
			return header+" -> ["+stream().map(Object::toString).collect(Collectors.joining(", "))+"] raw: "+TextUtil.toString(data.readAll());
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
}
