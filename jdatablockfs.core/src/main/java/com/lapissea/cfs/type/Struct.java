package com.lapissea.cfs.type;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.exceptions.RecursiveStructCompilation;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.compilation.DefInstanceCompiler;
import com.lapissea.cfs.type.compilation.FieldCompiler;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.StoragePool;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.cfs.type.field.annotations.IODynamic;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.util.*;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.PRINT_COMPILATION;
import static com.lapissea.cfs.Utils.getCallee;
import static com.lapissea.cfs.type.field.StoragePool.IO;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NOT_NULL;
import static com.lapissea.util.ConsoleColors.GREEN_BRIGHT;

public sealed class Struct<T extends IOInstance<T>> extends StagedInit implements RuntimeType<T>{
	
	static{
		//Preload for faster first start
		Runner.run(DefInstanceCompiler::init);
	}
	
	private static final Log.Channel COMPILATION=Log.channel(PRINT_COMPILATION&&!Access.DEV_CACHE, Log.Channel.colored(GREEN_BRIGHT));
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface NoDefaultConstructor{}
	
	public static final class Unmanaged<T extends IOInstance.Unmanaged<T>> extends Struct<T>{
		
		public static Unmanaged<?> thisClass(){
			return ofUnknown(getCallee(1));
		}
		
		public static Unmanaged<?> ofUnknown(@NotNull Type instanceType){
			return ofUnknown(Utils.typeToRaw(instanceType));
		}
		
		@SuppressWarnings({"unchecked", "rawtypes"})
		public static Unmanaged<?> ofUnknown(@NotNull Class<?> instanceClass){
			Objects.requireNonNull(instanceClass);
			
			if(!IOInstance.isUnmanaged(instanceClass)){
				throw new IllegalArgumentException(instanceClass.getName()+" is not an "+IOInstance.Unmanaged.class.getName());
			}
			
			return ofUnmanaged((Class<? extends IOInstance.Unmanaged>)instanceClass);
		}
		
		public static <T extends IOInstance.Unmanaged<T>> Unmanaged<T> ofUnmanaged(Class<T> instanceClass){
			Objects.requireNonNull(instanceClass);
			
			Unmanaged<T> cached=(Unmanaged<T>)getCached(instanceClass);
			if(cached!=null) return cached;
			
			
			return compile(instanceClass, Unmanaged::new, false);
		}
		
		private final NewUnmanaged<T> unmanagedConstructor;
		private final boolean         overridingDynamicUnmanaged;
		private       FieldSet<T>     unmanagedStaticFields;
		
		private Unmanaged(Class<T> type){
			super(type, false);
			overridingDynamicUnmanaged=checkOverridingUnmanaged();
			unmanagedConstructor=Access.findConstructor(getType(), NewUnmanaged.class, Access.getFunctionalMethod(NewUnmanaged.class).getParameterTypes());
		}
		
		private boolean checkOverridingUnmanaged(){
			Class<?> t=getType();
			while(true){
				try{
					return !t.getDeclaredMethod("listDynamicUnmanagedFields").getDeclaringClass().equals(IOInstance.Unmanaged.class);
				}catch(NoSuchMethodException e){
					t=t.getSuperclass();
				}
			}
		}
		
		public boolean isOverridingDynamicUnmanaged(){
			return overridingDynamicUnmanaged;
		}
		
		public T make(DataProvider provider, Reference reference, TypeLink type) throws IOException{
			return unmanagedConstructor.make(provider, reference, type);
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
				Stream.concat(getFields().stream().filter(f->!f.typeFlag(IOField.HAS_GENERATED_NAME)), instance.listUnmanagedFields())
			);
		}
		
		public FieldSet<T> getUnmanagedStaticFields(){
			if(unmanagedStaticFields==null){
				unmanagedStaticFields=FieldCompiler.compileStaticUnmanaged(this);
			}
			
			return unmanagedStaticFields;
		}
	}
	
	private static class WaitHolder{
		private boolean wait=true;
	}
	
	private static final ReadWriteLock STRUCT_CACHE_LOCK=new ReentrantReadWriteLock();
	
	private static final Map<Class<?>, Struct<?>>  STRUCT_CACHE     =new WeakValueHashMap<>();
	private static final Map<Class<?>, WaitHolder> NON_CONCRETE_WAIT=new HashMap<>();
	private static final Map<Class<?>, Thread>     STRUCT_THREAD_LOG=new HashMap<>();
	
	public static void clear(){
		if(!Access.DEV_CACHE) throw new RuntimeException();
		var lock=STRUCT_CACHE_LOCK.writeLock();
		lock.lock();
		try{
			if(!STRUCT_THREAD_LOG.isEmpty()) throw new RuntimeException();
			STRUCT_CACHE.clear();
			StructPipe.clear();
		}finally{
			lock.unlock();
		}
	}
	
	public static Struct<?> thisClass(){
		return ofUnknown(getCallee(1));
	}
	
	public static Struct<?> ofUnknown(@NotNull Type instanceType){
		return ofUnknown(Utils.typeToRaw(instanceType));
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Struct<?> ofUnknown(@NotNull Class<?> instanceClass, int minRequestedStage){
		validateStructType(instanceClass);
		var s=of0((Class<? extends IOInstance>)instanceClass, minRequestedStage==STATE_DONE);
		s.waitForState(minRequestedStage);
		return s;
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Struct<?> ofUnknown(@NotNull Class<?> instanceClass){
		validateStructType(instanceClass);
		return of((Class<? extends IOInstance>)instanceClass);
	}
	
	private static void validateStructType(Class<?> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		if(!IOInstance.isInstance(instanceClass)){
			throw new IllegalArgumentException(instanceClass.getName()+" is not an "+IOInstance.class.getSimpleName());
		}
	}
	
	public static <T extends IOInstance<T>> Struct<T> of(Class<T> instanceClass, int minRequestedStage){
		try{
			var s=of0(instanceClass, minRequestedStage==STATE_DONE);
			s.waitForState(minRequestedStage);
			return s;
		}catch(Throwable e){
			throw Utils.interceptClInit(e);
		}
	}
	
	public static <T extends IOInstance<T>> Struct<T> of(Class<T> instanceClass){
		try{
			return of0(instanceClass, false);
		}catch(Throwable e){
			throw Utils.interceptClInit(e);
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct<T> of0(Class<T> instanceClass, boolean runNow){
		Objects.requireNonNull(instanceClass);
		
		Struct<T> cached=getCached(instanceClass);
		if(cached!=null) return cached;
		
		if(IOInstance.isUnmanaged(instanceClass)){
			return (Struct<T>)Unmanaged.ofUnknown(instanceClass);
		}
		return compile(instanceClass, t->{
			if(!IOInstance.isInstance(t)) throw new ClassCastException(t.getName()+" is not an "+IOInstance.class.getSimpleName());
			if(IOInstance.isUnmanaged(t)) throw new ClassCastException(t.getName()+" is unmanaged!");
			return new Struct<>(t, runNow);
		}, runNow);
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>, S extends Struct<T>> S compile(Class<T> instanceClass, Function<Class<T>, S> newStruct, boolean runNow){
		boolean needsImpl=IOInstance.Def.isDefinition(instanceClass);
		
		if(!needsImpl&&Modifier.isAbstract(instanceClass.getModifiers())){
			throw new IllegalArgumentException("Can not compile "+instanceClass.getName()+" because it is abstract");
		}
		
		S struct;
		
		var lock=STRUCT_CACHE_LOCK.writeLock();
		lock.lock();
		try{
			recursiveCompileCheck(instanceClass);
			
			//If class was compiled in another thread this should early exit
			var existing=getCachedUnsafe(instanceClass, lock);
			if(existing!=null) return (S)existing;
			
			Log.trace("Requested struct: {}#green{}#greenBright",
			          ()->List.of(
				          instanceClass.getName().substring(0, instanceClass.getName().length()-instanceClass.getSimpleName().length()),
				          instanceClass.getSimpleName()
			          ));
			
			try{
				STRUCT_THREAD_LOG.put(instanceClass, Thread.currentThread());
				if(needsImpl) NON_CONCRETE_WAIT.put(instanceClass, new WaitHolder());
				
				struct=newStruct.apply(instanceClass);
				
				STRUCT_CACHE.put(instanceClass, struct);
			}catch(Throwable e){
				throw new MalformedStruct("Failed to compile "+instanceClass.getName(), e);
			}finally{
				STRUCT_THREAD_LOG.remove(instanceClass);
			}
		}finally{
			lock.unlock();
		}
		
		if(needsImpl) struct.runOnState(STATE_CONCRETE_TYPE, ()->{
			try{
				var impl=struct.getConcreteType();
				var wl  =STRUCT_CACHE_LOCK.writeLock();
				wl.lock();
				try{
					STRUCT_CACHE.put(impl, struct);
					NON_CONCRETE_WAIT.remove(instanceClass).wait=false;
				}finally{
					wl.unlock();
				}
			}catch(Throwable ignored){}
		}, null);
		
		
		if(!GlobalConfig.RELEASE_MODE&&Log.WARN){
			if(!COMPILATION.isEnabled()){
				struct.runOnStateDone(
					()->Log.trace("Struct compiled: {}#cyan{}#cyanBright", struct.getFullName().substring(0, struct.getFullName().length()-struct.cleanName().length()), struct),
					e->{
						var e1=StagedInit.WaitException.unwait(e);
						Log.warn("Failed to compile struct asynchronously: {}#red because - {}: {}", struct.cleanFullName(), e1.getClass().getSimpleName(), e1.getMessage());
					}
				);
			}else{
				struct.runOnStateDone(
					()->COMPILATION.log(TextUtil.toTable(struct.cleanFullName(), struct.getFields())),
					e->Log.warn("Failed to compile struct asynchronously: {}#red because {}", struct.cleanName(), e)
				);
			}
		}
		
		return struct;
	}
	
	private static <T extends IOInstance<T>> Struct<T> getCached(Class<T> instanceClass){
		var lock=STRUCT_CACHE_LOCK.readLock();
		try{
			lock.lock();
			return getCachedUnsafe(instanceClass, lock);
		}finally{
			lock.unlock();
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct<T> getCachedUnsafe(Class<T> instanceClass, Lock lock){
		waitNonConcrete(instanceClass, lock);
		return (Struct<T>)STRUCT_CACHE.get(instanceClass);
	}
	
	private static <T extends IOInstance<T>> void waitNonConcrete(Class<T> instanceClass, Lock lock){
		if(instanceClass.isInterface()||!UtilL.instanceOf(instanceClass, IOInstance.Def.class)){
			return;
		}
		
		var queue=new ArrayDeque<Class<?>>();
		queue.push(instanceClass);
		while(!queue.isEmpty()){
			var cl=queue.pop();
			for(var interf : cl.getInterfaces()){
				var holder=NON_CONCRETE_WAIT.get(interf);
				if(holder!=null){
					if(holder.wait){
						recursiveCompileCheck(interf);
					}
					
					while(holder.wait){
						lock.unlock();
						UtilL.sleep(1);
						lock.lock();
					}
				}
				queue.push(interf);
			}
			var sup=cl.getSuperclass();
			if(sup!=null&&UtilL.instanceOf(sup, IOInstance.Def.class)) queue.push(sup);
		}
	}
	
	private static void recursiveCompileCheck(Class<?> interf){
		Thread thread=STRUCT_THREAD_LOG.get(interf);
		if(thread!=null&&thread==Thread.currentThread()){
			throw new RecursiveStructCompilation("Recursive struct compilation");
		}
	}
	
	private final Class<T> type;
	private       Class<T> concreteType;
	private       boolean  isDefinition;
	
	private FieldSet<T> fields;
	private FieldSet<T> instanceFields;
	
	short[] poolObjectsSize;
	short[] poolPrimitivesSize;
	private boolean[] hasPools;
	
	private boolean canHavePointers;
	private byte    invalidInitialNulls=-1;
	
	private NewObj.Instance<T> emptyConstructor;
	
	private int hash=-1;
	
	public static final int STATE_CONCRETE_TYPE=1, STATE_FIELD_MAKE=2, STATE_INIT_FIELDS=3;
	
	private Struct(Class<T> type, boolean runNow){
		this.type=Objects.requireNonNull(type);
		init(runNow, ()->{
			calcHash();
			
			isDefinition=UtilL.instanceOf(type, IOInstance.Def.class);
			if(IOInstance.Def.isDefinition(type)){
				concreteType=DefInstanceCompiler.getImpl(type, runNow);
			}else concreteType=type;
			
			setInitState(STATE_CONCRETE_TYPE);
			
			this.fields=FieldCompiler.compile(this);
			setInitState(STATE_FIELD_MAKE);
			for(IOField<T, ?> field : this.fields){
				try{
					field.init();
				}catch(Throwable e){
					throw new RuntimeException("Failed to init "+field, e);
				}
			}
			setInitState(STATE_INIT_FIELDS);
			poolObjectsSize=calcPoolObjectsSize();
			poolPrimitivesSize=calcPoolPrimitivesSize();
			hasPools=calcHasPools();
			canHavePointers=calcCanHavePointers();
			
			if(emptyConstructor==null&&!getType().isAnnotationPresent(NoDefaultConstructor.class)){
				findEmptyConstructor();
			}
		});
	}
	
	@Override
	protected Stream<StateInfo> listStates(){
		return Stream.concat(
			super.listStates(),
			Stream.of(
				new StateInfo(STATE_CONCRETE_TYPE, "CONCRETE_TYPE"),
				new StateInfo(STATE_FIELD_MAKE, "FIELD_MAKE"),
				new StateInfo(STATE_INIT_FIELDS, "INIT_FIELDS")
			)
		);
	}
	
	private Stream<VirtualAccessor<T>> virtualAccessorStream(){
		return getFields().stream().map(t->Utils.getVirtual(t, null)).filter(Objects::nonNull);
	}
	
	private short[] calcPoolObjectsSize(){
		var vPools=virtualAccessorStream().map(VirtualAccessor::getStoragePool).toList();
		if(vPools.isEmpty()) return null;
		var poolPointerSizes=new short[StoragePool.values().length];
		for(var vPool : vPools){
			if(poolPointerSizes[vPool.ordinal()]==Short.MAX_VALUE) throw new OutOfMemoryError();
			poolPointerSizes[vPool.ordinal()]++;
		}
		return poolPointerSizes;
	}
	
	private short[] calcPoolPrimitivesSize(){
		var vPools=virtualAccessorStream().filter(a->a.getPrimitiveSize()>0).collect(Collectors.groupingBy(VirtualAccessor::getStoragePool));
		if(vPools.isEmpty()) return null;
		var poolSizes=new short[StoragePool.values().length];
		for(var e : vPools.entrySet()){
			var siz=e.getValue()
			         .stream()
			         .mapToInt(VirtualAccessor::getPrimitiveSize)
			         .sum();
			if(siz>Short.MAX_VALUE) throw new OutOfMemoryError();
			poolSizes[e.getKey().ordinal()]=(short)siz;
		}
		return poolSizes;
	}
	
	private boolean[] calcHasPools(){
		if(poolObjectsSize==null&&poolPrimitivesSize==null){
			return null;
		}
		var b=new boolean[poolObjectsSize==null?poolPrimitivesSize.length:poolObjectsSize.length];
		for(int i=0;i<b.length;i++){
			boolean p=poolObjectsSize!=null&&poolObjectsSize[i]>0;
			boolean n=poolPrimitivesSize!=null&&poolPrimitivesSize[i]>0;
			
			b[i]=p||n;
		}
		
		return b;
	}
	
	private boolean calcCanHavePointers(){
		if(this instanceof Struct.Unmanaged) return true;
		return getFields().stream().anyMatch(f->{
			var acc=f.getAccessor();
			if(acc==null) return true;
			
			if(acc.hasAnnotation(IODynamic.class)) return true;
			if(f instanceof RefField) return true;
			
			if(acc.getType()==ChunkPointer.class) return true;
			if(acc.getType()==Reference.class) return true;
			
			if(IOInstance.isInstance(acc.getType())){
				if(!IOInstance.isManaged(acc.getType())) return true;
				return Struct.canUnknownHavePointers(acc.getType());
			}
			return false;
		});
	}
	
	public String cleanName()    {return stripDef(getType().getSimpleName());}
	public String cleanFullName(){return stripDef(getFullName());}
	private String stripDef(String name){
		if(UtilL.instanceOf(getType(), IOInstance.Def.class)){
			var index=name.indexOf(IOInstance.Def.IMPL_COMPLETION_POSTFIX);
			index=Math.min(index, name.indexOf(IOInstance.Def.IMPL_NAME_POSTFIX, index==-1?0:index));
			if(index!=-1){
				name=name.substring(0, index);
			}
		}
		return name;
	}
	
	@Override
	public String toString(){
		var name=cleanName();
		var sj  =new StringJoiner(", ", name+"{", "}");
		if(this instanceof Struct.Unmanaged){
			sj.add("Unmanaged");
		}
		var e=getErr();
		if(e!=null){
			var msg=e.getLocalizedMessage();
			sj.add("INVALID: "+(msg==null?e.getClass().getSimpleName():msg));
		}
		var state=getEstimatedState();
		if(state!=STATE_DONE){
			if(e==null) sj.add("init-state: "+stateToString(state));
		}
		if(state>=STATE_FIELD_MAKE){
			var fields =getFields();
			var dynamic=this instanceof Struct.Unmanaged<?> u&&u.isOverridingDynamicUnmanaged();
			sj.add((dynamic?"+":"")+fields.size()+" "+TextUtil.plural("field", fields.size()+(dynamic?10:0)));
		}
		
		return sj.toString();
	}
	
	@Override
	public Class<T> getType(){
		return type;
	}
	public Class<T> getConcreteType(){
		if(concreteType==null) resolveConcrete();
		return concreteType;
	}
	
	private void resolveConcrete(){
		isDefinition=UtilL.instanceOf(type, IOInstance.Def.class);
		if(IOInstance.Def.isDefinition(type)){
			waitForState(STATE_CONCRETE_TYPE);
		}else{
			concreteType=type;
		}
	}
	
	public String getFullName(){
		return type.getName();
	}
	
	public FieldSet<T> getInstanceFields(){
		if(instanceFields==null){
			instanceFields=FieldSet.of(getFields().stream().filter(e->!Utils.isVirtual(e, IO)));
		}
		return instanceFields;
	}
	
	public FieldSet<T> getFields(){
		waitForState(STATE_FIELD_MAKE);
		return Objects.requireNonNull(fields);
	}
	
	public static boolean canUnknownHavePointers(@NotNull Class<?> instanceClass){
		var s=ofUnknown(instanceClass, STATE_DONE);
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
			fields.stream().filter(f->!f.typeFlag(IOField.HAS_GENERATED_NAME))
		);
	}
	
	protected String instanceToString0(VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator, Stream<IOField<T, ?>> fields){
		var    prefix=start;
		String name  =null;
		if(!doShort){
			name=cleanName();
			if(Modifier.isStatic(getType().getModifiers())){
				var index=name.lastIndexOf('$');
				if(index!=-1) name=name.substring(index+1);
			}
			
			prefix=name+prefix;
		}
		
		Function<IOField<T, ?>, Optional<String>> fieldMapper=field->{
			if(SupportedPrimitive.get(field.getType()).orElse(null)==SupportedPrimitive.BOOLEAN){
				Boolean val=(Boolean)field.get(ioPool, instance);
				if(val!=null){
					return val?Optional.of(field.getName()):Optional.empty();
				}
			}
			
			Optional<String> valStr;
			if(field.getNullability()==NOT_NULL&&field.isNull(ioPool, instance)){
				valStr=Optional.of("<UNINITIALIZED>");
			}else{
				try{
					valStr=field.instanceToString(ioPool, instance, doShort||TextUtil.USE_SHORT_IN_COLLECTIONS);
				}catch(Throwable e){
					valStr=Optional.of("CORRUPTED: "+e.getMessage());
				}
			}
			
			return valStr.map(value->field.getName()+fieldValueSeparator+value);
		};
		var str=fields.map(fieldMapper)
		              .filter(Optional::isPresent).map(Optional::get)
		              .collect(Collectors.joining(fieldSeparator, prefix, end));
		
		if(!doShort){
			if(str.equals(prefix+end)) return name;
		}
		return str;
	}
	
	@Override
	public NewObj.Instance<T> emptyConstructor(){
		if(emptyConstructor==null){
			findEmptyConstructor();
			if(emptyConstructor==null){
				throw new UnsupportedOperationException();
			}
		}
		return Objects.requireNonNull(emptyConstructor);
	}
	
	private void findEmptyConstructor(){
		if(getType().isAnnotationPresent(NoDefaultConstructor.class)){
			throw new UnsupportedOperationException("NoDefaultConstructor is present");
		}
		if(!(this instanceof Struct.Unmanaged)){
			emptyConstructor=Access.findConstructor(getConcreteType(), NewObj.Instance.class);
		}
	}
	
	public boolean hasInvalidInitialNulls(){
		if(invalidInitialNulls==-1){
			if(this instanceof Unmanaged){
				invalidInitialNulls=(byte)0;
				return false;
			}
			
			waitForStateDone();
			
			boolean inv=false;
			if(fields.unpackedStream().anyMatch(f->f.getNullability()==NOT_NULL)){
				var obj =make();
				var pool=allocVirtualVarPool(IO);
				inv=fields.unpackedStream()
				          .filter(f->f.getNullability()==NOT_NULL)
				          .anyMatch(f->f.isNull(pool, obj));
			}
			invalidInitialNulls=(byte)(inv?1:0);
			return inv;
		}
		return invalidInitialNulls==1;
	}
	
	@Nullable
	public VarPool<T> allocVirtualVarPool(StoragePool pool){
		waitForStateDone();
		if(hasPools==null||!hasPools[pool.ordinal()]) return null;
		return new VarPool.GeneralVarArray<>(this, pool);
	}
	
	public GenericContext describeGenerics(TypeLink def){
		return new GenericContext.Deferred(()->{
			var params=getType().getTypeParameters();
			//noinspection unchecked
			var types=(Map.Entry<String, Type>[])new Map.Entry<?, ?>[params.length];
			for(int i=0;i<params.length;i++){
				types[i]=Map.entry(params[i].getName(), def.genericArg(i, null));
			}
			return new GenericContext.MapConstant(Map.ofEntries(types));
		});
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof Struct<?> that&&
		       equals(that);
	}
	
	public boolean equals(Struct<?> o){
		return this==o||
		       o!=null&&
		       type.equals(o.type);
	}
	
	@Override
	public int hashCode(){
		if(hash==-1) calcHash();
		return hash;
	}
	private void calcHash(){
		var h=getFullName().hashCode();
		hash=h==-1?0:h;
	}
	
	private final Map<FieldSet<T>, Struct<T>> partialCache=Collections.synchronizedMap(new HashMap<>());
	
	public boolean isDefinition(){
		waitForState(STATE_CONCRETE_TYPE);
		return isDefinition;
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Struct<T> partialImplementation(FieldSet<T> fields){
		//synchronized get put pattern because makeimpl is thread safe
		var cached=partialCache.get(fields);
		if(cached!=null) return cached;
		
		var impl=makeImpl((FieldSet)fields);
		partialCache.put(fields, impl);
		
		return impl;
	}
	@SuppressWarnings({"unchecked"})
	private <E extends IOInstance.Def<E>> Struct<E> makeImpl(FieldSet<E> f){
		if(!isDefinition()){
			throw new UnsupportedOperationException();
		}
		var typ=(Class<E>)getType();
		if(!typ.isInterface()){
			typ=(Class<E>)IOInstance.Def.unmap((Class<E>)type).orElseThrow();
		}
		var names=f.stream().map(IOField::getName).collect(Collectors.toUnmodifiableSet());
		var impl =IOInstance.Def.partialImplementation(typ, names);
		return Struct.of(impl);
	}
}
