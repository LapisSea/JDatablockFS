package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.config.ConfigDefs;
import com.lapissea.cfs.exceptions.FieldIsNull;
import com.lapissea.cfs.exceptions.MalformedObject;
import com.lapissea.cfs.exceptions.MalformedPipe;
import com.lapissea.cfs.exceptions.RecursiveSelfCompilation;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.bit.BitUtils;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.CommandSet;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.StagedInit;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.compilation.FieldCompiler;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.StoragePool;
import com.lapissea.cfs.type.field.VaryingSize;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldInlineSealedObject;
import com.lapissea.cfs.utils.ClosableLock;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.config.GlobalConfig.TYPE_VALIDATION;
import static com.lapissea.util.ConsoleColors.BLUE_BRIGHT;
import static com.lapissea.util.ConsoleColors.CYAN_BRIGHT;
import static com.lapissea.util.ConsoleColors.RESET;
import static java.util.function.Predicate.not;

public abstract class StructPipe<T extends IOInstance<T>> extends StagedInit implements ObjectPipe<T, VarPool<T>>{
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	public @interface Special{ }
	
	private static class StructGroup<T extends IOInstance<T>, P extends StructPipe<T>> extends ConcurrentHashMap<Struct<T>, P>{
		
		interface PipeConstructor<T extends IOInstance<T>, P extends StructPipe<T>>{
			P make(Struct<T> type, boolean runNow);
		}
		
		private final Map<Struct<T>, Supplier<P>> specials = new HashMap<>();
		private final PipeConstructor<T, P>       lConstructor;
		private final Class<?>                    type;
		
		private static class CompileInfo{
			private final ClosableLock lock = ClosableLock.reentrant();
			private       int          recursiveCompilingDepth;
		}
		
		private final Map<Struct<T>, Throwable>   errors = new HashMap<>();
		private final Map<Struct<T>, CompileInfo> locks  = Collections.synchronizedMap(new HashMap<>());
		
		private StructGroup(Class<? extends StructPipe<?>> type){
			try{
				lConstructor = Access.makeLambda(type.getConstructor(Struct.class, boolean.class), PipeConstructor.class);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException("Failed to get pipe constructor", e);
			}
			this.type = type;
		}
		
		P make(Struct<T> struct, boolean runNow){
			var cached = get(struct);
			if(cached != null) return cached;
			return lockingMake(struct, runNow);
		}
		
		private P lockingMake(Struct<T> struct, boolean runNow){
			var info = locks.computeIfAbsent(struct, __ -> new CompileInfo());
			try(var ignored = info.lock.open()){
				var cached = get(struct);
				if(cached != null) return cached;
				
				if(info.recursiveCompilingDepth>50){
					throw new RecursiveSelfCompilation();
				}
				info.recursiveCompilingDepth++;
				
				return createPipe(struct, runNow);
			}finally{
				locks.remove(struct);
			}
		}
		
		private P createPipe(Struct<T> struct, boolean runNow){
			var err = errors.get(struct);
			if(err != null) throw err instanceof RuntimeException e? e : new RuntimeException(err);
			
			Log.trace("Requested pipe({}#greenBright): {}#blue{}#blueBright", () -> {
				var name     = struct.cleanFullName();
				var smolName = struct.cleanName();
				
				return List.of(shortPipeName(type), name.substring(0, name.length() - smolName.length()), smolName);
			});
			
			P created;
			try{
				var typ = struct.getType();
				//Special types must be statically initialized as they may add new special implementations.
				if(typ.isAnnotationPresent(Special.class)){
					try{
						Class.forName(typ.getName(), true, typ.getClassLoader());
					}catch(ClassNotFoundException e){
						throw new ShouldNeverHappenError(e);
					}
				}
				
				var special = specials.get(struct);
				if(special != null){
					created = special.get();
				}else{
					created = lConstructor.make(struct, runNow);
				}
			}catch(Throwable e){
				e.addSuppressed(new MalformedPipe("Failed to compile " + type.getSimpleName() + " for " + struct.getFullName(), e));
				errors.put(struct, e);
				throw e;
			}
			
			put(struct, created);//TODO: replace put/remove with scoped value as temporary storage before putting. Avoid potentially invalid result
			if(runNow){
				try{
					created.postValidate();
				}catch(Throwable e){
					remove(struct);
				}
			}
			
			if(ConfigDefs.PRINT_COMPILATION.resolveVal()){
				StagedInit.runBaseStageTask(() -> {
					String s = "Compiled: " + struct.getFullName() + "\n" +
					           "\tPipe type: " + BLUE_BRIGHT + created.getClass().getName() + CYAN_BRIGHT + "\n" +
					           "\tSize: " + BLUE_BRIGHT + created.getSizeDescriptor() + CYAN_BRIGHT + "\n" +
					           "\tReference commands: " + created.getReferenceWalkCommands();
					
					var sFields = created.getSpecificFields();
					
					if(!sFields.equals(struct.getFields())){
						s += "\n" + TextUtil.toTable(created.getSpecificFields());
					}
					
					Log.log(CYAN_BRIGHT + s + RESET);
				});
			}
			
			return created;
		}
		
		private void registerSpecialImpl(Struct<T> struct, Supplier<P> newType){
			specials.put(struct, newType);
		}
	}
	
	private static final ConcurrentHashMap<Class<? extends StructPipe<?>>, StructGroup<?, ?>> CACHE = new ConcurrentHashMap<>();
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends StructPipe<T>> P of(Class<P> type, Struct<T> struct, int minRequestedStage){
		try{
			var group = (StructGroup<T, P>)CACHE.computeIfAbsent(type, StructGroup::new);
			var pipe  = group.make(struct, minRequestedStage == STATE_DONE);
			pipe.waitForState(minRequestedStage);
			return pipe;
		}catch(Throwable e){
			throw Utils.interceptClInit(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends StructPipe<T>> P of(Class<P> type, Struct<T> struct){
		try{
			var group = (StructGroup<T, P>)CACHE.computeIfAbsent(type, StructGroup::new);
			return group.make(struct, false);
		}catch(Throwable e){
			throw Utils.interceptClInit(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>, P extends StructPipe<T>> void registerSpecialImpl(Struct<T> struct, Class<P> oldType, Supplier<P> newType){
		var group = (StructGroup<T, P>)CACHE.computeIfAbsent(oldType, StructGroup::new);
		group.registerSpecialImpl(struct, newType);
	}
	
	
	private final Struct<T>           type;
	private       SizeDescriptor<T>   sizeDescription;
	private       FieldSet<T>         ioFields;
	private       CommandSet          referenceWalkCommands;
	private       List<IOField<T, ?>> earlyNullChecks;
	
	private List<IOField.ValueGeneratorInfo<T, ?>> generators;
	
	private FieldDependency<T> fieldDependency;
	
	public static final int STATE_IO_FIELD = 1, STATE_SIZE_DESC = 2;
	
	public <E extends Exception> StructPipe(Struct<T> type, PipeFieldCompiler<T, E> compiler, boolean initNow) throws E{
		this.type = type;
		init(initNow, () -> {
			try{
				this.ioFields = FieldSet.of(compiler.compile(getType(), getType().getFields()));
			}catch(Exception e){
				throw UtilL.uncheckedThrow(e);
			}
			fieldDependency = new FieldDependency<>(getType(), ioFields);
			setInitState(STATE_IO_FIELD);
			
			sizeDescription = Objects.requireNonNull(createSizeDescriptor());
			setInitState(STATE_SIZE_DESC);
			generators = Utils.nullIfEmpty(ioFields.stream().flatMap(IOField::generatorStream).toList());
			referenceWalkCommands = generateReferenceWalkCommands();
			earlyNullChecks = !DEBUG_VALIDATION? null : Utils.nullIfEmpty(
				getNonNulls().filter(f -> generators == null || generators.stream().noneMatch(gen -> gen.field() == f))
				             .toList()
			);
			//Do not post validate now, will create issues with recursive types. It is called in registration
		}, initNow? null : this::postValidate);
	}
	
	protected void postValidate(){
		if(TYPE_VALIDATION && !(getType() instanceof Struct.Unmanaged)){
			var type = getType();
			T   inst;
			try{
				inst = type.make();
			}catch(Throwable e){
				inst = null;
			}
			if(inst != null){
				try{
					checkTypeIntegrity(inst, true);
				}catch(FieldIsNull ignored){
				}catch(IOException e){
					e.printStackTrace();
					throw new RuntimeException(e);
				}
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private CommandSet generateReferenceWalkCommands(){
		var         builder = CommandSet.builder();
		FieldSet<T> fields;
		
		getType().waitForStateDone();
		if(getType() instanceof Struct.Unmanaged<?> unmanaged){
			fields = FieldSet.of(Stream.concat(getSpecificFields().stream(), unmanaged.getUnmanagedStaticFields().stream().map(f -> (IOField<T, ?>)f)).toList());
		}else{
			fields = getSpecificFields();
		}
		
		var refs = fields.stream()
		                 .filter(RefField.class::isInstance)
		                 .map(RefField.class::cast)
		                 .map(ref -> fields.byName(IOFieldTools.makeRefName(ref.getAccessor())).map(f -> Map.entry(f, ref)))
		                 .filter(Optional::isPresent)
		                 .map(Optional::get)
		                 .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		
		for(var field : fields){
			var refVal = refs.get(field);
			
			if(refVal != null && refVal.nullable()){
				var refi = fields.indexOf(field);
				var vali = fields.indexOf(refVal);
				if(refi == -1 || vali == -1) throw new IllegalStateException();
				builder.skipFlowIfNull(vali - refi);
			}
			
			if(field.typeFlag(IOField.PRIMITIVE_OR_ENUM_FLAG) || field.typeFlag(IOField.HAS_NO_POINTERS_FLAG)){
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
			if(field instanceof IOFieldInlineSealedObject){
				builder.dynamic();//TODO determine if sealed object can have pointers, if not skip here
				continue;
			}
			
			var accessor = field.getAccessor();
			if(accessor == null){
				builder.skipField(field);
				continue;
			}
			Class<?> type = accessor.getType();
			
			if(field.typeFlag(IOField.IOINSTANCE_FLAG)){
				if(Struct.canUnknownHavePointers(type)){
					builder.potentialReference();
				}else{
					builder.skipField(field);
				}
				continue;
			}
			
			if(type == ChunkPointer.class){
				builder.chptr();
				continue;
			}
			
			if(FieldCompiler.getWrapperTypes().contains(type)){
				builder.skipField(field);
				continue;
			}
			if(UtilL.instanceOf(type, List.class)){
				var elType = ((ParameterizedType)accessor.getGenericType(null)).getActualTypeArguments()[0];
				if(elType == String.class){
					builder.skipField(field);
					continue;
				}
			}
			
			if(type.isArray()){
				var pType = type;
				while(pType.isArray()){
					pType = pType.componentType();
				}
				
				if(SupportedPrimitive.isAny(pType) || pType.isEnum() || pType == String.class){
					builder.skipField(field);
					continue;
				}
			}
			
			throw new NotImplementedException(field + " (" + type.getName() + ") not handled");
		}
		
		if(getType() instanceof Struct.Unmanaged<?> u && u.isOverridingDynamicUnmanaged()){
			builder.unmanagedRest();
		}else{
			builder.endFlow();
		}
		
		return builder.build();
	}
	
	
	public CommandSet getReferenceWalkCommands(){
		waitForStateDone();
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
		return ioFields.unpackedStream().filter(f -> f.getNullability() == IONullability.Mode.NOT_NULL && f.getAccessor().canBeNull());
	}
	
	protected record SizeGroup<T extends IOInstance<T>>(
		SizeDescriptor.UnknownNum<T> num,
		List<IOField<T, ?>> fields
	){
		protected SizeGroup(SizeDescriptor.UnknownNum<T> num, List<IOField<T, ?>> fields){
			this.num = Objects.requireNonNull(num);
			this.fields = List.copyOf(fields);
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
			
			max.ifPresent(maxV -> {
				if(min>maxV) throw new IllegalStateException("min is greater than max");
				if(knownFixed>maxV) throw new IllegalStateException("knownFixed is greater than max");
			});
			
			this.allFields = Objects.requireNonNull(allFields);
			this.wordSpace = Objects.requireNonNull(wordSpace);
			this.knownFixed = knownFixed;
			this.min = min;
			this.max = max;
			this.dynamic = dynamic;
			this.sizeGroups = List.copyOf(sizeGroups);
			this.genericUnknown = List.copyOf(genericUnknown);
		}
	}
	
	protected SizeRelationReport<T> createSizeReport(int minGroup){
		FieldSet<T> fields = getSpecificFields();
		if(type instanceof Struct.Unmanaged<?> u){
			var unmanagedStatic = (FieldSet<T>)u.getUnmanagedStaticFields();
			if(!unmanagedStatic.isEmpty()){
				fields = FieldSet.of(Stream.concat(fields.stream(), unmanagedStatic.stream()));
			}
		}
		
		type.waitForState(Struct.STATE_INIT_FIELDS);
		var wordSpace = IOFieldTools.minWordSpace(fields);
		
		var hasDynamicFields = type instanceof Struct.Unmanaged<?> u && u.isOverridingDynamicUnmanaged();
		
		type.waitForStateDone();
		
		if(!hasDynamicFields){
			var sumFixedO = IOFieldTools.sumVarsIfAll(fields, desc -> desc.getFixed(wordSpace));
			if(sumFixedO.isPresent()){
				var sumFixed = sumFixedO.getAsLong();
				return new SizeRelationReport<>(fields, wordSpace, sumFixed, sumFixed, sumFixedO, false, List.of(), List.of());
			}
		}
		
		var knownFixed = IOFieldTools.sumVars(fields, d -> {
			var fixed = d.getFixed(wordSpace);
			if(fixed.isPresent()){
				var siz = fixed.getAsLong();
				return switch(wordSpace){
					case BIT -> BitUtils.bitsToBytes(siz)*Byte.SIZE;
					case BYTE -> siz;
				};
			}
			return 0;
		});
		
		var min = IOFieldTools.sumVars(fields, siz -> siz.getMin(wordSpace));
		var max = hasDynamicFields? OptionalLong.empty() : IOFieldTools.sumVarsIfAll(fields, siz -> siz.getMax(wordSpace));
		
		
		var groups = fields.stream()
		                   .filter(f -> f.getSizeDescriptor() instanceof SizeDescriptor.UnknownNum)
		                   .collect(Collectors.groupingBy(f -> (SizeDescriptor.UnknownNum<T>)f.getSizeDescriptor()))
		                   .entrySet().stream()
		                   .filter(e -> e.getValue().size()>=minGroup)
		                   .map(e -> new SizeGroup<>(e.getKey(), e.getValue()))
		                   .collect(Collectors.toList());
		
		var groupNumberSet = groups.stream().map(e -> e.num).collect(Collectors.toUnmodifiableSet());
		
		return new SizeRelationReport<>(
			fields, wordSpace, knownFixed, min, max, hasDynamicFields,
			groups,
			fields.stream()
			      .filter(f -> !f.getSizeDescriptor().hasFixed())
			      .filter(f -> !(f.getSizeDescriptor() instanceof SizeDescriptor.UnknownNum<T> num && groupNumberSet.contains(num)))
			      .toList()
		);
	}
	
	@SuppressWarnings("unchecked")
	protected SizeDescriptor<T> createSizeDescriptor(){
		var report = createSizeReport(1);
		
		if(!report.dynamic && report.max.orElse(-1) == report.min){
			return SizeDescriptor.Fixed.of(report.wordSpace, report.min);
		}
		
		var wordSpace      = report.wordSpace;
		var knownFixed     = report.knownFixed;
		var genericUnknown = report.genericUnknown;
		
		FieldAccessor<T>[] unkownNumAcc;
		int[]              unkownNumAccMul;
		
		if(report.sizeGroups.isEmpty()){
			unkownNumAcc = null;
			unkownNumAccMul = null;
		}else{
			unkownNumAcc = new FieldAccessor[report.sizeGroups.size()];
			unkownNumAccMul = new int[report.sizeGroups.size()];
			int i = 0;
			for(SizeGroup(var num, var fields) : report.sizeGroups){
				unkownNumAcc[i] = num.getAccessor();
				unkownNumAccMul[i] = fields.size();
				i++;
			}
		}
		
		
		return SizeDescriptor.Unknown.of(wordSpace, report.min, report.max, (ioPool, prov, inst) -> {
			checkNull(inst);
			
			try{
				generateAll(ioPool, prov, inst, false);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			
			var unkownSum = IOFieldTools.sumVars(genericUnknown, d -> {
				return d.calcUnknown(ioPool, prov, inst, wordSpace);
			});
			
			if(unkownNumAcc != null){
				for(int i = 0; i<unkownNumAcc.length; i++){
					var  accessor   = unkownNumAcc[i];
					var  numberSize = (NumberSize)accessor.get(ioPool, inst);
					long multiplier = unkownNumAccMul[i];
					unkownSum += switch(wordSpace){
						case BIT -> numberSize.bits();
						case BYTE -> numberSize.bytes;
					}*multiplier;
				}
			}
			
			return knownFixed + unkownSum;
		});
	}
	
	private void checkNull(T inst){
		Objects.requireNonNull(inst, () -> "instance of type " + getType() + " is null!");
	}
	
	@Override
	public final void write(DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var ioPool = makeIOPool();
		if(DEBUG_VALIDATION) earlyCheckNulls(ioPool, instance);
		doWrite(provider, dest, ioPool, instance);
	}
	
	protected abstract void doWrite(DataProvider provider, ContentWriter dest, VarPool<T> ioPool, T instance) throws IOException;
	
	
	public final T readNewSelective(DataProvider provider, ContentReader src, FieldDependency.Ticket<T> depTicket, GenericContext genericContext, boolean strictHolder) throws IOException{
		T instance;
		if(strictHolder && type.isDefinition()){
			instance = type.partialImplementation(depTicket.readFields()).make();
		}else{
			instance = type.make();
		}
		readDeps(makeIOPool(), provider, src, depTicket, instance, genericContext);
		return instance;
	}
	
	@Override
	public final T readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		T instance = type.make();
		try{
			return doRead(makeIOPool(), provider, src, instance, genericContext);
		}catch(IOException e){
			throw new IOException("Failed reading " + getType().cleanFullName(), e);
		}
	}
	
	public final void read(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		try{
			doRead(makeIOPool(), provider, src, instance, genericContext);
		}catch(IOException e){
			throw new IOException("Failed reading " + getType().cleanFullName(), e);
		}
	}
	
	protected abstract T doRead(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	
	@Override
	public final SizeDescriptor<T> getSizeDescriptor(){
		waitForState(STATE_SIZE_DESC);
		return sizeDescription;
	}
	
	public final Struct<T> getType(){
		return type;
	}
	
	public final FieldSet<T> getSpecificFields(){
		waitForState(STATE_IO_FIELD);
		return ioFields;
	}
	
	public final FieldDependency<T> getFieldDependency(){
		waitForState(STATE_IO_FIELD);
		return fieldDependency;
	}
	
	public final void earlyCheckNulls(VarPool<T> ioPool, T instance){
		waitForStateDone();
		if(earlyNullChecks == null) return;
		for(var field : earlyNullChecks){
			if(field.isNull(ioPool, instance)){
				throw new FieldIsNull(field);
			}
		}
	}
	
	protected final void writeIOFields(FieldSet<T> fields, VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		ContentOutputBuilder destBuff = null;
		ContentWriter        target;
		boolean              close    = false;
		
		if(DEBUG_VALIDATION){
			var safe = validateAndSafeDestination(fields, ioPool, provider, dest, instance);
			if(safe != null){
				dest = safe;
				close = true;
			}
		}
		
		if(dest.isDirect()){
			var siz = getSizeDescriptor().calcAllocSize(WordSpace.BYTE);
			destBuff = new ContentOutputBuilder((int)siz);
			target = destBuff;
		}else{
			target = dest;
		}
		
		generateAll(ioPool, provider, instance, true);
		
		try{
			for(IOField<T, ?> field : fields){
				if(DEBUG_VALIDATION){
					writeFieldKnownSize(ioPool, provider, target, instance, field);
				}else{
					field.writeReported(ioPool, provider, target, instance);
				}
			}
		}catch(VaryingSize.TooSmall e){
			throw VaryingSize.makeInvalid(fields, ioPool, instance, e);
		}
		
		if(destBuff != null){
			destBuff.writeTo(dest);
		}
		if(close){
			dest.close();
		}
	}
	
	private ContentWriter validateAndSafeDestination(FieldSet<T> fields, VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		if(instance instanceof IOInstance.Unmanaged) return null;
		
		generateAll(ioPool, provider, instance, true);
		var siz = getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
		
		var sum = 0L;
		for(IOField<T, ?> field : fields){
			var desc  = field.getSizeDescriptor();
			var bytes = desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
			sum += bytes;
		}
		
		if(sum != siz){
			StringJoiner sj = new StringJoiner("\n");
			sj.add("total" + siz);
			for(IOField<T, ?> field : fields){
				var desc  = field.getSizeDescriptor();
				var bytes = desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
				sj.add(field + " " + bytes + " " + desc.hasFixed() + " " + desc.getWordSpace());
			}
			throw new RuntimeException(sj.toString());
		}
		
		return dest.writeTicket(siz).requireExact((written, expected) -> {
			return new IOException(written + " " + expected + " on " + instance);
		}).submit();
	}
	
	private boolean hasGenerators(){
		waitForStateDone();
		return generators != null;
	}
	private void generateAll(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
		if(hasGenerators()){
			generateAll(generators, ioPool, provider, instance, allowExternalMod);
		}
	}
	private void generateAll(List<IOField.ValueGeneratorInfo<T, ?>> generators, VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
		for(var generator : generators){
			try{
				generator.generate(ioPool, provider, instance, allowExternalMod);
			}catch(IOException e){
				throw new IOException("Failed to generate fields. Problem on " + generator, e);
			}catch(FieldIsNull e){
				e.addSuppressed(new RuntimeException("Failed to generate fields. Problem on " + generator));
				throw e;
			}catch(Throwable e){
				throw new RuntimeException("Failed to generate fields. Problem on " + generator, e);
			}
		}
	}
	
	private void writeFieldKnownSize(VarPool<T> ioPool, DataProvider provider, ContentWriter target, T instance, IOField<T, ?> field) throws IOException{
		var desc     = field.getSizeDescriptor();
		var bytes    = desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
		var safeBuff = target.writeTicket(bytes).requireExact().submit();
		field.writeReported(ioPool, provider, safeBuff, instance);
		
		try{
			safeBuff.close();
		}catch(Exception e){
			throw new IOException(TextUtil.toString(field) + " - " + field.getClass().getSimpleName() + " (" + Utils.toShortString(field.get(ioPool, instance)) + ") did not write correctly", e);
		}
	}
	
	public void writeSingleField(DataProvider provider, RandomIO dest, IOField<T, ?> selectedField, T instance) throws IOException{
		var deps = getFieldDependency().getDeps(selectedField);
		writeDeps(provider, dest, deps, instance);
	}
	
	public void writeDeps(DataProvider provider, RandomIO dest, FieldDependency.Ticket<T> deps, T instance) throws IOException{
		var fields = deps.writeFields();
		var ioPool = makeIOPool();
		
		var gen = deps.generators();
		if(gen != null) generateAll(gen, ioPool, provider, instance, true);
		
		var atomicIO = fields.size()>1? dest.localTransactionBuffer(false) : dest;
		
		int checkIndex = 0;
		try{
			for(IOField<T, ?> field : getSpecificFields()){
				if(fields.get(checkIndex) == field){
					checkIndex++;
					if(DEBUG_VALIDATION){
						writeFieldKnownSize(ioPool, provider, atomicIO, instance, field);
					}else{
						field.writeReported(ioPool, provider, atomicIO, instance);
					}
					
					if(checkIndex == fields.size()){
						return;
					}
					
					continue;
				}
				
				atomicIO.skipExact(field.getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BYTE));
			}
		}finally{
			if(atomicIO != dest){
				atomicIO.close();
			}
		}
	}
	
	protected void readIOFields(FieldSet<T> fields, VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		for(IOField<T, ?> field : fields){
			readField(ioPool, provider, src, instance, genericContext, field);
		}
	}
	
	public void readSelectiveFields(VarPool<T> ioPool, DataProvider provider, ContentReader src, FieldSet<T> selectedFields, T instance, GenericContext genericContext) throws IOException{
		var all = getSpecificFields();
		if(selectedFields.size() == all.size()){
			readIOFields(all, ioPool, provider, src, instance, genericContext);
			return;
		}
		
		var deps = getFieldDependency().getDeps(selectedFields);
		readDeps(ioPool, provider, src, deps, instance, genericContext);
	}
	
	public void readDeps(VarPool<T> ioPool, DataProvider provider, ContentReader src, FieldDependency.Ticket<T> deps, T instance, GenericContext genericContext) throws IOException{
		var fields = deps.readFields();
		if(fields.isEmpty()){
			return;
		}
		if(fields.size() == ioFields.size()){
			readIOFields(ioFields, ioPool, provider, src, instance, genericContext);
			return;
		}
		
		int checkIndex = 0;
		int limit      = fields.size();
		
		for(IOField<T, ?> field : ioFields){
			if(fields.get(checkIndex) == field){
				checkIndex++;
				
				readField(ioPool, provider, src, instance, genericContext, field);
				
				if(checkIndex == limit){
					return;
				}
				
				continue;
			}
			
			try{
				field.skip(ioPool, provider, src, instance, genericContext);
			}catch(IOException e){
				throw fail(field, e, "Failed to skip");
			}
		}
	}
	
	protected void readField(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext, IOField<T, ?> field) throws IOException{
		if(DEBUG_VALIDATION && field.getSizeDescriptor().hasFixed()){
			readFieldSafe(ioPool, provider, src, instance, genericContext, field);
		}else{
			try{
				field.read(ioPool, provider, src, instance, genericContext);
			}catch(IOException e){
				e.addSuppressed(new IOException("Failed to read " + field));
				throw e;
			}
		}
	}
	
	private void readFieldSafe(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext, IOField<T, ?> field) throws IOException{
		var desc  = field.getSizeDescriptor();
		var bytes = desc.requireFixed(WordSpace.BYTE);
		
		ContentInputStream buf;
		try{
			buf = src.readTicket(bytes).requireExact().submit();
		}catch(Exception e){
			throw fail(field, e, "failed to prepare raw data");
		}
		
		try{
			field.read(ioPool, provider, buf, instance, genericContext);
		}catch(Exception e){
			throw fail(field, e, "failed to read");
		}
		
		try{
			buf.close();
		}catch(Exception e){
			throw fail(field, e, "did not read correctly");
		}
	}
	private static IOException fail(IOField<?, ?> field, Exception e, String msg){
		return new IOException(field + " " + msg, e);
	}
	
	@Override
	public VarPool<T> makeIOPool(){
		return getType().allocVirtualVarPool(StoragePool.IO);
	}
	
	public void checkTypeIntegrity() throws IOException{
		checkTypeIntegrity(type.make(), true);
	}
	public void checkTypeIntegrity(T inst, boolean init) throws IOException{
		var man = DataProvider.newVerySimpleProvider();
		
		if(init){
			var fields = getType().getFields();
			for(IOField<T, ?> field : fields){
				if(field.isVirtual()) continue;
				if(SupportedPrimitive.get(field.getType()).filter(SupportedPrimitive::isInteger).isEmpty()) continue;
				
				field.getAccessor().getAnnotation(IODependency.class)
				     .map(IODependency::value).stream().flatMap(Arrays::stream)
				     .map(fields::byName).map(Optional::orElseThrow)
				     .filter(n -> n.getType() == NumberSize.class)
				     .findAny()//dependency that is a numsize
				     .filter(not(IOField::isVirtual))
				     .filter(f -> {
					     if(f.nullable()) return false;
					     return f.getAccessor().get(null, inst) == null;
				     })
				     .ifPresent(f -> {
					     var val        = field.get(null, inst);
					     var isUnsigned = field.getAccessor().hasAnnotation(IOValue.Unsigned.class);
					     ((IOField<T, NumberSize>)f).set(null, inst, NumberSize.bySize(((Number)val).longValue(), isUnsigned));
				     });
			}
			
			if(inst.getThisStruct().hasInvalidInitialNulls()){
				inst.allocateNulls(man);
			}
		}
		
		{
			var clone = inst.clone();
			if(inst == clone) throw new MalformedObject("Clone returns itself: " + getType().cleanFullName());
			if(!inst.equals(clone)){
				throw new MalformedObject("equals() or clone() are invalid. a.equals(b) returns false: " + getType().cleanFullName());
			}
		}
		
		T instRead;
		try{
			var ch = AllocateTicket.withData(this, man, inst).submit(man);
			write(ch, inst);
			instRead = readNew(ch, null);
		}catch(IOException e){
			throw new MalformedObject("Failed object IO " + getType(), e);
		}
		
		if(!instRead.equals(inst)){
			throw new MalformedObject(
				getType() + " has failed integrity check.\n" +
				"Expected: " + inst + "\n" +
				"Actual:   " + instRead
			);
		}
	}
	
	@Override
	public String toString(){
		return shortPipeName(getClass()) + "(" + type.cleanName() + ")";
	}
	
	private static String shortPipeName(Class<?> cls){
		var pipName = cls.getSimpleName();
		var end     = "StructPipe";
		if(pipName.endsWith(end)){
			pipName = "~~" + pipName.substring(0, pipName.length() - end.length());
		}
		return pipName;
	}
	
	@Override
	public boolean equals(Object o){
		if(this == o) return true;
		if(!(o instanceof StructPipe<?> that)) return false;
		
		if(!type.equals(that.type)) return false;
		return ioFields.equals(that.ioFields);
	}
	@Override
	public int hashCode(){
		int result = type.hashCode();
		result = 31*result + ioFields.hashCode();
		return result;
	}
}
