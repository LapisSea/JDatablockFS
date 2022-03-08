package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.MalformedObjectException;
import com.lapissea.cfs.exceptions.UnknownSizePredictionException;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public abstract class StructPipe<T extends IOInstance<T>>{
	
	private static class StructGroup<T extends IOInstance<T>, P extends StructPipe<T>> extends ConcurrentHashMap<Struct<T>, P>{
		
		private final Function<Struct<?>, P> lConstructor;
		
		private StructGroup(Class<? extends StructPipe<?>> type){
			try{
				lConstructor=Access.makeLambda(type.getConstructor(Struct.class), Function.class);
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
			
			
			if(DEBUG_VALIDATION){
				
				T inst;
				try{
					inst=created.getType().requireEmptyConstructor().get();
				}catch(Throwable e){
					inst=null;
				}
				if(inst!=null){
					try{
						created.checkTypeIntegrity(inst);
					}catch(FieldIsNullException e){
//						LogUtil.println("warning, "+struct+" is non conforming");
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}
			}
			
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
		this.ioFields=FieldSet.of(initFields());
		sizeDescription=calcSize();
		ioPoolAccessors=Utils.nullIfEmpty(calcIOPoolAccessors());
		earlyNullChecks=Utils.nullIfEmpty(getNonNulls());
		generators=Utils.nullIfEmpty(ioFields.stream().map(IOField::getGenerators).flatMap(Collection::stream).toList());
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
		var knownFixed=IOFieldTools.sumVars(fields, d->{
			var fixed=d.getFixed(wordSpace);
			if(fixed.isPresent()){
				var siz=fixed.getAsLong();
				return clampMinBit(wordSpace, siz);
			}
			return 0;
		});
		
		var min=IOFieldTools.sumVars(fields, siz->siz.getMin(wordSpace));
		var max=hasDynamicFields?OptionalLong.empty():IOFieldTools.sumVarsIfAll(fields, siz->siz.getMax(wordSpace));
		
		return new SizeDescriptor.Unknown<>(wordSpace, min, max, (ioPool, prov, inst)->{
			checkNull(inst);
			
			try{
				generateAll(ioPool, prov, inst, false);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			
			if(unknownFields.size()==1){
				var field=unknownFields.get(0);
				var d    =field.getSizeDescriptor();
				return knownFixed+d.calcUnknown(ioPool, prov, inst, wordSpace);
			}
			
			return knownFixed+IOFieldTools.sumVars(unknownFields, d->{
				return d.calcUnknown(ioPool, prov, inst, wordSpace);
			});
		});
	}
	
	private long clampMinBit(WordSpace wordSpace, long siz){
		var bytes=WordSpace.mapSize(wordSpace, WordSpace.BYTE, siz);
		return WordSpace.mapSize(WordSpace.BYTE, wordSpace, bytes);
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
			var siz=desc.getFixed(WordSpace.BYTE).orElseGet(()->{
				var max=desc.getMax(WordSpace.BYTE);
				var min=desc.getMin(WordSpace.BYTE);
				return max.orElse(Math.max(min, 32));
			});
			destBuff=new ContentOutputBuilder((int)siz);
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
			throw new IOException(TextUtil.toString(field)+" ("+field.get(ioPool, instance)+") did not write correctly", e);
		}
	}
	
	private record IODependency<T extends IOInstance<T>>(
		List<IOField<T, ?>> writeFields,
		List<IOField<T, ?>> readFields,
		List<IOField.ValueGeneratorInfo<T, ?>> generators
	){}
	
	private final Map<IOField<T, ?>, IODependency<T>> singleDependencyCache    =new HashMap<>();
	private final ReadWriteLock                       singleDependencyCacheLock=new ReentrantReadWriteLock();
	
	private IODependency<T> getDeps(IOField<T, ?> selectedField){
		var r=singleDependencyCacheLock.readLock();
		r.lock();
		try{
			var cached=singleDependencyCache.get(selectedField);
			if(cached!=null) return cached;
		}finally{
			r.unlock();
		}
		
		var w=singleDependencyCacheLock.writeLock();
		w.lock();
		try{
			var field=generateFieldDependency(selectedField);
			singleDependencyCache.put(selectedField, field);
			return field;
		}finally{
			w.unlock();
		}
	}
	
	private IODependency<T> generateFieldDependency(IOField<T, ?> selectedField){
		Set<IOField<T, ?>> selectedWriteFieldsSet=new HashSet<>();
		selectedWriteFieldsSet.add(selectedField);
		Set<IOField<T, ?>> selectedReadFieldsSet=new HashSet<>();
		selectedReadFieldsSet.add(selectedField);
		
		boolean shouldRun=true;
		while(shouldRun){
			shouldRun=false;
			
			for(IOField<T, ?> field : new HashSet<>(selectedWriteFieldsSet)){
				var deps=field.getDependencies();
				if(!deps.isEmpty()){
					if(selectedWriteFieldsSet.addAll(deps)) shouldRun=true;
				}
				var gens=field.getGenerators();
				for(var gen : gens){
					if(selectedWriteFieldsSet.add(gen.field())) shouldRun=true;
				}
			}
			for(IOField<T, ?> field : new HashSet<>(selectedReadFieldsSet)){
				var deps=field.getDependencies();
				if(!deps.isEmpty()){
					if(selectedReadFieldsSet.addAll(deps)) shouldRun=true;
				}
			}
			
			for(IOField<T, ?> field : new HashSet<>(selectedWriteFieldsSet)){
				if(field.getSizeDescriptor().hasFixed()){
					continue;
				}
				
				var fields=getSpecificFields();
				var index =fields.indexOf(field);
				assert index!=-1;//TODO handle fields in fields
				for(int i=index+1;i<fields.size();i++){
					if(selectedWriteFieldsSet.add(fields.get(i))) shouldRun=true;
				}
			}
		}
		
		var writeFields=fieldSetToOrderedList(selectedWriteFieldsSet);
		var readFields =fieldSetToOrderedList(selectedReadFieldsSet);
		var generators =writeFields.stream().flatMap(e->e.getGenerators().stream()).toList();
		
		return new IODependency<>(
			writeFields,
			readFields,
			Utils.nullIfEmpty(generators)
		);
	}
	
	private List<IOField<T, ?>> fieldSetToOrderedList(Set<IOField<T, ?>> fieldsSet){
		List<IOField<T, ?>> result=new ArrayList<>(fieldsSet.size());
		for(IOField<T, ?> f : getSpecificFields()){
			var iter      =f.streamUnpackedFields().iterator();
			var anyRemoved=false;
			while(iter.hasNext()){
				var fi=iter.next();
				if(fieldsSet.remove(fi)) anyRemoved=true;
			}
			
			if(anyRemoved){
				result.add(f);
			}
		}
		if(!fieldsSet.isEmpty()){
			throw new IllegalStateException(fieldsSet+"");
		}
		return List.copyOf(result);
	}
	
	
	public void writeSingleField(DataProvider provider, RandomIO dest, IOField<T, ?> selectedField, T instance) throws IOException{
		if(DEBUG_VALIDATION){
			checkExistenceOfField(selectedField);
		}
		
		var deps  =getDeps(selectedField);
		var fields=deps.writeFields;
		var ioPool=makeIOPool();
		
		if(deps.generators!=null){
			for(var generator : deps.generators){
				generator.generate(ioPool, provider, instance, true);
			}
		}
		
		int checkIndex=0;
		
		for(IOField<T, ?> field : getSpecificFields()){
			
			long bytes;
			try{
				var desc=field.getSizeDescriptor();
				bytes=desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
			}catch(UnknownSizePredictionException e){
				throw new RuntimeException("Write of "+deps+" is not currently supported!");
			}
			
			if(fields.get(checkIndex)==field){
				checkIndex++;
				writeFieldKnownSize(ioPool, provider, instance, field, dest.writeTicket(bytes));
				
				if(checkIndex==fields.size()){
					return;
				}
				
				continue;
			}
			
			dest.skipExact(bytes);
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
	
	public void readSingleField(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, IOField<T, ?> selectedField, T instance, GenericContext genericContext) throws IOException{
		if(DEBUG_VALIDATION){
			checkExistenceOfField(selectedField);
		}
		
		var deps      =getDeps(selectedField);
		var fields    =deps.readFields;
		int checkIndex=0;
		
		if(DEBUG_VALIDATION){
			for(IOField<T, ?> field : ioFields){
				if(fields.get(checkIndex)==field){
					checkIndex++;
					readFieldSafe(ioPool, provider, src, instance, field, genericContext);
					
					if(checkIndex==fields.size()){
						return;
					}
					
					continue;
				}
				
				field.skipReadReported(ioPool, provider, src, instance, genericContext);
			}
		}else{
			for(IOField<T, ?> field : getSpecificFields()){
				if(fields.get(checkIndex)==field){
					checkIndex++;
					field.readReported(ioPool, provider, src, instance, genericContext);
					
					if(checkIndex==fields.size()){
						return;
					}
					
					continue;
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
			
			String extra="";
			if(DEBUG_VALIDATION){
				extra=" started on: "+src;
			}
			
			var buf=src.readTicket(bytes).requireExact().submit();
			
			try{
				field.readReported(ioPool, provider, buf, instance, genericContext);
			}catch(Exception e){
				throw new IOException(TextUtil.toString(field)+" failed to read!"+extra, e);
			}
			
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
	
	
	public void checkTypeIntegrity(T inst) throws IOException{
		var tmp=MemoryData.build().build();
		var man=DataProvider.newVerySimpleProvider(tmp);
		
		T instRead;
		try{
			write(man, tmp, inst);
			instRead=readNew(man, tmp, null);
		}catch(IOException e){
			throw new MalformedObjectException("Failed object IO "+getType(), e);
		}
		
		if(!instRead.equals(inst)){
			throw new MalformedObjectException(getType()+" has failed integrity check. Source/read:\n"+inst+"\n"+instRead);
		}
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
