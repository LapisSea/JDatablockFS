package com.lapissea.cfs.type;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.compilation.FieldCompiler;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.util.*;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class Struct<T extends IOInstance<T>>{
	
	public static class Unmanaged<T extends IOInstance.Unmanaged<T>> extends Struct<T>{
		
		public interface Constr<T>{
			T create(ChunkDataProvider provider, Reference reference, TypeDefinition data);
		}
		
		public static Unmanaged<?> thisClass(){
			return ofUnknown(getStack(s->s.skip(1)));
		}
		
		public static Unmanaged<?> ofUnknown(@NotNull Class<?> instanceClass){
			Objects.requireNonNull(instanceClass);
			
			if(!UtilL.instanceOf(instanceClass, IOInstance.class)){
				throw new IllegalArgumentException(instanceClass.getName()+" is not an "+IOInstance.class.getSimpleName());
			}
			
			return ofUnmanaged((Class<? extends IOInstance.Unmanaged>)instanceClass);
		}
		
		public static <T extends IOInstance.Unmanaged<T>> Unmanaged<T> ofUnmanaged(Class<T> instanceClass){
			Objects.requireNonNull(instanceClass);
			
			Unmanaged<T> cached=(Unmanaged<T>)getCached(instanceClass);
			if(cached!=null) return cached;
			
			
			return compile(instanceClass, Unmanaged::new);
		}
		
		private Constr<T> unmanagedConstructor;
		
		public Unmanaged(Class<T> type){
			super(type);
		}
		public Constr<T> requireUnmanagedConstructor(){
			if(unmanagedConstructor==null){
				unmanagedConstructor=Utils.findConstructor(getType(), Constr.class, Utils.getFunctionalMethod(Constr.class).getParameterTypes());
			}
			return unmanagedConstructor;
		}
		
		@Deprecated
		@Override
		public Supplier<T> requireEmptyConstructor(){
			throw new UnsupportedOperationException();
		}
	}
	
	private static final Lock STRUCT_CACHE_LOCK=new ReentrantLock();
	
	private static final Map<Class<?>, Struct<?>> STRUCT_CACHE  =new WeakValueHashMap<>();
	private static final Map<Class<?>, Thread>    STRUCT_COMPILE=Collections.synchronizedMap(new HashMap<>());
	
	
	private static Class<?> getStack(Function<Stream<StackWalker.StackFrame>, Stream<StackWalker.StackFrame>> stream){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s->stream.apply(s).findFirst().orElseThrow().getDeclaringClass());
	}
	
	public static Struct<?> thisClass(){
		return ofUnknown(getStack(s->s.skip(1)));
	}
	
	public static Struct<?> ofUnknown(@NotNull Class<?> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		if(!UtilL.instanceOf(instanceClass, IOInstance.class)){
			throw new IllegalArgumentException(instanceClass.getName()+" is not an "+IOInstance.class.getSimpleName());
		}
		
		return of((Class<? extends IOInstance>)instanceClass);
	}
	
	public static <T extends IOInstance<T>> Struct<T> of(Class<T> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		Struct<T> cached=getCached(instanceClass);
		if(cached!=null) return cached;
		
		if(UtilL.instanceOf(instanceClass, IOInstance.Unmanaged.class)){
			return (Struct<T>)Unmanaged.ofUnknown(instanceClass);
		}
		return compile(instanceClass, t->{
			if(UtilL.instanceOf(t, IOInstance.Unmanaged.class)) throw new ClassCastException();
			return new Struct<>(t);
		});
	}
	
	private static <T extends IOInstance<T>, S extends Struct<T>> S compile(Class<T> instanceClass, Function<Class<T>, S> newStruct){
		if(Modifier.isAbstract(instanceClass.getModifiers())) throw new IllegalArgumentException("Can not compile "+instanceClass.getName()+" because it is abstract");
		
		Thread thread=STRUCT_COMPILE.get(instanceClass);
		if(thread!=null){
			if(thread==Thread.currentThread()) throw new MalformedStructLayout("Recursive struct compilation");
			UtilL.sleepWhile(()->STRUCT_COMPILE.containsKey(instanceClass));
			return Objects.requireNonNull((S)getCached(instanceClass));
		}
		
		try{
			STRUCT_CACHE_LOCK.lock();
			
			STRUCT_COMPILE.put(instanceClass, Thread.currentThread());
			
			S struct=newStruct.apply(instanceClass);
			STRUCT_CACHE.put(instanceClass, struct);
			
			if(GlobalConfig.PRINT_COMPILATION){
				LogUtil.println(ConsoleColors.GREEN_BRIGHT+TextUtil.toTable("Compiled: "+struct.getType().getName(), struct.getFields())+ConsoleColors.RESET);
			}
			
			return struct;
		}finally{
			STRUCT_COMPILE.remove(instanceClass);
			STRUCT_CACHE_LOCK.unlock();
		}
	}
	private static <T extends IOInstance<T>> Struct<T> getCached(Class<T> instanceClass){
		try{
			STRUCT_CACHE_LOCK.lock();
			return (Struct<T>)STRUCT_CACHE.get(instanceClass);
		}finally{
			STRUCT_CACHE_LOCK.unlock();
		}
	}
	
	private final Class<T> type;
	
	private final FieldSet<T, ?> fields;
	private final FieldSet<T, ?> virtualFields;
	private final int[]          poolSizes;
	
	private Supplier<T> emptyConstructor;
	
	public Struct(Class<T> type){
		this.type=type;
		fields=FieldCompiler.create().compile(this);
		fields.forEach(IOField::init);
		virtualFields=new FieldSet<>(fields.stream().filter(f->f.getAccessor() instanceof VirtualAccessor));
		
		var pools=VirtualFieldDefinition.StoragePool.values();
		poolSizes=new int[pools.length];
		for(var pool : pools){
			poolSizes[pool.ordinal()]=(int)getVirtualFields().stream().filter(c->((VirtualAccessor<T>)c.getAccessor()).getStoragePool()==pool).count();
		}
	}
	
	@Override
	public String toString(){
		return getType().getSimpleName()+"{}";
	}
	public Class<T> getType(){
		return type;
	}
	
	public FieldSet<T, ?> getFields(){
		return fields;
	}
	public FieldSet<T, ?> getVirtualFields(){
		return virtualFields;
	}
	
	public String instanceToString(T instance, boolean doShort){
		StringBuilder sb=new StringBuilder();
		if(!doShort) sb.append(type.getSimpleName());
		sb.append('{');
		boolean comma=false;
		for(var field : fields){
			var str=field.instanceToString(instance, doShort);
			if(str==null) continue;
			
			if(comma) sb.append(", ");
			
			sb.append(field.getName()).append("=").append(str);
			comma=true;
		}
		return sb.append('}').toString();
	}
	
	public Supplier<T> requireEmptyConstructor(){
		if(emptyConstructor==null) emptyConstructor=Utils.findConstructor(getType(), Supplier.class);
		return emptyConstructor;
	}
	
	@Nullable
	public Object[] allocVirtualVarPool(VirtualFieldDefinition.StoragePool pool){
		if(pool==VirtualFieldDefinition.StoragePool.NONE) throw new IllegalArgumentException();
		var count=poolSizes[pool.ordinal()];
		return count==0?null:new Object[count];
	}
}
