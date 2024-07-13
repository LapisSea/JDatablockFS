package com.lapissea.dfs.type;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.internal.Preload;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.CompileStructTools.MakeStruct;
import com.lapissea.dfs.type.compilation.DefInstanceCompiler;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.StoragePool;
import com.lapissea.dfs.type.field.access.VirtualAccessor;
import com.lapissea.dfs.type.field.access.VirtualAccessor.TypeOff.Primitive;
import com.lapissea.dfs.type.field.access.VirtualAccessor.TypeOff.Ptr;
import com.lapissea.dfs.type.field.annotations.IOUnmanagedValueInfo;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lapissea.dfs.type.CompileStructTools.compile;
import static com.lapissea.dfs.type.CompileStructTools.getCached;
import static com.lapissea.dfs.type.field.StoragePool.IO;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NOT_NULL;

/**
 * This is a struct. It is the IO version of a {@link Class}.<br/>
 * It contains all the information about a class that is needed to do any IO operation. It is
 * contains information about the fields (explicit and virtual) and has different useful values
 * that are needed by other classes such as {@link StructPipe}. A struct may be initialized
 * asynchronously. The initialization goes through different stages and values will require one of the
 * state to be completed.
 *
 * @param <T> the type of the containing class
 */
public sealed class Struct<T extends IOInstance<T>> extends StagedInit implements RuntimeType<T>{
	
	static{
		//Preload for faster first start
		Preload.preload(DefInstanceCompiler.class);
		Preload.preload(FieldCompiler.class);
	}
	
	/**
	 * This annotation is not really supposed to be used. It is a workaround for structs that do not have a default constructor and are never
	 * supposed to be created by the struct API. This is used only for the internal {@link Chunk} but it may have its
	 * uses elsewhere
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface NoDefaultConstructor{ }
	
	/**
	 * This is an unmanaged struct. It is like the regular {@link Struct} but it contains extra information
	 * about unmanaged types.<br/>
	 * Unmanaged struct are types that contain custom low level IO logic. Every unmanaged struct needs to
	 * properly state the data it contains trough things such as {@link IOInstance.Unmanaged.DynamicFields#listDynamicUnmanagedFields}
	 * for fields that may appear or change. For fields that are unchanging {@link IOUnmanagedValueInfo} is preferred.
	 *
	 * @param <T> the type of the containing class
	 */
	public static final class Unmanaged<T extends IOInstance.Unmanaged<T>> extends Struct<T>{
		
		/**
		 * This is a convenience overload of {@link Unmanaged#ofUnmanaged(Class)}
		 *
		 * @param instanceType a type whose raw value should implement {@link IOInstance}
		 * @return an instance of a {@link Struct} or {@link Struct.Unmanaged}
		 */
		public static Unmanaged<?> ofUnknown(@NotNull Type instanceType){
			return ofUnknown(Utils.typeToRaw(instanceType));
		}
		
		/**
		 * An unknown generics version of {@link Unmanaged#ofUnmanaged(Class)}
		 *
		 * @param instanceClass a class that should implement {@link IOInstance}
		 * @return an instance of a {@link Struct} or {@link Struct.Unmanaged}
		 */
		@SuppressWarnings({"unchecked", "rawtypes"})
		public static Unmanaged<?> ofUnknown(@NotNull Class<?> instanceClass){
			Objects.requireNonNull(instanceClass);
			
			if(!IOInstance.isUnmanaged(instanceClass)){
				throw new IllegalArgumentException(instanceClass.getName() + " is not an " + IOInstance.Unmanaged.class.getName());
			}
			
			return ofUnmanaged((Class<? extends IOInstance.Unmanaged>)instanceClass);
		}
		
		/**
		 * This is the unmanaged only version of {@link Struct#of(Class)}
		 *
		 * @param instanceClass a non-null instance of a class that implements {@link IOInstance.Unmanaged}
		 * @return an instance of a {@link Struct.Unmanaged}
		 */
		public static <T extends IOInstance.Unmanaged<T>> Unmanaged<T> ofUnmanaged(Class<T> instanceClass){
			Objects.requireNonNull(instanceClass);
			
			var cached = (Unmanaged<T>)getCached(instanceClass);
			if(cached != null) return cached;
			
			if(!IOInstance.isUnmanaged(instanceClass)){
				throw new ClassCastException(instanceClass.getName() + " is not unmanaged!");
			}
			
			return compile(instanceClass, (MakeStruct<T, Unmanaged<T>>)Unmanaged::new, false);
		}
		
		private final NewUnmanaged<T> unmanagedConstructor;
		private final boolean         hasDynamicFields;
		private       FieldSet<T>     unmanagedStaticFields;
		
		private Unmanaged(Class<T> type, boolean runNow){
			super(type, runNow);
			hasDynamicFields = UtilL.instanceOf(getType(), IOInstance.Unmanaged.DynamicFields.class);
			unmanagedConstructor = Access.findConstructor(getType(), NewUnmanaged.class);
		}
		
		public boolean hasDynamicFields(){
			return hasDynamicFields;
		}
		
		public T make(DataProvider provider, Chunk identity, IOType type) throws IOException{
			return unmanagedConstructor.make(provider, identity, type);
		}
		
		@Deprecated
		@Override
		public NewObj.Instance<T> emptyConstructor(){
			throw new UnsupportedOperationException();
		}
		@Override
		@Deprecated
		public T make(){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public String instanceToString(VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return instanceToString0(
				ioPool, instance, doShort, start, end, fieldValueSeparator, fieldSeparator,
				Iters.concat(getFields().filtered(f -> !f.typeFlag(IOField.HAS_GENERATED_NAME)), instance.listUnmanagedFields())
			);
		}
		
		public FieldSet<T> getUnmanagedStaticFields(){
			var usf = unmanagedStaticFields;
			if(usf == null){
				return unmanagedStaticFields = FieldCompiler.compileStaticUnmanaged(this);
			}
			return usf;
		}
	}
	
	
	/**
	 * This is a convenience overload of {@link Struct#of(Class)}
	 *
	 * @param instanceType a type whose raw value should implement {@link IOInstance}
	 * @return an instance of a {@link Struct} or {@link Struct.Unmanaged}
	 */
	public static Struct<?> ofUnknown(@NotNull Type instanceType){
		return ofUnknown(Utils.typeToRaw(instanceType));
	}
	
	/**
	 * This is a convenience overload for unknown generics version of {@link Struct#of(Class, int)}
	 *
	 * @param instanceClass     a class that should implement {@link IOInstance}
	 * @param minRequestedStage see {@link Struct#of(Class)}
	 * @return an instance of a {@link Struct} or {@link Struct.Unmanaged}
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Struct<?> ofUnknown(@NotNull Class<?> instanceClass, int minRequestedStage){
		validateStructType(instanceClass);
		var s = structOfType((Class<? extends IOInstance>)instanceClass, minRequestedStage == STATE_DONE);
		s.waitForState(minRequestedStage);
		return s;
	}
	
	/**
	 * An unknown generics version of {@link Struct#of(Class)}
	 *
	 * @param instanceClass a class that should implement {@link IOInstance}
	 * @return an instance of a {@link Struct} or {@link Struct.Unmanaged}
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Struct<?> ofUnknown(@NotNull Class<?> instanceClass){
		validateStructType(instanceClass);
		return of((Class<? extends IOInstance>)instanceClass);
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Optional<Struct<?>> tryOf(@NotNull Class<?> instanceClass){
		if(!IOInstance.isInstance(instanceClass)){
			return Optional.empty();
		}
		if(!IOInstance.Def.isDefinition(instanceClass) && Modifier.isAbstract(instanceClass.getModifiers())){
			return Optional.empty();
		}
		
		var s = structOfType((Class<? extends IOInstance>)instanceClass, false);
		return Optional.of(s);
	}
	
	private static void validateStructType(Class<?> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		if(!IOInstance.isInstance(instanceClass)){
			throw new IllegalArgumentException(instanceClass.getName() + " is not an " + IOInstance.class.getSimpleName());
		}
	}
	
	
	/**
	 * <p>
	 * Creates a Struct out of a class that has been provided. <br/>
	 * This class has to implement IOInstance (see {@link IOInstance.Managed},
	 * {@link IOInstance.Unmanaged}, {@link IOInstance.Def}).
	 * </p>
	 * The returning {@link Struct} will be initialized up to the requested stage or more. If the
	 * minRequestedStage is {@link StagedInit#STATE_DONE} then asynchronous logic will not be used
	 * and this struct will be fully initialized in this thread.<br/>
	 * It is not recommended to use this method in a static initializer as it is synchronized by the class
	 * loading lock and may cause decreased startup performance or worse, deadlocks.
	 *
	 * @param instanceClass     a non-null instance of a class that implements {@link IOInstance}
	 * @param minRequestedStage any of the {@link StagedInit#STATE_START}, {@link Struct#STATE_CONCRETE_TYPE},
	 *                          {@link Struct#STATE_FIELD_MAKE}, {@link Struct#STATE_INIT_FIELDS}, {@link StagedInit#STATE_DONE}
	 * @return an instance of a {@link Struct} or {@link Struct.Unmanaged}
	 */
	public static <T extends IOInstance<T>> Struct<T> of(Class<T> instanceClass, int minRequestedStage){
		try{
			var s = structOfType(instanceClass, minRequestedStage == STATE_DONE);
			s.waitForState(minRequestedStage);
			return s;
		}catch(Throwable e){
			throw Utils.interceptClInit(e);
		}
	}
	
	/**
	 * <p>
	 * Creates a Struct out of a class that has been provided. <br/>
	 * This class has to implement IOInstance (see {@link IOInstance.Managed},
	 * {@link IOInstance.Unmanaged}, {@link IOInstance.Def}).
	 * </p>
	 * <br/>
	 * <p>
	 * The struct is always reused and will not need to initialize on subsequent of calls.<br/>
	 * The struct may be initialized asynchronously to minimize the time in this
	 * function. If the struct is used while it is still initializing it may cause
	 * a blocking operation. This greatly improves the time needed to initialize
	 * everything but could cause odd performance on first run.<br/>
	 * </p>
	 *
	 * @param instanceClass a non-null instance of a class that implements {@link IOInstance}
	 * @return an instance of a {@link Struct} or {@link Struct.Unmanaged}
	 */
	public static <T extends IOInstance<T>> Struct<T> of(@NotNull Class<T> instanceClass){
		try{
			return structOfType(instanceClass, false);
		}catch(Throwable e){
			throw Utils.interceptClInit(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct<T> structOfType(Class<T> instanceClass, boolean runNow){
		Objects.requireNonNull(instanceClass);
		
		var cached = getCached(instanceClass);
		if(cached != null) return cached;
		
		validateStructType(instanceClass);
		
		if(IOInstance.isUnmanaged(instanceClass)){
			return (Struct<T>)Unmanaged.ofUnknown(instanceClass);
		}
		
		return compile(instanceClass, (MakeStruct<T, Struct<T>>)Struct::new, runNow);
	}
	
	public record FieldStruct<T extends IOInstance<T>>
		(IOField<T, ?> field, Struct<?> struct){
		public FieldStruct{
			if(field.getType() != struct.getType()){
				throw new IllegalArgumentException(field.getType().getName() + " != " + struct.getType().getName());
			}
		}
	}
	
	private final Class<T> type;
	private       Class<T> concreteType;
	private       boolean  isDefinition;
	
	private FieldSet<T> fields;
	private FieldSet<T> realFields;
	private FieldSet<T> cloneFields;
	
	private List<FieldStruct<T>> nullContainInstances;
	
	short[] poolObjectsSize;
	short[] poolPrimitivesSize;
	private boolean[] hasPools;
	
	private boolean canHavePointers;
	private byte    invalidInitialNulls = -1;
	
	private NewObj.Instance<T> emptyConstructor;
	
	private Map<FieldSet<T>, Struct<T>> partialCache;
	
	private int hash = -1;
	
	public static final int STATE_CONCRETE_TYPE = 1, STATE_FIELD_MAKE = 2, STATE_INIT_FIELDS = 3;
	
	private Struct(Class<T> type, boolean runNow){
		this.type = Objects.requireNonNull(type);
		init(runNow, () -> {
			calcHash();
			
			isDefinition = UtilL.instanceOf(type, IOInstance.Def.class);
			if(IOInstance.Def.isDefinition(type)){
				concreteType = DefInstanceCompiler.getImpl(type);
			}else concreteType = type;
			
			setInitState(STATE_CONCRETE_TYPE);
			
			fields = FieldCompiler.compile(this);
			setInitState(STATE_FIELD_MAKE);
			for(var field : fields){
				try{
					field.init(fields);
					Objects.requireNonNull(field.getSizeDescriptor(), "Descriptor was not inited");
				}catch(Throwable e){
					throw new RuntimeException("Failed to init " + field, e);
				}
			}
			setInitState(STATE_INIT_FIELDS);
			poolObjectsSize = calcPoolObjectsSize();
			poolPrimitivesSize = calcPoolPrimitivesSize();
			hasPools = calcHasPools();
			canHavePointers = calcCanHavePointers();
			
			Thread.startVirtualThread(() -> {
				if(emptyConstructor != null || !canHaveDefaultConstructor()) return;
				findEmptyConstructor();
			});
		});
	}
	
	
	@Override
	protected IterablePP<StateInfo> listStates(){
		return Iters.concat(
			super.listStates(),
			Iters.of(
				new StateInfo(STATE_CONCRETE_TYPE, "CONCRETE_TYPE"),
				new StateInfo(STATE_FIELD_MAKE, "FIELD_MAKE"),
				new StateInfo(STATE_INIT_FIELDS, "INIT_FIELDS")
			)
		);
	}
	
	private IterablePP<VirtualAccessor<T>> virtualAccessorStream(){
		return getFields().flatOptionals(t -> t.getVirtual(null));
	}
	
	private short[] calcPoolObjectsSize(){
		var vPools = virtualAccessorStream().filter(a -> a.typeOff instanceof Ptr)
		                                    .mapToInt(a -> a.getStoragePool().ordinal()).toArray();
		if(vPools.length == 0) return null;
		var poolPointerSizes = new short[StoragePool.values().length];
		for(var vPoolIndex : vPools){
			if(poolPointerSizes[vPoolIndex] == Short.MAX_VALUE)
				throw new OutOfMemoryError("Too many fields that need " + StoragePool.values()[vPoolIndex] + " pool");
			poolPointerSizes[vPoolIndex]++;
		}
		return poolPointerSizes;
	}
	
	private short[] calcPoolPrimitivesSize(){
		var vPools = virtualAccessorStream().filter(a -> a.typeOff instanceof Primitive)
		                                    .collect(Collectors.groupingBy(VirtualAccessor::getStoragePool));
		if(vPools.isEmpty()) return null;
		var poolSizes = new short[StoragePool.values().length];
		for(Map.Entry<StoragePool, List<VirtualAccessor<T>>> e : vPools.entrySet()){
			var siz = Iters.from(e.getValue())
			               .mapToInt(a -> ((Primitive)a.typeOff).size)
			               .sum();
			if(siz>Short.MAX_VALUE) throw new OutOfMemoryError();
			poolSizes[e.getKey().ordinal()] = (short)siz;
		}
		return poolSizes;
	}
	
	private boolean[] calcHasPools(){
		if(poolObjectsSize == null && poolPrimitivesSize == null){
			return null;
		}
		var b = new boolean[poolObjectsSize == null? poolPrimitivesSize.length : poolObjectsSize.length];
		for(int i = 0; i<b.length; i++){
			boolean p = poolObjectsSize != null && poolObjectsSize[i]>0;
			boolean n = poolPrimitivesSize != null && poolPrimitivesSize[i]>0;
			
			b[i] = p || n;
		}
		
		return b;
	}
	
	private boolean calcCanHavePointers(){
		IterablePP<IOField<T, ?>> fields;
		if(this instanceof Struct.Unmanaged<?> u){
			if(u.hasDynamicFields()) return true;
			//noinspection unchecked
			fields = Iters.concat(getFields(), (FieldSet<T>)u.getUnmanagedStaticFields());
		}else{
			fields = getFields().iter();
		}
		return fields.anyMatch(f -> {
			var acc = f.getAccessor();
			if(acc == null) return true;
			
			if(IOFieldTools.isGeneric(acc)) return true;
			if(f instanceof RefField) return true;
			
			if(acc.getType() == ChunkPointer.class) return true;
			if(acc.getType() == Reference.class) return true;
			
			if(IOInstance.isInstance(acc.getType())){
				if(!IOInstance.isManaged(acc.getType())) return true;
				var uni = SealedUtil.getSealedUniverse(acc.getType(), false)
				                    .filter(IOInstance::isInstance)
				                    .filter(u -> u.universe().contains(this.getType()));
				if(uni.isPresent()){
					for(var typ : uni.get().universe()){
						if(typ == this.getType()) continue;
						if(Struct.canUnknownHavePointers(typ)){
							return true;
						}
					}
					return false;
				}
				
				return Struct.canUnknownHavePointers(acc.getType());
			}
			return false;
		});
	}
	
	public String cleanName()    { return stripDef(getType().getSimpleName()); }
	public String cleanFullName(){ return stripDef(getFullName()); }
	private String stripDef(String name){
		if(UtilL.instanceOf(getType(), IOInstance.Def.class)){
			var index = name.indexOf(IOInstance.Def.IMPL_COMPLETION_POSTFIX);
			index = Math.min(index, name.indexOf(IOInstance.Def.IMPL_NAME_POSTFIX, index == -1? 0 : index));
			if(index != -1){
				name = name.substring(0, index);
			}
		}
		return name;
	}
	
	@Override
	public String toString(){
		var name = cleanName();
		var sj   = new StringJoiner(", ", name + "{", "}");
		if(this instanceof Struct.Unmanaged){
			sj.add("Unmanaged");
		}
		var e = getErr();
		if(e != null){
			var msg = e.getLocalizedMessage();
			sj.add("INVALID: " + (msg == null? e.getClass().getSimpleName() : msg));
		}
		var state = getEstimatedState();
		if(state != STATE_DONE){
			if(e == null) sj.add("init-state: " + stateToString(state));
		}
		if(state>=STATE_FIELD_MAKE){
			var fields  = getFields();
			var dynamic = this instanceof Struct.Unmanaged<?> u && u.hasDynamicFields();
			sj.add((dynamic? "+" : "") + fields.size() + " " + TextUtil.plural("field", fields.size() + (dynamic? 10 : 0)));
		}
		
		return sj.toString();
	}
	
	@Override
	public Class<T> getType(){
		return type;
	}
	public Class<T> getConcreteType(){
		if(concreteType == null) resolveConcrete();
		return concreteType;
	}
	
	private void resolveConcrete(){
		isDefinition = UtilL.instanceOf(type, IOInstance.Def.class);
		if(IOInstance.Def.isDefinition(type)){
			waitForState(STATE_CONCRETE_TYPE);
		}else{
			concreteType = type;
		}
	}
	
	public String getFullName(){
		return type.getName();
	}
	
	public FieldSet<T> getRealFields(){
		var f = realFields;
		return f == null? realFields = calcRealFields() : f;
	}
	private FieldSet<T> calcRealFields(){
		var fields = getFields();
		var res    = FieldSet.of(fields.filtered(e -> !e.isVirtual(IO)));
		if(res.size() == fields.size()) return fields;
		return res;
	}
	
	public FieldSet<T> getCloneFields(){
		var f = cloneFields;
		return f == null? cloneFields = calcCloneFields() : f;
	}
	private FieldSet<T> calcCloneFields(){
		return FieldSet.of(getFields().filtered(f -> {
			if(f.typeFlag(IOField.PRIMITIVE_OR_ENUM_FLAG) || f.isVirtual(IO)) return false;
			var acc = f.getAccessor();
			if(acc != null){
				var typ = acc.getType();
				return !FieldCompiler.getWrapperTypes().contains(typ);
			}
			return true;
		}));
	}
	
	public List<FieldStruct<T>> getNullContainInstances(){
		var f = nullContainInstances;
		return f == null? nullContainInstances = calcNullContainInstances() : f;
	}
	private List<FieldStruct<T>> calcNullContainInstances(){
		return getRealFields()
			       .flatOptionals(f -> Struct.tryOf(f.getType())
			                                 .filter(struct -> !struct.getRealFields().onlyRefs().isEmpty())
			                                 .map(s -> new FieldStruct<>(f, s)))
			       .toList();
	}
	
	public FieldSet<T> getFields(){
		waitForState(STATE_FIELD_MAKE);
		return Objects.requireNonNull(fields);
	}
	
	public static boolean canUnknownHavePointers(@NotNull Class<?> instanceClass){
		if(SealedUtil.isSealedCached(instanceClass)){
			var uni = SealedUtil.getSealedUniverse(instanceClass, false);
			return uni.flatMap(SealedUtil.SealedInstanceUniverse::ofUnknown)
			          .map(SealedUtil.SealedInstanceUniverse::calcCanHavePointers)
			          .orElse(true);
		}
		var s = ofUnknown(instanceClass, STATE_DONE);
		return s.canHavePointers;
	}
	
	@Override
	public boolean getCanHavePointers(){
		waitForStateDone();
		return canHavePointers;
	}
	
	public String instanceToString(T instance, boolean doShort){
		return instanceToString(allocVirtualVarPool(IO), instance, doShort);
	}
	public String instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
		return instanceToString(ioPool, instance, doShort, "{", "}", "=", ", ");
	}
	public String instanceToString(T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		return instanceToString(allocVirtualVarPool(IO), instance, doShort, start, end, fieldValueSeparator, fieldSeparator);
	}
	public String instanceToString(VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		return instanceToString0(
			ioPool, instance, doShort, start, end, fieldValueSeparator, fieldSeparator,
			fields.filtered(f -> !f.typeFlag(IOField.HAS_GENERATED_NAME))
		);
	}
	
	protected String instanceToString0(VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator, IterablePP<IOField<T, ?>> fields){
		var    prefix = start;
		String name   = null;
		if(!doShort){
			name = cleanName();
			if(Modifier.isStatic(getType().getModifiers())){
				var index = name.lastIndexOf('$');
				if(index != -1) name = name.substring(index + 1);
			}
			
			prefix = name + prefix;
		}
		
		Function<IOField<T, ?>, Optional<String>> fieldMapper = field -> {
			if(SupportedPrimitive.get(field.getType()).orElse(null) == SupportedPrimitive.BOOLEAN){
				Boolean val = (Boolean)field.get(ioPool, instance);
				if(val != null){
					return val? Optional.of(field.getName()) : Optional.empty();
				}
			}
			
			Optional<String> valStr;
			try{
				if(field.getNullability() == NOT_NULL && field.isNull(ioPool, instance)){
					valStr = Optional.of("<UNINITIALIZED>");
				}else{
					valStr = field.instanceToString(ioPool, instance, doShort || TextUtil.USE_SHORT_IN_COLLECTIONS, start, end, fieldValueSeparator, fieldSeparator);
				}
			}catch(Throwable e){
				valStr = Optional.of("CORRUPTED: " + e.getMessage());
			}
			
			return valStr.map(value -> field.getName() + fieldValueSeparator + value);
		};
		var str = fields.flatOptionals(fieldMapper).joinAsStr(fieldSeparator, prefix, end);
		
		if(!doShort){
			if(str.equals(prefix + end)) return name;
		}
		return str;
	}
	
	@Override
	public NewObj.Instance<T> emptyConstructor(){
		var ctor = emptyConstructor;
		if(ctor == null){
			return findEmptyConstructor();
		}
		return ctor;
	}
	
	private NewObj.Instance<T> findEmptyConstructor(){
		if(getType().isAnnotationPresent(NoDefaultConstructor.class)){
			throw new UnsupportedOperationException("NoDefaultConstructor is present");
		}
		if(this instanceof Struct.Unmanaged){
			throw new UnsupportedOperationException("Unmanaged instances can not have default constructor");
		}
		NewObj.Instance<T> ctor = Access.findConstructor(getConcreteType(), NewObj.Instance.class);
		Objects.requireNonNull(ctor);
		return emptyConstructor = ctor;
	}
	public boolean canHaveDefaultConstructor(){
		return !(this instanceof Struct.Unmanaged) &&
		       !getType().isAnnotationPresent(NoDefaultConstructor.class);
	}
	
	public boolean hasInvalidInitialNulls(){
		var iin = invalidInitialNulls;
		if(iin == -1) iin = calcHasInvalidInitialNulls();
		return iin == 1;
	}
	private byte calcHasInvalidInitialNulls(){
		if(this instanceof Unmanaged){
			return invalidInitialNulls = 0;
		}
		
		waitForStateDone();
		
		boolean inv = false;
		if(fields.unpackedStream().anyMatch(f -> f.getNullability() == NOT_NULL)){
			var obj  = make();
			var pool = allocVirtualVarPool(IO);
			inv = fields.unpackedStream()
			            .filter(f -> f.getNullability() == NOT_NULL)
			            .anyMatch(f -> f.isNull(pool, obj));
		}
		return invalidInitialNulls = (byte)(inv? 1 : 0);
	}
	
	@Nullable
	public final VarPool<T> allocVirtualVarPool(StoragePool pool){
		if(needsPool(pool)){
			return new VarPool.GeneralVarArray<>(this, pool);
		}
		return null;
	}
	
	public final boolean needsPool(StoragePool pool){
		waitForStateDone();
		return hasPools != null && hasPools[pool.ordinal()];
	}
	
	public GenericContext describeGenerics(IOType def, IOTypeDB db){
		return GenericContext.of(getType(), def.generic(db));
	}
	
	@Override
	public boolean equals(Object o){
		return this == o ||
		       o instanceof Struct<?> that &&
		       equals(that);
	}
	
	public boolean equals(Struct<?> o){
		return this == o ||
		       o != null &&
		       type.equals(o.type);
	}
	
	@Override
	public int hashCode(){
		if(hash == -1) calcHash();
		return hash;
	}
	private void calcHash(){
		var h = getFullName().hashCode();
		hash = h == -1? 0 : h;
	}
	
	public boolean isDefinition(){
		waitForState(STATE_CONCRETE_TYPE);
		return isDefinition;
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Struct<T> partialImplementation(FieldSet<T> fields){
		synchronized(this){
			if(partialCache == null) partialCache = Collections.synchronizedMap(new HashMap<>());
		}
		
		//synchronized get put pattern because makeimpl is thread safe
		var cached = partialCache.get(fields);
		if(cached != null) return cached;
		
		var impl = makeImpl((FieldSet)fields);
		partialCache.put(fields, impl);
		
		return impl;
	}
	
	@SuppressWarnings({"unchecked"})
	private <E extends IOInstance.Def<E>> Struct<E> makeImpl(FieldSet<E> f){
		if(!isDefinition()){
			throw new UnsupportedOperationException();
		}
		var typ = (Class<E>)getType();
		if(!typ.isInterface()){
			typ = (Class<E>)IOInstance.Def.unmap((Class<E>)type).orElseThrow();
		}
		var names = Iters.from(f).toSet(IOField::getName);
		var impl  = IOInstance.Def.partialImplementation(typ, names);
		return Struct.of(impl);
	}
}
