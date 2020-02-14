package com.lapissea.fsf.collections.fixedlist.headers;

import com.lapissea.fsf.INumber;
import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.UnsafeLongSupplier;

import java.io.IOException;
import java.util.function.Supplier;

public class SizedNumber<E extends INumber> extends FileObject.FullLayout<SizedNumber<E>> implements FixedLenList.ElementHead<SizedNumber<E>, E>{
	
	private static final ObjectDef<SizedNumber<?>> LAYOUT=FileObject.sequenceBuilder(
		new SingleEnumDef<>(NumberSize.class,
		                    head->head.size,
		                    (head, size)->head.size=size)
	                                                                                );
	
	private NumberSize size;
	
	private final Supplier<E>                     constructor;
	private final UnsafeLongSupplier<IOException> extraSource;
	
	public SizedNumber(Supplier<E> constructor, UnsafeLongSupplier<IOException> extraSource){
		this(constructor, null, extraSource);
	}
	
	public SizedNumber(Supplier<E> constructor, NumberSize size, UnsafeLongSupplier<IOException> extraSource){
		super((ObjectDef<SizedNumber<E>>)((Object)LAYOUT));
		this.size=size;
		this.constructor=constructor;
		this.extraSource=extraSource;
	}
	
	@Override
	public SizedNumber<E> copy(){
		return new SizedNumber<>(constructor, size, extraSource);
	}
	
	@Override
	public boolean willChange(E element) throws IOException{
		var val=element.getValue();
		return NumberSize.bySize(Math.max(val, extraSource.getAsLong())).max(size)!=size;
	}
	
	@Override
	public void update(E element) throws IOException{
		size=NumberSize.bySize(Math.max(element.getValue(), extraSource.getAsLong())).max(size);
	}
	
	@Override
	public int getElementSize(){
		return size.bytes;
	}
	
	@Override
	public E newElement(){
		return constructor.get();
	}
	
	@Override
	public void readElement(ContentInputStream src, E dest) throws IOException{
		var num=size.read(src);
		((INumber.Mutable)dest).setValue(num);
	}
	
	@Override
	public void writeElement(ContentOutputStream dest, E src) throws IOException{
		size.write(dest, src.getValue());
	}
	
	@Override
	public String toString(){
		return size+"S";
	}
}
