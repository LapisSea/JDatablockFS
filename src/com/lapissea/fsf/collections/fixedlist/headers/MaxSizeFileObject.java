package com.lapissea.fsf.collections.fixedlist.headers;

import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;

import java.io.IOException;
import java.util.function.Supplier;

public class MaxSizeFileObject<E extends FileObject> extends FileObject.FullLayout<MaxSizeFileObject<E>> implements FixedLenList.ElementHead<MaxSizeFileObject<E>, E>{
	
	private static final ObjectDef<MaxSizeFileObject<?>> LAYOUT=new FileObject.NumberDef<>(NumberSize.BIG_SHORT, h->h.maxElementSize, (h, v)->h.maxElementSize=(int)v);
	
	private final Supplier<E> constructor;
	
	private int maxElementSize=1;
	
	public MaxSizeFileObject(Supplier<E> constructor){
		super((ObjectDef<MaxSizeFileObject<E>>)((Object)LAYOUT));
		this.constructor=constructor;
	}
	
	@Override
	public MaxSizeFileObject<E> copy(){
		return new MaxSizeFileObject<>(constructor);
	}
	
	@Override
	public boolean willChange(E element){
		return element.length()>maxElementSize;
	}
	
	@Override
	public void update(E element){
		maxElementSize=Math.toIntExact(element.length());
	}
	
	@Override
	public int getElementSize(){
		return maxElementSize;
	}
	
	@Override
	public E newElement(){
		return constructor.get();
	}
	
	@Override
	public void readElement(ContentInputStream src, E dest) throws IOException{
		dest.read(src);
	}
	
	@Override
	public void writeElement(ContentOutputStream dest, E src) throws IOException{
		src.write(dest);
	}
	
	@Override
	public String toString(){
		return "OBJ";
	}
}
