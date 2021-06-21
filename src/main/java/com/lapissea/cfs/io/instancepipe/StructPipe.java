package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.FieldSet;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.*;

public abstract class StructPipe<T extends IOInstance<T>>{
	
	private static class StructGroup extends HashMap<Struct<?>, StructPipe<?>>{
		
		private final Function<Struct<?>, StructPipe<?>> lConstructor;
		
		private StructGroup(Class<? extends StructPipe<?>> type){
			try{
				lConstructor=Utils.makeLambda(type.getConstructor(Struct.class), Function.class);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException("Failed to get pipe constructor", e);
			}
		}
		
		@SuppressWarnings("unchecked")
		<T extends IOInstance<T>> StructPipe<T> make(Struct<T> struct){
			StructPipe<?> cached=get(struct);
			if(cached!=null) return (StructPipe<T>)cached;
			
			StructPipe<?> created=lConstructor.apply(struct);
			
			if(GlobalConfig.PRINT_COMPILATION){
				LogUtil.println(ConsoleColors.CYAN_BRIGHT+"Compiled "+struct.getType().getSimpleName()+" with "+TextUtil.toNamedPrettyJson(created, true)+ConsoleColors.RESET);
			}
			
			put(struct, created);
			return (StructPipe<T>)created;
		}
	}
	
	private static final Map<Class<? extends StructPipe<?>>, StructGroup> CACHE     =new HashMap<>();
	private static final Lock                                             CACHE_LOCK=new ReentrantLock();
	
	public static <T extends IOInstance<T>, P extends StructPipe<T>> StructPipe<T> of(Class<P> type, Struct<T> struct){
		try{
			CACHE_LOCK.lock();
			
			StructGroup group=CACHE.computeIfAbsent(type, StructGroup::new);
			
			return group.make(struct);
		}finally{
			CACHE_LOCK.unlock();
		}
	}
	
	private final Struct<T>                type;
	private final SizeDescriptor<T>        sizeDescription;
	private final FieldSet<T, ?>           ioFields;
	private final List<VirtualAccessor<T>> ioPoolAccessors;
	private final List<IFieldAccessor<T>>  earlyNullChecks;
	
	public StructPipe(Struct<T> type){
		this.type=type;
		List<IOField<T, ?>> ioFields=initFields();
		this.ioFields=new FieldSet<>(ioFields);
		sizeDescription=calcSize();
		ioPoolAccessors=nullIfEmpty(calcIOPoolAccessors());
		earlyNullChecks=nullIfEmpty(getNonNulls());
	}
	
	private List<IFieldAccessor<T>> getNonNulls(){
		return unpackedFields().filter(f->f.getNullability()==IONullability.Mode.NOT_NULL).map(IOField::getAccessor).toList();
	}
	
	protected abstract List<IOField<T, ?>> initFields();
	
	private Stream<? extends IOField<T, ?>> unpackedFields(){
		return ioFields.stream().flatMap(IOField::streamUnpackedFields);
	}
	
	private List<VirtualAccessor<T>> calcIOPoolAccessors(){
		return unpackedFields().map(IOField::getAccessor)
		                       .filter(a->a instanceof VirtualAccessor)
		                       .map(a->(VirtualAccessor<T>)a)
		                       .filter(a->a.getStoragePool()==VirtualFieldDefinition.StoragePool.IO)
		                       .toList();
	}
	private <E, C extends Collection<E>> C nullIfEmpty(C collection){
		if(collection.isEmpty()) return null;
		return collection;
	}
	
	private SizeDescriptor<T> calcSize(){
		var fields=getSpecificFields();
		var fixed =IOFieldTools.sumVarsIfAll(fields, desc->desc.toBytes(desc.getFixed()));
		if(fixed.isPresent()) return new SizeDescriptor.Fixed<>(fixed.getAsLong());
		else{
			var unknownFields=fields.stream().filter(f->!f.getSizeDescriptor().hasFixed()).toList();
			var knownFixed   =IOFieldTools.sumVars(fields, d->d.toBytes(d.getFixed().orElse(0)));
			return new SizeDescriptor.Unknown<>(IOFieldTools.sumVars(fields, siz->siz.toBytes(siz.getMin())), IOFieldTools.sumVarsIfAll(fields, siz->siz.toBytes(siz.getMax()))){
				@Override
				public long calcUnknown(T instance){
					return knownFixed+IOFieldTools.sumVars(unknownFields, d->d.calcUnknown(instance));
				}
			};
		}
	}
	
	
	public void write(RandomIO.Creator dest, T instance) throws IOException{
		earlyCheckNulls(instance);
		try(var io=dest.io()){
			doWrite(io, instance);
		}
	}
	public void write(ContentWriter dest, T instance) throws IOException{
		earlyCheckNulls(instance);
		doWrite(dest, instance);
	}
	protected abstract void doWrite(ContentWriter dest, T instance) throws IOException;
	
	
	public <Prov extends ChunkDataProvider.Holder&RandomIO.Creator> T readNew(Prov src) throws IOException{
		try(var io=src.io()){
			return readNew(src.getChunkProvider(), io);
		}
	}
	public T readNew(ChunkDataProvider provider, RandomIO.Creator src) throws IOException{
		try(var io=src.io()){
			return readNew(provider, io);
		}
	}
	public T readNew(ChunkDataProvider provider, ContentReader src) throws IOException{
		T instance=type.requireEmptyConstructor().get();
		return doRead(provider, src, instance);
	}
	
	public T read(ChunkDataProvider provider, RandomIO.Creator src, T instance) throws IOException{
		try(var io=src.io()){
			return doRead(provider, io, instance);
		}
	}
	public <Prov extends ChunkDataProvider.Holder&RandomIO.Creator> T read(Prov src, T instance) throws IOException{
		try(var io=src.io()){
			return doRead(src.getChunkProvider(), io, instance);
		}
	}
	public T read(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
		return doRead(provider, src, instance);
	}
	protected abstract T doRead(ChunkDataProvider provider, ContentReader src, T instance) throws IOException;
	
	
	public SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescription;
	}
	
	public Struct<T> getType(){
		return type;
	}
	
	public FieldSet<T, ?> getSpecificFields(){
		return ioFields;
	}
	
	protected List<VirtualAccessor<T>> getIoPoolAccessors(){
		return ioPoolAccessors;
	}
	
	public void earlyCheckNulls(T instance){
		if(earlyNullChecks==null) return;
		for(var accessor : earlyNullChecks){
			if(accessor.get(instance)==null){
				throw new NullPointerException(accessor+" is null");
			}
		}
	}
	
	protected void writeIOFields(ContentWriter dest, T instance) throws IOException{
		Object[] ioPool=makeIOPool();
		try{
			pushPool(ioPool);
			
			if(DEBUG_VALIDATION){
				for(IOField<T, ?> field : getSpecificFields()){
					var  desc =field.getSizeDescriptor();
					long bytes=desc.toBytes(desc.calcUnknown(instance));
					
					var buf=dest.writeTicket(bytes).requireExact().submit();
					field.writeReported(buf, instance);
					
					try{
						buf.close();
					}catch(Exception e){
						throw new IOException(TextUtil.toString(field)+" did not write correctly", e);
					}
				}
			}else{
				for(IOField<T, ?> field : getSpecificFields()){
					field.writeReported(dest, instance);
				}
			}
			
		}finally{
			popPool();
		}
	}
	public void readIOFields(ChunkDataProvider provider, ContentReader src,
	                         T instance)
		throws IOException{
		Object[] ioPool=makeIOPool();
		try{
			pushPool(ioPool);
			
			if(DEBUG_VALIDATION){
				for(IOField<T, ?> field : getSpecificFields()){
//					var debStr=instance.getThisStruct().instanceToString(instance, false);
					
					var desc=field.getSizeDescriptor();
					if(desc.hasFixed()){
						long bytes=desc.toBytes(desc.requireFixed());
						
						var buf=src.readTicket(bytes).requireExact().submit();
						field.readReported(provider, buf, instance);
						try{
							buf.close();
						}catch(Exception e){
							throw new IOException(TextUtil.toString(field)+" did not read correctly", e);
						}
					}else{
						field.readReported(provider, src, instance);
					}
				}
			}else{
				for(IOField<T, ?> field : getSpecificFields()){
					field.readReported(provider, src, instance);
				}
			}
			
		}finally{
			popPool();
		}
	}
	private void pushPool(Object[] ioPool){
		if(ioPool==null) return;
		for(var a : getIoPoolAccessors()){
			a.pushIoPool(ioPool);
		}
	}
	private void popPool(){
		if(getIoPoolAccessors()==null) return;
		for(var a : getIoPoolAccessors()){
			a.popIoPool();
		}
	}
	
	protected Object[] makeIOPool(){
		return getType().allocVirtualVarPool(VirtualFieldDefinition.StoragePool.IO);
	}
	
}
