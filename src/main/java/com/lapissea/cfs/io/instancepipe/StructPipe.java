package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.UnknownSizePredictionException;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.lapissea.cfs.GlobalConfig.*;

public abstract class StructPipe<T extends IOInstance<T>>{
	
	private static class StructGroup<T extends IOInstance<T>, P extends StructPipe<T>> extends ConcurrentHashMap<Struct<T>, P>{
		
		private final Function<Struct<?>, P> lConstructor;
		
		private StructGroup(Class<? extends StructPipe<?>> type){
			try{
				lConstructor=Utils.makeLambda(type.getConstructor(Struct.class), Function.class);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException("Failed to get pipe constructor", e);
			}
		}
		
		P make(Struct<T> struct){
			var cached=get(struct);
			if(cached!=null) return cached;
			
			P created=lConstructor.apply(struct);
			
			if(GlobalConfig.PRINT_COMPILATION){
				LogUtil.println(ConsoleColors.CYAN_BRIGHT+"Compiled "+struct.getType().getSimpleName()+" with "+TextUtil.toNamedPrettyJson(created, true)+ConsoleColors.RESET);
			}
			
			put(struct, created);
			return created;
		}
	}
	
	private static final ConcurrentHashMap<Class<? extends StructPipe<?>>, StructGroup<?, ?>> CACHE=new ConcurrentHashMap<>();
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends StructPipe<T>> P of(Class<P> type, Struct<T> struct){
		var group=(StructGroup<T, P>)CACHE.computeIfAbsent(type, StructGroup::new);
		return group.make(struct);
	}
	
	private final Struct<T>                type;
	private final SizeDescriptor<T>        sizeDescription;
	private final FieldSet<T>              ioFields;
	private final List<VirtualAccessor<T>> ioPoolAccessors;
	private final List<IOField<T, ?>>      earlyNullChecks;
	
	public StructPipe(Struct<T> type){
		this.type=type;
		List<IOField<T, ?>> ioFields=initFields();
		this.ioFields=new FieldSet<>(ioFields);
		sizeDescription=calcSize();
		ioPoolAccessors=Utils.nullIfEmpty(calcIOPoolAccessors());
		earlyNullChecks=Utils.nullIfEmpty(getNonNulls());
	}
	
	private List<IOField<T, ?>> getNonNulls(){
		return ioFields.unpackedStream().filter(f->f.getNullability()==IONullability.Mode.NOT_NULL).toList();
	}
	
	protected abstract List<IOField<T, ?>> initFields();
	
	private List<VirtualAccessor<T>> calcIOPoolAccessors(){
		return type.getFields().unpackedStream()
		           .map(IOField::getAccessor)
		           .filter(a->a instanceof VirtualAccessor)
		           .map(a->(VirtualAccessor<T>)a)
		           .filter(a->a.getStoragePool()==VirtualFieldDefinition.StoragePool.IO)
		           .toList();
	}
	
	private SizeDescriptor<T> calcSize(){
		var fields=getSpecificFields();
		
		var wordSpace  =IOFieldTools.minWordSpace(fields);
		var isUnmanaged=type instanceof Struct.Unmanaged;
		if(!isUnmanaged){
			
			var bitSpace=IOFieldTools.sumVarsIfAll(fields, desc->desc.getFixed(wordSpace));
			if(bitSpace.isPresent()){
				return SizeDescriptor.Fixed.of(wordSpace, bitSpace.getAsLong());
			}
		}
		
		var unknownFields=fields.stream().filter(f->!f.getSizeDescriptor().hasFixed()).toList();
		var knownFixed   =IOFieldTools.sumVars(fields, d->d.getFixed(wordSpace).orElse(0));
		
		var min=IOFieldTools.sumVars(fields, siz->siz.getMin(wordSpace));
		var max=isUnmanaged?OptionalLong.empty():IOFieldTools.sumVarsIfAll(fields, siz->siz.getMax(wordSpace));
		
		if(unknownFields.size()==1){
			//TODO: support unknown bit size?
			var unknownField=unknownFields.get(0);
			return new SizeDescriptor.Unknown<>(wordSpace, min, max, inst->{
				checkNull(inst);
				var d=unknownField.getSizeDescriptor();
				return knownFixed+d.calcUnknown(inst, wordSpace);
			});
		}
		
		return new SizeDescriptor.Unknown<>(wordSpace, min, max, inst->{
			checkNull(inst);
			return knownFixed+IOFieldTools.sumVars(unknownFields, d->d.calcUnknown(inst, wordSpace));
		});
	}
	private void checkNull(T inst){
		Objects.requireNonNull(inst, ()->"instance of type "+getType()+" is null!");
	}
	
	
	public final void write(ChunkDataProvider provider, RandomIO.Creator dest, T instance) throws IOException{
		earlyCheckNulls(instance);
		try(var io=dest.io()){
			doWrite(provider, io, instance);
		}
	}
	public final void write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
		earlyCheckNulls(instance);
		doWrite(provider, dest, instance);
	}
	public final void write(ChunkDataProvider.Holder holder, ContentWriter dest, T instance) throws IOException{
		earlyCheckNulls(instance);
		doWrite(holder.getChunkProvider(), dest, instance);
	}
	public final <Prov extends ChunkDataProvider.Holder&RandomIO.Creator> void write(Prov dest, T instance) throws IOException{
		earlyCheckNulls(instance);
		try(var io=dest.io()){
			doWrite(dest.getChunkProvider(), io, instance);
		}
	}
	protected abstract void doWrite(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException;
	
	
	public <Prov extends ChunkDataProvider.Holder&RandomIO.Creator> void modify(Prov src, UnsafeConsumer<T, IOException> modifier, GenericContext genericContext) throws IOException{
		T val=readNew(src, genericContext);
		modifier.accept(val);
		write(src, val);
	}
	public <Prov extends ChunkDataProvider.Holder&RandomIO.Creator> T readNew(Prov src, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return readNew(src.getChunkProvider(), io, genericContext);
		}
	}
	public T readNew(ChunkDataProvider provider, RandomIO.Creator src, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return readNew(provider, io, genericContext);
		}
	}
	public T readNew(ChunkDataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		T instance=type.requireEmptyConstructor().get();
		return doRead(provider, src, instance, genericContext);
	}
	
	public T read(ChunkDataProvider provider, RandomIO.Creator src, T instance, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return doRead(provider, io, instance, genericContext);
		}
	}
	public <Prov extends ChunkDataProvider.Holder&RandomIO.Creator> T read(Prov src, T instance, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return doRead(src.getChunkProvider(), io, instance, genericContext);
		}
	}
	
	public T read(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		return doRead(provider, src, instance, genericContext);
	}
	
	protected abstract T doRead(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	
	
	public final SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescription;
	}
	
	public Struct<T> getType(){
		return type;
	}
	
	public FieldSet<T> getSpecificFields(){
		return ioFields;
	}
	
	protected final List<VirtualAccessor<T>> getIoPoolAccessors(){
		return ioPoolAccessors;
	}
	
	public void earlyCheckNulls(T instance){
		if(earlyNullChecks==null) return;
		for(var field : earlyNullChecks){
			if(field.get(instance)==null){
				throw new FieldIsNullException(field);
			}
		}
	}
	
	protected void writeIOFields(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
		
		final ContentOutputBuilder destBuff=new ContentOutputBuilder((int)getSizeDescriptor().fixedOrMax(WordSpace.BYTE).orElse(32));
		
		Collection<IOField<T, ?>> dirtyMarked=new HashSet<>();
		Consumer<List<IOField<T, ?>>> logDirty=dirt->{
			if(dirt==null||dirt.isEmpty()) return;
			if(DEBUG_VALIDATION){
				var spec=getSpecificFields();
				for(IOField<T, ?> dirtyField : dirt){
					if(!spec.contains(dirtyField)){
						throw new RuntimeException(dirtyField+" not in "+spec);
					}
				}
			}
			dirtyMarked.addAll(dirt);
		};
		do{
			destBuff.reset();
			for(IOField<T, ?> field : getSpecificFields()){
				dirtyMarked.remove(field);
				if(DEBUG_VALIDATION){
					long bytes;
					try{
						var desc=field.getSizeDescriptor();
						bytes=desc.calcUnknown(instance, WordSpace.BYTE);
					}catch(UnknownSizePredictionException e){
						logDirty.accept(field.writeReported(provider, destBuff, instance));
						continue;
					}
					writeFieldKnownSize(provider, instance, logDirty, field, destBuff.writeTicket(bytes));
				}else{
					logDirty.accept(field.writeReported(provider, destBuff, instance));
				}
			}
		}while(!dirtyMarked.isEmpty());
		
		destBuff.writeTo(dest);
	}
	
	private void writeFieldKnownSize(ChunkDataProvider provider, T instance, Consumer<List<IOField<T, ?>>> logDirty, IOField<T, ?> field, ContentWriter.BufferTicket ticket) throws IOException{
		var safeBuff=ticket.requireExact().submit();
		var d       =field.writeReported(provider, safeBuff, instance);
		logDirty.accept(d);
		
		try{
			safeBuff.close();
		}catch(Exception e){
			throw new IOException(TextUtil.toString(field)+" did not write correctly", e);
		}
	}
	
	public void writeSingleField(ChunkDataProvider provider, RandomIO dest, IOField<T, ?> selectedField, T instance) throws IOException{
		temp_disableDependencyFields(selectedField);
		
		var ioPool=makeIOPool();
		try{
			pushPool(ioPool);
			
			for(IOField<T, ?> field : getSpecificFields()){
				checkExistenceOfField(selectedField);
				
				long bytes;
				try{
					var desc=field.getSizeDescriptor();
					bytes=desc.calcUnknown(instance, WordSpace.BYTE);
				}catch(UnknownSizePredictionException e){
					throw new RuntimeException("Single field write of "+selectedField+" is not currently supported!");
				}
				if(field==selectedField){
					writeFieldKnownSize(provider, instance, l->{}, field, dest.writeTicket(bytes));
					return;
				}
				
				dest.skipExact(bytes);
			}
			
		}finally{
			popPool();
		}
	}
	
	private void temp_disableDependencyFields(IOField<T, ?> field){
		if(!field.getDependencies().isEmpty()){
			throw new NotImplementedException("Single field IO with dependencies is currently not possible");//TODO
		}
	}
	
	protected void readIOFields(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(DEBUG_VALIDATION){
			for(IOField<T, ?> field : getSpecificFields()){
				readFieldSafe(provider, src, instance, field, genericContext);
			}
		}else{
			for(IOField<T, ?> field : getSpecificFields()){
				field.readReported(provider, src, instance, genericContext);
			}
		}
	}
	
	public void readSingleField(ChunkDataProvider provider, ContentReader src, IOField<T, ?> selectedField, T instance, GenericContext genericContext) throws IOException{
		temp_disableDependencyFields(selectedField);
		
		var ioPool=makeIOPool();
		try{
			pushPool(ioPool);
			
			if(DEBUG_VALIDATION){
				checkExistenceOfField(selectedField);
				for(IOField<T, ?> field : getSpecificFields()){
					if(field==selectedField){
						readFieldSafe(provider, src, instance, field, genericContext);
						return;
					}
					
					field.skipReadReported(provider, src, instance, genericContext);
				}
			}else{
				for(IOField<T, ?> field : getSpecificFields()){
					if(field==selectedField){
						field.readReported(provider, src, instance, genericContext);
						return;
					}
					
					field.skipReadReported(provider, src, instance, genericContext);
				}
				throw new IllegalArgumentException(selectedField+" is not listed!");
			}
			
		}finally{
			popPool();
		}
	}
	
	private void checkExistenceOfField(IOField<T, ?> selectedField){
		for(IOField<T, ?> field : getSpecificFields()){
			if(field==selectedField){
				return;
			}
		}
		throw new IllegalArgumentException(selectedField+" is not listed!");
	}
	
	private void readFieldSafe(ChunkDataProvider provider, ContentReader src, T instance, IOField<T, ?> field, GenericContext genericContext) throws IOException{
		var desc =field.getSizeDescriptor();
		var fixed=desc.getFixed(WordSpace.BYTE);
		if(fixed.isPresent()){
			long bytes=fixed.getAsLong();
			
			var buf=src.readTicket(bytes).requireExact().submit();
			field.readReported(provider, buf, instance, genericContext);
			try{
				buf.close();
			}catch(Exception e){
				throw new IOException(TextUtil.toString(field)+" did not read correctly", e);
			}
		}else{
			field.readReported(provider, src, instance, genericContext);
		}
	}
	
	protected void pushPool(Struct.Pool<T> ioPool){
		var acc=getIoPoolAccessors();
		if(acc==null) return;
		for(var a : acc){
			a.pushIoPool(ioPool);
		}
	}
	protected void popPool(){
		var acc=getIoPoolAccessors();
		if(acc==null) return;
		for(var a : acc){
			a.popIoPool();
		}
	}
	
	protected Struct.Pool<T> makeIOPool(){
		return getType().allocVirtualVarPool(VirtualFieldDefinition.StoragePool.IO);
	}
	
	
	@Override
	public String toString(){
		return getClass().getSimpleName()+"{"+type.getType().getSimpleName()+"}";
	}
}
