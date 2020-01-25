package com.lapissea.fsf;

import com.lapissea.util.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
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
	
	public static <H extends FileObject&ElementHead<H, ?>> void init(ContentOutputStream out, H header, int initialCapacity, boolean addEmergency) throws IOException{
		init(NumberSize.SHORT, out, header, initialCapacity, addEmergency);
	}
	
	public static <H extends FileObject&ElementHead<H, ?>> void init(NumberSize nextType, ContentOutputStream out, H header, int initialCapacity, boolean addEmergency) throws IOException{
		Chunk.init(out, nextType, (header.length())+initialCapacity*header.getElementSize(), header::write);
		if(addEmergency) init(out, header.copy(), Math.max(1, initialCapacity/2), false);
	}
	
	public static <T, H extends FileObject&ElementHead<H, T>> void init(ContentOutputStream out, H header, List<T> initialElements, boolean addEmergency) throws IOException{
		Chunk.init(out, NumberSize.SHORT, (header.length())+initialElements.size()*header.getElementSize(), dest->{
			header.write(dest);
			for(T e : initialElements){
				header.writeElement(dest, e);
			}
		});
		
		if(addEmergency) init(out, header.copy(), Math.max(1, initialElements.size()/2), false);
	}
	
	private final ChunkIO            data;
	private final FixedLenList<H, E> reserveData;
	private final H                  header;
	private       int                size;
	
	private boolean modifying;
	
	private final Map<Integer, E> cache;
	
	/**
	 * @param initialHead Object that serves to define how to read/write and possibly call for reformation of all elements in this list.
	 *                    Its size must not change as the list does not support dynamic size headers. The list will not defend against this and corruption will possibly occur.
	 * @param data        Chunk where the data will be read and written to. (and any chunks that are a part of a {@link ChunkChain} whos root is this chunk.
	 * @param reserveData Chunk where emergency data will be stored to transparently prevent recursive modification (does not prevent concurrent mod!)
	 */
	public FixedLenList(H initialHead, @NotNull Chunk data, @Nullable Chunk reserveData) throws IOException{
		header=initialHead;
		this.data=data.io();
		
		cache=data.header.config.newCacheMap();
		
		try(var in=this.data.read()){
			header.read(in);
		}
		
		if(reserveData!=null){
			var that=this;
			this.reserveData=new FixedLenList<>(initialHead.copy(), reserveData, null){
				@Override
				protected void calcSize(){
					super.calcSize();
					that.calcSize();
				}
			};
		}else{
			this.reserveData=null;
		}
		
		calcSize();
	}
	
	private boolean hasReserveData(){
		return reserveData!=null&&!reserveData.isEmpty();
	}
	
	private void flushReserve() throws IOException{
		if(!hasReserveData()) return;
		
		Assert(!modifying);
		
		LogUtil.println("flushing", this);
		
		calcSize();
		ensureElementCapacity(size());
		
		while(!reserveData.isEmpty()){
			addElement(remove(size()-1));
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
	
	private long calcDataSize(){
		return data.getSize()-header.length();
	}
	
	protected void calcSize(){
		size=Math.toIntExact(calcDataSize()/header.getElementSize());
		if(hasReserveData()){
			size+=reserveData.size();
		}
	}
	
	private void setSize(int size) throws IOException{
		//DO NOT REMOVE list may be accessed while setting capacity!
		this.size=size;
		
		LogUtil.println("setSize", size);
		var newCap=calcPos(size);
		data.setCapacity(newCap);
		
		calcSize();
//		Assert(size==this.size, size, this.size);
	}
	
	public long calcPos(int index){
		return header.length()+calcDataPos(index);
	}
	
	private int calcDataPos(int index){
		return header.getElementSize()*index;
	}
	
	public E getElement(int index) throws IOException{
		return getElement(header, index);
	}
	
	private int calcReserveIndex(int index){
		var mainElements=(data.getSize()-header.length())/header.getElementSize();
		return (int)(index-mainElements);
	}
	
	private E readElement(H header, int index) throws IOException{
		E e=header.newElement();
		
		var pos=calcPos(index);
		if(data.getSize()>pos){
			try(var in=data.read(pos)){
				header.readElement(in, e);
			}
		}else{
			Objects.checkIndex(index, size());
			LogUtil.println(index, data.getSize(), pos, size());
			return reserveData.get(calcReserveIndex(index));
		}
		
		return e;
	}
	
	private E getElement(H header, int index) throws IOException{
		Objects.checkIndex(index, size());
		
		var cached=cache.get(index);
		if(!DEBUG_VALIDATION){
			if(cached!=null) return cached;
		}
		
		E e=readElement(header, index);
		
		if(DEBUG_VALIDATION){
			if(cached!=null){
				Assert(cached.equals(e));
				return cached;
			}
		}
		cache.put(index, e);
		
		return e;
	}
	
	public void checkIntegrity() throws IOException{
		Map<Integer, E> disk=new LinkedHashMap<>();
		for(int i=0;i<size();i++){
			E d=readElement(header, i);
			disk.put(i, d);
		}
		
		for(Map.Entry<Integer, E> entry : cache.entrySet()){
			var i  =entry.getKey();
			var val=entry.getValue();
			
			var diskVal=disk.get(i);
			if(!val.equals(diskVal)){
				LogUtil.println(data.getRoot().loadWholeChain());
				throw new AssertionError(header+" "+TextUtil.toString(data.readAll())+"\n"+TextUtil.toTable("disk / cache", List.of(disk, cache)));
			}
		}
	}
	
	private void updateHeader(E change) throws IOException{
		if(!header.willChange(change)) return;
		var oldMod=modifying;
		
		try{
			modifying=true;
			
			
			if(DEBUG_VALIDATION) checkIntegrity();
			
			var oldCapacity=capacity();
			var oldHeader  =header.copy();
			
			//TODO: Performance - lower memory footprint algorithm needed
			var elementBuffer=new ArrayList<>(this);
			checkIntegrity();
			
			header.update(change);
			
			ensureElementCapacity(oldCapacity);
			
			var newSiz=header.getElementSize();
			var oldSiz=oldHeader.getElementSize();
			
			byte[] buffer   =new byte[newSiz];
			var    bufferOut=new ContentOutputStream.BA(buffer);
			
			List<Chunk> chain;
			if(DEBUG_VALIDATION){
				chain=data.getRoot().loadWholeChain();
				checkIntegrity();
			}
			
			try(var out=data.write(true)){
				header.write(out);
				
				
				for(E e : elementBuffer){
					header.writeElement(bufferOut, e);
					bufferOut.reset();
					
					out.write(buffer);
				}
			}
			
			if(DEBUG_VALIDATION){
				Assert(chain.equals(data.getRoot().loadWholeChain()), "\n", chain, "\n", data.getRoot().loadWholeChain());
				checkIntegrity();
			}
			
			var oldSize=size;
			calcSize();
			Assert(oldSize==size, oldSize+" "+size);
			
		}finally{
			if(!oldMod){
				modifying=false;
				flushReserve();
			}
		}
	}
	
	public void addElement(E e) throws IOException{
		updateHeader(e);
		
		LogUtil.println("adding", e, modifying);
		
		var oldMod=modifying;
		try{
			modifying=true;
			
			
			int index  =size();
			int newSize=index+1;
			
			if(DEBUG_VALIDATION){
				checkIntegrity();
			}
			
			cache.remove(index);
			
			if(!oldMod){
				ensureElementCapacity(size()+1);
				
				List<Chunk> chain;
				if(DEBUG_VALIDATION){
					chain=data.getRoot().loadWholeChain();
				}
				
				try(var out=data.write(calcPos(index), false)){
					out.write(elementToBytes(e));
				}
				
				if(DEBUG_VALIDATION){
					Assert(chain.equals(data.getRoot().loadWholeChain()), "\n", chain, "\n", data.getRoot().loadWholeChain());
				}
				
			}else{
				LogUtil.println("reserved", e);
				reserveData.addElement(e);
			}
			cache.put(index, e);
			calcSize();
			
			if(DEBUG_VALIDATION){
				checkIntegrity();
			}
			
		}finally{
			if(!oldMod){
				modifying=false;
				flushReserve();
			}
		}
	}
	
	private byte[] elementToBytes(E e) throws IOException{
		
		byte[] elementData=new byte[header.getElementSize()];
		header.writeElement(new ContentOutputStream.BA(elementData), e);
		
		return elementData;
	}
	
	public void setElement(int index, E element) throws IOException{
		Objects.requireNonNull(element);
		
		LogUtil.println("setting", element, modifying);
		
		if(index==size()){
			addElement(element);
			return;
		}
		Objects.checkIndex(index, size());
		
		var oldMod=modifying;
		try{
			modifying=true;
			
			if(hasReserveData()){
				int directSize  =size()-reserveData.size();
				int reserveIndex=index-directSize;
				if(reserveIndex >= 0){
					reserveData.setElement(reserveIndex, element);
					return;
				}
			}
			
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
			
			if(DEBUG_VALIDATION) checkIntegrity();
			
		}finally{
			if(!oldMod){
				modifying=false;
				flushReserve();
			}
		}
		
	}
	
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		
		LogUtil.println("removing", getElement(index), modifying);
		
		var oldMod=modifying;
		try{
			modifying=true;
			
			int newSize=size()-1;
			
			if(hasReserveData()){
				int directSize  =size()-reserveData.size();
				int reserveIndex=index-directSize;
				
				if(reserveIndex >= 0){
					cache.remove(index);
					reserveData.removeElement(reserveIndex);
					calcSize();
					return;
				}
				
				int li  =reserveData.size()-1;
				E   last=reserveData.getElement(li);
				reserveData.removeElement(li);
				
				cache.remove(newSize);
				
				setElement(index, last);
				
				calcSize();
			}
			
			//removing last element can be done by simply declaring it not inside list
			if(newSize==index){
				cache.remove(newSize);
				setSize(newSize);
				return;
			}
			
			E last=getElement(newSize);
			
			cache.remove(newSize);
			setSize(newSize);
			
			setElement(index, last);
			
			if(DEBUG_VALIDATION) checkIntegrity();
			
		}finally{
			if(!oldMod){
				modifying=false;
				flushReserve();
			}
		}
	}
	
	public void modifyElement(int index, Consumer<E> modifier) throws IOException{
		E element=getElement(index);
		modifier.accept(element);
		setElement(index, element);
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
			
			@Override
			public void remove(){
				try{
					removeElement(--index);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		};
	}
	
	public int capacity(){
		var dataCapacity=data.getCapacity()-header.length();
		return (int)(dataCapacity/header.getElementSize());
	}
	
	public boolean ensureElementCapacity(int capacity) throws IOException{
		var siz=size();
		if(hasReserveData()) siz-=reserveData.size();
		if(siz >= capacity) return false;
		var neededCapacity=capacity*header.getElementSize()+header.length();
		if(data.getCapacity() >= neededCapacity) return false;
		data.setCapacity(neededCapacity);
		
		return true;
	}
	
	public void clearElements() throws IOException{
		if(isEmpty()) return;
		
		if(hasReserveData()){
			super.clear();
			return;
		}
		
		cache.clear();
		setSize(0);
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
	
	@Override
	public void clear(){
		try{
			clearElements();
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	static{
		TextUtil.CUSTOM_TO_STRINGS.register(FixedLenList.class, FixedLenList::toString);
	}
	
	@Override
	public String toString(){
		var s=new StringBuilder().append(header).append(" -> [");
		
		if(hasReserveData()){
			s.append(stream().limit(size()-reserveData.size())
			                 .map(Object::toString)
			                 .collect(Collectors.joining(", ")));
			s.append(" + ");
			s.append(reserveData.toString());
		}else{
			s.append(stream().map(Object::toString).collect(Collectors.joining(", ")));
		}
		
		return s.append("]").toString();
	}
	
}
