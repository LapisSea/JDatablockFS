package com.lapissea.fsf.collections.fixedlist;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.Utils;
import com.lapissea.fsf.chunk.*;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeIntFunction;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

@SuppressWarnings("AutoBoxing")
public class FixedLenList<H extends FileObject&FixedLenList.ElementHead<H, E>, E> extends IOList.Abstract<E>{
	
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
		init(NumberSize.BYTE, out, header, initialCapacity, addEmergency);
	}
	
	public static <H extends FileObject&ElementHead<H, ?>> void init(NumberSize nextType, ContentOutputStream out, H header, int initialCapacity, boolean addEmergency) throws IOException{
		Chunk.init(out, nextType, (header.length())+initialCapacity*header.getElementSize(), header::write);
		if(addEmergency) init(out, header.copy(), Math.max(1, initialCapacity/2), false);
	}
	
	public static <T, H extends FileObject&ElementHead<H, T>> void init(ContentOutputStream out, H header, List<T> initialElements, boolean addEmergency) throws IOException{
		Chunk.init(out, NumberSize.BYTE, (header.length())+initialElements.size()*header.getElementSize(), dest->{
			header.write(dest);
			for(T e : initialElements){
				header.writeElement(dest, e);
			}
		});
		
		if(addEmergency) init(out, header.copy(), Math.max(1, initialElements.size()/2), false);
	}
	
	private UnsafeSupplier<FixedLenList<H, E>, IOException> shadowList;
	
	private final Header header;
	
	private       H           listHeader;
	private final Supplier<H> headerType;
	
	private final ChunkIO                                               data;
	private final FixedLenList<TransactionHeader<H, E>, Transaction<E>> transactionBuffer;
	private       int                                                   size;
	
	private boolean modifying;
	
	private final Map<Integer, E> cache;
	
	/**
	 * @param headerType            Provides object that serves to define how to read/write and possibly call for reformation of all elements in this list.
	 *                              Its size must not change as the list does not support dynamic size headers. The list will not defend against this and corruption will possibly occur.
	 * @param data                  Chunk where the data will be read and written to. (and any chunks that are a part of a {@link ChunkChain} whos root is this chunk.
	 * @param transactionBufferData Chunk where emergency data will be stored in form of a transaction log that will act as shadow changes until the log is applied to main data
	 */
	public FixedLenList(Supplier<H> headerType, @NotNull Chunk data, @Nullable Chunk transactionBufferData) throws IOException{
		this.headerType=headerType;
		header=data.header;
		
		this.data=data.io();
		
		cache=header.config.newCacheMap();
		
		readListHeader();
		
		if(transactionBufferData!=null){
			var that=this;
			transactionBuffer=new FixedLenList<>(()->new TransactionHeader<>(headerType.get()), transactionBufferData, null){
				@Override
				protected void recalcSize() throws IOException{
					super.recalcSize();
					that.recalcSize();
				}
			};
		}else{
			transactionBuffer=null;
		}
		
		recalcSize();
	}
	
	private void readListHeader() throws IOException{
		var h=headerType.get();
		try(var in=data.read()){
			h.read(in);
		}
		listHeader=h;
	}
	
	private H getListHeader() throws IOException{
		return listHeader;
	}
	
	private boolean hasBackingTransactions(){
		return transactionBuffer!=null&&!transactionBuffer.isEmpty();
	}
	
	public int getElementSize() throws IOException{
		return getListHeader().getElementSize();
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
		try(var s=header.safeAlocSession()){
			if(transaction.element!=null) updateHeader(transaction.element);
			
			var oldMod=modifying;
			try{
				modifying=true;
				transaction.commit(this);
			}finally{
				if(!oldMod) modifying=false;
			}
			
			recalcSize();
			if(DEBUG_VALIDATION) checkIntegrity();
		}
	}
	
	private void doTransaction(Transaction<E> transaction) throws IOException{
		Assert(shadowList==null);
		if(modifying){
			if(transactionBuffer==null){
				throw new ConcurrentModificationException(TextUtil.toString(this, transaction));
//				LogUtil.println("warning cocurent mod", this, transaction);
//				commitTransaction(transaction);
			}else{
//				LogUtil.println("buffering", this, transaction);
				if(DEBUG_VALIDATION) checkIntegrity();
				transactionBuffer.addElement(transaction);
				if(DEBUG_VALIDATION) checkIntegrity();
			}
		}else{
//			LogUtil.println("applying", this, transaction);
			
			applyBackingTransactions();
			commitTransaction(transaction);
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
	
	private long calcDataSize() throws IOException{
		return data.getSize()-getListHeader().length();
	}
	
	protected int calcMainSize() throws IOException{
		return Math.toIntExact(calcDataSize()/getListHeader().getElementSize());
	}
	
	protected void recalcSize() throws IOException{
		var size=calcMainSize();
		if(hasBackingTransactions()){
			for(Transaction<E> t : transactionBuffer){
				size=t.action.sizeModification.resize(size);
			}
		}
		this.size=size;
	}
	
	private static final float CAPACITY_SHRINK_RATIO=3F/2;
	
	private void setSize(int size) throws IOException{
		//DO NOT REMOVE list may be accessed while setting capacity!
		this.size=size;
		
		var newSize=calcPos(size);
		data.setSize(newSize);
		
		var newCapacity=calcPos(capacityPad(size));
		if(newCapacity<data.getCapacity()) data.setCapacity(newCapacity);
		
		recalcSize();
//		Assert(size==this.size, size, this.size);
	}
	
	public long calcPos(int index) throws IOException{
		return getListHeader().length()+calcDataPos(index);
	}
	
	private int calcDataPos(int index) throws IOException{
		return getListHeader().getElementSize()*index;
	}
	
	@Override
	@NotNull
	public E getElement(int index) throws IOException{
		if(shadowList!=null) return shadowList.get().getElement(index);
		
		return Objects.requireNonNull(getElement(getListHeader(), index));
	}
	
	private int calcReserveIndex(int index) throws IOException{
		var mainElements=(data.getSize()-getListHeader().length())/getListHeader().getElementSize();
		return (int)(index-mainElements);
	}
	
	
	private List<E> transactionReconstructView(UnsafeIntFunction<E, IOException> get) throws IOException{
		
		var realSize=Math.toIntExact(calcDataSize()/getListHeader().getElementSize());
		
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
		
		Assert(cached.equals(read), cached, read, data);
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
		
		if(DEBUG_VALIDATION){
			var prev     =Thread.currentThread().getStackTrace()[2];
			var recursive=prev.getClassName().equals(Header.class.getName())&&prev.getMethodName().equals("validateFile");
			if(!recursive){
				try{
					data.getRoot().header.validateFile();
				}catch(NullPointerException ignored){}
			}
		}
		
		var disk    =new LinkedHashMap<Integer, E>();
		var cacheMap=new HashMap<Integer, E>();
		
		for(int i=0;i<size();i++){
			E d=readElement(getListHeader(), i);
			disk.put(i, d);
			var c=safeCacheFetch(getListHeader(), i);
			if(c!=null) cacheMap.put(i, c);
		}
		
		if(!Utils.isCacheValid(disk, cacheMap)){
			throw new AssertionError(getListHeader()+" "+TextUtil.toString(data.readAll())+"\n"+TextUtil.toTable("disk / cache", List.of(disk, cache)));
		}
		
	}
	
	private long calcFileSize(H listHeader, int size){
		return listHeader.length()+listHeader.getElementSize()*size;
	}
	
	private void updateHeader(E change) throws IOException{
		if(!getListHeader().willChange(change)) return;
		var oldMod=modifying;
		
		try{
			modifying=true;
			
			if(DEBUG_VALIDATION) checkIntegrity();

//			LogUtil.println("reformatting", this);
			
			ChunkPointer ptr =data.getRoot().reference();
			ChunkPointer tPtr=transactionBuffer==null?null:transactionBuffer.data.getRoot().reference();
			
			shadowList=()->{
				var dataChunk=header.getChunk(ptr);
				if(dataChunk.getSize()==0){
					shadowList=null;
					return this;
				}
				return new FixedLenList<>(headerType, dataChunk, header.getChunk(tPtr));
			};
			
			var newHeader=getListHeader().copy();
			newHeader.update(change);
			
			var newData=header.aloc(calcFileSize(newHeader, capacityPad(size())), true);
			
			newData.setSize(calcFileSize(newHeader, size()));
			
			try(var out=newData.io().write(false)){
				newHeader.write(out);
				for(int i=0;i<size();i++){
					E e=getElement(i);
					newHeader.writeElement(out, e);
				}
			}
			
			var root=data.getRoot();
			
			var oldSize=size;
			listHeader=newHeader;
			
			root.transparentChainRestart(newData);
			
			readListHeader();
			shadowList=null;
			
			if(DEBUG_VALIDATION){
				checkIntegrity();
			}
			
			recalcSize();
			Assert(oldSize==size, oldSize+" "+size);
			
		}finally{
			if(!oldMod){
				modifying=false;
				applyBackingTransactions();
			}
		}
	}
	
	private int capacityPad(int size){
		return (int)(size*CAPACITY_SHRINK_RATIO)+1;
	}
	
	@Override
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
			chain=data.getRoot().collectWholeChain();
		}
		
		try(var out=data.write(calcPos(index), false)){
			out.write(elementToBytes(getListHeader(), e));
		}
		
		if(DEBUG_VALIDATION){
			Assert(chain.equals(data.getRoot().collectWholeChain()), "\n", chain, "\n", data.getRoot().collectWholeChain());
		}
	}
	
	@Override
	public void setElement(int index, E element) throws IOException{
		Objects.requireNonNull(element);
		Objects.checkIndex(index, size());
		doTransaction(new Transaction<>(element, Action.SET, index));
	}
	
	void applySet(int index, E element) throws IOException{
		
		var buffer=elementToBytes(getListHeader(), element);
		
		cache.remove(index);
		try(var in=data.write(calcPos(index), false)){
			in.write(buffer);
		}
	}
	
	@Override
	public void removeElement(int index) throws IOException{
		Objects.checkIndex(index, size());
		doTransaction(new Transaction<>(Action.REMOVE, index));
	}
	
	void applyRemove(int index) throws IOException{
		if(index==1){
			int i=0;
		}
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
	
	@Override
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
	
	public int capacity() throws IOException{
		var dataCapacity=data.getCapacity()-getListHeader().length();
		return (int)(dataCapacity/getListHeader().getElementSize());
	}
	
	public boolean ensureElementCapacity(int capacity) throws IOException{
		Assert(shadowList==null);
		
		if(hasBackingTransactions()){
			int toAdd=capacity-size();
			if(toAdd<=0) return false;
			return transactionBuffer.ensureElementCapacity(transactionBuffer.size()+toAdd);
		}
		
		if(!isEmpty()) updateHeader(getElement(0));
		
		if(size() >= capacity) return false;
		var neededCapacity=capacity*getListHeader().getElementSize()+getListHeader().length();
		if(data.getCapacity() >= neededCapacity) return false;
		data.setCapacity(neededCapacity);
		
		return true;
	}
	
	
	/**
	 * MUST CALL CLOSE ON STREAM
	 */
	public Stream<ChunkLink> openLinkStream(Function<E, ChunkPointer> getter, BiFunction<E, ChunkPointer, E> setter) throws IOException{
		if(isEmpty()) return Stream.empty();
		
		var chainIo=data.getRoot().io().doRandom();
		int siz    =size();
		return IntStream.range(0, siz).mapToObj(i->{
			Assert(siz==size());
			try{
				chainIo.setPos(calcPos(i));
				
				var off=chainIo.getGlobalPos();
				var ptr=getter.apply(getElement(i));
				
				return new ChunkLink(false, ptr, off, val->modifyElement(i, e->setter.apply(e, val)));
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		}).onClose(()->{
			try{
				chainIo.close();
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		});
	}
	
	/////////////////////////////////////////////////////////////////////
	/////////////////////////////////////////////////////////////////////
	
	static{
		TextUtil.CUSTOM_TO_STRINGS.register(t->instanceOf(t, FixedLenList.class), t->instanceOf(t, FixedLenList.class), Object::toString);
	}
	
	@Override
	public String toString(){
		try{
			var s=new StringBuilder().append(getListHeader()).append(" -> [");
			
			if(hasBackingTransactions()){
				s.append(IntStream.range(0, calcMainSize())
				                  .mapToObj(i->{
					                  try{
						                  return readElementDirect(getListHeader(), i);
					                  }catch(IOException e){
						                  throw UtilL.uncheckedThrow(e);
					                  }
				                  })
				                  .map(Object::toString)
				                  .collect(Collectors.joining(", ")));
				s.append(" -> [");
				s.append(transactionBuffer.stream()
				                          .map(Object::toString)
				                          .collect(Collectors.joining(", ")));
				s.append(']');
			}else{
				s.append(stream().map(Object::toString).collect(Collectors.joining(", ")));
			}
			
			return s.append("]").toString();
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
}
