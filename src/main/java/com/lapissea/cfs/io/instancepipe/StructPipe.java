package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

public abstract class StructPipe<T extends IOInstance<T>>{
	
	private static class StructGroup<P extends StructPipe<?>> extends HashMap<Struct<?>, P>{
		
		private final Function<Struct<?>, P> lConstructor;
		private final Class<P>               type;
		
		private StructGroup(Class<P> type){
			this.type=type;
			try{
				lConstructor=Utils.makeLambda(type.getConstructor(Struct.class), Function.class);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException("Failed to get pipe constructor", e);
			}
		}
		
		<T extends IOInstance<T>> P make(Struct<T> struct){
			var cached=get(struct);
			if(cached!=null) return cached;
			
			P created=lConstructor.apply(struct);
			
			if(GlobalConfig.PRINT_COMPILATION){
				LogUtil.println("Compiled "+struct.getType().getSimpleName()+" with "+TextUtil.toNamedPrettyJson(created, true));
			}
			
			put(struct, created);
			return created;
		}
	}
	
	private static final Map<Class<? extends StructPipe<?>>, StructGroup<?>> CACHE     =new HashMap<>();
	private static final Lock                                                CACHE_LOCK=new ReentrantLock();
	
	public static <T extends IOInstance<T>, P extends StructPipe<T>> P of(Class<P> type, Struct<T> struct){
		try{
			CACHE_LOCK.lock();
			
			StructGroup<P> group=(StructGroup<P>)CACHE.computeIfAbsent(type, StructGroup::new);
			
			return group.make(struct);
		}finally{
			CACHE_LOCK.unlock();
		}
	}
	
	private final Struct<T>         type;
	private       SizeDescriptor<T> sizeDescription;
	
	public StructPipe(Struct<T> type){
		this.type=type;
	}
	
	public void write(RandomIO.Creator dest, T instance) throws IOException{
		try(var io=dest.io()){
			write(io, instance);
		}
	}
	public T read(ChunkDataProvider provider, RandomIO.Creator src, T instance) throws IOException{
		try(var io=src.io()){
			return read(provider, io, instance);
		}
	}
	
	public abstract void write(ContentWriter dest, T instance) throws IOException;
	public abstract T read(ChunkDataProvider provider, ContentReader src, T instance) throws IOException;
	
	public SizeDescriptor<T> getSizeDescriptor(){
		if(sizeDescription==null){
			var fields=getSpecificFields();
			var fixed =IOFieldTools.sumVarsIfAll(fields, desc->desc.toBytes(desc.getFixed()));
			if(fixed.isPresent()) sizeDescription=new SizeDescriptor.Fixed<>(fixed.getAsLong());
			else{
				var unknownFields=fields.stream().filter(f->!f.getSizeDescriptor().hasFixed()).toList();
				var knownFixed   =IOFieldTools.sumVars(fields, d->d.getFixed().orElse(0));
				sizeDescription=new SizeDescriptor.Unknown<>(IOFieldTools.sumVars(fields, siz->siz.toBytes(siz.getMin())), IOFieldTools.sumVarsIfAll(fields, siz->siz.toBytes(siz.getMax()))){
					@Override
					public long calcUnknown(T instance){
						return knownFixed+IOFieldTools.sumVars(unknownFields, d->d.calcUnknown(instance));
					}
				};
			}
		}
		return sizeDescription;
	}
	
	public T readNew(ChunkDataProvider provider, RandomIO.Creator src) throws IOException{
		try(var io=src.io()){
			return readNew(provider, io);
		}
	}
	public T readNew(ChunkDataProvider provider, ContentReader src) throws IOException{
		T instance=type.requireEmptyConstructor().get();
		return read(provider, src, instance);
	}
	
	public Struct<T> getType(){
		return type;
	}
	
	public abstract List<IOField<T, ?>> getSpecificFields();
}
