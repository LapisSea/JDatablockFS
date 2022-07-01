package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.MalformedObjectException;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.exceptions.UnknownSizePredictionException;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.ConsoleColors.BLUE_BRIGHT;
import static com.lapissea.cfs.ConsoleColors.CYAN_BRIGHT;
import static com.lapissea.cfs.ConsoleColors.RESET;
import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public abstract class StructPipe<T extends IOInstance<T>> extends StagedInit{
	
	private static class StructGroup<T extends IOInstance<T>, P extends StructPipe<T>> extends ConcurrentHashMap<Struct<T>, P>{
		
		private final Map<Struct<T>, Supplier<P>> specials=new HashMap<>();
		private final Function<Struct<?>, P>      lConstructor;
		private final Class<?>                    type;
		
		private StructGroup(Class<? extends StructPipe<?>> type){
			try{
				lConstructor=Access.makeLambda(type.getConstructor(Struct.class), Function.class);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException("Failed to get pipe constructor", e);
			}
			this.type=type;
		}
		
		P make(Struct<T> struct){
			var cached=get(struct);
			if(cached!=null) return cached;
			
			P created;
			if(GlobalConfig.PRINT_COMPILATION&&!Access.DEV_CACHE){
				LogUtil.println(CYAN_BRIGHT+"Requested pipe: "+struct.getType().getName()+RESET);
			}
			try{
				var special=specials.get(struct);
				if(special!=null){
					created=special.get();
				}else{
					created=lConstructor.apply(struct);
				}
			}catch(Throwable e){
				throw new MalformedStructLayout("Failed to compile "+type.getSimpleName()+" for "+struct.getType().getName(), e);
			}
			
			if(GlobalConfig.PRINT_COMPILATION&&!Access.DEV_CACHE){
				StagedInit.runBaseStageTask(()->{
					String s=CYAN_BRIGHT+
					         "Compiled: "+struct.getType().getName()+"\n"+
					         "\tPipe type: "+BLUE_BRIGHT+created.getClass().getName()+CYAN_BRIGHT+"\n"+
					         "\tSize: "+BLUE_BRIGHT+created.getSizeDescriptor()+CYAN_BRIGHT;
					
					var sFields=created.getSpecificFields();
					
					if(!sFields.equals(struct.getFields())){
						s+="\n"+TextUtil.toTable(created.getSpecificFields());
					}
					
					LogUtil.println(s+RESET);
				});
			}
			
			put(struct, created);
			
			
			if(DEBUG_VALIDATION&&!(struct instanceof Struct.Unmanaged)){
				if(Access.DEV_CACHE){
					created.getType().requireEmptyConstructor();
				}else{
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
							e.printStackTrace();
							throw new RuntimeException(e);
						}
					}
				}
			}
			
			return created;
		}
		
		private void registerSpecialImpl(Struct<T> struct, Supplier<P> newType){
			specials.put(struct, newType);
		}
	}
	
	private static final ConcurrentHashMap<Class<? extends StructPipe<?>>, StructGroup<?, ?>> CACHE=new ConcurrentHashMap<>();
	
	public static void clear(){
		if(!Access.DEV_CACHE) throw new RuntimeException();
		CACHE.clear();
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends StructPipe<T>> P of(Class<P> type, Struct<T> struct){
		var group=(StructGroup<T, P>)CACHE.computeIfAbsent(type, StructGroup::new);
		return group.make(struct);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends StructPipe<T>> void registerSpecialImpl(Struct<T> struct, Class<P> oldType, Supplier<P> newType){
		var group=(StructGroup<T, P>)CACHE.computeIfAbsent(oldType, StructGroup::new);
		group.registerSpecialImpl(struct, newType);
	}
	
	
	private final Struct<T>           type;
	private       SizeDescriptor<T>   sizeDescription;
	private       FieldSet<T>         ioFields;
	private       List<IOField<T, ?>> earlyNullChecks;
	
	private List<IOField.ValueGeneratorInfo<T, ?>> generators;
	
	public static final int STATE_IO_FIELD=1;
	
	public StructPipe(Struct<T> type){
		this.type=type;
		init(false, ()->{
			this.ioFields=FieldSet.of(initFields());
			setInitState(STATE_IO_FIELD);
			earlyNullChecks=Utils.nullIfEmpty(getNonNulls());
			
			type.waitForState(STATE_DONE);
			sizeDescription=Objects.requireNonNull(createSizeDescriptor());
			generators=Utils.nullIfEmpty(ioFields.stream().flatMap(IOField::generatorStream).toList());
		});
	}
	
	@Override
	protected String stateToStr(int state){
		return switch(state){
			case STATE_IO_FIELD -> "IO_FIELD";
			default -> super.stateToStr(state);
		};
	}
	private List<IOField<T, ?>> getNonNulls(){
		return ioFields.unpackedStream().filter(f->f.getNullability()==IONullability.Mode.NOT_NULL&&f.getAccessor().canBeNull()).toList();
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
	
	protected SizeDescriptor<T> createSizeDescriptor(){
		FieldSet<T> fields=getSpecificFields();
		if(type instanceof Struct.Unmanaged<?> u){
			FieldSet<T> f=(FieldSet<T>)u.getUnmanagedStaticFields();
			if(!f.isEmpty()){
				fields=FieldSet.of(Stream.concat(fields.stream(), f.stream()));
			}
		}
		
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
		
		
		Map<SizeDescriptor.UnknownNum<T>, List<SizeDescriptor.UnknownNum<T>>> numMap;
		numMap=unknownFields.stream()
		                    .filter(f->f.getSizeDescriptor() instanceof SizeDescriptor.UnknownNum)
		                    .map(f->(SizeDescriptor.UnknownNum<T>)f.getSizeDescriptor())
		                    .collect(Collectors.groupingBy(f->f));
		
		numMap.entrySet().removeIf(e->e.getValue().size()==1);
		
		var unknownUnknownFields=unknownFields.stream()
		                                      .filter(f->!numMap.containsKey(f.getSizeDescriptor()))
		                                      .toList();
		
		FieldAccessor<T>[] unkownNumAcc;
		int[]              unkownNumAccMul;
		
		if(numMap.isEmpty()){
			unkownNumAcc=null;
			unkownNumAccMul=null;
		}else{
			unkownNumAcc=new FieldAccessor[numMap.size()];
			unkownNumAccMul=new int[numMap.size()];
			int i=0;
			for(var e : numMap.entrySet()){
				unkownNumAcc[i]=e.getKey().getAccessor();
				unkownNumAccMul[i]=e.getValue().size();
				i++;
			}
		}
		
		return SizeDescriptor.Unknown.of(wordSpace, min, max, (ioPool, prov, inst)->{
			checkNull(inst);
			
			try{
				generateAll(ioPool, prov, inst, false);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			var unkownSum=IOFieldTools.sumVars(unknownUnknownFields, d->{
				return d.calcUnknown(ioPool, prov, inst, wordSpace);
			});
			
			if(unkownNumAcc!=null){
				for(int i=0;i<unkownNumAcc.length;i++){
					var  acc=unkownNumAcc[i];
					var  num=(NumberSize)acc.get(ioPool, inst);
					long mul=unkownNumAccMul[i];
					unkownSum+=num.bytes*mul;
				}
			}
			return knownFixed+unkownSum;
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
			doWrite(provider, io, ioPool, instance);
		}
	}
	public final void write(DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var ioPool=makeIOPool();
		earlyCheckNulls(ioPool, instance);
		doWrite(provider, dest, ioPool, instance);
	}
	public final void write(DataProvider.Holder holder, ContentWriter dest, T instance) throws IOException{
		var ioPool=makeIOPool();
		earlyCheckNulls(ioPool, instance);
		doWrite(holder.getDataProvider(), dest, ioPool, instance);
	}
	public final <Prov extends DataProvider.Holder&RandomIO.Creator> void write(Prov dest, T instance) throws IOException{
		var ioPool=makeIOPool();
		earlyCheckNulls(ioPool, instance);
		try(var io=dest.io()){
			doWrite(dest.getDataProvider(), io, ioPool, instance);
		}
	}
	protected abstract void doWrite(DataProvider provider, ContentWriter dest, Struct.Pool<T> ioPool, T instance) throws IOException;
	
	
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
		if(sizeDescription==null){
			waitForState(STATE_DONE);
			if(sizeDescription==null){
				waitForState(STATE_DONE);
			}
			if(sizeDescription==null) throw new IllegalStateException();
		}
		return sizeDescription;
	}
	
	public Struct<T> getType(){
		return type;
	}
	
	public FieldSet<T> getSpecificFields(){
		waitForState(STATE_IO_FIELD);
		return ioFields;
	}
	
	public void earlyCheckNulls(Struct.Pool<T> ioPool, T instance){
		waitForState(STATE_DONE);
		if(earlyNullChecks==null) return;
		for(var field : earlyNullChecks){
			if(field.isNull(ioPool, instance)){
				throw new FieldIsNullException(field);
			}
		}
	}
	
	protected void writeIOFields(FieldSet<T> fields, Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		if(Access.DEV_CACHE) throw new RuntimeException();
		
		ContentOutputBuilder destBuff=null;
		ContentWriter        target;
		
		if(dest.isDirect()){
			var siz=getSizeDescriptor().calcAllocSize(WordSpace.BYTE);
			destBuff=new ContentOutputBuilder((int)siz);
			target=destBuff;
		}else{
			target=dest;
		}
		
		generateAll(ioPool, provider, instance, true);
		
		for(IOField<T, ?> field : fields){
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
		waitForState(STATE_DONE);
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
				if(field.hasDependencies()){
					if(selectedWriteFieldsSet.addAll(field.getDependencies())) shouldRun=true;
				}
				var gens=field.getGenerators();
				if(gens!=null){
					for(var gen : gens){
						if(selectedWriteFieldsSet.add(gen.field())) shouldRun=true;
					}
				}
			}
			for(IOField<T, ?> field : new HashSet<>(selectedReadFieldsSet)){
				if(field.hasDependencies()){
					if(selectedReadFieldsSet.addAll(field.getDependencies())) shouldRun=true;
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
		var generators =writeFields.stream().flatMap(IOField::generatorStream).toList();
		
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
	
	protected void readIOFields(FieldSet<T> fields, Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(DEBUG_VALIDATION){
			for(IOField<T, ?> field : fields){
				readFieldSafe(ioPool, provider, src, instance, field, genericContext);
			}
		}else{
			for(IOField<T, ?> field : fields){
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
			for(IOField<T, ?> field : getSpecificFields()){
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
		var pool=makeIOPool();
		if(DEBUG_VALIDATION){
			earlyCheckNulls(pool, instance);
		}
		return getSizeDescriptor().calcUnknown(pool, provider, instance, wordSpace);
	}
	
	
	public void checkTypeIntegrity(T inst) throws IOException{
		var tmp=MemoryData.builder().build();
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