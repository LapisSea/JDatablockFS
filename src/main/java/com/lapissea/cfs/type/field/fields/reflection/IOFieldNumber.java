package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.function.Function;
import java.util.function.LongFunction;

public class IOFieldNumber<T extends IOInstance<T>, E extends INumber> extends IOField<T, E>{
	private final NumberSize size=NumberSize.LONG;
	
	private final boolean                 forceFixed;
	private       Function<T, NumberSize> dynamicSize;
	private       LongFunction<E>         constructor;
	private       SizeDescriptor<T>       sizeDescriptor;
	
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
		this.constructor=Utils.findConstructor(getAccessor().getType(), LongFunction.class, long.class);
		
		var field=forceFixed?null:IOFieldTools.getDynamicSize(getAccessor());
		
		dynamicSize=field==null?null:field::get;
		
		if(dynamicSize==null) sizeDescriptor=new SizeDescriptor.Fixed<>(size.bytes);
		else sizeDescriptor=new SizeDescriptor.Unknown<>(0, NumberSize.LARGEST.optionalBytesLong, inst->getSize(inst).bytes);
	}
	@Override
	public IOField<T, E> implMaxAsFixedSize(){
		return new IOFieldNumber<>(getAccessor(), true);
	}
	
	private NumberSize getSize(T instance){
		if(dynamicSize!=null) return dynamicSize.apply(instance);
		return size;
	}
	
	@Override
	public void write(DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var size=getSize(instance);
		size.write(dest, get(instance));
	}
	
	@Override
	public void read(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var size=getSize(instance);
		set(instance, constructor.apply(size.read(src)));
	}
	@Override
	public void skipRead(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var size=getSize(instance);
		size.read(src);
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescriptor;
	}
}
