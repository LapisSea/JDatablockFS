package com.lapissea.fsf.collections.fixedlist;

import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.flags.FlagReader;
import com.lapissea.fsf.flags.FlagWriter;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;

import java.io.IOException;

public final class TransactionHeader
	<CHILD extends FileObject&FixedLenList.ElementHead<CHILD, E>, E>
	extends FileObject.FullLayout<TransactionHeader<CHILD, E>>
	implements FixedLenList.ElementHead<TransactionHeader<CHILD, E>, Transaction<E>>{
	
	private static final NumberSize ACTION_SIZE=NumberSize.BYTE;
	private static final NumberSize INDEX_SIZE =NumberSize.INT;
	
	private static final ObjectDef<TransactionHeader<?, ?>> LAYOUT=FileObject.sequenceBuilder(ObjDef.finalRef(h->h.child));
	
	private final CHILD child;
	
	@SuppressWarnings("unchecked")
	public TransactionHeader(CHILD child){
		super((ObjectDef<TransactionHeader<CHILD, E>>)((Object)LAYOUT));
		this.child=child;
	}
	
	@Override
	public TransactionHeader<CHILD, E> copy(){
		return new TransactionHeader<>(child.copy());
	}
	
	@Override
	public boolean willChange(Transaction<E> element) throws IOException{
		if(element.element==null) return false;
		return child.willChange(element.element);
	}
	
	@Override
	public void update(Transaction<E> element) throws IOException{
		child.update(element.element);
	}
	
	@Override
	public int getElementSize(){
		return ACTION_SIZE.bytes+
		       INDEX_SIZE.bytes+
		       child.getElementSize();
	}
	
	@Override
	public Transaction<E> newElement(){
		return new Transaction<>();
	}
	
	@Override
	public void readElement(ContentInputStream src, Transaction<E> dest) throws IOException{
		var flags=FlagReader.read(src, ACTION_SIZE);
		
		var hasIndex  =flags.readBoolBit();
		var hasElement=flags.readBoolBit();
		
		dest.action=flags.readEnum(Action.class);
		
		if(hasIndex) dest.index=(int)INDEX_SIZE.read(src);
		else{
			src.skipNBytes(INDEX_SIZE.bytes);
			dest.index=-1;
		}
		
		if(hasElement){
			if(dest.element==null) dest.element=child.newElement();
			child.readElement(src, dest.element);
		}else{
			src.skipNBytes(child.getElementSize());
			dest.element=null;
		}
	}
	
	@Override
	public void writeElement(ContentOutputStream dest, Transaction<E> src) throws IOException{
		var falgs=new FlagWriter(ACTION_SIZE);
		
		var hasIndex  =src.index!=-1;
		var hasElement=src.element!=null;
		
		falgs.writeBoolBit(hasIndex);
		falgs.writeBoolBit(hasElement);
		
		falgs.writeEnum(src.action);
		
		falgs.export(dest);
		
		if(hasIndex) INDEX_SIZE.write(dest, src.index);
		else dest.write(new byte[INDEX_SIZE.bytes]);
		
		if(hasElement) child.writeElement(dest, src.element);
		else dest.write(new byte[child.getElementSize()]);
	}
	
	@Override
	public String toString(){
		return "Transaction("+child+')';
	}
}
