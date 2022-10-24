package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.MalformedObjectException;
import com.lapissea.cfs.exceptions.MalformedPipe;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.*;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.ConsoleColors.BLUE_BRIGHT;
import static com.lapissea.cfs.ConsoleColors.CYAN_BRIGHT;
import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.GlobalConfig.PRINT_COMPILATION;
import static com.lapissea.cfs.GlobalConfig.TYPE_VALIDATION;

public abstract class StructPipe<T extends IOInstance<T>> extends StagedInit implements ObjectPipe<T, VarPool<T>>{
	
	private static final Log.Channel COMPILATION=Log.channel(PRINT_COMPILATION&&!Access.DEV_CACHE, Log.Channel.colored(CYAN_BRIGHT));
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	public @interface Special{}
	
	private static class StructGroup<T extends IOInstance<T>, P extends StructPipe<T>> extends ConcurrentHashMap<Struct<T>, P>{
		
		interface PipeConstructor<T extends IOInstance<T>, P extends StructPipe<T>>{
			P make(Struct<T> type, boolean runNow);
		}
		
		private final Map<Struct<T>, Supplier<P>> specials=new HashMap<>();
		private final PipeConstructor<T, P>       lConstructor;
		private final Class<?>                    type;
		private final Map<Struct<T>, Throwable>   errors  =new ConcurrentHashMap<>();
		
		private StructGroup(Class<? extends StructPipe<?>> type){
			try{
				lConstructor=Access.makeLambda(type.getConstructor(Struct.class, boolean.class), PipeConstructor.class);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException("Failed to get pipe constructor", e);
			}
			this.type=type;
		}
		
		P make(Struct<T> struct, boolean runNow){
			var cached=get(struct);
			if(cached!=null) return cached;
			var err=errors.get(struct);
			if(err!=null) throw err instanceof RuntimeException e?e:new RuntimeException(err);
			
			COMPILATION.log("Requested pipe({}): {}", (Supplier<String>)()->shortPipeName(type), struct.getType().getName());
			
			P created;
			try{
				var typ=struct.getType();
				if(typ.isAnnotationPresent(Special.class)){
					try{
						Class.forName(typ.getName(), true, typ.getClassLoader());
					}catch(ClassNotFoundException e){
						throw new AssertionError(e);  // Can't happen
					}
				}
				
				var special=specials.get(struct);
				if(special!=null){
					created=special.get();
				}else{
					created=lConstructor.make(struct, runNow);
				}
			}catch(Throwable e){
				e.addSuppressed(new MalformedPipe("Failed to compile "+type.getSimpleName()+" for "+struct.getType().getName(), e));
				errors.put(struct, e);
				throw e;
			}
			
			put(struct, created);
			
			COMPILATION.on(()->StagedInit.runBaseStageTask(()->{
				String s="Compiled: "+struct.getType().getName()+"\n"+
				         "\tPipe type: "+BLUE_BRIGHT+created.getClass().getName()+CYAN_BRIGHT+"\n"+
				         "\tSize: "+BLUE_BRIGHT+created.getSizeDescriptor()+CYAN_BRIGHT+"\n"+
				         "\tReference commands: "+created.getReferenceWalkCommands();
				
				var sFields=created.getSpecificFields();
				
				if(!sFields.equals(struct.getFields())){
					s+="\n"+TextUtil.toTable(created.getSpecificFields());
				}
				
				COMPILATION.log(s);
			}));
			
			if(TYPE_VALIDATION&&!(struct instanceof Struct.Unmanaged)){
				if(Access.DEV_CACHE){
					created.getType().emptyConstructor();
				}else{
					T inst;
					try{
						inst=created.getType().make();
					}catch(Throwable e){
						inst=null;
					}
					if(inst!=null){
						try{
							created.checkTypeIntegrity(inst);
						}catch(FieldIsNullException ignored){
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
	public static <T extends IOInstance<T>, P extends StructPipe<T>> P of(Class<P> type, Struct<T> struct, int minRequestedStage){
		try{
			var group=(StructGroup<T, P>)CACHE.computeIfAbsent(type, StructGroup::new);
			var pipe =group.make(struct, minRequestedStage==STATE_DONE);
			pipe.waitForState(minRequestedStage);
			return pipe;
		}catch(Throwable e){
			throw Utils.interceptClInit(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends StructPipe<T>> P of(Class<P> type, Struct<T> struct){
		try{
			var group=(StructGroup<T, P>)CACHE.computeIfAbsent(type, StructGroup::new);
			return group.make(struct, false);
		}catch(Throwable e){
			throw Utils.interceptClInit(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends StructPipe<T>> void registerSpecialImpl(Struct<T> struct, Class<P> oldType, Supplier<P> newType){
		var group=(StructGroup<T, P>)CACHE.computeIfAbsent(oldType, StructGroup::new);
		group.registerSpecialImpl(struct, newType);
	}
	
	
	private final Struct<T>           type;
	private       SizeDescriptor<T>   sizeDescription;
	private       FieldSet<T>         ioFields;
	private       CommandSet          referenceWalkCommands;
	private       List<IOField<T, ?>> earlyNullChecks;
	
	private List<IOField.ValueGeneratorInfo<T, ?>> generators;
	
	public static final int STATE_IO_FIELD=1, STATE_SIZE_DESC=2;
	
	public StructPipe(Struct<T> type, boolean runNow){
		this.type=type;
		init(runNow, ()->{
			this.ioFields=FieldSet.of(initFields());
			setInitState(STATE_IO_FIELD);
			
			sizeDescription=Objects.requireNonNull(createSizeDescriptor());
			setInitState(STATE_SIZE_DESC);
			generators=Utils.nullIfEmpty(ioFields.stream().flatMap(IOField::generatorStream).toList());
			referenceWalkCommands=generateReferenceWalkCommands();
			earlyNullChecks=Utils.nullIfEmpty(
				getNonNulls().filter(f->generators==null||generators.stream().noneMatch(gen->gen.field()==f))
				             .toList()
			);
		});
	}
	
	
	@SuppressWarnings("unchecked")
	private CommandSet generateReferenceWalkCommands(){
		var         builder  =CommandSet.builder();
		var         hasDynmic=getType() instanceof Struct.Unmanaged<?> u&&u.isOverridingDynamicUnmanaged();
		FieldSet<T> fields;
		
		if(getType() instanceof Struct.Unmanaged<?> unmanaged){
			fields=FieldSet.of(Stream.concat(getSpecificFields().stream(), unmanaged.getUnmanagedStaticFields().stream().map(f->(IOField<T, ?>)f)).toList());
		}else{
			fields=getSpecificFields();
		}
		
		var refs=fields.stream()
		               .map(f->f instanceof RefField<?, ?> ref?ref:null)
		               .filter(Objects::nonNull)
		               .map(ref->fields.byName(IOFieldTools.makeRefName(ref.getAccessor())).map(f->Map.entry(f, ref)))
		               .filter(Optional::isPresent)
		               .map(Optional::get)
		               .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		
		for(var field : fields){
			var refVal=refs.get(field);
			
			if(refVal!=null&&refVal.nullable()){
				var refi=fields.indexOf(field);
				var vali=fields.indexOf(refVal);
				if(refi==-1||vali==-1) throw new IllegalStateException();
				builder.skipFlowIfNull(vali-refi);
			}
			
			if(field.typeFlag(IOField.PRIMITIVE_OR_ENUM_FLAG)||field.typeFlag(IOField.HAS_NO_POINTERS_FLAG)){
				builder.skipField(field);
				continue;
			}
			
			if(field.typeFlag(IOField.DYNAMIC_FLAG)){
				builder.dynamic();
				continue;
			}
			
			if(field instanceof RefField){
				builder.referenceField();
				continue;
			}
			
			var accessor=field.getAccessor();
			if(accessor==null){
				builder.skipField(field);
				continue;
			}
			Class<?> type=accessor.getType();
			
			if(field.typeFlag(IOField.IOINSTANCE_FLAG)){
				if(Struct.canUnknownHavePointers(type)){
					builder.potentialReference();
				}else{
					builder.skipField(field);
				}
				continue;
			}
			
			if(type==ChunkPointer.class){
				builder.chptr();
				continue;
			}
			if(type==String.class){
				builder.skipField(field);
				continue;
			}
			
			if(type.isArray()){
				var pType=type;
				while(pType.isArray()){
					pType=pType.componentType();
				}
				
				if(SupportedPrimitive.isAny(pType)||pType.isEnum()||pType==String.class){
					builder.skipField(field);
					continue;
				}
			}
			
			throw new NotImplementedException(field+" not handled");
		}
		
		if(hasDynmic){
			builder.unmanagedRest();
		}else{
			builder.endFlow();
		}
		
		return builder.build();
	}
	
	
	public CommandSet getReferenceWalkCommands(){
		waitForState(STATE_DONE);
		return referenceWalkCommands;
	}
	
	@Override
	protected Stream<StateInfo> listStates(){
		return Stream.concat(
			super.listStates(),
			Stream.of(
				new StateInfo(STATE_IO_FIELD, "IO_FIELD"),
				new StateInfo(STATE_SIZE_DESC, "SIZE_DESC")
			)
		);
	}
	
	private Stream<IOField<T, ?>> getNonNulls(){
		return ioFields.unpackedStream().filter(f->f.getNullability()==IONullability.Mode.NOT_NULL&&f.getAccessor().canBeNull());
	}
	
	protected abstract List<IOField<T, ?>> initFields();
	
	
	protected record SizeGroup<T extends IOInstance<T>>(
		SizeDescriptor.UnknownNum<T> num,
		List<IOField<T, ?>> fields
	){
		protected SizeGroup(SizeDescriptor.UnknownNum<T> num, List<IOField<T, ?>> fields){
			this.num=Objects.requireNonNull(num);
			this.fields=List.copyOf(fields);
		}
	}
	
	protected record SizeRelationReport<T extends IOInstance<T>>(
		FieldSet<T> allFields,
		WordSpace wordSpace,
		long knownFixed,
		long min,
		OptionalLong max,
		boolean dynamic,
		List<SizeGroup<T>> sizeGroups,
		List<IOField<T, ?>> genericUnknown
	){
		protected SizeRelationReport(
			FieldSet<T> allFields, WordSpace wordSpace, long knownFixed, long min, OptionalLong max,
			boolean dynamic, List<SizeGroup<T>> sizeGroups, List<IOField<T, ?>> genericUnknown
		){
			if(min<0) throw new IllegalArgumentException();
			
			max.ifPresent(maxV->{
				if(min>maxV) throw new IllegalStateException("min is greater than max");
				if(knownFixed>maxV) throw new IllegalStateException("knownFixed is greater than max");
			});
			
			this.allFields=Objects.requireNonNull(allFields);
			this.wordSpace=Objects.requireNonNull(wordSpace);
			this.knownFixed=knownFixed;
			this.min=min;
			this.max=max;
			this.dynamic=dynamic;
			this.sizeGroups=List.copyOf(sizeGroups);
			this.genericUnknown=List.copyOf(genericUnknown);
		}
	}
	
	protected SizeRelationReport<T> createSizeReport(int minGroup){
		FieldSet<T> fields=getSpecificFields();
		if(type instanceof Struct.Unmanaged<?> u){
			var unmanagedStatic=(FieldSet<T>)u.getUnmanagedStaticFields();
			if(!unmanagedStatic.isEmpty()){
				fields=FieldSet.of(Stream.concat(fields.stream(), unmanagedStatic.stream()));
			}
		}
		
		var wordSpace=IOFieldTools.minWordSpace(fields);
		
		var hasDynamicFields=type instanceof Struct.Unmanaged<?> u&&u.isOverridingDynamicUnmanaged();
		
		type.waitForState(STATE_DONE);
		
		if(!hasDynamicFields){
			var sumFixedO=IOFieldTools.sumVarsIfAll(fields, desc->desc.getFixed(wordSpace));
			if(sumFixedO.isPresent()){
				var sumFixed=sumFixedO.getAsLong();
				return new SizeRelationReport<>(fields, wordSpace, sumFixed, sumFixed, sumFixedO, false, List.of(), List.of());
			}
		}
		
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
		
		
		var groups=fields.stream()
		                 .filter(f->f.getSizeDescriptor() instanceof SizeDescriptor.UnknownNum)
		                 .collect(Collectors.groupingBy(f->(SizeDescriptor.UnknownNum<T>)f.getSizeDescriptor()))
		                 .entrySet().stream()
		                 .filter(e->e.getValue().size()>=minGroup)
		                 .map(e->new SizeGroup<>(e.getKey(), e.getValue()))
		                 .collect(Collectors.toList());
		
		var groupNumberSet=groups.stream().map(e->e.num).collect(Collectors.toUnmodifiableSet());
		
		return new SizeRelationReport<>(
			fields, wordSpace, knownFixed, min, max, hasDynamicFields,
			groups,
			fields.stream()
			      .filter(f->!f.getSizeDescriptor().hasFixed())
			      .filter(f->!(f.getSizeDescriptor() instanceof SizeDescriptor.UnknownNum<T> num&&groupNumberSet.contains(num)))
			      .toList()
		);
	}
	
	@SuppressWarnings("unchecked")
	protected SizeDescriptor<T> createSizeDescriptor(){
		var report=createSizeReport(2);
		
		if(!report.dynamic&&report.max.orElse(-1)==report.min){
			return SizeDescriptor.Fixed.of(report.wordSpace, report.min);
		}
		
		var wordSpace     =report.wordSpace;
		var knownFixed    =report.knownFixed;
		var genericUnknown=report.genericUnknown;
		
		FieldAccessor<T>[] unkownNumAcc;
		int[]              unkownNumAccMul;
		
		if(report.sizeGroups.isEmpty()){
			unkownNumAcc=null;
			unkownNumAccMul=null;
		}else{
			unkownNumAcc=new FieldAccessor[report.sizeGroups.size()];
			unkownNumAccMul=new int[report.sizeGroups.size()];
			int i=0;
			for(var e : report.sizeGroups){
				unkownNumAcc[i]=e.num.getAccessor();
				unkownNumAccMul[i]=e.fields.size();
				i++;
			}
		}
		
		
		return SizeDescriptor.Unknown.of(wordSpace, report.min, report.max, (ioPool, prov, inst)->{
			checkNull(inst);
			
			waitForState(STATE_DONE);
			
			if(generators!=null){
				try{
					generateAll(ioPool, prov, inst, false);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			
			var unkownSum=IOFieldTools.sumVars(genericUnknown, d->{
				return d.calcUnknown(ioPool, prov, inst, wordSpace);
			});
			
			if(unkownNumAcc!=null){
				for(int i=0;i<unkownNumAcc.length;i++){
					var  accessor  =unkownNumAcc[i];
					var  numberSize=(NumberSize)accessor.get(ioPool, inst);
					long multiplier=unkownNumAccMul[i];
					unkownSum+=switch(wordSpace){
						case BIT -> numberSize.bits();
						case BYTE -> numberSize.bytes;
					}*multiplier;
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
	@Override
	public final void write(DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var ioPool=makeIOPool();
		earlyCheckNulls(ioPool, instance);
		doWrite(provider, dest, ioPool, instance);
	}
	
	protected abstract void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException;
	
	
	@Override
	public T readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		T instance=type.make();
		return doRead(makeIOPool(), provider, src, instance, genericContext);
	}
	
	public T read(DataProvider provider, RandomIO.Creator src, T instance, GenericContext genericContext) throws IOException{
		try(var io=src.io()){
			return doRead(makeIOPool(), provider, io, instance, genericContext);
		}
	}
	
	@Override
	public T read(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		return doRead(makeIOPool(), provider, src, instance, genericContext);
	}
	
	protected abstract T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	
	@Override
	public final SizeDescriptor<T> getSizeDescriptor(){
		waitForState(STATE_SIZE_DESC);
		return sizeDescription;
	}
	
	public Struct<T> getType(){
		return type;
	}
	
	public FieldSet<T> getSpecificFields(){
		waitForState(STATE_IO_FIELD);
		return ioFields;
	}
	
	public void earlyCheckNulls(VarPool<T> ioPool, T instance){
		waitForState(STATE_DONE);
		if(earlyNullChecks==null) return;
		for(var field : earlyNullChecks){
			if(field.isNull(ioPool, instance)){
				throw new FieldIsNullException(field);
			}
		}
	}
	
	protected void writeIOFields(FieldSet<T> fields, VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		if(Access.DEV_CACHE) throw new RuntimeException();
		
		ContentOutputBuilder destBuff=null;
		ContentWriter        target;
		
		if(DEBUG_VALIDATION){
			var safe=validateAndSafeDestination(fields, ioPool, provider, dest, instance);
			if(safe!=null) dest=safe;
		}
		
		if(dest.isDirect()){
			var siz=getSizeDescriptor().calcAllocSize(WordSpace.BYTE);
			destBuff=new ContentOutputBuilder((int)siz);
			target=destBuff;
		}else{
			target=dest;
		}
		
		waitForState(STATE_DONE);
		if(generators!=null){
			generateAll(ioPool, provider, instance, true);
		}
		
		for(IOField<T, ?> field : fields){
			if(DEBUG_VALIDATION){
				writeFieldKnownSize(ioPool, provider, target, instance, field);
			}else{
				field.writeReported(ioPool, provider, target, instance);
			}
		}
		
		if(destBuff!=null){
			destBuff.writeTo(dest);
		}
		if(DEBUG_VALIDATION){
			dest.close();
		}
	}
	
	private ContentWriter validateAndSafeDestination(FieldSet<T> fields, VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		ContentWriter safe=null;
		if(!(instance instanceof IOInstance.Unmanaged<?>)){
			waitForState(STATE_DONE);
			if(generators!=null){
				generateAll(ioPool, provider, instance, true);
			}
			var siz=getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
			
			var sum=0L;
			for(IOField<T, ?> field : fields){
				var desc =field.getSizeDescriptor();
				var bytes=desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
				sum+=bytes;
			}
			
			if(sum!=siz){
				StringJoiner sj=new StringJoiner("\n");
				sj.add("total"+siz);
				for(IOField<T, ?> field : fields){
					var desc =field.getSizeDescriptor();
					var bytes=desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
					sj.add(field+" "+bytes+" "+desc.hasFixed()+" "+desc.getWordSpace());
				}
				throw new RuntimeException(sj.toString());
			}
			
			safe=dest.writeTicket(siz).requireExact((written, expected)->{
				return new IOException(written+" "+expected+" on "+instance);
			}).submit();
		}
		return safe;
	}
	
	private void generateAll(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
		for(var generator : generators){
			try{
				generator.generate(ioPool, provider, instance, allowExternalMod);
			}catch(Throwable e){
				throw new IOException("Failed to generate fields. Problem on "+generator, e);
			}
		}
	}
	
	private void writeFieldKnownSize(VarPool<T> ioPool, DataProvider provider, ContentWriter target, T instance, IOField<T, ?> field) throws IOException{
		var desc    =field.getSizeDescriptor();
		var bytes   =desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
		var safeBuff=target.writeTicket(bytes).requireExact().submit();
		field.writeReported(ioPool, provider, safeBuff, instance);
		
		try{
			safeBuff.close();
		}catch(Exception e){
			throw new IOException(TextUtil.toString(field)+" ("+Utils.toShortString(field.get(ioPool, instance))+") did not write correctly", e);
		}
	}
	
	protected record IODependency<T extends IOInstance<T>>(
		FieldSet<T> writeFields,
		FieldSet<T> readFields,
		List<IOField.ValueGeneratorInfo<T, ?>> generators
	){}
	
	private final Map<IOField<T, ?>, IODependency<T>> singleDependencyCache    =new HashMap<>();
	private final ReadWriteLock                       singleDependencyCacheLock=new ReentrantReadWriteLock();
	
	protected IODependency<T> getDeps(IOField<T, ?> selectedField){
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
			
			for(IOField<T, ?> field : List.copyOf(selectedWriteFieldsSet)){
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
			for(IOField<T, ?> field : List.copyOf(selectedReadFieldsSet)){
				if(field.hasDependencies()){
					if(selectedReadFieldsSet.addAll(field.getDependencies())) shouldRun=true;
				}
			}
			
			//Find field and add to read fields when the field is skipped but is a dependency of another
			// skipped field that may need the dependency to correctly skip
			skipDependency:
			for(IOField<T, ?> field : selectedReadFieldsSet){
				var fields=getSpecificFields();
				var index =fields.indexOf(field);
				if(index==-1) continue;
				
				var before=new ArrayList<>(fields.subList(0, index));
				
				before.removeIf(selectedReadFieldsSet::contains);
				
				for(IOField<T, ?> skipped : before){
					//is skipped field dependency of another skipped field whos size may depend on it.
					if(before.stream().filter(e->!e.getSizeDescriptor().hasFixed())
					         .filter(e->e.getDependencies()!=null).flatMap(e->e.getDependencies().stream())
					         .anyMatch(e->e==skipped)){
						selectedReadFieldsSet.add(skipped);
						shouldRun=true;
						break skipDependency;
					}
				}
				
			}
			
			for(IOField<T, ?> field : List.copyOf(selectedWriteFieldsSet)){
				if(field.getSizeDescriptor().hasFixed()){
					continue;
				}
				
				var fields=getSpecificFields();
				var index =fields.indexOf(field);
				if(index==-1) throw new AssertionError();//TODO handle fields in fields
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
	
	private FieldSet<T> fieldSetToOrderedList(Set<IOField<T, ?>> fieldsSet){
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
		return FieldSet.of(result);
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
			var desc =field.getSizeDescriptor();
			var bytes=desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
			
			if(fields.get(checkIndex)==field){
				checkIndex++;
				if(DEBUG_VALIDATION){
					writeFieldKnownSize(ioPool, provider, dest, instance, field);
				}else{
					field.writeReported(ioPool, provider, dest, instance);
				}
				
				if(checkIndex==fields.size()){
					return;
				}
				
				continue;
			}
			
			dest.skipExact(bytes);
		}
		
	}
	
	protected void readIOFields(FieldSet<T> fields, VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		for(IOField<T, ?> field : fields){
			if(DEBUG_VALIDATION){
				readFieldSafe(ioPool, provider, src, instance, genericContext, field);
			}else{
				field.readReported(ioPool, provider, src, instance, genericContext);
			}
		}
	}
	
	public void readSingleField(VarPool<T> ioPool, DataProvider provider, ContentReader src, IOField<T, ?> selectedField, T instance, GenericContext genericContext) throws IOException{
		if(DEBUG_VALIDATION){
			checkExistenceOfField(selectedField);
		}
		
		var deps      =getDeps(selectedField);
		var fields    =deps.readFields;
		int checkIndex=0;
		int limit     =fields.size();
		
		for(IOField<T, ?> field : getSpecificFields()){
			if(fields.get(checkIndex)==field){
				checkIndex++;
				
				if(DEBUG_VALIDATION){
					readFieldSafe(ioPool, provider, src, instance, genericContext, field);
				}else{
					field.readReported(ioPool, provider, src, instance, genericContext);
				}
				
				if(checkIndex==limit){
					return;
				}
				
				continue;
			}
			
			field.skipReported(ioPool, provider, src, instance, genericContext);
		}
		throw new IllegalArgumentException(selectedField+" is not listed!");
	}
	
	private void checkExistenceOfField(IOField<T, ?> selectedField){
		for(IOField<T, ?> field : getSpecificFields()){
			if(field==selectedField){
				return;
			}
		}
		throw new IllegalArgumentException(selectedField+" is not listed!");
	}
	
	private void readFieldSafe(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext, IOField<T, ?> field) throws IOException{
		var desc =field.getSizeDescriptor();
		var fixed=desc.getFixed(WordSpace.BYTE);
		if(fixed.isPresent()){
			long bytes=fixed.getAsLong();
			
			String extra=null;
			if(DEBUG_VALIDATION){
				extra=" started on: "+src;
			}
			
			var buf=src.readTicket(bytes).requireExact().submit();
			
			try{
				field.readReported(ioPool, provider, buf, instance, genericContext);
			}catch(Exception e){
				throw new IOException(TextUtil.toString(field)+" failed to read!"+(DEBUG_VALIDATION?extra:""), e);
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
	
	@Override
	public VarPool<T> makeIOPool(){
		return getType().allocVirtualVarPool(VirtualFieldDefinition.StoragePool.IO);
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
		return shortPipeName(getClass())+"("+type.cleanName()+")";
	}
	
	private static String shortPipeName(Class<?> cls){
		var pipName=cls.getSimpleName();
		var end    ="StructPipe";
		if(pipName.endsWith(end)){
			pipName="~~"+pipName.substring(0, pipName.length()-end.length());
		}
		return pipName;
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
