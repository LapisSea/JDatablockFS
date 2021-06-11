package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.access.IFieldAccessor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.LongFunction;

public class IOFieldNumber<T extends IOInstance<T>, E extends INumber> extends IOField<T, E>{
	private final NumberSize size=NumberSize.LONG;
	
	private Function<T, NumberSize> dynamicSize;
	private LongFunction<E>         constructor;
	
	public IOFieldNumber(IFieldAccessor<T> accessor){
		super(accessor);
	}
	
	@Override
	public void init(){
		super.init();
		LongFunction<E> constructor;
		try{
			var lconst=getAccessor().getType().getConstructor(long.class);
			constructor=Utils.makeLambda(lconst, LongFunction.class);
		}catch(ReflectiveOperationException ce){
			
			try{
				Method of=getAccessor().getType().getMethod("of", long.class);
				if(!Modifier.isStatic(of.getModifiers())) throw new ReflectiveOperationException(of+" not static");
				if(!Modifier.isPublic(of.getModifiers())) throw new ReflectiveOperationException(of+" not public");
				if(!of.getGenericReturnType().equals(getAccessor().getGenericType())) throw new ReflectiveOperationException(of+" does not return "+getAccessor().getGenericType());
				
				constructor=Utils.makeLambda(of, LongFunction.class);
			}catch(ReflectiveOperationException ofe){
				ofe.addSuppressed(ce);
				throw new MalformedStructLayout(getAccessor().getType().getName()+" does not have a valid constructor or of(long) static method", ofe);
			}
		}
		this.constructor=constructor;
		
		var field=IOFieldTools.getDynamicSize(getAccessor());
		
		dynamicSize=field==null?null:field::get;
	}
	
	private NumberSize getSize(T instance){
		if(dynamicSize!=null) return dynamicSize.apply(instance);
		return size;
	}
	
	@Override
	public long calcSize(T instance){
		return getSize(instance).bytes;
	}
	
	@Override
	public OptionalLong getFixedSize(){
		return dynamicSize!=null?OptionalLong.empty():OptionalLong.of(size.bytes);
	}
	
	@Override
	public void write(ContentWriter dest, T instance) throws IOException{
		var size=getSize(instance);
		size.write(dest, get(instance));
	}
	
	@Override
	public void read(ContentReader src, T instance) throws IOException{
		var size=getSize(instance);
		set(instance, constructor.apply(size.read(src)));
	}
}
