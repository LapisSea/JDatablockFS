package com.lapissea.fsf.collections.fixedlist;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.ShadowChunks;
import com.lapissea.fsf.Utils;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkChain;
import com.lapissea.fsf.chunk.ChunkIO;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.*;
import com.lapissea.util.function.UnsafeIntFunction;

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
	
	
	private static <E> byte[] elementToBytes(ElementHead<?, E> header, E e) throws IOException{
		byte[] elementData=new byte[header.getElementSize()];
		header.writeElement(new ContentOutputStream.BA(elementData), e);
		return elementData;
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
	
	private final Header                                                header;
	private final ChunkIO                                               data;
	private final FixedLenList<TransactionHeader<H, E>, Transaction<E>> transactionBuffer;
	private final H                                                     listHeader;
	private       int                                                   size;
	
	private boolean modifying;
	
	private final Map<Integer, E> cache;
	
	/**
	 * @param initialHead           Object that serves to define how to read/write and possibly call for reformation of all elements in this list.
	 *                              Its size must not change as the list does not support dynamic size headers. The list will not defend against this and corruption will possibly occur.
	 * @param data                  Chunk where the data will be read and written to. (and any chunks that are a part of a {@link ChunkChain} whos root is this chunk.
	 * @param transactionBufferData Chunk where emergency data will be stored in form of a transaction log that will act as shadow changes until the log is applied to main data
	 */
	public FixedLenList(H initialHead, @NotNull Chunk data, @Nullable Chunk transactionBufferData) throws IOException{
		listHeader=initialHead;
		header=data.header;
		
		this.data=data.io();
		
		cache=header.config.newCacheMap();
		
		try(var in=this.data.read()){
			listHeader.read(in);
		}
		
		if(transactionBufferData!=null){
			var that=this;
			transactionBuffer=new FixedLenList<>(new TransactionHeader<>(initialHead.copy()), transactionBufferData, null){
				@Override
				protected void calcSize(){
					super.calcSize();
					that.calcSize();
				}
			};
		}else{
			transactionBuffer=null;
		}
		
		calcSize();
	}
	
	private boolean hasBackingTransactions(){
		return transactionBuffer!=null&&!transactionBuffer.isEmpty();
	}
	
	public int getElementSize(){
		return listHeader.getElementSize();
	}
	
	private void applyBackingTransactions() throws IOException{
		Assert(!modifying);
		if(!hasBackingTransactions()) return;
		
		List<Transaction<E>> transactions=new ArrayList<>(transactionBuffer);
		
		size=calcMainSize();
		cache.entrySet().removeIf(e->e.getKey() >= size);
		transactionBuffer.clearElements();
		
		for(var transaction : transactions){
			commitTransaction(transaction);
		}
		
		applyBackingTransactions();
	}
	
	private void commitTransaction(Transaction<E> transaction) throws IOException{
		if(transaction.element!=null) updateHeader(transaction.element);

//		if(transaction.element instanceof Transaction){
//			int i=0;
//		}
//		LogUtil.println(TextUtil.toNamedPrettyJson(transaction));
		
		if(DEBUG_VALIDATION) checkIntegrity();
		
		var oldMod=modifying;
		try{
			modifying=true;
			transaction.commit(this);
		}finally{
			if(!oldMod) modifying=false;
		}
		
		calcSize();
		if(DEBUG_VALIDATION) checkIntegrity();
	}
	
	private void doTransaction(Transaction<E> transaction) throws IOException{
		
		LogUtil.println(this, transaction);
		if(modifying){
			if(transactionBuffer==null){
				throw new ConcurrentModificationException(TextUtil.toString(this, transaction));
//				LogUtil.println("warning cocurent mod", this, transaction);
//				commitTransaction(transaction);
			}else{
				if(DEBUG_VALIDATION) checkIntegrity();
				transactionBuffer.addElement(transaction);
				if(DEBUG_VALIDATION) checkIntegrity();
			}
		}else{
			applyBackingTransactions();
			try(var s=header.safeAlocSession()){
				commitTransaction(transaction);
			}
			applyBackingTransactions();
		}
	}
	
	@Override
	public List<Chunk> getShadowChunks(){
		if(transactionBuffer!=null) return List.of(data.getRoot(), transactionBuffer.getShadowChunks().get(0));
		return List.of(data.getRoot());
	}
	
	@Override
	public int size(){
		return size;
	}
	
	private long calcDataSize(){
		return data.getSize()-listHeader.length();
	}
	
	protected int calcMainSize(){
		return Math.toIntExact(calcDataSize()/listHeader.getElementSize());
	}
	
	protected void calcSize(){
		var size=calcMainSize();
		if(hasBackingTransactions()){
			for(Transaction<E> t : transactionBuffer){
				size=t.action.sizeModification.resize(size);
			}
		}
		this.size=size;
	}
	
	private void setSize(int size) throws IOException{
		//DO NOT REMOVE list may be accessed while setting capacity!
		this.size=size;
		
		var newCap=calcPos(size);
		data.setCapacity(newCap);
		
		calcSize();
//		Assert(size==this.size, size, this.size);
	}
	
	public long calcPos(int index){
		return listHeader.length()+calcDataPos(index);
	}
	
	private int calcDataPos(int index){
		return listHeader.getElementSize()*index;
	}
	
	@NotNull
	public E getElement(int index) throws IOException{
		return Objects.requireNonNull(getElement(listHeader, index));
	}
	
	private int calcReserveIndex(int index){
		var mainElements=(data.getSize()-listHeader.length())/listHeader.getElementSize();
		return (int)(index-mainElements);
	}
	
	
	private List<E> transactionReconstructView(UnsafeIntFunction<E, IOException> get){
		
		var realSize=Math.toIntExact(calcDataSize()/listHeader.getElementSize());
		
		List<Transaction.Reconstruct<E>> shadowCopy=new ArrayList<>(size());
		for(int i=0;i<realSize;i++){
			shadowCopy.add(new Transaction.Reconstruct<>(i));
		}
		
		for(var transaction : transactionBuffer){
			transaction.commit(shadowCopy);
		}
		
		return new AbstractList<>(){
			@Override
			public E get(int index){
				var reconstruct=shadowCopy.get(index);
				if(reconstruct.logValue!=null) return reconstruct.logValue;
				try{
					return get.apply(index);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
			
			@Override
			public int size(){
				return shadowCopy.size();
			}
		};
	}
	
	private E readElementDirect(H listHeader, int index) throws IOException{
		E e=listHeader.newElement();
		try(var in=data.read(calcPos(index))){
			listHeader.readElement(in, e);
		}
		return e;
	}
	
	private E readElement(H listHeader, int index) throws IOException{
		if(hasBackingTransactions()){
			List<E> view=transactionReconstructView(realId->{
				var cached=cache.get(realId);
				if(cached!=null) return cached;
				
				return readElementDirect(listHeader, realId);
			});
			
			return view.get(index);
		}
		
		return readElementDirect(listHeader, index);
	}
	
	
	private E safeCacheFetch(H listHeader, int index) throws IOException{
		if(hasBackingTransactions()){
			List<E> view=transactionReconstructView(cache::get);
			return view.get(index);
		}
		
		var cached=cache.get(index);
		if(!DEBUG_VALIDATION) return cached;
		
		if(cached==null) return null;
		
		E read=readElement(listHeader, index);
		
		Assert(cached.equals(read), cached, read);
		return cached;
	}
	
	private E getElement(H listHeader, int index) throws IOException{
		Objects.checkIndex(index, size());
		
		var cached=safeCacheFetch(listHeader, index);
		if(cached!=null) return cached;
		
		E e=readElement(listHeader, index);
		
		if(!hasBackingTransactions()) cache.put(index, e);
		
		return e;
	}
	
	public void checkIntegrity() throws IOException{
//		if(DEBUG_VALIDATION) data.getRoot().header.validateFile();
		
		var disk    =new LinkedHashMap<Integer, E>();
		var cacheMap=new HashMap<Integer, E>();
		
		for(int i=0;i<size();i++){
			E d=readElement(listHeader, i);
			disk.put(i, d);
			var c=safeCacheFetch(listHeader, i);
			if(c!=null) cacheMap.put(i, c);
		}
		
		if(!Utils.isCacheValid(disk, cacheMap)){
			throw new AssertionError(listHeader+" "+TextUtil.toString(data.readAll())+"\n"+TextUtil.toTable("disk / cache", List.of(disk, cache)));
		}
		
	}
	
	private void updateHeader(E change) throws IOException{
		if(!listHeader.willChange(change)) return;
		var oldMod=modifying;
		
		try{
			modifying=true;
			
			
			if(DEBUG_VALIDATION) checkIntegrity();
			
			var oldCapacity=capacity();
			var oldHeader  =listHeader.copy();
			
			//TODO: Performance - lower memory footprint algorithm needed
			var elementBuffer=new ArrayList<>(this);
			checkIntegrity();
			
			listHeader.update(change);
			
			ensureElementCapacity(oldCapacity);
			
			var newSiz=listHeader.getElementSize();
			var oldSiz=oldHeader.getElementSize();
			
			byte[] buffer   =new byte[newSiz];
			var    bufferOut=new ContentOutputStream.BA(buffer);
			
			List<Chunk> chain;
			if(DEBUG_VALIDATION){
				chain=data.getRoot().loadWholeChain();
				checkIntegrity();
			}
			
			try(var out=data.write(true)){
				listHeader.write(out);
				
				
				for(E e : elementBuffer){
					listHeader.writeElement(bufferOut, e);
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
				applyBackingTransactions();
			}
		}
	}
	
	public void addElement(E e) throws IOException{
		Objects.requireNonNull(e);
		doTransaction(new Transaction<>(e, Action.ADD));
	}
	
	void applyAdd(E e) throws IOException{
		
		int index  =size();
		int newSize=index+1;
		
		cache.remove(index);
		
		ensureElementCapacity(newSize);
		
		List<Chunk> chain;
		if(DEBUG_VALIDATION){
			chain=data.getRoot().loadWholeChain();
		}
		
		try(var out=data.write(calcPos(index), false)){
			out.write(elementToBytes(listHeader, e));
		}
		
		if(DEBUG_VALIDATION){
			Assert(chain.equals(data.getRoot().loadWholeChain()), "\n", chain, "\n", data.getRoot().loadWholeChain());
		}
	}
	
	public void setElement(int index, E element) throws IOException{
		Objects.requireNonNull(element);
		Objects.checkIndex(index, size());
		doTransaction(new Transaction<>(element, Action.SET, index));
	}
	
	void applySet(int index, E element) throws IOException{
		
		var buffer=elementToBytes(listHeader, element);
		
		cache.remove(index);
		try(var in=data.write(calcPos(index), false)){
			in.write(buffer);
		}
	}
	
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		doTransaction(new Transaction<>(Action.REMOVE, index));
	}
	
	void applyRemove(int index) throws IOException{
		
		int newSize=size()-1;
		
		//removing last element can be done by simply declaring it not inside list
		if(newSize==index){
			cache.remove(newSize);
			setSize(newSize);
			return;
		}
		
		E last=getElement(newSize);
		
		cache.remove(newSize);
		setSize(newSize);
		
		applySet(index, last);
	}
	
	public void clearElements() throws IOException{
		if(isEmpty()) return;
		doTransaction(new Transaction<>(Action.CLEAR));
	}
	
	void applyClear() throws IOException{
		cache.clear();
		setSize(0);
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
		var dataCapacity=data.getCapacity()-listHeader.length();
		return (int)(dataCapacity/listHeader.getElementSize());
	}
	
	public boolean ensureElementCapacity(int capacity) throws IOException{
		
		if(hasBackingTransactions()){
			int toAdd=capacity-size();
			if(toAdd<=0) return false;
			return transactionBuffer.ensureElementCapacity(transactionBuffer.size()+toAdd);
		}
		
		if(size() >= capacity) return false;
		var neededCapacity=capacity*listHeader.getElementSize()+listHeader.length();
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
		var s=new StringBuilder().append(listHeader).append(" -> [");
		
		if(hasBackingTransactions()){
			s.append(stream().limit(calcMainSize())
			                 .map(Object::toString)
			                 .collect(Collectors.joining(", ")));
			s.append(" -> ");
			s.append(transactionBuffer.stream()
			                          .map(Object::toString)
			                          .collect(Collectors.joining(", ")));
		}else{
			s.append(stream().map(Object::toString).collect(Collectors.joining(", ")));
		}
		
		return s.append("]").toString();
	}
	
}
