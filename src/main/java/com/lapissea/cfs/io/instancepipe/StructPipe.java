package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
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
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

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
	
	protected final List<IOField.ValueGeneratorInfo<T, ?>> generators;
	
	public StructPipe(Struct<T> type){
		this.type=type;
		this.ioFields=new FieldSet<>(initFields());
		sizeDescription=calcSize();
		ioPoolAccessors=Utils.nullIfEmpty(calcIOPoolAccessors());
		earlyNullChecks=Utils.nullIfEmpty(getNonNulls());
		generators=Utils.nullIfEmpty(ioFields.stream().map(IOField::getGenerators).filter(Objects::nonNull).flatMap(Collection::stream).toList());
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
		
		var wordSpace=IOFieldTools.minWordSpace(fields);
		
		var hasDynamicFields=type instanceof Struct.Unmanaged<?> u&&u.isOverridingDynamicUnmanaged();
		
		if(!hasDynamicFields){
			
			var bitSpace=IOFieldTools.sumVarsIfAll(fields, desc->desc.getFixed(wordSpace));
			if(bitSpace.isPresent()){
				return SizeDescriptor.Fixed.of(wordSpace, bitSpace.getAsLong());
			}
		}
		
		var unknownFields=fields.stream().filter(f->!f.getSizeDescriptor().hasFixed()).toList();
		var knownFixed   =IOFieldTools.sumVars(fields, d->d.getFixed(wordSpace).orElse(0));
		
		var min=IOFieldTools.sumVars(fields, siz->siz.getMin(wordSpace));
		var max=hasDynamicFields?OptionalLong.empty():IOFieldTools.sumVarsIfAll(fields, siz->siz.getMax(wordSpace));
		
		if(unknownFields.size()==1){
			//TODO: support unknown bit size?
			var unknownField=unknownFields.get(0);
			return new SizeDescriptor.Unknown<>(wordSpace, min, max, (ioPool, prov, inst)->{
				checkNull(inst);
				if(generators!=null){
					try{
						generateAll(ioPool, prov, inst, false);
						
						var d=unknownField.getSizeDescriptor();
						return knownFixed+d.calcUnknown(ioPool, prov, inst, wordSpace);
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
				
				var d=unknownField.getSizeDescriptor();
				return knownFixed+d.calcUnknown(ioPool, prov, inst, wordSpace);
			});
		}
		
		return new SizeDescriptor.Unknown<>(wordSpace, min, max, (ioPool, prov, inst)->{
			checkNull(inst);
			
			if(generators!=null){
				try{
					generateAll(ioPool, prov, inst, false);
					
					return knownFixed+IOFieldTools.sumVars(unknownFields, d->d.calcUnknown(ioPool, prov, inst, wordSpace));
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			
			return knownFixed+IOFieldTools.sumVars(unknownFields, d->d.calcUnknown(ioPool, prov, inst, wordSpace));
		});
	}
	
	private void checkNull(T inst){
		Objects.requireNonNull(inst, ()->"instance of type "+getType()+" is null!");
	}
	
	
	public final void write(DataProvider provider, RandomIO.Creator dest, T instance) throws IOException{
		var ioPool=makeIOPool();
		earlyCheckNulls(ioPool, instance);
		try(var io=dest.io()){
			doWrite(provider, io, instance);
		}
	}
	public final void write(DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var ioPool=makeIOPool();
		earlyCheckNulls(ioPool, instance);
		doWrite(provider, dest, instance);
	}
	public final void write(DataProvider.Holder holder, ContentWriter dest, T instance) throws IOException{
		var ioPool=makeIOPool();
		earlyCheckNulls(ioPool, instance);
		doWrite(holder.getDataProvider(), dest, instance);
	}
	public final <Prov extends DataProvider.Holder&RandomIO.Creator> void write(Prov dest, T instance) throws IOException{
		var ioPool=makeIOPool();
		earlyCheckNulls(ioPool, instance);
		try(var io=dest.io()){
			doWrite(dest.getDataProvider(), io, instance);
		}
	}
	protected abstract void doWrite(DataProvider provider, ContentWriter dest, T instance) throws IOException;
	
	
	public <Prov extends DataProvider.Holder&RandomIO.Creator> void modify(Prov src, UnsafeConsumer<T, IOException> modifier, GenericContext genericContext) throws IOException{
		T val=readNew(src, genericContext);
		modifier.accept(val);
		write(src, val);
	}
	public <Prov extends DataProvider.Holder&RandomIO.Creator> T readNew(Prov src, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return readNew(src.getDataProvider(), io, genericContext);
		}
	}
	public T readNew(DataProvider provider, RandomIO.Creator src, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return readNew(provider, io, genericContext);
		}
	}
	public T readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		T instance=type.requireEmptyConstructor().get();
		return doRead(makeIOPool(), provider, src, instance, genericContext);
	}
	
	public T read(DataProvider provider, RandomIO.Creator src, T instance, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return doRead(makeIOPool(), provider, io, instance, genericContext);
		}
	}
	public <Prov extends DataProvider.Holder&RandomIO.Creator> T read(Prov src, T instance, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return doRead(makeIOPool(), src.getDataProvider(), io, instance, genericContext);
		}
	}
	
	public T read(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		return doRead(makeIOPool(), provider, src, instance, genericContext);
	}
	public T read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		return doRead(ioPool, provider, src, instance, genericContext);
	}
	
	protected abstract T doRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	
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
	
	public void earlyCheckNulls(Struct.Pool<T> ioPool, T instance){
		if(earlyNullChecks==null) return;
		for(var field : earlyNullChecks){
			if(field.get(ioPool, instance)==null){
				throw new FieldIsNullException(field);
			}
		}
	}
	
	protected void writeIOFields(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		
		ContentOutputBuilder destBuff=null;
		ContentWriter        target;
		
		if(dest.isDirect()){
			var desc=getSizeDescriptor();
			var max =desc.getMax(WordSpace.BYTE);
			var min =desc.getMin(WordSpace.BYTE);
			destBuff=new ContentOutputBuilder((int)max.orElse(Math.max(min, 32)));
			target=destBuff;
		}else{
			target=dest;
		}
		
		generateAll(ioPool, provider, instance, true);
		
		for(IOField<T, ?> field : getSpecificFields()){
			if(DEBUG_VALIDATION){
				long bytes;
				try{
					var desc=field.getSizeDescriptor();
					bytes=desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
				}catch(UnknownSizePredictionException e){
					field.writeReported(ioPool, provider, target, instance);
					continue;
				}
				writeFieldKnownSize(ioPool, provider, instance, field, target.writeTicket(bytes));
			}else{
				field.writeReported(ioPool, provider, target, instance);
			}
		}
		
		if(destBuff!=null){
			destBuff.writeTo(dest);
		}
	}
	
	private void generateAll(Struct.Pool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
		if(generators==null) return;
		for(var generator : generators){
			generator.generate(ioPool, provider, instance, allowExternalMod);
		}
	}
	
	private void writeFieldKnownSize(Struct.Pool<T> ioPool, DataProvider provider, T instance, IOField<T, ?> field, ContentWriter.BufferTicket ticket) throws IOException{
		var safeBuff=ticket.requireExact().submit();
		field.writeReported(ioPool, provider, safeBuff, instance);
		
		try{
			safeBuff.close();
		}catch(Exception e){
			throw new IOException(TextUtil.toString(field)+" did not write correctly", e);
		}
	}
	
	public void writeSingleField(DataProvider provider, RandomIO dest, IOField<T, ?> selectedField, T instance) throws IOException{
		temp_disableDependencyFields(selectedField);
		
		var ioPool=makeIOPool();
		
		for(IOField<T, ?> field : getSpecificFields()){
			checkExistenceOfField(selectedField);
			
			long bytes;
			try{
				var desc=field.getSizeDescriptor();
				bytes=desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
			}catch(UnknownSizePredictionException e){
				throw new RuntimeException("Single field write of "+selectedField+" is not currently supported!");
			}
			if(field==selectedField){
				writeFieldKnownSize(ioPool, provider, instance, field, dest.writeTicket(bytes));
				return;
			}
			
			dest.skipExact(bytes);
		}
		
	}
	
	private void temp_disableDependencyFields(IOField<T, ?> field){
		if(!field.getDependencies().isEmpty()){
			throw new NotImplementedException("Single field IO with dependencies is currently not possible");//TODO
		}
	}
	
	protected void readIOFields(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(DEBUG_VALIDATION){
			for(IOField<T, ?> field : getSpecificFields()){
				readFieldSafe(ioPool, provider, src, instance, field, genericContext);
			}
		}else{
			for(IOField<T, ?> field : getSpecificFields()){
				field.readReported(ioPool, provider, src, instance, genericContext);
			}
		}
	}
	
	public void readSingleField(DataProvider provider, ContentReader src, IOField<T, ?> selectedField, T instance, GenericContext genericContext) throws IOException{
		temp_disableDependencyFields(selectedField);
		
		var ioPool=makeIOPool();
		
		if(DEBUG_VALIDATION){
			checkExistenceOfField(selectedField);
			for(IOField<T, ?> field : getSpecificFields()){
				if(field==selectedField){
					readFieldSafe(ioPool, provider, src, instance, field, genericContext);
					return;
				}
				
				field.skipReadReported(ioPool, provider, src, instance, genericContext);
			}
		}else{
			for(IOField<T, ?> field : getSpecificFields()){
				if(field==selectedField){
					field.readReported(ioPool, provider, src, instance, genericContext);
					return;
				}
				
				field.skipReadReported(ioPool, provider, src, instance, genericContext);
			}
			throw new IllegalArgumentException(selectedField+" is not listed!");
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
	
	private void readFieldSafe(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, IOField<T, ?> field, GenericContext genericContext) throws IOException{
		var desc =field.getSizeDescriptor();
		var fixed=desc.getFixed(WordSpace.BYTE);
		if(fixed.isPresent()){
			long bytes=fixed.getAsLong();
			
			var buf=src.readTicket(bytes).requireExact().submit();
			field.readReported(ioPool, provider, buf, instance, genericContext);
			try{
				buf.close();
			}catch(Exception e){
				throw new IOException(TextUtil.toString(field)+" did not read correctly", e);
			}
		}else{
			field.readReported(ioPool, provider, src, instance, genericContext);
		}
	}
	
	public Struct.Pool<T> makeIOPool(){
		return getType().allocVirtualVarPool(VirtualFieldDefinition.StoragePool.IO);
	}
	
	public long calcUnknownSize(DataProvider provider, T instance, WordSpace wordSpace){
		return getSizeDescriptor().calcUnknown(makeIOPool(), provider, instance, wordSpace);
	}
	
	@Override
	public String toString(){
		return getClass().getSimpleName()+"{"+type.getType().getSimpleName()+"}";
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof StructPipe<?> that)) return false;
		
		if(!type.equals(that.type)) return false;
		return ioFields.equals(that.ioFields);
	}
	@Override
	public int hashCode(){
		int result=type.hashCode();
		result=31*result+ioFields.hashCode();
		return result;
	}
}
