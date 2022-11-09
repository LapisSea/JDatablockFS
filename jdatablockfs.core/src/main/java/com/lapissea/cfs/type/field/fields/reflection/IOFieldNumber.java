package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VaryingSize;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.LongFunction;

import static com.lapissea.cfs.objects.NumberSize.LARGEST;
import static com.lapissea.cfs.objects.NumberSize.VOID;

public class IOFieldNumber<T extends IOInstance<T>, E extends INumber> extends IOField<T, E>{
	
	private final boolean                               forceFixed;
	private final VaryingSize                           maxSize;
	private       BiFunction<VarPool<T>, T, NumberSize> dynamicSize;
	private       LongFunction<E>                       constructor;
	private       SizeDescriptor<T>                     sizeDescriptor;
	
	public IOFieldNumber(FieldAccessor<T> accessor){
		this(accessor, null);
	}
	private IOFieldNumber(FieldAccessor<T> accessor, VaryingSize maxSize){
		super(accessor);
		this.forceFixed=maxSize!=null;
		this.maxSize=maxSize==null?VaryingSize.MAX:maxSize;
	}
	
	@Override
	public void init(){
		super.init();
		this.constructor=Access.findConstructor(getAccessor().getType(), LongFunction.class, long.class);
		
		Optional<IOField<T, NumberSize>> fieldOps=forceFixed?Optional.empty():IOFieldTools.getDynamicSize(getAccessor());
		
		fieldOps.ifPresent(f->dynamicSize=f::get);
		
		sizeDescriptor=fieldOps.map(field->SizeDescriptor.Unknown.of(VOID, Optional.of(LARGEST), field.getAccessor()))
		                       .orElse(SizeDescriptor.Fixed.of(maxSize.size.bytes));
	}
	@Override
	public IOField<T, E> maxAsFixedSize(VaryingSize.Provider varProvider){
		var ptr=getAccessor().getType()==ChunkPointer.class;
		return new IOFieldNumber<>(getAccessor(), varProvider.provide(LARGEST, ptr));
	}
	
	private NumberSize getSize(VarPool<T> ioPool, T instance){
		if(dynamicSize!=null) return dynamicSize.apply(ioPool, instance);
		return maxSize.size;
	}
	private NumberSize getSafeSize(VarPool<T> ioPool, T instance, long num){
		if(dynamicSize!=null) return dynamicSize.apply(ioPool, instance);
		return maxSize.safeNumber(num);
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var oVal=get(ioPool, instance);
		var val =oVal==null?0:oVal.getValue();
		
		var size=getSafeSize(ioPool, instance, val);
		size.write(dest, val);
	}
	
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var size=getSize(ioPool, instance);
		set(ioPool, instance, constructor.apply(size.read(src)));
	}
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var size=getSize(ioPool, instance);
		size.skip(src);
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		if(sizeDescriptor==null) throw new IllegalStateException(this+" not initialized");
		return sizeDescriptor;
	}
}
