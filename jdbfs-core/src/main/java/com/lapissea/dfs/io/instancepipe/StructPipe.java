package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.FieldIsNull;
import com.lapissea.dfs.exceptions.InvalidGenericArgument;
import com.lapissea.dfs.exceptions.MalformedObject;
import com.lapissea.dfs.exceptions.RecursiveSelfCompilation;
import com.lapissea.dfs.exceptions.TypeIOFail;
import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.internal.Runner;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.CommandSet;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.compilation.helpers.ProxyBuilder;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.StoragePool;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldInlineSealedObject;
import com.lapissea.dfs.type.field.fields.reflection.InstanceCollection;
import com.lapissea.dfs.utils.ClosableLock;
import com.lapissea.dfs.utils.GcDelayer;
import com.lapissea.dfs.utils.KeyCounter;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.ParameterizedType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.config.GlobalConfig.TYPE_VALIDATION;
import static com.lapissea.util.ConsoleColors.BLUE_BRIGHT;
import static com.lapissea.util.ConsoleColors.CYAN_BRIGHT;
import static com.lapissea.util.ConsoleColors.RESET;

public abstract class StructPipe<T extends IOInstance<T>> extends StagedInit implements ObjectPipe<T, VarPool<T>>{
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	public @interface Special{ }
	
	private static final class StructGroup<T extends IOInstance<T>, P extends StructPipe<T>>{
		
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
		
		private final WeakKeyValueMap<Struct<T>, P> pipes            = new WeakKeyValueMap.Sync<>();
		private final KeyCounter<String>            compilationCount = new KeyCounter<>();
		private final Duration                      gcDelay          = ConfigDefs.DELAY_COMP_OBJ_GC.resolveLocking();
		private final GcDelayer                     gcDelayer        = gcDelay.isZero()? null : new GcDelayer();
		
		P make(Struct<T> struct, boolean runNow){
			var cached = pipes.get(struct);
			if(cached != null) return cached;
			return lockingMake(struct, runNow);
		}
		
		private P lockingMake(Struct<T> struct, boolean runNow){
			var info = locks.computeIfAbsent(struct, __ -> new CompileInfo());
			try(var ignored = info.lock.open()){
				var cached = pipes.get(struct);
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
		
		@SuppressWarnings("unchecked")
		private P createPipe(Struct<T> struct, boolean runNow){
			var err = errors.get(struct);
			if(err != null) throw err instanceof RuntimeException e? e : new RuntimeException(err);
			
			var printLogLevel = ConfigDefs.PRINT_COMPILATION.resolve();
			
			var encounterKey   = struct.getType().getClassLoader().hashCode() + "/" + struct.getType().getName();
			var prevEncounters = compilationCount.getCount(encounterKey);
			if(printLogLevel.isWithin(ConfigDefs.CompLogLevel.JUST_START)){
				var name     = struct.cleanFullName();
				var smolName = struct.cleanName();
				var again    = prevEncounters>0? " (again #" + prevEncounters + ")" : "";
				Log.trace("Requested pipe({}#greenBright): {}#blue{}#blueBright{}",
				          shortPipeName(type), name.substring(0, name.length() - smolName.length()), smolName, again);
			}
			
			P created;
			try{
				var typ = struct.getType();
				//Special types must be statically initialized as they may add new special implementations.
				if(typ.isAnnotationPresent(Special.class)){
					Utils.ensureClassLoaded(typ);
				}
				
				var special = specials.get(struct);
				if(special != null){
					var specialInst = special.get();
					if(DEBUG_VALIDATION){
						var check = lConstructor.make(struct, runNow);
						created = switch(check){
							case StandardStructPipe<?> p ->
								(P)new CheckedPipe.Standard<>((StandardStructPipe<T>)check, (StandardStructPipe<T>)specialInst);
							case FixedStructPipe<?> p -> (P)new CheckedPipe.Fixed<>((FixedStructPipe<T>)check, (FixedStructPipe<T>)specialInst);
							default -> check;
						};
					}else{
						created = specialInst;
					}
				}else{
					created = lConstructor.make(struct, runNow);
				}
			}catch(Throwable e){
				errors.put(struct, e);
				throw e;
			}
			
			//TODO: replace put/remove with scoped value as temporary storage before putting. Avoid potentially invalid result
			pipes.put(struct, created);
			if(runNow){
				try{
					created.postValidate();
				}catch(Throwable e){
					pipes.remove(struct);
					errors.put(struct, e);
					throw e;
				}
			}
			
			int count = compilationCount.inc(encounterKey);
			if(count>1 && gcDelayer != null){
				gcDelayer.delay(created, gcDelay.multipliedBy(count - 1));
			}
			
			if(printLogLevel.isWithin(ConfigDefs.CompLogLevel.FULL)){
				created.runOnStateDone(() -> {
					String s = "Compiled: " + struct.getFullName() + (prevEncounters>0? " (again)" : "") + "\n" +
					           "\tPipe type: " + BLUE_BRIGHT + created.getClass().getName() + CYAN_BRIGHT + "\n" +
					           "\tSize: " + BLUE_BRIGHT + created.getSizeDescriptor() + CYAN_BRIGHT + "\n" +
					           "\tReference commands: " + created.getReferenceWalkCommands();
					
					var sFields = created.getSpecificFields();
					
					if(!sFields.equals(struct.getFields())){
						s += "\n" + TextUtil.toTable(created.getSpecificFields());
					}
					
					Log.log(CYAN_BRIGHT + s + RESET);
				}, e -> Log.warn("Failed to compile: {}#yellow asynchronously because:\n\t{}#red", created, Utils.errToStackTraceOnDemand(e)));
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
	
	private StructPipe<ProxyBuilder<T>> builderPipe;
	private boolean                     needsBuilderObj;
	
	public static final int STATE_IO_FIELD = 1, STATE_SIZE_DESC = 2, LOCAL_DATA = 3;
	
	public <E extends Exception> StructPipe(Struct<T> type, PipeFieldCompiler<T, E> compiler, boolean initNow) throws E{
		this.type = type;
		init(initNow, () -> {
			needsBuilderObj = this.type.needsBuilderObj();
			
			PipeFieldCompiler.Result<T> res;
			try{
				res = compiler.compile(getType(), getType().getFields());
				this.ioFields = FieldSet.of(res.fields());
			}catch(Exception e){
				throw UtilL.uncheckedThrow(e);
			}
			
			var bt = needsBuilderObj? builderObjectTask(initNow, res.builderMetadata()) : null;
			
			if(DEBUG_VALIDATION) this.checkOrder(ioFields, compiler, getType());
			
			fieldDependency = new FieldDependency<>(ioFields);
			setInitState(STATE_IO_FIELD);
			
			sizeDescription = Objects.requireNonNull(createSizeDescriptor());
			setInitState(STATE_SIZE_DESC);
			
			generators = Utils.nullIfEmpty(IOFieldTools.fieldsToGenerators(ioFields));
			
			referenceWalkCommands = generateReferenceWalkCommands();
			earlyNullChecks = !DEBUG_VALIDATION? null : Utils.nullIfEmpty(
				getNonNulls().filter(f -> generators == null || Iters.from(generators).noneMatch(gen -> gen.field() == f))
				             .toList()
			);
			setInitState(LOCAL_DATA);
			if(bt != null){
				builderPipe = bt.join();
			}
			//Do not post validate now, will create issues with recursive types. It is called in registration
		}, initNow? null : this::postValidate);
	}
	
	boolean needsBuilderObj(){
		waitForState(STATE_IO_FIELD);
		return needsBuilderObj;
	}
	private CompletableFuture<StructPipe<ProxyBuilder<T>>> builderObjectTask(boolean initNow, Object builderMetadata){
		if(initNow){
			var res = createBuilderPipe(builderMetadata);
			return CompletableFuture.completedFuture(res);
		}
		return CompletableFuture.supplyAsync(() -> createBuilderPipe(builderMetadata), t -> Runner.run(t, "BP-" + this.toShortString()));
	}
	protected StructPipe<ProxyBuilder<T>> createBuilderPipe(Object builderMetadata){
		var struct = getType().getBuilderObjType(true);
		
		Class<?> cls = getSelfClass();
		//noinspection unchecked
		var pipe = StructPipe.of((Class<StructPipe<ProxyBuilder<T>>>)cls, struct, STATE_DONE);
		ConfigDefs.CompLogLevel.SMALL.log("Acquired builder pipe for {}#green", this);
		return pipe;
	}
	
	public abstract Class<StructPipe<T>> getSelfClass();
	
	protected static class DoNotTest extends RuntimeException{ }
	
	private void checkOrder(FieldSet<T> ioFields, PipeFieldCompiler<T, ?> compiler, Struct<T> type){
		if(ioFields.size()<=1) return;
		try{
			var ioFCopy = List.copyOf(ioFields);
			var rr      = new RawRandom(ioFields.hashCode());
			var fields  = new ArrayList<>(getType().getFields());
			for(int i = 0; i<20; i++){
				Collections.shuffle(fields, rr);
				var f = compiler.compile(type, FieldSet.of(fields), true).fields();
				if(!ioFCopy.equals(f)){
					throw new IllegalStateException("Fields with different order do not resolve to the same set!");
				}
			}
		}catch(DoNotTest no){
			//ok, sorry
		}catch(Throwable e){
			throw new RuntimeException("Failed to ensure field stability", e);
		}
	}
	
	protected void postValidate(){
		if(TYPE_VALIDATION){
			var type = getType();
			if(type instanceof Struct.Unmanaged) return;
			if(!type.canHaveDefaultConstructor()) return;
			T inst;
			try{
				inst = type.make();
			}catch(Throwable e){
				inst = null;
			}
			if(inst != null){
				try{
					checkTypeIntegrity(inst, true);
				}catch(FieldIsNull|InvalidGenericArgument ignored){
				}catch(IOException e){
					throw new RuntimeException(Log.fmt("Failed to check integrity of: {}#red", type.cleanFullName()), e);
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
			fields = FieldSet.of(Iters.concat(
				getSpecificFields(),
				(FieldSet<T>)unmanaged.getUnmanagedStaticFields()
			));
		}else{
			fields = getSpecificFields();
		}
		
		var refs = fields.instancesOf(RefField.class)
		                 .flatOptionalsPP(ref -> fields.byName(FieldNames.ref(ref.getAccessor())).asKeyWith(ref))
		                 .toModMap(Function.identity());
		
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
			
			if(field instanceof IOFieldInlineSealedObject<?, ?> seal){
				if(Iters.from(seal.getTypePipes()).map(StructPipe::getType).anyMatch(Struct::getCanHavePointers)){
					builder.dynamic();
				}else{
					builder.skipField(field);
				}
				continue;
			}
			if(field instanceof InstanceCollection.InlineField<?, ?, ?> colField){
				builder.dynamic(); //TODO: make this skip field if it is a no pointer sealed collection
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
			
			var accessor = field.getAccessor();
			if(accessor == null){
				builder.skipField(field);
				continue;
			}
			Class<?> type = accessor.getType();
			if(type == Optional.class){
				var genTyp = accessor.getGenericType(null);
				var valTyp = IOFieldTools.unwrapOptionalTypeRequired(genTyp);
				type = Utils.typeToRaw(valTyp);
			}
			
			if(field.typeFlag(IOField.IO_INSTANCE_FLAG)){
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
			
			throw new NotImplementedException(field + " (" + accessor.getGenericType(null).getTypeName() + ") not handled");
		}
		
		if(getType() instanceof Struct.Unmanaged<?> u && u.hasDynamicFields()){
			builder.unmanagedRest();
		}else{
			builder.endFlow();
		}
		
		return builder.build();
	}
	
	
	public CommandSet getReferenceWalkCommands(){
		waitForState(LOCAL_DATA);
		return referenceWalkCommands;
	}
	
	@Override
	protected IterablePP<StateInfo> listStates(){
		return Iters.concat(
			super.listStates(),
			Iters.of(
				new StateInfo(STATE_IO_FIELD, "IO_FIELD"),
				new StateInfo(STATE_SIZE_DESC, "SIZE_DESC"),
				new StateInfo(LOCAL_DATA, "LOCAL_DATA")
			)
		);
	}
	
	private IterablePP<IOField<T, ?>> getNonNulls(){
		return ioFields.unpackedStream().filter(f -> f.isNonNullable() && f.getAccessor().canBeNull());
	}
	
	protected record SizeGroup<T extends IOInstance<T>>(
		SizeDescriptor.UnknownNum<T> num,
		List<IOField<T, ?>> fields
	){
		protected SizeGroup(Map.Entry<SizeDescriptor.UnknownNum<T>, List<IOField<T, ?>>> e){ this(e.getKey(), e.getValue()); }
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
			//noinspection unchecked
			var unmanagedStatic = (FieldSet<T>)u.getUnmanagedStaticFields();
			if(!unmanagedStatic.isEmpty()){
				fields = FieldSet.of(Iters.concat(fields, unmanagedStatic));
			}
		}
		
		type.waitForState(Struct.STATE_INIT_FIELDS);
		var wordSpace = IOFieldTools.minWordSpace(fields);
		
		var hasDynamicFields = type instanceof Struct.Unmanaged<?> u && u.hasDynamicFields();
		
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
		
		var rawGroups = fields.instancesOf(SizeDescriptor.UnknownNum.class, IOField::getSizeDescriptor)
		                      .toGrouping(f -> (SizeDescriptor.UnknownNum<T>)f.getSizeDescriptor());
		var groups = Iters.entries(rawGroups)
		                  .filter(e -> e.getValue().size()>=minGroup)
		                  .toList(SizeGroup::new);
		
		var groupNumberSet = Iters.from(groups).map(e -> e.num).toModSet();
		
		return new SizeRelationReport<>(
			fields, wordSpace, knownFixed, min, max, hasDynamicFields,
			groups,
			fields.filtered(f -> !f.getSizeDescriptor().hasFixed())
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
		var genericUnknown = Utils.nullIfEmpty(report.genericUnknown);
		
		FieldAccessor<T>[] unkownNumAcc;
		int[]              unkownNumAccMul;
		
		if(report.sizeGroups.isEmpty()){
			unkownNumAcc = null;
			unkownNumAccMul = null;
		}else{
			unkownNumAcc = new FieldAccessor[report.sizeGroups.size()];
			unkownNumAccMul = new int[report.sizeGroups.size()];
			int i = 0;
			for(var group : report.sizeGroups){
				unkownNumAcc[i] = group.num.getAccessor();
				unkownNumAccMul[i] = group.fields.size();
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
			
			long unkownSum;
			if(genericUnknown != null){
				unkownSum = IOFieldTools.sumVars(genericUnknown, d -> {
					return d.calcUnknown(ioPool, prov, inst, wordSpace);
				});
			}else unkownSum = 0;
			
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
	
	
	private Map<FieldDependency.Ticket<T>, FieldDependency.Ticket<ProxyBuilder<T>>> proxyDepMapping = Map.of();
	public FieldDependency.Ticket<ProxyBuilder<T>> mapToProxy(FieldDependency.Ticket<T> ticket){
		var cached = proxyDepMapping.get(ticket);
		if(cached != null) return cached;
		
		var names  = ticket.readFields().mapped(IOField::getName);
		var mapped = getBuilderPipe().getFieldDependency().getDeps(names);
		
		var map = new HashMap<>(proxyDepMapping);
		map.put(ticket, mapped);
		proxyDepMapping = Map.copyOf(map);
		
		return mapped;
	}
	
	public final T readNewSelective(DataProvider provider, ContentReader src, Set<String> names, GenericContext genericContext) throws IOException{
		return readNewSelective(provider, src, getFieldDependency().getDeps(names), genericContext, false);
	}
	public final T readNewSelective(DataProvider provider, ContentReader src, FieldDependency.Ticket<T> depTicket, GenericContext genericContext, boolean strictHolder) throws IOException{
		if(needsBuilderObj()){
			var pipe     = getBuilderPipe();
			var mTicket  = mapToProxy(depTicket);
			var builtObj = pipe.readNewSelective(provider, src, mTicket, genericContext, strictHolder);
			if(strictHolder && type.isDefinition()){
				return builderToStrictPartial(mTicket, builtObj);
			}
			return builtObj.build();
		}
		
		T instance;
		if(strictHolder && type.isDefinition()){
			instance = type.partialImplementation(depTicket.readFields()).make();
		}else{
			instance = type.make();
		}
		readDeps(makeIOPool(), provider, src, depTicket, instance, genericContext);
		return instance;
	}
	
	//TODO: implement new partial from partial builder
	private T builderToStrictPartial(FieldDependency.Ticket<ProxyBuilder<T>> mTicket, ProxyBuilder<T> builtObj){
		var fields = mTicket.readFields();
		
		//noinspection rawtypes,unchecked
		var ctor = IOInstance.Def.partialDataConstructor(
			(Class)type.getType(),
			fields.mapped(IOField::getName).toSet(),
			false
		);
		
		var args = fields.mapped(f -> f.get(null, builtObj)).toArray(Object[]::new);
		try{
			var res = ctor.invokeWithArguments(args);
			//noinspection unchecked
			return (T)res;
		}catch(Throwable e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public T readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		if(needsBuilderObj()){
			return readNewByBuilder(provider, src, genericContext);
		}
		T instance = type.make();
		try{
			return doRead(makeIOPool(), provider, src, instance, genericContext);
		}catch(IOException e){
			throw new TypeIOFail("Failed reading", getType().getType(), e);
		}
	}
	private T readNewByBuilder(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var pipe     = getBuilderPipe();
		var builtObj = pipe.readNew(provider, src, genericContext);
		return builtObj.build();
	}
	
	public StructPipe<ProxyBuilder<T>> getBuilderPipe(){
		var pipe = builderPipe;
		if(pipe != null) return pipe;
		waitForStateDone();
		return Objects.requireNonNull(builderPipe);
	}
	
	public final void read(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		try{
			doRead(makeIOPool(), provider, src, instance, genericContext);
		}catch(IOException e){
			throw new TypeIOFail("Failed reading", getType().getType(), e);
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
		waitForState(LOCAL_DATA);
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
		
		if(!DEBUG_VALIDATION){
			generateAll(ioPool, provider, instance, true);
		}
		
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
		generateAll(ioPool, provider, instance, true);
		if(instance instanceof IOInstance.Unmanaged) return null;
		var siz = getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
		
		var sum = 0L;
		for(IOField<T, ?> field : fields){
			var desc  = field.getSizeDescriptor();
			var bytes = desc.calcUnknown(ioPool, provider, instance, WordSpace.BYTE);
			sum += bytes;
		}
		
		if(sum != siz){
			StringJoiner sj = new StringJoiner("\n");
			sj.add("total " + siz);
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
		waitForState(LOCAL_DATA);
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
		
		if(DEBUG_VALIDATION){
			checkGenerated(generators, ioPool, provider, instance, allowExternalMod);
		}
	}
	
	private static <T extends IOInstance<T>> void checkGenerated(List<IOField.ValueGeneratorInfo<T, ?>> generators, VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
		enum Status{
			OK,
			SKIPPED,
			ERR
		}
		var stat = new ArrayList<Status>(generators.size());
		var err  = false;
		for(int i = 0; i<generators.size(); i++){
			var generator = generators.get(i);
			var vg        = generator.generator();
			
			var shouldSkip = switch(vg.strictDetermineLevel()){
				case NOT_REALLY -> true;
				case ON_EXTERNAL_ALWAYS -> !allowExternalMod;
				case ALWAYS -> false;
			};
			if(shouldSkip){
				stat.add(Status.SKIPPED);
				continue;
			}
			var errL = vg.shouldGenerate(ioPool, provider, instance);
			stat.add(errL? Status.ERR : Status.OK);
			err |= errL;
		}
		if(err){
			String info;
			if(stat.size() == 1){
				info = " " + generators.getFirst() + " failed";
			}else{
				var table = TextUtil.toTable(Iters.zip(generators, stat, (gen, st) -> Map.of("generator", gen, "status", st)).asCollection());
				table = table.substring(table.indexOf('\n') + 1, table.lastIndexOf('\n'));
				info = "\n" + table;
			}
			throw new IllegalStateException(
				"Generator(s) have not been satisfied:" + info
			);
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
		if(DEBUG_VALIDATION) validateReadTo(fields);
		if(fields.isEmpty()){
			return;
		}
		var ioFields = this.ioFields;
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
	
	private void validateReadTo(FieldSet<T> fields){
		for(IOField<T, ?> field : fields.flatMapped(IOField::iterUnpackedFields)){
			if(field.isReadOnly()){
				throw new UnsupportedOperationException("Field " + field + " is read-only, can not modify it!");
			}
		}
	}
	
	protected void readField(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext, IOField<T, ?> field) throws IOException{
		if(DEBUG_VALIDATION && field.getSizeDescriptor().hasFixed()){
			readFieldSafe(ioPool, provider, src, instance, genericContext, field);
		}else{
			try{
				field.read(ioPool, provider, src, instance, genericContext);
			}catch(TypeIOFail|EOFException e){
				var typ = field.getAccessor() == null? null : field.getAccessor().getType();
				throw new TypeIOFail("Failed reading " + field + "", typ, e);
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
		var typ = field.getAccessor() == null? null : field.getAccessor().getType();
		return new TypeIOFail(field + " " + msg, typ, e);
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
				if(SupportedPrimitive.get(field.getType()).isPresentAnd(SupportedPrimitive::isInteger)){
					var fieldDeps = field.getAccessor().getAnnotation(IODependency.class).map(IODependency::value).orElse(new String[0]);
					Iters.from(fieldDeps)
					     .map(fields::requireByName)
					     .firstMatching(n -> n.getType() == NumberSize.class)//dependency that is a numsize
					     .filter(f -> {
						     if(f.isVirtual() || f.nullable() || f.isReadOnly()) return false;
						     return f.getAccessor().get(null, inst) == null;
					     })
					     .ifPresent(f -> {
						     var val        = field.get(null, inst);
						     var isUnsigned = field.getAccessor().hasAnnotation(IOValue.Unsigned.class);
						     ((IOField<T, NumberSize>)f).set(null, inst, NumberSize.bySize(((Number)val).longValue(), isUnsigned));
					     });
				}
				
				if(field.getType() == String.class && !field.isReadOnly()){
					field.getAccessor().set(null, inst, field + " : this is a test");
				}
			}
			
			if(inst.getThisStruct().hasInvalidInitialNulls()){
				inst.allocateNulls(man, null);
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
	
	protected static String shortPipeName(Class<?> cls){
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
