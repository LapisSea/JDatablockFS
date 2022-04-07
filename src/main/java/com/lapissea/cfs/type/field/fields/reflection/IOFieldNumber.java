package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

import static com.lapissea.cfs.objects.NumberSize.LARGEST;
import static com.lapissea.cfs.objects.NumberSize.VOID;

public class IOFieldNumber<T extends IOInstance<T>, E extends INumber> extends IOField<T, E>{
	private static final NumberSize size=NumberSize.LONG;
	
	private final boolean                                   forceFixed;
	private       BiFunction<Struct.Pool<T>, T, NumberSize> dynamicSize;
	private       LongFunction<E>                           constructor;
	private       SizeDescriptor<T>                         sizeDescriptor;
	
	public IOFieldNumber(FieldAccessor<T> accessor){
		this(accessor, false);
	}
	public IOFieldNumber(FieldAccessor<T> accessor, boolean forceFixed){
		super(accessor);
		this.forceFixed=forceFixed;
	}
	
	@Override
	public void init(){
		super.init();
		this.constructor=Access.findConstructor(getAccessor().getType(), LongFunction.class, long.class);
		
		Optional<IOField<T, NumberSize>> fieldOps=forceFixed?Optional.empty():IOFieldTools.getDynamicSize(getAccessor());
		
		fieldOps.ifPresent(f->dynamicSize=f::get);
		
		sizeDescriptor=fieldOps.map(field->SizeDescriptor.Unknown.of(VOID, Optional.of(LARGEST), field.getAccessor()))
		                       .orElse(SizeDescriptor.Fixed.of(size.bytes));
	}
	@Override
	public IOField<T, E> implMaxAsFixedSize(){
		return new IOFieldNumber<>(getAccessor(), true);
	}
	
	private NumberSize getSize(Struct.Pool<T> ioPool, T instance){
		if(dynamicSize!=null) return dynamicSize.apply(ioPool, instance);
		return size;
	}
	
	@Override
	public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var size=getSize(ioPool, instance);
		size.write(dest, get(ioPool, instance));
	}
	
	@Override
	public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var size=getSize(ioPool, instance);
		set(ioPool, instance, constructor.apply(size.read(src)));
	}
	@Override
	public void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var size=getSize(ioPool, instance);
		size.read(src);
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescriptor;
	}
}
