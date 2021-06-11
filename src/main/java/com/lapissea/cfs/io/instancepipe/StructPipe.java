package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.io.Serial;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public abstract class StructPipe<T extends IOInstance<T>>{
	
	private static class StructGroup<T extends IOInstance<T>> extends HashMap<Struct<T>, StructPipe<T>>{
		@Serial
		private static final long serialVersionUID=-4101348902993564538L;
	}
	
	private static final Map<Class<? extends StructPipe>, StructGroup<?>> CACHE     =new HashMap<>();
	private static final Lock                                             CACHE_LOCK=new ReentrantLock();
	
	public static <T extends IOInstance<T>, P extends StructPipe<T>> P of(Class<P> type, Struct<T> struct){
		try{
			CACHE_LOCK.lock();
			
			var group=(StructGroup<T>)CACHE.computeIfAbsent(type, t->new StructGroup<>());
			
			var cached=(P)group.get(struct);
			if(cached!=null) return cached;
			
			Function<Struct<T>, P> lConstructor;
			try{
				lConstructor=Utils.makeLambda(type.getConstructor(StructPipe.class.getConstructors()[0].getParameterTypes()), Function.class);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException("Failed to get pipe constructor", e);
			}
			var created=lConstructor.apply(struct);
			
			if(GlobalConfig.PRINT_COMPILATION){
				LogUtil.println(TextUtil.toTable("Compiled pipe "+type.getSimpleName()+" for: "+struct.getType().getSimpleName(), created.getSpecificFields()));
			}
			
			group.put(struct, created);
			return created;
		}finally{
			CACHE_LOCK.unlock();
		}
	}
	
	private final Struct<T>         type;
	private       SizeDescriptor<T> sizeDescription;
	
	public StructPipe(Struct<T> type){
		this.type=type;
	}
	
	public abstract void write(ContentWriter dest, T instance) throws IOException;
	public abstract T read(ContentReader src, T instance) throws IOException;
	
	public SizeDescriptor<T> getSizeDescriptor(){
		if(sizeDescription==null){
			var fields=getSpecificFields();
			var fixed =IOFieldTools.sumVarsIfAll(fields, SizeDescriptor::fixed);
			if(fixed.isPresent()) sizeDescription=new SizeDescriptor.Fixed<>(WordSpace.BYTE, fixed.getAsLong());
			else{
				sizeDescription=new SizeDescriptor.Unknown<T>(WordSpace.BYTE, IOFieldTools.sumVars(fields, SizeDescriptor::min), IOFieldTools.sumVarsIfAll(fields, SizeDescriptor::max)){
					@Override
					public long variable(T instance){
						return IOFieldTools.sumVars(fields, d->d.variable(instance));
					}
				};
			}
		}
		return sizeDescription;
	}
	
	public T readNew(ContentReader src) throws IOException{
		T instance=type.requireEmptyConstructor().get();
		return read(src, instance);
	}
	
	public Struct<T> getType(){
		return type;
	}
	
	protected abstract List<IOField<T, ?>> getSpecificFields();
}
