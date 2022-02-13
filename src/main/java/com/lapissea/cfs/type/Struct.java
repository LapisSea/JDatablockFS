package com.lapissea.cfs.type;

import com.lapissea.cfs.ConsoleColors;
import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.compilation.FieldCompiler;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.util.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class Struct<T extends IOInstance<T>>{
	
	public interface Pool<T extends IOInstance<T>>{
		
		class StructArray<T extends IOInstance<T>> implements Pool<T>{
			
			private final Struct<T> typ;
			private final Object[]  pool;
			public StructArray(Struct<T> typ, VirtualFieldDefinition.StoragePool pool){
				this.typ=typ;
				if(pool==VirtualFieldDefinition.StoragePool.NONE) throw new IllegalArgumentException();
				var sizes=typ.poolSizes;
				if(sizes==null) this.pool=null;
				else{
					var count=sizes[pool.ordinal()];
					this.pool=count==0?null:new Object[count];
				}
			}
			
			@Override
			public void set(VirtualAccessor<T> accessor, Object value){
				protectAccessor(accessor);
				int index=accessor.getAccessIndex();
				pool[index]=value;
			}
			@Override
			public Object get(VirtualAccessor<T> accessor){
				protectAccessor(accessor);
				int index=accessor.getAccessIndex();
				return pool[index];
			}
			
			private void protectAccessor(VirtualAccessor<T> accessor){
				if(DEBUG_VALIDATION){
					if(accessor.getDeclaringStruct()!=typ){
						throw new IllegalArgumentException(accessor.getDeclaringStruct()+" != "+typ);
					}
				}
			}
		}
		
		void set(VirtualAccessor<T> accessor, Object value);
		Object get(VirtualAccessor<T> accessor);
	}
	
	public static class Unmanaged<T extends IOInstance.Unmanaged<T>> extends Struct<T>{
		
		public interface Constr<T>{
			T create(DataProvider provider, Reference reference, TypeLink type) throws IOException;
		}
		
		public static Unmanaged<?> thisClass(){
			return ofUnknown(getStack(s->s.skip(2)));
		}
		
		public static Unmanaged<?> ofUnknown(@NotNull Type instanceType){
			return ofUnknown(Utils.typeToRaw(instanceType));
		}
		
		@SuppressWarnings({"unchecked", "rawtypes"})
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
		
		private       Constr<T> unmanagedConstructor;
		private final boolean   overridingDynamicUnmanaged;
		
		public Unmanaged(Class<T> type){
			super(type);
			overridingDynamicUnmanaged=checkOverridingUnmanaged();
		}
		
		private boolean checkOverridingUnmanaged(){
			boolean u;
			try{
				u=!getType().getMethod("listDynamicUnmanagedFields").getDeclaringClass().equals(IOInstance.Unmanaged.class);
			}catch(NoSuchMethodException e){
				throw new RuntimeException(e);
			}
			return u;
		}
		
		public boolean isOverridingDynamicUnmanaged(){
			return overridingDynamicUnmanaged;
		}
		
		public Constr<T> requireUnmanagedConstructor(){
			if(unmanagedConstructor==null){
				unmanagedConstructor=Access.findConstructor(getType(), Constr.class, Access.getFunctionalMethod(Constr.class).getParameterTypes());
			}
			return unmanagedConstructor;
		}
		
		@Deprecated
		@Override
		public Supplier<T> requireEmptyConstructor(){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public String instanceToString(Pool<T> ioPool, T instance, boolean doShort){
			StringBuilder sb=new StringBuilder();
			if(!doShort) sb.append(getType().getSimpleName());
			sb.append('{');
			boolean comma=false;
			for(var field : getFields()){
				var str=field.instanceToString(ioPool, instance, doShort);
				if(str==null) continue;
				
				if(comma) sb.append(", ");
				
				sb.append(field.getName()).append("=").append(str);
				comma=true;
			}
			var iter=instance.listDynamicUnmanagedFields().iterator();
			while(iter.hasNext()){
				var field=iter.next();
				var str  =field.instanceToString(ioPool, instance, doShort);
				if(str==null) continue;
				
				if(comma) sb.append(", ");
				
				sb.append(field.getName()).append("=").append(str);
				comma=true;
			}
			return sb.append('}').toString();
		}
	}
	
	private static final Lock STRUCT_CACHE_LOCK=new ReentrantLock();
	
	private static final Map<Class<?>, Struct<?>> STRUCT_CACHE  =new WeakValueHashMap<>();
	private static final Map<Class<?>, Thread>    STRUCT_COMPILE=new ConcurrentHashMap<>();
	
	
	private static Class<?> getStack(Function<Stream<StackWalker.StackFrame>, Stream<StackWalker.StackFrame>> stream){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s->stream.apply(s).findFirst().orElseThrow().getDeclaringClass());
	}
	
	public static Struct<?> thisClass(){
		return ofUnknown(getStack(s->s.skip(2)));
	}
	
	public static Struct<?> ofUnknown(@NotNull Type instanceType){
		return ofUnknown(Utils.typeToRaw(instanceType));
	}
	
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static Struct<?> ofUnknown(@NotNull Class<?> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		if(!UtilL.instanceOf(instanceClass, IOInstance.class)){
			throw new IllegalArgumentException(instanceClass.getName()+" is not an "+IOInstance.class.getSimpleName());
		}
		
		return of((Class<? extends IOInstance>)instanceClass);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>> Struct<T> of(Class<T> instanceClass){
		Objects.requireNonNull(instanceClass);
		
		Struct<T> cached=getCached(instanceClass);
		if(cached!=null) return cached;
		
		if(UtilL.instanceOf(instanceClass, IOInstance.Unmanaged.class)){
			return (Struct<T>)Unmanaged.ofUnknown(instanceClass);
		}
		return compile(instanceClass, t->{
			if(!UtilL.instanceOf(t, IOInstance.class)) throw new ClassCastException(t.getName()+" is not an "+IOInstance.class.getSimpleName());
			if(UtilL.instanceOf(t, IOInstance.Unmanaged.class)) throw new ClassCastException(t.getName()+" is unmanaged!");
			return new Struct<>(t);
		});
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>, S extends Struct<T>> S compile(Class<T> instanceClass, Function<Class<T>, S> newStruct){
		if(Modifier.isAbstract(instanceClass.getModifiers())){
			throw new IllegalArgumentException("Can not compile "+instanceClass.getName()+" because it is abstract");
		}
		
		Thread thread=STRUCT_COMPILE.get(instanceClass);
		if(thread!=null&&thread==Thread.currentThread()){
			throw new MalformedStructLayout("Recursive struct compilation");
		}
		
		S struct;
		try{
			STRUCT_CACHE_LOCK.lock();
			//If class was compiled in another thread this should early exit
			var existing=STRUCT_CACHE.get(instanceClass);
			if(existing!=null) return (S)existing;
			STRUCT_COMPILE.put(instanceClass, Thread.currentThread());
			
			struct=newStruct.apply(instanceClass);
			
			if(GlobalConfig.PRINT_COMPILATION){
				LogUtil.println(ConsoleColors.GREEN_BRIGHT+TextUtil.toTable("Compiled: "+struct.getType().getName(), struct.getFields())+ConsoleColors.RESET);
			}
			
			STRUCT_CACHE.put(instanceClass, struct);
			return struct;
		}catch(Throwable e){
			throw new MalformedStructLayout("Failed to compile "+instanceClass.getName(), e);
		}finally{
			STRUCT_COMPILE.remove(instanceClass);
			STRUCT_CACHE_LOCK.unlock();
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Struct<T> getCached(Class<T> instanceClass){
		try{
			STRUCT_CACHE_LOCK.lock();
			return (Struct<T>)STRUCT_CACHE.get(instanceClass);
		}finally{
			STRUCT_CACHE_LOCK.unlock();
		}
	}
	
	private final Class<T> type;
	
	private final FieldSet<T> fields;
	private final int[]       poolSizes;
	
	private Supplier<T> emptyConstructor;
	
	
	public Struct(Class<T> type){
		this.type=type;
		this.fields=computeFields();
		this.fields.forEach(IOField::init);
		poolSizes=calcPoolSizes();
	}
	
	private FieldSet<T> computeFields(){
		FieldSet<T> fields=FieldCompiler.create().compile(this);
		if(!(this instanceof Unmanaged<?> unmanaged)){
			return fields;
		}
		
		//noinspection unchecked
		var staticFields=(FieldSet<T>)FieldCompiler.create().compileStaticUnmanaged(unmanaged);
		return FieldSet.of(Stream.concat(fields.stream(), staticFields.stream()));
	}
	
	private int[] calcPoolSizes(){
		var vPools=fields.stream().map(IOField::getAccessor).filter(f->f instanceof VirtualAccessor).map(c->((VirtualAccessor<T>)c).getStoragePool()).toList();
		if(vPools.isEmpty()) return null;
		var poolSizes=new int[VirtualFieldDefinition.StoragePool.values().length];
		for(var vPool : vPools){
			poolSizes[vPool.ordinal()]++;
		}
		return poolSizes;
	}
	
	@Override
	public String toString(){
		return getType().getSimpleName()+"{}";
	}
	public Class<T> getType(){
		return type;
	}
	
	public FieldSet<T> getFields(){
		return fields;
	}
	public IOField<T, ?> toIOField(Field field){
		if(field.getDeclaringClass()!=getType()) throw new IllegalArgumentException();
		return getFields().byName(field.getName()).orElseThrow();
	}
	
	public String instanceToString(Pool<T> ioPool, T instance, boolean doShort){
		StringBuilder sb=new StringBuilder();
		if(!doShort){
			var simple=getType().getSimpleName();
			var index =simple.lastIndexOf('$');
			if(index!=-1) simple=simple.substring(index+1);
			
			sb.append(simple);
		}
		
		var fields=new ArrayList<>(this.fields);
		fields.removeIf(toRem->toRem.getName().contains(IOFieldTools.GENERATED_FIELD_SEPARATOR));
		
		sb.append('{');
		boolean comma=false;
		for(var field : fields){
			String str;
			try{
				str=field.instanceToString(ioPool, instance, doShort||TextUtil.USE_SHORT_IN_COLLECTIONS);
			}catch(FieldIsNullException e){
				str="<UNINITIALIZED>";
			}
			
			if(str==null) continue;
			
			if(comma) sb.append(", ");
			
			sb.append(field.getName()).append("=").append(str);
			comma=true;
		}
		sb.append('}');
		return sb.toString();
	}
	
	public Supplier<T> requireEmptyConstructor(){
		if(emptyConstructor==null) emptyConstructor=Access.findConstructor(getType(), Supplier.class);
		return emptyConstructor;
	}
	
	@Nullable
	public Pool<T> allocVirtualVarPool(VirtualFieldDefinition.StoragePool pool){
		return new Pool.StructArray<>(this, pool);
	}
	
	public GenericContext describeGenerics(TypeLink def){
		return new GenericContext.Deferred(()->{
			var parms=getClass().getTypeParameters();
			var types=IntStream.range(0, parms.length)
			                   .boxed()
			                   .collect(Collectors.toMap(i->parms[i].getName(), i->def.arg(i).generic(null)));
			return new GenericContext.MapConstant(types);
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
		return type.getName().hashCode();
	}
}
